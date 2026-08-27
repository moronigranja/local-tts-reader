package com.moronigranja.localttsreader.featureplayer.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
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
import com.moronigranja.localttsreader.player.PlayerEvent
import com.moronigranja.localttsreader.player.PlayerPhase
import com.moronigranja.localttsreader.player.PlayerPosition
import com.moronigranja.localttsreader.player.PlayerStateMachine
import com.moronigranja.localttsreader.player.PlayerStore
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
import javax.inject.Inject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
    private var machine: PlayerStateMachine? = null
    private var book: Book? = null
    private var output: PassageOutput = AudioTrackPassageOutput()
    private var playerJob: Job? = null
    private var tickerJob: Job? = null
    private var pregenJob: Job? = null
    private var queue: PregenQueue? = null
    private var stopSignal = CompletableDeferred<Unit>()
    private var segments: List<SegmentAnchor> = emptyList()
    private var baselineOffset = 0.0
    private var ringHasEntries = false
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
            ACTION_PLAY -> startPlayback(intent.bookId(), explicit = false)
            ACTION_PLAY_POSITION -> startPlayback(intent.bookId(), explicit = true, intent = intent)
            ACTION_RESUME -> resumePlayer()
            ACTION_PAUSE -> pausePlayer(PauseReason.USER)
            ACTION_SKIP_FORWARD -> navigate { it.skipForward() }
            ACTION_SKIP_BACKWARD -> navigate { it.skipBackward() }
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

    private fun startPlayback(bookId: String?, explicit: Boolean, intent: Intent? = null) {
        val id = bookId ?: return
        if (runtime.engine() == null) {
            PlaybackStateHolder.update { it.copy(failure = runtime.failureReason ?: "engine unavailable") }
            return
        }
        stopEverything()
        requestFocus()
        scope.launch {
            settings.reload() // V1: settings written by the UI apply at the next play action
            val activeBook = runCatching { libraryStore.cachedBooks() }.getOrNull()
                ?.firstOrNull { it.id == id }?.toBook() ?: return@launch
            book = activeBook
            machine = PlayerStateMachine(store, BookLayout(activeBook))
            queue = buildQueue()
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
                    return@launch
                })
            }
            if (machine!!.state.value.phase != PlayerPhase.LOADING) {
                PlaybackStateHolder.update { it.copy(failure = "nothing to play") }
                return@launch
            }
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
        scope.launch {
            settings.reload() // V1: voice/speed changes from settings apply on resume
            if (phase == PlayerPhase.COMPLETED) {
                active.playFrom(PlayerPosition(active.bookId, 0, 0))
            } else {
                active.resume()
            }
            queue = buildQueue()
            PlaybackActive.markStarted()
            startForeground(NOTIFICATION_ID, buildNotification())
            publish()
            startLoop()
        }
    }

    private fun pausePlayer(reason: PauseReason, resumeOnGain: Boolean = false) {
        this.resumeOnGain = resumeOnGain
        val active = machine ?: return
        val live = liveOffsetSeconds()
        stopEverything()
        scope.launch {
            active.pause(live)
            publish()
        }
    }

    private fun navigate(move: suspend (PlayerStateMachine) -> List<PlayerEvent>) {
        val active = machine ?: return
        val live = liveOffsetSeconds()
        stopEverything()
        scope.launch {
            active.notePlaybackOffset(live)
            move(active)
            ringHasEntries = store.readRing(active.bookId).isNotEmpty()
            publish()
            startLoop()
        }
    }

    private fun navigateUndo() {
        val active = machine ?: return
        stopEverything()
        scope.launch {
            active.undoSkip()
            ringHasEntries = store.readRing(active.bookId).isNotEmpty()
            publish()
            startLoop()
        }
    }

    private fun cycleSpeed() {
        val active = machine ?: return
        val current = active.state.value.speed
        val next = SPEED_PRESETS[(SPEED_PRESETS.indexOfFirst { kotlin.math.abs(it - current) < 1e-9 }.coerceAtLeast(0) + 1) % SPEED_PRESETS.size]
        val live = liveOffsetSeconds()
        stopEverything()
        scope.launch {
            settings.reload() // speed change rebuilds the queue anyway; keep voice fresh
            active.pause(live)
            active.setSpeed(next)
            queue = buildQueue()
            active.resume()
            publish()
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
            publish()
        }
    }

    private fun stopPlayer() {
        PlaybackActive.markStopped()
        stopEverything()
        scope.launch {
            machine?.stop(liveOffsetSeconds())
            PlaybackStateHolder.reset()
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ------------------------------------------------------------------
    // Playback loop

    private suspend fun startLoop() {
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
            val fromQueue = queue?.take(position.chapterIndex, position.passageIndex)
            val fromDisk = fromQueue
                ?.let { null }
                ?: pregenCache.cache.get(key)
            val outcome = fromQueue
                ?.let { SynthesisOutcome.Audio(it.pcm, it.sampleRateHz, channelCount = 1, segments = it.segments) }
                ?: fromDisk
                    ?.let { SynthesisOutcome.Audio(it.pcm, it.sampleRateHz, channelCount = 1, segments = it.segments) }
                ?: (runtime.engine()?.synthesize(SynthesisRequest(text, voice, speed = current.speed))
                    ?: SynthesisOutcome.Failed("engine unavailable"))
            android.util.Log.d("PlaybackService", "loop: source=" + when {
                fromQueue != null -> "pregen"
                fromDisk != null -> "disk"
                else -> "synthesized"
            })
            val audio = outcome as? SynthesisOutcome.Audio ?: run {
                val reason = (outcome as? SynthesisOutcome.Failed)?.reason ?: "engine/packs unavailable"
                PlaybackStateHolder.update { it.copy(failure = "synthesis failed: $reason") }
                return
            }
            // First listen of this passage (neither tier had it): persist it so
            // an offline run never redoes the work — normal use fills the cache.
            if (fromQueue == null && fromDisk == null) {
                scope.launch(Dispatchers.IO) {
                    pregenCache.cache.put(key, PregenAudio(audio.pcm, audio.sampleRateHz, audio.segments))
                }
            }
            segments = audio.segments ?: emptyList()
            baselineOffset = position.offsetSeconds
            active.onAudioStarted()
            android.util.Log.d("PlaybackService", "loop: playing ${position.chapterIndex}/${position.passageIndex} ${audio.pcm.size / 2} frames at ${current.speed}x voice=$voice")

            val sliced = sliceForSpeed(audio.pcm, baselineOffset, audio.sampleRateHz, current.speed)
            output.play(sliced, audio.sampleRateHz)
            // Pre-generate the passages ahead while this one plays.
            pregenJob?.cancel()
            pregenJob = scope.launch { queue?.ensure(position) }
            val finished = awaitPlaybackOrStop(sliced.size / 2)
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

    private suspend fun awaitPlaybackOrStop(totalFrames: Int): Boolean {
        // Static tracks park the head at the end without a marker callback on
        // some devices; poll the head position (10 ms of margin).
        val target = totalFrames - FRAME_MARGIN
        while (true) {
            if (stopSignal.isCompleted) return false
            if (output.positionSamples >= target) return true
            delay(50)
        }
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
                chapterIndex = position?.chapterIndex ?: 0,
                passageIndex = position?.passageIndex ?: 0,
                passageText = position?.let { p -> book?.passageText(p.chapterIndex, p.passageIndex) } ?: "",
                passageDurationSeconds = segments.lastOrNull()?.endSeconds ?: 0.0,
                chapters = book?.chapters?.map { it.title.orEmpty() } ?: emptyList(),
                segments = segments,
                offsetSeconds = liveOffsetSeconds(),
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
        val speed = active.state.value.speed.takeIf { it > 0 } ?: 1.0
        return baselineOffset + output.positionSamples / (KokoroEngine.SAMPLE_RATE * speed)
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
            .setContentTitle(book?.title ?: "local-tts-reader")
            .setContentText("Chapter ${(active?.position?.chapterIndex ?: 0) + 1} · Passage ${(active?.position?.passageIndex ?: 0) + 1} · ${"%.2g".format(active?.speed ?: 1.0)}×")
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

    private fun createNotificationChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Playback", NotificationManager.IMPORTANCE_LOW),
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

    private fun stopEverything() {
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

    override fun onDestroy() {
        PlaybackActive.markStopped()
        runBlocking { stopEverything(); machine?.stop(liveOffsetSeconds()); PlaybackStateHolder.reset() }
        session.isActive = false
        session.release()
        runCatching { unregisterReceiver(noisyReceiver) }
        scope.cancel()
        super.onDestroy()
    }

    private fun Intent.bookId(): String? = getStringExtra(EXTRA_BOOK_ID)

    private enum class PauseReason { USER, FOCUS, NOISY, SLEEP }

    companion object {
        private const val CHANNEL_ID = "playback"
        private const val NOTIFICATION_ID = 42
        private const val TICK_MS = 1_000L
        private const val FRAME_MARGIN = 240 // 10 ms at 24 kHz
        private const val DUCK_VOLUME = 0.2f
        private val SPEED_PRESETS = listOf(1.0, 1.25, 1.5, 2.0)
        private val SETTLED_PHASES = setOf(PlayerPhase.PLAYING, PlayerPhase.PAUSED, PlayerPhase.LOADING)
        private var clock: () -> Long = System::currentTimeMillis

        const val ACTION_PLAY = "play"
        const val ACTION_PLAY_POSITION = "play_position"
        const val ACTION_RESUME = "resume"
        const val ACTION_PAUSE = "pause"
        const val ACTION_SKIP_FORWARD = "skip_forward"
        const val ACTION_SKIP_BACKWARD = "skip_backward"
        const val ACTION_UNDO = "undo"
        const val ACTION_STOP = "stop"
        const val ACTION_SLEEP = "sleep"
        const val ACTION_SPEED = "speed"
        const val ACTION_BOOKMARK = "bookmark"
        const val EXTRA_BOOK_ID = "bookId"
        const val EXTRA_CHAPTER = "chapter"
        const val EXTRA_PASSAGE = "passage"

        /** Book-time slicing: a speed-`s` render occupies samples / speed per book-second. */
        fun sliceForSpeed(pcm: ByteArray, offsetSeconds: Double, sampleRate: Int, speed: Double): ByteArray {
            if (offsetSeconds <= 0.0) return pcm
            val skipSamples = (offsetSeconds * sampleRate * speed).toInt()
            val skipBytes = (skipSamples * 2).coerceIn(0, maxOf(pcm.size - 2, 0))
            return pcm.copyOfRange(skipBytes, pcm.size)
        }
    }
}
