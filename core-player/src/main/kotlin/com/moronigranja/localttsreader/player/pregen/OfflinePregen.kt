package com.moronigranja.localttsreader.player.pregen

import com.moronigranja.localttsreader.model.Book
import com.moronigranja.localttsreader.tts.SynthesisOutcome
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** One run's limits; null fields are unbounded. */
data class PregenBudget(
    /** Stop after this many passages processed (synthesized, cached-hit, or failed). */
    val maxPassages: Int? = null,
    /** Stop after this many chapters fully walked. */
    val maxChapters: Int? = null,
    /** Wall-clock stop for the whole run. */
    val maxTimeMs: Long? = null,
)

/**
 * Running tallies for one book; [OnProgress] fires after every passage.
 * [totalPassages] counts the full walk, so [percent] is monotonic and
 * comparable across runs.
 */
data class PregenProgress(
    val chaptersDone: Int = 0,
    val passagesSynthesized: Int = 0,
    val passagesCached: Int = 0,
    val failures: Int = 0,
    val totalChapters: Int,
    val totalPassages: Int,
) {
    val processed: Int get() = passagesSynthesized + passagesCached + failures
    val percent: Int get() = if (totalPassages == 0) 100 else (processed * 100 / totalPassages).coerceIn(0, 100)
}

/**
 * Offline chapter pre-generation (post-v1 slice, decisions #42): synthesizes
 * whole books into the [PcmPassageCache] disk tier in spine order, skipping
 * passages already cached — so a run resumes anywhere (the cache is the
 * source of truth) and budgets keep the overnight job from hogging the
 * charger forever.
 *
 * Contract:
 * - Walks chapters 0..n and passages 0..m; every passage already on disk is
 *   skipped without synthesis.
 * - Stops when the budget is exhausted ([PregenBudget]), when the cache is
 *   saturated (free space below the last synthesized passage's size — a put
 *   would only evict another entry), when [shouldContinue] turns false (the
 *   caller yields to playback), or when the caller's coroutine is cancelled
 *   (checked per passage; the engine's synthesis is cancellable per batch).
 * - A failed synthesis is counted and the walk continues — the cache never
 *   holds a placeholder — but [consecutiveFailureCap] consecutive failures
 *   stop the run (an engine meltdown must not spin). `Unavailable` stops
 *   immediately: missing packs will not heal within the run.
 */
class OfflinePregen(
    private val cache: PcmPassageCache,
    private val synthesize: suspend (text: String) -> SynthesisOutcome,
    private val consecutiveFailureCap: Int = 5,
    private val shouldContinue: () -> Boolean = { true },
    /**
     * Wall clock for [PregenBudget.maxTimeMs]; injectable so tests drive the
     * budget with virtual time.
     */
    private val clock: () -> Long = System::currentTimeMillis,
) {

    init {
        require(consecutiveFailureCap > 0) { "consecutiveFailureCap must be positive" }
    }

    suspend fun run(
        book: Book,
        voice: String,
        speed: Double,
        budget: PregenBudget = PregenBudget(),
        onProgress: suspend (PregenProgress) -> Unit = {},
    ): PregenProgress {
        val context = currentCoroutineContext()
        val startedAt = clock()
        var progress = PregenProgress(
            totalChapters = book.chapters.size,
            totalPassages = book.chapters.sumOf { it.passages.size },
        )
        var consecutiveFailures = 0
        var lastPutBytes = 0L

        suspend fun bump(update: PregenProgress.() -> PregenProgress) {
            progress = update(progress)
            onProgress(progress)
        }

        for (chapter in book.chapters) {
            budget.maxChapters?.let { if (progress.chaptersDone >= it) return progress }
            context.ensureActive()
            if (!shouldContinue()) return progress

            for ((passageIndex, passage) in chapter.passages.withIndex()) {
                context.ensureActive()
                if (!shouldContinue()) return progress
                budget.maxTimeMs?.let { if (clock() - startedAt >= it) return progress }
                budget.maxPassages?.let { if (progress.processed >= it) return progress }

                val key = PregenKey(book.id, chapter.index, passageIndex, voice, speed)
                if (cache.contains(key)) {
                    bump { copy(passagesCached = passagesCached + 1) }
                    consecutiveFailures = 0
                    continue
                }
                if (cache.bytesRemaining() == 0L) return progress
                if (lastPutBytes > 0L && cache.bytesRemaining() < lastPutBytes) return progress

                when (val outcome = synthesize(passage.text)) {
                    is SynthesisOutcome.Audio -> {
                        cache.put(key, PregenAudio(outcome.pcm, outcome.sampleRateHz, outcome.segments))
                        bump { copy(passagesSynthesized = passagesSynthesized + 1) }
                        consecutiveFailures = 0
                        lastPutBytes = outcome.pcm.size.toLong()
                    }
                    is SynthesisOutcome.Unavailable -> return progress
                    is SynthesisOutcome.Failed -> {
                        bump { copy(failures = failures + 1) }
                        consecutiveFailures++
                        if (consecutiveFailures >= consecutiveFailureCap) return progress
                    }
                }
            }
            bump { copy(chaptersDone = chaptersDone + 1) }
        }
        return progress
    }
}