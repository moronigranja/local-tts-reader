package com.moronigranja.localttsreader.player

import com.moronigranja.localttsreader.model.Book

/**
 * Reader-orientation math (decisions #50 gap pass): where the playhead is
 * in the book (fraction) and how much listening time remains at the current
 * speed.
 *
 * The fraction is passage-granular like the resume row (completed passages
 * incl. the current one over the book's total). Remaining time uses the same
 * chars-per-second estimate as [com.moronigranja.localttsreader.player.pregen.PregenSpaceEstimator]
 * — informational, deliberately not a measured per-voice rate; the
 * estimate's timing at 1.0× is divided by [speed] (positions are book-time,
 * decision #29).
 */
object BookProgress {

    const val DEFAULT_CHARS_PER_SECOND = 15.0

    /** [0..1]: completed passages (incl. current) over the book's total. */
    fun fraction(book: Book, chapterIndex: Int, passageIndex: Int): Float {
        val total = book.chapters.sumOf { it.passages.size }
        if (total == 0) return 0f
        var before = 0
        for (chapter in book.chapters) {
            if (chapter.index < chapterIndex) {
                before += chapter.passages.size
            } else if (chapter.index == chapterIndex) {
                before += passageIndex.coerceIn(0, chapter.passages.size)
                break
            }
        }
        return ((before + 1).toFloat() / total).coerceIn(0f, 1f)
    }

    /** Listening time left in the book at [speed], from the playhead. */
    fun remainingSeconds(
        book: Book,
        chapterIndex: Int,
        passageIndex: Int,
        offsetSeconds: Double,
        speed: Double,
        charsPerSecond: Double = DEFAULT_CHARS_PER_SECOND,
    ): Double {
        if (book.chapters.isEmpty()) return 0.0
        val rate = speed.coerceAtLeast(0.01) * charsPerSecond
        var remaining = 0.0
        var seenCurrent = false
        for (chapter in book.chapters) {
            for ((index, passage) in chapter.passages.withIndex()) {
                val sectionSeconds = passage.text.length / rate
                if (chapter.index == chapterIndex && index == passageIndex) {
                    // Offset is book-time at 1.0×; the passage's 1.0× duration
                    // minus the playhead, scaled to the current speed.
                    remaining += ((passage.text.length / charsPerSecond) - offsetSeconds).coerceAtLeast(0.0) / speed.coerceAtLeast(0.01)
                    seenCurrent = true
                } else if (seenCurrent) {
                    remaining += sectionSeconds
                }
            }
        }
        return remaining
    }
}