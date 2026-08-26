package com.moronigranja.localttsreader.featureplayer.playback

import android.content.Context
import com.moronigranja.localttsreader.tts.DefaultEngines
import com.moronigranja.localttsreader.tts.PackCache
import com.moronigranja.localttsreader.tts.kokoro.EspeakPhonemizer
import com.moronigranja.localttsreader.tts.kokoro.KokoroEngine
import com.moronigranja.localttsreader.tts.kokoro.KokoroPacks
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-scoped Kokoro engine, opened lazily and exactly once (decisions #25/#32):
 * model + voices from the pack cache (runtime downloads, decision #7 — staged
 * or downloaded before first play), espeak-ng from the staged Android bundle
 * (lib + data under `files/espeak/`, decision #32 — the adb-staging script is
 * in build.md until V1 wires the pack download).
 */
@Singleton
class KokoroRuntime @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    @Volatile private var engine: KokoroEngine? = null
    @Volatile private var failure: String? = null

    /** The ready engine, or null with [failure] set when packs/bundle are missing. */
    fun engine(): KokoroEngine? {
        engine?.let { return it }
        failure?.let { return null }
        synchronized(this) {
            engine?.let { return it }
            failure?.let { return null }
            val cache = PackCache(context.filesDir)
            val model = cache.targetFile(KokoroPacks.model)
            val voices = cache.targetFile(KokoroPacks.voices)
            val espeakLib = File(context.filesDir, "espeak/libespeak-ng.so")
            val espeakData = File(context.filesDir, "espeak/espeak-ng-data")
            return try {
                check(model.isFile) {
                    "Kokoro model pack not ready — download/stage it first (files/packs/kokoro-82m/kokoro-model)"
                }
                check(voices.isFile) { "Kokoro voices pack not ready" }
                check(espeakLib.isFile && espeakData.isDirectory) {
                    "espeak-ng bundle not staged (files/espeak/) — see build.md"
                }
                KokoroEngine.open(
                    spec = DefaultEngines.kokoro,
                    packs = KokoroPacks.all,
                    modelFile = model,
                    voicesFile = voices,
                    phonemizer = EspeakPhonemizer(
                        libraryPath = espeakLib.absolutePath,
                        dataPath = espeakData.absolutePath,
                    ),
                ).also { engine = it }
            } catch (e: Throwable) {
                failure = e.message ?: "engine open failed"
                null
            }
        }
    }

    val failureReason: String? get() = failure
}
