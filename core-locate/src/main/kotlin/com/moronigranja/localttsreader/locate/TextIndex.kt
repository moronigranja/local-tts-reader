package com.moronigranja.localttsreader.locate

import com.moronigranja.localttsreader.locate.model.IndexedBook
import com.moronigranja.localttsreader.locate.model.MatchResult

/**
 * In-memory text index over imported books, populated by the import pipeline (§8):
 * every parsed book is added here so the share feature can identify book + passage.
 *
 * Stores per-passage precomputed gram sets, so a query only normalizes the snippet and
 * scores it against each passage (linear in total passage count — fine up to a few
 * thousand passages; an inverted n-gram index is the documented scaling follow-up).
 * add/remove/clear are synchronized; query works on a snapshot so it never blocks
 * concurrent indexing for longer than the snapshot copy.
 */
class TextIndex {

    private val books = linkedMapOf<String, IndexedBook>()
    private val gramSets = mutableMapOf<String, List<TextMatcher.PassageGrams>>()

    @Synchronized
    fun add(book: IndexedBook) {
        books[book.id] = book
        gramSets[book.id] = book.passages.map {
            TextMatcher.indexGrams(TextNormalizer.normalize(it.text))
        }
    }

    @Synchronized
    fun remove(bookId: String) {
        books.remove(bookId)
        gramSets.remove(bookId)
    }

    @Synchronized
    fun clear() {
        books.clear()
        gramSets.clear()
    }

    @Synchronized
    fun bookCount(): Int = books.size

    @Synchronized
    private fun snapshot(): List<Pair<IndexedBook, List<TextMatcher.PassageGrams>>> =
        books.values.map { book -> book to (gramSets[book.id] ?: emptyList()) }

    /**
     * Best match for [snippet] across all indexed books, or null when no passage reaches
     * [minConfidence] (the user-configurable threshold from settings, default 0.6).
     * Ties keep the earliest book/passage in index (insertion) order.
     */
    fun query(snippet: String, minConfidence: Double): MatchResult? {
        val normalized = TextNormalizer.normalize(snippet)
        if (normalized.isEmpty()) return null
        var best: MatchResult? = null
        for ((book, passageGrams) in snapshot()) {
            for ((index, passage) in book.passages.withIndex()) {
                val grams = passageGrams.getOrNull(index) ?: continue
                val recall = TextMatcher.scoreNormalized(normalized, grams)
                if (best == null || recall > best.confidence) {
                    best = MatchResult(book, passage, recall)
                }
            }
        }
        return best?.takeIf { it.confidence >= minConfidence }
    }
}
