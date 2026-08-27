package com.moronigranja.localttsreader.player.pregen

import java.io.File

/**
 * Post-v1 disk tier for pre-generated passages (decisions #35): raw PCM with
 * a `.meta` sidecar (sample rate + sentence anchors) under
 * `<root>/<bookId>/<voice>/<speed>/c<ch>p<passage>.pcm` — the layout is the
 * [PregenKey] path, so book removal deletes a `bookId` subtree (content-hash
 * ids, decisions #11) and engine+voice+speed are part of the key (#31/#34).
 *
 * LRU eviction by a byte cap tracked in-process (walls off filesystem
 * timestamp quirks — an accelerator cache may lose entries freely). Writes
 * are atomic (tmp + rename); `get` re-validates the sidecar before returning.
 */
class PcmPassageCache(
    private val root: File,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) {

    private val lock = Object()
    // accessOrder=true: re-put/read moves the key to the end; first = LRU.
    private val recency = object : LinkedHashMap<PregenKey, Long>(16, 0.75f, true) {}
    private var now = 0L

    fun put(key: PregenKey, audio: PregenAudio) = synchronized(lock) {
        val pcmFile = pcmFile(key)
        pcmFile.parentFile?.mkdirs()
        val tmp = File(pcmFile.parentFile, pcmFile.name + ".tmp")
        tmp.writeBytes(audio.pcm)
        File(pcmFile.parentFile, pcmFile.nameWithoutExtension + ".meta").writeText(meta(audio))
        if (!tmp.renameTo(pcmFile)) {
            tmp.delete()
            return // another writer won the race; the cache stays consistent
        }
        now++
        recency[key] = now
        evictLocked()
    }

    fun get(key: PregenKey): PregenAudio? = synchronized(lock) {
        val pcmFile = pcmFile(key)
        if (!pcmFile.isFile) return null
        val metaFile = File(pcmFile.parentFile, pcmFile.nameWithoutExtension + ".meta")
        val meta = metaFile.takeIf { it.isFile }?.readText()?.trim() ?: return null
        val parsed = parseMeta(meta) ?: return null
        now++
        recency[key] = now
        PregenAudio(pcmFile.readBytes(), parsed.first, parsed.second)
    }
    /** Existence check without reading the PCM — the pregen planner's skip-check. */
    fun contains(key: PregenKey): Boolean = synchronized(lock) { pcmFile(key).isFile }

    /** Free bytes under the cap; the planner stops at 0 (a put would only evict). */
    fun bytesRemaining(): Long = synchronized(lock) { (maxBytes - totalBytesLocked()).coerceAtLeast(0) }

    /** Exact on-disk bytes for one passage (the .pcm file), or null when not cached. */
    fun sizeOf(key: PregenKey): Long? = synchronized(lock) {
        val file = pcmFile(key)
        if (file.isFile) file.length() else null
    }

    /** Bytes on disk per book — pcm + sidecar files under each `bookId` subtree (decisions #44). */
    fun usageByBook(): Map<String, Long> = synchronized(lock) {
        root.listFiles()
            ?.filter { it.isDirectory }
            ?.associate { dir -> dir.name to dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() } }
            ?.filterValues { it > 0L }
            ?: emptyMap()
    }

    fun delete(key: PregenKey) = synchronized(lock) {
        val pcmFile = pcmFile(key)
        pcmFile.delete()
        File(pcmFile.parentFile, pcmFile.nameWithoutExtension + ".meta").delete()
        recency.remove(key)
        pruneEmptyDirs(root, pcmFile.parentFile)
    }

    fun deleteBook(bookId: String) = synchronized(lock) {
        File(root, bookId).deleteRecursively()
        recency.keys.removeAll { it.bookId == bookId }
    }

    fun totalBytes(): Long = synchronized(lock) { root.walkBottomUp().filter { it.isFile }.sumOf { it.length() } }

    private fun evictLocked() {
        var total = totalBytesLocked()
        while (total > maxBytes) {
            val lru = recency.keys.firstOrNull() ?: break
            val file = pcmFile(lru)
            file.delete()
            File(file.parentFile, file.nameWithoutExtension + ".meta").delete()
            recency.remove(lru)
            pruneEmptyDirs(root, file.parentFile)
            total = totalBytesLocked()
        }
    }

    private fun totalBytesLocked(): Long =
        root.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    private fun pcmFile(key: PregenKey): File {
        val slug = key.toString()
        val bookPart = slug.substringBefore('/')
        val rest = slug.substringAfter('/')
        val folderPart = rest.substringBeforeLast('/')
        val namePart = rest.substringAfterLast('/')
        return File(File(File(root, bookPart), folderPart), "$namePart.pcm")
    }

    private fun meta(audio: PregenAudio): String = buildString {
        append(audio.sampleRateHz)
        audio.segments?.forEach { append('\n').append(it.startSeconds).append(';').append(it.endSeconds) }
    }

    private fun parseMeta(meta: String): Pair<Int, List<com.moronigranja.localttsreader.tts.SegmentAnchor>>? {
        val lines = meta.lines().filter { it.isNotBlank() }
        val sampleRate = lines.firstOrNull()?.toIntOrNull() ?: return null
        val segments = lines.drop(1).mapNotNull { line ->
            val (start, end) = line.split(';').takeIf { it.size == 2 } ?: return@mapNotNull null
            val s = start.toDoubleOrNull() ?: return@mapNotNull null
            val e = end.toDoubleOrNull() ?: return@mapNotNull null
            com.moronigranja.localttsreader.tts.SegmentAnchor(s, e)
        }
        return sampleRate to segments
    }

    private fun pruneEmptyDirs(root: File, start: File) {
        var dir = start
        while (dir != root && dir.isDirectory && dir.listFiles().orEmpty().isEmpty()) {
            dir.delete()
            dir = dir.parentFile
        }
    }

    companion object {
        /** 256 MiB of PCM ≈ a few hours of listening; a tuning knob. */
        const val DEFAULT_MAX_BYTES = 256L * 1024 * 1024
    }
}
