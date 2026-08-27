package com.moronigranja.localttsreader.ebook

import com.moronigranja.localttsreader.model.Book
import com.moronigranja.localttsreader.model.Chapter
import com.moronigranja.localttsreader.model.TextPassage

/**
 * Passage segmentation stage — the grain contract for matching and resume.
 *
 * The **passage is the unit of share-and-identify matching and of playback resume**,
 * so its grain directly sets match precision (a snippet lands on the passage that
 * contains it; resuming starts at that passage's beginning). Rules:
 *
 * - **Paragraph grain**: each block from the parser is its own passage (already the
 *   extraction contract). Passages longer than [DEFAULT_MAX_PASSAGE_WORDS] are split
 *   at sentence boundaries into smaller chunks, so a long paragraph still yields a
 *   precise resume target. Text without usable sentence boundaries stays whole.
 * - **Front/back matter**: a contiguous leading run of front-matter chapters (Title
 *   Page, Copyright, Table of Contents, Dedication, …) and a contiguous trailing run
 *   of back matter (Index, About the Author, …) are dropped — contiguous, so a run
 *   of any length is removed (books list the TOC at any spine depth), while a novel
 *   whose *middle* chapter is literally called "Index" is untouched. If stripping
 *   would remove the whole book, the book is returned unchanged.
 * - **Empty passages**: any passage whose text contains no letters (e.g. a
 *   scene-break marker "* * *" or "···") is dropped — otherwise synthesis reads it
 *   aloud as noise.
 * - **Dense renumbering**: kept chapters are renumbered contiguously from 0, so the
 *   reader/layout never holds empty chapter slots and `chapterIndex` is always a
 *   valid list position (the reader's chapter menu and resume rows use it).
 *
 * **Contract with TextIndex** (see docs/features/share-and-identify.md): the import
 * pipeline MUST run [segment] on a parsed book before `TextIndex.add(...)`. Passages
 * must be stable across re-parses of the same file; renumbering is stable for a given
 * file (same content → same kept set → same indexes).
 */
object BookSegmentation {

    const val DEFAULT_MAX_PASSAGE_WORDS = 100

    private val FRONT_MATTER = setOf(
        "title page", "copyright", "colophon", "table of contents", "contents",
        "dedication", "epigraph",
    )
    private val BACK_MATTER = setOf(
        "about the author", "also by", "also available", "books by",
        "advertisement", "advertisements", "index",
    )
    fun segment(book: Book, maxPassageWords: Int = DEFAULT_MAX_PASSAGE_WORDS): Book {
        if (book.chapters.isEmpty()) return book
        // By containment, not equality: real spines name furniture liberally
        // ("Copyright Notice", "Table of Contents", "Books by the Author").
        val isFrontMatter = { chapter: Chapter ->
            val key = chapter.title?.trim()?.lowercase().orEmpty()
            key.isNotEmpty() && FRONT_MATTER.any { key.contains(it) }
        }
        val isBackMatter = { chapter: Chapter ->
            val key = chapter.title?.trim()?.lowercase().orEmpty()
            key.isNotEmpty() && BACK_MATTER.any { key.contains(it) }
        }
        // Strip a contiguous leading front-matter run and trailing back-matter run.
        var start = 0
        while (start < book.chapters.size && isFrontMatter(book.chapters[start])) start++
        var end = book.chapters.size
        while (end > start && isBackMatter(book.chapters[end - 1])) end--
        val kept = book.chapters.subList(start, end)
            .mapIndexed { index, chapter ->
                chapter.copy(
                    index = index,
                    passages = splitLongPassages(chapter.passages, maxPassageWords)
                        .filter { it.text.any { c -> c.isLetter() } },
                )
            }
        // Safety net: never strip an entire book.
        return if (kept.isEmpty()) book else book.copy(chapters = kept)
    }

    /** Split over-long passages at sentence boundaries; paragraph grain otherwise untouched. */
    fun splitLongPassages(passages: List<TextPassage>, maxPassageWords: Int): List<TextPassage> {
        if (maxPassageWords <= 0) return passages
        return passages.flatMap { passage ->
            if (wordCount(passage.text) <= maxPassageWords) {
                listOf(passage)
            } else {
                val sentences = passage.text.split(SENTENCE_SPLIT).map { it.trim() }.filter { it.isNotEmpty() }
                if (sentences.size <= 1) {
                    listOf(passage) // no usable boundaries: keep whole rather than mid-sentence cuts
                } else {
                    val chunks = mutableListOf<String>()
                    val current = StringBuilder()
                    var currentWords = 0
                    for (sentence in sentences) {
                        val sentenceWords = wordCount(sentence)
                        if (currentWords > 0 && currentWords + sentenceWords > maxPassageWords) {
                            chunks += current.toString()
                            current.clear()
                            currentWords = 0
                        }
                        if (currentWords > 0) current.append(' ')
                        current.append(sentence)
                        currentWords += sentenceWords
                    }
                    if (currentWords > 0) chunks += current.toString()
                    chunks.map(::TextPassage)
                }
            }
        }
    }

    fun wordCount(text: String): Int =
        text.split(WHITESPACE).count { it.isNotBlank() }

    /** Sentence boundary: punctuation + whitespace, only when a sentence truly continues
     *  (uppercase letter, quote, digit) — so "Dr. Watson" and "e.g." survive. */
    private val SENTENCE_SPLIT = Regex("""(?<=[.!?…])\s+(?=["'«“”‘’A-ZÀ-ÖØ-Þ0-9])""")
    private val WHITESPACE = Regex("\\s+")
}