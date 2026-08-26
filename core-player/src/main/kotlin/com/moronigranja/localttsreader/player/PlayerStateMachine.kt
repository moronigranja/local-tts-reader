package com.moronigranja.localttsreader.player

import com.moronigranja.localttsreader.model.Book
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Chapter geometry of one parsed [Book]: passage counts per chapter in spine
 * order, and passage-level navigation. The machine is bound to one book —
 * playing another book is a new machine over the same [PlayerStore].
 */
class BookLayout(book: Book) {

    /** The book the layout was built from. */
    val bookId: String = book.id

    private val passageCounts: IntArray =
        IntArray((book.chapters.maxOfOrNull { it.index } ?: -1) + 1)

    init {
        for (chapter in book.chapters) {
            require(chapter.index in passageCounts.indices) { "chapter index ${chapter.index} out of range" }
            passageCounts[chapter.index] = chapter.passages.size
        }
    }

    val chapterCount: Int get() = passageCounts.size

    fun passageCount(chapterIndex: Int): Int = passageCounts[chapterIndex]

    fun isValid(chapterIndex: Int, passageIndex: Int): Boolean =
        chapterIndex in passageCounts.indices && passageIndex in 0 until passageCounts[chapterIndex]

    /** The passage after [chapterIndex]/[passageIndex], or null past the book's end. */
    fun next(chapterIndex: Int, passageIndex: Int): Pair<Int, Int>? {
        if (!isValid(chapterIndex, passageIndex)) return null
        if (passageIndex + 1 < passageCounts[chapterIndex]) return chapterIndex to (passageIndex + 1)
        if (chapterIndex + 1 < passageCounts.size) return (chapterIndex + 1) to 0
        return null
    }

    /** The passage before [chapterIndex]/[passageIndex], or null at the book's start. */
    fun previous(chapterIndex: Int, passageIndex: Int): Pair<Int, Int>? {
        if (!isValid(chapterIndex, passageIndex)) return null
        if (passageIndex > 0) return chapterIndex to (passageIndex - 1)
        if (chapterIndex > 0) return (chapterIndex - 1) to (passageCounts[chapterIndex - 1] - 1)
        return null
    }
}

/** Passage text lookup from a [Book] by spine indexes (the player's audio unit). */
fun Book.passageText(chapterIndex: Int, passageIndex: Int): String? =
    chapters.firstOrNull { it.index == chapterIndex }?.passages?.getOrNull(passageIndex)?.text

/**
 * The v1 player state machine (decisions #29/#33) — the single writer of
 * [PlayerStore]: transport transitions, sleep timer, per-book speed, and the
 * undo-skip position ring. Pure JVM, fully unit-testable; the Android edge
 * (feature-player) drives it and reacts to [PlayerEvent]s.
 *
 * Ring semantics: a user-directed move **away** from the current position
 * (skip, seek, play-from-elsewhere) pushes the position being left so one
 * undo restores it; natural forward playback never pushes. Every write goes
 * through [PlayerStore.commitProgress] — progress row + ring push are one
 * transaction, so the undo target can never drift from the resume row
 * (roadmap T4 carry-over note 3). Completion pushes the ending, so undo
 * replays it.
 *
 * Positions are in book-time (offset at 1.0×): [setSpeed] never moves the
 * play point and the offset survives speed changes.
 *
 * The machine is not thread-safe per instance; the edge serializes calls
 * (a single player coroutine).
 */
class PlayerStateMachine(
    private val store: PlayerStore,
    private val layout: BookLayout,
    private val ringCapacity: Int = 10,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state

    /** Book the machine is bound to (layout was built from it). */
    val bookId: String = layout.bookId

    // ------------------------------------------------------------------
    // Transport

    /** Starts playback at the stored resume point; returns it, or null when
     * the book was never played. Ring untouched (nothing is being left). */
    suspend fun resume(): PlayerPosition? {
        val stored = storeOp { store.readProgress(bookId) } ?: return null
        val position = stored.toPosition()
        require(layout.isValid(position.chapterIndex, position.passageIndex)) {
            "stored progress points outside the layout: $position"
        }
        _state.update {
            it.copy(
                phase = PlayerPhase.LOADING,
                position = position,
                speed = stored.speed,
                sleepTimer = SleepTimer.Off,
                failure = null,
            )
        }
        return position
    }

    /** Starts playback at [position] — an explicit (possibly accidental) play
     * target: the stored resume point is pushed for one undo. */
    suspend fun playFrom(position: PlayerPosition) {
        require(position.bookId == bookId) { "position for ${position.bookId}, machine bound to $bookId" }
        require(layout.isValid(position.chapterIndex, position.passageIndex)) { "position outside layout: $position" }
        val stored = storeOp { store.readProgress(bookId) }
        val ringPush = stored?.takeUnless { it.samePointer(position) }?.toPosition()
        _state.update { it.copy(phase = PlayerPhase.LOADING, position = position, failure = null) }
        storeOp { store.commitProgress(position.toProgress(_state.value.speed), ringPush) }
    }

    /**
     * Reports where the playhead physically is (book-time seconds) and
     * commits it — the edge calls this throttled during playback and before
     * [pause]/[stop], so the resume row tracks the live position (single
     * writer, decisions #33). Ring untouched (natural progress).
     */
    suspend fun notePlaybackOffset(offsetSeconds: Double) {
        val position = _state.value.position ?: return
        val updated = position.copy(offsetSeconds = offsetSeconds)
        _state.update { it.copy(position = updated) }
        storeOp { store.commitProgress(updated.toProgress(_state.value.speed), null) }
    }

    /** Pauses at the playhead and writes — phase PAUSED. [offsetSeconds]
     * defaults to the machine's committed offset when the edge reports none. */
    suspend fun pause(offsetSeconds: Double? = null) {
        val position = _state.value.position ?: return
        val final = offsetSeconds?.let { position.copy(offsetSeconds = it) } ?: position
        storeOp { store.commitProgress(final.toProgress(_state.value.speed), null) }
        _state.update { it.copy(position = final, phase = PlayerPhase.PAUSED) }
    }

    /** The edge started producing audio for the current position. */
    fun onAudioStarted() {
        _state.update { it.copy(phase = PlayerPhase.PLAYING) }
    }

    /** Detaches the player: final write at the playhead, phase IDLE. */
    suspend fun stop(offsetSeconds: Double? = null) {
        _state.value.position?.let { position ->
            val final = offsetSeconds?.let { position.copy(offsetSeconds = it) } ?: position
            _state.update { it.copy(position = final) }
            storeOp { store.commitProgress(final.toProgress(_state.value.speed), null) }
        }
        _state.update { it.copy(phase = PlayerPhase.IDLE) }
    }

    // ------------------------------------------------------------------
    // Playback progress

    /**
     * The edge finished the current passage's audio. Advances to the next
     * passage (no ring push — natural progress), pauses at a chapter end when
     * the sleep timer is [SleepTimer.EndOfChapter], or completes the book
     * (pushing the ending for one undo).
     */
    suspend fun onPassageFinished(): List<PlayerEvent> {
        val current = _state.value.position ?: return emptyList()
        if (_state.value.phase != PlayerPhase.PLAYING) return emptyList()

        if (_state.value.sleepTimer == SleepTimer.EndOfChapter) {
            val next = layout.next(current.chapterIndex, current.passageIndex)
            if (next != null && next.first != current.chapterIndex) {
                // Chapter boundary with end-of-chapter set: stop before the
                // new chapter, resume row at the chapter's last passage.
                val pauseAt = current.copy(offsetSeconds = 0.0)
                storeOp { store.commitProgress(pauseAt.toProgress(_state.value.speed), null) }
                _state.update { it.copy(position = pauseAt, phase = PlayerPhase.PAUSED, sleepTimer = SleepTimer.Off) }
                return listOf(PlayerEvent.PauseRequested)
            }
        }

        val next = layout.next(current.chapterIndex, current.passageIndex)
        if (next == null) {
            // Book end: keep the resume row at the ending's start for undo.
            val ending = current.copy(offsetSeconds = 0.0)
            storeOp { store.commitProgress(ending.toProgress(_state.value.speed), ending) }
            _state.update { it.copy(position = ending, phase = PlayerPhase.COMPLETED) }
            return listOf(PlayerEvent.PlaybackCompleted)
        }

        val (chapterIndex, passageIndex) = next
        val advanced = PlayerPosition(bookId, chapterIndex, passageIndex)
        storeOp { store.commitProgress(advanced.toProgress(_state.value.speed), null) }
        // The next passage is not being heard yet: the edge must load it.
        _state.update { it.copy(position = advanced, phase = PlayerPhase.LOADING) }
        return listOf(PlayerEvent.PassageAdvanced(chapterIndex, passageIndex))
    }

    // ------------------------------------------------------------------
    // Navigation (user-directed: every move pushes what is being left)

    /** Next passage (or next chapter's first); pushes the current position. */
    suspend fun skipForward(): List<PlayerEvent> = moveBy { chapter, passage -> layout.next(chapter, passage) }

    /** Previous passage; pushes the current position. */
    suspend fun skipBackward(): List<PlayerEvent> = moveBy { chapter, passage -> layout.previous(chapter, passage) }

    /** Explicit jump (reader "tap a passage", S3 "Listen from here");
     * pushes what is being left. */
    suspend fun seekTo(position: PlayerPosition) {
        require(position.bookId == bookId) { "position for ${position.bookId}" }
        require(layout.isValid(position.chapterIndex, position.passageIndex)) { "position outside layout: $position" }
        val current = _state.value.position ?: return
        if (current.samePointer(position)) return
        commitMove(position, ringPush = current)
    }

    /** Pops the ring and returns to the pushed position (one-shot undo). */
    suspend fun undoSkip(): PlayerPosition? {
        val popped = storeOp { store.popRing(bookId) } ?: return null
        require(layout.isValid(popped.chapterIndex, popped.passageIndex)) { "ring entry outside layout: $popped" }
        commitMove(popped, ringPush = null)
        return popped
    }

    private suspend fun moveBy(
        target: (chapter: Int, passage: Int) -> Pair<Int, Int>?,
    ): List<PlayerEvent> {
        val current = _state.value.position ?: return emptyList()
        val moved = target(current.chapterIndex, current.passageIndex) ?: return emptyList()
        val position = PlayerPosition(bookId, moved.first, moved.second)
        commitMove(position, ringPush = current)
        return listOf(PlayerEvent.PassageAdvanced(position.chapterIndex, position.passageIndex))
    }

    private suspend fun commitMove(position: PlayerPosition, ringPush: PlayerPosition?) {
        storeOp { store.commitProgress(position.toProgress(_state.value.speed), ringPush) }
        _state.update { it.copy(position = position, phase = PlayerPhase.LOADING, failure = null) }
    }

    // ------------------------------------------------------------------
    // Speed / sleep timer / bookmarks

    /** Per-book speed (presets UI, decisions #29). Clamped; preserves the
     * play point — the offset is book-time, so it never moves with speed. */
    suspend fun setSpeed(speed: Double) {
        val clamped = speed.coerceIn(MIN_SPEED, MAX_SPEED)
        _state.value.position?.let { position ->
            storeOp { store.commitProgress(position.toProgress(clamped), null) }
        }
        _state.update { it.copy(speed = clamped) }
    }

    fun setSleepTimer(timer: SleepTimer) {
        _state.update { it.copy(sleepTimer = timer) }
    }

    /**
     * Wall-clock tick for countdown sleep: fires once at [SleepTimer.Duration]
     * expiry (pauses + writes), clears the timer. End-of-chapter is handled by
     * [onPassageFinished]. Returns the events the edge must honor.
     */
    suspend fun advance(nowEpochMillis: Long = clock()): List<PlayerEvent> {
        val timer = _state.value.sleepTimer
        if (timer !is SleepTimer.Duration) return emptyList()
        if (nowEpochMillis < timer.endsAtEpochMillis) return emptyList()
        // Expired: clear the timer, pause (and write) when actively playing.
        val wasPlaying = _state.value.phase == PlayerPhase.PLAYING
        _state.update { it.copy(sleepTimer = SleepTimer.Off) }
        if (wasPlaying) {
            _state.value.position?.let { position ->
                storeOp { store.commitProgress(position.toProgress(_state.value.speed), null) }
            }
            _state.update { it.copy(phase = PlayerPhase.PAUSED) }
        }
        return if (wasPlaying) listOf(PlayerEvent.PauseRequested) else emptyList()
    }

    /** Bookmarks the machine's current position (long-press add). */
    suspend fun addBookmark(label: String? = null): Bookmark? {
        val position = _state.value.position ?: return null
        val bookmark = Bookmark(
            bookId = bookId,
            chapterIndex = position.chapterIndex,
            passageIndex = position.passageIndex,
            offsetSeconds = position.offsetSeconds,
            label = label,
            createdAtEpochMillis = clock(),
        )
        return storeOp { store.addBookmark(bookmark) }
    }

    suspend fun removeBookmark(bookmarkId: Long) = storeOp { store.removeBookmark(bookmarkId) }

    suspend fun bookmarks(): List<Bookmark> = storeOp { store.bookmarks(bookId) } ?: emptyList()

    // ------------------------------------------------------------------

    private inline fun <T> storeOp(block: () -> T): T? =
        try {
            block()
        } catch (e: Throwable) {
            _state.update { it.copy(failure = e.message ?: "store failure") }
            null
        }

    private fun PlayerProgress.toPosition(): PlayerPosition =
        PlayerPosition(bookId, chapterIndex, passageIndex, offsetSeconds)

    private fun PlayerPosition.toProgress(speed: Double): PlayerProgress =
        PlayerProgress(bookId, chapterIndex, passageIndex, offsetSeconds, speed, clock())

    private fun PlayerProgress.samePointer(other: PlayerPosition): Boolean =
        chapterIndex == other.chapterIndex && passageIndex == other.passageIndex

    private fun PlayerPosition.samePointer(other: PlayerPosition): Boolean =
        chapterIndex == other.chapterIndex && passageIndex == other.passageIndex

    companion object {
        const val MIN_SPEED = 0.5
        const val MAX_SPEED = 3.0
    }
}
