package com.moronigranja.localttsreader.featuresettings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.moronigranja.localttsreader.featureocr.TessDataStager
import com.moronigranja.localttsreader.ocr.TrainedDataPacks
import com.moronigranja.localttsreader.persistence.AppSettings
import com.moronigranja.localttsreader.persistence.SettingsStore
import com.moronigranja.localttsreader.persistence.ThemeMode
import com.moronigranja.localttsreader.tts.DownloadOutcome
import com.moronigranja.localttsreader.tts.PackCache
import com.moronigranja.localttsreader.tts.PackRegistry
import com.moronigranja.localttsreader.tts.PackState
import com.moronigranja.localttsreader.tts.PackStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

 data class PackRow(
    val packId: String,
    val displayName: String,
    val sizeBytes: Long,
    val status: PackStatus,
    val progress: Double? = null, // 0..1 while downloading
    val error: String? = null,
    val staged: Boolean = false, // tessdata copied into the tess-two data dir
)

data class SettingsUiState(
    val packs: List<PackRow> = emptyList(),
    val voices: List<String> = emptyList(),
    val selectedVoice: String = SettingsStore.DEFAULT_VOICE,
    val favoriteVoices: Set<String> = emptySet(),
    val matchThreshold: Double = SettingsStore.DEFAULT_MATCH_THRESHOLD,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val ocrLanguages: List<String> = listOf(SettingsStore.DEFAULT_OCR_LANGUAGE),
    val espeakReady: Boolean = false,
    val espeakDetail: String = "",
)

/**
 * V1 settings: packs (engine/OCR, download + progress), voice picker +
 * favorites, match threshold, theme, OCR language selection. All writes go
 * through [AppSettings] (playback hot-path mirror); downloads go through the
 * [PackRegistry] (explicit, resumable, verified — decision #7); staged tess
 * data is copied into the tess-two data dir after each language download.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val registry: PackRegistry,
    private val cache: PackCache,
    private val settings: AppSettings,
    private val voiceCatalog: VoiceCatalog,
    private val espeak: EspeakBundleStatus,
    private val filesDir: File,
) : ViewModel() {

    private val progress = MutableStateFlow<Map<String, Double>>(emptyMap())
    private val errors = MutableStateFlow<Map<String, String>>(emptyMap())
    private val voices = MutableStateFlow<List<String>>(emptyList())

    private val core = combine(registry.packs, progress, errors, voices, settingsFlow()) { packs, prog, err, voices, prefs ->
        SettingsUiState(
            packs = packs.map { packRow(it, prog[it.pack.id], err[it.pack.id]) },
            voices = voices,
            selectedVoice = prefs.voice,
            favoriteVoices = prefs.favorites.toSet(),
            matchThreshold = prefs.threshold,
            themeMode = prefs.theme,
            ocrLanguages = prefs.ocrLanguages,
            espeakReady = espeak.ready,
            espeakDetail = espeak.detail,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState(espeakReady = espeak.ready, espeakDetail = espeak.detail))

    val state: StateFlow<SettingsUiState> = core

    init {
        viewModelScope.launch {
            discoverVoices()
        }
    }

    private fun settingsFlow() = kotlinx.coroutines.flow.flow {
        while (true) {
            settings.reload()
            emit(
                Prefs(
                    threshold = settings.matchThreshold,
                    voice = settings.voice,
                    favorites = settings.favoriteVoices,
                    theme = settings.themeMode.value,
                    ocrLanguages = settings.ocrLanguages,
                ),
            )
            kotlinx.coroutines.delay(REFRESH_MS)
        }
    }
    private data class Prefs(
        val threshold: Double,
        val voice: String,
        val favorites: List<String>,
        val theme: ThemeMode,
        val ocrLanguages: List<String>,
    )

    private fun packRow(pack: PackState, prog: Double?, err: String?): PackRow =
        PackRow(
            packId = pack.pack.id,
            displayName = pack.pack.displayName,
            sizeBytes = pack.pack.sizeBytes,
            status = pack.status,
            progress = prog,
            error = err,
            staged = pack.pack.engineId == TessEngineId && TessDataStager.isStaged(filesDir, pack.pack),
        )

    fun download(packId: String) {
        val isTess = registry.packs.value.firstOrNull { it.pack.id == packId }?.pack?.engineId == TessEngineId
        downloadInternal(packId, isTess)
    }

    private fun downloadInternal(packId: String, isTess: Boolean) {
        viewModelScope.launch {
            errors.update(packId, null)
            registry.download(packId) { done, total ->
                progress.value = progress.value + (packId to done.toDouble() / total)
            }.let { outcome ->
                progress.value = progress.value - packId
                when (outcome) {
                    is DownloadOutcome.Ready, is DownloadOutcome.AlreadyCached -> {
                        if (isTess) stageTess(packId)
                        if (packId == com.moronigranja.localttsreader.tts.kokoro.KokoroPacks.voices.id) {
                            voiceCatalog.invalidate()
                            discoverVoices()
                        }
                    }
                    is DownloadOutcome.Failed -> errors.update(packId, shortReason(outcome.reason))
                }
            }
        }
    }

    fun selectVoice(voice: String) = viewModelScope.launch { settings.setVoice(voice) }

    fun toggleFavorite(voice: String) = viewModelScope.launch { settings.toggleFavorite(voice) }

    fun setThreshold(value: Double) = viewModelScope.launch { settings.setMatchThreshold(value) }

    fun setTheme(mode: ThemeMode) = viewModelScope.launch { settings.setThemeMode(mode) }

    fun setOcrLanguage(lang: String, enable: Boolean) {
        viewModelScope.launch {
            val next = if (enable) (settings.ocrLanguages + lang).distinct() else settings.ocrLanguages - lang
            settings.setOcrLanguages(next)
        }
    }

    private suspend fun stageTess(packId: String) {
        val pack = registry.packs.value.firstOrNull { it.pack.id == packId }?.pack ?: return
        runCatching { TessDataStager.stage(filesDir, cache, pack) }
            .onFailure { errors.update(packId, "staging failed: ${it.message}") }
    }

    private suspend fun discoverVoices() {
        voices.value = voiceCatalog.names().sorted()
    }

    private fun shortReason(reason: com.moronigranja.localttsreader.tts.DownloadFailureReason): String = when (reason) {
        is com.moronigranja.localttsreader.tts.DownloadFailureReason.HttpStatus -> "HTTP ${reason.status}"
        is com.moronigranja.localttsreader.tts.DownloadFailureReason.IoError -> reason.message ?: "network error"
        is com.moronigranja.localttsreader.tts.DownloadFailureReason.CorruptContent -> "checksum mismatch"
        is com.moronigranja.localttsreader.tts.DownloadFailureReason.Incomplete -> "incomplete download"
    }

    private companion object {
        const val REFRESH_MS = 2_000L
        const val TessEngineId = "tess-two"
    }
}

private fun MutableStateFlow<Map<String, String>>.update(key: String, value: String?) {
    this.value = if (value == null) this.value - key else this.value + (key to value)
}

private operator fun Map<String, Double>.plus(entry: Pair<String, Double>): Map<String, Double> = this + entry
private operator fun Map<String, Double>.minus(key: String): Map<String, Double> = this - key
