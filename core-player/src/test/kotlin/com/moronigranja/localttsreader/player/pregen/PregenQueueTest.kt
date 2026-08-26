package com.moronigranja.localttsreader.player.pregen

import com.moronigranja.localttsreader.model.Book
import com.moronigranja.localttsreader.model.Chapter
import com.moronigranja.localttsreader.model.TextPassage
import com.moronigranja.localttsreader.player.PlayerPosition
import com.moronigranja.localttsreader.tts.SegmentAnchor
import com.moronigranja.localttsreader.tts.SynthesisOutcome
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** T5-core: bounded look-ahead, dedup, stale pruning, failure fallback. */
class PregenQueueTest {

    private val book = Book(
        id = "b1",
        title = "Anna",
        chapters = listOf(
            Chapter(0, "One", listOf(TextPassage("p0"), TextPassage("p1"), TextPassage("p2"))),
            Chapter(1, "Two", listOf(TextPassage("p3"), TextPassage("p4"))),
        ),
    )

    private var callCount = 0
    private var failIndex: Int? = null

    /** Deterministic fake: size = (spine index + 1) * 1000 bytes per passage. */
    private suspend fun fake(text: String): SynthesisOutcome {
        callCount++
        val spineIndex = text.drop(1).toInt()
        if (spineIndex == failIndex) return SynthesisOutcome.Failed("boom")
        val size = (spineIndex + 1) * 1_000
        val pcm = ByteArray(size) { ((it + spineIndex) % 256).toByte() }
        return SynthesisOutcome.Audio(
            pcm,
            24_000,
            1,
            listOf(SegmentAnchor(0.0, 1.0)),
        )
    }

    private fun queue(lookahead: Int = 2) =
        PregenQueue(book, "af_heart", 1.0, ::fake, lookahead)

    @Test
    fun `pre-generates the next passages within the bound`() = runTest {
        val q = queue()
        q.ensure(PlayerPosition("b1", 0, 0))
        assertEquals(2, q.size, "lookahead=2 fills immediately")
        assertEquals(2, callCount)

        val one = q.take(0, 1)
        assertEquals(2_000, one?.pcm?.size, "p1 = (1+1)*1000")
        assertEquals(listOf(SegmentAnchor(0.0, 1.0)), one?.segments)

        val fast = q.take(0, 2)
        assertEquals(3_000, fast?.pcm?.size, "p2 = (2+1)*1000")
        assertNull(q.take(0, 2), "take consumes")
    }

    @Test
    fun `ensure is idempotent - no re-synthesis of queued passages`() = runTest {
        val q = queue()
        q.ensure(PlayerPosition("b1", 0, 0))
        q.ensure(PlayerPosition("b1", 0, 0))
        assertEquals(2, q.size)
        assertEquals(2, callCount, "second ensure must not re-synthesize")
    }

    @Test
    fun `refills after the playhead advances`() = runTest {
        val q = queue()
        q.ensure(PlayerPosition("b1", 0, 0)) // queues 0/1, 0/2
        q.take(0, 1)
        q.ensure(PlayerPosition("b1", 0, 1)) // prunes 0/1-ish, refills toward 1/0
        val refill = q.take(0, 2)
        assertEquals(3_000, refill?.pcm?.size)
        assertEquals(3, callCount, "0/1 + 0/2 first, then 1/0 refill")
    }

    @Test
    fun `a jump forward prunes the stale look-ahead`() = runTest {
        val q = queue()
        q.ensure(PlayerPosition("b1", 0, 0)) // 0/1, 0/2 queued
        q.ensure(PlayerPosition("b1", 1, 0)) // jump: past passages drop, 1/1 + end queued
        assertNull(q.take(0, 1), "stale look-ahead pruned")
        val fresh = q.take(1, 1)
        assertEquals(5_000, fresh?.pcm?.size, "p4 = (4+1)*1000")
    }

    @Test
    fun `failed synthesis stops the look-ahead`() = runTest {
        failIndex = 1
        val q = queue(lookahead = 3)
        q.ensure(PlayerPosition("b1", 0, 0)) // p1 (spine 1) fails
        assertEquals(0, q.size, "p1 failed -> nothing queued past the gap")
        assertNull(q.take(0, 1))
    }

    @Test
    fun `book end yields nothing`() = runTest {
        val q = queue()
        q.ensure(PlayerPosition("b1", 1, 1)) // last passage: no look-ahead
        assertEquals(0, q.size)
    }

    @Test
    fun `clear drops everything`() = runTest {
        val q = queue()
        q.ensure(PlayerPosition("b1", 0, 0))
        q.clear()
        assertEquals(0, q.size)
        assertNull(q.take(0, 1))
    }

    @Test
    fun `PregenKey round-trips through its path form`() {
        val key = PregenKey("abc123", 2, 5, "af_heart", 1.5)
        assertEquals(key, PregenKey.parse(key.toString()))
        val intSpeed = PregenKey("abc123", 0, 0, "pf_dora", 1.0)
        assertEquals(intSpeed, PregenKey.parse(intSpeed.toString()))
        assertTrue(PregenKey.parse("") == null)
    }
}
