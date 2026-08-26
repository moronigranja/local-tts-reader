package com.moronigranja.localttsreader.featureplayer.playback

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

/**
 * One passage's audio output — the seam the service plays through and tests
 * fake. [play] is non-blocking; the CALLER detects completion by polling
 * [positionSamples] against the buffer's frame count (static tracks park the
 * head at the end without a reliable marker on some devices), and [stop]
 * cancels playback, after which [positionSamples] reads 0.
 */
interface PassageOutput {
    fun play(pcm: ByteArray, sampleRate: Int)
    fun stop()
    val positionSamples: Int
    fun setVolume(multiplier: Float)
}

/** Real output: a fresh [AudioTrack] per passage, MODE_STATIC. */
class AudioTrackPassageOutput : PassageOutput {

    private var track: AudioTrack? = null

    override fun play(pcm: ByteArray, sampleRate: Int) {
        stop()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val built = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(format)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(maxOf(pcm.size, 1))
            .build()
        // MODE_STATIC: write the whole buffer, then play. Completion is read
        // from [positionSamples] reaching the frame count (polled by the
        // service); static tracks do not loop and hold the head at the end.
        built.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
        track = built
        built.play()
    }

    override fun stop() {
        track?.let { current ->
            current.pause()
            current.flush()
            current.release()
        }
        track = null
    }

    /** Monotonic played samples; 0 when idle. Safe for UI polling. */
    override val positionSamples: Int
        get() = track?.let { if (it.playState == AudioTrack.PLAYSTATE_PLAYING) it.playbackHeadPosition else 0 } ?: 0

    override fun setVolume(multiplier: Float) {
        track?.setVolume(multiplier.coerceIn(0f, 1f))
    }
}
