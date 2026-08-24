package com.moronigranja.localttsreader.ebook

import com.moronigranja.localttsreader.model.Book
import java.io.InputStream

/**
 * Failure to parse an ebook container into a [Book]. Thrown for missing/broken
 * containers, malformed XML, empty spines — never caught as a crash: the import
 * flow maps it to a user-visible "could not import" state with [message].
 */
class EBookParseException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Where a format parser reads a book from. The [open] stream is owned by the caller
 * of the factory (import flow); parsers wrap it in `use {}` and read it fully.
 */
data class EBookSource(
    val fileName: String,
    val open: () -> InputStream,
)

/**
 * Adapts a format parser to the common domain model ([Book]). One implementation per
 * container format; [EBookFormats.parserFor] picks the implementation by file name.
 */
interface EBookParser {
    fun parse(source: EBookSource): Book
}
