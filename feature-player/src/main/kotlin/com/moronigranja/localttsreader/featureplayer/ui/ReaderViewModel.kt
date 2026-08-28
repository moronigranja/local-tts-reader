package com.moronigranja.localttsreader.featureplayer.ui

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import com.moronigranja.localttsreader.featureplayer.playback.PlaybackService
import com.moronigranja.localttsreader.player.PlaybackStateHolder
import com.moronigranja.localttsreader.player.PlaybackUiState
import com.moronigranja.localttsreader.player.PlayerCommands
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

/**
 * Reader-side commands to the [PlaybackService]; state is read from the
 * service-published [PlaybackStateHolder] (the service is the single writer).
 */
@HiltViewModel
class ReaderViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
) : ViewModel(), PlayerCommands {

    val state: StateFlow<PlaybackUiState> = PlaybackStateHolder.state

    /** The book this reader shows — [resume] carries it so a machine-less
     * service (STOP's post-stop fill self-stopped it) can rebuild and
     * resume from the persisted playhead instead of dead-ending the
     * play button. */
    private var openedBookId: String? = null

    /** Opens a book in the reader WITHOUT starting playback (decisions #52). */
    fun open(bookId: String) {
        openedBookId = bookId
        command(PlaybackService.ACTION_OPEN, bookId)
    }

    override fun play(bookId: String) {
        openedBookId = bookId
        command(PlaybackService.ACTION_PLAY, bookId)
    }

    override fun playAt(bookId: String, chapterIndex: Int, passageIndex: Int) {
        openedBookId = bookId
        command(PlaybackService.ACTION_PLAY_POSITION, bookId, chapterIndex, passageIndex)
    }

    fun playPosition(bookId: String, chapter: Int, passage: Int) {
        openedBookId = bookId
        command(PlaybackService.ACTION_PLAY_POSITION, bookId, chapter, passage)
    }

    fun openChapter(bookId: String, direction: Int) =
        command(PlaybackService.ACTION_OPEN_CHAPTER, bookId, direction = direction)
    fun skipForward() = command(PlaybackService.ACTION_SKIP_FORWARD)
    fun skipBackward() = command(PlaybackService.ACTION_SKIP_BACKWARD)
    fun undo() = command(PlaybackService.ACTION_UNDO)
    override fun stop() = command(PlaybackService.ACTION_STOP)
    fun cycleSleep() = command(PlaybackService.ACTION_SLEEP)
    fun bookmark() = command(PlaybackService.ACTION_BOOKMARK)

    override fun resume() = command(PlaybackService.ACTION_RESUME, openedBookId)
    override fun pause() = command(PlaybackService.ACTION_PAUSE)
    override fun seekForward() = command(PlaybackService.ACTION_SEEK_FORWARD)
    override fun seekBackward() = command(PlaybackService.ACTION_SEEK_BACKWARD)
    private fun command(action: String, bookId: String? = null, chapter: Int = 0, passage: Int = 0, direction: Int = 0) {
        val intent = Intent(context, PlaybackService::class.java).setAction(action)
        if (bookId != null) intent.putExtra(PlaybackService.EXTRA_BOOK_ID, bookId)
        if (action == PlaybackService.ACTION_PLAY_POSITION) {
            intent.putExtra(PlaybackService.EXTRA_CHAPTER, chapter)
            intent.putExtra(PlaybackService.EXTRA_PASSAGE, passage)
        }
        if (action == PlaybackService.ACTION_OPEN_CHAPTER) {
            intent.putExtra(PlaybackService.EXTRA_DIRECTION, direction)
        }
        runCatching { context.startForegroundService(intent) }
    }
}
