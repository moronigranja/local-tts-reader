package com.moronigranja.localttsreader.tts.kokoro

/**
 * Static voice metadata for the kokoro v1.0 voices pack (C1.3): the 54 names
 * are presentation data — the roster lives only inside the downloaded
 * `voices-v1.0.bin` (`KokoroVoiceBank`), so "choose a voice before download"
 * needs a static table. The roster below was read from the pinned pack
 * artifact itself (the pack is the contract; metadata is presentation):
 * `unzip -l voices-v1.0.bin` yields exactly these 54 `.npy` members, and this
 * table is cross-checked against a loaded pack in tests.
 *
 * Prefix families → language/gender, mirroring [KokoroEngine.VOICE_LANGUAGES]
 * (the espeak-ng phonemization map): af/am en-US, bf/bm en-GB, ef/em es,
 * ff/fm fr, if/im it, pf/pm pt-BR, jf/jm ja, zf/zm zh, hf/hm hi. v1.0 has no
 * `fm_` voices — only `ff_siwis` — so the table carries the families the pack
 * actually ships.
 */
data class KokoroVoiceMeta(
    val name: String,
    val language: String,
    val gender: String,
)

object KokoroVoiceMetadata {

    /** Voice prefix family → (display language, gender). Declared FIRST:
     * object init runs top-down and [all]'s init calls [meta], which reads
     * this map — a later declaration would be null at that point. */
    private val FAMILY_TO_LANGUAGE: Map<String, Pair<String, String>> = mapOf(
        "af" to ("English (US)" to "Female"),
        "am" to ("English (US)" to "Male"),
        "bf" to ("English (UK)" to "Female"),
        "bm" to ("English (UK)" to "Male"),
        "ef" to ("Spanish" to "Female"),
        "em" to ("Spanish" to "Male"),
        "ff" to ("French" to "Female"),
        "hf" to ("Hindi" to "Female"),
        "hm" to ("Hindi" to "Male"),
        "if" to ("Italian" to "Female"),
        "im" to ("Italian" to "Male"),
        "jf" to ("Japanese" to "Female"),
        "jm" to ("Japanese" to "Male"),
        "pf" to ("Portuguese (Brazil)" to "Female"),
        "pm" to ("Portuguese (Brazil)" to "Male"),
        "zf" to ("Chinese" to "Female"),
        "zm" to ("Chinese" to "Male"),
    )

    /** All 54 v1.0 voices, language-grouped (declaration order). */
    val all: List<KokoroVoiceMeta> = listOf(
        // en-US female
        meta("af_alloy"), meta("af_aoede"), meta("af_bella"), meta("af_heart"),
        meta("af_jessica"), meta("af_kore"), meta("af_nicole"), meta("af_nova"),
        meta("af_river"), meta("af_sarah"), meta("af_sky"),
        // en-US male
        meta("am_adam"), meta("am_echo"), meta("am_eric"), meta("am_fenrir"),
        meta("am_liam"), meta("am_michael"), meta("am_onyx"), meta("am_puck"),
        meta("am_santa"),
        // en-GB female
        meta("bf_alice"), meta("bf_emma"), meta("bf_isabella"), meta("bf_lily"),
        // en-GB male
        meta("bm_daniel"), meta("bm_fable"), meta("bm_george"), meta("bm_lewis"),
        // es female
        meta("ef_dora"),
        // es male
        meta("em_alex"), meta("em_santa"),
        // fr female
        meta("ff_siwis"),
        // hi female
        meta("hf_alpha"), meta("hf_beta"),
        // hi male
        meta("hm_omega"), meta("hm_psi"),
        // it female
        meta("if_sara"),
        // it male
        meta("im_nicola"),
        // ja female
        meta("jf_alpha"), meta("jf_gongitsune"), meta("jf_nezumi"), meta("jf_tebukuro"),
        // ja male
        meta("jm_kumo"),
        // pt-BR female
        meta("pf_dora"),
        // pt-BR male
        meta("pm_alex"), meta("pm_santa"),
        // zh female
        meta("zf_xiaobei"), meta("zf_xiaoni"), meta("zf_xiaoxiao"), meta("zf_xiaoyi"),
        // zh male
        meta("zm_yunjian"), meta("zm_yunxi"), meta("zm_yunxia"), meta("zm_yunyang"),
    ).also {
        require(it.size == 54) { "kokoro v1.0 ships 54 voices, table has ${it.size}" }
        require(it.map(KokoroVoiceMeta::name).distinct().size == it.size) {
            "voice names must be unique in the metadata table"
        }
    }

    /** Names the metadata table carries but the pack roster lacks — surfaced
     * in tests (fixture pack), never required at app runtime. */
    fun missingFrom(packNames: Set<String>): Set<String> =
        all.map(KokoroVoiceMeta::name).toSet() - packNames

    private fun meta(name: String): KokoroVoiceMeta {
        val family = name.substringBefore('_')
        val (language, gender) = FAMILY_TO_LANGUAGE[family]
            ?: error("voice $name has no known prefix family")
        return KokoroVoiceMeta(name, language, gender)
    }
}