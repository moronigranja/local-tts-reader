package com.moronigranja.localttsreader.featuresettings

import com.moronigranja.localttsreader.tts.kokoro.KokoroPacks
import com.moronigranja.localttsreader.tts.PackCache
import com.moronigranja.localttsreader.tts.kokoro.KokoroVoiceBank
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The voice names for the picker (V1): `KokoroVoiceBank` parses the whole
 * 28 MiB voices pack to enumerate names, so that work is done once, lazily,
 * off the UI thread, and cached for the process. An absent/unverified voices
 * pack yields an empty catalog (the UI shows the download action instead).
 */
@Singleton
class VoiceCatalog @Inject constructor(
    private val cache: PackCache,
) {

    @Volatile private var names: Set<String>? = null

    suspend fun names(): Set<String> {
        names?.let { return it }
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                val file: File = cache.targetFile(KokoroPacks.voices)
                if (!cache.isVerified(KokoroPacks.voices)) emptySet()
                else KokoroVoiceBank.load(file).voiceNames
            }.getOrDefault(emptySet())
        }
        names = loaded
        return loaded
    }

    fun invalidate() {
        names = null
    }
}
