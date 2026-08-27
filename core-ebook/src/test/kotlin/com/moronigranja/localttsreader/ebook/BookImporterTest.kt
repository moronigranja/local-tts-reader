package com.moronigranja.localttsreader.ebook

import com.moronigranja.localttsreader.ebook.EpubFixture.CONTAINER
import com.moronigranja.localttsreader.ebook.EpubFixture.chapterHtml
import com.moronigranja.localttsreader.ebook.EpubFixture.ncx
import com.moronigranja.localttsreader.ebook.EpubFixture.opf
import com.moronigranja.localttsreader.ebook.EpubFixture.zip
import com.moronigranja.localttsreader.ebook.EBookFormats
import com.moronigranja.localttsreader.ebook.EBookSource
import com.moronigranja.localttsreader.ebook.TextParser
import com.moronigranja.localttsreader.locate.TextIndex
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BookImporterTest {

    // ------------------------------------------------------------------
    // Fixture books
    // ------------------------------------------------------------------

    private fun epubBook(title: String, chapterTitle: String, body: String): ByteArray = zip(
        "META-INF/container.xml" to CONTAINER,
        "OEBPS/content.opf" to opf(
            title = title,
            spine = listOf("f0" to "title.xhtml", "c1" to "chap1.xhtml"),
            ncxHref = "toc.ncx",
        ),
        "OEBPS/toc.ncx" to ncx(
            listOf("title.xhtml" to "Title Page", "chap1.xhtml" to chapterTitle),
        ),
        "OEBPS/title.xhtml" to chapterHtml(null, listOf("A Novel by Someone")),
        "OEBPS/chap1.xhtml" to chapterHtml(null, listOf(body)),
    )

    private fun source(name: String, bytes: ByteArray): EBookSource =
        EBookSource(name) { ByteArrayInputStream(bytes) }

    private fun importer(index: TextIndex = TextIndex()) =
        BookImporter(index, now = { 1_700_000_000_000L })

    private fun textBytes(text: String): ByteArray = text.toByteArray(StandardCharsets.UTF_8)

    // ------------------------------------------------------------------
    // Happy path + contract
    // ------------------------------------------------------------------

    @Test
    fun `imports parses segments and indexes a book`() {
        val index = TextIndex()
        val outcome = importer(index).import(source("Novel.epub", epubBook("Novel", "Chapter 1", "Prose here.")))

        val entry = assertInstanceOf(ImportOutcome.Added::class.java, outcome).entry
        assertEquals("Novel", entry.book.title)
        assertEquals(listOf("Chapter 1"), entry.book.chapters.map { it.title })
        assertEquals(1_700_000_000_000L, entry.importedAtEpochMillis)
        assertEquals(64, entry.book.id.length)
        assertEquals(1, index.bookCount())
        assertTrue(index.contains(entry.book.id))
    }

    @Test
    fun `re-importing identical content is idempotent and skips work`() {
        val index = TextIndex()
        val outcome = importer(index).import(source("Novel.epub", epubBook("Novel", "Chapter 1", "Prose here.")))

        val entry = assertInstanceOf(ImportOutcome.Added::class.java, outcome).entry
        assertEquals(1, index.bookCount())
        assertTrue(index.contains(entry.book.id))
    }

    @Test
    fun `same name with different content imports as a distinct book`() {
        val index = TextIndex()
        val outcome1 = importer(index).import(source("Novel.epub", epubBook("Novel", "Chapter 1", "Prose here.")))
        val outcome2 = importer(index).import(source("Novel.epub", epubBook("Novel", "Chapter 1", "Different content.")))

        assertEquals(2, index.bookCount())
        val entry1 = assertInstanceOf(ImportOutcome.Added::class.java, outcome1).entry
        val entry2 = assertInstanceOf(ImportOutcome.Added::class.java, outcome2).entry
        assertTrue(index.contains(entry1.book.id))
        assertTrue(index.contains(entry2.book.id))
    }

    // ------------------------------------------------------------------
    // Failures never touch the index
    // ------------------------------------------------------------------

    @Test
    fun `unsupported format fails cleanly`() {
        val index = TextIndex()
        val outcome = importer(index).import(source("Book.pdf", epubBook("X", "C", "y")))

        val failed = assertInstanceOf(ImportOutcome.Failed::class.java, outcome)
        assertEquals(ImportFailureReason.UnsupportedFormat, failed.reason)
        assertEquals(0, index.bookCount())
    }

    @Test
    fun `garbage content fails with a parse error`() {
        val index = TextIndex()
        val outcome = importer(index).import(source("Broken.epub", "not a zip".toByteArray()))

        val failed = assertInstanceOf(ImportOutcome.Failed::class.java, outcome)
        assertInstanceOf(ImportFailureReason.ParseError::class.java, failed.reason)
        assertEquals(0, index.bookCount())
    }

    @Test
    fun `unreadable source fails cleanly`() {
        val index = TextIndex()
        val broken = EBookSource("Book.epub") { throw IllegalStateException("uri gone") }
        val outcome = importer(index).import(broken)

        val failed = assertInstanceOf(ImportOutcome.Failed::class.java, outcome)
        assertEquals(ImportFailureReason.Unreadable, failed.reason)
        assertEquals(0, index.bookCount())
    }

    @Test
    fun `drm-encrypted book fails with a parse error`() {
        val encrypted = Files.readAllBytes(Path.of("core-ebook/src/test/resources/mobi7_encrypted.mobi"))
        val index = TextIndex()
        val outcome = importer(index).import(source("Book.mobi", encrypted))

        val failed = assertInstanceOf(ImportOutcome.Failed::class.java, outcome)
        val reason = assertInstanceOf(ImportFailureReason.ParseError::class.java, failed.reason)
        assertTrue(reason.message.contains("DRM"))
        assertEquals(0, index.bookCount())
    }

    // ------------------------------------------------------------------
    // Batch + progress
    // ------------------------------------------------------------------

    @Test
    fun `batch import preserves order and reports progress`() = runTest {
        val index = TextIndex()
        val progress = mutableListOf<Triple<String, Int, Int>>()
        val sources = listOf(
            source("One.epub", epubBook("One", "Chapter 1", "First.")),
            source("Two.epub", epubBook("Two", "Chapter 1", "Second.")),
            source("Three.txt", epubBook("Three", "Chapter 1", "Third.")),
        )

        val outcomes = importer(index).importAll(sources) { current, done, total ->
            progress += Triple(current.fileName, done, total)
        }

        assertEquals(
            listOf(true, true, false),
            outcomes.map { it is ImportOutcome.Added },
        )
        // F1: a pre-parse event per file — a single large file shows
        // "Importing 0/1" immediately instead of looking hung.
        assertEquals(
            listOf(
                Triple("One.epub", 0, 3),
                Triple("One.epub", 1, 3),
                Triple("Two.epub", 1, 3),
                Triple("Two.epub", 2, 3),
                Triple("Three.txt", 2, 3),
                Triple("Three.txt", 3, 3),
            ),
            progress,
        )
        assertEquals(2, index.bookCount())
    }

    /** F1: cancelling a batch stops at the next file boundary and never
     * mutates the index for a file it never started. */
    @Test
    fun `cancelling a batch stops at the file boundary and skips later files`() = runTest {
        val index = TextIndex()
        val secondBytes = epubBook("Second", "Chapter 1", "Second.")
        val secondId = Bytes.sha256Hex(secondBytes)
        var cancelled = false
        val job = launch(StandardTestDispatcher(testScheduler)) {
            try {
                importer(index).importAll(
                    listOf(
                        source("One.epub", epubBook("One", "Chapter 1", "First.")),
                        source("Two.epub", secondBytes),
                    ),
                ) { _, _, _ -> }
            } catch (e: CancellationException) {
                cancelled = true
                throw e
            }
        }
        runCurrent() // body starts, parks on the first 1 ms boundary
        advanceTimeBy(2) // past the boundary: file 1 parsed + indexed; parks again
        assertEquals(1, index.bookCount(), "file 1 completed before the cancel")
        job.cancel()
        advanceTimeBy(2) // the boundary delay throws: the batch stops cleanly
        assertTrue(cancelled, "importAll propagates the cancellation")
        assertTrue(!index.contains(secondId), "file 2 must never reach the index")
    }

    // ------------------------------------------------------------------
    // txt / markdown end-to-end
    // ------------------------------------------------------------------

    @Test
    fun `imports a txt file and indexes a book`() {
        val text = "Hello world.\n\nSecond paragraph."
        val bytes = textBytes(text)
        val index = TextIndex()
        val outcome = importer(index).import(source("book.txt", bytes))
        val added = assertInstanceOf(ImportOutcome.Added::class.java, outcome)
        assertEquals(1, index.bookCount())
        assertEquals(1, added.entry.book.chapters.size)
        assertEquals(2, added.entry.book.chapters[0].passages.size)
        assertEquals("Hello world.", added.entry.book.chapters[0].passages[0].text)
    }

    @Test
    fun `imports a markdown file and indexes a book`() {
        val text = "# Chapter 1\n\nBody paragraph.\n\n## Chapter 2\n\nMore."
        val bytes = textBytes(text)
        val index = TextIndex()
        val outcome = importer(index).import(source("book.md", bytes))
        val added = assertInstanceOf(ImportOutcome.Added::class.java, outcome)
        assertEquals(1, index.bookCount())
        assertEquals(2, added.entry.book.chapters.size)
        assertEquals("Chapter 1", added.entry.book.chapters[0].title)
        assertEquals("Chapter 2", added.entry.book.chapters[1].title)
    }

    @Test
    fun `importing a txt file with no chapters raises parse error`() {
        val index = TextIndex()
        val outcome = importer(index).import(source("book.txt", textBytes("")))

        val failed = assertInstanceOf(ImportOutcome.Failed::class.java, outcome)
        assertInstanceOf(ImportFailureReason.ParseError::class.java, failed.reason)
        assertEquals(0, index.bookCount())
    }

    @Test
    fun `importing a markdown file with no chapters raises parse error`() {
        val index = TextIndex()
        val outcome = importer(index).import(source("book.md", textBytes("")))

        val failed = assertInstanceOf(ImportOutcome.Failed::class.java, outcome)
        assertInstanceOf(ImportFailureReason.ParseError::class.java, failed.reason)
        assertEquals(0, index.bookCount())
    }
}
