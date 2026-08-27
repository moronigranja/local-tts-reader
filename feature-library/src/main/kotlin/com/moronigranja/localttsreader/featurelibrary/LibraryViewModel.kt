package com.moronigranja.localttsreader.featurelibrary

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import android.content.Intent
import android.content.Context
import com.moronigranja.localttsreader.ebook.BookImporter
import com.moronigranja.localttsreader.ebook.EBookSource
import com.moronigranja.localttsreader.ebook.ImportFailureReason
import com.moronigranja.localttsreader.ebook.ImportOutcome
import com.moronigranja.localttsreader.featureplayer.playback.PlaybackService
import com.moronigranja.localttsreader.featureplayer.playback.PregenManager
import com.moronigranja.localttsreader.featureplayer.playback.PregenStorage
import com.moronigranja.localttsreader.featurelibrary.CoverStore
import com.moronigranja.localttsreader.locate.TextIndex
import com.moronigranja.localttsreader.model.LibraryEntry
import com.moronigranja.localttsreader.model.LibraryStore
import com.moronigranja.localttsreader.persistence.ChapterCount
import com.moronigranja.localttsreader.persistence.PassageDao
import com.moronigranja.localttsreader.persistence.ProgressDao
import com.moronigranja.localttsreader.persistence.ProgressEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

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
    // Default null: pure-JVM unit tests skip the Room progress surface (Hilt provides it).
    private val passageDao: PassageDao? = null,
    private val progressDao: ProgressDao? = null,
    // Default null: unit tests drop the index; Hilt provides the shared one.
    private val index: TextIndex? = null,
) : ViewModel() {
    /** Books the user has covers for (extracted at import; sidecar files). */
    fun cover(bookId: String): ByteArray? = context?.let { CoverStore(File(it.filesDir, "covers")).load(bookId) }

    /** Dismisses the finished-batch summary; the snackbar/dialog must not re-show on revisit. */
    fun consumeImportResult() {
        _importState.value = ImportUiState.Idle
    }
    /** Starts a manual pre-generation run for one book (#42); null budget = whole book. */
    fun pregenerate(bookId: String, budgetMinutes: Long? = null) =
        pregenManager?.pregenerate(bookId, budgetMinutes)

    /** The book's manual pre-generation job, for row progress (KEEP-deduplicated). */
    fun pregenWork(bookId: String): LiveData<List<WorkInfo>> =
        pregenManager?.workInfo(bookId) ?: MutableLiveData(emptyList())

    val library: StateFlow<List<LibraryEntry>> = repository.books
    /** Recently-active books (resume rows, most recent first) — the library's
     * "Continue listening" section (decisions #50 pass). */
    val recent: StateFlow<List<LibraryEntry>> =
        if (progressDao == null) {
            MutableStateFlow(emptyList())
        } else {
            combine(progressDao!!.observeAll(), repository.books) { rows, books ->
                val byId = books.associateBy { it.book.id }
                rows.sortedByDescending { it.updatedAtEpochMillis }
                    .mapNotNull { byId[it.bookId] }
                    .take(MAX_RECENT)
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
        }

    /** bookId → read/listened fraction (0..1): completed passages over the
     * cached book's total, from the resume rows (passage-granular — the
     * player's resume unit). Null DAOs (unit tests) → empty. */
    val readProgress: StateFlow<Map<String, Float>> =
        if (passageDao == null || progressDao == null) {
            MutableStateFlow(emptyMap())
        } else {
            combine(passageDao!!.chapterCounts(), progressDao!!.observeAll()) { counts, rows ->
                rows.associate { row -> row.bookId to progressFraction(row, counts) }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())
        }

    private fun progressFraction(row: ProgressEntity, counts: List<ChapterCount>): Float {
        val byBook = counts.filter { it.bookId == row.bookId }
        val total = byBook.sumOf { it.passageCount }
        if (total == 0) return 0f
        val before = byBook.filter { it.chapterIndex < row.chapterIndex }.sumOf { it.passageCount } + row.passageIndex
        return ((before + 1).coerceAtMost(total).toFloat() / total).coerceIn(0f, 1f)
    }

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

    /** Removes a book from the library entirely: index, offline audio,
     * covers and all rows (decisions #50 pass). */
    fun removeBook(bookId: String) {
        viewModelScope.launch {
            withContext(ioDispatcher) {
                // Stop live playback first: the service holds its own book
                // reference and would otherwise keep reading the removed book.
                context?.let { ctx ->
                    runCatching {
                        ctx.startService(
                            Intent(ctx, PlaybackService::class.java).setAction(PlaybackService.ACTION_STOP),
                        )
                    }
                }
                index?.remove(bookId)
                storage?.deleteBook(bookId) // cancels queued pre-gen first
                repository.delete(bookId)
                context?.let { CoverStore(File(it.filesDir, "covers")).delete(bookId) }
            }
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
    private companion object {
        const val MAX_RECENT = 5
    }
}