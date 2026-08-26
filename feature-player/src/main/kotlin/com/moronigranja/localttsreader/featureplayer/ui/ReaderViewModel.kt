package com.moronigranja.localttsreader.featureplayer.ui

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import com.moronigranja.localttsreader.featureplayer.playback.PlaybackService
import com.moronigranja.localttsreader.featureplayer.playback.PlaybackStateHolder
import com.moronigranja.localttsreader.featureplayer.playback.PlaybackUiState
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
) : ViewModel() {

    val state: StateFlow<PlaybackUiState> = PlaybackStateHolder.state

    fun play(bookId: String) = command(PlaybackService.ACTION_PLAY, bookId)
    fun playPosition(bookId: String, chapter: Int, passage: Int) =
        command(PlaybackService.ACTION_PLAY_POSITION, bookId, chapter, passage)
    fun resume() = command(PlaybackService.ACTION_RESUME)
    fun pause() = command(PlaybackService.ACTION_PAUSE)
    fun skipForward() = command(PlaybackService.ACTION_SKIP_FORWARD)
    fun skipBackward() = command(PlaybackService.ACTION_SKIP_BACKWARD)
    fun undo() = command(PlaybackService.ACTION_UNDO)
    fun stop() = command(PlaybackService.ACTION_STOP)
    fun cycleSpeed() = command(PlaybackService.ACTION_SPEED)
    fun cycleSleep() = command(PlaybackService.ACTION_SLEEP)
    fun bookmark() = command(PlaybackService.ACTION_BOOKMARK)

    private fun command(action: String, bookId: String? = null, chapter: Int = 0, passage: Int = 0) {
        val intent = Intent(context, PlaybackService::class.java).setAction(action)
        if (bookId != null) intent.putExtra(PlaybackService.EXTRA_BOOK_ID, bookId)
        if (action == PlaybackService.ACTION_PLAY_POSITION) {
            intent.putExtra(PlaybackService.EXTRA_CHAPTER, chapter)
            intent.putExtra(PlaybackService.EXTRA_PASSAGE, passage)
        }
        runCatching { context.startForegroundService(intent) }
    }
}
