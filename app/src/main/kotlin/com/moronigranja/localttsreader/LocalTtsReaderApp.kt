package com.moronigranja.localttsreader

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.moronigranja.localttsreader.locate.IndexLock
import com.moronigranja.localttsreader.locate.IndexRebuilder
import com.moronigranja.localttsreader.persistence.RoomLibraryStore
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * App-level Hilt container; the DI root for feature modules (C5/C6).
 *
 * [onCreate] warms the search index from the cached parses (P2): the rebuild
 * reads the passages stored by [RoomLibraryStore] and **never re-parses** a
 * source file. It runs on the app [appScope] (IO, process-lifetime) and is
 * idempotent — a relaunch, an empty library, or a rebuild racing an import
 * all settle to "index mirrors the persisted library".
 */
@HiltAndroidApp
class LocalTtsReaderApp : Application(), Configuration.Provider {

    @Inject lateinit var libraryStore: RoomLibraryStore
    @Inject lateinit var indexRebuilder: IndexRebuilder
    @Inject lateinit var indexLock: IndexLock
    @Inject lateinit var appScope: CoroutineScope
    @Inject lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        appScope.launch {
            // CR-3/A3: the rebuild reconciles UNDER the index lock — the fresh
            // Room snapshot is read inside the critical section, so a
            // concurrent import can neither be purged by a stale snapshot
            // nor published mid-reconciliation.
            indexLock.withExclusiveIndex {
                indexRebuilder.rebuild(libraryStore.cachedBooks())
            }
        }
    }
}
