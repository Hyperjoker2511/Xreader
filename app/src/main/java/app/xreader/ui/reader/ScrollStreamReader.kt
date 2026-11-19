package app.xreader.ui.reader

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.xreader.data.db.Highlight
import app.xreader.domain.content.ChapterBlock
import app.xreader.domain.reader.AutoScrollController
import app.xreader.domain.reader.ChapterText
import app.xreader.domain.reader.ChapterTransition
import app.xreader.domain.reader.ReaderConfig
import app.xreader.ui.theme.ReaderPalette
import java.io.File
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged

/** Actions offered on a long-pressed paragraph (passage tools, §2.4 #7).
 * v1.7 one-engine (C1): relocated here from the retired
 * ScrollReader.kt — the unified scroll core is the enum's only
 * definition site now. */
enum class ParagraphAction { HIGHLIGHT, NOTE, BOOKMARK }

/**
 * The unified scroll core (v1.0.2's endless stream, generalised
 * by the v1.7 one-engine build): ONE LazyColumn over the window
 * of chapters the ViewModel keeps resident, under BOTH chapter
 * transitions — the window policy is the only difference:
 *
 * - CONTINUOUS: the shipped sliding window. Chapters flow into
 *   each other with a quiet header; the window slides at the
 *   edges; no chapter-end wall, no swap, no position reset.
 * - CHAPTER_BY_CHAPTER: the window is pinned to the current
 *   chapter (headers suppressed — the chrome names the
 *   chapter) and never slides. As the current chapter's last
 *   block becomes visible, the next chapter is requested
 *   (Item 3's QuickNovel pattern): a small LOADING item sits
 *   at the boundary while it loads, the window extends by the
 *   landed chapter and scrolling continues into it; a failed
 *   load stands an in-flow FAILED row with a retry door.
 *
 * Position is reported as (chapterIndex, charOffsetInChapter,
 * chapterFraction) — the exact locator the rest of the reader
 * already speaks. Window slides are keyed by chapter+block, so
 * appending forward (the common case) never disturbs what's on
 * screen.
 */
@OptIn(ExperimentalFoundationApi::class, FlowPreview::class)
@Composable
fun ScrollStreamReader(
    entries: List<StreamEntry>,
    totalChapters: Int,
    config: ReaderConfig,
    palette: ReaderPalette,
    fontFamily: FontFamily,
    highlights: List<Highlight>,
    /** v1.7 one-engine (Item 1 parity): the read-aloud spoken
     * range + the media-overlay block, followed in both
     * policies — the retired single-chapter surface's follow
     * effects, carried over. */
    spokenRange: IntRange?,
    overlayBlockId: String?,
    restoreChapterIndex: Int,
    restoreOffset: Int,
    restoreGeneration: Int,
    externalTurns: SharedFlow<Int>,
    assetFile: (String) -> File,
    onStreamPosition: (chapterIndex: Int, charOffset: Int, chapterFraction: Float) -> Unit,
    /** Item 1 parity + A5: the paragraph long-press menu's
     * actions, CHAPTER-QUALIFIED — the callback carries the
     * paragraph's own chapter index; the ViewModel resolves
     * ranges against that entry's text and writes the mark
     * against that chapter, never the current one. */
    onParagraphAction: (chapterIndex: Int, blockIndex: Int, action: ParagraphAction) -> Unit,
    /** Patch 11: the selection menu's screen-level actions. */
    onSelectionAction: (ReaderSelectionAction, String) -> Unit = { _, _ -> },
    onCenterTap: () -> Unit,
    onChromeWanted: (Boolean) -> Unit,
    onDownloadChapter: (chapterIndex: Int) -> Unit,
    /** Item 3: the current chapter's last block became visible
     * (pinned policy) — the boundary request trigger. */
    onBoundaryEndVisible: () -> Unit = {},
    /** Item 3: the first item beyond the current chapter
     * became the last visible item (pinned policy) — the
     * chapter counts as finished. */
    onChapterEndReached: () -> Unit = {},
    /** Item 3: the boundary FAILED row's retry tap. */
    onRetryChapter: (chapterIndex: Int) -> Unit = {},
    /** Item 3: the boundary FAILED row's reason text. */
    failedReason: String? = null,
    modifier: Modifier = Modifier,
    /** v1.0.6.2 (F7): measured chrome heights. */
    topInset: androidx.compose.ui.unit.Dp = 0.dp,
    bottomInset: androidx.compose.ui.unit.Dp = 0.dp
) {
    val pinned = config.chapterTransition == ChapterTransition.CHAPTER_BY_CHAPTER
    val listState = rememberLazyListState()
    var restoredGeneration by remember { mutableStateOf(-1) }
    // v1.7 one-engine (R2): the generation the user has MOVED
    // in — once set, no restore assertion may fire for that
    // generation again (the restore effect below checks it).
    var userMovedGeneration by remember { mutableStateOf(-1) }
    // v1.2.0 (WS2): scroll lock for image zoom — the flat-item
    // keys whose ZoomableImage reports a pinch/pan in progress or
    // a zoomed-in state. While any is active the column must not
    // scroll: drags belong to the image. Zoom state itself stays
    // per-item inside ZoomableImage; this set is only the lock.
    var zoomLockedItems by remember { mutableStateOf<Set<String>>(emptySet()) }
    // Item 1 parity: auto-scroll's pause state (the retired
    // surface's tap contract: any non-centre tap pauses the
    // drift, a centre tap resumes it). Reset per anchor
    // chapter, as the retired surface reset it per chapter.
    var autoPaused by remember(restoreChapterIndex) { mutableStateOf(false) }

    // Flatten the window into stable item keys once per window.
    val flat = remember(entries, totalChapters, pinned, restoreChapterIndex) {
        flattenEntries(entries, totalChapters, pinned, restoreChapterIndex)
    }

    // Restore: land on the anchor chapter's saved block.
    // v1.7 one-engine (R1 + R2): a generation is asserted
    // EXACTLY ONCE — the guard records consumption even when
    // the anchor lookup misses (the trace's S1 hole: keyed on
    // flat.size, an unguarded retry re-fired scrollToItem on
    // every window churn and yanked the reader back to the
    // anchor). A retry may be keyed only on the anchor entry
    // gaining text or entering the window — the entries list
    // itself — never on flat.size. And once the user has
    // moved in the generation, no assertion fires at all.
    LaunchedEffect(restoreGeneration, entries) {
        if (entries.isEmpty() || restoredGeneration == restoreGeneration) {
            return@LaunchedEffect
        }
        if (userMovedGeneration == restoreGeneration) {
            restoredGeneration = restoreGeneration
            return@LaunchedEffect
        }
        val anchor = entries.firstOrNull { it.chapterIndex == restoreChapterIndex }
            ?: entries.firstOrNull() ?: return@LaunchedEffect
        if (anchor.text == null) {
            // The anchor chapter hasn't landed yet: NOT
            // consumed — this effect re-runs when the entries
            // change (its key), which is the anchor gaining
            // text or entering the window, and only that.
            return@LaunchedEffect
        }
        val blockIndex = anchor.text.blockIndexAt(restoreOffset)
        val flatIndex = flat.indexOfFirst {
            it.chapterIndex == anchor.chapterIndex && it.blockIndex == blockIndex
        }
        if (flatIndex >= 0) {
            listState.scrollToItem(flatIndex)
        }
        // Consumed whether the lookup hit or missed: the
        // anchor may be asserted once per generation, never
        // repeatedly (R1).
        restoredGeneration = restoreGeneration
    }

    // R2's movement witness: a USER drag in the current
    // generation retires its restore for good. Keyed on the
    // interaction source, so programmatic scrolls (the
    // restore's own jump, volume turns, the Listen follow)
    // never masquerade as the user moving.
    LaunchedEffect(restoreGeneration) {
        listState.interactionSource.interactions.collectLatest { interaction ->
            if (interaction is androidx.compose.foundation.interaction
                    .DragInteraction.Start
            ) {
                userMovedGeneration = restoreGeneration
            }
        }
    }

    // Position reporting from the first visible item, plus
    // Item 3's last-visible end-detection (pinned policy).
    LaunchedEffect(entries, pinned) {
        snapshotFlow {
            val info = listState.layoutInfo
            val first = info.visibleItemsInfo.firstOrNull()
                ?: return@snapshotFlow null
            val last = info.visibleItemsInfo.lastOrNull()
                ?: return@snapshotFlow null
            val firstItem = flat.getOrNull(first.index) ?: return@snapshotFlow null
            // Item 3 (A3's care): a boundary item at the top —
            // the position is the CURRENT chapter at its END
            // (its length, fraction 1f), never the next
            // chapter's start and never 0: a boundary the user
            // merely reached must not save the next chapter as
            // started or corrupt the reopen.
            if (firstItem.kind == FlatKind.LOADING ||
                firstItem.kind == FlatKind.FAILED
            ) {
                val current = entries.firstOrNull {
                    it.chapterIndex == firstItem.chapterIndex - 1
                } ?: return@snapshotFlow null
                val currentText = current.text ?: return@snapshotFlow null
                return@snapshotFlow PositionReport(
                    chapterIndex = current.chapterIndex,
                    offset = currentText.length,
                    fraction = 1f,
                    lastVisibleIndex = last.index
                )
            }
            // v1.0.6.2 (F7): the named chapter is the one whose
            // TEXT is on screen — a known-empty entry (imported
            // front matter) never names the position by itself.
            val blockCounts = entries.associate {
                it.chapterIndex to (it.text?.content?.blocks?.size ?: -1)
            }
            val resolvedIndex =
                app.xreader.domain.reader.StreamReadingPosition.resolve(
                    firstItem.chapterIndex, blockCounts
                )
            val entry = entries.firstOrNull { it.chapterIndex == resolvedIndex }
                ?: return@snapshotFlow null
            val text = entry.text ?: return@snapshotFlow null
            if (firstItem.blockIndex < 0 || resolvedIndex != firstItem.chapterIndex) {
                // On a chapter header: position = chapter start.
                return@snapshotFlow PositionReport(
                    entry.chapterIndex, 0, 0f, last.index
                )
            }
            val range = text.rangeOf(firstItem.blockIndex)
                ?: return@snapshotFlow null
            val blockLength = maxOf(1, range.end - range.start)
            val within = if (first.size > 0) {
                (listState.firstVisibleItemScrollOffset.toFloat() / first.size)
                    .coerceIn(0f, 1f)
            } else {
                0f
            }
            val offset = range.start + (blockLength * within).toInt()
            PositionReport(
                chapterIndex = entry.chapterIndex,
                offset = offset,
                fraction = offset.toFloat() / maxOf(1, text.length),
                lastVisibleIndex = last.index
            )
        }
            .distinctUntilChanged()
            .debounce(180L)
            .collectLatest { report ->
                if (report == null) return@collectLatest
                onStreamPosition(report.chapterIndex, report.offset, report.fraction)
                // Item 3 end-detection — pinned policy only, and
                // NO fraction threshold anywhere: (a) the
                // current chapter's last BLOCK becoming visible
                // requests the next chapter; (b) the first item
                // BEYOND the current chapter becoming the last
                // visible item is the end report (the chapter
                // counts as finished; auto-scroll's crossing
                // cue is the same signal, consumed VM-side).
                if (pinned) {
                    val lastItem = flat.getOrNull(report.lastVisibleIndex)
                    if (lastItem != null) {
                        val currentEntry = entries.firstOrNull {
                            it.chapterIndex == report.chapterIndex
                        }
                        val lastBlockIndex =
                            (currentEntry?.text?.content?.blocks?.size ?: 0) - 1
                        if (lastItem.kind == FlatKind.BLOCK &&
                            lastItem.chapterIndex == report.chapterIndex &&
                            lastBlockIndex >= 0 &&
                            lastItem.blockIndex >= lastBlockIndex
                        ) {
                            onBoundaryEndVisible()
                        }
                        val firstBeyond = flat.indexOfFirst {
                            it.chapterIndex > report.chapterIndex
                        }
                        if (firstBeyond >= 0 &&
                            report.lastVisibleIndex >= firstBeyond
                        ) {
                            onChapterEndReached()
                        }
                    }
                }
            }
    }

    // Chrome follows scroll direction, like the single-chapter
    // reader: up = show, down a while = hide.
    LaunchedEffect(entries) {
        var lastIndex = -1
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collectLatest { index ->
                if (lastIndex >= 0) {
                    if (index < lastIndex) onChromeWanted(true)
                    else if (index > lastIndex + 6) onChromeWanted(false)
                }
                lastIndex = index
            }
    }

    // Volume-key turns scroll a viewport, as in scroll mode.
    LaunchedEffect(Unit) {
        externalTurns.collectLatest { direction ->
            val viewport = listState.layoutInfo.viewportSize.height
            if (viewport > 0) {
                listState.animateScrollBy(direction * viewport.toFloat() * 0.92f)
            }
        }
    }

    // Item 1 parity: auto-scroll drift (the retired surface's
    // v1.0.1 loop, both policies). Any non-centre tap pauses
    // it (the tap toggle below); a centre tap resumes. The
    // boundary crossing under drift is Item 3's end report —
    // the drift simply keeps scrolling into the landed
    // chapter, pausing in effect on the LOADING item (there
    // is nothing beyond it to scroll into until it lands).
    LaunchedEffect(config.autoScrollEnabled, config.autoScrollSpeed, autoPaused) {
        if (!config.autoScrollEnabled || autoPaused) return@LaunchedEffect
        val velocity = AutoScrollController.velocityPxPerSecond(config.autoScrollSpeed)
        while (true) {
            delay(100L)
            listState.animateScrollBy(velocity * 0.1f)
        }
    }

    // Item 1 parity: read-aloud follow — keep the spoken
    // sentence visible. The spoken range is in the CURRENT
    // chapter's coordinates (Listen reads the chapter the VM
    // publishes as on screen).
    LaunchedEffect(spokenRange) {
        if (spokenRange == null) return@LaunchedEffect
        val entry = entries.firstOrNull { it.chapterIndex == restoreChapterIndex }
            ?: return@LaunchedEffect
        val text = entry.text ?: return@LaunchedEffect
        val blockIndex = text.blockIndexAt(spokenRange.first)
        if (blockIndex < 0) return@LaunchedEffect
        val flatIndex = flat.indexOfFirst {
            it.chapterIndex == entry.chapterIndex && it.blockIndex == blockIndex
        }
        if (flatIndex >= 0) listState.animateScrollToItem(flatIndex)
    }

    // Item 1 parity: media-overlay follow — keep the overlay's
    // block visible, whichever window entry carries it.
    LaunchedEffect(overlayBlockId) {
        if (overlayBlockId == null) return@LaunchedEffect
        for (entry in entries) {
            val text = entry.text ?: continue
            val range = text.ranges.firstOrNull { it.blockId == overlayBlockId }
                ?: continue
            val flatIndex = flat.indexOfFirst {
                it.chapterIndex == entry.chapterIndex &&
                    it.blockIndex == range.blockIndex
            }
            if (flatIndex >= 0) listState.animateScrollToItem(flatIndex)
            return@LaunchedEffect
        }
    }

    Box(
        modifier
            .fillMaxSize()
            // v1.0.6.2 (F7): Initial-pass tap observation — the
            // centre tap toggles the chrome even when it lands on
            // text (the old Main-pass detector was starved by the
            // blocks' click handlers and the selection container).
            // Item 1 parity: the tap also drives auto-scroll's
            // pause/resume contract, as on the retired surface.
            .readerTapToggle(entries) { xFraction, _ ->
                if (app.xreader.domain.reader.CentreTap.isCentre(xFraction)) {
                    autoPaused = false
                    onCenterTap()
                } else {
                    autoPaused = true
                }
            }
    ) {
        ReaderSelectionMenuHost(onAction = onSelectionAction) {
        SelectionContainer {
            LazyColumn(
                state = listState,
                userScrollEnabled = zoomLockedItems.isEmpty(),
                contentPadding = PaddingValues(
                    start = config.marginHorizontalDp.dp,
                    end = config.marginHorizontalDp.dp,
                    top = config.marginVerticalDp.dp + topInset,
                    bottom = config.marginVerticalDp.dp + bottomInset
                ),
                modifier = Modifier.fillMaxSize()
            ) {
                flat.forEach { flatItem ->
                    when (flatItem.kind) {
                        FlatKind.HEADER -> item(key = flatItem.key) {
                            StreamChapterHeader(
                                // v1.1.2: the same reconciliation
                                // as the chrome header — a chapter
                                // name whose own number contradicts
                                // its position yields to the
                                // position line, so the eyebrow
                                // ("Chapter N") and the title under
                                // it can never disagree.
                                title = ReaderCounters.headerLine(
                                    chapterTitle = flatItem.title,
                                    chapterIndex = flatItem.chapterIndex,
                                    chapterCount = totalChapters,
                                    // v1.3.2 (item 79): identity
                                    // over position, as in the
                                    // paged reader's chrome.
                                    ownNumber =
                                        app.xreader.data.extension
                                            .ChapterNumbers
                                            .parseAny(flatItem.title, "")
                                ),
                                chapterNumber =
                                    app.xreader.data.extension.ChapterNumbers
                                        .parseAny(flatItem.title, "")
                                        ?.toInt() ?: (flatItem.chapterIndex + 1),
                                palette = palette
                            )
                        }
                        FlatKind.MISSING -> item(key = flatItem.key) {
                            // v1.0.6.2 (F3-stream): a chapter the
                            // stream is already pulling live says
                            // so — no button for a download that's
                            // already running.
                            val entry = entries.firstOrNull {
                                it.chapterIndex == flatItem.chapterIndex
                            }
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                if (entry?.fetching == true) {
                                    Text(
                                        "Fetching this chapter…",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = palette.muted,
                                        textAlign = TextAlign.Center
                                    )
                                } else {
                                    Text(
                                        "This chapter hasn't been downloaded yet.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = palette.muted,
                                        textAlign = TextAlign.Center
                                    )
                                    TextButton(
                                        onClick = { onDownloadChapter(flatItem.chapterIndex) }
                                    ) { Text("Download this chapter") }
                                }
                            }
                        }
                        FlatKind.LOADING -> item(key = flatItem.key) {
                            // Item 3 (Q2, the QuickNovel pattern):
                            // the next chapter is loading in —
                            // a small in-flow loading item at the
                            // boundary, never a dead end, never a
                            // full-screen interruption.
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 24.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(
                                    color = palette.accent,
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp
                                )
                                Text(
                                    "Loading the next chapter…",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = palette.muted,
                                    modifier = Modifier.padding(start = 12.dp)
                                )
                            }
                        }
                        FlatKind.FAILED -> item(key = flatItem.key) {
                            // Item 3: the boundary chapter's load
                            // failed — the reason stands in the
                            // flow with a retry door (QuickNovel's
                            // FailedSpanned shape). Never a dead
                            // screen, never a silent stop.
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    "The next chapter couldn't be fetched.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = palette.muted,
                                    textAlign = TextAlign.Center
                                )
                                if (!failedReason.isNullOrBlank()) {
                                    Text(
                                        failedReason,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = palette.muted,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.padding(top = 4.dp)
                                    )
                                }
                                TextButton(
                                    onClick = { onRetryChapter(flatItem.chapterIndex) }
                                ) { Text("Try again") }
                            }
                        }
                        FlatKind.BLOCK -> item(key = flatItem.key) {
                            val entry = entries.firstOrNull {
                                it.chapterIndex == flatItem.chapterIndex
                            }
                            val text = entry?.text
                            val block = text?.content?.blocks
                                ?.getOrNull(flatItem.blockIndex)
                            // v1.2.0 (Wave 3): the per-chapter
                            // highlight filter is remembered — a
                            // fresh list identity per recomposition
                            // would defeat StreamBlock's own
                            // per-block memoisation below.
                            val chapterHighlights = remember(
                                highlights, entry?.chapterId
                            ) {
                                highlights.filter {
                                    it.chapterId == entry?.chapterId
                                }
                            }
                            if (text != null && block != null) {
                                StreamBlock(
                                    block = block,
                                    blockIndex = flatItem.blockIndex,
                                    chapterText = text,
                                    chapterIndex = flatItem.chapterIndex,
                                    config = config,
                                    palette = palette,
                                    fontFamily = fontFamily,
                                    highlights = chapterHighlights,
                                    spokenRange = if (
                                        flatItem.chapterIndex == restoreChapterIndex
                                    ) {
                                        spokenRange
                                    } else {
                                        null
                                    },
                                    overlayActive = overlayBlockId != null &&
                                        text.rangeOf(flatItem.blockIndex)?.blockId ==
                                        overlayBlockId,
                                    assetFile = assetFile,
                                    onParagraphAction = onParagraphAction,
                                    onZoomActiveChanged = { active ->
                                        zoomLockedItems = if (active) {
                                            zoomLockedItems + flatItem.key
                                        } else {
                                            zoomLockedItems - flatItem.key
                                        }
                                    }
                                )
                            }
                        }
                        FlatKind.END_OF_BOOK -> item(key = flatItem.key) {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 40.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    "You've reached the end of the book.",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = palette.text
                                )
                                Text(
                                    "New chapters will appear here when " +
                                        "the source publishes them.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = palette.muted,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }
            }
        }
        } // ReaderSelectionMenuHost
        if (config.readingLine) {
            ReadingLineOverlay(palette = palette)
        }
    }
}

/** One settled position sample from the unified core's reporter:
 * the named chapter + char offset + fraction, and the last
 * visible flat index (Item 3's end-detection input). */
private data class PositionReport(
    val chapterIndex: Int,
    val offset: Int,
    val fraction: Float,
    val lastVisibleIndex: Int
)

private enum class FlatKind { HEADER, BLOCK, MISSING, LOADING, FAILED, END_OF_BOOK }

private data class FlatItem(
    val key: String,
    val kind: FlatKind,
    val chapterIndex: Int,
    val blockIndex: Int,
    val title: String = ""
)

private fun flattenEntries(
    entries: List<StreamEntry>,
    totalChapters: Int,
    pinned: Boolean,
    currentChapterIndex: Int
): List<FlatItem> {
    val out = mutableListOf<FlatItem>()
    for (entry in entries) {
        // C3: in the pinned policy the chapter header item is
        // REMOVED from the flat list (not merely hidden — a
        // hidden header would drift the flat indices and the
        // restore lookup apart, and the reporter's header
        // branch must never see a header that isn't there).
        // The chrome already names the chapter.
        if (!pinned) {
            out += FlatItem(
                key = "h-${entry.chapterIndex}",
                kind = FlatKind.HEADER,
                chapterIndex = entry.chapterIndex,
                blockIndex = -1,
                title = entry.title
            )
        }
        when {
            entry.failed -> out += FlatItem(
                key = "f-${entry.chapterIndex}",
                kind = FlatKind.FAILED,
                chapterIndex = entry.chapterIndex,
                blockIndex = -1
            )
            entry.missing || entry.text == null -> out += FlatItem(
                key = "m-${entry.chapterIndex}",
                kind = FlatKind.MISSING,
                chapterIndex = entry.chapterIndex,
                blockIndex = -1
            )
            else -> {
                val blocks = entry.text.content.blocks
                for (index in blocks.indices) {
                    out += FlatItem(
                        key = "b-${entry.chapterIndex}-$index",
                        kind = FlatKind.BLOCK,
                        chapterIndex = entry.chapterIndex,
                        blockIndex = index
                    )
                }
            }
        }
    }
    val lastEntry = entries.lastOrNull()
    if (pinned && lastEntry != null && !lastEntry.failed &&
        lastEntry.chapterIndex == currentChapterIndex &&
        lastEntry.chapterIndex < totalChapters - 1 &&
        entries.none { it.chapterIndex == lastEntry.chapterIndex + 1 }
    ) {
        // Item 3: the pinned window's boundary — the next
        // chapter is not an entry yet; while it is requested
        // (and until it lands as an entry), the LOADING item
        // closes the current chapter's list.
        out += FlatItem(
            key = "load-${lastEntry.chapterIndex + 1}",
            kind = FlatKind.LOADING,
            chapterIndex = lastEntry.chapterIndex + 1,
            blockIndex = -1
        )
    }
    if (lastEntry != null && !lastEntry.failed &&
        lastEntry.chapterIndex >= totalChapters - 1
    ) {
        out += FlatItem(
            key = "end-of-book",
            kind = FlatKind.END_OF_BOOK,
            chapterIndex = lastEntry.chapterIndex,
            blockIndex = -1
        )
    }
    return out
}

@Composable
private fun StreamChapterHeader(
    title: String,
    chapterNumber: Int,
    palette: ReaderPalette
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 28.dp, bottom = 10.dp)
    ) {
        Text(
            "Chapter $chapterNumber",
            style = MaterialTheme.typography.labelLarge,
            color = palette.accent
        )
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = palette.text
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(palette.muted.copy(alpha = 0.35f))
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StreamBlock(
    block: ChapterBlock,
    blockIndex: Int,
    chapterText: ChapterText,
    chapterIndex: Int,
    config: ReaderConfig,
    palette: ReaderPalette,
    fontFamily: FontFamily,
    highlights: List<Highlight>,
    spokenRange: IntRange?,
    overlayActive: Boolean,
    assetFile: (String) -> File,
    onParagraphAction: (chapterIndex: Int, blockIndex: Int, action: ParagraphAction) -> Unit,
    onZoomActiveChanged: (Boolean) -> Unit = {}
) {
    val range = chapterText.rangeOf(blockIndex) ?: return
    when (block) {
        // v1.2.0 (WS2): image blocks zoom via the shared
        // ZoomableImage — graphicsLayer-only, so stream position
        // reporting is unaffected.
        // v1.7 one-engine (Item 4, A6): this call site — the
        // unified scroll core's ONLY one — opts into the aspect
        // reserve, so an image item always holds a real box
        // ahead of its load and can never collapse to zero
        // height inside the column (the blank-illustration fix).
        is ChapterBlock.Image -> ZoomableImage(
            model = assetFile(block.fileName),
            contentDescription = block.alt,
            contentScale = ContentScale.FillWidth,
            doubleTapToZoom = config.doubleTapToZoom,
            disableZoomOut = config.disableZoomOut,
            onZoomActiveChanged = onZoomActiveChanged,
            reserveAspect = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        )
        is ChapterBlock.Rule -> Text(
            "• • •",
            color = palette.muted,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp)
        )
        else -> {
            // Item 1 parity: the paragraph long-press menu
            // (Highlight / Note / Bookmark), carried over from
            // the retired single-chapter surface — the action
            // reports THIS block's chapter (A5).
            var menuOpen by remember(blockIndex) { mutableStateOf(false) }
            // v1.2.0 (Wave 3, audit #2): remembered per block —
            // keyed by the block, its range, the highlights and
            // the rendering inputs (bionic, palette, and now
            // the spoken/overlay washes the unified core feeds
            // for real instead of hard-coding them away).
            val highlightColor = palette.accent.copy(alpha = 0.38f)
            val spokenColor = palette.accent.copy(alpha = 0.30f)
            val overlayColor = palette.accent.copy(alpha = 0.20f)
            val backgrounds = remember(
                block, range.start, range.end, highlights,
                spokenRange, overlayActive,
                highlightColor, spokenColor, overlayColor
            ) {
                blockBackgrounds(
                    blockStart = range.start,
                    blockEnd = range.end,
                    highlightRanges = highlights.map { it.startOffset to it.endOffset },
                    spokenRange = spokenRange,
                    overlayActive = overlayActive,
                    highlightColor = highlightColor,
                    spokenColor = spokenColor,
                    overlayColor = overlayColor
                )
            }
            val annotated = remember(block, config.bionicReading, backgrounds) {
                annotatedForBlock(
                    block = block,
                    bionic = config.bionicReading,
                    backgrounds = backgrounds
                )
            }
            Box {
                Text(
                    annotated,
                    style = blockTextStyle(block, config, fontFamily),
                    color = palette.text,
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = {},
                            onLongClick = { menuOpen = true }
                        )
                        .padding(
                            top = if (block is ChapterBlock.Heading) 16.dp else 0.dp,
                            bottom = config.paragraphSpacingDp.dp
                        )
                )
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Highlight passage") },
                        onClick = {
                            menuOpen = false
                            onParagraphAction(
                                chapterIndex, blockIndex, ParagraphAction.HIGHLIGHT
                            )
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Add note to passage") },
                        onClick = {
                            menuOpen = false
                            onParagraphAction(
                                chapterIndex, blockIndex, ParagraphAction.NOTE
                            )
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Bookmark here") },
                        onClick = {
                            menuOpen = false
                            onParagraphAction(
                                chapterIndex, blockIndex, ParagraphAction.BOOKMARK
                            )
                        }
                    )
                }
            }
        }
    }
}

/**
 * The reading line (sweep addendum): a soft guide at reading
 * height. Drawn as an overlay — it never touches layout, text
 * selection, or position reporting.
 */
@Composable
fun ReadingLineOverlay(palette: ReaderPalette, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(28.dp)
                .align(Alignment.Center)
                .background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        listOf(
                            androidx.compose.ui.graphics.Color.Transparent,
                            palette.accent.copy(alpha = 0.07f),
                            androidx.compose.ui.graphics.Color.Transparent
                        )
                    )
                )
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(2.dp)
                .align(Alignment.Center)
                .background(palette.accent.copy(alpha = 0.35f))
        )
    }
}
