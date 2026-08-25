package com.moronigranja.localttsreader.persistence

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A book's resume point (P1): one row per book, pointing at the last read
 * passage. Written by the player (T4) and read by resume wiring (S3).
 */
@Entity(tableName = "progress")
data class ProgressEntity(
    @PrimaryKey val bookId: String,
    val chapterIndex: Int,
    val passageIndex: Int,
    val updatedAtEpochMillis: Long,
)
