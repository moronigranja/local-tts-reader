package com.moronigranja.localttsreader.featureplayer.ui
import androidx.activity.compose.BackHandler

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChanged
import com.moronigranja.localttsreader.featureplayer.playback.PlaybackUiState
import com.moronigranja.localttsreader.player.PlayerPhase
import com.moronigranja.localttsreader.player.PlayerPosition
import com.moronigranja.localttsreader.player.SleepTimer
/**
 * The docked reader+player (decisions #29): the current passage with the
 * active sentence highlighted from the engine's anchors (#31), and the
 * docked transport (play/pause, skip, speed cycle, sleep timer, undo-skip,
 * bookmark at playhead).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    bookId: String,
    onClose: () -> Unit,
    startAt: PlayerPosition? = null,
    viewModel: ReaderViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val scrollState = rememberScrollState()
    var chapterMenu by remember { mutableStateOf(false) }
    // System back returns to the library, same as the top-bar arrow (the
    // reader is a top-level destination, not an exit from the app).
    BackHandler { onClose() }

    // A passage change (auto-advance or skip) resets the view to the new page.
    LaunchedEffect(state.chapterIndex, state.passageIndex) {
        scrollState.scrollTo(0)
    }

    // S3: an explicit target passage (share "Listen here") starts playback
    // there; otherwise normal resume/start.
    LaunchedEffect(bookId, startAt) {
        if (state.bookId != bookId || startAt != null) {
            if (startAt != null) {
                viewModel.playPosition(bookId, startAt.chapterIndex, startAt.passageIndex)
            } else {
                viewModel.play(bookId)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.bookTitle.ifEmpty { "Reader" }) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    Box {
                        TextButton(
                            onClick = { chapterMenu = true },
                            enabled = state.bookId != null && state.chapters.isNotEmpty(),
                        ) {
                            Text("Ch ${state.chapterIndex + 1}/${state.chapters.size}")
                        }
                        DropdownMenu(expanded = chapterMenu, onDismissRequest = { chapterMenu = false }) {
                            state.chapters.forEachIndexed { index, title ->
                                DropdownMenuItem(
                                    text = { Text("${index + 1}. $title") },
                                    onClick = {
                                        chapterMenu = false
                                        viewModel.playPosition(bookId, index, 0)
                                    },
                                )
                            }
                        }
                    }
                    IconButton(onClick = { viewModel.bookmark() }, enabled = state.positioned) {
                        Icon(Icons.Filled.Bookmark, contentDescription = "Bookmark")
                    }
                    IconButton(onClick = { viewModel.undo() }, enabled = state.canUndo) {
                        Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo")
                    }
                    TextButton(onClick = { viewModel.cycleSleep() }) { Text(state.sleepLabel, fontSize = 12.sp) }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            )
        },
        bottomBar = { DockedControls(state, viewModel) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            state.failure?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            if (state.passageText.isBlank() && state.phase == PlayerPhase.IDLE) {
                Text("Tap play to start listening from this book.", textAlign = TextAlign.Center)
            }
            // Passage paging (decisions #49): swipe, or tap the side zones, to
            // page through passages; the middle tap still (re)starts playback
            // at the current one ("listen from here", S3).
            Text(
                annotatedPassage(state, MaterialTheme.colorScheme.tertiaryContainer),
                style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 30.sp),
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(state.positioned) {
                        val pageWidth = size.width / 3f
                        val swipePx = SWIPE_PAGE_THRESHOLD.toPx()
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            var dragX = 0f
                            var paged = false
                            do {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull() ?: break
                                if (change.positionChanged()) {
                                    dragX += change.positionChange().x
                                    if (dragX > swipePx || dragX < -swipePx) {
                                        paged = true
                                        change.consume()
                                    }
                                }
                            } while (!event.changes.all { it.changedToUp() })
                            if (paged) {
                                if (dragX < 0f) viewModel.skipForward() else viewModel.skipBackward()
                            } else if (state.positioned) {
                                val x = down.position.x
                                when {
                                    x < pageWidth -> viewModel.skipBackward()
                                    x > pageWidth * 2f -> viewModel.skipForward()
                                    else -> viewModel.playPosition(bookId, state.chapterIndex, state.passageIndex)
                                }
                            }
                        }
                    },
            )
            Text(
                listOf(
                    "Ch ${state.chapterIndex + 1}",
                    "P ${state.passageIndex + 1}",
                    progressLabel(state),
                    timeLeftLabel(state),
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = Color.Gray,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

private val PlaybackUiState.positioned: Boolean
    get() = bookId != null && (phase != PlayerPhase.IDLE || canUndo)

private val PlaybackUiState.sleepLabel: String
    get() = when (sleepTimer) {
        SleepTimer.Off -> "Sleep: off"
        SleepTimer.EndOfChapter -> "Sleep: ch."
        is SleepTimer.Duration -> "Sleep: 30m"
    }

@Composable
private fun DockedControls(state: PlaybackUiState, viewModel: ReaderViewModel) {
    val playing = state.phase == PlayerPhase.PLAYING || state.phase == PlayerPhase.LOADING
    Column(modifier = Modifier.fillMaxWidth()) {
        if (state.passageDurationSeconds > 0.0) {
            val progress = (state.offsetSeconds / state.passageDurationSeconds).coerceIn(0.0, 1.0).toFloat()
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TransportButton(
                icon = Icons.Filled.SkipPrevious,
                label = "Prev",
                enabled = state.positioned,
            ) { viewModel.skipBackward() }
            TransportButton(
                icon = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                label = if (playing) "Pause" else "Play",
                enabled = state.bookId != null,
            ) { if (playing) viewModel.pause() else viewModel.resume() }
            TransportButton(
                icon = Icons.Filled.SkipNext,
                label = "Next",
                enabled = state.positioned,
            ) { viewModel.skipForward() }
            OutlinedButton(onClick = { viewModel.cycleSpeed() }, enabled = state.positioned) {
                Text("${"%.2g".format(state.speed)}×")
            }
        }
    }
}

@Composable
private fun TransportButton(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, enabled = enabled) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(28.dp))
            Text(label, style = MaterialTheme.typography.labelSmall)
        }
    }
}

/**
 * The passage with the active sentence highlighted. Sentence spans are split
 * on the same `.!?…` marks the engine anchored its segments on (#31), and the
 * active index comes from the playhead against those segments, so the text
 * highlight and the audio are the same sentence.
 */
private fun annotatedPassage(state: PlaybackUiState, highlightColor: Color) = buildAnnotatedString {
    val text = state.passageText
    if (text.isBlank()) return@buildAnnotatedString
    val spans = sentenceSpans(text)
    val highlight = spans.getOrNull(state.activeSentenceIndex)
    var cursor = 0
    for ((index, span) in spans.withIndex()) {
        val end = span.offset + span.length
        if (index == state.activeSentenceIndex) {
            withStyle(
                SpanStyle(
                    background = highlightColor,
                    fontWeight = FontWeight.Bold,
                ),
            ) {
                append(text, cursor, end)
            }
        } else {
            append(text, cursor, end)
        }
        cursor = end
    }
    if (cursor < text.length) append(text, cursor, text.length)
}

private data class SentenceSpan(val offset: Int, val length: Int)

/** Char spans of the sentences, split after `.!?…` and following whitespace. */
private fun sentenceSpans(text: String): List<SentenceSpan> {
    val spans = mutableListOf<SentenceSpan>()
    var start = 0
    var index = 0
    while (index < text.length) {
        if (text[index] in ".!?…") {
            var end = index + 1
            while (end < text.length && text[end].isWhitespace()) end++
            spans += SentenceSpan(start, end - start)
            start = end
            index = end
        } else {
            index++
        }
    }
    if (start < text.length) spans += SentenceSpan(start, text.length - start)
    return spans
}
/** Horizontal drag distance that pages to the next/previous passage. */
private val SWIPE_PAGE_THRESHOLD = 64.dp
/** [0..1] position → "%" (sub-1% keeps a decimal so early listening shows motion). */
private fun progressLabel(state: PlaybackUiState): String {
    val percent = state.readFraction * 100
    return if (percent < 1f) "%.1f%%".format(percent) else "${percent.toInt()}%"
}

/** Estimated remaining listening time at the current speed. */
private fun timeLeftLabel(state: PlaybackUiState): String {
    if (state.phase == PlayerPhase.COMPLETED) return "done"
    val seconds = state.timeLeftSeconds
    return if (seconds <= 0) "end" else "≈${formatClock(seconds)} left"
}

private fun formatClock(seconds: Double): String {
    val total = seconds.toInt()
    if (total < 60) return "${total}s"
    val minutes = total / 60
    if (minutes < 60) return "${minutes}m"
    return "${minutes / 60}h ${"%02d".format(minutes % 60)}m"
}
