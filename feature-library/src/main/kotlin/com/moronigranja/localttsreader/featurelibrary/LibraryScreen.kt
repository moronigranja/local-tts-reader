package com.moronigranja.localttsreader.featurelibrary

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.work.WorkInfo
import com.moronigranja.localttsreader.featureplayer.playback.PregenWorker
import kotlinx.coroutines.launch

/**
 * The library list + import flow (C5/C6). Pick ebooks via SAF, import them through
 * the Hilt-provided [LibraryViewModel], and see the result — with progress, a
 * failure dialog when anything failed, and a snackbar for clean successes.
 */
/** MIME types the SAF picker offers. mobi/azw have no registered MIME type, so
 *  providers report them as `application/octet-stream`; the importer still filters
 *  by extension, so stray picks surface as a typed "format not supported" failure. */
private val IMPORT_MIME_TYPES = arrayOf(
    "application/epub+zip",
    "text/plain",
    "text/markdown",
    "text/x-markdown",
    "application/octet-stream",
)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onOpenBook: (String) -> Unit = {},
    onOpenSettings: () -> Unit = {},
    viewModel: LibraryViewModel = viewModel(),
) {
    val library by viewModel.library.collectAsState()
    val importState by viewModel.importState.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // The last finished batch's summary; non-null while the result dialog is up.
    var resultSummary by remember { mutableStateOf<ImportUiState.Summary?>(null) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach(context::takeReadPermission)
            context.toEBookSources(uris).let(viewModel::import)
        }
    }

    val doneState = importState as? ImportUiState.Done
    LaunchedEffect(doneState) {
        if (doneState != null) {
            if (doneState.summary.failed.isNotEmpty()) {
                resultSummary = doneState.summary
            } else if (doneState.summary.added > 0) {
                snackbarHostState.showSnackbar(
                    "Added ${doneState.summary.added} · Unchanged ${doneState.summary.unchanged}",
                )
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Library") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                    TextButton(onClick = { launcher.launch(IMPORT_MIME_TYPES) }) {
                        Text("Import books")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            (importState as? ImportUiState.Importing)?.let { importing ->
                LinearProgressIndicator(
                    progress = { importing.done / importing.total.toFloat() },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "Importing ${importing.done}/${importing.total} — ${importing.currentFileName}",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            if (library.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "No books yet — import your first ebook",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(library, key = { it.book.id }) { entry ->
                        BookRow(entry.book.id, entry.book.title, entry.book.authors, onOpenBook, viewModel)
                    }
                }
            }
        }
    }

    resultSummary?.let { summary ->
        AlertDialog(
            onDismissRequest = { resultSummary = null },
            title = { Text("Import finished") },
            text = {
                Column {
                    Text("Added ${summary.added} · Unchanged ${summary.unchanged}")
                    for ((fileName, message) in summary.failed) {
                        Text(
                            text = "$fileName: $message",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { resultSummary = null }) { Text("OK") }
            },
        )
    }
}

/**
 * One library card with the offline pre-generation action (decisions #42):
 * starts the manual worker (KEEP-deduplicated), shows its live progress from
 * WorkManager, and flips to a "again" affordance once a run succeeded.
 */
@Composable
private fun BookRow(
    bookId: String,
    title: String,
    authors: List<String>,
    onOpenBook: (String) -> Unit,
    viewModel: LibraryViewModel,
) {
    val workInfos by viewModel.pregenWork(bookId).observeAsState(emptyList())
    val running = workInfos.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }
    val percent = workInfos.maxOfOrNull { it.progress.getInt(PregenWorker.KEY_PROGRESS_PERCENT, 0) } ?: 0
    val ready = workInfos.any { it.state == WorkInfo.State.SUCCEEDED }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenBook(bookId) },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
            )
            if (authors.isNotEmpty()) {
                Text(
                    text = authors.joinToString(", "),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when {
                    running -> {
                        LinearProgressIndicator(
                            progress = { percent / 100f },
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 12.dp),
                        )
                        Text("$percent%", style = MaterialTheme.typography.labelSmall)
                    }
                    else -> TextButton(onClick = { viewModel.pregenerate(bookId) }) {
                        Text(if (ready) "Pre-gen again" else "Pre-generate")
                    }
                }
            }
        }
    }
}
