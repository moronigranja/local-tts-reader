package com.moronigranja.localttsreader.player.pregen

import com.moronigranja.localttsreader.model.Book
import com.moronigranja.localttsreader.tts.kokoro.KokoroEngine

/**
 * Storage transparency (decisions #44): what pre-generating a book costs.
 *
 * Exact bytes for passages already in the cache (the cache is the source of
 * truth — [PcmPassageCache.sizeOf] is the on-disk file size); estimated
 * bytes otherwise: PCM at `KokoroEngine.SAMPLE_RATE`, 16-bit mono, × the
 * estimated speaking time — [charsPerSecond] at 1.0×, scaled by [speed]
 * (speed is part of the cache key, #35; the worker pre-generates at 1.0).
 *
 * Estimates are informational: one listened hour ≈ 170 MB (24 kHz 16-bit
 * mono). The default rate (~150–180 wpm English) is a safe middle ground,
 * deliberately not a measured per-voice rate.
 */
class PregenSpaceEstimator(
    private val cache: PcmPassageCache,
    private val charsPerSecond: Double = DEFAULT_CHARS_PER_SECOND,
) {

    init {
        require(charsPerSecond > 0) { "charsPerSecond must be positive" }
    }

    fun estimate(book: Book, voice: String, speed: Double): PregenSpaceEstimate {
        require(speed > 0) { "speed must be positive" }
        var total = 0L
        var cached = 0L
        for (chapter in book.chapters) {
            for ((passageIndex, passage) in chapter.passages.withIndex()) {
                val key = PregenKey(book.id, chapter.index, passageIndex, voice, speed)
                val exact = cache.sizeOf(key)
                if (exact != null) {
                    total += exact
                    cached += exact
                } else {
                    val seconds = passage.text.length / charsPerSecond / speed
                    total += (seconds * BYTES_PER_SECOND).toLong()
                }
            }
        }
        return PregenSpaceEstimate(total, cached)
    }

    companion object {
        /** ~150–180 wpm English at 1.0×; the exact cached part always wins. */
        const val DEFAULT_CHARS_PER_SECOND = 15.0

        private val BYTES_PER_SECOND = KokoroEngine.SAMPLE_RATE * 2L
    }
}

/** [totalBytes] = cached-exact + estimated; [cachedBytes] = the exact part only. */
data class PregenSpaceEstimate(val totalBytes: Long, val cachedBytes: Long)