package com.moronigranja.localttsreader.model

import kotlinx.coroutines.flow.StateFlow

/**
 * The library store contract (conventions: "design against `LibraryStore`").
 *
 * `books` is the observable library in import order — the surface the list UI
 * reads. [add] persists one import outcome; [delete] removes a book and
 * everything owned by it (cached passages, progress, bookmarks, undo ring).
 *
 * The store is not responsible for the search index: the importer indexes into
 * [com.moronigranja.localttsreader.locate.TextIndex] before a LibraryEntry ever
 * reaches [add]; the removal path drops the index entry separately.
 */
interface LibraryStore {

    /** Observable library contents, in import order. */
    val books: StateFlow<List<LibraryEntry>>

    /** Persists [entry]. Idempotent per book id: re-adding the same id replaces. */
    suspend fun add(entry: LibraryEntry)

    /** Removes the book and its per-book rows; no-op for an unknown id. */
    suspend fun delete(bookId: String)
}