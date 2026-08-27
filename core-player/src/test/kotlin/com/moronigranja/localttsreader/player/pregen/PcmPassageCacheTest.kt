package com.moronigranja.localttsreader.player.pregen

import com.moronigranja.localttsreader.tts.SegmentAnchor
import java.io.File
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** T5-core disk tier: round-trip, LRU eviction, book deletion, atomicity. */
class PcmPassageCacheTest {

    @TempDir
    lateinit var tempDir: File

    private fun audio(seed: Int) = PregenAudio(
        pcm = ByteArray(2_000 + seed) { ((it + seed) % 256).toByte() },
        sampleRateHz = 24_000,
        segments = listOf(SegmentAnchor(0.5, 1.5)),
    )

    private fun key(chapter: Int, passage: Int) =
        PregenKey("book-$chapter-$passage", chapter, passage, "af_heart", 1.0)

    @Test
    fun `put and get round-trip pcm and anchors`() {
        val cache = PcmPassageCache(tempDir, maxBytes = Long.MAX_VALUE)
        cache.put(key(0, 1), audio(7))
        val loaded = cache.get(key(0, 1))
        assertArrayEquals(audio(7).pcm, loaded?.pcm)
        assertEquals(24_000, loaded?.sampleRateHz)
        assertEquals(listOf(SegmentAnchor(0.5, 1.5)), loaded?.segments)
        assertNull(cache.get(key(0, 2)), "missing key")
    }

    @Test
    fun `LRU eviction drops the least recently used under the byte cap`() {
        // each entry is ~2 KB; a 6 KB cap holds two entries (+ meta).
        val cache = PcmPassageCache(tempDir, maxBytes = 6_000)
        cache.put(key(0, 0), audio(0))
        cache.put(key(0, 1), audio(1))
        cache.put(key(0, 2), audio(2)) // evicts the first
        assertNull(cache.get(key(0, 0)), "oldest evicted")
        assertTrue(cache.get(key(0, 1)) != null, "kept")
        assertTrue(cache.get(key(0, 2)) != null, "kept")
        assertTrue(cache.totalBytes() <= 6_000, "cap enforced")
    }

    @Test
    fun `get refreshes LRU recency`() {
        val cache = PcmPassageCache(tempDir, maxBytes = 6_000)
        cache.put(key(0, 0), audio(0))
        cache.put(key(0, 1), audio(1))
        cache.get(key(0, 0)) // becomes the newest
        cache.put(key(0, 2), audio(2)) // evicts key(0,1) instead
        assertTrue(cache.get(key(0, 0)) != null)
        assertNull(cache.get(key(0, 1)), "least recently used evicted")
    }

    @Test
    fun `delete removes a single key and book removal clears the subtree`() {
        val cache = PcmPassageCache(tempDir, maxBytes = Long.MAX_VALUE)
        cache.put(key(0, 0), audio(0))
        cache.put(key(0, 1), audio(1))
        cache.delete(key(0, 0))
        assertNull(cache.get(key(0, 0)))
        assertTrue(cache.get(key(0, 1)) != null)

        cache.put(PregenKey("b2", 0, 0, "af_heart", 1.0), audio(3))
        cache.deleteBook("book-0-1")
        assertNull(cache.get(key(0, 1)))
        assertTrue(cache.get(PregenKey("b2", 0, 0, "af_heart", 1.0)) != null, "other book intact")
    }

    @Test
    fun `a tombstone-less missing pcm is null`() {
        val cache = PcmPassageCache(tempDir, maxBytes = Long.MAX_VALUE)
        assertNull(cache.get(key(9, 9)))
    }

    @Test
    fun `sizeOf reports the exact on-disk bytes per passage`() {
        val cache = PcmPassageCache(tempDir, maxBytes = Long.MAX_VALUE)
        assertNull(cache.sizeOf(key(0, 0)), "absent passage")
        cache.put(key(0, 0), audio(7))
        assertEquals(2_007L, cache.sizeOf(key(0, 0)), "the pcm file's length (meta excluded)")
        assertEquals(2_007L, cache.sizeOf(key(0, 0)), "stat reads don't mutate anything")
    }

    @Test
    fun `usageByBook sums every file per book subtree`() {
        val cache = PcmPassageCache(tempDir, maxBytes = Long.MAX_VALUE)
        cache.put(PregenKey("b1", 0, 0, "af_heart", 1.0), audio(0)) // 2000 + meta
        cache.put(PregenKey("b1", 0, 1, "af_heart", 1.0), audio(5)) // 2005 + meta
        cache.put(PregenKey("b2", 0, 0, "af_heart", 1.0), audio(9)) // 2009 + meta

        val usage = cache.usageByBook()
        assertEquals(setOf("b1", "b2"), usage.keys)
        // pcm bytes + the meta sidecars (small, counted but not bit-pinned).
        val b1Pcm = cache.sizeOf(PregenKey("b1", 0, 0, "af_heart", 1.0))!! +
            cache.sizeOf(PregenKey("b1", 0, 1, "af_heart", 1.0))!!
        val b2Pcm = cache.sizeOf(PregenKey("b2", 0, 0, "af_heart", 1.0))!!
        assertTrue(usage["b1"]!! > b1Pcm && usage["b1"]!! - b1Pcm in 2..200, "b1 includes sidecars")
        assertTrue(usage["b2"]!! > b2Pcm && usage["b2"]!! - b2Pcm in 2..200, "b2 includes sidecars")
        assertEquals(b1Pcm + (usage["b1"]!! - b1Pcm) + b2Pcm + (usage["b2"]!! - b2Pcm), usage.values.sum())
    }

    @Test
    fun `usageByBook is empty for an empty cache`() {
        val cache = PcmPassageCache(tempDir, maxBytes = Long.MAX_VALUE)
        assertTrue(cache.usageByBook().isEmpty())
    }
}
