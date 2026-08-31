package com.moronigranja.localttsreader.player

import com.moronigranja.localttsreader.tts.SegmentAnchor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * The single observable player surface the service writes and the reader UI
 * reads (T4-2, moved to core-player as part of the A6 composition cutover —
 * feature modules observe it through this core type, never through the
 * implementation). The [PlayerStateMachine] remains the only *persistence*
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
    /** The book's authors, spine order — the player card subtitle. */
    val authors: List<String> = emptyList(),
    val chapterIndex: Int = 0,
    val passageIndex: Int = 0,
    val passageText: String = "",
    /** Book-time seconds the current passage's audio spans (speed-independent). */
    val passageDurationSeconds: Double = 0.0,
    /** Chapter titles in spine order — the chapter selector. */
    val chapters: List<String> = emptyList(),
    /** The current chapter's passage texts, in order — the stitched reader
     * surface (decisions #51 follow-up). */
    val chapterPassages: List<String> = emptyList(),
    /** Sentence spans of the current passage's audio (decisions #31). */
    val segments: List<SegmentAnchor> = emptyList(),
    /** Live playhead, book-time seconds within the passage. */
    val offsetSeconds: Double = 0.0,
    /** [0..1] book position — completed passages over the book's total. */
    val readFraction: Float = 0f,
    /** Book-time seconds elapsed at the playhead (1.0×) — the card's elapsed. */
    val elapsedSeconds: Double = 0.0,
    /** Estimated listening time left in the book at the current speed. */
    val timeLeftSeconds: Double = 0.0,
    val speed: Double = 1.0,
    /** Book-time seconds of pre-generated audio queued strictly ahead of the
     * playhead (`PregenQueue.aheadSeconds`); 0 when no fill runs. */
    val generatedAheadSeconds: Double = 0.0,
    val phase: PlayerPhase = PlayerPhase.IDLE,
    val sleepTimer: SleepTimer = SleepTimer.Off,
    val canUndo: Boolean = false,
    /** The book's bookmarks, newest first — the reader's bookmark menu. */
    val bookmarks: List<Bookmark> = emptyList(),
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

    /** [0..1] fullness of the pregen cushion: listenable book-time seconds
     * queued strictly ahead of the playhead, measured against the fixed
     * [PREGEN_HORIZON_SECONDS] horizon — NOT book time. The original
     * book-time-remaining denominator painted sub-pixel segments on long
     * books (45 s cushion vs ~27 h remaining ≈ 0.05% of the bar; B4
     * finding, decisions #95), so the segment now answers "how full is the
     * buffer right now", not "how much of the book is it" (decisions #98).
     * Clamped. */
    val generatedAheadFraction: Float
        get() = (generatedAheadSeconds / PREGEN_HORIZON_SECONDS).toFloat().coerceIn(0f, 1f)

    companion object {
        /** Denominator of [generatedAheadFraction] (decisions #98): ~2.7× the
         * service's 45 s look-ahead target (PREFILL_LOOKAHEAD_SECONDS), so a
         * steady-state cushion sits ~37% into the segment and manual pregen
         * can overfill visibly. */
        const val PREGEN_HORIZON_SECONDS = 120.0
    }
}