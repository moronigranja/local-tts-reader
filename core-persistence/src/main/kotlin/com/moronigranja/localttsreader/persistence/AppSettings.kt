package com.moronigranja.localttsreader.persistence

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The settings mirror every UI/playback surface reads (V1): one immutable
 * [Snapshot] in a StateFlow, updated on every write and on [reload] (app
 * start, share entry, service command). Consumers observe the flow — the
 * theme radio reflects a change the moment it lands — while the hot paths
 * read `state.value.<field>` with no database and no polling.
 *
 * Pure JVM: Hilt annotations only (core-persistence has no Android deps).
 */
@Singleton
class AppSettings @Inject constructor(
    private val store: SettingsStore,
) {

    data class Snapshot(
        val threshold: Double = SettingsStore.DEFAULT_MATCH_THRESHOLD,
        val voice: String = SettingsStore.DEFAULT_VOICE,
        val favorites: List<String> = emptyList(),
        val theme: ThemeMode = ThemeMode.SYSTEM,
        val ocrLanguages: List<String> = listOf(SettingsStore.DEFAULT_OCR_LANGUAGE),
        /** The speech engine id (C1.5/decisions #102): kokoro-82m default,
         * system-tts degraded fallback. */
        val ttsEngine: String = SettingsStore.DEFAULT_TTS_ENGINE,
    )

    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    suspend fun reload() {
        _state.value = Snapshot(
            threshold = store.matchThreshold(),
            voice = store.voice(),
            favorites = store.favoriteVoices(),
            theme = store.themeMode(),
            ocrLanguages = store.ocrLanguages(),
            ttsEngine = store.ttsEngine(),
        )
    }

    suspend fun setVoice(value: String) {
        store.setVoice(value)
        _state.value = _state.value.copy(voice = value)
    }

    suspend fun setThemeMode(value: ThemeMode) {
        store.setThemeMode(value)
        _state.value = _state.value.copy(theme = value)
    }

    suspend fun setMatchThreshold(value: Double) {
        store.setMatchThreshold(value)
        _state.value = _state.value.copy(threshold = value)
    }

    suspend fun setOcrLanguages(value: List<String>) {
        store.setOcrLanguages(value)
        _state.value = _state.value.copy(ocrLanguages = value)
    }

    suspend fun setFavoriteVoices(value: List<String>) {
        store.setFavoriteVoices(value)
        _state.value = _state.value.copy(favorites = value)
    }

    suspend fun toggleFavorite(voiceName: String) {
        val current = _state.value.favorites
        val next = if (voiceName in current) current - voiceName else current + voiceName
        setFavoriteVoices(next)
    }

    suspend fun setTtsEngine(value: String) {
        store.setTtsEngine(value)
        _state.value = _state.value.copy(ttsEngine = value)
    }
}
