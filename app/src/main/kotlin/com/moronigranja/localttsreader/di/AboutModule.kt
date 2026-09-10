package com.moronigranja.localttsreader.di

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.moronigranja.localttsreader.BuildConfig
import com.moronigranja.localttsreader.featuresettings.AppInfo
import com.moronigranja.localttsreader.featuresettings.LinkOpener
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Release 0.1.1 composition root: the Settings About seams. [AppInfo] is the
 * one place the built version is read (`feature-settings` has no `BuildConfig`
 * of its own, decisions #66), and [LinkOpener] adapts the platform's
 * ACTION_VIEW browser dispatch.
 */
@Module
@InstallIn(SingletonComponent::class)
object AboutModule {
    @Provides
    @Singleton
    fun provideAppInfo(): AppInfo = AppInfo(BuildConfig.VERSION_NAME)

    @Provides
    @Singleton
    fun provideLinkOpener(
        @ApplicationContext context: Context,
    ): LinkOpener = IntentLinkOpener(context)
}

/**
 * ACTION_VIEW adapter over the application context: a device with no browser
 * handler (or one that refuses the intent) does nothing at all — the About row
 * stays rendered and the tap is a no-op, never a crash.
 */
private class IntentLinkOpener(
    private val context: Context,
) : LinkOpener {
    override fun open(url: String) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
