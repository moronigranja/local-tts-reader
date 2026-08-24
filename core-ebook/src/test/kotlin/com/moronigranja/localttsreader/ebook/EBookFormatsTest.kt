package com.moronigranja.localttsreader.ebook

import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class EBookFormatsTest {

    @Test
    fun `detects epub case-insensitively`() {
        assertSame(EpubParser, EBookFormats.parserFor("book.epub"))
        assertSame(EpubParser, EBookFormats.parserFor("BOOK.EPUB"))
    }

    @Test
    fun `unsupported and extensionless files return null`() {
        assertNull(EBookFormats.parserFor("book.azw3")) // arrives with C2
        assertNull(EBookFormats.parserFor("book.mobi")) // arrives with C3
        assertNull(EBookFormats.parserFor("book"))
        assertNull(EBookFormats.parserFor(""))
    }
}
