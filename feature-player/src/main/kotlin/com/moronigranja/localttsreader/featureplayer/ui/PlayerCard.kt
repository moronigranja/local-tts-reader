package com.moronigranja.localttsreader.featureplayer.ui

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.moronigranja.localttsreader.featureplayer.playback.PlaybackUiState
import com.moronigranja.localttsreader.player.PlayerPhase
import java.io.File

/**
 * The player-card command surface (decisions #53): the same docked-card
 * transport commands, implemented by the reader and library view models.
 */
interface PlayerCommands {
    fun resume()
    fun pause()
    fun seekForward()
    fun seekBackward()
    fun cycleSpeed()
}

/**
 * The app-wide docked player card (decisions #53, mockup-matched): cover
 * thumb, title, subtitle (authors · chapter · passage, or "Generating…"
 * while the engine loads), book-wide progress with elapsed / % /
 * remaining-at-speed, and the transport row — −30s · play/pause (spinner
 * while synthesizing) · +30s · speed pill. State comes from the
 * service-published [PlaybackUiState], commands go through [PlayerCommands];
 * [topRight]/[badge] let the library add its row actions + offline usage;
 * [onOpen] makes the cover/title area open the book.
 */
@Composable
fun PlayerCard(
    state: PlaybackUiState,
    commands: PlayerCommands,
    modifier: Modifier = Modifier,
    /** Library-only surface (decisions #56): overflow actions (pre-gen, delete,
     * remove) drawn at the title row's right, and the offline disk badge drawn
     * under the progress bar. The reader passes nothing. */
    topRight: (@Composable () -> Unit)? = null,
    badge: (@Composable () -> Unit)? = null,
    /** Opens the book (library card: the replaced row was tappable). */
    onOpen: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    var cover by remember(state.bookId) { mutableStateOf(decodeCover(context, state.bookId)) }
    val loading = state.phase == PlayerPhase.LOADING
    val playing = state.phase == PlayerPhase.PLAYING || loading
    val subtitle = when {
        loading -> "Generating…"
        state.authors.isNotEmpty() ->
            state.authors.joinToString(", ") + " · Ch " + (state.chapterIndex + 1) + " · P " + (state.passageIndex + 1)
        else -> "Ch " + (state.chapterIndex + 1) + " · P " + (state.passageIndex + 1)
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
    ) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .then(if (onOpen != null) Modifier.clickable(onClick = onOpen!!) else Modifier),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                if (cover != null) {
                    Image(
                        painter = BitmapPainter(cover!!),
                        contentDescription = state.bookTitle,
                        modifier = Modifier.fillMaxWidth().height(64.dp),
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = state.bookTitle,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (topRight != null) topRight()
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (loading) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { state.readFraction.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
                Spacer(Modifier.height(2.dp))
                if (badge != null) badge()
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = formatClock(state.elapsedSeconds),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = progressLabel(state),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = formatClock(state.timeLeftSeconds) + " left",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PillButton("−30s", onClick = commands::seekBackward)
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable { if (playing) commands.pause() else commands.resume() },
                contentAlignment = Alignment.Center,
            ) {
                if (loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 3.dp,
                    )
                } else {
                    Icon(
                        imageVector = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (playing) "Pause" else "Play",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(30.dp),
                    )
                }
            }
            PillButton("+30s", onClick = commands::seekForward)
            PillButton("${formatSpeed(state.speed)}×", onClick = commands::cycleSpeed)
        }
    }
}

@Composable
private fun PillButton(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onPrimary,
        )
    }
}

/** The book's cover from the sidecar store (`files/covers/<bookId>`), or null. */
private fun decodeCover(context: Context, bookId: String?): androidx.compose.ui.graphics.ImageBitmap? {
    if (bookId == null) return null
    val file = File(context.filesDir, "covers/$bookId")
    if (!file.isFile) return null
    return runCatching {
        BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
    }.getOrNull()
}

/** "M:SS" under an hour, "H:MM:SS" at or above (mockup's 12:15 / 1:03:45). */
private fun formatClock(seconds: Double): String {
    val total = seconds.toInt().coerceAtLeast(0)
    val s = total % 60
    val m = (total / 60) % 60
    val h = total / 3600
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private fun formatSpeed(speed: Double): String =
    if (speed % 1.0 == 0.0) speed.toInt().toString() else speed.toString().trimEnd('0').trimEnd('.')

/** [0..1] position → "%" (sub-1% keeps a decimal so early listening shows motion). */
private fun progressLabel(state: PlaybackUiState): String {
    val percent = state.readFraction * 100
    return if (percent < 1f) String.format("%.1f%%", percent) else "${percent.toInt()}%"
}