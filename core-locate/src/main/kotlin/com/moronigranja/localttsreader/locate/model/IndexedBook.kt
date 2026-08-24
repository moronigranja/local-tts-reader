package com.moronigranja.localttsreader.locate.model

/**
 * A book loaded into the library, with its full ordered passage list.
 *
 * Built by the file-import pipeline (agents.md §8: **every imported book must also be
 * indexed**) and handed to [com.moronigranja.localttsreader.locate.TextIndex] so the share feature
 * can identify book + passage from shared text.
 */
data class IndexedBook(
    /** Stable id assigned by the import pipeline (e.g., content hash or DB id). */
    val id: String,
    val title: String,
    val passages: List<Passage>,
)
