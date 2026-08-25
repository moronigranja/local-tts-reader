package com.moronigranja.localttsreader.spiketts

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.util.Log
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.io.File
import kotlin.random.Random

/**
 * T3 spike runner: loads the CosyVoice3-0.5B ONNX pipeline (jiangzhuo int4
 * export, sokuji-corrected semantics), synthesizes a fixed English sentence
 * in a bundled voice, and reports RTF, peak RAM and thermal behavior for the
 * engine-order decision.
 */
class MainActivity : Activity() {

    companion object {
        private const val TAG = "T3Spike"
        private val TEXT = ("The quick brown fox jumps over the lazy dog, and the orange cat "
            + "sits on the windowsill watching the morning rain fall on the "
            + "empty street below.")
        private const val THREADS = 6
        private const val RUNS = 3
    }

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this)
        // A multi-minute benchmark must not let the screen sleep: a demoted
        // foreground app is reclaimed by lmkd under memory pressure.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        status = TextView(this)
        status.textSize = 13f
        status.text = "T3 spike starting…"
        scroll.addView(status, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        setContentView(scroll)
        Thread { runBenchmark() }.start()
    }

    private fun log(line: String) {
        Log.d(TAG, line)
        runOnUiThread { status.text = status.text.toString() + "\n" + line }
    }

    private fun runBenchmark() {
        val outDir = getExternalFilesDir(null) ?: filesDir
        val extModels = File(outDir, "models")
        // Android 11+ FUSE hides shell-pushed files under Android/data/<pkg>,
        // so the benchmark also accepts models staged in internal storage via
        // `adb shell run-as <pkg> cp -r /data/local/tmp/models files/models`.
        val models = if (File(extModels, "onnx/flow_estimator.onnx").exists()) extModels
        else File(filesDir, "models")
        try {
            check(File(models, "onnx/flow_estimator.onnx").exists()) {
                "models not found at ${models.absolutePath} — push them first"
            }
            log("model dir: ${models.absolutePath}")
            log("device: ${Build.MANUFACTURER} ${Build.MODEL}, sdk ${Build.VERSION.SDK_INT}, threads=$THREADS")

            val tok = Bpe.load(models)
            log("tokenizer loaded (vocab entries: ${tok.vocab.size})")

            val sessions = Sessions(models, THREADS)
            log("session manager ready (graphs load lazily per stage)")

            val s16 = Wav.read(File(models, "voices/sarah16.wav"))
            val s24 = Wav.read(File(models, "voices/sarah24.wav"))
            val transcript = File(models, "voices/sarah.txt").readText().trim()
            log("prompt wavs: 16k=${s16.size} samples, 24k=${s24.size} samples")

            val pipeline = Pipeline(sessions)
            val tP = System.currentTimeMillis()
            val prompt = pipeline.processPrompt(tok, s16, s24, transcript)
            val promptMs = System.currentTimeMillis() - tP
            var spkNorm = 0.0
            for (v in prompt.spkEmbedding) spkNorm += v * v
            log("prompt processed in ${promptMs} ms: tokens=${prompt.speechTokens.size}, " +
                "melFrames=${prompt.melFrames}, spkNorm=${Math.sqrt(spkNorm).toFloat()}")

            // Deterministic prompt mel is host-comparable (matches sokuji's
            // golden-tested melodics); dump it for exact cross-checking.
            val melBytes = java.nio.ByteBuffer.allocate(prompt.mel.size * 4)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            for (v in prompt.mel) melBytes.putFloat(v)
            File(outDir, "prompt_mel.bin").writeBytes(melBytes.array())
            var pmSum = 0.0
            for (v in prompt.mel) pmSum += v
            log("prompt mel dump written (mean=${"%.3f".format(pmSum / prompt.mel.size)})")

            // Cold graphs (speech_tokenizer 924 MB + campplus) ran once; free them
            // before synthesis so peak native memory stays low enough for lmkd.
            sessions.release(Sessions.COLD_GRAPHS)
            log("released cold graphs")

            val results = JSONObject()
            val runs = org.json.JSONArray()
            val thermal = ThermalProbe(this)
            thermal.start()
            for (run in 1..RUNS) {
                val rng = Random(1234L + run)
                val tLlm0 = System.currentTimeMillis()
                val (flowTokens, _) = pipeline.llmGenerate(tok, TEXT, prompt, rng)
                val llmMs = System.currentTimeMillis() - tLlm0
                sessions.release(Sessions.LLM_GROUP)
                val tFlow0 = System.currentTimeMillis()
                val mel = pipeline.flowGenerate(flowTokens, prompt, rng)
                val flowMs = System.currentTimeMillis() - tFlow0
                sessions.release(Sessions.FLOW_GROUP)
                val tHift0 = System.currentTimeMillis()
                val (audio, hiftStats) = pipeline.hiftGenerate(mel, mel.size / 80)
                val hiftMs = System.currentTimeMillis() - tHift0
                sessions.release(Sessions.HIFT_GROUP)
                var fSum = 0.0
                for (v in mel) fSum += v
                val fMean = fSum / mel.size
                var fVar = 0.0
                for (v in mel) fVar += (v - fMean) * (v - fMean)
                val fMax = mel.max()
                log("  flow mel: mean=${"%.3f".format(fMean)} std=${"%.3f".format(Math.sqrt(fVar / mel.size))} " +
                    "max=${"%.3f".format(fMax)} | f0: mean=${"%.1f".format(hiftStats.f0Mean)} " +
                    "std=${"%.1f".format(hiftStats.f0Std)} | src: rms=${"%.4f".format(hiftStats.srcRms)} len=${hiftStats.srcLen}")
                val synthMs = llmMs + flowMs + hiftMs
                val dur = audio.size.toDouble() / Pipeline.SAMPLE_RATE
                var peak = 0.0f
                var rms = 0.0
                for (v in audio) {
                    peak = maxOf(peak, kotlin.math.abs(v))
                    rms += v * v
                }
                rms = Math.sqrt(rms / audio.size)
                val finite = audio.all { it.isFinite() }
                val runJson = JSONObject()
                    .put("run", run)
                    .put("llm_ms", llmMs)
                    .put("flow_ms", flowMs)
                    .put("hift_ms", hiftMs)
                    .put("flow_mel_mean", fMean)
                    .put("flow_mel_max", fMax)
                    .put("f0_mean", hiftStats.f0Mean)
                    .put("src_rms", hiftStats.srcRms)
                    .put("synth_ms", synthMs)
                    .put("audio_seconds", dur)
                    .put("rtf", synthMs / 1000.0 / dur)
                    .put("samples", audio.size)
                    .put("peak_abs", peak)
                    .put("rms", rms)
                    .put("finite", finite)
                runs.put(runJson)
                log("run $run: llm=${llmMs} ms flow=${flowMs} ms hift=${hiftMs} ms, " +
                    "audio=${"%.2f".format(dur)} s, RTF=${"%.3f".format(synthMs / 1000.0 / dur)}, " +
                    "peak=${peak}, rms=${"%.4f".format(rms)}, finite=$finite")
                Wav.write(File(outDir, "out_run$run.wav"), audio, Pipeline.SAMPLE_RATE)
            }
            thermal.stop()
            val mem = Debug.MemoryInfo()
            Debug.getMemoryInfo(mem)
            results.put("prompt_ms", promptMs)
            results.put("runs", runs)
            results.put("vm_hwm_kb", readVmHwm())
            results.put("total_pss_kb", mem.totalPss)
            results.put("thermal_status_max", thermal.maxStatus)
            results.put("thermal_headroom_max", thermal.maxHeadroom)
            results.put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            results.put("threads", THREADS)
            File(outDir, "results.json").writeText(results.toString(2))
            log("results.json written to $outDir")
            log("peak status: ${thermal.maxStatus}, headroom: ${thermal.maxHeadroom}")
            log("VmHWM: ${readVmHwm()} kB, totalPss: ${mem.totalPss} kB")
            log("DONE")
            sessions.close()
        } catch (e: Throwable) {
            Log.e(TAG, "benchmark failed", e)
            log("FAILED: $e\n${e.stackTraceToString().lineSequence().take(6).joinToString("\n")}")
        }
    }

    private fun readVmHwm(): Long {
        val line = File("/proc/self/status").readLines().firstOrNull { it.startsWith("VmHWM:") } ?: return -1
        return line.split(Regex("\\s+"))[1].toLongOrNull() ?: -1
    }

    private class ThermalProbe(private val activity: Activity) {
        var maxStatus = -1
        var maxHeadroom = 0.0f
        @Volatile private var running = false
        private var thread: Thread? = null

        fun start() {
            if (Build.VERSION.SDK_INT < 29) return
            running = true
            thread = Thread {
                try {
                    // android.os.ThermalManager is missing from the android-36
                    // compile jar; the class exists at runtime on API 29+.
                    val cls = Class.forName("android.os.ThermalManager")
                    val tm = activity.getSystemService("thermalservice")!!
                    val statusM = cls.getMethod("getCurrentThermalStatus")
                    val headroomM = cls.getMethod("getThermalHeadroom", Int::class.javaPrimitiveType)
                    while (running) {
                        try {
                            val s = statusM.invoke(tm) as Int
                            if (s > maxStatus) maxStatus = s
                            val h = headroomM.invoke(tm, 0) as Float
                            if (h > maxHeadroom) maxHeadroom = h
                        } catch (e: Exception) { /* sample skipped */ }
                        Thread.sleep(500)
                    }
                } catch (e: ClassNotFoundException) {
                    // API < 29 or class absent: thermal stays at defaults
                }
            }.also { it.isDaemon = true; it.start() }
        }

        fun stop() {
            running = false
            thread?.join(2000)
        }
    }
}
