package com.moronigranja.localttsreader.tts

/**
 * The known engines (hard-facts engine table + decisions #21), metadata only.
 *
 * Pack descriptors — URL + pinned SHA-256 of the exact artifact — are
 * intentionally absent: hashes are produced by downloading each artifact once
 * during the engine slice (T2) and are never fabricated. Until then the
 * registry tracks zero concrete packs; the settings UI renders the engine list,
 * and download management goes live with the first descriptor (Kokoro, T2).
 */
object DefaultEngines {
    val cosyVoice3: EngineSpec = EngineSpec(
        id = "cosyvoice3-0.5b",
        displayName = "Fun-CosyVoice3-0.5B",
        tier = EngineTier.FALLBACK, // gated: CPU RTF 14.7–17.5 on the S22 Ultra (decisions #21)
        languages = setOf("zh", "en", "fr", "es", "ja", "ko", "it", "ru", "de"), // hard-facts: 9 languages
    )

    val kokoro: EngineSpec = EngineSpec(
        id = "kokoro-82m",
        displayName = "Kokoro-82M",
        tier = EngineTier.PRIMARY, // v1 primary (decisions #21)
        // hard-facts: v1.0 "9 groups incl. pt-BR"; exact BCP-47 codes pinned at T2.
        languages = setOf("en", "fr", "de", "es", "it", "pt", "ja", "zh", "ko"),
    )

    val descriptors: List<EngineDescriptor> = listOf(
        EngineDescriptor(kokoro, emptyList()),
        EngineDescriptor(cosyVoice3, emptyList()),
    )
}
