package com.moronigranja.localttsreader.persistence

/**
 * Typed accessors over the generic `settings` table (V1). Unknown or corrupt
 * stored values fall back to defaults — a missing row and a bad value are
 * equivalent, and both are user-recoverable from the settings UI.
 *
 * Regular (non-suspend) accessors are for the playback hot path: the app
 * supplies a cached [com.moronigranja.localttsreader.featuresettings
 * .AppSettings] singleton that mirrors these keys in memory; these direct
 * calls are the source of truth underneath it.
 */
class SettingsStore(private val settingsDao: SettingsDao) {

    /** Recall floor for share matches (decisions #3), default 0.6. */
    suspend fun matchThreshold(): Double =
        settingsDao.get(KEY_MATCH_THRESHOLD)?.toDoubleOrNull() ?: DEFAULT_MATCH_THRESHOLD

    suspend fun setMatchThreshold(value: Double) {
        require(value in 0.0..1.0) { "match threshold must be within 0..1, was $value" }
        settingsDao.put(SettingEntity(KEY_MATCH_THRESHOLD, value.toString()))
    }

    /** The Kokoro voice played by default (V1 voice picker), default af_heart. */
    suspend fun voice(): String =
        settingsDao.get(KEY_VOICE)?.takeIf { it.isNotBlank() } ?: DEFAULT_VOICE

    suspend fun setVoice(value: String) {
        require(value.isNotBlank()) { "voice must not be blank" }
        settingsDao.put(SettingEntity(KEY_VOICE, value))
    }

    /** The user's starred voices, in picker order (V1 favorites). */
    suspend fun favoriteVoices(): List<String> =
        settingsDao.get(KEY_FAVORITE_VOICES)?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()

    suspend fun setFavoriteVoices(value: List<String>) {
        require(value.all { it.isNotBlank() }) { "favorite voices must not be blank" }
        settingsDao.put(SettingEntity(KEY_FAVORITE_VOICES, value.joinToString("\n")))
    }

    /** UI theme: system / light / dark (V1). */
    suspend fun themeMode(): ThemeMode =
        ThemeMode.from(settingsDao.get(KEY_THEME_MODE))

    suspend fun setThemeMode(value: ThemeMode) {
        settingsDao.put(SettingEntity(KEY_THEME_MODE, value.key))
    }

    /** Installed OCR languages for the share flow (S1/S2), default English only. */
    suspend fun ocrLanguages(): List<String> =
        settingsDao.get(KEY_OCR_LANGUAGES)?.split('\n')?.filter { it.isNotBlank() }
            ?: listOf(DEFAULT_OCR_LANGUAGE)

    suspend fun setOcrLanguages(value: List<String>) {
        require(value.all { it.isNotBlank() }) { "OCR languages must not be blank" }
        settingsDao.put(SettingEntity(KEY_OCR_LANGUAGES, value.joinToString("\n")))
    }

    companion object {
        const val DEFAULT_MATCH_THRESHOLD = 0.6
        const val DEFAULT_VOICE = "af_heart"
        const val DEFAULT_OCR_LANGUAGE = "eng"

        const val KEY_MATCH_THRESHOLD = "match_threshold"
        const val KEY_VOICE = "voice"
        const val KEY_FAVORITE_VOICES = "favorite_voices"
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_OCR_LANGUAGES = "ocr_languages"
    }
}

/** How the app picks its light/dark palette (V1 theme-follows-system). */
enum class ThemeMode(val key: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark");

    companion object {
        fun from(raw: String?): ThemeMode = entries.firstOrNull { it.key == raw } ?: SYSTEM
    }
}
