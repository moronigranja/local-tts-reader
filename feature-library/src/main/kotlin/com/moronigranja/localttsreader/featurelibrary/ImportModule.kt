package com.moronigranja.localttsreader.featurelibrary

import com.moronigranja.localttsreader.ebook.BookImporter
import com.moronigranja.localttsreader.ebook.ImportCoordinator
import com.moronigranja.localttsreader.locate.IndexLock
import com.moronigranja.localttsreader.locate.IndexRebuilder
import com.moronigranja.localttsreader.locate.TextIndex
import com.moronigranja.localttsreader.model.LibraryStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Hilt bindings for the import feature: the domain core ([TextIndex], the
 * [BookImporter] built on it, the [ImportCoordinator] (A3: durable-commit-
 * then-index under [IndexLock]), the launch-time [IndexRebuilder]) plus the
 * IO dispatcher and the process-lifetime [CoroutineScope]. Core modules are pure
 * JVM with plain constructors (no DI annotations), so they are provided here
 * as app-wide singletons; the dispatcher is qualifier-bound for testability.
 */
@Module
@InstallIn(SingletonComponent::class)
object ImportModule {

    @Provides
    @Singleton
    fun provideTextIndex(): TextIndex = TextIndex()

    @Provides
    @Singleton
    fun provideBookImporter(): BookImporter = BookImporter()

    /** CR-3/A3: serializes every index mutation (publish/remove/rebuild). */
    @Provides
    @Singleton
    fun provideIndexLock(): IndexLock = IndexLock()

    /** CR-3/A3: the one import orchestration boundary (parse → durable → index). */
    @Provides
    @Singleton
    fun provideImportCoordinator(
        importer: BookImporter,
        store: LibraryStore,
        index: TextIndex,
        indexLock: IndexLock,
    ): ImportCoordinator = ImportCoordinator(importer, store, index, indexLock)

    @Provides
    @Singleton
    fun provideIndexRebuilder(index: TextIndex): IndexRebuilder = IndexRebuilder(index)

    /** Process-lifetime scope — launch-time index rebuild, long-lived stores. */
    @Provides
    @Singleton
    fun provideAppScope(@IoDispatcher io: CoroutineDispatcher): CoroutineScope =
        CoroutineScope(SupervisorJob() + io)

    @Provides
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO
}
