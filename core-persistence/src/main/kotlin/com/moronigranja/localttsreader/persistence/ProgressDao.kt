package com.moronigranja.localttsreader.persistence

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ProgressDao {

    @Query("SELECT * FROM progress WHERE bookId = :bookId")
    suspend fun get(bookId: String): ProgressEntity?

    /** Every resume row — the library read-progress source (D4). */
    @Query("SELECT * FROM progress ORDER BY bookId")
    fun observeAll(): Flow<List<ProgressEntity>>

    /** Inserts or replaces the book's resume point. */
    @Upsert
    suspend fun upsert(progress: ProgressEntity)
}