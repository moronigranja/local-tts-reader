package com.moronigranja.localttsreader.tts

import com.moronigranja.localttsreader.tts.kokoro.KokoroPacks
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DefaultEnginesTest {

    @Test
    fun `catalog ids are unique and ordered primary first`() {
        val engines = DefaultEngines.descriptors
        assertEquals(engines.map { it.spec.id }.distinct().size, engines.size, "engine ids must be unique")
        assertEquals(EngineTier.PRIMARY, engines.first().spec.tier, "v1 primary must lead the catalog")
    }

    @Test
    fun `cosyvoice3 declares its 9 hard-facts languages and sits in the fallback tier`() {
        val spec = DefaultEngines.cosyVoice3
        assertEquals(9, spec.languages.size)
        assertTrue(spec.languages.containsAll(setOf("zh", "en", "fr", "es", "ja", "ko", "it", "ru", "de")))
        assertEquals(EngineTier.FALLBACK, spec.tier, "CosyVoice3 stays behind the T3 gate (decisions #21)")
    }

    @Test
    fun `kokoro advertises exactly the languages its voice pack serves`() {
        val spec = DefaultEngines.kokoro
        assertEquals(EngineTier.PRIMARY, spec.tier)
        // The pinned voices-v1.0.bin covers 9 families but no German or
        // Korean voices exist in the release pack (T2 pinning).
        assertEquals(setOf("en", "fr", "es", "it", "pt", "ja", "zh", "hi"), spec.languages)
        assertTrue("pt" in spec.languages, "v1.0 ships pt-BR voices (hard-facts)")
        assertFalse("de" in spec.languages, "no German voices in the v1.0 pack")
        assertFalse("ko" in spec.languages, "no Korean voices in the v1.0 pack")
    }

    @Test
    fun `kokoro ships the pinned T2 pack descriptors`() {
        val kokoro = DefaultEngines.descriptors.first { it.spec.id == "kokoro-82m" }
        assertEquals(KokoroPacks.all, kokoro.packs)
        assertEquals(listOf("kokoro-model", "kokoro-voices"), kokoro.packs.map { it.id })

        val model = KokoroPacks.model
        assertTrue(model.url.startsWith("https://"))
        assertTrue(model.sha256Hex.length == 64 && model.sha256Hex.all { it in "0123456789abcdefABCDEF" })
        assertEquals(325_505_369L, model.sizeBytes, "kokoro-v1.0.onnx @ model-files-v1.1")
        assertEquals(28_214_398L, KokoroPacks.voices.sizeBytes, "voices-v1.0.bin @ model-files-v1.1")
    }

    @Test
    fun `cosyvoice3 still ships no pack descriptors until its slice pins artifacts`() {
        val cosy = DefaultEngines.descriptors.first { it.spec.id == "cosyvoice3-0.5b" }
        assertTrue(cosy.packs.isEmpty(), "no fake URLs/hashes may ship")
        assertTrue(cosy.spec.tier == EngineTier.FALLBACK)
    }
}
