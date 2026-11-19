package app.xreader.ui.reader

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.animateScrollBy
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
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.xreader.data.db.Chapter
import app.xreader.data.db.Highlight
import app.xreader.domain.content.ChapterBlock
import app.xreader.domain.reader.AutoScrollController
import app.xreader.domain.reader.ChapterLayoutCache
import app.xreader.domain.reader.ChapterLayoutService
import app.xreader.domain.reader.ChapterText
import app.xreader.domain.reader.ChapterTransition
import app.xreader.domain.reader.PageHint
import app.xreader.domain.reader.ReaderConfig
import app.xreader.domain.reader.SegmentIndex
import app.xreader.ui.theme.ReaderPalette
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext

/** Actions offered on a long-pressed paragraph (passage tools, §2.4 #7).
 * v1.7 one-engine (C1): relocated to the unified scroll core from the
 * retired ScrollReader.kt; the document surface is its only
 * definition site now. */
enum class ParagraphAction { HIGHLIGHT, NOTE, BOOKMARK }

/**
 * The document surface (v1.7 Moon+ document engine, Item 1):
 * ONE LazyColumn over the WHOLE BOOK — every chapter a
 * segment, composed per segment from the ViewModel's
 * published per-segment state:
 *
 * - a RESIDENT segment composes its blocks (stable keys
 *   `b-<chapterId>-<blockIndex>` — identity is the chapter
 *   ID, never a bare index, so a mid-session chapter-list
 *   change cannot rebind a key);
 * - any other segment composes exactly ONE state item
 *   (`s-<chapterId>`): LOADING (the small in-flow spinner
 *   row), MISSING (the honest not-downloaded row with its
 *   download door) or FAILED (the reason + a retry door) —
 *   Moon+'s whole-book placeholders, present from open;
 * - CONTINUOUS segments carry a chapter header item
 *   (`h-<chapterId>`); CHAPTER_BY_CHAPTER suppresses it —
 *   a per-segment item-count difference in the segment
 *   index, nothing more (Q4: the transitions are converged;
 *   headers, fill policy and end reporting are their only
 *   differences).
 *
 * There is no flat list: the [SegmentIndex] (prefix sums
 * over per-segment item counts, O(chapters)) resolves
 * restore targets and names the segment under the viewport.
 * Segments fill IN PLACE: a state item's composition
 * requests its fill (B1's trigger), the fill lands, and the
 * state item is replaced by the segment's blocks under the
 * same keys — the reader's place is held by the list's key
 * anchoring and is never re-asserted for a fill.
 *
 * Position is reported as (chapterIndex, charOffsetInChapter,
 * chapterFraction) — the exact locator the rest of the reader
 * already speaks.
 */
@OptIn(ExperimentalFoundationApi::class, FlowPreview::class)
@Composable
fun DocumentReader(
    chapters: List<Chapter>,
    segmentStates: Map<Long, SegmentState>,
    segmentTexts: Map<Long, ChapterText>,
    segmentFailedReasons: Map<Long, String>,
    config: ReaderConfig,
    palette: ReaderPalette,
    fontFamily: FontFamily,
    highlights: List<Highlight>,
    /** The read-aloud spoken range + the media-overlay block,
     * followed in both transitions — the retired surfaces'
     * follow effects, carried over. */
    spokenRange: IntRange?,
    overlayBlockId: String?,
    /** The anchor segment (the ViewModel's chapterIndex):
     * restores assert against it and the spoken range is in
     * its coordinates. */
    anchorChapterIndex: Int,
    restoreOffset: Int,
    restoreHint: PageHint,
    restoreGeneration: Int,
    externalTurns: SharedFlow<Int>,
    assetFile: (String) -> File,
    /** Item 2: the shared layout store + the scroll
     * fingerprint + geometry, hoisted to ReaderScreen — the
     * stage-2 restore refinement reads (and, on a miss,
     * fills) the anchor segment's record here. */
    layoutService: ChapterLayoutService,
    layoutFingerprint: List<Any?>,
    layoutColumnWidthPx: Float,
    layoutContentHeightPx: Float,
    /** Bumped by ReaderScreen's worker each time it lands an
     * ahead record — the refinement effect's re-trigger. */
    layoutVersion: Int,
    onDocumentPosition: (chapterIndex: Int, charOffset: Int, chapterFraction: Float) -> Unit,
    /** The paragraph long-press menu's actions,
     * CHAPTER-QUALIFIED — the callback carries the
     * paragraph's own chapter index; the ViewModel resolves
     * ranges against that segment's text and writes the mark
     * against that chapter, never the current one. */
    onParagraphAction: (chapterIndex: Int, blockIndex: Int, action: ParagraphAction) -> Unit,
    /** Patch 11: the selection menu's screen-level actions. */
    onSelectionAction: (ReaderSelectionAction, String) -> Unit = { _, _ -> },
    onCenterTap: () -> Unit,
    onChromeWanted: (Boolean) -> Unit,
    onDownloadChapter: (chapterId: Long) -> Unit,
    onRetryChapter: (chapterId: Long) -> Unit,
    /** B1: a state item entered the composed range. */
    onSegmentVisible: (chapterId: Long) -> Unit,
    /** B1: the first visible item belongs to a textless
     * segment no boundary posture covers. */
    onSegmentApproached: (chapterId: Long) -> Unit,
    /** Item 4: the current segment's last block became the
     * last visible item (CHAPTER_BY_CHAPTER) — the boundary
     * fill trigger. */
    onBoundaryEndVisible: () -> Unit = {},
    /** Item 4: the first item beyond the current segment
     * became the last visible item (CHAPTER_BY_CHAPTER) —
     * the chapter counts as finished. */
    onChapterEndReached: () -> Unit = {},
    modifier: Modifier = Modifier,
    /** Measured chrome heights. */
    topInset: androidx.compose.ui.unit.Dp = 0.dp,
    bottomInset: androidx.compose.ui.unit.Dp = 0.dp
) {
    val pinned = config.chapterTransition == ChapterTransition.CHAPTER_BY_CHAPTER
    val showHeaders = !pinned
    val density = LocalDensity.current

    /** A segment's state as THIS surface renders it: the
     * published map, with the row-derived fallback for a
     * segment the map hasn't covered yet (a stored copy
     * stands LOADING, no copy stands MISSING). A held text
     * is RESIDENT whatever the map says. */
    fun stateOf(segment: Int): SegmentState {
        val chapter = chapters.getOrNull(segment) ?: return SegmentState.MISSING
        if (segmentTexts[chapter.id] != null) return SegmentState.RESIDENT
        return segmentStates[chapter.id]
            ?: if (chapter.contentPath != null) {
                SegmentState.LOADING
            } else {
                SegmentState.MISSING
            }
    }

    fun textOf(segment: Int): ChapterText? =
        chapters.getOrNull(segment)?.let { segmentTexts[it.id] }

    // The segment index: prefix sums over per-segment item
    // counts — O(chapters), never O(total blocks), and no
    // flat list is ever materialised (Item 6).
    val segmentIndex = remember(chapters, segmentStates, segmentTexts, showHeaders) {
        SegmentIndex(
            chapters.indices.map { segment ->
                SegmentIndex.Shape(
                    hasHeader = showHeaders,
                    blockCount = if (stateOf(segment) == SegmentState.RESIDENT) {
                        textOf(segment)?.content?.blocks?.size
                            ?: SegmentIndex.STATE
                    } else {
                        SegmentIndex.STATE
                    }
                )
            }
        )
    }

    // Open at the anchor segment: its saved block when it is
    // already resident (a warm re-entry), else its state item
    // (the restore effect asserts the block landing when the
    // fill lands). Stage 2 refines to the line (below).
    val anchorText = textOf(anchorChapterIndex)
    val initialFlat = remember(segmentIndex, anchorChapterIndex) {
        val targetOffset = when (restoreHint) {
            PageHint.FIRST -> 0
            PageHint.LAST -> anchorText?.length ?: 0
            PageHint.OFFSET -> restoreOffset
        }
        val flat = if (anchorText != null) {
            segmentIndex.flatIndexOf(
                anchorChapterIndex,
                anchorText.blockIndexAt(targetOffset)
            )
        } else {
            segmentIndex.flatIndexOf(
                anchorChapterIndex, SegmentIndex.STATE_ITEM
            )
        }
        if (flat >= 0) flat else 0
    }
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = initialFlat
    )

    // Restore bookkeeping (Item 5): a generation is consumed
    // exactly once — stage 1 (block granularity) records its
    // consumption on hit OR miss; stage 2 (line granularity)
    // refines from the layout record when it exists. Neither
    // stage is keyed on any list size (there is no flat list)
    // and neither fires once the user has moved (R2).
    var consumedGeneration by remember { mutableIntStateOf(-1) }
    var stage1Generation by remember { mutableIntStateOf(-1) }
    var refinedGeneration by remember { mutableIntStateOf(-1) }
    var userMovedGeneration by remember { mutableIntStateOf(-1) }
    // v1.2.0 (WS2): scroll lock for image zoom — the item keys
    // whose ZoomableImage reports a pinch/pan in progress or a
    // zoomed-in state. While any is active the column must not
    // scroll: drags belong to the image.
    var zoomLockedItems by remember { mutableStateOf<Set<String>>(emptySet()) }
    // Auto-scroll's pause state (the tap contract: any
    // non-centre tap pauses the drift, a centre tap resumes
    // it). Reset per anchor chapter, as the retired surfaces
    // reset it per chapter.
    var autoPaused by remember(anchorChapterIndex) { mutableStateOf(false) }

    // Restore, stage 1 (block granularity): assert once per
    // generation, when the anchor segment has text. While it
    // has none, the generation is NOT consumed — the list is
    // merely parked at the anchor's state item (composing it
    // is what triggers its fill), and the only retry is the
    // anchor gaining text, which is this effect's key.
    LaunchedEffect(restoreGeneration, anchorText) {
        if (consumedGeneration == restoreGeneration) return@LaunchedEffect
        if (userMovedGeneration == restoreGeneration) {
            consumedGeneration = restoreGeneration
            return@LaunchedEffect
        }
        if (anchorText == null) {
            val flat = segmentIndex.flatIndexOf(
                anchorChapterIndex, SegmentIndex.STATE_ITEM
            )
            if (flat >= 0 &&
                listState.layoutInfo.visibleItemsInfo
                    .none { it.index == flat }
            ) {
                listState.scrollToItem(flat)
            }
            return@LaunchedEffect
        }
        val targetOffset = when (restoreHint) {
            PageHint.FIRST -> 0
            PageHint.LAST -> anchorText.length
            PageHint.OFFSET -> restoreOffset
        }
        val blockIndex = anchorText.blockIndexAt(targetOffset)
        val flat = segmentIndex.flatIndexOf(anchorChapterIndex, blockIndex)
        if (flat >= 0) {
            listState.scrollToItem(flat)
        }
        // Consumed whether the lookup hit or missed (R1).
        consumedGeneration = restoreGeneration
        stage1Generation = restoreGeneration
    }

    // Restore, stage 2 (line granularity): once stage 1 has
    // landed and the anchor segment's layout record exists
    // under the SCROLL fingerprint, refine within the block —
    // scroll so the exact saved line sits at the viewport
    // top. The record comes from the shared store (the
    // worker may have measured it ahead); on a miss THIS
    // surface measures the anchor segment with its own
    // visible-path measurer, off the composition path, and
    // puts the record. The arithmetic (A3): the block item's
    // top inset for this block (the stage-2 authority: a
    // heading's rendered top padding) plus the lines above
    // the saved offset at the record's average line height —
    // exact for uniform-metric text (the common case) and
    // within about a line under fallback-font expansion.
    val refinementTextMeasurer = rememberTextMeasurer()
    val refinementMeasurer = remember(
        refinementTextMeasurer, density, fontFamily,
        layoutColumnWidthPx, layoutContentHeightPx
    ) {
        ComposeChapterMeasurer(
            textMeasurer = refinementTextMeasurer,
            density = density,
            fontFamily = fontFamily,
            columnWidthPx = layoutColumnWidthPx,
            contentHeightPx = layoutContentHeightPx
        )
    }
    LaunchedEffect(restoreGeneration, anchorText, layoutVersion) {
        if (stage1Generation != restoreGeneration ||
            refinedGeneration == restoreGeneration
        ) {
            return@LaunchedEffect
        }
        if (userMovedGeneration == restoreGeneration) {
            refinedGeneration = restoreGeneration
            return@LaunchedEffect
        }
        val chapter = chapters.getOrNull(anchorChapterIndex)
            ?: return@LaunchedEffect
        val text = anchorText ?: return@LaunchedEffect
        val key = ChapterLayoutCache.Key(
            chapterId = chapter.id,
            contentHash = ChapterLayoutCache.contentHash(text),
            fingerprint = layoutFingerprint
        )
        var layout = layoutService.cache.get(key)
        if (layout == null) {
            layout = withContext(Dispatchers.Default) {
                runCatching {
                    layoutService.layout(
                        text, config, layoutContentHeightPx,
                        layoutFingerprint, refinementMeasurer
                    )
                }.getOrNull()
            }
            if (layout != null) layoutService.cache.put(key, layout)
        }
        if (layout == null ||
            userMovedGeneration == restoreGeneration
        ) {
            refinedGeneration = restoreGeneration
            return@LaunchedEffect
        }
        val targetOffset = when (restoreHint) {
            PageHint.FIRST -> 0
            PageHint.LAST -> text.length
            PageHint.OFFSET -> restoreOffset
        }
        val blockIndex = text.blockIndexAt(targetOffset)
        val measured = layout.measuredBlocks.getOrNull(blockIndex)
        if (measured == null || measured.lineStarts.isEmpty() ||
            measured.lineHeightPx <= 0f
        ) {
            refinedGeneration = restoreGeneration
            return@LaunchedEffect
        }
        val lineIndex = measured.lineStarts
            .indexOfLast { it <= targetOffset }
            .coerceAtLeast(0)
        if (lineIndex == 0) {
            // Already at the block's first line — stage 1's
            // landing IS the line landing.
            refinedGeneration = restoreGeneration
            return@LaunchedEffect
        }
        val block = text.content.blocks.getOrNull(blockIndex)
        val topInsetPx = if (block is ChapterBlock.Heading) {
            with(density) { 16.dp.toPx() }
        } else {
            0f
        }
        val flat = segmentIndex.flatIndexOf(anchorChapterIndex, blockIndex)
        if (flat >= 0) {
            listState.scrollToItem(
                flat,
                (-(topInsetPx + lineIndex * measured.lineHeightPx)).toInt()
            )
        }
        refinedGeneration = restoreGeneration
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
    // Item 4's last-visible end-detection (CHAPTER_BY_CHAPTER).
    LaunchedEffect(segmentIndex, pinned) {
        snapshotFlow {
            val info = listState.layoutInfo
            val first = info.visibleItemsInfo.firstOrNull()
                ?: return@snapshotFlow null
            val last = info.visibleItemsInfo.lastOrNull()
                ?: return@snapshotFlow null
            // The end-of-book item tops the viewport: the
            // position is the last segment at its END when it
            // is resident (never its start — the windowed
            // reporter's header-branch wart is not carried).
            if (first.index >= segmentIndex.itemCount) {
                val lastSegment = segmentIndex.segmentCount - 1
                if (lastSegment < 0) return@snapshotFlow null
                val text = textOf(lastSegment) ?: return@snapshotFlow null
                return@snapshotFlow DocumentReport.Position(
                    lastSegment, text.length, 1f, last.index
                )
            }
            val loc = segmentIndex.locate(first.index)
                ?: return@snapshotFlow null
            when (loc.block) {
                SegmentIndex.STATE_ITEM -> {
                    // B1's acceptance contract: a textless
                    // first-visible segment is NEVER named as
                    // a position. Boundary posture (A3): the
                    // state item is LOADING or FAILED and the
                    // previous segment is resident → the
                    // previous segment at its end. Otherwise
                    // the approach door owns the report.
                    val segState = stateOf(loc.segment)
                    val prevText = if (loc.segment > 0) {
                        textOf(loc.segment - 1)
                    } else {
                        null
                    }
                    if ((segState == SegmentState.LOADING ||
                            segState == SegmentState.FAILED) &&
                        prevText != null
                    ) {
                        DocumentReport.Position(
                            loc.segment - 1, prevText.length, 1f,
                            last.index
                        )
                    } else {
                        chapters.getOrNull(loc.segment)?.let {
                            DocumentReport.Approach(it.id)
                        }
                    }
                }
                SegmentIndex.HEADER -> DocumentReport.Position(
                    loc.segment, 0, 0f, last.index
                )
                else -> {
                    // F7: the named segment is the one whose
                    // TEXT is on screen — a known-empty segment
                    // (imported front matter) never names the
                    // position by itself. resolve() and its
                    // signature are the shipped, test-pinned
                    // ones; the counts are the segments' block
                    // counts (not-yet-parsed counts as live).
                    val blockCounts = chapters.indices.associate {
                        it to (textOf(it)?.content?.blocks?.size ?: -1)
                    }
                    val resolved = app.xreader.domain.reader
                        .StreamReadingPosition.resolve(loc.segment, blockCounts)
                    if (resolved != loc.segment) {
                        DocumentReport.Position(resolved, 0, 0f, last.index)
                    } else {
                        val text = textOf(loc.segment)
                            ?: return@snapshotFlow null
                        val range = text.rangeOf(loc.block)
                            ?: return@snapshotFlow null
                        val blockLength = maxOf(1, range.end - range.start)
                        val within = if (first.size > 0) {
                            (listState.firstVisibleItemScrollOffset
                                .toFloat() / first.size)
                                .coerceIn(0f, 1f)
                        } else {
                            0f
                        }
                        val offset = range.start +
                            (blockLength * within).toInt()
                        DocumentReport.Position(
                            chapterIndex = loc.segment,
                            offset = offset,
                            fraction = offset.toFloat() /
                                maxOf(1, text.length),
                            lastVisibleIndex = last.index
                        )
                    }
                }
            }
        }
            .distinctUntilChanged()
            .debounce(180L)
            .collectLatest { report ->
                when (report) {
                    is DocumentReport.Position -> {
                        onDocumentPosition(
                            report.chapterIndex, report.offset,
                            report.fraction
                        )
                        // Item 4 end-detection —
                        // CHAPTER_BY_CHAPTER only, and NO
                        // fraction threshold anywhere: (a) the
                        // current segment's last BLOCK becoming
                        // the last visible item requests the
                        // next segment's fill; (b) the first
                        // item BEYOND the current segment
                        // becoming the last visible item is the
                        // end report (the chapter counts as
                        // finished).
                        if (pinned) {
                            val lastLoc = if (
                                report.lastVisibleIndex <
                                segmentIndex.itemCount
                            ) {
                                segmentIndex.locate(report.lastVisibleIndex)
                            } else {
                                null
                            }
                            val currentText = textOf(report.chapterIndex)
                            val lastBlockIndex =
                                (currentText?.content?.blocks?.size ?: 0) - 1
                            if (lastLoc != null &&
                                lastLoc.segment == report.chapterIndex &&
                                lastLoc.block >= 0 &&
                                lastBlockIndex >= 0 &&
                                lastLoc.block >= lastBlockIndex
                            ) {
                                onBoundaryEndVisible()
                            }
                            val nextStart = segmentIndex
                                .segmentStart(report.chapterIndex + 1)
                            if (nextStart >= 0 &&
                                report.lastVisibleIndex >= nextStart
                            ) {
                                onChapterEndReached()
                            }
                        }
                    }
                    is DocumentReport.Approach ->
                        onSegmentApproached(report.chapterId)
                    null -> {}
                }
            }
    }

    // Chrome follows scroll direction, like the paged reader:
    // up = show, down a while = hide.
    LaunchedEffect(Unit) {
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

    // Auto-scroll drift (both transitions). A9's stop rule:
    // a tick scrolls NOTHING while a state item stands at
    // either edge of the viewport — the drift never pushes
    // past an unlanded segment and never spins against one;
    // it resumes by itself when the fill lands and the edge
    // is text again.
    LaunchedEffect(
        config.autoScrollEnabled, config.autoScrollSpeed, autoPaused,
        segmentIndex
    ) {
        if (!config.autoScrollEnabled || autoPaused) return@LaunchedEffect
        val velocity = AutoScrollController.velocityPxPerSecond(config.autoScrollSpeed)
        while (true) {
            delay(100L)
            val info = listState.layoutInfo
            val halted = listOfNotNull(
                info.visibleItemsInfo.firstOrNull(),
                info.visibleItemsInfo.lastOrNull()
            ).any { item ->
                item.index < segmentIndex.itemCount &&
                    segmentIndex.locate(item.index)?.block ==
                    SegmentIndex.STATE_ITEM
            }
            if (!halted) {
                listState.animateScrollBy(velocity * 0.1f)
            }
        }
    }

    // Read-aloud follow — keep the spoken sentence visible.
    // The spoken range is in the ANCHOR segment's coordinates
    // (Listen reads the chapter the VM publishes as on
    // screen); the follow is gated to that segment.
    LaunchedEffect(spokenRange, segmentIndex) {
        if (spokenRange == null) return@LaunchedEffect
        val text = textOf(anchorChapterIndex) ?: return@LaunchedEffect
        val blockIndex = text.blockIndexAt(spokenRange.first)
        if (blockIndex < 0) return@LaunchedEffect
        val flat = segmentIndex.flatIndexOf(anchorChapterIndex, blockIndex)
        if (flat >= 0) listState.animateScrollToItem(flat)
    }

    // Media-overlay follow — keep the overlay's block visible,
    // whichever RESIDENT segment carries it.
    LaunchedEffect(overlayBlockId, segmentIndex) {
        if (overlayBlockId == null) return@LaunchedEffect
        for (segment in chapters.indices) {
            val text = textOf(segment) ?: continue
            val range = text.ranges.firstOrNull { it.blockId == overlayBlockId }
                ?: continue
            val flat = segmentIndex.flatIndexOf(segment, range.blockIndex)
            if (flat >= 0) listState.animateScrollToItem(flat)
            return@LaunchedEffect
        }
    }

    Box(
        modifier
            .fillMaxSize()
            // Initial-pass tap observation — the centre tap
            // toggles the chrome even when it lands on text.
            // The tap also drives auto-scroll's pause/resume
            // contract, as on the retired surfaces.
            .readerTapToggle(chapters) { xFraction, _ ->
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
                chapters.forEachIndexed { segment, chapter ->
                    if (showHeaders) {
                        item(key = "h-${chapter.id}") {
                            DocumentChapterHeader(
                                // The same reconciliation as
                                // the chrome header — a chapter
                                // name whose own number
                                // contradicts its position yields
                                // to the position line, so the
                                // eyebrow and the title under it
                                // can never disagree.
                                title = ReaderCounters.headerLine(
                                    chapterTitle = chapter.title,
                                    chapterIndex = segment,
                                    chapterCount = chapters.size,
                                    ownNumber =
                                        app.xreader.data.extension
                                            .ChapterNumbers
                                            .parseAny(chapter.title, "")
                                ),
                                chapterNumber =
                                    app.xreader.data.extension.ChapterNumbers
                                        .parseAny(chapter.title, "")
                                        ?.toInt() ?: (segment + 1),
                                palette = palette
                            )
                        }
                    }
                    val text = segmentTexts[chapter.id]
                    if (stateOf(segment) == SegmentState.RESIDENT &&
                        text != null
                    ) {
                        items(
                            count = text.content.blocks.size,
                            key = { blockIndex -> "b-${chapter.id}-$blockIndex" }
                        ) { blockIndex ->
                            // The per-chapter highlight filter
                            // is remembered — a fresh list
                            // identity per recomposition would
                            // defeat DocumentBlock's own
                            // per-block memoisation.
                            val chapterHighlights = remember(
                                highlights, chapter.id
                            ) {
                                highlights.filter {
                                    it.chapterId == chapter.id
                                }
                            }
                            val block = text.content.blocks
                                .getOrNull(blockIndex)
                            if (block != null) {
                                DocumentBlock(
                                    block = block,
                                    blockIndex = blockIndex,
                                    chapterText = text,
                                    chapterIndex = segment,
                                    config = config,
                                    palette = palette,
                                    fontFamily = fontFamily,
                                    highlights = chapterHighlights,
                                    spokenRange = if (
                                        segment == anchorChapterIndex
                                    ) {
                                        spokenRange
                                    } else {
                                        null
                                    },
                                    overlayActive = overlayBlockId != null &&
                                        text.rangeOf(blockIndex)?.blockId ==
                                        overlayBlockId,
                                    assetFile = assetFile,
                                    onParagraphAction = onParagraphAction,
                                    onZoomActiveChanged = { active ->
                                        val itemKey =
                                            "b-${chapter.id}-$blockIndex"
                                        zoomLockedItems = if (active) {
                                            zoomLockedItems + itemKey
                                        } else {
                                            zoomLockedItems - itemKey
                                        }
                                    }
                                )
                            }
                        }
                    } else {
                        item(key = "s-${chapter.id}") {
                            SegmentStateItem(
                                state = stateOf(segment),
                                isBoundary = pinned &&
                                    segment == anchorChapterIndex + 1,
                                hasStoredCopy = chapter.contentPath != null,
                                reason = segmentFailedReasons[chapter.id],
                                chapterId = chapter.id,
                                palette = palette,
                                onVisible = onSegmentVisible,
                                onDownload = onDownloadChapter,
                                onRetry = onRetryChapter
                            )
                        }
                    }
                }
                // Q2: the end-of-book item stands from open —
                // shown once the LAST segment is resident (its
                // text is the book's end; before that the last
                // segment's own state item stands there).
                val lastSegment = chapters.lastIndex
                if (lastSegment >= 0 &&
                    stateOf(lastSegment) == SegmentState.RESIDENT
                ) {
                    item(key = "end-of-book") {
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
        } // ReaderSelectionMenuHost
        if (config.readingLine) {
            ReadingLineOverlay(palette = palette)
        }
    }
}

/** One settled sample from the document surface's reporter:
 * either a position (the named segment + char offset +
 * fraction, and the last visible flat index — Item 4's
 * end-detection input) or an approach (the first visible
 * item belongs to a textless segment no boundary posture
 * covers — B1's acceptance contract routes it to the
 * approach door, never to a position). */
private sealed interface DocumentReport {
    data class Position(
        val chapterIndex: Int,
        val offset: Int,
        val fraction: Float,
        val lastVisibleIndex: Int
    ) : DocumentReport

    data class Approach(val chapterId: Long) : DocumentReport
}

/**
 * One segment's state item (Item 1): the whole-book
 * placeholder that stands for a segment without text.
 * Composing it in the composed range IS the fill trigger
 * (B1) — a [LaunchedEffect] keyed on (chapterId, state)
 * requests the fill through the ViewModel's one door;
 * FAILED never re-fires from composition (its state doesn't
 * change, so the effect doesn't re-run, and the ViewModel
 * refuses a non-explicit refill of a FAILED segment anyway).
 */
@Composable
private fun SegmentStateItem(
    state: SegmentState,
    isBoundary: Boolean,
    hasStoredCopy: Boolean,
    reason: String?,
    chapterId: Long,
    palette: ReaderPalette,
    onVisible: (Long) -> Unit,
    onDownload: (Long) -> Unit,
    onRetry: (Long) -> Unit
) {
    LaunchedEffect(chapterId, state) { onVisible(chapterId) }
    when (state) {
        SegmentState.LOADING -> Row(
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
            // The boundary fill keeps the shipped wording; a
            // fetch in flight says so; a parse in flight makes
            // no text claim (the same small row, spinner only).
            val label = when {
                isBoundary -> "Loading the next chapter…"
                !hasStoredCopy -> "Fetching this chapter…"
                else -> null
            }
            if (label != null) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.muted,
                    modifier = Modifier.padding(start = 12.dp)
                )
            }
        }
        SegmentState.MISSING -> Column(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "This chapter hasn't been downloaded yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = palette.muted,
                textAlign = TextAlign.Center
            )
            TextButton(onClick = { onDownload(chapterId) }) {
                Text("Download this chapter")
            }
        }
        SegmentState.FAILED -> Column(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                if (isBoundary) {
                    "The next chapter couldn't be fetched."
                } else {
                    "This chapter couldn't be fetched."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = palette.muted,
                textAlign = TextAlign.Center
            )
            if (!reason.isNullOrBlank()) {
                Text(
                    reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.muted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            TextButton(onClick = { onRetry(chapterId) }) {
                Text("Try again")
            }
        }
        SegmentState.RESIDENT -> {}
    }
}

@Composable
private fun DocumentChapterHeader(
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
private fun DocumentBlock(
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
        // ZoomableImage — graphicsLayer-only, so position
        // reporting is unaffected.
        // Item 4 (A6): THIS call site — the document surface's
        // only one — opts into the aspect reserve, so an image
        // item always holds a real box ahead of its load and
        // can never collapse to zero height inside the column
        // (the blank-illustration fix). The paged surface's
        // and the plate viewer's call sites are unchanged.
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
            // The paragraph long-press menu (Highlight / Note /
            // Bookmark) — the action reports THIS block's
            // chapter (A5).
            var menuOpen by remember(blockIndex) { mutableStateOf(false) }
            // Remembered per block — keyed by the block, its
            // range, the highlights and the rendering inputs
            // (bionic, palette, and the spoken/overlay washes).
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
                            // Stage 2's inset authority: a
                            // heading's rendered top padding
                            // is THIS 16.dp (body blocks: 0).
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
