package com.moronigranja.localttsreader.persistence

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface BookmarkDao {

    @Insert
    suspend fun insert(bookmark: BookmarkEntity): Long

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId ORDER BY createdAtEpochMillis DESC, id DESC")
    suspend fun all(bookId: String): List<BookmarkEntity>
}
