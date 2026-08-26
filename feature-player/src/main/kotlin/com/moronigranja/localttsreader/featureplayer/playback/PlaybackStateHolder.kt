package com.moronigranja.localttsreader.featureplayer.playback

import com.moronigranja.localttsreader.player.PlayerPhase
import com.moronigranja.localttsreader.player.SleepTimer
import com.moronigranja.localttsreader.tts.SegmentAnchor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * The single observable player surface the service writes and the reader UI
 * reads (T4-2). The [PlayerStateMachine] remains the only *persistence*
 * writer; this holder is in-memory UI/edge state only.
 */
object PlaybackStateHolder {

    private val _state = MutableStateFlow(PlaybackUiState())
    val state: StateFlow<PlaybackUiState> = _state

    fun update(transform: (PlaybackUiState) -> PlaybackUiState) = _state.update(transform)

    fun reset() {
        _state.value = PlaybackUiState()
    }
}

data class PlaybackUiState(
    val bookId: String? = null,
    val bookTitle: String = "",
    val chapterIndex: Int = 0,
    val passageIndex: Int = 0,
    val passageText: String = "",
    /** Sentence spans of the current passage's audio (decisions #31). */
    val segments: List<SegmentAnchor> = emptyList(),
    /** Live playhead, book-time seconds within the passage. */
    val offsetSeconds: Double = 0.0,
    val speed: Double = 1.0,
    val phase: PlayerPhase = PlayerPhase.IDLE,
    val sleepTimer: SleepTimer = SleepTimer.Off,
    val canUndo: Boolean = false,
    val failure: String? = null,
) {
    /** The read-along highlight index: the sentence the playhead is inside. */
    val activeSentenceIndex: Int
        get() {
            var last = -1
            for ((index, segment) in segments.withIndex()) {
                if (segment.startSeconds <= offsetSeconds) last = index else break
            }
            return if (last >= 0) last else 0
        }
}
