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

    /** Book removal: the book's bookmarks go with it (decisions #50 pass). */
    @Query("DELETE FROM bookmarks WHERE bookId = :bookId")
    suspend fun deleteByBook(bookId: String)

    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId ORDER BY createdAtEpochMillis DESC, id DESC")
    suspend fun all(bookId: String): List<BookmarkEntity>
}