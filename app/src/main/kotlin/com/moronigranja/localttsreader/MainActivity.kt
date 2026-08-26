package com.moronigranja.localttsreader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import com.moronigranja.localttsreader.featurelibrary.LibraryScreen
import com.moronigranja.localttsreader.featureplayer.ui.ReaderScreen
import com.moronigranja.localttsreader.featuresettings.SettingsScreen
import com.moronigranja.localttsreader.persistence.AppSettings
import com.moronigranja.localttsreader.persistence.ThemeMode
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var appSettings: AppSettings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // V1 theme-follows-system: the activity level owns the palette; the
            // stored mode arrives async at start, then follows every change.
            val themeMode by appSettings.themeMode.collectAsState()
            val dark = when (themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            LaunchedEffect(Unit) { appSettings.reload() }
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                var openBookId by rememberSaveable { mutableStateOf<String?>(null) }
                var openSettings by rememberSaveable { mutableStateOf(false) }
                val bookId = openBookId
                when {
                    openSettings -> SettingsScreen(onBack = { openSettings = false })
                    bookId != null -> ReaderScreen(bookId = bookId, onClose = { openBookId = null })
                    else -> LibraryScreen(
                        onOpenBook = { openBookId = it },
                        onOpenSettings = { openSettings = true },
                    )
                }
            }
        }
    }
}
