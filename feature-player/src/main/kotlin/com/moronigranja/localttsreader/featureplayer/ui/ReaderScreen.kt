package com.moronigranja.localttsreader.featureplayer.ui
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.moronigranja.localttsreader.player.PlaybackUiState
import com.moronigranja.localttsreader.player.PlayerPhase
import com.moronigranja.localttsreader.player.PlayerPosition
import com.moronigranja.localttsreader.player.SleepTimer
import com.moronigranja.localttsreader.player.TextPagination
import com.moronigranja.localttsreader.player.chapterMenuLabel
import com.moronigranja.localttsreader.ui.AyvuSpacing
import com.moronigranja.localttsreader.ui.EmptyState
import com.moronigranja.localttsreader.ui.PlayerCard
import kotlin.math.ceil

/**
 * The docked reader+player (decisions #29/#52): a real paginated book page —
 * the chapter's text flows and breaks exactly where it would overflow, every
 * chapter starts on a fresh page, there is NO scroll. The active sentence is
 * highlighted from the engine's anchors (#31) and playback turns pages
 * (only when the spoken passage leaves the current one). Opening a book does
 * NOT auto-play (decisions #52); the transport or the library play button
 * start audio. The shared player card docks below (play/pause, ±30s seek,
 * chapter skip); sleep timer + undo-skip stay in the top bar.
 * Page gestures (horizontal swipe or side-zone taps turn pages;
 * a middle tap starts playback at the passage under the finger) and the
 * bookmark menu (add + jump) round it out.
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
    var chapterMenu by remember { mutableStateOf(false) }
    var bookmarkMenu by remember { mutableStateOf(false) }
    var voiceSheet by remember { mutableStateOf(false) }
    val voiceSelector by viewModel.voiceSelector.collectAsState()
    // System back returns to the library, same as the top-bar arrow (the
    // reader is a top-level destination, not an exit from the app).
    BackHandler { onClose() }

    // Opening positions the reader WITHOUT starting playback (decisions #52);
    // an explicit share target (S3 "listen here") still starts audio there.
    LaunchedEffect(bookId, startAt) {
        if (state.bookId != bookId || startAt != null) {
            if (startAt != null) {
                viewModel.playPosition(bookId, startAt.chapterIndex, startAt.passageIndex)
            } else {
                viewModel.open(bookId)
            }
        }
    }

    Scaffold(
        topBar = {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .statusBarsPadding(),
            ) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(AyvuSpacing.XS),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                    Spacer(modifier = Modifier.weight(1f))
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
                                    text = { Text(chapterMenuLabel(index, title)) },
                                    onClick = {
                                        chapterMenu = false
                                        viewModel.playPosition(bookId, index, 0)
                                    },
                                )
                            }
                        }
                    }
                    Box {
                        IconButton(onClick = { bookmarkMenu = true }, enabled = state.positioned) {
                            Icon(Icons.Filled.Bookmark, contentDescription = "Bookmarks")
                        }
                        DropdownMenu(expanded = bookmarkMenu, onDismissRequest = { bookmarkMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Bookmark this passage") },
                                onClick = {
                                    bookmarkMenu = false
                                    viewModel.bookmark()
                                },
                            )
                            state.bookmarks.forEach { bookmark ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            bookmark.label
                                                ?: "Ch ${bookmark.chapterIndex + 1} · P ${bookmark.passageIndex + 1}",
                                        )
                                    },
                                    onClick = {
                                        bookmarkMenu = false
                                        viewModel.playPosition(bookId, bookmark.chapterIndex, bookmark.passageIndex)
                                    },
                                )
                            }
                        }
                    }
                    IconButton(onClick = { voiceSheet = true }) {
                        Icon(Icons.Filled.RecordVoiceOver, contentDescription = "Change voice")
                    }
                    IconButton(onClick = { viewModel.undo() }, enabled = state.canUndo) {
                        Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo")
                    }
                    TextButton(onClick = { viewModel.cycleSleep() }) {
                        Text(state.sleepLabel, style = MaterialTheme.typography.labelSmall)
                    }
                }
                Text(
                    state.bookTitle.ifEmpty { "Reader" },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = AyvuSpacing.LG, vertical = AyvuSpacing.SM),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        },
        // The shared app-wide player card (decisions #53): cover, progress,
        // times, −30s/◀Ch/play+spinner/Ch▶/+30s. Sleep timer + undo
        // stay in the top bar; the old transport row and footer are gone.
        bottomBar = { PlayerCard(state, viewModel) },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding),
        ) {
            when {
                state.failure != null -> EmptyState(state.failure!!)
                state.chapterPassages.isEmpty() && state.phase == PlayerPhase.IDLE ->
                    EmptyState("Tap play to start listening from this book.")
                else ->
                    PaginatedChapter(
                        state = state,
                        bookId = bookId,
                        viewModel = viewModel,
                        modifier = Modifier.weight(1f),
                    )
            }
        }
    }
    // C2: the reader's voice surface — the same shared selector as Setup and
    // Settings. Selecting a voice persists it and rebuilds the active book at
    // the same playhead (A5); preview is one-at-a-time and narration-safe.
    if (voiceSheet) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { voiceSheet = false },
            confirmButton = {},
            title = { Text("Voice") },
            text = {
                androidx.compose.foundation.layout.Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .verticalScroll(androidx.compose.foundation.rememberScrollState()),
                ) {
                    com.moronigranja.localttsreader.ui.VoiceSelector(
                        state = voiceSelector,
                        onSelect = {
                            viewModel.selectVoice(it)
                            voiceSheet = false
                        },
                        onToggleFavorite = viewModel::toggleFavorite,
                        onPreview = viewModel::previewVoice,
                        onStopPreview = viewModel::stopPreview,
                        onDownload = { viewModel.downloadVoicePacks() },
                    )
                }
            },
        )
    }
}

private val PlaybackUiState.positioned: Boolean
    get() = bookId != null && (phase != PlayerPhase.IDLE || canUndo || passageText.isNotBlank())

private val PlaybackUiState.sleepLabel: String
    get() =
        when (sleepTimer) {
            SleepTimer.Off -> "Sleep: off"
            SleepTimer.EndOfChapter -> "Sleep: ch."
            is SleepTimer.Duration -> "Sleep: 30m"
        }

/**
 * One real book page (decisions #52): the chapter's text flows and breaks
 * exactly where it would overflow the viewport — no scroll — and a new
 * chapter always starts on a fresh page (its first page reserves headroom
 * for the chapter title). Pagination is measured from the fixed-line-height
 * chapter layout; pages are contiguous line ranges, so the rendered slice
 * re-wraps identically (greedy wrap breaks only depend on the line start).
 *
 * Gestures: horizontal swipe or side-zone taps turn pages; a middle tap
 * starts playback at the passage under the finger (S3). Playback turns the
 * page only when the spoken passage leaves the current one.
 */
@Composable
private fun PaginatedChapter(
    state: PlaybackUiState,
    bookId: String,
    viewModel: ReaderViewModel,
    modifier: Modifier = Modifier,
) {
    if (state.chapterPassages.isEmpty()) return

    val textMeasurer = rememberTextMeasurer()
    val highlightColor = MaterialTheme.colorScheme.tertiaryContainer
    val pressedColor = MaterialTheme.colorScheme.surfaceVariant
    // Measurement constant: pagination keys on the FIXED line-height contract.
    val bodyStyle = MaterialTheme.typography.bodyLarge.copy(lineHeight = 30.sp)
    val titleStyle = MaterialTheme.typography.titleLarge
    val indicatorStyle = MaterialTheme.typography.labelSmall
    val density = LocalDensity.current
    // Title gap and page margins are tokenized (decisions #94); the gap and
    // the rendered title padding MUST stay equal — both read AyvuSpacing.MD.
    val titleGapPx = with(density) { AyvuSpacing.MD.toPx() }.toInt()
    val horizontalPadPx = with(density) { AyvuSpacing.LG.toPx() }.toInt()
    var page by remember(state.chapterIndex) { mutableIntStateOf(0) }
    var pressedPassage by remember { mutableStateOf<Int?>(null) }

    BoxWithConstraints(modifier = modifier) {
        val viewportHeight = constraints.maxHeight
        val pageWidth = (constraints.maxWidth - horizontalPadPx * 2).coerceAtLeast(1)
        val chapterTitle = state.chapters.getOrNull(state.chapterIndex).orEmpty()
        val chapterText =
            remember(state.chapterIndex, state.chapterPassages) {
                state.chapterPassages.joinToString("\n\n")
            }
        val passageOffsets = remember(state.chapterPassages) { computePassageOffsets(state.chapterPassages) }
        val activeSpan = activeSentenceRange(passageOffsets, state.passageIndex, state.passageText, state.activeSentenceIndex)
        val bodyLayout =
            remember(chapterText, pageWidth, bodyStyle, activeSpan) {
                val annotated =
                    buildAnnotatedString {
                        append(chapterText)
                        if (activeSpan != null) {
                            addStyle(SpanStyle(fontWeight = FontWeight.Bold), activeSpan.first, activeSpan.second)
                        }
                    }
                textMeasurer.measure(
                    text = annotated,
                    style = bodyStyle,
                    constraints = Constraints(maxWidth = pageWidth),
                )
            }
        val titleHeightPx =
            remember(chapterTitle, pageWidth, titleStyle) {
                if (chapterTitle.isBlank()) {
                    0
                } else {
                    textMeasurer
                        .measure(
                            text = AnnotatedString(chapterTitle),
                            style = titleStyle,
                            constraints = Constraints(maxWidth = pageWidth),
                        ).size.height
                }
            }
        // Real line pitch, measured from the layout itself: on some OEMs the
        // paint-level font scale differs from Density.fontScale (S22 @2.0×:
        // 148px pitch vs 107px from the sp conversion) — a parallel sp→px
        // computation silently over-filled pages, cropped the last line and
        // pushed the indicator out (#87/#95). Pagination keys on the measured
        // pitch so the two can never disagree.
        val lineHeightPx =
            if (bodyLayout.lineCount > 1) {
                (bodyLayout.multiParagraph.getLineTop(1) - bodyLayout.multiParagraph.getLineTop(0)).toInt()
            } else {
                (bodyLayout.multiParagraph.getLineBottom(0) - bodyLayout.multiParagraph.getLineTop(0)).toInt()
            }
        // Bottom-crop-safe reserve for the page indicator (decisions #94):
        // measured from a real layout of the indicator text (its rendered
        // height) plus the 2 * XS vertical padding, so the reserve matches
        // what renders — the #87 last-line-visible invariant holds by
        // construction at any font scale.
        val indicatorReservedPx =
            remember(pageWidth, indicatorStyle) {
                textMeasurer
                    .measure(
                        text = AnnotatedString("Page 999 of 999"),
                        style = indicatorStyle,
                        constraints = Constraints(maxWidth = pageWidth),
                    ).size.height
            } + 2 * with(density) { AyvuSpacing.XS.roundToPx() }

        val totalLines = bodyLayout.lineCount
        val firstPageLines =
            TextPagination.linesPerPage(
                viewportHeight,
                lineHeightPx,
                reservedPx = titleHeightPx + titleGapPx + indicatorReservedPx,
            )
        val fullPageLines = TextPagination.linesPerPage(viewportHeight, lineHeightPx, reservedPx = indicatorReservedPx)
        val totalPages = TextPagination.totalPages(totalLines, firstPageLines, fullPageLines)
        val range =
            remember(page, totalLines, firstPageLines, fullPageLines) {
                val start = TextPagination.pageStartLine(page.coerceIn(0, totalPages - 1), firstPageLines, fullPageLines)
                if (start >= totalLines) {
                    start until start
                } else {
                    start until (start + if (page <= 0) firstPageLines else fullPageLines).coerceAtMost(totalLines)
                }
            }
        val startChar = if (page <= 0 || range.isEmpty()) 0 else bodyLayout.multiParagraph.getLineStart(range.first)
        val endChar = if (range.isEmpty()) startChar else bodyLayout.multiParagraph.getLineEnd(range.last)
        // Line each passage begins on — tap-mapping + follow.
        val passageStartLines =
            remember(bodyLayout, passageOffsets, totalLines) {
                passageOffsets.map { bodyLayout.getLineForOffset(it.coerceAtMost(maxOf(0, chapterText.length - 1))) }
            }

        // Playback turns the page only when the spoken passage leaves it.
        LaunchedEffect(state.chapterIndex, state.passageIndex, state.phase, totalPages) {
            if (state.phase == PlayerPhase.PLAYING || state.phase == PlayerPhase.LOADING) {
                val line = passageStartLines.getOrNull(state.passageIndex) ?: return@LaunchedEffect
                val target = TextPagination.pageOf(line.coerceAtMost(maxOf(0, totalLines - 1)), firstPageLines, fullPageLines)
                if (target != page) page = target.coerceIn(0, totalPages - 1)
            }
        }
        // A chapter opened WITHOUT playback (boundary turn, share-open, resume)
        // shows the presented passage's page, not page one — the backward turn
        // lands on the previous chapter's LAST passage, so its ending page is
        // what the reader must open at. Chapter no-ops (book edges) change no
        // key here, so the page stays put.
        LaunchedEffect(state.chapterIndex) {
            if (state.phase != PlayerPhase.PLAYING && state.phase != PlayerPhase.LOADING) {
                val line = passageStartLines.getOrNull(state.passageIndex) ?: return@LaunchedEffect
                val targetPage = TextPagination.pageOf(line.coerceAtMost(maxOf(0, totalLines - 1)), firstPageLines, fullPageLines)
                if (targetPage != page) page = targetPage.coerceIn(0, totalPages - 1)
            }
        }

        val pageSlice = chapterText.substring(startChar.coerceIn(0, chapterText.length), endChar.coerceIn(startChar, chapterText.length))
        val pageText =
            remember(pageSlice, startChar, endChar, activeSpan, highlightColor, pressedPassage, pressedColor) {
                buildAnnotatedString {
                    append(pageSlice)
                    if (activeSpan != null) {
                        val from = maxOf(activeSpan.first, startChar)
                        val to = minOf(activeSpan.second, endChar)
                        if (from < to) {
                            addStyle(
                                SpanStyle(background = highlightColor, fontWeight = FontWeight.Bold),
                                from - startChar,
                                to - startChar,
                            )
                        }
                    }
                    // Pressed-passage feedback (decisions #94): surfaceVariant
                    // fill, no bold — distinct from the read-along highlight.
                    val pressed = pressedPassage
                    if (pressed != null) {
                        val passageStart = passageOffsets.getOrNull(pressed) ?: 0
                        val passageEnd = passageOffsets.getOrNull(pressed + 1) ?: chapterText.length
                        val from = maxOf(passageStart, startChar)
                        val to = minOf(passageEnd, endChar)
                        if (from < to) {
                            addStyle(SpanStyle(background = pressedColor), from - startChar, to - startChar)
                        }
                    }
                }
            }

        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .clipToBounds()
                    .pointerInput(state.bookId, totalPages, state.chapterPassages) {
                        // Passage under a y coordinate — the middle-third tap
                        // mapping, shared by the press highlight and the tap (S3).
                        val pageWidthPx = size.width / 3f
                        val swipePx = SWIPE_PAGE_THRESHOLD.toPx()

                        fun passageAt(y: Float): Int? {
                            val titleBlock = if (page <= 0) titleHeightPx + titleGapPx else 0
                            val lineInPage = ((y - titleBlock).toInt() / lineHeightPx).coerceAtLeast(0)
                            val globalLine = range.first + lineInPage
                            return passageStartLines
                                .indexOfLast { it <= globalLine }
                                .takeIf { it >= 0 && state.positioned }
                        }
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            // Press feedback only for middle-zone touches — the
                            // zone whose up-action plays a passage.
                            if (down.position.x >= pageWidthPx && down.position.x <= pageWidthPx * 2f) {
                                pressedPassage = passageAt(down.position.y)
                            }
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
                            pressedPassage = null
                            if (paged) {
                                val delta = if (dragX < 0f) 1 else -1
                                when {
                                    page + delta < 0 -> viewModel.openChapter(bookId, -1)
                                    page + delta > totalPages - 1 -> viewModel.openChapter(bookId, +1)
                                    else -> page = page + delta
                                }
                            } else {
                                val x = down.position.x
                                when {
                                    x < pageWidthPx -> if (page <= 0) viewModel.openChapter(bookId, -1) else page = page - 1
                                    x > pageWidthPx * 2f ->
                                        if (page >= totalPages - 1) viewModel.openChapter(bookId, +1) else page = page + 1
                                    else -> {
                                        // Middle tap: play the passage under the finger.
                                        val passage = passageAt(down.position.y)
                                        if (passage != null) {
                                            viewModel.playPosition(bookId, state.chapterIndex, passage)
                                        } else if (state.positioned) {
                                            viewModel.playPosition(bookId, state.chapterIndex, state.passageIndex)
                                        }
                                    }
                                }
                            }
                        }
                    },
        ) {
            if (page <= 0 && chapterTitle.isNotBlank()) {
                Text(
                    chapterTitle,
                    style = titleStyle,
                    modifier =
                        Modifier.padding(
                            bottom = AyvuSpacing.MD,
                            start = AyvuSpacing.LG,
                            end = AyvuSpacing.LG,
                        ),
                )
            }
            if (pageSlice.isNotBlank()) {
                Text(
                    text = pageText,
                    style = bodyStyle,
                    modifier = Modifier.padding(horizontal = AyvuSpacing.LG),
                )
            }
            if (totalPages > 1) {
                Text(
                    "Page ${page + 1} of $totalPages",
                    style = indicatorStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = AyvuSpacing.XS),
                )
            }
        }
    }
}

/** Char offset of each passage's start in the joined chapter text. */
private fun computePassageOffsets(passages: List<String>): IntArray {
    val offsets = IntArray(passages.size)
    var acc = 0
    for ((index, passage) in passages.withIndex()) {
        offsets[index] = acc
        acc += passage.length + 2 // "\n\n"
    }
    return offsets
}

private data class SentenceSpan(
    val offset: Int,
    val length: Int,
)

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

/** Global char range [start, endExclusive) of the active sentence in the joined chapter text. */
private fun activeSentenceRange(
    passageOffsets: IntArray,
    passageIndex: Int,
    passageText: String,
    activeSentenceIndex: Int,
): Pair<Int, Int>? {
    val passageOffset = passageOffsets.getOrNull(passageIndex) ?: return null
    val sentence = sentenceSpans(passageText).getOrNull(activeSentenceIndex) ?: return null
    val start = passageOffset + sentence.offset
    return start to (start + sentence.length)
}

/** Horizontal drag distance that turns a page. */
private val SWIPE_PAGE_THRESHOLD = 64.dp
