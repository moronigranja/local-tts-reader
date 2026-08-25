package com.moronigranja.localttsreader.persistence

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface ProgressDao {

    @Query("SELECT * FROM progress WHERE bookId = :bookId")
    suspend fun get(bookId: String): ProgressEntity?

    /** Inserts or replaces the book's resume point. */
    @Upsert
    suspend fun upsert(progress: ProgressEntity)
}
