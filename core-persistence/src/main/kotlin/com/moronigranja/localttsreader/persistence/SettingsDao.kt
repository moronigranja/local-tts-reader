package com.moronigranja.localttsreader.persistence

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface SettingsDao {

    @Query("SELECT value FROM settings WHERE key = :key")
    suspend fun get(key: String): String?

    /** Inserts or replaces the setting row. */
    @Upsert
    suspend fun put(setting: SettingEntity)

    /** One-shot read of every row, key-sorted — the backup snapshot source (E1). */
    @Query("SELECT * FROM settings ORDER BY key")
    suspend fun all(): List<SettingEntity>

    /** Bulk upsert — the backup restore apply (E1); absent keys keep their local rows. */
    @Upsert
    suspend fun putAll(settings: List<SettingEntity>)
}
