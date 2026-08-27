package com.moronigranja.localttsreader.featureplayer.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.moronigranja.localttsreader.model.CachedBook
import com.moronigranja.localttsreader.persistence.AppSettings
import com.moronigranja.localttsreader.persistence.RoomLibraryStore
import com.moronigranja.localttsreader.player.pregen.OfflinePregen
import com.moronigranja.localttsreader.player.pregen.PregenBudget
import com.moronigranja.localttsreader.player.pregen.PregenProgress
import com.moronigranja.localttsreader.player.pregen.PregenTerminal
import com.moronigranja.localttsreader.tts.SynthesisOutcome
import com.moronigranja.localttsreader.tts.SynthesisRequest
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Offline pre-generation worker (decisions #42): runs the tested
 * [OfflinePregen] core over the library store's cached parses into the
 * shared [PregenCache], so the work is pure scheduling over a tested cache.
 *
 * Modes (input `KEY_MODE`):
 * - `MODE_MANUAL` — one book (`KEY_BOOK_IDS`), unbounded time: the run ends
 *   when the book is fully cached, the tier saturates, or the user cancels.
 * - `MODE_OVERNIGHT` — every book, charging-gated periodic job; yields to an
 *   active playback session ([PlaybackActive]) and keeps a 3h wall budget
 *   per night; re-runs resume from whatever the cache already holds.
 *
 * Runs as a foreground worker (dataSync) — synthesis is minutes-to-hours, so
 * the process must not be reaped; the notification carries progress and the
 * library row observes [androidx.work.WorkInfo] progress. The engine's
 * synthesis is cancellable per batch ([PregenWorker]'s cancellation stops the
 * run at the next passage boundary).
 *
 * CR-1: only safely bounded terminals ([PregenTerminal.Completed],
 * [PregenTerminal.BudgetExhausted], [PregenTerminal.CacheSaturated] and the
 * overnight playback yield) settle as success. Engine failure terminals
 * ([PregenTerminal.Unavailable], [PregenTerminal.FailureCap]) fail the job
 * with a typed error and the run's progress counts.
 */
@HiltWorker
class PregenWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val runtime: KokoroRuntime,
    private val libraryStore: RoomLibraryStore,
    private val settings: AppSettings,
    private val pregenCache: PregenCache,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        settings.reload()
        val engine = runtime.engine()
            ?: return Result.failure(workDataOf(KEY_ERROR to (runtime.failureReason ?: "engine unavailable")))

        val mode = inputData.getString(KEY_MODE) ?: MODE_MANUAL
        // Manual runs take the library row's chosen listening-time budget
        // (KEY_BUDGET_TIME_MS); absent → whole book (the pre-budget default).
        val budget = when (mode) {
            MODE_OVERNIGHT -> OVERNIGHT_BUDGET
            else -> inputData.getLong(KEY_BUDGET_TIME_MS, -1L).takeIf { it > 0 }
                ?.let { PregenBudget(maxTimeMs = it) } ?: MANUAL_BUDGET
        }
        val voice = inputData.getString(KEY_VOICE) ?: settings.state.value.voice
        val speed = inputData.getDouble(KEY_SPEED, 1.0)

        val wantedIds = inputData.getStringArray(KEY_BOOK_IDS)?.toSet()
        val books = libraryStore.cachedBooks().filter { wantedIds == null || it.id in wantedIds }
        if (books.isEmpty()) return Result.success()

        val synthesize: suspend (String) -> SynthesisOutcome = { text ->
            engine.synthesize(SynthesisRequest(text, voice, speed))
        }
        var chapterNotified = -1

        suspend fun notify(bookTitle: String, progress: PregenProgress) {
            val percent = progress.percent
            setProgress(
                workDataOf(
                    KEY_PROGRESS_PERCENT to percent,
                    KEY_PROGRESS_CHAPTER to progress.chaptersDone,
                    KEY_PROGRESS_TOTAL_CHAPTERS to progress.totalChapters,
                    KEY_PROGRESS_BOOK to bookTitle,
                ),
            )
            if (progress.chaptersDone != chapterNotified) {
                chapterNotified = progress.chaptersDone
                setForeground(
                    ForegroundInfo(
                        NOTIFICATION_ID,
                        pregenNotification(
                            bookTitle = bookTitle,
                            chapter = progress.chaptersDone,
                            totalChapters = progress.totalChapters,
                            percent = percent,
                            overnight = mode == MODE_OVERNIGHT,
                        ),
                        // Explicit type: implicit MANIFEST resolution is rejected
                        // on some API-34 devices even with the manifest set (#42).
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                    ),
                )
            }
        }

        return runBooks(
            books = books,
            mode = mode,
            budget = budget,
            voice = voice,
            speed = speed,
            synthesize = synthesize,
            notify = { title, progress -> notify(title, progress) },
        )
    }

    /**
     * The book loop over [OfflinePregen], separated from [doWork]'s engine
     * open so host tests can drive it with a virtual [clock] (CR-1:
     * whole-book runs are unbounded and failure terminals fail the job).
     */
    internal suspend fun runBooks(
        books: List<CachedBook>,
        mode: String,
        budget: PregenBudget,
        voice: String,
        speed: Double,
        synthesize: suspend (String) -> SynthesisOutcome,
        clock: () -> Long = System::currentTimeMillis,
        notify: suspend (String, PregenProgress) -> Unit = { _, _ -> },
    ): Result {
        val startedAt = clock()
        var bookIndex = 0
        for (book in books) {
            if (mode == MODE_OVERNIGHT && PlaybackActive.isActive) break
            val elapsed = clock() - startedAt
            // CR-1: an absent deadline (whole-book manual) is NOT an expired
            // one. remaining == null → unbounded; break only when non-null and
            // exhausted.
            val remaining = budget.remainingTimeMs(elapsed)
            if (remaining != null && remaining <= 0L) break
            val runner = OfflinePregen(
                cache = pregenCache.cache,
                synthesize = synthesize,
                shouldContinue = { mode != MODE_OVERNIGHT || !PlaybackActive.isActive },
            )
            bookIndex++
            if (bookIndex == 1) {
                setForeground(
                    ForegroundInfo(
                        NOTIFICATION_ID,
                        pregenNotification(book.title, 0, 0, 0, overnight = mode == MODE_OVERNIGHT),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                    ),
                )
            }
            val result = runner.run(
                book = book.toBook(),
                voice = voice,
                speed = speed,
                budget = budget.copy(maxTimeMs = remaining),
            ) { notify(book.title, it) }
            // CR-1: failure terminals must not settle as success. Engine
            // conditions (missing packs, a synthesis meltdown) are global —
            // they will hit every remaining book the same way, so the job
            // stops with a typed error instead of reporting completed work.
            when (result.terminal) {
                PregenTerminal.Completed,
                PregenTerminal.BudgetExhausted,
                PregenTerminal.CacheSaturated,
                PregenTerminal.Yielded,
                -> Unit
                PregenTerminal.Unavailable,
                PregenTerminal.FailureCap,
                -> return Result.failure(
                    workDataOf(
                        KEY_ERROR to runErrorMessage(book.title, result, result.terminal ?: PregenTerminal.Completed),
                        KEY_PROGRESS_SYNTHESIZED to result.passagesSynthesized,
                        KEY_PROGRESS_CACHED to result.passagesCached,
                        KEY_PROGRESS_FAILURES to result.failures,
                    ),
                )
                // run() sets a terminal on every return path; a missing one
                // is a contract violation — never a silent success.
                null -> return Result.failure(
                    workDataOf(KEY_ERROR to "Pre-generation stopped without a terminal reason ($book.title)"),
                )
            }
        }
        return Result.success()
    }

    private fun runErrorMessage(bookTitle: String, progress: PregenProgress, terminal: PregenTerminal): String {
        val reason = when (terminal) {
            PregenTerminal.Unavailable ->
                "Pre-generation stopped: the synthesis engine is unavailable (packs missing?)"
            PregenTerminal.FailureCap ->
                "Pre-generation stopped after ${progress.failures} repeated synthesis failures"
            else -> "Pre-generation stopped ($terminal)"
        }
        return "$reason — $bookTitle (" +
            "${progress.passagesSynthesized} synthesized, ${progress.passagesCached} cached, ${progress.failures} failed)"
    }

    private fun pregenNotification(
        bookTitle: String,
        chapter: Int,
        totalChapters: Int,
        percent: Int,
        overnight: Boolean,
    ): Notification {
        ensureChannel()
        val body = when {
            totalChapters == 0 -> "Pre-generating $bookTitle…"
            percent >= 100 -> "$bookTitle — offline audio ready"
            else -> "$bookTitle — chapter ${chapter + 1}/$totalChapters ($percent%)"
        }
        return NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(if (overnight) "Ayvu — overnight pre-generation" else "Ayvu — pre-generating")
            .setContentText(body)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, percent, percent <= 0)
            .build()
    }

    private fun ensureChannel() {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Pre-generation", NotificationManager.IMPORTANCE_LOW),
        )
    }

    companion object {
        const val MODE_MANUAL = "manual"
        const val MODE_OVERNIGHT = "overnight"
        const val KEY_MODE = "mode"
        const val KEY_BOOK_IDS = "bookIds"
        const val KEY_VOICE = "voice"
        const val KEY_SPEED = "speed"
        const val KEY_BUDGET_TIME_MS = "budgetTimeMs"
        const val KEY_ERROR = "error"
        const val KEY_PROGRESS_SYNTHESIZED = "progressSynthesized"
        const val KEY_PROGRESS_CACHED = "progressCached"
        const val KEY_PROGRESS_FAILURES = "progressFailures"
        const val KEY_PROGRESS_PERCENT = "progressPercent"
        const val KEY_PROGRESS_CHAPTER = "progressChapter"
        const val KEY_PROGRESS_TOTAL_CHAPTERS = "progressTotalChapters"
        const val KEY_PROGRESS_BOOK = "progressBook"
        const val OVERNIGHT_NAME = "offline-pregen-overnight"
        const val NOTIFICATION_ID = 43
        private const val CHANNEL_ID = "pregen"
        /**
         * Manual: whole book by default, or bounded by a KEY_BUDGET_TIME_MS
         * input (the library's pre-generate overlay); a run always ends when
         * the tier saturates at its byte cap or the user cancels.
         */
        val MANUAL_BUDGET = PregenBudget()
        /** Overnight: the charger window is finite; the cache resumes next night. */
        val OVERNIGHT_BUDGET = PregenBudget(maxTimeMs = 3L * 60 * 60 * 1_000)

        fun workName(bookId: String) = "offline-pregen-$bookId"
    }
}