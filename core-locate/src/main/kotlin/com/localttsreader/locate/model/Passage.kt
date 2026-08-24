package com.localttsreader.locate.model

/**
 * One atomic chunk of a book's text, as produced by the import pipeline: a passage
 * (typically a paragraph or section) within a chapter, carrying enough navigation
 * structure to display "Chapter N" and to resume playback exactly here.
 *
 * Passages are ordered chapter-major: chapter 0's passages, then chapter 1's, ...
 */
data class Passage(
    /** 0-based index of the chapter this passage belongs to. */
    val chapterIndex: Int,
    /** 0-based index of this passage within its chapter. */
    val passageIndex: Int,
    /** Chapter title when known; null for untitled chapters. */
    val title: String?,
    /** Raw text as parsed (never normalized; matching normalizes on the fly). */
    val text: String,
)
