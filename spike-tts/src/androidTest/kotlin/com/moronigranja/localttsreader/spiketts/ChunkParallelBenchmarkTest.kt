package com.moronigranja.localttsreader.spiketts

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Chunk-parallel window-inference measurement (decisions #116 follow-up).
 * Runs `ChunkParallelRunner` as an instrumented test — locked/screen-off, the
 * realistic pregen condition. Logs through `KokoroSpike`, writes
 * `kokoro_chunk_parallel.json` to the external files dir.
 *
 * Usage (device staged per build.md):
 *   adb shell am instrument -w \
 *     com.moronigranja.localttsreader.spiketts.test/androidx.test.runner.AndroidJUnitRunner
 *     -e class com.moronigranja.localttsreader.spiketts.ChunkParallelBenchmarkTest
 */
@RunWith(AndroidJUnit4::class)
class ChunkParallelBenchmarkTest {
    @Test
    fun measureChunkParallelOnDevice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val ok = ChunkParallelRunner(context).run { Log.d("KokoroSpike", it) }
        assertTrue("chunk-parallel benchmark failed", ok)
    }
}
