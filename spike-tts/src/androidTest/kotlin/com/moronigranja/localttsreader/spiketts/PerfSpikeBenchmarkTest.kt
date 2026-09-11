package com.moronigranja.localttsreader.spiketts

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cross-app performance spike (decisions #148) — one instrumented entry point
 * for legs A–F; each leg writes its own `perfspike_<leg>.json` to the external
 * files dir and asserts on its own completion.
 *
 * Device staging (models + corpora) is in `docs/build.md` §"Cross-app
 * performance spike"; legs A/D/E must run **unplugged** (battery current while
 * plugged is a charge current, not a load signal) with the screen off.
 *
 * Usage (device staged per build.md):
 *   adb shell am instrument -w -e class \
 *     com.moronigranja.localttsreader.spiketts.PerfSpikeBenchmarkTest \
 *     -e leg a \
 *     com.moronigranja.localttsreader.spiketts.test/androidx.test.runner.AndroidJUnitRunner
 *
 * Args: `leg` a|b|c|d|e|f1|f2|g (default a), `runs` 1-10 (default 3),
 * `corpus` (default corpus.tsv), `passages` 1-64 (default 16),
 * `threads` 1-8 (default 6), `memOff` 1 (lmkd retry: memory-pattern + CPU arena
 * allocator off).
 *
 * Leg `g` is the harness-sensitivity audit (RTF only, no wake lock — a plugged
 * device is fine): it re-measures the fp32 baseline against the axes legs A–E
 * held fixed (ORT's default thread count, XNNPACK off, no warm-up, a resident
 * oracle session).
 */
@RunWith(AndroidJUnit4::class)
class PerfSpikeBenchmarkTest {
    @Test
    fun measureLegOnDevice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val args = InstrumentationRegistry.getArguments()
        val leg = args.getString("leg") ?: "a"
        val runs = args.getString("runs")?.toIntOrNull()?.coerceIn(1, 10) ?: PerfSpikeRunner.RUNS
        val corpus = args.getString("corpus") ?: "corpus.tsv"
        val passages = args.getString("passages")?.toIntOrNull()?.coerceIn(1, 64) ?: 16
        val threads = args.getString("threads")?.toIntOrNull()?.coerceIn(1, 8) ?: PerfSpikeRunner.THREADS
        val memOff = args.getString("memOff") == "1"
        val log: (String) -> Unit = { Log.d("KokoroSpike", it) }
        log("leg $leg: runs=$runs corpus=$corpus passages=$passages threads=$threads memOff=$memOff")

        val runner = PerfSpikeRunner(context, memOff)
        val ok =
            when (leg) {
                "a" -> runner.runInt8Tier(runs, log)
                "b" -> runner.runWindowLength(corpus, runs, log)
                "c" -> runner.runIncrementalOutput(log)
                "d" -> runner.runScheduling(threads, runs, corpus, log)
                "e" -> runner.runDutyCycle(corpus, passages, runs, log)
                "f1" -> runner.runInt4Probe(log)
                "f2" -> runner.runXnnpackPartition(threads, log)
                "g" -> runner.runHarnessSensitivity(runs, corpus, threads, log)
                else -> throw IllegalArgumentException("unknown leg '$leg' (a|b|c|d|e|f1|f2|g)")
            }
        assertTrue("leg $leg failed", ok)
    }
}
