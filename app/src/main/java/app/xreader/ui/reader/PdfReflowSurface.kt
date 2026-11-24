package app.xreader.ui.reader

import android.content.Intent
import androidx.compose.foundation.gestures.AwaitPointerEventScope
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import app.xreader.domain.content.ChapterBlock
import app.xreader.domain.content.ChapterContent
import app.xreader.domain.reader.ChapterText
import app.xreader.domain.reader.ReaderConfig
import app.xreader.ui.localreader.HIGHLIGHT_TINT_ARGB
import app.xreader.ui.localreader.PdfTextSelection
import app.xreader.ui.localreader.SelectionNoteEditor
import app.xreader.ui.localreader.StoredSelectionHighlight
import app.xreader.ui.localreader.expandWordRange
import app.xreader.ui.localreader.highlightSpansFor
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** F1 (post-re-check): the drain a post-long-press exit
 * owes its gesture. Once the entry recogniser's
 * long-press has fired, Rev 1 §11.1's consumption duty
 * runs to the gesture's end — every remaining change of
 * the tracked pointer is consumed until its up/cancel —
 * so the release can never reach the split onTap
 * detector unconsumed (foundation's
 * waitForUpOrCancellation has no long-press timeout, so
 * an unconsumed up would fire onToggleControls). The
 * success path already consumes to the up inline; the
 * early exits call this instead of returning bare. */
private suspend fun AwaitPointerEventScope
    .drainPdfEntryGesture(pointerId: PointerId) {
    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes
            .firstOrNull { it.id == pointerId } ?: continue
        change.consume()
        if (!change.pressed) break
    }
}

/**
 * v1.7 OE-1 (R8–R12, OQ3): Reflow in the one reader — ONE
 * page's text at a time, rendered by the core reader's own
 * text styling, deliberately NOT inside DocumentReader:
 * the chapter machinery (wall, prefetch, chapter state)
 * stays inert for a PDF, and a page boundary is crossed by
 * replacing this page's text with the adjacent page's —
 * Moon+'s shape, never a whole-book flow, never a chapter.
 *
 * Scrolling inside the page is ordinary text scrolling. A
 * continued pull (or a strong fling) past the top or
 * bottom edge crosses to the adjacent page and the new
 * page starts at its top — the page-granular place rule
 * (R14): nothing finer is carried.
 *
 * Selection is the local reader's own: long-press a word,
 * then the shared SelectionBar (SelectionBar §6.3 — the
 * same component the Pages bodies host, seated here at the
 * foot of the text column). Highlights made here are the
 * Reflow form (R20): page + offset within THIS page's
 * Reflow text — the same string Listen speaks in this
 * mode, so the spoken range and the search mark land on
 * the very offsets the reader sees.
 */
@Composable
fun PdfReflowSurface(
    content: ChapterContent,
    config: ReaderConfig,
    fontFamily: FontFamily,
    textColor: Color,
    page: Int,
    highlights: List<StoredSelectionHighlight>,
    searchMark: PdfSearchMark?,
    spokenRange: IntRange?,
    onBoundaryCross: (direction: Int) -> Unit,
    onToggleControls: () -> Unit,
    onAddHighlight: (StoredSelectionHighlight) -> Unit,
    modifier: Modifier = Modifier,
    // SelectionBar: the More doors (§6.4) and the back
    // chain's reports + clear nonce (§9.2), threaded from
    // PdfReaderHost.
    onSelectionTranslate: (String) -> Unit = {},
    onSelectionAskAi: (String) -> Unit = {},
    onSelectionActiveChanged: (Boolean) -> Unit = {},
    onDialogOpenChanged: (Boolean) -> Unit = {},
    clearSelectionSignal: Int = 0,
    motionEnabled: Boolean = true
) {
    val chapterText = remember(content) { ChapterText(content) }
    val fullText = chapterText.text
    val scrollState = rememberScrollState()
    // R14: a page change lands at the new page's top —
    // nothing finer than the page is ever carried.
    LaunchedEffect(page) { scrollState.scrollTo(0) }
    val density = LocalDensity.current
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    var selection by remember(page, content) {
        mutableStateOf<PdfTextSelection?>(null)
    }
    var noteDraft by remember(page, content) {
        mutableStateOf<PdfTextSelection?>(null)
    }
    var dictionary by remember(page, content) {
        mutableStateOf<Pair<String, String?>?>(null)
    }
    // SelectionBar (§6.3): the bar's hoisted state + its
    // §9.3 dismissing flag, in this surface's own seat.
    var barState by remember(page, content) {
        mutableStateOf(SelectionBarState())
    }
    var barDismissing by remember(page, content) {
        mutableStateOf(false)
    }
    // §9.3 (as revised): an action's effect fires at tap;
    // the selection state clears 150ms later, as the bar's
    // fade completes. A new entry cancels the fade (its
    // write sets barDismissing = false first).
    LaunchedEffect(barDismissing) {
        if (barDismissing) {
            delay(150)
            selection = null
            barDismissing = false
        }
    }
    // §6.1's reset rule: a cleared selection resets the bar.
    LaunchedEffect(selection) {
        if (selection == null) barState = SelectionBarState()
    }
    // §9.2 (Critic F1): the armed/dialog conditions,
    // computed in place — this surface's dialog state is
    // local (noteDraft / dictionary).
    val reflowDialogOpen = noteDraft != null || dictionary != null
    val reflowArmed = selection != null && !reflowDialogOpen
    LaunchedEffect(reflowArmed, reflowDialogOpen) {
        onSelectionActiveChanged(reflowArmed)
        onDialogOpenChanged(reflowDialogOpen)
    }
    // §9.2: the back chain's clear nonce — one press kills
    // the selection, bar and all.
    LaunchedEffect(clearSelectionSignal) {
        if (clearSelectionSignal > 0) {
            selection = null
            barDismissing = false
        }
    }
    val layouts = remember(page, content) {
        mutableMapOf<Int, TextLayoutResult>()
    }
    // Code 117 (§11.6): each block's origin + height in the
    // column's content space, recorded beside [layouts] —
    // the drag loop converts a finger position in the DOWN
    // block's local space into any block's local space
    // through these.
    val blockOrigins = remember(page, content) {
        mutableMapOf<Int, Offset>()
    }
    val blockHeights = remember(page, content) {
        mutableMapOf<Int, Int>()
    }

    // The boundary crossing: overscroll past an edge
    // accumulates; a deliberate pull (or a strong fling
    // at the edge) crosses. Any real scroll consumption
    // resets the pull — mid-page reading never crosses.
    val edgeThresholdPx = with(density) { 72.dp.toPx() }
    val boundary = remember(page) {
        object : NestedScrollConnection {
            var pull = 0f
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                if (available.y == 0f) {
                    pull = 0f
                } else {
                    pull += available.y
                    if (pull <= -edgeThresholdPx) {
                        pull = 0f
                        onBoundaryCross(1)
                    } else if (pull >= edgeThresholdPx) {
                        pull = 0f
                        onBoundaryCross(-1)
                    }
                }
                return Offset.Zero
            }

            override suspend fun onPostFling(
                consumed: Velocity,
                available: Velocity
            ): Velocity {
                if (available.y < -1600f) {
                    onBoundaryCross(1)
                } else if (available.y > 1600f) {
                    onBoundaryCross(-1)
                }
                return Velocity.Zero
            }
        }
    }

    val pageHighlights = remember(fullText, page, highlights) {
        highlightSpansFor(fullText, page, highlights)
    }
    val mark = searchMark?.takeIf { it.page == page }

    Column(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .nestedScroll(boundary)
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            content.blocks.forEachIndexed { index, block ->
                val range = chapterText.rangeOf(index)
                val blockText = when (block) {
                    is ChapterBlock.Heading -> block.text
                    is ChapterBlock.Paragraph -> block.text
                    else -> null
                }
                if (blockText != null && range != null) {
                    Text(
                        text = pdfReflowAnnotated(
                            blockText = blockText,
                            blockStart = range.start,
                            highlights = pageHighlights,
                            selection = selection
                                ?.takeIf { it.page == page },
                            mark = mark,
                            spokenRange = spokenRange
                        ),
                        style = blockTextStyle(block, config, fontFamily),
                        color = textColor,
                        onTextLayout = { layouts[index] = it },
                        modifier = Modifier
                            .onGloballyPositioned { coordinates ->
                                blockOrigins[index] =
                                    coordinates.positionInParent()
                                blockHeights[index] =
                                    coordinates.size.height
                            }
                            .pointerInput(page, content, index) {
                                detectTapGestures(
                                    onTap = { onToggleControls() }
                                )
                            }
                            .pointerInput(page, content, index) {
                                // Code 117 (§11.6): the
                                // press-drag entry recogniser
                                // (bounded Reflow scope). The
                                // entry is today's, unchanged —
                                // long-press → word through
                                // expandWordRange — then the
                                // pointer is KEPT and the same
                                // motion extends the selection
                                // across lines/blocks:
                                // character-level edges on
                                // extension (§11.2's rule,
                                // shared maths), word on
                                // entry. The column's scroll
                                // competes exactly as §11.5's
                                // list does:
                                // awaitLongPressOrCancellation
                                // returns null when the scroll
                                // claims the pointer first.
                                // There are no handles in
                                // Reflow (§11.6).
                                awaitEachGesture {
                                    val down = awaitFirstDown(
                                        requireUnconsumed = false
                                    )
                                    val longPress =
                                        awaitLongPressOrCancellation(
                                            down.id
                                        ) ?: return@awaitEachGesture
                                    val layout = layouts[index]
                                    if (layout == null) {
                                        longPress.consume()
                                        drainPdfEntryGesture(down.id)
                                        return@awaitEachGesture
                                    }
                                    val local = layout
                                        .getOffsetForPosition(
                                            longPress.position
                                        )
                                    val global = range.start + local
                                    val word = expandWordRange(
                                        fullText, global
                                    )
                                    // §9.3: a new entry cancels
                                    // a fade still running.
                                    barDismissing = false
                                    if (word.isEmpty()) {
                                        selection = null
                                        longPress.consume()
                                        drainPdfEntryGesture(down.id)
                                        return@awaitEachGesture
                                    }
                                    longPress.consume()
                                    selection = PdfTextSelection(
                                        page = page,
                                        range = word,
                                        word = fullText.substring(
                                            word.first, word.last + 1
                                        )
                                    )
                                    val downOrigin =
                                        blockOrigins[index]
                                            ?: Offset.Zero
                                    var anchor: Int? = null
                                    fun extend(position: Offset) {
                                        val columnPoint =
                                            position + downOrigin
                                        // The block whose
                                        // vertical span holds
                                        // the point — or the
                                        // nearest by y when
                                        // the finger sits in a
                                        // gap or past the
                                        // last block.
                                        var target = index
                                        var bestDy = Float.MAX_VALUE
                                        for ((i, origin)
                                            in blockOrigins) {
                                            val h = blockHeights[i]
                                                ?: continue
                                            val dy = when {
                                                columnPoint.y <
                                                    origin.y ->
                                                    origin.y -
                                                        columnPoint.y
                                                columnPoint.y >
                                                    origin.y + h ->
                                                    columnPoint.y -
                                                        (origin.y + h)
                                                else -> 0f
                                            }
                                            if (dy < bestDy) {
                                                bestDy = dy
                                                target = i
                                            }
                                        }
                                        val targetLayout =
                                            layouts[target] ?: return
                                        val targetRange =
                                            chapterText
                                                .rangeOf(target)
                                                ?: return
                                        val targetOrigin =
                                            blockOrigins[target]
                                                ?: Offset.Zero
                                        val fingerGlobal =
                                            targetRange.start +
                                                targetLayout
                                                    .getOffsetForPosition(
                                                        columnPoint -
                                                            targetOrigin
                                                    )
                                        val (newRange, newAnchor) =
                                            pdfEntryDragRange(
                                                word.first,
                                                word.last + 1,
                                                anchor,
                                                fingerGlobal
                                            )
                                        anchor = newAnchor
                                        if (selection?.range !=
                                            newRange
                                        ) {
                                            selection =
                                                PdfTextSelection(
                                                    page = page,
                                                    range = newRange,
                                                    word = fullText
                                                        .substring(
                                                            newRange.first,
                                                            newRange.last + 1
                                                        )
                                                )
                                        }
                                    }
                                    while (true) {
                                        val event =
                                            awaitPointerEvent()
                                        val change = event.changes
                                            .firstOrNull {
                                                it.id == down.id
                                            } ?: continue
                                        change.consume()
                                        if (!change.pressed) break
                                        extend(change.position)
                                    }
                                    // The selection stands at
                                    // the up — no rollback, and
                                    // this host keeps no drag
                                    // channel to settle.
                                }
                            }
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }

        val active = selection
        if (active != null) {
            val activeStart = active.range.first
                .coerceIn(0, fullText.length)
            val activeEnd = (active.range.last + 1)
                .coerceIn(activeStart, fullText.length)
            val selectedText = fullText.substring(
                activeStart, activeEnd
            )
            // §6.3: the shared SelectionBar in the SAME
            // in-flow seat the action row occupied (foot of
            // the text column), centred — same component,
            // same rows, same actions; the seat is this
            // host's choice, as with every host. (Reflow has
            // no per-block position tracking to derive a
            // floating seat from — §11 OQ-6.)
            val seatMotion = rememberSelectionBarSeatMotion(
                visible = true,
                dismissing = barDismissing,
                motionEnabled = motionEnabled
            )
            SelectionBar(
                state = barState,
                onStateChange = { barState = it },
                moreItems = PdfSelectionMoreItems,
                callbacks = SelectionBarCallbacks(
                    onAction = { action ->
                        if (!barDismissing) {
                            when (action) {
                                SelectionBarAction.COPY -> {
                                    clipboard.setText(
                                        AnnotatedString(selectedText)
                                    )
                                    barDismissing = true
                                }
                                SelectionBarAction.HIGHLIGHT -> {
                                    // Capability-off fallback
                                    // (the bar opens the colour
                                    // row instead on this host).
                                    onAddHighlight(
                                        StoredSelectionHighlight(
                                            id = UUID.randomUUID()
                                                .toString(),
                                            page = page,
                                            charStart = activeStart,
                                            charEnd = activeEnd,
                                            selectedText = selectedText,
                                            tintArgb =
                                                barState.activeTintArgb,
                                            createdAtEpochMillis =
                                                System
                                                    .currentTimeMillis()
                                        )
                                    )
                                    barDismissing = true
                                }
                                SelectionBarAction.NOTE -> {
                                    // The shipped draft flow:
                                    // the editor opens over
                                    // the live selection, which
                                    // stays until save/cancel.
                                    noteDraft = active
                                }
                                SelectionBarAction.DICTIONARY -> {
                                    val word = selectedText.trim()
                                        .split(Regex("\\s+"))
                                        .firstOrNull().orEmpty()
                                    if (word.isNotBlank()) {
                                        scope.launch {
                                            val definition =
                                                runCatching {
                                                    app.xreader.ui
                                                        .library
                                                        .DictionaryLookup
                                                        .lookup(
                                                            context.filesDir,
                                                            word
                                                        )
                                                }.getOrNull()
                                            dictionary =
                                                word to definition
                                        }
                                    }
                                    barDismissing = true
                                }
                                SelectionBarAction.TRANSLATE -> {
                                    onSelectionTranslate(selectedText)
                                    barDismissing = true
                                }
                                SelectionBarAction.ASK_AI -> {
                                    onSelectionAskAi(selectedText)
                                    barDismissing = true
                                }
                                SelectionBarAction.SHARE -> {
                                    val intent = Intent(
                                        Intent.ACTION_SEND
                                    ).apply {
                                        type = "text/plain"
                                        putExtra(
                                            Intent.EXTRA_TEXT,
                                            selectedText
                                        )
                                    }
                                    context.startActivity(
                                        Intent.createChooser(intent, null)
                                    )
                                    barDismissing = true
                                }
                                else -> Unit
                            }
                        }
                    },
                    onApplyHighlight = { tintArgb, _ ->
                        if (!barDismissing) {
                            onAddHighlight(
                                StoredSelectionHighlight(
                                    id = UUID.randomUUID().toString(),
                                    page = page,
                                    charStart = activeStart,
                                    charEnd = activeEnd,
                                    selectedText = selectedText,
                                    tintArgb = tintArgb,
                                    createdAtEpochMillis =
                                        System.currentTimeMillis()
                                )
                            )
                            barDismissing = true
                        }
                    },
                    onRemoveHighlight = {},
                    onDismiss = {
                        selection = null
                        barDismissing = false
                    }
                ),
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .graphicsLayer {
                        alpha = seatMotion.alpha
                        scaleX = seatMotion.scale
                        scaleY = seatMotion.scale
                    }
            )
        }
    }

    val draft = noteDraft
    if (draft != null) {
        val draftStart = draft.range.first
            .coerceIn(0, fullText.length)
        val draftEnd = (draft.range.last + 1)
            .coerceIn(draftStart, fullText.length)
        val selectedText = fullText.substring(draftStart, draftEnd)
        SelectionNoteEditor(
            selectedText = selectedText,
            initialNote = "",
            onSave = { note ->
                onAddHighlight(
                    StoredSelectionHighlight(
                        id = UUID.randomUUID().toString(),
                        page = page,
                        charStart = draftStart,
                        charEnd = draftEnd,
                        selectedText = selectedText,
                        note = note,
                        createdAtEpochMillis =
                            System.currentTimeMillis()
                    )
                )
                noteDraft = null
                selection = null
            },
            onCancel = { noteDraft = null }
        )
    }

    val lookup = dictionary
    if (lookup != null) {
        AlertDialog(
            onDismissRequest = { dictionary = null },
            title = { Text(lookup.first) },
            text = {
                Text(
                    lookup.second
                        ?: "No definition found on this device."
                )
            },
            confirmButton = {
                TextButton(onClick = { dictionary = null }) {
                    Text("Close")
                }
            }
        )
    }
}

/** One block's annotated text: this page's highlight spans,
 * the live selection, the search mark and the spoken range,
 * each clipped to the block's own slice of the page text. */
private fun pdfReflowAnnotated(
    blockText: String,
    blockStart: Int,
    highlights: List<IntRange>,
    selection: PdfTextSelection?,
    mark: PdfSearchMark?,
    spokenRange: IntRange?
): AnnotatedString {
    val blockEnd = blockStart + blockText.length
    fun local(range: IntRange): IntRange? {
        val start = maxOf(range.first, blockStart)
        val end = minOf(range.last + 1, blockEnd)
        return if (end > start) {
            IntRange(start - blockStart, end - 1 - blockStart)
        } else {
            null
        }
    }
    return buildAnnotatedString {
        append(blockText)
        highlights.forEach { range ->
            local(range)?.let { span ->
                addStyle(
                    SpanStyle(background = Color(HIGHLIGHT_TINT_ARGB)),
                    span.first, span.last + 1
                )
            }
        }
        spokenRange?.let { range ->
            local(range)?.let { span ->
                addStyle(
                    SpanStyle(background = Color(0x338B5CF6)),
                    span.first, span.last + 1
                )
            }
        }
        if (mark != null && mark.end > mark.start) {
            local(IntRange(mark.start, mark.end - 1))?.let { span ->
                addStyle(
                    SpanStyle(background = Color(0x99FFB020)),
                    span.first, span.last + 1
                )
            }
        }
        if (selection != null && !selection.range.isEmpty()) {
            local(selection.range)
                ?.let { span ->
                    addStyle(
                        SpanStyle(background = Color(0x558B5CF6)),
                        span.first, span.last + 1
                    )
                }
        }
    }
}
