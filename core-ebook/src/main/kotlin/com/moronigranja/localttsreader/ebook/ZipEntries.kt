package com.moronigranja.localttsreader.ebook

import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.zip.ZipInputStream

/**
 * ZIP container reading shared by the EPUB and KF8 parsers.
 *
 * - [readAll]: strict — a broken container throws [EBookParseException] (EPUB).
 * - [readUntilBroken]: returns whatever parsed before the first broken section, so an
 *   archive that trails non-zip data (e.g. KF8 with trailing resource records) still
 *   yields its files; only an archive that yields nothing throws.
 */
internal object ZipEntries {

    fun readAll(bytes: ByteArray): Map<String, ByteArray> {
        val entries = read(bytes, lenient = false)
        if (entries.isEmpty()) throw EBookParseException("ebook container is empty")
        return entries
    }

    fun readUntilBroken(bytes: ByteArray): Map<String, ByteArray> {
        val entries = read(bytes, lenient = true)
        if (entries.isEmpty()) throw EBookParseException("archive has no readable content")
        return entries
    }

    private fun read(bytes: ByteArray, lenient: Boolean): Map<String, ByteArray> {
        val entries = linkedMapOf<String, ByteArray>()
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val path = normalizePath(entry.name)
                        if (path.isNotEmpty()) entries[path] = zip.readBytes()
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        } catch (e: IOException) {
            if (!lenient) throw EBookParseException("not a valid zip/ebook container", e)
        }
        return entries
    }

    fun normalizePath(path: String): String {
        val parts = path.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
        val out = mutableListOf<String>()
        for (part in parts) {
            if (part == "..") {
                if (out.isNotEmpty()) out.removeAt(out.lastIndex)
            } else {
                out += part
            }
        }
        return out.joinToString("/")
    }

    /** Case-insensitive path lookup: EPUB paths are case-sensitive, real files are sloppy. */
    fun Map<String, ByteArray>.lookup(path: String): ByteArray? {
        get(path)?.let { return it }
        val lower = path.lowercase()
        for ((key, value) in this) if (key.lowercase() == lower) return value
        return null
    }
}
