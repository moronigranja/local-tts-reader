package com.moronigranja.localttsreader.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.moronigranja.localttsreader.ebook.EBookSource
import com.moronigranja.localttsreader.ebook.ImportCoordinator
import com.moronigranja.localttsreader.ebook.ImportOutcome
import com.moronigranja.localttsreader.model.LibraryStore
import com.moronigranja.localttsreader.persistence.AppSettings
import com.moronigranja.localttsreader.persistence.SettingsStore
import com.moronigranja.localttsreader.player.EspeakStager
import com.moronigranja.localttsreader.player.IoDispatcher
import com.moronigranja.localttsreader.tts.DownloadFailureReason
import com.moronigranja.localttsreader.tts.DownloadOutcome
import com.moronigranja.localttsreader.tts.PackCache
import com.moronigranja.localttsreader.tts.PackRegistry
import com.moronigranja.localttsreader.tts.PackState
import com.moronigranja.localttsreader.tts.PackStatus
import com.moronigranja.localttsreader.tts.VoiceCatalog
import com.moronigranja.localttsreader.tts.kokoro.KokoroPacks
import com.moronigranja.localttsreader.tts.kokoro.KokoroVoiceMeta
import com.moronigranja.localttsreader.tts.kokoro.KokoroVoiceMetadata
import com.moronigranja.localttsreader.tts.setup.SetupFacts
import com.moronigranja.localttsreader.tts.setup.SetupState
import com.moronigranja.localttsreader.tts.setup.StepKind
import com.moronigranja.localttsreader.tts.setup.StorageProbe
import com.moronigranja.localttsreader.ui.PlanPackRow
import com.moronigranja.localttsreader.ui.PlanPackStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Named

data class SetupUiState(
    val steps: List<StepKind> = emptyList(),
    /** The three required Kokoro packs, mapped for the shared plan card. */
    val packs: List<PlanPackRow> = emptyList(),
    /** Static 54-voice catalog — usable before any download. */
    val voices: List<KokoroVoiceMeta> = KokoroVoiceMetadata.all,
    val selectedVoice: String = SettingsStore.DEFAULT_VOICE,
    /** Sum of the three required packs' descriptor sizes — the plan's total. */
    val storageTotalBytes: Long = 0L,
    /** Sum of the not-yet-ready required packs' sizes — what still needs
     * space beyond what is already on disk. */
    val requiredBytes: Long = 0L,
    val availableBytes: Long = 0L,
    /** `availableBytes - requiredBytes` when negative (named on the plan
     * before any download starts, C1 acceptance leg 4). */
    val shortfallBytes: Long = 0L,
    val systemTtsOptedIn: Boolean = false,
    val importSummary: String? = null,
)

/**
 * C1.4: the first-run setup driver — the screen the app gate shows while
 * [SetupState] derives anything but COMPLETE. It owns the coordinated
 * download (progress/cancel/resume/retry via the registry), the storage
 * probe, the voice pick (persisted immediately via AppSettings), the
 * system-TTS opt-in (decisions #102) and the import hand-off (SAF →
 * [ImportCoordinator], app-injected — no feature-library VM is imported).
 *
 * Post-success hooks mirror SettingsVM's downloadInternal (espeak staging +
 * voice catalog invalidation), so Ready in setup equals Ready in Settings.
 */
@HiltViewModel
class SetupViewModel
    @Inject
    constructor(
        private val registry: PackRegistry,
        private val cache: PackCache,
        private val settings: AppSettings,
        private val voiceCatalog: VoiceCatalog,
        private val libraryStore: LibraryStore,
        @Named("app_files_dir") private val filesDir: File,
        private val storageProbe: StorageProbe,
        private val coordinator: ImportCoordinator,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val errors = MutableStateFlow<Map<String, String>>(emptyMap())
        private val stageTick = MutableStateFlow(0)
        private val importTick = MutableStateFlow(0)
        private val jobs = mutableMapOf<String, Job>()
        private var importSummaryValue: String? = null

        // `combine` types at most 5 flows — nest: (packs, errors, settings) then
        // books + the two ticks re-derive the checklist on every fact change.
        private val core =
            combine(
                combine(registry.packs, errors, settings.state) { packs, err, prefs ->
                    Triple(packs, err, prefs)
                },
                libraryStore.books,
                stageTick,
                importTick,
            ) { (packs, err, prefs), books, _, _ ->
                val required = REQUIRED_PACK_IDS.mapNotNull { id -> packs.firstOrNull { it.pack.id == id } }
                val requiredReady =
                    REQUIRED_PACK_IDS.all { id -> packs.firstOrNull { it.pack.id == id }?.status == PackStatus.Ready }
                val facts =
                    SetupFacts(
                        requiredPacksReady = requiredReady,
                        espeakStaged = EspeakStager.isStaged(filesDir),
                        voiceSelected = prefs.voice != SettingsStore.DEFAULT_VOICE,
                        bookCount = books.size,
                        systemTtsOptedIn = prefs.ttsEngine == SettingsStore.SYSTEM_TTS_ENGINE,
                    )
                val steps = SetupState.derive(facts)
                val requiredBytes = required.filter { it.status != PackStatus.Ready }.sumOf { it.pack.sizeBytes }
                val available = storageProbe.availableBytes()
                SetupUiState(
                    steps = steps,
                    packs = required.map { it.toPlanRow(err, filesDir) },
                    selectedVoice = prefs.voice,
                    voices = KokoroVoiceMetadata.all,
                    storageTotalBytes = required.sumOf { it.pack.sizeBytes },
                    requiredBytes = requiredBytes,
                    availableBytes = available,
                    shortfallBytes = (requiredBytes - available).coerceAtLeast(0L),
                    systemTtsOptedIn = prefs.ttsEngine == SettingsStore.SYSTEM_TTS_ENGINE,
                    importSummary = importSummaryValue,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SetupUiState())

        val state: StateFlow<SetupUiState> = core

        init {
            viewModelScope.launch { autoStageEspeak() }
        }

        /** A verified-but-unstaged espeak pack self-heals like Settings does. */
        private suspend fun autoStageEspeak() {
            val pack =
                registry.packs.value
                    .firstOrNull { it.pack.id == ESPEAK_PACK_ID }
                    ?.pack ?: return
            if (!EspeakStager.isStaged(filesDir) && cache.isVerified(pack)) stageEspeak(pack.id)
        }

        fun download(packId: String) {
            if (jobs.containsKey(packId)) return
            jobs[packId] =
                viewModelScope.launch {
                    errors.update(packId, null)
                    try {
                        val outcome = registry.download(packId) { _, _ -> }
                        when (outcome) {
                            is DownloadOutcome.Ready, is DownloadOutcome.AlreadyCached -> {
                                if (packId == ESPEAK_PACK_ID) stageEspeak(packId)
                                if (packId == KokoroPacks.voices.id) voiceCatalog.invalidate()
                            }
                            is DownloadOutcome.Failed -> errors.update(packId, shortReason(outcome.reason))
                        }
                    } catch (e: CancellationException) {
                        errors.update(packId, null) // cancelled — the `.part` survives for resume
                    } finally {
                        jobs.remove(packId)
                    }
                }
        }

        /** Cancels one pack's in-flight transfer; the `.part` survives → resume. */
        fun cancelDownload(packId: String) {
            jobs[packId]?.cancel()
        }

        fun retry(packId: String) = download(packId)

        fun chooseVoice(name: String) = viewModelScope.launch { settings.setVoice(name) }

        /** decisions #102: opt into the zero-download degraded device voice. */
        fun optInSystemTts() = viewModelScope.launch { settings.setTtsEngine(SettingsStore.SYSTEM_TTS_ENGINE) }

        /** SAF import hand-off — the contact LibraryScreen uses, driven here
         * against app-injected dependencies (no feature-library VM). */
        fun importBooks(sources: List<EBookSource>) {
            if (sources.isEmpty()) return
            importSummaryValue = null
            viewModelScope.launch {
                importTick.value += 1
                try {
                    val outcomes = withContext(ioDispatcher) { coordinator.importAll(sources) { _, _, _ -> } }
                    importSummaryValue = buildSummary(outcomes)
                } catch (e: CancellationException) {
                    throw e
                } finally {
                    importTick.value += 1
                }
            }
        }

        fun consumeImportSummary() {
            importSummaryValue = null
        }

        private fun buildSummary(outcomes: List<ImportOutcome>): String {
            var added = 0
            var unchanged = 0
            var failed = 0
            for (outcome in outcomes) {
                when (outcome) {
                    is ImportOutcome.Added -> added += 1
                    is ImportOutcome.Unchanged -> unchanged += 1
                    is ImportOutcome.Failed -> failed += 1
                }
            }
            return when {
                failed > 0 -> "$added imported · $unchanged unchanged · $failed failed"
                added > 0 -> "$added added · $unchanged unchanged"
                else -> "Nothing new to import"
            }
        }

        private fun shortReason(reason: DownloadFailureReason): String =
            when (reason) {
                is DownloadFailureReason.HttpStatus -> "HTTP ${reason.status}"
                is DownloadFailureReason.IoError -> reason.message ?: "network error"
                is DownloadFailureReason.CorruptContent -> "checksum mismatch"
                is DownloadFailureReason.Incomplete -> "incomplete download"
            }

        private suspend fun stageEspeak(packId: String) {
            val pack =
                registry.packs.value
                    .firstOrNull { it.pack.id == packId }
                    ?.pack ?: return
            runCatching { EspeakStager.stage(filesDir, cache, pack) }
                .onSuccess { staged -> if (staged) stageTick.value += 1 }
                .onFailure { errors.update(packId, "staging failed: ${it.message}") }
        }

        private fun PackState.toPlanRow(
            err: Map<String, String>,
            filesDir: File,
        ): PlanPackRow {
            val status =
                when (val s = status) {
                    is PackStatus.Downloading -> PlanPackStatus.Downloading(s.downloadedBytes, s.totalBytes)
                    PackStatus.Ready -> PlanPackStatus.Ready
                    is PackStatus.Failed -> PlanPackStatus.Failed(err[pack.id] ?: shortReason(s.reason))
                    PackStatus.NotDownloaded -> PlanPackStatus.NotDownloaded
                }
            return PlanPackRow(
                packId = pack.id,
                displayName = pack.displayName,
                sizeBytes = pack.sizeBytes,
                status = status,
                staged = pack.id == ESPEAK_PACK_ID && EspeakStager.isStaged(filesDir),
            )
        }

        private companion object {
            const val ESPEAK_PACK_ID = "espeak-ng"
            val REQUIRED_PACK_IDS =
                listOf(
                    KokoroPacks.model.id,
                    KokoroPacks.voices.id,
                    KokoroPacks.espeak.id,
                )
        }
    }

private fun MutableStateFlow<Map<String, String>>.update(
    key: String,
    value: String?,
) {
    this.value = if (value == null) this.value - key else this.value + (key to value)
}
