package com.moronigranja.localttsreader.player.pregen

import com.moronigranja.localttsreader.model.Book
import com.moronigranja.localttsreader.player.BookLayout
import com.moronigranja.localttsreader.player.PlayerPosition
import com.moronigranja.localttsreader.player.passageText
import com.moronigranja.localttsreader.tts.SynthesisOutcome

/**
 * T5 pre-generation (decisions #35): synthesizes the passages ahead of the
 * playhead while the current one plays, so a passage change is a [take] fast
 * path instead of a synthesize-then-play gap. In-memory and bounded at
 * [lookahead]; [PcmPassageCache] is the post-v1 disk tier.
 *
 * Contract:
 * - [ensure] walks the [BookLayout] after [PlayerPosition] and synthesizes
 *   the missing passages up to the bound. Single-flight: concurrent callers
 *   serialize on the internal lock; [take] runs lock-free against the map so
 *   the play loop never waits on synthesis.
 * - Entries at/before the playhead are pruned on [ensure]; a jump forward
 *   prunes the now-stale look-ahead and refills.
 * - A failed synthesis stops the look-ahead: nothing is queued past the gap,
 *   and the player's synchronous synthesize path is the typed-failure
 *   fallback — never a placeholder.
 */
class PregenQueue(
    private val book: Book,
    private val voice: String,
    private val speed: Double,
    private val synthesize: suspend (text: String) -> SynthesisOutcome,
    private val lookahead: Int = 2,
) {
    private val layout = BookLayout(book)
    private val lock = Object()
    private val entries = LinkedHashMap<PregenKey, PregenAudio>()

    /** Synthesizes the passages after [from] that fit in the bound. */
    suspend fun ensure(from: PlayerPosition) {
        val plan = synchronized(lock) {
            entries.keys.removeAll { key -> !isAfter(key, from) }
            shrinkToBound()
            val missing = mutableListOf<PregenKey>()
            var (chapter, passage) = from.chapterIndex to from.passageIndex
            while (entries.size + missing.size < lookahead) {
                val next = layout.next(chapter, passage) ?: break
                val (c, p) = next
                val key = PregenKey(book.id, c, p, voice, speed)
                if (!entries.containsKey(key) && key !in missing) missing += key
                chapter = c
                passage = p
            }
            missing
        }
        for (key in plan) {
            val text = book.passageText(key.chapterIndex, key.passageIndex) ?: break
            val audio = convert(synthesize(text)) ?: break
            synchronized(lock) {
                if (!entries.containsKey(key)) {
                    entries[key] = audio
                    shrinkToBound()
                }
            }
        }
    }

    /** The queued audio for the passage, consumed; null when not pre-generated. */
    fun take(chapterIndex: Int, passageIndex: Int): PregenAudio? =
        synchronized(lock) { entries.remove(PregenKey(book.id, chapterIndex, passageIndex, voice, speed)) }

    fun clear() = synchronized(lock) { entries.clear() }

    val size: Int get() = synchronized(lock) { entries.size }

    private fun shrinkToBound() {
        while (entries.size > lookahead) entries.remove(entries.keys.last())
    }

    private fun isAfter(key: PregenKey, from: PlayerPosition): Boolean =
        key.chapterIndex > from.chapterIndex ||
            (key.chapterIndex == from.chapterIndex && key.passageIndex > from.passageIndex)

    private fun convert(outcome: SynthesisOutcome): PregenAudio? =
        (outcome as? SynthesisOutcome.Audio)?.let { PregenAudio(it.pcm, it.sampleRateHz, it.segments) }
}
