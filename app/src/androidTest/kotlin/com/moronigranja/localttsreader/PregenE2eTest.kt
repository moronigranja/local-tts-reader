package com.moronigranja.localttsreader

import android.content.Intent
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.moronigranja.localttsreader.featureplayer.playback.PlaybackService
import com.moronigranja.localttsreader.featureplayer.playback.PlaybackStateHolder
import com.moronigranja.localttsreader.featureplayer.playback.PregenWorker
import com.moronigranja.localttsreader.model.Book
import com.moronigranja.localttsreader.model.Chapter
import com.moronigranja.localttsreader.model.LibraryEntry
import com.moronigranja.localttsreader.model.TextPassage
import com.moronigranja.localttsreader.persistence.LibraryDatabase
import com.moronigranja.localttsreader.persistence.MIGRATION_1_2
import com.moronigranja.localttsreader.persistence.RoomLibraryStore
import com.moronigranja.localttsreader.player.PlayerPhase
import com.moronigranja.localttsreader.player.pregen.PcmPassageCache
import com.moronigranja.localttsreader.player.pregen.PregenKey
import com.moronigranja.localttsreader.tts.kokoro.KokoroEngine
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end offline pre-generation (decisions #42) on the real device,
 * through the PRODUCTION path: the [PregenWorker] (Hilt-injected, singleton
 * Kokoro engine) run by WorkManager into the `files/pregen` tier, then
 * playback over the warm cache in the real service.
 *
 * The audible proof that the playback loop serves from the disk tier are the
 * `loop: source=disk` logcat lines during the playback phase; the in-test
 * assertions pin worker success, the cache round-trip, and playback
 * completion. Requires staged packs + espeak bundle (build.md) — the tier is
 * wiped per test, the engine is NEVER constructed twice in-process (two
 * espeak_Initialize calls crash/starve the device — LMK kills).
 */
@RunWith(AndroidJUnit4::class)
class PregenE2eTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var database: LibraryDatabase
    private lateinit var store: RoomLibraryStore
    private lateinit var cache: PcmPassageCache
    private val scope = CoroutineScope(Dispatchers.IO)

    // 4 passages x ~15 s ≈ 1 min of audio: a real synthesis run (Kokoro RTF
    // ~0.7 on this device) without stretching the test past ~4 minutes total.
    private val book = Book(
        id = "t42-pregen-e2e-book",
        title = "Pregen E2E Book",
        chapters = listOf(
            Chapter(
                0,
                "One",
                listOf(
                    TextPassage(
                        "The hikers met at dawn by the old stone bridge. " +
                            "Mist lay over the river and the town was quiet. " +
                            "They packed the map, the thermos, and the climbing rope. " +
                            "The trail rose gently through the pines for an hour. " +
                            "Listen to the wind in the high branches and the birds. " +
                            "It takes patience to reach the ridge before noon.",
                    ),
                    TextPassage(
                        "Beyond the ridge the valley opened wide and green. " +
                            "A stream cut through the meadow and the sheep had come down. " +
                            "They stopped for bread and cheese under a lone oak. " +
                            "The afternoon light made the water shine like silver. " +
                            "Keep the pace steady and the summit will come to you.",
                    ),
                ),
            ),
            Chapter(
                1,
                "Two",
                listOf(
                    TextPassage(
                        "The descent was steeper than the map suggested. " +
                            "Loose stones slid underfoot and the rope scraped the rock. " +
                            "They moved one at a time and called out before each step. " +
                            "By evening the forest closed in and the path softened. " +
                            "Rest now, and let the fire hold the dark at bay.",
                    ),
                ),
            ),
            Chapter(
                2,
                "Three",
                listOf(
                    TextPassage(
                        "And this is the very last passage of the test book. Goodbye.",
                    ),
                ),
            ),
        ),
    )

    @Before
    fun setUp() {
        runBlocking {
            database = Room.databaseBuilder(context, LibraryDatabase::class.java, "local-tts-reader.db")
                .addMigrations(MIGRATION_1_2)
                .allowMainThreadQueries()
                .build()
            store = RoomLibraryStore(database, scope)
            store.add(LibraryEntry(book, importedAtEpochMillis = 1L))
            cache = PcmPassageCache(File(context.filesDir, "pregen")) // read-only handle to the worker's tier
            File(context.filesDir, "pregen").deleteRecursively()
        }
    }

    @After
    fun tearDown() {
        context.stopService(Intent(context, PlaybackService::class.java))
        database.close()
        // No deleteDatabase: the app's Hilt Room singleton (worker + service)
        // keeps a live connection to this file; unlinking it would silently
        // starve the worker (see PlaybackE2eTest note, decisions #42 device pass).
        File(context.filesDir, "pregen").deleteRecursively()
    }

    @Test
    fun workerPregensTheBookThenPlaybackCompletesOverTheCache() {
        // 1) The real manual worker (Hilt graph, singleton engine, foreground).
        val workManager = WorkManager.getInstance(context)
        val request = OneTimeWorkRequestBuilder<PregenWorker>()
            .setInputData(
                workDataOf(
                    PregenWorker.KEY_MODE to PregenWorker.MODE_MANUAL,
                    PregenWorker.KEY_BOOK_IDS to arrayOf(book.id),
                ),
            )
            .build()
        workManager.enqueue(request)

        // 2) Wait for the worker to fill the tier (synthesis ~1 min + engine
        // open). The tier was wiped in setUp, so its content is this run's
        // work — a complete cache IS the worker's SUCCEEDED + 100%.
        val deadline = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(5)
        var complete = false
        while (System.currentTimeMillis() < deadline) {
            complete = allPassagesCached()
            if (complete) break
            Thread.sleep(1_000)
        }
        assertTrue("worker filled the whole book within 5 min", complete)

        // 3) The tier holds every passage, PCM + anchors round-trip.
        val key = PregenKey(book.id, 0, 0, "af_heart", 1.0) // worker defaults: settings voice, speed 1.0
        val audio = cache.get(key) ?: throw AssertionError("first passage not on disk")
        assertTrue(audio.pcm.isNotEmpty())
        assertEquals(KokoroEngine.SAMPLE_RATE, audio.sampleRateHz)
        assertTrue("sentence anchors persisted", !audio.segments.isNullOrEmpty())
        for (chapter in book.chapters) {
            for (passageIndex in chapter.passages.indices) {
                assertTrue(
                    "c${chapter.index}p$passageIndex cached",
                    cache.contains(PregenKey(book.id, chapter.index, passageIndex, "af_heart", 1.0)),
                )
            }
        }
        assertTrue("cache holds real bytes", cache.totalBytes() > 0)

        // 4) Playback over the warm cache completes in the real service.
        context.startForegroundService(
            Intent(context, PlaybackService::class.java)
                .setAction(PlaybackService.ACTION_PLAY)
                .putExtra(PlaybackService.EXTRA_BOOK_ID, book.id),
        )
        var sawPlaying = false
        val playDeadline = System.currentTimeMillis() + 90_000
        while (System.currentTimeMillis() < playDeadline) {
            val state = PlaybackStateHolder.state.value
            Thread.sleep(250)
            if (state.phase == PlayerPhase.LOADING || state.phase == PlayerPhase.PLAYING) sawPlaying = true
            if (state.phase == PlayerPhase.COMPLETED) {
                assertTrue("playback actually ran", sawPlaying)
                assertTrue("cache survives playback", cache.contains(key))
                return
            }
        }
        throw AssertionError("playback did not complete; last state: ${PlaybackStateHolder.state.value}")
    }

    private fun allPassagesCached(): Boolean =
        book.chapters.all { chapter ->
            chapter.passages.indices.all { passageIndex ->
                cache.contains(PregenKey(book.id, chapter.index, passageIndex, "af_heart", 1.0))
            }
        }
}