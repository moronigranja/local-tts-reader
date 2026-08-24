package com.moronigranja.localttsreader.ebook

import com.moronigranja.localttsreader.ebook.ZipEntries.lookup
import com.moronigranja.localttsreader.model.Book
import com.moronigranja.localttsreader.model.Chapter
import com.moronigranja.localttsreader.model.TextPassage
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

/**
 * MOBI-family parser covering both halves of the format family:
 *
 * - **KF8 (.azw3/.kf8)** — MOBI header type 0xFFFFFFFF or 248; the text records
 *   concatenate into a ZIP whose root holds content.opf + XHTML; parsed through the
 *   shared [OpfBookReader] (nav/NCX chapter titles included).
 * - **MOBI7 (.mobi/.azw, plain PalmDOC)** — text records decompressed per
 *   compression (none / PalmDOC LZ77 / HUFF-CDIC), decoded with the header's
 *   codepage, extracted as one chapter's passages. Chapter boundaries via the NCX
 *   index are a documented follow-up; headings surface as passages.
 *
 * Real-world record trailing-data entries (MOBI extra-flags at 0xF0) are trimmed per
 * KindleUnpack semantics. DRM-encrypted files are rejected by [MobiContainer].
 */
object MobiParser : EBookParser {

    override fun parse(source: EBookSource): Book {
        val bytes = source.open().use { it.readBytes() }
        val base = source.fileName.substringBeforeLast('.').substringAfterLast('/')
        return parse(bytes, fallbackTitle = base.ifBlank { "Untitled" })
    }

    fun parse(bytes: ByteArray, fallbackTitle: String = "Untitled"): Book {
        val id = Bytes.sha256Hex(bytes)
        val container = MobiContainer(bytes)
        val mobiHeader = container.mobiHeader()
        if (mobiHeader != null && isKf8Type(mobiHeader)) {
            return parseKf8(id, container, mobiHeader, fallbackTitle)
        }
        return parseMobi7(id, container, mobiHeader, fallbackTitle)
    }

    private fun isKf8Type(mobiHeader: ByteArray): Boolean =
        mobiHeader.size >= 0x1C && (Bytes.u32(mobiHeader, 0x18) == 0xFFFFFFFFL || Bytes.u32(mobiHeader, 0x18) == 248L)

    // ------------------------------------------------------------------
    // KF8: text records = ZIP stream
    // ------------------------------------------------------------------

    private fun parseKf8(id: String, container: MobiContainer, mobiHeader: ByteArray, fallbackTitle: String): Book {
        if (container.compression == 2) {
            throw EBookParseException("KF8 with PalmDOC compression is invalid")
        }
        val huff = if (container.compression == MobiContainer.HUFF_CDIC) loadHuffCdic(container) else null
        val zipBytes = readTextRecords(container, huff)
        val entries = ZipEntries.readUntilBroken(zipBytes)
        val opfPath = entries.keys.firstOrNull { it.lowercase().endsWith(".opf") }
            ?: throw EBookParseException("KF8 archive has no OPF file")
        return OpfBookReader.parseBook(id, entries, opfPath, fallbackTitle)
    }

    // ------------------------------------------------------------------
    // MOBI7: text records = rawML markup
    // ------------------------------------------------------------------

    private fun parseMobi7(
        id: String,
        container: MobiContainer,
        mobiHeader: ByteArray?,
        fallbackTitle: String,
    ): Book {
        val huff = if (container.compression == MobiContainer.HUFF_CDIC) loadHuffCdic(container) else null
        val rawMl = readTextRecords(container, huff)
        val charset = if (mobiHeader != null && mobiHeader.size >= 0x20 && Bytes.u32(mobiHeader, 0x1C) == 65001L) {
            Charsets.UTF_8
        } else {
            Charset.forName("windows-1252")
        }
        val text = String(rawMl, charset)
        val passages = OpfBookReader.extractParagraphs(text).map(::TextPassage)
        if (passages.isEmpty()) throw EBookParseException("no readable text in mobi")

        val fullName = mobiHeader?.let { fullNameTitle(it) }
        val exthTitle = mobiHeader?.let { exthTitle(it, charset) }
        val title = exthTitle ?: fullName ?: container.palmName.takeIf { it.isNotBlank() } ?: fallbackTitle
        return Book(id, title, chapters = listOf(Chapter(0, null, passages)))
    }

    // ------------------------------------------------------------------
    // Shared record handling
    // ------------------------------------------------------------------

    private fun loadHuffCdic(container: MobiContainer): HuffCdicDecoder {
        val mobiHeader = container.mobiHeader() ?: throw EBookParseException("HUFF/CDIC compression without a MOBI header")
        if (mobiHeader.size < 0x78) throw EBookParseException("MOBI header too short for HUFF fields")
        val reader = HuffCdicDecoder()
        val huffOffset = Bytes.u32(mobiHeader, 0x70).toInt()
        val huffCount = Bytes.u32(mobiHeader, 0x74).toInt()
        if (huffCount < 2) throw EBookParseException("HUFF/CDIC compression without dictionary records")
        val huff = container.records.getOrNull(huffOffset)
            ?: throw EBookParseException("HUFF record missing (section $huffOffset)")
        reader.loadHuff(huff)
        for (i in 1 until huffCount) {
            val cdic = container.records.getOrNull(huffOffset + i)
                ?: throw EBookParseException("CDIC record missing (section ${huffOffset + i})")
            reader.loadCdic(cdic)
        }
        return reader
    }

    private fun readTextRecords(container: MobiContainer, huff: HuffCdicDecoder?): ByteArray {
        val out = ByteArrayOutputStream()
        val mobiHeader = container.mobiHeader()
        val trim = trailingTrimSetup(mobiHeader)
        for (i in 0 until container.textRecords) {
            val record = container.records.getOrNull(1 + i) ?: break
            var data = trim?.let { trimTrailingDataEntries(record, it.first, it.second) } ?: record
            when (container.compression) {
                1 -> out.write(data)
                2 -> out.write(Palmdoc.unpack(data))
                MobiContainer.HUFF_CDIC -> out.write(huff!!.unpack(data))
            }
        }
        return out.toByteArray()
    }

    /** (trailer count, multibyte) per the MOBI extra-flags at 0xF0 (v5/6+). */
    private fun trailingTrimSetup(mobiHeader: ByteArray?): Pair<Int, Boolean>? {
        if (mobiHeader == null || mobiHeader.size < 0xF4) return null
        val headerLength = Bytes.u32(mobiHeader, 0x14)
        val version = Bytes.u32(mobiHeader, 0x24)
        if (headerLength < 0xE4 || version < 5) return null
        var flags = Bytes.u16(mobiHeader, 0xF0)
        val multibyte = (flags and 1) != 0
        var trailers = 0
        while (flags > 1) {
            if ((flags and 2) != 0) trailers++
            flags = flags ushr 1
        }
        return trailers to multibyte
    }

    private fun trimTrailingDataEntries(data: ByteArray, trailers: Int, multibyte: Boolean): ByteArray {
        var result = data
        repeat(trailers) {
            var num = 0
            for (i in maxOf(0, result.size - 4) until result.size) {
                val v = result[i].toInt() and 0xFF
                if ((v and 0x80) != 0) num = 0
                num = (num shl 7) or (v and 0x7F)
            }
            if (num in 1 until result.size) result = result.copyOf(result.size - num)
        }
        if (multibyte && result.isNotEmpty()) {
            val num = (result.last().toInt() and 3) + 1
            if (num < result.size) result = result.copyOf(result.size - num)
        }
        return result
    }

    // ------------------------------------------------------------------
    // Titles
    // ------------------------------------------------------------------

    /** EXTH record type 503 ("Updated Title"). EXTH starts at 0x10 + header length. */
    private fun exthTitle(mobiHeader: ByteArray, charset: Charset): String? {
        if (mobiHeader.size < 0x84 || (Bytes.u32(mobiHeader, 0x80) and 0x40L) == 0L) return null
        val headerLength = Bytes.u32(mobiHeader, 0x14).toInt()
        val exthStart = 0x10 + headerLength
        if (exthStart + 12 > mobiHeader.size || !Bytes.hasText(mobiHeader, exthStart, "EXTH")) return null
        val count = Bytes.u32(mobiHeader, exthStart + 8).toInt()
        var pos = exthStart + 12
        for (i in 0 until count) {
            if (pos + 8 > mobiHeader.size) break
            val id = Bytes.u32(mobiHeader, pos).toInt()
            val size = Bytes.u32(mobiHeader, pos + 4).toInt()
            if (size < 8 || pos + size > mobiHeader.size) break
            if (id == 503) {
                val titleBytes = mobiHeader.copyOfRange(pos + 8, pos + size)
                // KF8 EXTH strings are occasionally UTF-16; try the declared charset first.
                val utf16 = try {
                    String(titleBytes, Charsets.UTF_16LE).takeIf { it.all { c -> c.isLetterOrDigit() || c.isWhitespace() || "-'.&()".contains(c) } }
                } catch (_: Exception) {
                    null
                }
                return (utf16?.takeIf { it.isNotBlank() } ?: String(titleBytes, charset).trim()).ifEmpty { null }
            }
            pos += size
        }
        return null
    }

    /** Full-name field (0x54 offset / 0x58 length, record-0-relative). */
    private fun fullNameTitle(mobiHeader: ByteArray): String? {
        if (mobiHeader.size < 0x5C) return null
        val offset = Bytes.u32(mobiHeader, 0x54).toInt()
        val length = Bytes.u32(mobiHeader, 0x58).toInt()
        if (offset < 0 || length <= 0 || offset + length > mobiHeader.size) return null
        return String(mobiHeader, offset, length, Charsets.UTF_8).trim().ifEmpty { null }
    }
}
