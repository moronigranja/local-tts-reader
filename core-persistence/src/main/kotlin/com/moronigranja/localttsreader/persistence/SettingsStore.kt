package com.moronigranja.localttsreader.persistence

/**
 * Typed accessors over the generic `settings` table. Unknown or corrupt stored
 * values fall back to defaults — a missing row and a bad value are equivalent,
 * and both are user-recoverable from the settings UI (V1).
 */
class SettingsStore(private val settingsDao: SettingsDao) {

    /** Recall floor for share matches (decisions #3), default 0.6. */
    suspend fun matchThreshold(): Double =
        settingsDao.get(KEY_MATCH_THRESHOLD)?.toDoubleOrNull() ?: DEFAULT_MATCH_THRESHOLD

    suspend fun setMatchThreshold(value: Double) {
        require(value in 0.0..1.0) { "match threshold must be within 0..1, was $value" }
        settingsDao.put(SettingEntity(KEY_MATCH_THRESHOLD, value.toString()))
    }

    companion object {
        const val DEFAULT_MATCH_THRESHOLD = 0.6
        const val KEY_MATCH_THRESHOLD = "match_threshold"
    }
}
