package com.moronigranja.localttsreader.persistence

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Forward-only schema evolution (decisions #22: no destructive fallback — a
 * schema bump without a migration fails loudly rather than wiping the
 * library). One object per step; builders must call [addMigrations].
 */
val MIGRATION_1_2: Migration = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Player slice (T4-1, decisions #33): progress gains the in-passage
        // book-time offset + per-book speed; bookmarks and the position ring
        // arrive as new tables.
        db.execSQL("ALTER TABLE progress ADD COLUMN offsetSeconds REAL NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE progress ADD COLUMN speed REAL NOT NULL DEFAULT 1.0")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `bookmarks` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `bookId` TEXT NOT NULL,
                `chapterIndex` INTEGER NOT NULL,
                `passageIndex` INTEGER NOT NULL,
                `offsetSeconds` REAL NOT NULL,
                `label` TEXT,
                `createdAtEpochMillis` INTEGER NOT NULL
            )
            """,
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_bookmarks_bookId` ON `bookmarks` (`bookId`)")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `position_history` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `bookId` TEXT NOT NULL,
                `chapterIndex` INTEGER NOT NULL,
                `passageIndex` INTEGER NOT NULL,
                `offsetSeconds` REAL NOT NULL,
                `createdAtEpochMillis` INTEGER NOT NULL
            )
            """,
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_position_history_bookId` ON `position_history` (`bookId`)")
    }
}
