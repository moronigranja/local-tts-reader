package com.moronigranja.localttsreader.featurelibrary

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import com.moronigranja.localttsreader.ebook.BookImporter
import com.moronigranja.localttsreader.ebook.EBookSource
import com.moronigranja.localttsreader.ebook.ImportFailureReason
import com.moronigranja.localttsreader.ebook.ImportOutcome
import com.moronigranja.localttsreader.featureplayer.playback.PregenManager
import com.moronigranja.localttsreader.featureplayer.playback.PregenStorage
import com.moronigranja.localttsreader.featurelibrary.CoverStore
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import com.moronigranja.localttsreader.model.LibraryEntry
import com.moronigranja.localttsreader.model.LibraryStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Drives the import flow: batches [EBookSource]s through the domain [BookImporter]
 * on the IO dispatcher, publishes progress as [Importing], appends [Added]
 * entries to the [LibraryStore], and lands on [ImportUiState.Done] with the
 * batch summary — for every outcome, including all-failed batches.
 *
 * [ioDispatcher] is qualifier-injected so unit tests can hand a virtual dispatcher
 * to [import]'s coroutine (production gets [kotlinx.coroutines.Dispatchers.IO]
 * from [ImportModule]).
 */
@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val repository: LibraryStore,
    private val importer: BookImporter,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    // Default null: pure-JVM unit tests skip pre-generation (Hilt always supplies it).
    private val pregenManager: PregenManager? = null,
    private val storage: PregenStorage? = null,
    @ApplicationContext private val context: Context? = null,
) : ViewModel() {
    /** Books the user has covers for (extracted at import; sidecar files). */
    fun cover(bookId: String): ByteArray? = context?.let { CoverStore(File(it.filesDir, "covers")).load(bookId) }

    /** Dismisses the finished-batch summary; the snackbar/dialog must not re-show on revisit. */
    fun consumeImportResult() {
        _importState.value = ImportUiState.Idle
    }
    /** Starts a manual offline pre-generation run for one book (#42). */
    fun pregenerate(bookId: String) = pregenManager?.pregenerate(bookId)

    /** The book's manual pre-generation job, for row progress (KEEP-deduplicated). */
    fun pregenWork(bookId: String): LiveData<List<WorkInfo>> =
        pregenManager?.workInfo(bookId) ?: MutableLiveData(emptyList())

    val library: StateFlow<List<LibraryEntry>> = repository.books

    /** bookId → offline-audio facts for the row (#44): usage + full-book estimate. */
    data class OfflineBook(val usageBytes: Long, val estimateBytes: Long)

    private val _offline = MutableStateFlow<Map<String, OfflineBook>>(emptyMap())
    val offline: StateFlow<Map<String, OfflineBook>> = _offline.asStateFlow()

    init {
        refreshOffline()
    }

    /** Recomputes usage + estimates from the disk tier (IO). */
    fun refreshOffline() {
        val storage = storage ?: return
        viewModelScope.launch {
            val usage = withContext(ioDispatcher) { storage.usageByBook() }
            val estimates = withContext(ioDispatcher) { storage.estimateAll() }
            _offline.value = estimates.mapValues { (id, est) ->
                OfflineBook(usageBytes = usage[id] ?: 0L, estimateBytes = est.totalBytes)
            }
        }
    }

    /** Reclaims one book's offline audio: cancel queued work, delete the subtree. */
    fun deleteOffline(bookId: String) {
        val storage = storage ?: return
        viewModelScope.launch {
            withContext(ioDispatcher) { storage.deleteBook(bookId) }
            refreshOffline()
        }
    }

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

    private suspend fun buildSummary(outcomes: List<ImportOutcome>): ImportUiState.Summary {
        var added = 0
        var unchanged = 0
        val failed = mutableListOf<Pair<String, String>>()
        for (outcome in outcomes) {
            when (outcome) {
                is ImportOutcome.Added -> {
                    added += 1
                    repository.add(outcome.entry)
                    outcome.coverBytes?.let { cover ->
                        context?.let { CoverStore(File(it.filesDir, "covers")).save(outcome.entry.book.id, cover) }
                    }
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
