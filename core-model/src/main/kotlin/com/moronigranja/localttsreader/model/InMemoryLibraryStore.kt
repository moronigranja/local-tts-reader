package com.moronigranja.localttsreader.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory [LibraryStore] keeping the full [LibraryEntry]. Used by unit tests
 * (the ViewModel contract is store-agnostic) and as a reference implementation;
 * production uses the Room-backed store in `core-persistence`.
 *
 * Synchronous by design — [add]/[delete] complete before returning, so tests
 * observe the effect immediately. [books] is a plain `MutableStateFlow`
 * update: no room for conflation under the virtual test dispatcher.
 */
class InMemoryLibraryStore : LibraryStore {

    private val _books = MutableStateFlow<List<LibraryEntry>>(emptyList())

    override val books: StateFlow<List<LibraryEntry>> = _books.asStateFlow()

    override suspend fun contains(bookId: String): Boolean =
        _books.value.any { it.book.id == bookId }

    override suspend fun add(entry: LibraryEntry) {
        if (_books.value.any { it.book.id == entry.book.id }) return
        _books.value = _books.value + entry
    }

    override suspend fun delete(bookId: String) {
        _books.value = _books.value.filterNot { it.book.id == bookId }
    }
}