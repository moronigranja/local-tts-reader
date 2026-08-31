package com.moronigranja.localttsreader

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.moronigranja.localttsreader.featurelibrary.LibraryScreen
import com.moronigranja.localttsreader.featureplayer.ui.ReaderScreen
import com.moronigranja.localttsreader.featuresettings.SettingsScreen
import com.moronigranja.localttsreader.featureshare.OpenTarget
import com.moronigranja.localttsreader.persistence.AppSettings
import com.moronigranja.localttsreader.persistence.ThemeMode
import com.moronigranja.localttsreader.player.PlayerPosition
import com.moronigranja.localttsreader.setup.SetupGate
import com.moronigranja.localttsreader.setup.SetupScreen
import com.moronigranja.localttsreader.ui.AyvuTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var appSettings: AppSettings
    @Inject lateinit var setupGate: SetupGate
    /** Process-lifetime scope (app.di) for the gate re-derivation. */
    @Inject lateinit var appScope: CoroutineScope

    /** S3 "Listen here" → { book, passage } consumed once by composition. */
    private var pendingTarget by mutableStateOf<OpenTarget?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingTarget = consumeTarget(intent)
        // C1.4: the gate derives from durable facts (packs/books/engine) on
        // every cold start — never an onboarding flag (C3). Non-blocking:
        // composition starts on the library, then flips once `active` lands.
        appScope.launch { setupGate.evaluate() }
        setContent {
            val settingsState by appSettings.state.collectAsState()
            val dark = when (settingsState.theme) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            AyvuTheme(darkTheme = dark) {
                var openBookId by rememberSaveable { mutableStateOf<String?>(null) }
                var targetChapter by rememberSaveable { mutableStateOf(-1) }
                var targetPassage by rememberSaveable { mutableStateOf(-1) }
                var openSettings by rememberSaveable { mutableStateOf(false) }

                val target = pendingTarget
                if (target != null) {
                    openBookId = target.bookId
                    targetChapter = target.chapterIndex
                    targetPassage = target.passageIndex
                    pendingTarget = null
                }

                val bookId = openBookId
                when {
                    // C1.4: FIRST branch — the guided setup owns the empty tree.
                    setupGate.active -> SetupScreen(
                        onFinished = {
                            setupGate.dismiss()
                            appScope.launch { setupGate.evaluate() }
                        },
                    )
                    openSettings -> SettingsScreen(onBack = { openSettings = false })
                    bookId != null -> ReaderScreen(
                        bookId = bookId,
                        startAt = if (targetChapter >= 0) {
                            PlayerPosition(bookId, targetChapter, targetPassage)
                        } else {
                            null
                        },
                        onClose = {
                            openBookId = null
                            targetChapter = -1
                            targetPassage = -1
                        },
                    )
                    else -> LibraryScreen(
                        onOpenBook = { openBookId = it },
                        onOpenSettings = { openSettings = true },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingTarget = consumeTarget(intent)
    }

    private fun consumeTarget(intent: Intent?): OpenTarget? =
        intent?.let {
            OpenTarget.fromExtras(
                bookId = it.getStringExtra(OpenTarget.EXTRA_BOOK_ID),
                chapterIndex = it.getIntExtra(OpenTarget.EXTRA_CHAPTER, -1),
                passageIndex = it.getIntExtra(OpenTarget.EXTRA_PASSAGE, -1),
            )
        }
}