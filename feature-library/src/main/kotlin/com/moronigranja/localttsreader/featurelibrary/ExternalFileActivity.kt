package com.moronigranja.localttsreader.featurelibrary

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.moronigranja.localttsreader.ebook.IntakeRouting
import com.moronigranja.localttsreader.persistence.AppSettings
import com.moronigranja.localttsreader.persistence.ThemeMode
import com.moronigranja.localttsreader.ui.AyvuTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * F4 external-file gateway: opened from a file manager's "Open with Ayvu"
 * (ACTION_VIEW, content:// or file:// — the octet-stream filter covers
 * MIME-inconsistent typers, the extension gate is the backstop) and by
 * feature-share's forwarded book-file shares ([IntakeRouting.ACTION_IMPORT_BOOK]).
 * The file lands in the library through the ONE shared batch importer
 * ([LibraryViewModel.import] — F1 progress + F3 typed outcomes); unsupported
 * formats show typed guidance, never a silent no-op. Exported by design
 * (external apps target it); it never appears in recents.
 */
@AndroidEntryPoint
class ExternalFileActivity : ComponentActivity() {
    @Inject lateinit var appSettings: AppSettings

    private val viewModel: LibraryViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val settingsState by appSettings.state.collectAsState()
            val dark =
                when (settingsState.theme) {
                    ThemeMode.SYSTEM -> isSystemInDarkTheme()
                    ThemeMode.LIGHT -> false
                    ThemeMode.DARK -> true
                }
            AyvuTheme(darkTheme = dark) {
                intakeScreen(
                    viewModel = viewModel,
                    onOpenLibrary = { openLibrary() },
                    onClose = { finish() },
                )
            }
        }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val uri =
            when (intent.action) {
                Intent.ACTION_VIEW -> intent.data
                IntakeRouting.ACTION_IMPORT_BOOK -> intent.parcelableStream()
                else -> return
            }
        if (uri == null) return
        // ACTION_VIEW grants may be persistable (re-open after restart);
        // forwarded SEND grants are transient — takeReadPermission swallows
        // both refusals indifferently.
        takeReadPermission(uri)
        val sources = toEBookSources(listOf(uri))
        when (val verdict = IntakeRouting.resolveFile(sources.firstOrNull()?.fileName)) {
            is IntakeRouting.IntakeVerdict.Import -> viewModel.import(sources)
            is IntakeRouting.IntakeVerdict.Guidance ->
                viewModel.showGuidance(verdict.displayName, verdict.message)
        }
    }

    private fun Intent.parcelableStream(): Uri? =
        if (Build.VERSION.SDK_INT >= 33) {
            getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        }

    private fun openLibrary() {
        // The library lives in MainActivity (app module); resolve launcher-style
        // instead of a feature→app class reference (A6).
        val launcher =
            Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        startActivity(launcher)
        finish()
    }
}

/**
 * The gateway's compact surface: import progress (F1), the typed batch
 * summary, and non-import guidance (unsupported/kfx/DRM).
 */
@Composable
fun intakeScreen(
    viewModel: LibraryViewModel,
    onOpenLibrary: () -> Unit,
    onClose: () -> Unit,
) {
    val importState by viewModel.importState.collectAsState()
    val guidance by viewModel.intakeGuidance.collectAsState()
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val g = guidance
        if (g != null) {
            Text(g.first, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            Text(g.second, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(24.dp))
            OutlinedButton(onClick = onClose) { Text("Close") }
            return@Column
        }
        when (val s = importState) {
            is ImportUiState.Importing -> {
                Text(
                    "Importing ${s.done}/${s.total}",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(8.dp))
                Text(s.currentFileName, style = MaterialTheme.typography.bodyMedium)
            }
            is ImportUiState.Done -> {
                val summary = s.summary
                Text("Import complete", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
                Text("Added ${summary.added}", style = MaterialTheme.typography.bodyMedium)
                Text("Already in library ${summary.unchanged}", style = MaterialTheme.typography.bodyMedium)
                summary.failed.forEach { (file, message) ->
                    Spacer(Modifier.height(8.dp))
                    Text("$file — $message", style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(24.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onClose) { Text("Close") }
                    Button(onClick = onOpenLibrary) { Text("Open library") }
                }
            }
            // Idle: the import kicks in on the first frame after handleIntent.
            ImportUiState.Idle, is ImportUiState.Scanning ->
                Text(
                    "…",
                    style = MaterialTheme.typography.bodyMedium,
                )
        }
    }
}
