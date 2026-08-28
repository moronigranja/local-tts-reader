package com.moronigranja.localttsreader.featureplayer.playback

import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * QW5d: app start must neutralize an overnight periodic job left behind by a
 * pre-removal install — the app-start scheduling hook is gone, but the
 * leftover survives in WorkManager's DB and can still fire once after an
 * upgrade. [PregenManager.cancelOvernight] is the startup path
 * (LocalTtsReaderApp.onCreate): enqueue a leftover via the untouched
 * [PregenManager.ensureOvernightScheduled], cancel the way the app does, and
 * assert the unique work is CANCELLED.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PregenManagerCancelTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private lateinit var manager: PregenManager

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        manager = PregenManager(context)
    }

    @Test
    fun `startup cancel neutralizes a leftover overnight job`() {
        // The leftover: enqueued by an older build's app-start hook before
        // the removal (HANDOFF 2a). ensureOvernightScheduled is untouched —
        // it is simply the way such a job lands in the DB.
        manager.ensureOvernightScheduled()
        val enqueued = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(PregenWorker.OVERNIGHT_NAME).get()
        assertTrue("leftover overnight job exists before the cancel", enqueued.isNotEmpty())

        manager.cancelOvernight() // the app startup path

        val after = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(PregenWorker.OVERNIGHT_NAME).get()
        assertTrue("the leftover overnight job is cancelled", after.isNotEmpty())
        assertTrue(
            "no leftover work may survive startup",
            after.all { it.state == WorkInfo.State.CANCELLED },
        )
    }

    @Test
    fun `startup cancel with no leftover is a harmless no-op`() {
        manager.cancelOvernight() // fresh install: nothing enqueued

        val after = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(PregenWorker.OVERNIGHT_NAME).get()
        assertTrue("nothing to cancel on a fresh install", after.isEmpty())
    }
}