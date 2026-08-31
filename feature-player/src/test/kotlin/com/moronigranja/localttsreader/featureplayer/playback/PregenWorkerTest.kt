package com.moronigranja.localttsreader.featureplayer.playback

import android.content.Context
import androidx.room.Room
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import com.moronigranja.localttsreader.model.Book
import com.moronigranja.localttsreader.model.CachedBook
import com.moronigranja.localttsreader.model.CachedPassage
import com.moronigranja.localttsreader.model.Chapter
import com.moronigranja.localttsreader.model.LibraryEntry
import com.moronigranja.localttsreader.model.TextPassage
import com.moronigranja.localttsreader.persistence.AppSettings
import com.moronigranja.localttsreader.persistence.LibraryDatabase
import com.moronigranja.localttsreader.persistence.RoomLibraryStore
import com.moronigranja.localttsreader.persistence.SettingsStore
import com.moronigranja.localttsreader.player.pregen.PregenBudget
import com.moronigranja.localttsreader.player.pregen.PregenKey
import com.moronigranja.localttsreader.tts.EngineSpec
import com.moronigranja.localttsreader.tts.EngineTier
import com.moronigranja.localttsreader.tts.SegmentAnchor
import com.moronigranja.localttsreader.tts.SynthesisOutcome
import com.moronigranja.localttsreader.tts.SynthesisRequest
import com.moronigranja.localttsreader.tts.TTSEngine
import com.moronigranja.localttsreader.tts.TtsPack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * CR-1 worker-level regression: the manual whole-book path (no
 * KEY_BUDGET_TIME_MS) must actually synthesize instead of silently
 * succeeding, finite expired budgets must not run, and engine failure
 * terminals must fail the job with a typed error rather than collapse into
 * an indistinguishable success.
 *
 * Runs the real [PregenWorker] (Robolectric: in-memory Room store/settings,
 * real [PregenCache] under the test app's filesDir, a faked [TTSEngine]
 * through the [KokoroRuntime] override seam).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PregenWorkerTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private lateinit var database: LibraryDatabase
    private lateinit var store: RoomLibraryStore
    private lateinit var settings: AppSettings
    private lateinit var cache: PregenCache
    private val scope = CoroutineScope(Dispatchers.IO)

    private val book = Book(
        id = "t42-worker-test-book",
        title = "Worker Test",
        chapters = listOf(
            Chapter(0, "One", listOf(TextPassage("p0"), TextPassage("p1"))),
            Chapter(1, "Two", listOf(TextPassage("p2"), TextPassage("p3"), TextPassage("p4"))),
        ),
    )

    /** Fake engine: records every request; outcome scriptable per test. */
    private class FakeEngine(
        var outcome: (String) -> SynthesisOutcome = {
            SynthesisOutcome.Audio(ByteArray(1_000), 24_000, 1, listOf(SegmentAnchor(0.0, 1.0)))
        },
        /** Per-text hook (e.g. to engage [PlaybackActive] mid-run). */
        var onText: (String) -> Unit = {},
    ) : TTSEngine {
        override val spec = EngineSpec("fake", "Fake", EngineTier.PRIMARY, setOf("en"))
        override val packs: List<TtsPack> = emptyList()
        val synthesized = mutableListOf<String>()

        suspend fun synthesizeText(text: String): SynthesisOutcome {
            synthesized += text
            onText(text)
            return outcome(text)
        }

        override suspend fun synthesize(request: SynthesisRequest): SynthesisOutcome =
            synthesizeText(request.text)
    }

    /** KokoroRuntime is open solely so host tests inject a fake engine. */
    private class FakeRuntime(
        context: Context,
        private val engine: TTSEngine?,
        override val failureReason: String? = null,
    ) : KokoroRuntime(context) {
        override fun engine(): TTSEngine? = engine
    }

    private fun worker(
        input: Data,
        engine: TTSEngine?,
    ): PregenWorker {
        val runtime = FakeRuntime(context, engine)
        // C1.5: the worker consumes the engine through the selector seam
        // (kokoro default — the system engine is never realized in tests).
        val selector = EngineSelector(
            runtime,
            object : dagger.Lazy<TTSEngine> {
                override fun get(): TTSEngine = error("system tts must not be used in kokoro tests")
            },
            settings,
        )
        val factory = object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters,
            ): ListenableWorker? =
                PregenWorker(appContext, workerParameters, selector, store, settings, cache)
        }
        return TestListenableWorkerBuilder<PregenWorker>(context)
            .setWorkerFactory(factory)
            .setInputData(input)
            .build()
    }

    private fun manualInput(vararg ids: String) = workDataOf(
        PregenWorker.KEY_MODE to PregenWorker.MODE_MANUAL,
        PregenWorker.KEY_BOOK_IDS to ids,
    )

    private fun toCached(book: Book): CachedBook =
        CachedBook(
            id = book.id,
            title = book.title,
            passages = book.chapters.flatMap { ch ->
                ch.passages.mapIndexed { i, p -> CachedPassage(ch.index, ch.title, i, p.text) }
            },
        )

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = RoomLibraryStore(database, scope)
        settings = AppSettings(SettingsStore(database.settingsDao()))
        cache = PregenCache(context)
        runBlocking { store.add(LibraryEntry(book, importedAtEpochMillis = 1L)) }
    }

    @After
    fun tearDown() {
        database.close()
        PlaybackActive.markStopped() // the G2 tests drive the global session flag
    }

    @Test
    fun `whole-book manual input without a budget synthesizes and caches`() = runBlocking {
        val engine = FakeEngine()
        val result = worker(manualInput(book.id), engine).doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals(listOf("p0", "p1", "p2", "p3", "p4"), engine.synthesized)
        assertTrue(
            "first passage written to the tier",
            cache.cache.contains(PregenKey(book.id, 0, 0, "af_heart", 1.0)),
        )
        assertTrue(
            "whole book reached the tier",
            book.chapters.all { ch ->
                ch.passages.indices.all { p ->
                    cache.cache.contains(PregenKey(book.id, ch.index, p, "af_heart", 1.0))
                }
            },
        )
    }

    @Test
    fun `an expired finite budget performs no synthesis`() = runBlocking {
        val engine = FakeEngine()
        val w = worker(manualInput(book.id), engine)
        // Virtual clock: every read advances 2s, so the 1s budget is already
        // spent when the first book's deadline is checked — the run must not
        // start (an unbounded budget is the only path that runs).
        var now = 0L
        val result = w.runBooks(
            books = listOf(toCached(book)),
            budget = PregenBudget(maxTimeMs = 1_000),
            voice = "af_heart",
            speed = 1.0,
            synthesize = engine::synthesizeText,
            clock = { now += 2_000; now },
        )
        assertTrue(result is ListenableWorker.Result.Success)
        assertTrue("an expired deadline must not synthesize", engine.synthesized.isEmpty())
    }

    @Test
    fun `an unbounded budget is not classified as expired`() = runBlocking {
        // The CR-1 defect: MANUAL_BUDGET (no maxTimeMs) was treated as an
        // expired deadline. Driving the loop with a far-advanced clock must
        // still run (only finite deadlines can expire).
        val engine = FakeEngine()
        val w = worker(manualInput(book.id), engine)
        var now = 999_000L
        val result = w.runBooks(
            books = listOf(toCached(book)),
            budget = PregenBudget(),
            voice = "af_heart",
            speed = 1.0,
            synthesize = engine::synthesizeText,
            clock = { now += 1L; now },
        )
        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals("unbounded runs until the book is done", 5, engine.synthesized.size)
    }

    // ------------------------------------------------------------------
    // G2 — manual runs yield to an engaged playback session
    // ------------------------------------------------------------------

    /** G2: the admission window covers manual runs too — with [PlaybackActive]
     * already engaged (playback live, or the post-stop fill synthesizing), the
     * run must not synthesize a single passage. */
    @Test
    fun `manual pregen breaks before any synthesis while playback is engaged`() = runBlocking {
        val engine = FakeEngine()
        val w = worker(manualInput(book.id), engine)
        PlaybackActive.markStarted()
        try {
            val result = w.runBooks(
                books = listOf(toCached(book)),
                budget = PregenBudget(), // unbounded — only the session can stop it
                voice = "af_heart",
                speed = 1.0,
                synthesize = engine::synthesizeText,
            )
            assertTrue(result is ListenableWorker.Result.Success)
            assertTrue(
                "an engaged session must pause the run before any synthesis",
                engine.synthesized.isEmpty(),
            )
        } finally {
            PlaybackActive.markStopped()
        }
    }

    /** G2: playback engaging MID-run yields at the next passage boundary — the
     * run settles as success (Yielded is a safely bounded terminal, CR-1)
     * without ever resuming, so manual pregen cannot compete with an active
     * session for the shared engine. */
    @Test
    fun `manual pregen yields mid-book when playback engages and never resumes`() = runBlocking {
        val engine = FakeEngine(onText = { text ->
            if (text == "p0") PlaybackActive.markStarted() // playback engages during the first passage
        })
        val w = worker(manualInput(book.id), engine)
        try {
            val result = w.runBooks(
                books = listOf(toCached(book)),
                budget = PregenBudget(), // unbounded — only the session can stop it
                voice = "af_heart",
                speed = 1.0,
                synthesize = engine::synthesizeText,
            )
            assertTrue(result is ListenableWorker.Result.Success)
            assertEquals(
                "the run yields at the boundary after playback engages",
                listOf("p0"),
                engine.synthesized,
            )
        } finally {
            PlaybackActive.markStopped()
        }
    }

    @Test
    fun `Unavailable settles as a failed job with a typed error`() = runBlocking {
        val engine = FakeEngine(outcome = { SynthesisOutcome.Unavailable })
        val result = worker(manualInput(book.id), engine).doWork()

        assertTrue(result is ListenableWorker.Result.Failure)
        val output = (result as ListenableWorker.Result.Failure).outputData
        val error = output.getString(PregenWorker.KEY_ERROR)
        assertTrue("error names the cause: $error", error != null && "unavailable" in error.lowercase())
    }

    @Test
    fun `synthesis meltdown settles as a failed job`() = runBlocking {
        val engine = FakeEngine(outcome = { SynthesisOutcome.Failed("boom") })
        val result = worker(manualInput(book.id), engine).doWork()

        assertTrue(result is ListenableWorker.Result.Failure)
        val output = (result as ListenableWorker.Result.Failure).outputData
        val error = output.getString(PregenWorker.KEY_ERROR)
        assertTrue("error names the meltdown: $error", error != null && "repeated synthesis failures" in error)
        assertEquals("no audio survived the meltdown", 0, output.getInt(PregenWorker.KEY_PROGRESS_SYNTHESIZED, 1))
    }

    @Test
    fun `missing engine fails before any work`() = runBlocking {
        val result = worker(manualInput(book.id), null).doWork()
        assertTrue(result is ListenableWorker.Result.Failure)
    }
}