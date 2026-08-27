package com.moronigranja.localttsreader.featureplayer.playback

import com.moronigranja.localttsreader.tts.PackCache
import com.moronigranja.localttsreader.tts.TtsPack
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Stages the downloaded espeak-ng bundle under `files/espeak/` — the layout
 * [KokoroRuntime] and the settings status read (decision #32: lib + data
 * staged next to each other, loaded by explicit path). The pack artifact is
 * a zip of `libespeak-ng.so` + `espeak-ng-data/` at its root; the download
 * itself is a normal verified pack ([PackCache] layout, decision #7), so the
 * staging is an accelerator-style extract, idempotent and cheap.
 */
object EspeakStager {

    fun bundleDir(filesDir: File): File = File(filesDir, "espeak")

    fun libFile(filesDir: File): File = File(bundleDir(filesDir), "libespeak-ng.so")

    fun dataDir(filesDir: File): File = File(bundleDir(filesDir), "espeak-ng-data")

    /** Ready = staged lib + non-empty data dir (the readiness KokoroRuntime requires). */
    fun isStaged(filesDir: File): Boolean {
        val lib = libFile(filesDir)
        val data = dataDir(filesDir)
        return lib.isFile && data.isDirectory && data.listFiles()?.isNotEmpty() == true
    }

    /** Extracts the verified zip pack into [bundleDir] (idempotent; replaces an old bundle). */
    fun stage(filesDir: File, cache: PackCache, pack: TtsPack): Boolean {
        if (isStaged(filesDir)) return true
        val source = cache.targetFile(pack)
        if (!source.isFile || !cache.isVerified(pack)) return false

        val tmp = File(filesDir, "espeak-tmp")
        tmp.deleteRecursively()
        tmp.mkdirs()
        ZipInputStream(source.inputStream().buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val target = File(tmp, entry.name)
                check(target.canonicalPath.startsWith(tmp.canonicalPath)) { "zip entry escapes the bundle dir: ${entry.name}" }
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    target.outputStream().use { zip.copyTo(it) }
                }
                entry = zip.nextEntry
            }
        }

        val target = bundleDir(filesDir)
        val backup = File(filesDir, "espeak-bak")
        backup.deleteRecursively()
        if (target.isDirectory && !target.renameTo(backup)) return false
        if (!tmp.renameTo(target)) {
            backup.renameTo(target) // restore the previous bundle on failure
            return false
        }
        backup.deleteRecursively()
        return true
    }
}