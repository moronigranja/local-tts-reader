package com.localttsreader.locate.model

/**
 * Outcome of locating a shared snippet: the best book/passage and how confident the
 * match is. [confidence] is a recall fraction in 0..1 (1.0 = the snippet is fully
 * contained in the passage). A null [TextIndex.query] result means no confident match
 * (below the user-configurable threshold, default 0.6).
 */
data class MatchResult(
    val book: IndexedBook,
    val passage: Passage,
    val confidence: Double,
)
