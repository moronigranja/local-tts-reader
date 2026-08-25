package com.moronigranja.localttsreader.model

import kotlinx.coroutines.flow.StateFlow

/**
 * The library store contract (conventions: "design against `LibraryStore`").
 *
 * `books` is the observable library in import order — the surface the list UI
 * reads. [add] persists one import outcome; implementations decide how (Room
 * caches the book's parsed passages for the launch-time index rebuild, the
 * in-memory store just keeps the entry).
 *
 * The store is not responsible for the search index: the importer indexes into
 * [com.moronigranja.localttsreader.locate.TextIndex] before a LibraryEntry ever
 * reaches [add].
 */
interface LibraryStore {

    /** Observable library contents, in import order. */
    val books: StateFlow<List<LibraryEntry>>

    /** Persists [entry]. Idempotent per book id: re-adding the same id replaces. */
    suspend fun add(entry: LibraryEntry)
}
