package com.moronigranja.localttsreader.featurelibrary

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.moronigranja.localttsreader.ebook.BookImporter
import com.moronigranja.localttsreader.ebook.EBookSource
import com.moronigranja.localttsreader.ebook.ImportFailureReason
import com.moronigranja.localttsreader.ebook.ImportOutcome
import com.moronigranja.localttsreader.model.LibraryEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Drives the import flow: batches [EBookSource]s through the domain [BookImporter]
 * on the IO dispatcher, publishes progress as [Importing], appends [Added]
 * entries to the [LibraryRepository], and lands on [ImportUiState.Done] with the
 * batch summary — for every outcome, including all-failed batches.
 *
 * [ioDispatcher] is qualifier-injected so unit tests can hand a virtual dispatcher
 * to [import]'s coroutine (production gets [kotlinx.coroutines.Dispatchers.IO]
 * from [ImportModule]).
 */
@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val repository: LibraryRepository,
    private val importer: BookImporter,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {

    val library: StateFlow<List<LibraryEntry>> = repository.books

    private val _importState = MutableStateFlow<ImportUiState>(ImportUiState.Idle)
    val importState: StateFlow<ImportUiState> = _importState.asStateFlow()

    /** Imports [sources] in order, reporting per-file progress; no-op for an empty list. */
    fun import(sources: List<EBookSource>) {
        if (sources.isEmpty()) return
        viewModelScope.launch {
            val summary = withContext(ioDispatcher) {
                val outcomes = importer.importAll(sources) { done, total ->
                    _importState.value = ImportUiState.Importing(
                        done = done,
                        total = total,
                        currentFileName = sources[done - 1].fileName,
                    )
                }
                buildSummary(outcomes)
            }
            _importState.value = ImportUiState.Done(summary)
        }
    }

    private fun buildSummary(outcomes: List<ImportOutcome>): ImportUiState.Summary {
        var added = 0
        var unchanged = 0
        val failed = mutableListOf<Pair<String, String>>()
        for (outcome in outcomes) {
            when (outcome) {
                is ImportOutcome.Added -> {
                    added += 1
                    repository.add(outcome.entry)
                }
                is ImportOutcome.Unchanged -> unchanged += 1
                is ImportOutcome.Failed -> failed += outcome.fileName to reasonMessage(outcome.reason)
            }
        }
        return ImportUiState.Summary(added, unchanged, failed)
    }

    private fun reasonMessage(reason: ImportFailureReason): String = when (reason) {
        ImportFailureReason.UnsupportedFormat -> "format not supported"
        ImportFailureReason.Unreadable -> "could not read file"
        is ImportFailureReason.ParseError -> reason.message
    }
}
