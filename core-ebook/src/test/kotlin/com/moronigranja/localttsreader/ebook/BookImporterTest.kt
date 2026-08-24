package com.moronigranja.localttsreader.ebook

import com.moronigranja.localttsreader.ebook.EpubFixture.CONTAINER
import com.moronigranja.localttsreader.ebook.EpubFixture.chapterHtml
import com.moronigranja.localttsreader.ebook.EpubFixture.ncx
import com.moronigranja.localttsreader.ebook.EpubFixture.opf
import com.moronigranja.localttsreader.ebook.EpubFixture.zip
import com.moronigranja.localttsreader.locate.TextIndex
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

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

    // ------------------------------------------------------------------
    // Happy path + contract
    // ------------------------------------------------------------------

    @Test
    fun `imports parses segments and indexes a book`() {
        val index = TextIndex()
        val outcome = importer(index).import(source("Novel.epub", epubBook("Novel", "Chapter 1", "Prose here.")))

        val entry = assertInstanceOf(ImportOutcome.Added::class.java, outcome).entry
        assertEquals("Novel", entry.book.title)
        assertEquals(listOf("Chapter 1"), entry.book.chapters.map { it.title }) // title page stripped
        assertEquals(1_700_000_000_000L, entry.importedAtEpochMillis)
        assertEquals(64, entry.book.id.length)
        assertEquals(1, index.bookCount())
        assertTrue(index.contains(entry.book.id))
    }

    @Test
    fun `re-importing identical content is idempotent and skips work`() {
        val index = TextIndex()
        val importer = importer(index)
        val bytes = epubBook("Novel", "Chapter 1", "Prose here.")

        val first = importer.import(source("Novel.epub", bytes))
        val second = importer.import(source("Novel.epub", bytes))

        val added = assertInstanceOf(ImportOutcome.Added::class.java, first)
        val unchanged = assertInstanceOf(ImportOutcome.Unchanged::class.java, second)
        assertEquals(added.entry.book.id, unchanged.bookId)
        assertEquals(1, index.bookCount())
    }

    @Test
    fun `same name with different content imports as a distinct book`() {
        val index = TextIndex()
        val importer = importer(index)

        val a = importer.import(source("Same.epub", epubBook("A", "Chapter 1", "First version.")))
        val b = importer.import(source("Same.epub", epubBook("B", "Chapter 1", "Second, changed version.")))

        val addedA = assertInstanceOf(ImportOutcome.Added::class.java, a)
        val addedB = assertInstanceOf(ImportOutcome.Added::class.java, b)
        assertTrue(addedA.entry.book.id != addedB.entry.book.id)
        assertEquals(2, index.bookCount())
    }

    // ------------------------------------------------------------------
    // Failures never touch the index
    // ------------------------------------------------------------------

    @Test
    fun `unsupported format fails cleanly`() {
        val index = TextIndex()
        val outcome = importer(index).import(source("Book.txt", epubBook("X", "C", "y")))

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
    fun `batch import preserves order and reports progress`() {
        val index = TextIndex()
        val progress = mutableListOf<Pair<Int, Int>>()
        val sources = listOf(
            source("One.epub", epubBook("One", "Chapter 1", "First.")),
            source("Two.epub", epubBook("Two", "Chapter 1", "Second.")),
            source("Three.txt", epubBook("Three", "Chapter 1", "Third.")),
        )

        val outcomes = importer(index).importAll(sources, onProgress = { done, total ->
            progress += done to total
        })

        assertEquals(
            listOf(true, true, false),
            outcomes.map { it is ImportOutcome.Added },
        )
        assertEquals(listOf(1 to 3, 2 to 3, 3 to 3), progress)
        assertEquals(2, index.bookCount())
    }
}
