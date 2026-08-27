package com.moronigranja.localttsreader.player.pregen

import com.moronigranja.localttsreader.model.Book
import com.moronigranja.localttsreader.model.Chapter
import com.moronigranja.localttsreader.model.TextPassage
import com.moronigranja.localttsreader.tts.SegmentAnchor
import com.moronigranja.localttsreader.tts.SynthesisOutcome
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Offline pre-gen (decisions #42): full-book synthesis into the disk tier. */
class OfflinePregenTest {

    private val book = Book(
        id = "b1",
        title = "Anna",
        chapters = listOf(
            Chapter(0, "One", listOf(TextPassage("p0"), TextPassage("p1"), TextPassage("p2"))),
            Chapter(1, "Two", listOf(TextPassage("p3"), TextPassage("p4"))),
        ),
    )

    @TempDir
    lateinit var tmp: File

    private val voice = "af_heart"
    private val speed = 1.0

    private val synthesized = mutableListOf<String>()
    private var failTexts: Set<String> = emptySet()
    private var unavailableText: String? = null

    /** Deterministic fake: uniform 1000-byte passages (the congestion cases stay byte-exact). */
    private suspend fun fake(text: String): SynthesisOutcome {
        synthesized += text
        if (text in failTexts) return SynthesisOutcome.Failed("boom")
        if (text == unavailableText) return SynthesisOutcome.Unavailable
        return SynthesisOutcome.Audio(
            ByteArray(1_000) { it.toByte() },
            24_000,
            1,
            listOf(SegmentAnchor(0.0, 1.0)),
        )
    }

    private fun cache(maxBytes: Long = PcmPassageCache.DEFAULT_MAX_BYTES) =
        PcmPassageCache(File(tmp, "cache-$counter"), maxBytes).also { counter++ }

    private var counter = 0

    private fun runner(
        cache: PcmPassageCache,
        cap: Int = 5,
        clock: () -> Long = System::currentTimeMillis,
        shouldContinue: () -> Boolean = { true },
    ) = OfflinePregen(cache, ::fake, cap, shouldContinue, clock)

    private fun seed(cache: PcmPassageCache, spineIndexes: List<Int>, bytes: Int = 1_000) {
        for (spine in spineIndexes) {
            val (c, p) = spineToPosition(spine)
            cache.put(
                PregenKey(book.id, c, p, voice, speed),
                PregenAudio(ByteArray(bytes), 24_000, listOf(SegmentAnchor(0.0, 1.0))),
            )
        }
    }

    /** Spine order over the two chapters: 0/0, 0/1, 0/2, 1/0, 1/1. */
    private fun spineToPosition(spine: Int): Pair<Int, Int> = when (spine) {
        0 -> 0 to 0
        1 -> 0 to 1
        2 -> 0 to 2
        3 -> 1 to 0
        else -> 1 to 1
    }

    @Test
    fun `synthesizes every passage in spine order`() = runTest {
        val result = runner(cache()).run(book, voice, speed)
        assertEquals(5, result.passagesSynthesized)
        assertEquals(0, result.passagesCached)
        assertEquals(listOf("p0", "p1", "p2", "p3", "p4"), synthesized)
        assertTrue(result.percent == 100, "full walk -> 100%")
        assertEquals(PregenTerminal.Completed, result.terminal)
    }

    @Test
    fun `skips passages already on disk`() = runTest {
        val cache = cache()
        seed(cache, listOf(1, 3))
        val result = runner(cache).run(book, voice, speed)
        assertEquals(listOf("p0", "p2", "p4"), synthesized, "cached passages not re-synthesized")
        assertEquals(2, result.passagesCached)
        assertEquals(3, result.passagesSynthesized)
    }

    @Test
    fun `maxPassages budget stops the walk`() = runTest {
        val result = runner(cache()).run(book, voice, speed, PregenBudget(maxPassages = 2))
        assertEquals(listOf("p0", "p1"), synthesized)
        assertEquals(2, result.processed)
        assertEquals(40, result.percent)
        assertEquals(PregenTerminal.BudgetExhausted, result.terminal)
    }

    @Test
    fun `maxChapters budget stops after whole chapters`() = runTest {
        val result = runner(cache()).run(book, voice, speed, PregenBudget(maxChapters = 1))
        assertEquals(listOf("p0", "p1", "p2"), synthesized)
        assertEquals(1, result.chaptersDone)
        assertEquals(60, result.percent)
        assertEquals(PregenTerminal.BudgetExhausted, result.terminal)
    }

    @Test
    fun `maxTimeMs budget stops mid-book`() = runTest {
        var now = 0L
        val result = runner(cache(), clock = { now += 1_000; now })
            .run(book, voice, speed, PregenBudget(maxTimeMs = 2_500))
        assertEquals(listOf("p0", "p1"), synthesized, "two passages fit in 2.5s of virtual time")
        assertEquals(2, result.processed)
        assertEquals(PregenTerminal.BudgetExhausted, result.terminal)
    }

    @Test
    fun `split runs resume from the cache`() = runTest {
        val cache = cache()
        val first = runner(cache).run(book, voice, speed, PregenBudget(maxPassages = 2))
        assertEquals(listOf("p0", "p1"), synthesized)
        synthesized.clear()
        val second = runner(cache).run(book, voice, speed)
        assertEquals(listOf("p2", "p3", "p4"), synthesized, "run 2 re-walks the cached prefix, then finishes")
        assertEquals(2, second.passagesCached)
        assertEquals(3, second.passagesSynthesized)
        assertEquals(100, second.percent)
        assertEquals(PregenTerminal.Completed, second.terminal)
    }

    @Test
    fun `isolated failures are counted and the walk continues`() = runTest {
        failTexts = setOf("p1", "p3")
        val result = runner(cache()).run(book, voice, speed)
        assertEquals(listOf("p0", "p1", "p2", "p3", "p4"), synthesized, "failed passages still walked")
        assertEquals(2, result.failures)
        assertEquals(3, result.passagesSynthesized)
        assertEquals(PregenTerminal.Completed, result.terminal, "isolated failures do not fail the run")
    }

    @Test
    fun `consecutive failures stop the run`() = runTest {
        failTexts = setOf("p1", "p2")
        val result = runner(cache(), cap = 2).run(book, voice, speed)
        assertEquals(listOf("p0", "p1", "p2"), synthesized)
        assertEquals(2, result.failures)
        assertEquals(1, result.passagesSynthesized)
        assertEquals(PregenTerminal.FailureCap, result.terminal)
    }

    @Test
    fun `Unavailable stops immediately - packs are gone for the whole run`() = runTest {
        unavailableText = "p1"
        val result = runner(cache()).run(book, voice, speed)
        assertEquals(listOf("p0", "p1"), synthesized)
        assertEquals(1, result.passagesSynthesized)
        assertEquals(0, result.failures, "Unavailable is a state, not a passage failure")
        assertEquals(PregenTerminal.Unavailable, result.terminal)
    }

    @Test
    fun `a saturated cache stops the run - a put would only evict`() = runTest {
        // Cap 4k: 8 x ~1030B seeds settle to 3 retained entries (p0..p2, ~3090B, ~910B free).
        val tiny = cache(maxBytes = 4_000)
        seed(tiny, listOf(0, 1, 2, 3, 4))
        seed(tiny, listOf(0, 1, 2))
        val result = runner(tiny).run(book, voice, speed)
        assertEquals(listOf("p3"), synthesized, "p3 fits; p4 is as big as the free space -> stop")
        assertEquals(3, result.passagesCached)
        assertEquals(1, result.passagesSynthesized)
        assertEquals(4, result.processed)
        assertEquals(PregenTerminal.CacheSaturated, result.terminal)
    }

    @Test
    fun `shouldContinue yields between passages`() = runTest {
        var gates = 0
        val result = runner(cache(), shouldContinue = { gates++ < 3 }).run(book, voice, speed)
        assertEquals(listOf("p0", "p1"), synthesized)
        assertEquals(2, result.processed)
        assertEquals(PregenTerminal.Yielded, result.terminal)
    }

    @Test
    fun `cancellation propagates at the synthesis boundary`() = runTest {
        suspend fun gate(text: String): SynthesisOutcome {
            awaitCancellation()
        }
        val blocker = OfflinePregen(cache(), ::gate)
        var thrown: Throwable? = null
        val job: Job = launch {
            try {
                blocker.run(book, voice, speed)
            } catch (e: CancellationException) {
                thrown = e
                throw e
            }
        }
        testScheduler.runCurrent() // the first synthesis is now parked on awaitCancellation
        job.cancel()
        job.join()
        assertTrue(thrown is CancellationException, "run rethrows the caller's cancellation")
    }

    @Test
    fun `progress events are monotonic and end at the final state`() = runTest {
        val events = mutableListOf<PregenProgress>()
        val result = runner(cache()).run(book, voice, speed, PregenBudget(), events::add)
        assertTrue(events.isNotEmpty())
        assertEquals(result, events.last())
        assertEquals(PregenTerminal.Completed, events.last().terminal, "the final event carries the terminal")
        val percents = events.map { it.percent }
        assertEquals(percents.sorted(), percents, "percent never decreases")
        assertTrue(events.size >= result.processed, "at least one event per processed passage")
    }
}