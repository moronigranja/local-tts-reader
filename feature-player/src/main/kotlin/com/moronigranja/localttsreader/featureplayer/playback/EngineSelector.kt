package com.moronigranja.localttsreader.featureplayer.playback

import com.moronigranja.localttsreader.persistence.AppSettings
import com.moronigranja.localttsreader.persistence.SettingsStore
import com.moronigranja.localttsreader.tts.TTSEngine
import dagger.Lazy
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * C1.5/decisions #102: the playback engine seam. PlaybackService and
 * PregenWorker keep one neutral entry point; this selector returns the
 * zero-download [SystemTtsEngine] (bound app-side under
 * `@Named("system_tts")`, realized lazily so a kokoro-only session never
 * touches the device TTS) when the user opted into the degraded device voice,
 * else the ready [KokoroRuntime] engine.
 *
 * The selected engine changes only via [AppSettings] (the Settings screen's
 * "Speech engine" row / setup opt-in), and the service re-reads it on every
 * play/resume — no restart needed.
 */
@Singleton
class EngineSelector
    @Inject
    constructor(
        private val runtime: KokoroRuntime,
        @Named("system_tts") private val systemTts: Lazy<TTSEngine>,
        private val settings: AppSettings,
    ) {
        /** True when the degraded system voice is selected (drives the
         * PlayerCard's "Device voice" pill via PlaybackUiState.degraded). */
        val isDegraded: Boolean
            get() = settings.state.value.ttsEngine == SettingsStore.SYSTEM_TTS_ENGINE

        /** The active engine, or null when its prerequisites are missing. */
        fun engine(): TTSEngine? = if (isDegraded) systemTts.get() else runtime.engine()

        /** Missing-prerequisite reason for the non-degraded engine; null in
         * degraded mode (system synthesis failures surface per passage). */
        val failureReason: String?
            get() = if (isDegraded) null else runtime.failureReason
    }
