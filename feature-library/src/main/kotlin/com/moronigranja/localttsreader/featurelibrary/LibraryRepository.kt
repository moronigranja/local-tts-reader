package com.moronigranja.localttsreader.featurelibrary

import com.moronigranja.localttsreader.model.LibraryEntry
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory library store backing the list UI. Imports land here (via the
 * ViewModel) as `Added` outcomes; [BookImporter] already guarantees content
 * idempotency, so [add] also defends against duplicate ids defensively.
 *
 * P1 swap target: a Room-backed implementation behind the same [books] surface.
 */
@Singleton
class LibraryRepository @Inject constructor() {

    private val _books = MutableStateFlow<List<LibraryEntry>>(emptyList())

    /** Observable library contents, in import order. */
    val books: StateFlow<List<LibraryEntry>> = _books.asStateFlow()

    /** Appends [entry] unless a book with the same id is already present. */
    fun add(entry: LibraryEntry) {
        if (_books.value.any { it.book.id == entry.book.id }) return
        _books.value = _books.value + entry
    }
}
