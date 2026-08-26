package com.moronigranja.localttsreader

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.moronigranja.localttsreader.ebook.BookImporter
import com.moronigranja.localttsreader.ebook.EBookSource
import com.moronigranja.localttsreader.ebook.EpubParser
import com.moronigranja.localttsreader.ebook.ImportOutcome
import com.moronigranja.localttsreader.locate.TextIndex
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * S-debug device repro: a real-world EPUB (Gutenberg Pride and Prejudice,
 * 24.8 MiB) through the ACTUAL parsers on the Android runtime — Android's
 * DOM (Expat-backed) differs from host Xerces, which is where the field
 * "could not read container.xml" originated. The container path is now
 * regex-based and parseXml pre-processes doctype/single-quoted-declaration;
 * this probe exercises the full import and prints the cause chain on failure.
 *
 * Requires `files/import-probe/pp.epub` staged via adb (build.md pattern).
 */
@RunWith(AndroidJUnit4::class)
class RealEpubImportProbe {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun realEpubImportsOnDevice() {
        val file = File(context.filesDir, "import-probe/pp.epub")
        assertTrue("stage build.md's pp.epub first: $file", file.isFile)

        // Direct parse first: surfaces the cause chain if anything fails.
        val book = try {
            EpubParser.parse(file.readBytes())
        } catch (e: Throwable) {
            val chain = generateSequence(e) { it.cause }
                .map { "${it::class.java.simpleName}: ${it.message ?: ""}" }
                .joinToString(" <- ")
            fail("EpubParser.parse failed: $chain")
            null
        }!!
        assertEquals("Pride and Prejudice", book.title)
        assertTrue("chapters parsed", book.chapters.size >= 5)
        assertTrue("passages parsed", book.chapters.sumOf { it.passages.size } > 500)

        // The importer path (what SAF import runs) must land Added too.
        val index = TextIndex()
        val importer = BookImporter(index)
        val outcome = importer.import(EBookSource(fileName = "pp.epub") { file.inputStream() })
        assertTrue("expected Added, was $outcome", outcome is ImportOutcome.Added)
        assertEquals(1, index.bookCount())
    }
}