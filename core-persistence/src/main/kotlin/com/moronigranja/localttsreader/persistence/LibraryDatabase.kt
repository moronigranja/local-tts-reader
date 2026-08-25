package com.moronigranja.localttsreader.persistence

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * The app's single database (P1). Version 1 is the baseline schema:
 * books + cached passages + progress + settings. Schema evolution is
 * forward-only migrations (`exportSchema = false` until the CI slice (V2)
 * adds a schema-drift check; no destructive fallback — a schema bump without
 * a migration fails loudly rather than wiping the library).
 */
@Database(
    entities = [
        BookEntity::class,
        PassageEntity::class,
        ProgressEntity::class,
        SettingEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun passageDao(): PassageDao
    abstract fun progressDao(): ProgressDao
    abstract fun settingsDao(): SettingsDao
}
