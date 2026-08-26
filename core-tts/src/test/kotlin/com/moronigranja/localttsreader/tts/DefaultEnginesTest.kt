package com.moronigranja.localttsreader.tts

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
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
    fun `kokoro is the v1 primary with pt among its language groups`() {
        val spec = DefaultEngines.kokoro
        assertEquals(EngineTier.PRIMARY, spec.tier)
        assertTrue("pt" in spec.languages, "v1.0 ships pt-BR voices (hard-facts)")
        assertEquals(9, spec.languages.size)
    }

    @Test
    fun `catalog ships no pack descriptors until T2 pins real artifacts`() {
        assertTrue(DefaultEngines.descriptors.all { it.packs.isEmpty() }, "no fake URLs/hashes may ship")
        assertNotEquals(DefaultEngines.kokoro.id, DefaultEngines.cosyVoice3.id)
    }
}
