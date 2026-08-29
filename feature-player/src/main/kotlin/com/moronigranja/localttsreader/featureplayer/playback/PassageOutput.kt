package com.moronigranja.localttsreader.featureplayer.playback

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper

/**
 * One passage's audio output — the seam the service plays through and tests
 * fake. [play] is non-blocking; the CALLER detects completion either via an
 * exact end marker armed with [setCompletionMarker] (verified reliable on the
 * S22, decisions #81) or by polling [positionSamples] against the frame count
 * as a fallback (the historical "static markers unreliable on some devices"
 * concern — kept because the marker path is device-dependent); [stop] cancels
 * playback, after which [positionSamples] reads 0.
 *
 * [speed] is applied via [AudioTrack.setPlaybackRate] — the audio system's
 * rate converter (no manual resampling, no interpolation artifacts). The
 * buffer holds book-time frames, so [positionSamples] counts book-time
 * frames regardless of [speed] (a 2× track reaches the same frame count in
 * half the physical time — decisions #52).
 */
interface PassageOutput {
    fun play(pcm: ByteArray, sampleRate: Int, speed: Double)
    fun stop()
    val positionSamples: Int
    fun setVolume(multiplier: Float)
    /** One-shot end-of-buffer marker (decisions #81): [onReached] fires when
     * playback passes [frames]. Where MODE_STATIC markers are unreliable the
     * platform never fires it and the caller's [positionSamples] polling is
     * the fallback. Default no-op so test fakes compile unchanged. */
    fun setCompletionMarker(frames: Int, onReached: () -> Unit) = Unit
}

/** Real output: one MODE_STATIC [AudioTrack] retained across passages
 * (S4) — the track is re-fed in place when the new passage matches its
 * format AND static capacity, and rebuilt fresh on any mismatch. */
class AudioTrackPassageOutput : PassageOutput {

    private var track: AudioTrack? = null
    private var markerCallback: (() -> Unit)? = null
    private var markerListenerAttached = false

    private val markerListener = object : AudioTrack.OnPlaybackPositionUpdateListener {
        override fun onMarkerReached(track: AudioTrack) {
            // One-shot per passage: captured and cleared after firing.
            markerCallback?.let { cb ->
                markerCallback = null
                cb()
            }
        }

        override fun onPeriodicNotification(track: AudioTrack) = Unit
    }

    override fun play(pcm: ByteArray, sampleRate: Int, speed: Double) {
        val retained = track
        if (retained == null || !shouldReuse(retained, sampleRate, pcm.size)) {
            stop()
            track = buildTrack(pcm.size, sampleRate)
            markerListenerAttached = false // fresh track: re-attach the listener
        } else {
            // MODE_STATIC refeed: stop() resets the static head to the buffer
            // start (AOSP stop() pushes position 0 for static tracks — the
            // documented head reset). flush() is a native no-op for static
            // tracks on device but keeps the Robolectric shadow head honest.
            // write() then copies the new passage over the buffer from
            // offset 0, so the head counts the NEW passage from 0.
            retained.stop()
            retained.flush()
        }
        val active = track!!
        // MODE_STATIC: write the whole buffer, then play. Completion is read
        // from [positionSamples] reaching the frame count (polled by the
        // service); static tracks do not loop and hold the head at the end.
        active.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
        // Playback rate = sample rate × speed: the buffer's frames stay
        // book-time; the hardware/SoC converter consumes them at `speed`.
        runCatching { active.setPlaybackRate((sampleRate * speed.coerceAtLeast(0.1)).toInt().coerceIn(4_000, 192_000)) }
        active.play()
    }

    /** MODE_STATIC track sized to exactly one passage's PCM. */
    private fun buildTrack(pcmBytes: Int, sampleRate: Int): AudioTrack {
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(format)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(maxOf(pcmBytes, 1))
            .build()
    }

    override fun stop() {
        track?.let { current ->
            current.pause()
            current.flush()
            current.release()
        }
        track = null
        markerListenerAttached = false
        markerCallback = null
    }

    /** Monotonic played samples; 0 when idle. Safe for UI polling. */
    override val positionSamples: Int
        get() = track?.let { if (it.playState == AudioTrack.PLAYSTATE_PLAYING) it.playbackHeadPosition else 0 } ?: 0

    override fun setVolume(multiplier: Float) {
        track?.setVolume(multiplier.coerceIn(0f, 1f))
    }

    override fun setCompletionMarker(frames: Int, onReached: () -> Unit) {
        markerCallback = onReached
        val t = track ?: return
        if (!markerListenerAttached) {
            runCatching { t.setPlaybackPositionUpdateListener(markerListener, Handler(Looper.getMainLooper())) }
            markerListenerAttached = true
        }
        runCatching { t.setNotificationMarkerPosition(frames.coerceAtLeast(1)) }
    }
}

/**
 * Reuse decision for a retained [AudioTrack] (S4): keep the track only when
 * the new passage is identical in every property that defines a MODE_STATIC
 * track — sample rate, channel config, and the static buffer capacity. A
 * static track's frame count is fixed at construction and the audio server
 * plays to it, so a smaller re-write would replay stale tail audio and a
 * larger one cannot be written at all; only an exact-capacity re-feed is
 * faithful. [speed] is deliberately NOT part of the identity: the rate is
 * applied per play via [AudioTrack.setPlaybackRate], so one track serves
 * every speed of a matching passage (decisions #52).
 */
internal fun shouldReuse(current: AudioTrack, sampleRate: Int, pcmBytes: Int): Boolean =
    current.sampleRate == sampleRate &&
        current.channelConfiguration == AudioFormat.CHANNEL_OUT_MONO &&
        pcmBytes == current.bufferSizeInFrames * 2 // mono 16-bit: 2 bytes/frame