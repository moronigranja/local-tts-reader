package com.moronigranja.localttsreader.ebook

import com.moronigranja.localttsreader.locate.TextIndex
import com.moronigranja.localttsreader.model.LibraryEntry
import kotlinx.coroutines.delay

/**
 * The import pipeline's domain core: format detection → parse → segment → index,
 * plus the library entry. Pure logic, no Android — the SAF picker and UI are a thin
 * adapter over this (C6/F1).
 *
 * Re-import semantics are content-addressed: the book id is the SHA-256 of the file
 * bytes, so importing the same file twice is idempotent ([ImportOutcome.Unchanged],
 * no re-parse), while a changed file — even with the same name — imports as a new
 * book (the index keeps both until persistence decides replacement policy).
 *
 * Every failure maps to a typed [ImportOutcome.Failed] with a user-facing reason;
 * the pipeline never throws for bad input and never touches the index on failure.
 */
class BookImporter(
    private val index: TextIndex,
    private val now: () -> Long = System::currentTimeMillis,
) {

    fun import(source: EBookSource): ImportOutcome {
        val parser = EBookFormats.parserFor(source.fileName)
            ?: return ImportOutcome.Failed(source.fileName, ImportFailureReason.UnsupportedFormat)

        val bytes = try {
            source.open().use { it.readBytes() }
        } catch (e: Exception) {
            return ImportOutcome.Failed(source.fileName, ImportFailureReason.Unreadable)
        }
        val id = Bytes.sha256Hex(bytes)
        if (index.contains(id)) return ImportOutcome.Unchanged(id)

        val book = try {
            parser.parse(source) // EBookSource.open() is a factory: a fresh stream per call
        } catch (e: EBookParseException) {
            return ImportOutcome.Failed(source.fileName, ImportFailureReason.ParseError(e.message ?: "parse failed"))
        }

        val segmented = BookSegmentation.segment(book) // index/segmentation contract (C4)
        index.add(segmented)
        val cover = parser.coverOf(bytes)
        return ImportOutcome.Added(LibraryEntry(segmented, now()), cover)
    }

    /**
     * Batch import with progress reporting; outcomes preserve input order.
     *
     * F1: [onProgress] fires BEFORE each file's parse (done = files already
     * completed, current = the file about to be read) and AFTER each
     * completion — so a single large file shows "Importing 0/1 — book.epub"
     * the moment the batch starts instead of looking hung until the parse
     * lands. The loop cooperates with cancellation at every file boundary
     * (a 1 ms [delay] — noise against multi-megabyte parses, but a real
     * suspension so a cancelled batch stops between files and never mutates
     * the index for a file it never started).
     */
    suspend fun importAll(
        sources: List<EBookSource>,
        onProgress: (current: EBookSource, done: Int, total: Int) -> Unit = { _, _, _ -> },
    ): List<ImportOutcome> {
        val results = ArrayList<ImportOutcome>(sources.size)
        sources.forEachIndexed { i, source ->
            delay(1) // cancellation/cooperation boundary between files (F1)
            onProgress(source, i, sources.size)
            results += import(source)
            onProgress(source, i + 1, sources.size)
        }
        return results
    }
}

/** One import's verdict. The index is only ever mutated on [ImportOutcome.Added]. */
sealed interface ImportOutcome {
    data class Added(val entry: LibraryEntry, val coverBytes: ByteArray? = null) : ImportOutcome
    data class Unchanged(val bookId: String) : ImportOutcome
    data class Failed(val fileName: String, val reason: ImportFailureReason) : ImportOutcome
}

sealed interface ImportFailureReason {
    data object UnsupportedFormat : ImportFailureReason
    data object Unreadable : ImportFailureReason
    data class ParseError(val message: String) : ImportFailureReason
}
