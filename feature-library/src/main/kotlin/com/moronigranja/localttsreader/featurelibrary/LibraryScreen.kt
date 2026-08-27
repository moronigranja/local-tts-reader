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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.ExperimentalMaterial3Api
import android.graphics.BitmapFactory
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
import com.moronigranja.localttsreader.featureplayer.playback.formatBytes
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
    val offline by viewModel.offline.collectAsState()
    val readProgress by viewModel.readProgress.collectAsState()

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
            viewModel.consumeImportResult()
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
                        BookRow(
                            bookId = entry.book.id,
                            title = entry.book.title,
                            authors = entry.book.authors,
                            offline = offline[entry.book.id],
                            readFraction = readProgress[entry.book.id] ?: 0f,
                            onOpenBook = onOpenBook,
                            viewModel = viewModel,
                        )
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
 * One library card with the offline pre-generation action (decisions #42) and
 * storage transparency (decisions #44): the row shows what a full pre-gen
 * costs (~estimate), how much is already on disk, and a one-tap delete that
 * cancels queued work first. A run's settlement refreshes the disk facts.
 */
@Composable
private fun BookRow(
    bookId: String,
    title: String,
    authors: List<String>,
    offline: LibraryViewModel.OfflineBook?,
    readFraction: Float,
    onOpenBook: (String) -> Unit,
    viewModel: LibraryViewModel,
) {
    val workInfos by viewModel.pregenWork(bookId).observeAsState(emptyList())
    val running = workInfos.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }
    val percent = workInfos.maxOfOrNull { it.progress.getInt(PregenWorker.KEY_PROGRESS_PERCENT, 0) } ?: 0
    val usage = offline?.usageBytes ?: 0L
    val estimate = offline?.estimateBytes ?: 0L

    // A settled run changed the tier: re-read usage + estimate (the worker's
    // own progress only carries percentages).
    val settled = workInfos.lastOrNull {
        it.state == WorkInfo.State.SUCCEEDED || it.state == WorkInfo.State.FAILED || it.state == WorkInfo.State.CANCELLED
    }?.state
    LaunchedEffect(settled) {
        if (settled != null) viewModel.refreshOffline()
    }

    val cover = rememberCoverBitmap(viewModel, bookId)
    var menuOpen by remember { mutableStateOf(false) }
    var budgetDialog by remember { mutableStateOf(false) }

    if (budgetDialog) {
        PregenBudgetDialog(
            estimate = estimate,
            onPick = { minutes ->
                budgetDialog = false
                viewModel.pregenerate(bookId, minutes)
            },
            onDismiss = { budgetDialog = false },
        )
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenBook(bookId) },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(width = 56.dp, height = 80.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                if (cover != null) {
                    Image(
                        bitmap = cover,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Text(
                        title.take(1).uppercase(),
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
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
                if (readFraction > 0f) {
                    LinearProgressIndicator(
                        progress = { readFraction },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                    )
                    val readPercent = readFraction * 100
                    Text(
                        if (readPercent < 1f) "%.1f%%".format(readPercent) else "${readPercent.toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                when {
                    running -> {
                        LinearProgressIndicator(
                            progress = { percent / 100f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                        )
                        Text("$percent%", style = MaterialTheme.typography.labelSmall)
                    }
                    else -> {
                        if (usage > 0L) {
                            Text(
                                "${formatBytes(usage)} offline",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Book actions")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                buildString {
                                    append("Pre-generate")
                                    if (estimate > 0L) append(" (≈${formatBytes(estimate)})")
                                },
                            )
                        },
                        onClick = {
                            menuOpen = false
                            budgetDialog = true
                        },
                    )
                    if (usage > 0L) {
                        DropdownMenuItem(
                            text = { Text("Delete offline audio") },
                            onClick = {
                                menuOpen = false
                                viewModel.deleteOffline(bookId)
                            },
                        )
                    }
                }
            }
        }
    }
}

/** Decodes the book's extracted cover off the main thread; null while loading or absent. */
@Composable
private fun rememberCoverBitmap(viewModel: LibraryViewModel, bookId: String): ImageBitmap? {
    var bitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(bookId) {
        bitmap = withContext(Dispatchers.IO) {
            viewModel.cover(bookId)?.let { bytes ->
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }
        }
    }
    return bitmap
}

/** The pre-generation budget picker: listening-time options, each with the
 * exact linear byte cost of the estimate model (1 min at the 24 kHz 16-bit
 * mono rate ≈ 2.88 MB; the whole-book estimate matches #44). */
@Composable
private fun PregenBudgetDialog(
    estimate: Long,
    onPick: (Long?) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pre-generate: how much listening time?") },
        text = {
            Column {
                Text(
                    "Synthesis runs in the background; you can keep reading or cancel anytime.",
                    style = MaterialTheme.typography.bodySmall,
                )
                PregenBudgetOption("30 min (≈${formatBytes(BYTES_PER_MINUTE * 30)})") { onPick(30) }
                PregenBudgetOption("1 h (≈${formatBytes(BYTES_PER_MINUTE * 60)})") { onPick(60) }
                PregenBudgetOption("2 h (≈${formatBytes(BYTES_PER_MINUTE * 120)})") { onPick(120) }
                PregenBudgetOption("3 h (≈${formatBytes(BYTES_PER_MINUTE * 180)})") { onPick(180) }
                PregenBudgetOption("Whole book (≈${formatBytes(estimate)})") { onPick(null) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun PregenBudgetOption(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(label)
    }
}

private const val BYTES_PER_MINUTE = 24_000L * 2 * 60
