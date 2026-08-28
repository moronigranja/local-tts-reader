package com.moronigranja.localttsreader.featureshare

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.moronigranja.localttsreader.persistence.AppSettings
import com.moronigranja.localttsreader.persistence.ThemeMode
import com.moronigranja.localttsreader.ui.AyvuTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * The ACTION_SEND gateway (S2): a launcher-less, exported activity that
 * receives plain-text and image shares from any app, resolves them against
 * the library, and shows the verdict. Refresh cached settings on entry (a
 * share can boot the process; V1 settings must apply immediately).
 */
@AndroidEntryPoint
class ShareReceiverActivity : ComponentActivity() {

    @Inject lateinit var appSettings: AppSettings
    @Inject lateinit var openHandler: ShareOpenHandler

    private val viewModel: ShareViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val settingsState by appSettings.state.collectAsState()
            val dark = when (settingsState.theme) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            AyvuTheme(darkTheme = dark) {
                LaunchedEffect(Unit) { appSettings.reload() }
                ShareResultScreen(
                    onClose = { finish() },
                    onListen = { found ->
                        // S3: the app-side handler opens MainActivity at the passage.
                        openHandler.open(OpenTarget(found.bookId, found.chapterIndex, found.passageIndex))
                        finish()
                    },
                )
            }
        }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        viewModel.resolve(
            extraText = intent.getStringExtra(Intent.EXTRA_TEXT),
            extraStream = intent.getParcelableExtra(Intent.EXTRA_STREAM),
            mimeType = intent.type,
        )
    }
}
