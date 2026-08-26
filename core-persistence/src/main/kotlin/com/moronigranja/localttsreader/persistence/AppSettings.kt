package com.moronigranja.localttsreader.persistence

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The playback/UI hot-path mirror of [SettingsStore] (V1): the play loop and
 * the theme read plain fields + one flow — no database on the audio path —
 * while every write goes through the store and lands everywhere. Values
 * default to the store's defaults until [reload] runs (app start, settings
 * open); [reload] is idempotent and cheap.
 *
 * Pure JVM: Hilt annotations only (core-persistence has no Android deps).
 */
@Singleton
class AppSettings @Inject constructor(
    private val store: SettingsStore,
) {

    @Volatile var voice: String = SettingsStore.DEFAULT_VOICE
        private set

    @Volatile var matchThreshold: Double = SettingsStore.DEFAULT_MATCH_THRESHOLD
        private set

    @Volatile var ocrLanguages: List<String> = listOf(SettingsStore.DEFAULT_OCR_LANGUAGE)
        private set

    @Volatile var favoriteVoices: List<String> = emptyList()
        private set

    private val _themeMode = MutableStateFlow(ThemeMode.SYSTEM)
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    suspend fun reload() {
        voice = store.voice()
        _themeMode.value = store.themeMode()
        matchThreshold = store.matchThreshold()
        ocrLanguages = store.ocrLanguages()
        favoriteVoices = store.favoriteVoices()
    }

    suspend fun setVoice(value: String) {
        store.setVoice(value)
        voice = value
    }

    suspend fun setThemeMode(value: ThemeMode) {
        store.setThemeMode(value)
        _themeMode.value = value
    }

    suspend fun setMatchThreshold(value: Double) {
        store.setMatchThreshold(value)
        matchThreshold = value
    }

    suspend fun setOcrLanguages(value: List<String>) {
        store.setOcrLanguages(value)
        ocrLanguages = value
    }

    suspend fun setFavoriteVoices(value: List<String>) {
        store.setFavoriteVoices(value)
        favoriteVoices = value
    }

    suspend fun toggleFavorite(voiceName: String) {
        val next = if (voiceName in favoriteVoices) {
            favoriteVoices - voiceName
        } else {
            favoriteVoices + voiceName
        }
        setFavoriteVoices(next)
    }
}
