package com.moronigranja.localttsreader.locate

import com.moronigranja.localttsreader.model.Book
import com.moronigranja.localttsreader.model.CachedBook
import com.moronigranja.localttsreader.model.Chapter
import com.moronigranja.localttsreader.model.TextPassage

/**
 * Syncs a [TextIndex] to the cached library (P2): consumes the flat cached-parse
 * rows from storage — never a source file — which is the "never re-parse on
 * launch" contract.
 *
 * Semantics are mirror-set based, so a relaunch, a duplicate call, or a
 * concurrent import landing in the middle cannot corrupt the index:
 * - every cached book is (re)indexed from its cached passages (same id = same
 *   content hash = identical parse, so re-adding is a harmless overwrite);
 * - every indexed id absent from the cache is dropped.
 */
class IndexRebuilder(private val index: TextIndex) {

    fun rebuild(cache: List<CachedBook>) {
        for (cached in cache) {
            index.add(cached.toBook())
        }
        val cachedIds = cache.mapTo(HashSet(), CachedBook::id)
        for (id in index.bookIds()) {
            if (id !in cachedIds) index.remove(id)
        }
    }

    /** Reconstructs the canonical [Book] from the flat cache rows. */
    private fun CachedBook.toBook(): Book = Book(
        id = id,
        title = title,
        authors = authors,
        chapters = passages
            .groupBy { it.chapterIndex }
            .toSortedMap()
            .map { (chapterIndex, rows) ->
                Chapter(
                    index = chapterIndex,
                    title = rows.first().chapterTitle,
                    passages = rows.sortedBy { it.passageIndex }.map { TextPassage(it.text) },
                )
            },
    )
}
