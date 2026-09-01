package com.moronigranja.localttsreader.di

import com.moronigranja.localttsreader.player.VoicePackDownloader
import com.moronigranja.localttsreader.tts.PackRegistry
import com.moronigranja.localttsreader.tts.kokoro.KokoroPacks
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Singleton

/**
 * C2 composition root: the explicit voice-pack download the reader's voice
 * sheet surfaces while Kokoro packs are missing — over the same [PackRegistry]
 * Setup and Settings use (one disk truth).
 */
@Module
@InstallIn(SingletonComponent::class)
object VoicePackModule {
    @Provides
    @Singleton
    fun provideVoicePackDownloader(
        registry: PackRegistry,
        appScope: CoroutineScope,
    ): VoicePackDownloader =
        object : VoicePackDownloader {
            override fun requestDownload() {
                appScope.launch {
                    listOf(KokoroPacks.model, KokoroPacks.voices, KokoroPacks.espeak)
                        .forEach { registry.download(it.id) }
                }
            }
        }
}
