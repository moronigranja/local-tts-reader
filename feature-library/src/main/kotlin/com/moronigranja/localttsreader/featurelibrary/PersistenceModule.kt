package com.moronigranja.localttsreader.featurelibrary

import android.content.Context
import androidx.room.Room
import com.moronigranja.localttsreader.model.LibraryStore
import com.moronigranja.localttsreader.persistence.BookDao
import com.moronigranja.localttsreader.persistence.LibraryDatabase
import com.moronigranja.localttsreader.persistence.PassageDao
import com.moronigranja.localttsreader.persistence.ProgressDao
import com.moronigranja.localttsreader.persistence.RoomLibraryStore
import com.moronigranja.localttsreader.persistence.SettingsDao
import com.moronigranja.localttsreader.persistence.SettingsStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope

/**
 * Hilt bindings for the Room persistence layer (P1/P2). The store contract
 * [LibraryStore] is bound to the single [RoomLibraryStore] instance, so the
 * list UI, the importer, and the launch-time index rebuild all share one
 * database-backed surface.
 */
@Module
@InstallIn(SingletonComponent::class)
object PersistenceModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): LibraryDatabase =
        Room.databaseBuilder(context, LibraryDatabase::class.java, "local-tts-reader.db").build()

    @Provides
    fun provideBookDao(database: LibraryDatabase): BookDao = database.bookDao()

    @Provides
    fun providePassageDao(database: LibraryDatabase): PassageDao = database.passageDao()

    @Provides
    fun provideProgressDao(database: LibraryDatabase): ProgressDao = database.progressDao()

    @Provides
    fun provideSettingsDao(database: LibraryDatabase): SettingsDao = database.settingsDao()

    @Provides
    @Singleton
    fun provideRoomLibraryStore(
        database: LibraryDatabase,
        scope: CoroutineScope,
    ): RoomLibraryStore = RoomLibraryStore(database, scope)

    @Provides
    @Singleton
    fun provideLibraryStore(store: RoomLibraryStore): LibraryStore = store

    @Provides
    @Singleton
    fun provideSettingsStore(dao: SettingsDao): SettingsStore = SettingsStore(dao)
}
