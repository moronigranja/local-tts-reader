package com.moronigranja.localttsreader

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import kotlin.math.abs
import kotlin.math.min
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device confirmation of the Opus storage spike (tools/opus_drift_spike.py,
 * 2026-08-27): the real MediaCodec Opus encoder/decoder on the S22,
 * 24 kHz mono 24 kbps — the pregen cache's format. Measures duration drift
 * and sentence-anchor boundary residuals (the `.meta` sidecar maps
 * seconds → samples): host result was 0.0 ms drift / max +20 ms residual
 * (one 20 ms frame), anchors seconds-based so 48 kHz decode needs only ×2.
 *
 * The signal is deterministic burst noise with exact onset times (anchors at
 * 1.0/5.5/12.3/18.9/25.0 s); boundaries located by envelope cross-correlation,
 * so the numbers are pure codec behavior — no engine anchor jitter mixed in.
 * Log tag: OpusDrift.
 */
@RunWith(AndroidJUnit4::class)
class OpusDriftInstrumentedTest {

    private val tag = "OpusDrift"

    private fun encoderInfo(mime: String): MediaCodecList.CodecInfo? =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull {
            it.isEncoder && it.supportedTypes.any { t -> t.equals(mime, ignoreCase = true) }
        }

    private fun encodeOpus(pcm: ShortArray, sr: Int, bitrate: Int): ByteArray {
        val codec = try {
            MediaCodec.createEncoderByType("audio/opus")
        } catch (e: Exception) {
            throw AssertionError("MediaCodec opus ENCODER unavailable on this device: $e")
        }
        val fmt = MediaFormat.createAudioFormat("audio/opus", sr, 1)
        fmt.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val out = ByteArrayOutputStream()
        val chunk = sr / 25 // 40 ms per input buffer
        var pos = 0
        var eosQueued = false
        val info = MediaCodec.BufferInfo()
        var guards = 0
        while (true) {
            val inIdx = codec.dequeueInputBuffer(10_000)
            if (inIdx >= 0) {
                val buf = codec.getInputBuffer(inIdx)!!
                val n = min(chunk, pcm.size - pos)
                if (n > 0) {
                    buf.clear()
                    buf.order(ByteOrder.nativeOrder()).asShortBuffer().put(pcm, pos, n)
                    codec.queueInputBuffer(inIdx, 0, n * 2, System.nanoTime(), 0)
                    pos += n
                } else if (!eosQueued) {
                    codec.queueInputBuffer(inIdx, 0, 0, System.nanoTime(), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    eosQueued = true
                }
            }
            val outIdx = codec.dequeueOutputBuffer(info, 10_000)
            if (outIdx >= 0) {
                val ob = codec.getOutputBuffer(outIdx)!!
                if (info.size > 0) {
                    val bytes = ByteArray(info.size)
                    ob.get(bytes)
                    out.write(bytes)
                }
                codec.releaseOutputBuffer(outIdx, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
            if (++guards > 1_000_000) throw AssertionError("encoder did not finish")
        }
        codec.stop()
        codec.release()
        return out.toByteArray()
    }

    private fun decodeOpus(encoded: ByteArray): Pair<Int, ShortArray> {
        val codec = MediaCodec.createDecoderByType("audio/opus")
        val fmt = MediaFormat.createAudioFormat("audio/opus", 24_000, 1)
        codec.configure(fmt, null, null, 0)
        codec.start()
        val pcmBytes = ByteArrayOutputStream()
        val info = MediaCodec.BufferInfo()
        var pos = 0
        var eosQueued = false
        var dsr = 24_000
        var guards = 0
        while (true) {
            val inIdx = codec.dequeueInputBuffer(10_000)
            if (inIdx >= 0) {
                val buf = codec.getInputBuffer(inIdx)!!
                val n = min(4096, encoded.size - pos)
                if (n > 0) {
                    buf.clear()
                    buf.put(encoded, pos, n)
                    codec.queueInputBuffer(inIdx, 0, n, System.nanoTime(), 0)
                    pos += n
                } else if (!eosQueued) {
                    codec.queueInputBuffer(inIdx, 0, 0, System.nanoTime(), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    eosQueued = true
                }
            }
            val outIdx = codec.dequeueOutputBuffer(info, 10_000)
            if (outIdx >= 0) {
                val ob = codec.getOutputBuffer(outIdx)!!
                val format = codec.outputFormat
                if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) dsr = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                if (info.size > 0) {
                    val bytes = ByteArray(info.size)
                    ob.get(bytes)
                    pcmBytes.write(bytes)
                }
                codec.releaseOutputBuffer(outIdx, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
            if (++guards > 1_000_000) throw AssertionError("decoder did not finish")
        }
        codec.stop()
        codec.release()
        val bytes = pcmBytes.toByteArray()
        val shorts = ShortArray(bytes.size / 2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
        return dsr to shorts
    }

    /** Linear-interpolate a 24 kHz window to the decoded rate. */
    private fun resampleTo(src: ShortArray, from: Int, to: Int): ShortArray {
        if (to == from) return src
        val n = src.size * to / from
        val out = ShortArray(n)
        for (i in 0 until n) {
            val x = i * from.toDouble() / to
            val lo = x.toInt().coerceAtMost(src.size - 2)
            val frac = x - lo
            out[i] = (src[lo] * (1 - frac) + src[lo + 1] * frac).toInt().toShort()
        }
        return out
    }

    /** Where the anchor's 20 ms window actually lands in the decoded stream (ms). */
    private fun boundaryResidualMs(orig: ShortArray, sr: Int, decoded: ShortArray, dsr: Int, anchorSec: Double): Double {
        val winLen = sr / 50 // 20 ms
        val start = (anchorSec * sr).toInt()
        val ref = resampleTo(orig.copyOfRange(start, start + winLen), sr, dsr)
        val expected = (anchorSec * dsr).toLong()
        val half = 40L * dsr / 1000 // +-40 ms search
        val lo = (expected - half).coerceAtLeast(0)
        val hi = (expected + half).coerceAtMost(decoded.size.toLong() - ref.size)
        // Envelope correlation: codec phase/spectral smearing does not move energy onsets.
        var bestDot = -1L
        var bestAt = lo
        for (cand in lo..hi) {
            var dot = 0L
            for (k in ref.indices) {
                dot += abs(ref[k].toInt()).toLong() * abs(decoded[(cand + k).toInt()].toInt())
            }
            if (dot > bestDot) {
                bestDot = dot
                bestAt = cand
            }
        }
        return (bestAt - expected) * 1000.0 / dsr
    }

    @Test
    fun opusRoundTripDriftOnDevice() {
        val sr = 24_000
        val anchors = doubleArrayOf(1.0, 5.5, 12.3, 18.9, 25.0)
        val burst = 0.8 // seconds of noise per anchor
        val rng = Random(42)
        val pcm = ShortArray(((anchors.last() + burst + 0.5) * sr).toInt())
        for (a in anchors) {
            val start = (a * sr).toInt()
            val len = (burst * sr).toInt()
            for (j in 0 until len) {
                val ramp = min(1.0, min(j.toDouble(), (len - 1 - j).toDouble()) / 40.0)
                pcm[start + j] = (rng.nextGaussian() * 7000 * ramp).toInt().toShort()
            }
        }

        val enc = encoderInfo("audio/opus")
        assertTrue("opus encoder advertised on this device: $enc", enc != null)
        Log.i(tag, "encoder component: ${enc!!.name}")
        val rates = enc.capabilitiesForType("audio/opus")
            .audioCapabilities.supportedSampleRates?.toList()
        Log.i(tag, "encoder supported sample rates: $rates")

        val t0 = System.nanoTime()
        val encoded = encodeOpus(pcm, sr, 24_000)
        val encMs = (System.nanoTime() - t0) / 1_000_000
        val (dsr, decoded) = decodeOpus(encoded)
        Log.i(tag, "size: ${pcm.size * 2} B PCM -> ${encoded.size} B opus (${(pcm.size * 2.0 / encoded.size).toInt()}x) | encode ${encMs} ms")

        val durDriftMs = (decoded.size.toDouble() / dsr - pcm.size.toDouble() / sr) * 1000
        val residuals = anchors.map { boundaryResidualMs(pcm, sr, decoded, dsr, it) }
        val maxResidual = residuals.max()
        Log.i(tag, "decode: dsr=$dsr channels=${1} | dur drift ${"%.1f".format(durDriftMs)} ms | " +
            "boundary residuals ms: ${residuals.map { "%.1f".format(it) }}")

        // Host reference: drift 0.0 ms; residual +20 ms (one frame). Allow one
        // frame of decoder slack beyond that — the decision bound.
        assertTrue("duration drift ${"%.1f".format(durDriftMs)} ms <= 25 ms", abs(durDriftMs) <= 25)
        assertTrue("max boundary residual ${"%.1f".format(maxResidual)} ms <= 40 ms", abs(maxResidual) <= 40)
        Log.i(tag, "OPUS_DRIFT_OK drift=${"%.1f".format(durDriftMs)}ms maxResidual=${"%.1f".format(maxResidual)}ms")
    }
}