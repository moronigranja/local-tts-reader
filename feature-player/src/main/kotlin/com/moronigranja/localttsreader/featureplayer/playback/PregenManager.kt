package com.moronigranja.localttsreader.featureplayer.playback

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WorkManager surface for offline pre-generation (decisions #42) — the UI
 * never touches [PregenWorker] internals:
 *
 * - [pregenerate] starts a manual, unique, whole-book run (KEEP: a tap while
 *   one is already queued does nothing).
 * - [ensureOvernightScheduled] installs the 24h charging-gated periodic job
 *   (called once at app start; KEEP keeps the existing schedule).
 * - [workInfo] observes a book's manual job for the library-row progress.
 */
@Singleton
class PregenManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    // Lazy: the Hilt-provided Configuration.Provider (LocalTtsReaderApp) reads
    // the injected worker factory, so no WorkManager touch may happen DURING
    // injection — first use is after onCreate completes.
    private val workManager: WorkManager by lazy { WorkManager.getInstance(context) }

    fun pregenerate(bookId: String) {
        workManager.enqueueUniqueWork(
            PregenWorker.workName(bookId),
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<PregenWorker>()
                .setInputData(
                    workDataOf(
                        PregenWorker.KEY_MODE to PregenWorker.MODE_MANUAL,
                        PregenWorker.KEY_BOOK_IDS to arrayOf(bookId),
                    ),
                )
                .build(),
        )
    }

    fun cancel(bookId: String) {
        workManager.cancelUniqueWork(PregenWorker.workName(bookId))
    }

    /** Idempotent app-start hook: the overnight job is unique and KEEP-ing. */
    fun ensureOvernightScheduled() {
        workManager.enqueueUniquePeriodicWork(
            PregenWorker.OVERNIGHT_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<PregenWorker>(24, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresCharging(true)
                        .setRequiresBatteryNotLow(true)
                        .build(),
                )
                .setInputData(workDataOf(PregenWorker.KEY_MODE to PregenWorker.MODE_OVERNIGHT))
                .build(),
        )
    }

    fun workInfo(bookId: String): LiveData<List<WorkInfo>> =
        workManager.getWorkInfosForUniqueWorkLiveData(PregenWorker.workName(bookId))
}