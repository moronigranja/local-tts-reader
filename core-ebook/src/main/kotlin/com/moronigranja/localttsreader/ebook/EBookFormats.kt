package com.moronigranja.localttsreader.ebook

/**
 * Picks an [EBookParser] by file extension. Unsupported containers return null so the
 * import flow can tell the user "format not supported" instead of crashing.
 */
object EBookFormats {

    fun parserFor(fileName: String): EBookParser? =
        when (fileName.substringAfterLast('.', missingDelimiterValue = "").lowercase()) {
            "epub" -> EpubParser
            // azw3/kf8 and mobi/azw arrive with C2/C3.
            else -> null
        }
}
