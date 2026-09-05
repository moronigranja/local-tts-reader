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
        /** Realtime-capability tri-state (item 8, D2): `true` = the engine
         * generates ≥ as fast as it plays (wall ≤ audio over ≥ 10 s of
         * rendered audio), `false` = slower, `null` = unmeasured (fewer than
         * 10 s of audio samples accumulated; today's behavior keeps). */
        val realtimeCapable: Boolean? = null,
    )

    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    suspend fun reload() {
        _state.value =
            Snapshot(
                threshold = store.matchThreshold(),
                voice = store.voice(),
                favorites = store.favoriteVoices(),
                theme = store.themeMode(),
                ocrLanguages = store.ocrLanguages(),
                ttsEngine = store.ttsEngine(),
                realtimeCapable = deriveRtf(store.rtfWallMs(), store.rtfAudioMs()),
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

    /** Records one synthesis sample (item 8): ACCUMULATES wall and audio
     * into the persisted pair — every Preview and live passage contributes,
     * and the tri-state flips the moment the ≥ 10 s gate is crossed. */
    suspend fun setRtfSample(
        wallMs: Long,
        audioMs: Long,
    ) {
        store.putRtf(store.rtfWallMs() + wallMs, store.rtfAudioMs() + audioMs)
        _state.value = _state.value.copy(realtimeCapable = deriveRtf(store.rtfWallMs(), store.rtfAudioMs()))
    }

    /** The derivation table (null under the 10 s gate, true ≤ 1.0 realtime,
     * false slower). */
    private fun deriveRtf(
        wallMs: Long,
        audioMs: Long,
    ): Boolean? =
        when {
            // #93: short probes overstate RTF — no verdict below 10 s of audio.
            audioMs < RTF_MIN_AUDIO_MS -> null
            wallMs <= 0L -> null
            wallMs <= audioMs -> true
            else -> false
        }

    private companion object {
        /** The realtime gate (item 8/#93): verdicts need ≥ 10 s of rendered
         * audio, else the tri-state stays unmeasured. */
        const val RTF_MIN_AUDIO_MS = 10_000L
    }
}
