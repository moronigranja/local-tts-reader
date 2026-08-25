package com.moronigranja.localttsreader.featurelibrary

import com.moronigranja.localttsreader.ebook.BookImporter
import com.moronigranja.localttsreader.locate.IndexRebuilder
import com.moronigranja.localttsreader.locate.TextIndex
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
 * [BookImporter] built on it, the launch-time [IndexRebuilder]) plus the IO
 * dispatcher and the process-lifetime [CoroutineScope]. Core modules are pure
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
    fun provideBookImporter(index: TextIndex): BookImporter = BookImporter(index)

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
