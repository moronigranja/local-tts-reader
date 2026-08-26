package com.moronigranja.localttsreader.featurelibrary

import com.moronigranja.localttsreader.ebook.BookImporter
import com.moronigranja.localttsreader.ebook.EBookSource
import com.moronigranja.localttsreader.ebook.EpubFixture.CONTAINER
import com.moronigranja.localttsreader.ebook.EpubFixture.chapterHtml
import com.moronigranja.localttsreader.ebook.EpubFixture.ncx
import com.moronigranja.localttsreader.ebook.EpubFixture.opf
import com.moronigranja.localttsreader.ebook.EpubFixture.zip
import com.moronigranja.localttsreader.locate.TextIndex
import com.moronigranja.localttsreader.model.InMemoryLibraryStore
import java.io.ByteArrayInputStream
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest {

    // Main is virtual for every test; viewModelScope work runs eagerly on the
    // test thread (no real threads/time → fully deterministic).
    @RegisterExtension
    val mainDispatcherRule = MainDispatcherRule()

    // ------------------------------------------------------------------
    // Fixture helpers (EpubFixture comes from core-ebook's testFixtures)
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

    private fun viewModel() = LibraryViewModel(
        InMemoryLibraryStore(),
        BookImporter(TextIndex()),
        mainDispatcherRule.testDispatcher,
    )

    // ------------------------------------------------------------------
    // Tests
    // ------------------------------------------------------------------

    @Test
    fun `importing a valid epub adds a library entry and lands on Done`() = runTest(mainDispatcherRule.testDispatcher) {
        val vm = viewModel()

        vm.import(listOf(source("Novel.epub", epubBook("Novel", "Chapter 1", "Prose here."))))

        assertEquals(1, vm.library.value.size)
        assertEquals("Novel", vm.library.value.first().book.title)
        assertEquals(listOf("Chapter 1"), vm.library.value.first().book.chapters.map { it.title })
        assertEquals(
            ImportUiState.Done(ImportUiState.Summary(added = 1, unchanged = 0, failed = emptyList())),
            vm.importState.value,
        )
    }

    @Test
    fun `re-importing identical bytes is idempotent through the repository`() = runTest(mainDispatcherRule.testDispatcher) {
        val vm = viewModel()
        val sameBook = source("Novel.epub", epubBook("Novel", "Chapter 1", "Prose here."))

        vm.import(listOf(sameBook))
        vm.import(listOf(sameBook))

        assertEquals(1, vm.library.value.size)
        assertEquals(
            ImportUiState.Done(ImportUiState.Summary(added = 0, unchanged = 1, failed = emptyList())),
            vm.importState.value,
        )
    }

    @Test
    fun `unsupported format fails with a typed message and leaves the library untouched`() = runTest(mainDispatcherRule.testDispatcher) {
        val vm = viewModel()

        vm.import(listOf(source("notes.rtf", "{\rtf1 hello}".toByteArray())))

        val done = vm.importState.value as ImportUiState.Done
        assertEquals(0, done.summary.added)
        assertEquals(0, done.summary.unchanged)
        assertEquals(listOf("notes.rtf" to "format not supported"), done.summary.failed)
        assertTrue(vm.library.value.isEmpty())
    }

    @Test
    fun `corrupted epub fails with a parse error and leaves the library untouched`() = runTest(mainDispatcherRule.testDispatcher) {
        val vm = viewModel()

        vm.import(listOf(source("broken.epub", "not a zip".toByteArray())))

        val done = vm.importState.value as ImportUiState.Done
        assertEquals(0, done.summary.added)
        assertEquals(1, done.summary.failed.size)
        assertEquals("broken.epub", done.summary.failed.first().first)
        assertTrue(done.summary.failed.first().second.isNotBlank(), "parse error should carry a message")
        assertTrue(vm.library.value.isEmpty())
    }

    @Test
    fun `importAll reports per-file progress and lands on Done`() = runTest(mainDispatcherRule.testDispatcher) {
        // The exact onProgress callback sequence (1,2) then (2,2) is deterministic
        // to assert synchronously at the importer level — the same call the ViewModel
        // makes with its progress wiring:
        val progress = mutableListOf<Pair<Int, Int>>()
        BookImporter(TextIndex()).importAll(
            listOf(
                source("Novel.epub", epubBook("Novel", "Chapter 1", "Prose here.")),
                source("book.txt", "nope".toByteArray()),
            ),
        ) { done, total -> progress += done to total }
        assertEquals(listOf(1 to 2, 2 to 2), progress)

        // ViewModel wiring: Idle → Importing (last file processed) → Done.
        // Importing(1,2) specifically is not asserted: StateFlow conflates
        // intermediate values when the emitter never suspends, so only the last
        // per-file progress is guaranteed observable — a UI sees the same.
        val vm = viewModel()
        val states = mutableListOf<ImportUiState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.importState.collect(states::add)
        }
        vm.import(
            listOf(
                source("Novel.epub", epubBook("Novel", "Chapter 1", "Prose here.")),
                source("notes.rtf", "{\rtf1 nope}".toByteArray()),
            ),
        )

        assertEquals(ImportUiState.Idle, states.first(), "states: $states")
        val importings = states.filterIsInstance<ImportUiState.Importing>()
        assertTrue(importings.isNotEmpty(), "expected an Importing state in $states")
        assertEquals(2, importings.last().total)
        assertEquals("notes.rtf", importings.last().currentFileName)
        assertTrue(
            states.indexOf(importings.last()) < states.lastIndex,
            "Importing must precede the final state in $states",
        )
        val done = states.last() as ImportUiState.Done
        assertEquals(1, done.summary.added)
        assertEquals(listOf("notes.rtf" to "format not supported"), done.summary.failed)
    }

    @Test
    fun `empty source list is a no-op and stays Idle`() = runTest(mainDispatcherRule.testDispatcher) {
        val vm = viewModel()

        vm.import(emptyList())

        assertEquals(ImportUiState.Idle, vm.importState.value)
        assertTrue(vm.library.value.isEmpty())
    }

    @Test
    fun `store add appends and dedupes by book id`() = runTest(mainDispatcherRule.testDispatcher) {
        val store = InMemoryLibraryStore()
        val entry = BookImporter(TextIndex()).import(
            source("Novel.epub", epubBook("Novel", "Chapter 1", "Prose here.")),
        ) as com.moronigranja.localttsreader.ebook.ImportOutcome.Added

        store.add(entry.entry)
        store.add(entry.entry)

        assertEquals(1, store.books.value.size)
        assertEquals("Novel", store.books.value.first().book.title)
    }
}
