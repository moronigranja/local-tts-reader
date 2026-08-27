package com.moronigranja.localttsreader.featuresettings

import android.content.Context
import com.moronigranja.localttsreader.ocr.TrainedDataPacks
import com.moronigranja.localttsreader.tts.DefaultEngines
import com.moronigranja.localttsreader.tts.EngineDescriptor
import com.moronigranja.localttsreader.tts.PackCache
import com.moronigranja.localttsreader.tts.PackDownloader
import com.moronigranja.localttsreader.tts.PackRegistry
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton
import javax.inject.Named

/**
 * V1 wiring: the pack machinery over the Android transport, shared by the
 * settings UI and (through the cache) the engine runtimes. KokoroRuntime
 * drops its private PackCache for this singleton so "Ready" in the UI and
 * "downloadable" at play time are the same disk truth.
 */
@Module
@InstallIn(SingletonComponent::class)
object SettingsModule {
    /** The app's internal files dir, QUALIFIED — OcrModule's TessDataDir
     * provides a bare `File`, and an unqualified injection would resolve to
     * it (all staging roots would land under `files/tesseract`; #50). */
    @Provides
    @Singleton
    @Named("app_files_dir")
    fun provideAppFilesDir(@ApplicationContext context: Context): File = context.filesDir

    @Provides
    @Singleton
    fun providePackCache(@ApplicationContext context: Context): PackCache =
        PackCache(context.filesDir)

    @Provides
    @Singleton
    fun provideTransport(): com.moronigranja.localttsreader.tts.DownloadTransport =
        AndroidHttpTransport()

    @Provides
    @Singleton
    fun providePackDownloader(
        cache: PackCache,
        transport: com.moronigranja.localttsreader.tts.DownloadTransport,
    ): PackDownloader = PackDownloader(cache, transport)

    /** One registry over both engines: Kokoro-82M + the OCR language packs. */
    @Provides
    @Singleton
    fun providePackRegistry(cache: PackCache, downloader: PackDownloader): PackRegistry {
        val descriptors = DefaultEngines.descriptors + listOf(
            EngineDescriptor(TrainedDataPacks.spec, TrainedDataPacks.all),
        )
        return PackRegistry(cache, downloader, descriptors)
    }

    /** Lazy voice catalog: names arrive from the voices pack once it is verified. */
    @Provides
    @Singleton
    fun provideVoiceCatalog(cache: PackCache): VoiceCatalog = VoiceCatalog(cache)
}
