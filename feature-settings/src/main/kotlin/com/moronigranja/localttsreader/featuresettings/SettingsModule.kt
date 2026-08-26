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

/**
 * V1 wiring: the pack machinery over the Android transport, shared by the
 * settings UI and (through the cache) the engine runtimes. KokoroRuntime
 * drops its private PackCache for this singleton so "Ready" in the UI and
 * "downloadable" at play time are the same disk truth.
 */
@Module
@InstallIn(SingletonComponent::class)
object SettingsModule {

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

@Module
@InstallIn(SingletonComponent::class)
object EspeakModule {

    @Provides
    @Singleton
    fun provideEspeakStatus(@ApplicationContext context: Context): EspeakBundleStatus {
        val dir = File(context.filesDir, "espeak")
        val lib = File(dir, "libespeak-ng.so")
        val data = File(dir, "espeak-ng-data")
        return if (lib.isFile && data.isDirectory && (data.listFiles()?.isNotEmpty() == true)) {
            EspeakBundleStatus(true, "staged (${lib.length() / 1024} KiB lib + data)")
        } else {
            EspeakBundleStatus(false, "not staged — build with tools/build-espeak-android.sh and adb-stage it (V1 keeps the manual path; the bundle has no pinned artifact host)")
        }
    }

}
