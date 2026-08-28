package com.moronigranja.localttsreader.featureplayer.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media.app.NotificationCompat.MediaStyle
import com.moronigranja.localttsreader.model.Book
import com.moronigranja.localttsreader.player.BookLayout
import com.moronigranja.localttsreader.player.BookProgress
import com.moronigranja.localttsreader.player.PlayerEvent
import com.moronigranja.localttsreader.player.PlayerPhase
import com.moronigranja.localttsreader.player.PlayerPosition
import com.moronigranja.localttsreader.player.PlayerStateMachine
import com.moronigranja.localttsreader.player.PlayerStore
import com.moronigranja.localttsreader.player.PlaybackStateHolder
import com.moronigranja.localttsreader.player.PlaybackUiState
import com.moronigranja.localttsreader.player.passageText
import com.moronigranja.localttsreader.player.pregen.PregenAudio
import com.moronigranja.localttsreader.player.pregen.PregenKey
import com.moronigranja.localttsreader.player.pregen.PregenQueue
import com.moronigranja.localttsreader.player.SleepTimer
import com.moronigranja.localttsreader.persistence.AppSettings
import com.moronigranja.localttsreader.persistence.RoomLibraryStore
import com.moronigranja.localttsreader.tts.SegmentAnchor
import com.moronigranja.localttsreader.tts.SynthesisOutcome
import com.moronigranja.localttsreader.tts.SynthesisRequest
import com.moronigranja.localttsreader.tts.kokoro.KokoroEngine
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * T4-2 playback: foreground service running the [PlayerStateMachine] against
 * the Kokoro engine and an [AudioTrack] (through [PassageOutput]), with
 * MediaSession, audio focus/ducking, route-switch (becoming-noisy) handling,
 * sleep-timer ticks, and a media notification. The docked reader UI observes
 * [PlaybackStateHolder] and drives this service with Intent actions.
 *
 */
@AndroidEntryPoint
class PlaybackService : Service() {

    @Inject lateinit var store: PlayerStore
    @Inject lateinit var libraryStore: RoomLibraryStore
    @Inject lateinit var runtime: KokoroRuntime
    @Inject lateinit var settings: AppSettings
    @Inject lateinit var pregenCache: PregenCache

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    /** CR-2 host-test seam: the current machine (set by tests directly). */
    internal var machine: PlayerStateMachine? = null
    /** Active book (internal for host tests that drive commands directly). */
    internal var book: Book? = null
    /** CR-2 host-test seam: the passage output (tests inject a fake). */
    internal var output: PassageOutput = AudioTrackPassageOutput()
    private var playerJob: Job? = null
    private var tickerJob: Job? = null
    private var pregenJob: Job? = null
    private var queue: PregenQueue? = null
    /** The last passage's rendered audio, keyed identically to the disk tier —
     * a seek that stays in the passage replays it with zero synthesis (decisions
     * #55 layer 1). Cleared on book switch. */
    private var lastAudio: Pair<PregenKey, PregenAudio>? = null
    /** In-flight first-listen persists; seek paths join them so a re-fetch of
     * a just-listened passage always finds the disk entry. Never cancelled —
     * a new first-listen must not drop the previous passage's write. */
    private val pendingPersists = mutableListOf<Job>()
    /** The active play loop coroutine; cancelled directly by [stopEverything]
     * so a stale loop can never advance the machine after a newer command. */
    private var loopJob: Job? = null
    /** Serializes transport commands (seek/chapter/skip/speed): rapid taps
     * queue instead of racing the machine's single-writer state. */
    private val commandLock = kotlinx.coroutines.sync.Mutex()
    private var stopSignal = CompletableDeferred<Unit>()
    /** The graceful STOP's final machine write (CR-2): onDestroy joins it so
     * exactly one authoritative stop persists — never a stale teardown write
     * overwriting the captured playhead. */
    private var finalStopJob: Job? = null
    /** Last live-playhead checkpoint (CR-2): monotonic interval gate. */
    private var lastCheckpointAt = 0L
    /** The in-flight command coroutine (CR-5): every control-plane command
     * (open/play/pause/seek/navigate/...) runs here; a NEWER command's
     * [stopEverything] cancels it so a superseded load can never advance or
     * publish shared state. The long-running synthesis/play loop runs inside
     * its command, so commands stay cancellable. */
    private var commandJob: Job? = null
    /**
     * Monotonic command generation (CR-5/CR-7): bumped by every
     * [stopEverything]. A command captures the generation it was launched
     * with and checks [active] before ANY publish/startForeground/
     * stopForeground side effect — cancellation alone cannot stop a
     * non-cancellable section from reaching its tail.
     */
    @Volatile private var commandGeneration = 0L
    private var segments: List<SegmentAnchor> = emptyList()
    /** CR-2 host-test seam: the current PCM slice's start offset (book-time). */
    internal var baselineOffset = 0.0
    private var ringHasEntries = false
    // Media-notification cover art, cached per book (files/covers/<bookId>).
    private var coverArtBookId: String? = null
    private var coverArt: Bitmap? = null
    private var resumeOnGain = false
    private var ducking = false

    private lateinit var session: MediaSessionCompat
    private lateinit var audioManager: AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        session = MediaSessionCompat(this, "local-tts-reader").apply {
            setCallback(mediaCallback)
            isActive = true
        }
        ContextCompat.registerReceiver(
            this,
            noisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY
        // Every command arrives via startForegroundService: enter the
        // foreground FIRST so an early return (no engine/book/machine) can
        // never trip ForegroundServiceDidNotStartInTimeException (#50).
        startForeground(NOTIFICATION_ID, buildNotification())
        when (intent.action) {
            ACTION_OPEN -> openBook(intent.bookId())
            ACTION_PLAY -> startPlayback(intent.bookId(), explicit = false)
            ACTION_PLAY_POSITION -> startPlayback(intent.bookId(), explicit = true, intent = intent)
            ACTION_OPEN_CHAPTER -> openChapter(intent.bookId(), intent.getIntExtra(EXTRA_DIRECTION, 0))
            ACTION_RESUME -> resumePlayer()
            ACTION_PAUSE -> pausePlayer(PauseReason.USER)
            ACTION_SKIP_FORWARD -> navigate { it.skipForward() }
            ACTION_SKIP_BACKWARD -> navigate { it.skipBackward() }
            ACTION_SEEK_FORWARD -> seekBy(SEEK_STEP_SECONDS)
            ACTION_SEEK_BACKWARD -> seekBy(-SEEK_STEP_SECONDS)
            ACTION_UNDO -> navigateUndo()
            ACTION_STOP -> stopPlayer()
            ACTION_SLEEP -> cycleSleepTimer()
            ACTION_SPEED -> cycleSpeed()
            ACTION_BOOKMARK -> addBookmarkAtPlayhead()
        }
        return START_NOT_STICKY
    }

    // ------------------------------------------------------------------
    // Commands

    /**
     * Opens a book for READING without starting playback (decisions #52):
     * stops whatever is playing, loads the book, positions the machine at the
     * resume point (or the start), publishes the text state, and drops the
     * foreground — the transport buttons or the library play button resume
     * audio later. The service stays started to answer commands.
     */
    internal fun openBook(bookId: String?) {
        val id = bookId ?: return
        stopEverything()
        launchCommand { generation ->
            settings.reload()
            val activeBook = runCatching { libraryStore.cachedBooks() }.getOrNull()
                ?.firstOrNull { it.id == id }?.toBook()
            // CR-5: a superseding command cancelled us — never touch shared state.
            if (!active(generation)) return@launchCommand
            if (activeBook == null) return@launchCommand
            book = activeBook
            machine = PlayerStateMachine(store, BookLayout(activeBook))
            lastAudio = null
            queue = buildQueue()
            val position = machine!!.openPosition() ?: machine!!.firstPosition()
            if (position != null) machine!!.present(position)
            refreshBookmarks()
            // CR-5: a stale load must never publish or drop the foreground.
            if (!active(generation)) return@launchCommand
            PlaybackStateHolder.update { it.copy(failure = null) }
            publish()
            ServiceCompat.stopForeground(this@PlaybackService, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }
    }

    /**
     * Turns the reader across a chapter boundary WITHOUT starting playback
     * (decisions #52: open ≠ auto-play — same contract as [openBook]). A
     * forward turn lands on the neighbor's FIRST passage, a backward turn
     * on its LAST — the reader's left-zone turn shows the previous
     * chapter's ending. The machine is rebuilt over the book while the
     * CURRENT chapter is captured first; the neighbor is the next/previous
     * chapter with passages ([BookLayout.nextChapter]/[previousChapter]),
     * so an empty spine slot is skipped and a boundary turn at the book's
     * edge is a no-op. Playback never starts; the reader pages to the
     * presented passage itself.
     */
    internal fun openChapter(bookId: String?, direction: Int) {
        val id = bookId ?: return
        val activeBook = book ?: return
        val current = machine?.state?.value?.position?.chapterIndex ?: return
        // Resolve the neighbor BEFORE touching the machine/audio: a book-edge
        // turn is a pure no-op and must leave the present machine intact —
        // rebuilding below before this null check would null the position and
        // silently swallow the next turn.
        val layout = BookLayout(activeBook)
        val target = if (direction > 0) layout.nextChapter(current) else layout.previousChapter(current)
        if (target == null) return
        stopEverything()
        launchCommand { generation ->
            settings.reload()
            val reloaded = runCatching { libraryStore.cachedBooks() }.getOrNull()
                ?.firstOrNull { it.id == id }?.toBook()
            // CR-5: a superseding command cancelled us — never touch shared state.
            if (!active(generation)) return@launchCommand
            if (reloaded == null) return@launchCommand
            book = reloaded
            machine = PlayerStateMachine(store, BookLayout(reloaded))
            lastAudio = null
            queue = buildQueue()
            val passage = if (direction < 0) activeBook.chapters[target].passages.lastIndex else 0
            machine!!.present(PlayerPosition(id, target, passage))
            refreshBookmarks()
            // CR-5: a stale load must never publish or drop the foreground.
            if (!active(generation)) return@launchCommand
            PlaybackStateHolder.update { it.copy(failure = null) }
            publish()
            ServiceCompat.stopForeground(this@PlaybackService, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }
    }

    /** Enriches the UI state with the book's bookmarks for the reader menu. */
    private fun refreshBookmarks() {
        val id = machine?.bookId ?: return
        scope.launch {
            val bookmarks = store.bookmarks(id)
            PlaybackStateHolder.update { it.copy(bookmarks = bookmarks) }
        }
    }

    private fun startPlayback(bookId: String?, explicit: Boolean, intent: Intent? = null) {
        val id = bookId ?: return
        if (runtime.engine() == null) {
            PlaybackStateHolder.update { it.copy(failure = runtime.failureReason ?: "engine unavailable") }
            return
        }
        stopEverything()
        requestFocus()
        launchCommand { generation ->
            settings.reload() // V1: settings written by the UI apply at the next play action
            val activeBook = runCatching { libraryStore.cachedBooks() }.getOrNull()
                ?.firstOrNull { it.id == id }?.toBook()
            // CR-5: a superseding command cancelled us — never touch shared state.
            if (!active(generation)) return@launchCommand
            if (activeBook == null) return@launchCommand
            book = activeBook
            machine = PlayerStateMachine(store, BookLayout(activeBook))
            lastAudio = null
            queue = buildQueue()
            refreshBookmarks()
            val position = if (explicit) {
                PlayerPosition(id, intent?.getIntExtra(EXTRA_CHAPTER, 0) ?: 0, intent?.getIntExtra(EXTRA_PASSAGE, 0) ?: 0)
            } else {
                null
            }
            if (position != null) {
                machine!!.playFrom(position)
            } else if (machine!!.resume() == null) {
                // First play: start at the book's first *playable* passage —
                // segmentation renumbers from 0, but a stale/empty parse must
                // not crash the (0,0) require.
                machine!!.playFrom(machine!!.firstPosition() ?: run {
                    PlaybackStateHolder.update { it.copy(failure = "nothing to play") }
                    return@launchCommand
                })
            }
            if (machine!!.state.value.phase != PlayerPhase.LOADING) {
                PlaybackStateHolder.update { it.copy(failure = "nothing to play") }
                return@launchCommand
            }
            // CR-5/CR-7: a superseded play must not enter the foreground or
            // start its loop after a newer command won.
            if (!active(generation)) return@launchCommand
            PlaybackActive.markStarted() // overnight pre-gen yields while a session is live (#42)
            startForeground(NOTIFICATION_ID, buildNotification())
            publish()
            startLoop()
        }
    }

    private fun resumePlayer() {
        val active = machine ?: return
        val phase = active.state.value.phase
        if (phase == PlayerPhase.PLAYING || phase == PlayerPhase.LOADING) return
        if (runtime.engine() == null) return
        stopEverything()
        requestFocus()
        launchCommand { generation ->
            settings.reload() // V1: voice/speed changes from settings apply on resume
            if (phase == PlayerPhase.COMPLETED) {
                active.playFrom(PlayerPosition(active.bookId, 0, 0))
            } else if (active.resume() == null) {
                // A fresh book (or one opened without playing yet): start
                // from the first passage instead of doing nothing.
                active.playFrom(active.firstPosition() ?: return@launchCommand)
            }
            if (!active(generation)) return@launchCommand
            queue = buildQueue()
            PlaybackActive.markStarted()
            startForeground(NOTIFICATION_ID, buildNotification())
            publish()
            startLoop()
        }
    }

    /**
     * Pause: cancels everything in flight (the load/generation loop included)
     * and publishes PAUSED on every surface — but only if no newer command
     * superseded this pause before its publish (CR-5/CR-7).
     */
    internal fun pausePlayer(reason: PauseReason, resumeOnGain: Boolean = false) {
        this.resumeOnGain = resumeOnGain
        val active = machine ?: return
        val live = liveOffsetSeconds()
        stopEverything()
        launchCommand { generation ->
            active.pause(live)
            if (active(generation)) publish()
        }
    }

    internal fun navigate(move: suspend (PlayerStateMachine) -> List<PlayerEvent>) {
        val active = machine ?: return
        val wasPaused = active.state.value.phase == PlayerPhase.PAUSED
        val live = liveOffsetSeconds()
        stopEverything()
        launchCommand { generation ->
            commandLock.lock()
            try {
                if (!active(generation)) return@launchCommand
                active.notePlaybackOffset(live)
                move(active)
                ringHasEntries = store.readRing(active.bookId).isNotEmpty()
                if (wasPaused) active.pause() // A7: navigation never resumes a paused playhead
                if (active(generation)) publish()
            } finally {
                commandLock.unlock()
            }
            // CR-5: a stale command never restarts the loop against the
            // machine another command won.
            if (!active(generation)) return@launchCommand
            if (!wasPaused) startLoop()
        }
    }

    /**
     * Rolling seek (decisions #53): converts the playhead to global book-time
     * (chars/15 speech model), applies the delta, clamps to the book, and maps
     * back to a spine position — seeks roll across passage boundaries. The
     * machine's [PlayerStateMachine.seekTo] pushes the position being left for
     * one undo.
     */
    internal fun seekBy(deltaSeconds: Double) {
        val active = machine ?: return
        val wasPaused = active.state.value.phase == PlayerPhase.PAUSED
        val live = liveOffsetSeconds()
        stopEverything()
        launchCommand { generation ->
            commandLock.lock()
            try {
                if (!active(generation)) return@launchCommand
                active.notePlaybackOffset(live)
                val activeBook = book ?: return@launchCommand
                val position = active.state.value.position ?: return@launchCommand
                val target = BookProgress.positionAt(
                    activeBook,
                    BookProgress.elapsedSeconds(activeBook, position) + deltaSeconds,
                )
                active.seekTo(target)
                ringHasEntries = store.readRing(active.bookId).isNotEmpty()
                // A7 (CR-7): seek repositions a paused playhead without resuming.
                if (wasPaused) active.pause()
                if (active(generation)) publish()
            } finally {
                commandLock.unlock()
            }
            // CR-5: a stale command never restarts the loop against the
            // machine another command won.
            if (!active(generation)) return@launchCommand
            if (!wasPaused) startLoop()
        }
    }

    private fun navigateUndo() {
        val active = machine ?: return
        val wasPaused = active.state.value.phase == PlayerPhase.PAUSED
        stopEverything()
        launchCommand { generation ->
            commandLock.lock()
            try {
                if (!active(generation)) return@launchCommand
                active.undoSkip()
                ringHasEntries = store.readRing(active.bookId).isNotEmpty()
                if (wasPaused) active.pause() // A7: undo never resumes a paused playhead
                if (active(generation)) publish()
            } finally {
                commandLock.unlock()
            }
            // CR-5: a stale command never restarts the loop against the
            // machine another command won.
            if (!active(generation)) return@launchCommand
            if (!wasPaused) startLoop()
        }
    }

    private fun cycleSpeed() {
        val active = machine ?: return
        val current = active.state.value.speed
        val next = SPEED_PRESETS[(SPEED_PRESETS.indexOfFirst { kotlin.math.abs(it - current) < 1e-9 }.coerceAtLeast(0) + 1) % SPEED_PRESETS.size]
        val live = liveOffsetSeconds()
        stopEverything()
        launchCommand { generation ->
            commandLock.lock()
            try {
                if (!active(generation)) return@launchCommand
                settings.reload() // speed change rebuilds the queue anyway; keep voice fresh
                active.pause(live)
                active.setSpeed(next)
                queue = buildQueue()
                active.resume()
                if (active(generation)) publish()
            } finally {
                commandLock.unlock()
            }
            // CR-5: a stale command never restarts the loop against the
            // machine another command won.
            if (!active(generation)) return@launchCommand
            startLoop()
        }
    }

    private fun cycleSleepTimer() {
        val active = machine ?: return
        val next = when (active.state.value.sleepTimer) {
            SleepTimer.Off -> SleepTimer.EndOfChapter
            SleepTimer.EndOfChapter -> SleepTimer.Duration(clock() + 30 * 60_000L)
            is SleepTimer.Duration -> SleepTimer.Off
        }
        active.setSleepTimer(next)
        publish()
    }

    private fun addBookmarkAtPlayhead() {
        val active = machine ?: return
        val position = active.state.value.position ?: return
        val label = book?.passageText(position.chapterIndex, position.passageIndex)?.take(48)
        scope.launch {
            active.notePlaybackOffset(liveOffsetSeconds())
            active.addBookmark(label = label)
            refreshBookmarks()
            publish()
        }
    }

    private fun stopPlayer() {
        PlaybackActive.markStopped()
        captureAndStop()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * CR-2: captures the live playhead BEFORE any output teardown and hands
     * the single authoritative final write to [finalStopJob]. [stopEverything]
     * releases [output] (zeroing its head), so capturing first is what keeps
     * STOP from rewinding persistence to the buffer's start offset.
     * Returns the captured book-time offset.
     */
    internal fun captureAndStop(): Double {
        val finalOffset = liveOffsetSeconds()
        stopEverything()
        finalStopJob = scope.launch {
            machine?.stop(finalOffset)
            PlaybackStateHolder.reset()
        }
        return finalOffset
    }

    // ------------------------------------------------------------------
    // Playback loop

    private suspend fun startLoop() {
        loopJob = coroutineContext[Job]
        tickerJob = scope.launch { ticker() }
        while (true) {
            val active = machine ?: return
            val current = active.state.value
            if (current.phase != PlayerPhase.LOADING) return
            val position = current.position ?: return
            val activeBook = book ?: return
            val text = activeBook.passageText(position.chapterIndex, position.passageIndex) ?: return

            // Fast path (T5), now with two tiers: the in-memory look-ahead queue
            // first, then the offline disk cache (#42) — a passage pre-generated
            // overnight plays without synthesis; a cold/jumped passage falls
            // back to a synchronous synthesize.
            val voice = activeVoice()
            val key = PregenKey(activeBook.id, position.chapterIndex, position.passageIndex, voice, current.speed)
            // Deterministic re-seek (layer 2): in-flight first-listen persists
            // land before any re-fetch, so a played passage is always on disk.
            pendingPersists.forEach { it.join() }
            val fromLast = lastAudio?.takeIf { it.first == key }?.second
            val fromQueue = fromLast
                ?.let { null }
                ?: queue?.take(position.chapterIndex, position.passageIndex)
            val fromDisk = fromQueue
                ?.let { null }
                ?: pregenCache.cache.get(key)
            val outcome = fromLast
                ?.let { SynthesisOutcome.Audio(it.pcm, it.sampleRateHz, channelCount = 1, segments = it.segments) }
                ?: fromQueue
                    ?.let { SynthesisOutcome.Audio(it.pcm, it.sampleRateHz, channelCount = 1, segments = it.segments) }
                ?: fromDisk
                    ?.let { SynthesisOutcome.Audio(it.pcm, it.sampleRateHz, channelCount = 1, segments = it.segments) }
                ?: (runtime.engine()?.synthesize(SynthesisRequest(text, voice, speed = current.speed))
                    ?: SynthesisOutcome.Failed("engine unavailable"))
            android.util.Log.d("PlaybackService", "loop: source=" + when {
                fromLast != null -> "buffer"
                fromQueue != null -> "pregen"
                fromDisk != null -> "disk"
                else -> "synthesized"
            })
            val audio = outcome as? SynthesisOutcome.Audio ?: run {
                val reason = (outcome as? SynthesisOutcome.Failed)?.reason ?: "engine/packs unavailable"
                PlaybackStateHolder.update { it.copy(failure = "synthesis failed: $reason") }
                return
            }
            // First listen of this passage (no source had it): persist it so
            // an offline run never redoes the work — normal use fills the cache.
            // The write is tracked so seek paths join it (deterministic disk).
            if (fromLast == null && fromQueue == null && fromDisk == null) {
                val toCache = PregenAudio(audio.pcm, audio.sampleRateHz, audio.segments)
                pendingPersists += scope.launch(Dispatchers.IO) {
                    pregenCache.cache.put(key, toCache)
                }
            }
            // Keep the rendered passage for same-passage seek reuse (layer 1).
            lastAudio = key to PregenAudio(audio.pcm, audio.sampleRateHz, audio.segments)
            segments = audio.segments ?: emptyList()
            baselineOffset = position.offsetSeconds
            active.onAudioStarted()
            android.util.Log.d("PlaybackService", "loop: playing ${position.chapterIndex}/${position.passageIndex} ${audio.pcm.size / 2} frames at ${current.speed}x voice=$voice")

            val sliced = sliceForSpeed(audio.pcm, baselineOffset, audio.sampleRateHz, current.speed)
            output.play(sliced, audio.sampleRateHz, current.speed)
            // Pre-generate the passages ahead while this one plays.
            pregenJob?.cancel()
            pregenJob = scope.launch { queue?.ensure(position) }
            val finished = awaitPlaybackOrStop(sliced.size / 2, active)
            android.util.Log.d("PlaybackService", "loop: await returned finished=$finished (pos=${output.positionSamples}/${sliced.size / 2})")
            if (!finished) return

            val events = active.onPassageFinished()
            android.util.Log.d("PlaybackService", "loop: onPassageFinished events=$events")
            if (events.isEmpty()) return
            if (events.any { it is PlayerEvent.PlaybackCompleted || it is PlayerEvent.PauseRequested }) {
                publish()
                return
            }
            publish()
        }
    }

    private suspend fun awaitPlaybackOrStop(totalFrames: Int, active: PlayerStateMachine): Boolean {
        // Static tracks park the head at the end without a marker callback on
        // some devices; poll the head position (10 ms of margin).
        val target = totalFrames - FRAME_MARGIN
        while (true) {
            if (stopSignal.isCompleted) return false
            if (output.positionSamples >= target) return true
            // CR-2: throttled live-playhead checkpoint while playing — abrupt
            // process death loses at most one interval, not a whole passage.
            // Runs in the player coroutine, so it cannot race the machine's
            // own passage transitions (single-player-writer edge).
            if (dueCheckpoint(clock())) active.notePlaybackOffset(liveOffsetSeconds())
            delay(50)
        }
    }

    /** CR-2 checkpoint gate: true at most every [CHECKPOINT_MS] of wall time
     * (persistence cadence — never every UI tick). */
    internal fun dueCheckpoint(nowMs: Long): Boolean {
        if (nowMs - lastCheckpointAt < CHECKPOINT_MS) return false
        lastCheckpointAt = nowMs
        return true
    }

    private suspend fun ticker() {
        while (true) {
            delay(TICK_MS)
            val active = machine ?: return
            val events = active.advance(clock())
            if (events.any { it == PlayerEvent.PauseRequested }) {
                val live = liveOffsetSeconds()
                stopEverything()
                active.pause(live)
                publish()
                return
            }
            if (events.isEmpty() && active.state.value.phase in SETTLED_PHASES) publish()
        }
    }

    private fun publish() {
        val active = machine ?: run { PlaybackStateHolder.reset(); return }
        val state = active.state.value
        val position = state.position
        PlaybackStateHolder.update {
            it.copy(
                bookId = active.bookId,
                bookTitle = book?.title ?: "",
                authors = book?.authors ?: emptyList(),
                chapterIndex = position?.chapterIndex ?: 0,
                passageIndex = position?.passageIndex ?: 0,
                passageText = position?.let { p -> book?.passageText(p.chapterIndex, p.passageIndex) } ?: "",
                passageDurationSeconds = segments.lastOrNull()?.endSeconds ?: 0.0,
                chapters = book?.chapters?.map { it.title.orEmpty() } ?: emptyList(),
                segments = segments,
                offsetSeconds = liveOffsetSeconds(),
                readFraction = position?.let { p -> book?.let { BookProgress.fraction(it, p.chapterIndex, p.passageIndex) } } ?: 0f,
                elapsedSeconds = position?.let { p -> book?.let { BookProgress.elapsedSeconds(it, p) } } ?: 0.0,
                timeLeftSeconds = position?.let { p ->
                    book?.let { BookProgress.remainingSeconds(it, p.chapterIndex, p.passageIndex, liveOffsetSeconds(), state.speed) }
                } ?: 0.0,
                speed = state.speed,
                phase = state.phase,
                sleepTimer = state.sleepTimer,
                canUndo = ringHasEntries,
                failure = state.failure ?: PlaybackStateHolder.state.value.failure,
            )
        }
        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, book?.title ?: "")
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "local-tts-reader")
                .build(),
        )
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_STOP,
                )
                .setState(
                    when (state.phase) {
                        PlayerPhase.PLAYING, PlayerPhase.LOADING -> PlaybackStateCompat.STATE_PLAYING
                        PlayerPhase.PAUSED -> PlaybackStateCompat.STATE_PAUSED
                        PlayerPhase.COMPLETED -> PlaybackStateCompat.STATE_STOPPED
                        PlayerPhase.IDLE -> PlaybackStateCompat.STATE_NONE
                    },
                    PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN,
                    1f,
                )
                .build(),
        )
        runCatching { NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification()) }
    }

    /** Live playhead in book-time seconds within the passage. */
    private fun liveOffsetSeconds(): Double {
        val active = machine ?: return 0.0
        // The buffer is book-time and sped by setPlaybackRate, so the head
        // position counts book-time frames at ANY speed (decisions #52).
        return baselineOffset + output.positionSamples / (KokoroEngine.SAMPLE_RATE.toDouble())
    }

    // ------------------------------------------------------------------
    // Media session / focus / noisy / notification

    private val mediaCallback = object : MediaSessionCompat.Callback() {
        override fun onPlay() = resumePlayer()
        override fun onPause() = pausePlayer(PauseReason.USER)
        override fun onStop() = stopPlayer()
        override fun onSkipToNext() = navigate { it.skipForward() }
        override fun onSkipToPrevious() = navigate { it.skipBackward() }
    }

    private fun requestFocus() {
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setOnAudioFocusChangeListener(focusListener, mainHandler)
            .build()
        focusRequest = request
        audioManager.requestAudioFocus(request)
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (ducking) {
                    ducking = false
                    output.setVolume(1f)
                }
                if (resumeOnGain && machine?.state?.value?.phase == PlayerPhase.PAUSED) {
                    resumeOnGain = false
                    resumePlayer()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                ducking = false
                pausePlayer(PauseReason.FOCUS, resumeOnGain = false)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> pausePlayer(PauseReason.FOCUS, resumeOnGain = true)
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                ducking = true
                output.setVolume(DUCK_VOLUME)
            }
        }
    }

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pausePlayer(PauseReason.NOISY)
        }
    }

    private fun buildNotification(): Notification {
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val openApp = launch?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }
        fun action(intentAction: String, icon: Int, label: String) = NotificationCompat.Action(
            icon, label,
            PendingIntent.getService(
                this, intentAction.hashCode(),
                Intent(this, PlaybackService::class.java).setAction(intentAction),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        val active = machine?.state?.value
        val playing = active?.phase == PlayerPhase.PLAYING || active?.phase == PlayerPhase.LOADING
        return NotificationCompat.Builder(this, CHANNEL_ID)
                        .setContentTitle(book?.title ?: "Ayvu")
                        .setContentText("Chapter ${(active?.position?.chapterIndex ?: 0) + 1} · Passage ${(active?.position?.passageIndex ?: 0) + 1} · ${"%.2g".format(active?.speed ?: 1.0)}×")
            .setLargeIcon(coverArt())
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setContentIntent(openApp)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(action(ACTION_SKIP_BACKWARD, android.R.drawable.ic_media_previous, "Previous"))
            .addAction(
                action(
                    if (playing) ACTION_PAUSE else ACTION_RESUME,
                    if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                    if (playing) "Pause" else "Play",
                ),
            )
            .addAction(action(ACTION_SKIP_FORWARD, android.R.drawable.ic_media_next, "Next"))
            .addAction(action(ACTION_STOP, android.R.drawable.ic_menu_close_clear_cancel, "Stop"))
            .setStyle(MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(1))
            .build()
        }

    /** The book's cover bitmap for the media notification, cached per book
     * and downsampled to ≤ ~512 px (album-art only — full res is overkill). */
    private fun coverArt(): Bitmap? {
        val id = book?.id ?: return null
        if (coverArtBookId == id) return coverArt
        coverArtBookId = id
        val file = File(filesDir, "covers/$id")
        coverArt = if (file.isFile) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, bounds)
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= 512 && bounds.outHeight / (sample * 2) >= 512) sample *= 2
                BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
            }.getOrNull()
        } else {
            null
        }
        return coverArt
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Ayvu playback", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun buildQueue(): PregenQueue? {
        val active = machine ?: return null
        val activeBook = book ?: return null
        val speed = active.state.value.speed
        return PregenQueue(
            book = activeBook,
            voice = activeVoice(),
            speed = speed,
            synthesize = { text ->
                runtime.engine()?.synthesize(SynthesisRequest(text, activeVoice(), speed = speed))
                    ?: SynthesisOutcome.Failed("engine unavailable")
            },
        )
    }

    /** The selected Kokoro voice (V1 settings); defaults to af_heart until chosen. */
    private fun activeVoice(): String = settings.state.value.voice

    internal fun stopEverything() {
        // CR-5/CR-7: supersede every in-flight command BEFORE cancelling —
        // the generation check is what stops a stale load from publishing at
        // its (otherwise uncancellable) tail.
        commandGeneration++
        commandJob?.cancel()
        commandJob = null
        loopJob?.cancel()
        loopJob = null
        playerJob?.cancel()
        playerJob = null
        tickerJob?.cancel()
        tickerJob = null
        pregenJob?.cancel()
        pregenJob = null
        stopSignal.complete(Unit)
        stopSignal = CompletableDeferred()
        output.stop()
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    /**
     * CR-5/CR-7: launches a command coroutine under the generation guard.
     * Every control-plane command routes through here (NOT
     * [addBookmarkAtPlayhead]/[refreshBookmarks] — those must not take over
     * command ownership). [active] checks before side effects are what make a
     * superseded command a no-op even when cancellation alone would not reach
     * it in time.
     */
    internal fun launchCommand(block: suspend (generation: Long) -> Unit) {
        val generation = commandGeneration
        commandJob = scope.launch { block(generation) }
    }

    /** CR-5/CR-7: true only for the current command generation. */
    private fun active(generation: Long): Boolean = generation == commandGeneration

    override fun onDestroy() {
        PlaybackActive.markStopped()
        runBlocking {
            // CR-2: exactly one authoritative final write — join a graceful
            // STOP's write; otherwise write the captured playhead ourselves
            // (captured before teardown).
            teardownWrite()
            stopEverything()
            PlaybackStateHolder.reset()
        }
        session.isActive = false
        session.release()
        runCatching { unregisterReceiver(noisyReceiver) }
        scope.cancel()
        super.onDestroy()
    }

    /**
     * CR-2: the single final machine write. When a graceful STOP's write is in
     * flight ([finalStopJob]), joins it — never double-writing with a stale
     * offset computed from an already-released output. Otherwise captures the
     * live playhead first and writes it.
     */
    internal suspend fun teardownWrite() {
        val pending = finalStopJob
        if (pending != null) {
            pending.join()
        } else {
            val finalOffset = liveOffsetSeconds()
            machine?.stop(finalOffset)
        }
    }

    private fun Intent.bookId(): String? = getStringExtra(EXTRA_BOOK_ID)

    internal enum class PauseReason { USER, FOCUS, NOISY, SLEEP }

    companion object {
        private const val CHANNEL_ID = "playback"
        private const val NOTIFICATION_ID = 42
        private const val TICK_MS = 1_000L
        /** CR-2 live-playhead persistence cadence (roadmap A2). */
        internal const val CHECKPOINT_MS = 5_000L
        private const val SEEK_STEP_SECONDS = 30.0
        private const val FRAME_MARGIN = 240 // 10 ms at 24 kHz
        private const val DUCK_VOLUME = 0.2f
        private val SPEED_PRESETS = listOf(1.0, 1.25, 1.5, 2.0)
        private val SETTLED_PHASES = setOf(PlayerPhase.PLAYING, PlayerPhase.PAUSED, PlayerPhase.LOADING)
        private var clock: () -> Long = System::currentTimeMillis

        const val ACTION_OPEN = "open"
        const val ACTION_PLAY = "play"
        const val ACTION_PLAY_POSITION = "play_position"
        const val ACTION_OPEN_CHAPTER = "open_chapter"
        const val ACTION_RESUME = "resume"
        const val ACTION_PAUSE = "pause"
        const val ACTION_SKIP_FORWARD = "skip_forward"
        const val ACTION_SKIP_BACKWARD = "skip_backward"
        const val ACTION_SEEK_FORWARD = "seek_forward"
        const val ACTION_SEEK_BACKWARD = "seek_backward"
        const val ACTION_UNDO = "undo"
        const val ACTION_STOP = "stop"
        const val ACTION_SLEEP = "sleep"
        const val ACTION_SPEED = "speed"
        const val ACTION_BOOKMARK = "bookmark"
        const val EXTRA_BOOK_ID = "bookId"
        const val EXTRA_CHAPTER = "chapter"
        const val EXTRA_PASSAGE = "passage"
        const val EXTRA_DIRECTION = "direction"

        /**
         * Book-time start slicing: trims the passage PCM to the playhead only.
         * Speed is NOT applied here — [PassageOutput] tempo-changes the buffer
         * via `AudioTrack.setPlaybackRate`, so frames stay book-time and a
         * mid-passage resume skips exactly `offsetSeconds * sampleRate` frames
         * at any speed (decisions #52).
         */
        fun sliceForSpeed(pcm: ByteArray, offsetSeconds: Double, sampleRate: Int, speed: Double): ByteArray {
            if (offsetSeconds <= 0.0) return pcm
            val skipBytes = ((offsetSeconds * sampleRate).toInt() * 2).coerceIn(0, maxOf(pcm.size - 2, 0))
            return pcm.copyOfRange(skipBytes, pcm.size)
        }
    }
}
