package com.moronigranja.localttsreader.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * C2 (roadmap): the ONE voice-selection surface, reused across first-run
 * setup, Settings and the reader/player voice sheet — decisions #102.4 ("the
 * voice selector is built once as shared groundwork so C2 reuses it instead
 * of growing a second convention"). Pure presentation: selection, favorites,
 * preview and download actions come from the host screen; the composable
 * renders the C2 contract verbatim:
 *
 * - a persistent **Selected voice: _name_** summary;
 * - exactly one row carries the selection indicator (the radio); the star is
 *   a SEPARATE favorite action and never implies or changes selection —
 *   tapping the row selects, tapping the star only toggles the favorite;
 * - every ready row exposes **Preview/Stop**; slow synthesis shows
 *   cancellable **Generating sample…** feedback (static text — reduced-motion
 *   safe by construction);
 * - missing engine assets replace Preview with the same explicit download
 *   action used elsewhere (never silence, never an unannounced fallback);
 * - a saved voice absent from the catalog renders as unavailable with a
 *   download/reselect action instead of leaving every row unselected.
 */
data class VoiceRowUi(
    val name: String,
    val language: String,
    val gender: String,
    val favorite: Boolean = false,
    /** Whether the voice's pack is ready; false → the row's [VoiceSelector]
     * [downloadLabel]/Download action replaces Preview. */
    val ready: Boolean = true,
    val selected: Boolean = false,
    val preview: VoicePreviewUi = VoicePreviewUi.Idle,
)

sealed interface VoicePreviewUi {
    data object Idle : VoicePreviewUi

    data object Generating : VoicePreviewUi

    data object Playing : VoicePreviewUi

    data class Failed(
        val reason: String,
    ) : VoicePreviewUi
}

data class VoiceSelectorUiState(
    val rows: List<VoiceRowUi> = emptyList(),
    /** Persistent summary — "Selected voice: X" (C2 acceptance). */
    val summary: String = "",
    /** A persisted voice absent from the static catalog (e.g. from a pack the
     * current build does not know): shown as unavailable with a download /
     * reselect action, never as an all-unselected list. */
    val unavailableSavedVoice: String? = null,
)

@Composable
fun VoiceSelector(
    state: VoiceSelectorUiState,
    onSelect: (String) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onPreview: (String) -> Unit,
    onStopPreview: () -> Unit,
    onDownload: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (state.summary.isNotBlank()) {
            Text(
                text = state.summary,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(vertical = AyvuSpacing.XS),
            )
        }
        if (state.unavailableSavedVoice != null) {
            UnavailableVoiceRow(
                name = state.unavailableSavedVoice,
                onDownload = { onDownload(state.unavailableSavedVoice) },
            )
        }
        state.rows.groupBy { it.language }.forEach { (language, rows) ->
            SectionHeader(language, Modifier.padding(top = AyvuSpacing.MD, bottom = AyvuSpacing.XS))
            rows.forEach { row ->
                VoiceSelectorRow(
                    row = row,
                    onSelect = onSelect,
                    onToggleFavorite = onToggleFavorite,
                    onPreview = onPreview,
                    onStopPreview = onStopPreview,
                    onDownload = onDownload,
                )
            }
        }
    }
}

@Composable
private fun VoiceSelectorRow(
    row: VoiceRowUi,
    onSelect: (String) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onPreview: (String) -> Unit,
    onStopPreview: () -> Unit,
    onDownload: (String) -> Unit,
) {
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = row.selected,
                        onClick = { onSelect(row.name) },
                    ).padding(vertical = AyvuSpacing.XS),
        ) {
            // The radio is an indicator only — selecting happens on the row
            // tap, so the star stays an independent favorite action.
            RadioButton(selected = row.selected, onClick = null)
            Column(modifier = Modifier.weight(1f)) {
                Text(row.name, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "${row.language} · ${row.gender}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { onToggleFavorite(row.name) }) {
                Icon(
                    imageVector = Icons.Filled.Star,
                    contentDescription = if (row.favorite) "Remove ${row.name} from favorites" else "Favorite ${row.name}",
                    tint =
                        if (row.favorite) {
                            MaterialTheme.colorScheme.tertiary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                )
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(start = 48.dp), // under the name column, clear of the radio
        ) {
            when {
                !row.ready ->
                    TextButton(onClick = { onDownload(row.name) }) {
                        Text("Download this voice's pack")
                    }
                row.preview is VoicePreviewUi.Generating ->
                    Text(
                        "Generating sample…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = AyvuSpacing.SM),
                    )
                row.preview is VoicePreviewUi.Playing ->
                    TextButton(onClick = onStopPreview) {
                        Text("Stop")
                    }
                row.preview is VoicePreviewUi.Failed ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            row.preview.reason,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        TextButton(onClick = { onPreview(row.name) }) { Text("Retry") }
                    }
                else ->
                    TextButton(onClick = { onPreview(row.name) }) {
                        Text("Preview")
                    }
            }
        }
    }
}

@Composable
private fun UnavailableVoiceRow(
    name: String,
    onDownload: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            "Selected voice: $name (unavailable)",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "This voice is not in the current catalog — download its pack or choose another.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = { onDownload(name) }) { Text("Download / choose again") }
    }
}

/**
 * The ONE selector-state builder (C2, decisions #102.4): Setup, Settings and
 * the reader voice sheet all derive [VoiceSelectorUiState] through this
 * function — never a second convention. Rows come from the static
 * [com.moronigranja.localttsreader.tts.kokoro.KokoroVoiceMeta] catalog,
 * readiness from the Kokoro pack states, selection/favorites from the
 * [com.moronigranja.localttsreader.persistence.AppSettings] snapshot,
 * audition stage from the shared coordinator.
 */
fun buildVoiceSelectorState(
    voices: List<com.moronigranja.localttsreader.tts.kokoro.KokoroVoiceMeta>,
    selectedVoice: String,
    favorites: Set<String>,
    ready: Boolean,
    audition: com.moronigranja.localttsreader.player.AuditionUiState,
): VoiceSelectorUiState {
    val known = voices.map(com.moronigranja.localttsreader.tts.kokoro.KokoroVoiceMeta::name).toSet()
    val unavailable = selectedVoice.takeIf { it !in known }
    return VoiceSelectorUiState(
        rows =
            voices.map { meta ->
                VoiceRowUi(
                    name = meta.name,
                    language = meta.language,
                    gender = meta.gender,
                    favorite = meta.name in favorites,
                    ready = ready,
                    selected = meta.name == selectedVoice,
                    preview = previewStage(meta.name, audition),
                )
            },
        summary = if (unavailable == null) "Selected voice: $selectedVoice" else "",
        unavailableSavedVoice = unavailable,
    )
}

private fun previewStage(
    name: String,
    audition: com.moronigranja.localttsreader.player.AuditionUiState,
): VoicePreviewUi =
    if (audition.voice == name) {
        val stage = audition.stage
        when (stage) {
            com.moronigranja.localttsreader.player.AuditionStage.Generating -> VoicePreviewUi.Generating
            com.moronigranja.localttsreader.player.AuditionStage.Playing -> VoicePreviewUi.Playing
            is com.moronigranja.localttsreader.player.AuditionStage.Failed ->
                VoicePreviewUi.Failed(stage.reason)
            com.moronigranja.localttsreader.player.AuditionStage.Idle -> VoicePreviewUi.Idle
        }
    } else {
        VoicePreviewUi.Idle
    }
