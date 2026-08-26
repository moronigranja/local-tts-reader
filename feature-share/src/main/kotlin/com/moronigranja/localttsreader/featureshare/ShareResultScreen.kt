package com.moronigranja.localttsreader.featureshare

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * S2 result UX: "Found: book · chapter · passage" for a hit; a clear
 * not-found card (with the closest candidate dimmed as a hint); typed
 * failures. Close dismisses the share surface.
 */
@Composable
fun ShareResultScreen(
    onClose: () -> Unit,
    viewModel: ShareViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (val s = state) {
                is ShareUiState.Idle, is ShareUiState.Resolving -> {
                    CircularProgressIndicator(Modifier.size(48.dp))
                    Text(
                        if (s is ShareUiState.Resolving) "Reading the library…" else "Preparing…",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                is ShareUiState.Verdict -> VerdictContent(s.resolution, onClose)
            }
        }
    }
}

@Composable
private fun VerdictContent(resolution: ShareResolution, onClose: () -> Unit) {
    when (resolution) {
        is ShareResolution.Found -> FoundCard(resolution)
        is ShareResolution.NotFound -> NotFoundCard(resolution)
        is ShareResolution.Failed -> Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Could not match the share.", style = MaterialTheme.typography.titleMedium)
                Text(resolution.message, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
    OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
        Text("Close")
    }
}

@Composable
private fun FoundCard(found: ShareResolution.Found) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Found in your library", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(found.bookTitle, style = MaterialTheme.typography.titleLarge)
            Text(
                "${found.chapterTitle ?: "Chapter ${found.chapterIndex + 1}"} · Passage ${found.passageIndex + 1}",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                "Match ${(found.confidence * 100).toInt()}%",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
private fun NotFoundCard(notFound: ShareResolution.NotFound) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("No passage matched", style = MaterialTheme.typography.titleLarge)
            Text(
                when (notFound.reason) {
                    ShareResolution.Reason.BLANK -> "The shared text was empty. Share a quote from a book in your library."
                    ShareResolution.Reason.NO_READABLE_TEXT -> "No readable text came out of that image. Try a sharper screenshot."
                    ShareResolution.Reason.OCR_UNAVAILABLE -> "OCR languages aren't installed yet — add one in Settings, then share again."
                    ShareResolution.Reason.NO_MATCH -> "Nothing in your library reached the match threshold. Try a longer quote."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            if (notFound.snippet.isNotBlank()) {
                Text(
                    "\u201C${notFound.snippet.take(120)}\u201D",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            notFound.closest?.let { closest ->
                Text(
                    "Closest: ${closest.bookTitle} (${(closest.confidence * 100).toInt()}%)",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}
