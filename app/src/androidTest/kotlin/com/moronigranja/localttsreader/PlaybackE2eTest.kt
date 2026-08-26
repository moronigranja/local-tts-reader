package com.moronigranja.localttsreader

import android.content.Intent
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.moronigranja.localttsreader.featureplayer.playback.PlaybackService
import com.moronigranja.localttsreader.featureplayer.playback.PlaybackStateHolder
import com.moronigranja.localttsreader.model.Book
import com.moronigranja.localttsreader.model.Chapter
import com.moronigranja.localttsreader.model.LibraryEntry
import com.moronigranja.localttsreader.model.TextPassage
import com.moronigranja.localttsreader.persistence.LibraryDatabase
import com.moronigranja.localttsreader.persistence.MIGRATION_1_2
import com.moronigranja.localttsreader.persistence.RoomLibraryStore
import com.moronigranja.localttsreader.player.PlayerPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end T4-2: the real service + engine + AudioTrack over a two-passage
 * book — machine transitions to COMPLETED, segments arrive, and the resume
 * row lands on the last passage, all in the real app process.
 *
 * Requires staged packs + espeak bundle in the app files dir (build.md), and
 * media volume 0 (`adb shell media volume --stream 3 --set 0`) so the spike
 * runs silently.
 */
@RunWith(AndroidJUnit4::class)
class PlaybackE2eTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var database: LibraryDatabase
    private lateinit var store: RoomLibraryStore
    private val scope = CoroutineScope(Dispatchers.IO)

    private val book = Book(
        id = "t4-e2e-book",
        title = "E2E Test Book",
        chapters = listOf(
            Chapter(0, "One", listOf(TextPassage("Hello world, this is the first passage. It has two sentences."))),
            Chapter(1, "Two", listOf(TextPassage("And this is the very last passage of the test book. Goodbye."))),
        ),
    )

    @Before
    fun setUp() = runBlocking {
        database = Room.databaseBuilder(context, LibraryDatabase::class.java, "local-tts-reader.db")
            .addMigrations(MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()
        store = RoomLibraryStore(database, scope)
        store.add(LibraryEntry(book, importedAtEpochMillis = 1L))
    }

    @After
    fun tearDown() {
        context.stopService(Intent(context, PlaybackService::class.java))
        database.close()
        context.deleteDatabase("local-tts-reader.db")
    }

    @Test
    fun playsThroughTheBookAndCompletes() {
        PlaybackStateHolder.reset()
        context.startForegroundService(
            Intent(context, PlaybackService::class.java)
                .setAction(PlaybackService.ACTION_PLAY)
                .putExtra(PlaybackService.EXTRA_BOOK_ID, book.id),
        )

        // Wait for the full run: LOADING → PLAYING → … → COMPLETED.
        var sawPlaying = false
        var sawSegments = false
        val deadline = System.currentTimeMillis() + 90_000
        while (System.currentTimeMillis() < deadline) {
            val state = PlaybackStateHolder.state.value
            Thread.sleep(250)
            if (state.phase == PlayerPhase.LOADING || state.phase == PlayerPhase.PLAYING) sawPlaying = true
            if (state.segments.isNotEmpty()) sawSegments = true
            if (state.phase == PlayerPhase.COMPLETED) {
                assertEquals("book id propagated", book.id, state.bookId)
                // The book's last passage is chapter 1 / passage 0 (chapter 2 has one passage).
                assertEquals("last chapter reached", 1, state.chapterIndex)
                assertEquals("last passage reached", 0, state.passageIndex)
                assertTrue("read-along anchors surfaced somewhere", sawSegments)
                assertTrue("playback actually ran (saw LOADING/PLAYING)", sawPlaying)
                // The resume row lands on the ending (decisions #33).
                val progress = runBlocking { database.progressDao().get(book.id) }
                assertEquals(1, progress?.chapterIndex)
                assertEquals(0, progress?.passageIndex)
                return
            }
        }
        throw AssertionError("playback did not complete; last state: ${PlaybackStateHolder.state.value}")
    }
}
