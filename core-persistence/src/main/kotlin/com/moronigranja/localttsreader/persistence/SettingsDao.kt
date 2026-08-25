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
}
