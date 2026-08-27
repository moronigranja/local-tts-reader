package com.moronigranja.localttsreader.model

import kotlinx.coroutines.flow.StateFlow

/**
 * The library store contract (conventions: "design against `LibraryStore`").
 *
 * `books` is the observable library in import order — the surface the list UI
 * reads. [add] persists one import outcome; [delete] removes a book and
 * everything owned by it (cached passages, progress, bookmarks, undo ring).
 *
 * The store is not responsible for the search index: the import coordinator
 * commits a LibraryEntry here FIRST and publishes it to the search index only
 * after the durable write lands (CR-3/A3); the removal path drops the index
 * entry only after the durable delete succeeds.
 */
interface LibraryStore {

    /** Observable library contents, in import order. */
    val books: StateFlow<List<LibraryEntry>>

    /**
     * CR-3/A3: durable membership check — the duplicate gate for re-imports.
     * Room (not the derived index) decides whether persistence work is
     * necessary, so a failed commit can never poison the retry path.
     */
    suspend fun contains(bookId: String): Boolean

    /** Persists [entry]. Idempotent per book id: re-adding the same id replaces. */
    suspend fun add(entry: LibraryEntry)

    /** Removes the book and its per-book rows; no-op for an unknown id. */
    suspend fun delete(bookId: String)
}