package com.moronigranja.localttsreader.featuresettings
import androidx.activity.compose.BackHandler

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.moronigranja.localttsreader.player.formatBytes
import com.moronigranja.localttsreader.persistence.ThemeMode
import com.moronigranja.localttsreader.tts.PackStatus
import com.moronigranja.localttsreader.ui.SectionHeader
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue

/**
 * V1 settings: engines + packs (download/status), voice picker + favorites,
 * share match threshold, OCR languages, theme. Every row maps directly to a
 * [SettingsViewModel] call — no logic in the view.
 */
private enum class SettingsPane { Root, OcrLanguages }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val offlineRows by viewModel.offlineRows.collectAsState()
    var pane by remember { mutableStateOf(SettingsPane.Root) }
    // System back mirrors the top-bar arrow: OCR subpane collapses first,
    // then the settings screen closes back to the library (not app exit).
    BackHandler {
        if (pane == SettingsPane.OcrLanguages) pane = SettingsPane.Root else onBack()
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (pane == SettingsPane.OcrLanguages) "OCR languages" else "Settings") },
                navigationIcon = {
                    IconButton(onClick = {
                        if (pane == SettingsPane.OcrLanguages) pane = SettingsPane.Root else onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        if (pane == SettingsPane.Root) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { SectionHeader("Engine", Modifier.padding(top = 16.dp, bottom = 4.dp)) }
            items(state.packs.filter { it.packId == "kokoro-model" || it.packId == "kokoro-voices" || it.packId == "espeak-ng" }) { row ->
                PackRow(row, onDownload = { viewModel.download(row.packId) })
            }
            item {
                Text(
                    "espeak-ng: ${state.espeakDetail}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                )
            }

            item { SectionHeader("Voice", Modifier.padding(top = 16.dp, bottom = 4.dp)) }
            if (state.voices.isEmpty()) {
                item {
                    Text(
                        "Download the voices pack above to pick a voice.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            } else {
                items(state.voices) { voice ->
                    VoiceRow(
                        name = voice,
                        selected = voice == state.selectedVoice,
                        favorite = voice in state.favoriteVoices,
                        onSelect = { viewModel.selectVoice(voice) },
                        onToggleFavorite = { viewModel.toggleFavorite(voice) },
                    )
                }
            }

            item { SectionHeader("Share & reading", Modifier.padding(top = 16.dp, bottom = 4.dp)) }
            item {
                Column {
                    Text("Match threshold: ${"%.2f".format(state.matchThreshold)}", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "How closely a shared snippet must match a book passage.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Slider(
                        value = state.matchThreshold.toFloat(),
                        onValueChange = { viewModel.setThreshold(it.toDouble()) },
                        valueRange = 0.3f..0.9f,
                    )
                }
            }

            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { pane = SettingsPane.OcrLanguages }
                        .padding(vertical = 8.dp),
                ) {
                    Text(
                        "OCR languages",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                }
            }

            item { SectionHeader("Offline audio", Modifier.padding(top = 16.dp, bottom = 4.dp)) }
            if (offlineRows.isEmpty()) {
                item {
                    Text(
                        "No pre-generated audio — the library row's Pre-generate fills it.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            } else {
                item {
                    Text(
                        "Total: ${formatBytes(offlineRows.sumOf { it.bytes })} — one listened hour ≈ 170 MB",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                }
                items(offlineRows, key = { it.bookId }) { row ->
                    OfflineAudioRow(
                        title = row.title,
                        bytes = row.bytes,
                        onDelete = { viewModel.deleteOffline(row.bookId) },
                    )
                }
            }

            item { SectionHeader("Appearance", Modifier.padding(top = 16.dp, bottom = 4.dp)) }
            item {
                Column {
                    ThemeMode.entries.forEach { mode ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = state.themeMode == mode,
                                    onClick = { viewModel.setTheme(mode) },
                                )
                                .padding(vertical = 4.dp),
                        ) {
                            RadioButton(selected = state.themeMode == mode, onClick = { viewModel.setTheme(mode) })
                            Text(when (mode) {
                                ThemeMode.SYSTEM -> "Follow system"
                                ThemeMode.LIGHT -> "Light"
                                ThemeMode.DARK -> "Dark"
                            })
                        }
                    }
                }
            }
        }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
            ) {
                item { SectionHeader("OCR languages", Modifier.padding(top = 16.dp, bottom = 4.dp)) }
                items(state.packs.filter { it.packId in OCR_PACK_IDS }) { row ->
                    PackRow(row, onDownload = { viewModel.download(row.packId) })
                    OcrLanguageRow(
                        packId = row.packId,
                        enabled = row.staged,
                        selected = row.packId in state.ocrLanguages,
                        onToggle = { viewModel.setOcrLanguage(row.packId, it) },
                    )
                }
                item {
                    Text(
                        "Selected languages are used for shared-image snippets; the bundle installs once.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun OfflineAudioRow(title: String, bytes: Long, onDelete: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(formatBytes(bytes), style = MaterialTheme.typography.labelSmall)
        }
        androidx.compose.material3.TextButton(onClick = onDelete) {
            Text("Delete")
        }
    }
}

@Composable
private fun PackRow(row: PackRow, onDownload: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(row.displayName, style = MaterialTheme.typography.bodyMedium)
            when {
                row.progress != null -> {
                    LinearProgressIndicator(
                        progress = { row.progress.toFloat() },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                    Text("${(row.progress * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
                }
                row.status == PackStatus.Ready -> Text(
                    if (row.staged) "ready · installed" else "ready",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                row.error != null -> {
                    Text("failed: ${row.error}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    TextButtonRetry(onClick = onDownload)
                }
                else -> Text(
                    "${row.sizeBytes / 1_048_576} MiB — download required",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        if (row.progress == null && row.status != PackStatus.Ready) {
            androidx.compose.material3.TextButton(onClick = onDownload) {
                Text("Download")
            }
        } else if (row.status == PackStatus.Ready) {
            Icon(Icons.Default.Check, contentDescription = "Ready", tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun TextButtonRetry(onClick: () -> Unit) {
    androidx.compose.material3.TextButton(onClick = onClick) {
        Text("Retry")
    }
}

@Composable
private fun OcrLanguageRow(packId: String, enabled: Boolean, selected: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp, bottom = 8.dp),
    ) {
        Switch(
            checked = selected && enabled,
            onCheckedChange = onToggle,
            enabled = enabled,
        )
        Text(
            if (enabled) "Use for share OCR" else "Download above, then enable",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
private fun VoiceRow(
    name: String,
    selected: Boolean,
    favorite: Boolean,
    onSelect: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onSelect).padding(vertical = 2.dp),
    ) {
        Text(
            name,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onToggleFavorite) {
            Icon(
                Icons.Filled.Star,
                contentDescription = if (favorite) "Unfavorite $name" else "Favorite $name",
                tint = if (favorite) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outline,
            )
        }
    }
}

private val OCR_PACK_IDS = setOf("eng", "spa", "fra", "deu", "por", "ita")
