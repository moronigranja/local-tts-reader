package com.moronigranja.localttsreader.player.pregen

import com.moronigranja.localttsreader.tts.SegmentAnchor

/**
 * One pre-synthesized passage: PCM plus what the read-along reader needs.
 * Engine-agnostic by construction — the player converts `SynthesisOutcome`
 * into this (decisions #35).
 */
data class PregenAudio(
    val pcm: ByteArray,
    val sampleRateHz: Int,
    val segments: List<SegmentAnchor>? = null,
)

/**
 * Identity of one synthesized passage: book, spine indexes, voice, speed.
 * The [toString] form is the future disk-cache path — book id is a content
 * hash (decisions #11), the voice/speed slug mirrors the post-v1 PCM cache
 * keying (engine + voice + speed + passage, #31/#34), and it round-trips
 * through [parse].
 */
data class PregenKey(
    val bookId: String,
    val chapterIndex: Int,
    val passageIndex: Int,
    val voice: String,
    val speed: Double,
) {
    override fun toString(): String =
        "$bookId/$voice/${formatSpeed(speed)}/c$chapterIndex" + "p$passageIndex"

    companion object {
        private fun formatSpeed(speed: Double): String =
            if (speed % 1.0 == 0.0) speed.toInt().toString() else speed.toString().replace('.', '_')

        /** Parses the [toString] form; null on malformed input. */
        fun parse(path: String): PregenKey? {
            val voiceIndex = path.indexOf('/')
            if (voiceIndex <= 0) return null
            val bookId = path.substring(0, voiceIndex)
            val rest = path.substring(voiceIndex + 1)
            val speedDivider = rest.indexOf('/')
            if (speedDivider <= 0) return null
            val voice = rest.substring(0, speedDivider)
            val tail = rest.substring(speedDivider + 1)
            val speed = tail.substringBefore("/c").replace('_', '.').toDoubleOrNull() ?: return null
            val spine = tail.substringAfter("/c", missingDelimiterValue = "")
            val (c, p) = spine.split('p', limit = 2).takeIf { it.size == 2 } ?: return null
            return PregenKey(bookId, c.toIntOrNull() ?: return null, p.toIntOrNull() ?: return null, voice, speed)
        }
    }
}
