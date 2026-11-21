package app.xreader.ui.reader

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import app.xreader.data.db.Chapter
import app.xreader.data.db.Highlight
import app.xreader.domain.content.ChapterBlock
import app.xreader.domain.reader.AutoScrollController
import app.xreader.domain.reader.ChapterLayoutCache
import app.xreader.domain.reader.ChapterLayoutService
import app.xreader.domain.reader.ChapterText
import app.xreader.domain.reader.ChapterTransition
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
import kotlin.math.abs
import kotlin.math.sign

/** Actions offered on a long-pressed paragraph (passage tools, §2.4 #7).
 * v1.7 one-engine (C1): relocated to the unified scroll core from the
 * retired ScrollReader.kt; the document surface is its only
 * definition site now. */
enum class ParagraphAction { HIGHLIGHT, NOTE, BOOKMARK }

/**
 * Seam patch (Moon Reader update): the wall's gesture
 * constants — the REA's numbers, ALL of them in this one
 * object, so a feel fix round tunes the wall from a single
 * place. The wall is a commitment gesture: drag distance
 * the chapter cannot scroll ACCUMULATES, the ProgressWheel
 * stands past [SHOW_THRESHOLD_DP] of it, and its arc fills
 * at [ARC_DEGREES_PER_DP] degrees per accumulated dp — a
 * full circle (60 dp of drag) ARMS the chapter change,
 * which happens only when the finger lifts. There is no
 * resistance and no visual give (B2 stands): the chapter
 * simply ends, and the wheel is the only answer to a push
 * past that end.
 */
private object WallWheel {
    /** Accumulated drag (dp) past which the wheel stands. */
    const val SHOW_THRESHOLD_DP = 8f

    /** Arc fill per accumulated dp (REA: arc = min(360,
     * |drag| × 6 / density), drag in px). */
    const val ARC_DEGREES_PER_DP = 6f

    const val FULL_CIRCLE_DEGREES = 360f

    /** The wheel's diameter (REA: a 40 dp circle). */
    val SIZE = 40.dp
}

/**
 * The wall wheel's live drag state: SIGNED accumulated
 * un-scrollable drag in px — negative pushes past the
 * chapter's END (toward NEXT), positive pulls past its
 * START (toward PREVIOUS). Written by the wall connection
 * (accumulation + unwind) and by the release observer
 * (the reset); read ONLY by the wheel overlay, so a drag
 * delta recomposes the wheel alone, never the list.
 */
private class WallWheelState {
    var dragPx by mutableFloatStateOf(0f)
}

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
    /** v1.7 Moon+ standard fix (Part 1): the segments whose
     * own text opens with its own title (the ViewModel's
     * fill-time decision on the original text) — they
     * compose NO app header; the book's own heading stands
     * alone. */
    segmentsWithOwnTitle: Set<Long> = emptySet(),
    /** Seam patch (Moon Reader update): the chapter-end
     * wall is live for this book (Chapter-by-chapter + the
     * owner's wall scope, decided by ReaderScreen): the
     * surface composes ONLY the anchor chapter, and a drag
     * past its end (or start) fills the ProgressWheel —
     * releasing with a full wheel swaps the whole content
     * to the next (or previous) chapter through the swap
     * doors below. Moon+'s model: one chapter resident,
     * neighbours held only as filled text, never composed. */
    wallActive: Boolean = false,
    /** Seam patch: an ARMED forward swap — the reader
     * dragged past the anchor chapter's end, filled the
     * wheel and let go. The ViewModel's next-chapter door
     * owns the semantics: the chapter being left counts as
     * FINISHED (A1 — the mark the retired append used to
     * make, now made by the swap), then the anchor moves
     * to the next chapter's top. */
    onWallSwapNext: () -> Unit = {},
    /** Seam patch: an ARMED backward swap — the anchor
     * moves to the previous chapter's END. Nothing is
     * marked (the retired prepend's precedent). */
    onWallSwapPrevious: () -> Unit = {},
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

    /** Part 1: whether [segment] composes the app's header
     * item. The decision is the ViewModel's (made at fill
     * time on the original text); this surface only applies
     * it — and an UNDECIDED segment (its text not yet held)
     * composes NO header, so no header can pop in or out
     * when the fill lands: the header arrives WITH the
     * segment's text, as one swap, or never (own title). */
    fun headerFor(segment: Int): Boolean {
        if (!showHeaders) return false
        val chapter = chapters.getOrNull(segment) ?: return false
        return segmentTexts.containsKey(chapter.id) &&
            chapter.id !in segmentsWithOwnTitle
    }

    // The segment index: prefix sums over per-segment item
    // counts — O(chapters), never O(total blocks), and no
    // flat list is ever materialised (Item 6).
    val segmentIndex = remember(
        chapters, segmentStates, segmentTexts, showHeaders,
        segmentsWithOwnTitle
    ) {
        SegmentIndex(
            chapters.indices.map { segment ->
                SegmentIndex.Shape(
                    hasHeader = headerFor(segment),
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

    // ── Wall mode: one chapter composed, the swap ────────
    // (Seam patch, Moon Reader update — replaces Fix Round
    // G's composed-range append wall.)
    //
    // When the wall is active the surface composes ONLY the
    // anchor segment: the composed "range" is the anchor
    // alone (rangeStart == rangeEnd == anchorChapterIndex),
    // and a second SegmentIndex stands over it. Every
    // consumer below reads through `index` and translates
    // its segment argument through `indexSegment`
    // (chapters-space minus rangeStart):
    //
    // - initialFlat / restore stages 1+2: the anchor's
    //   local index is anchorChapterIndex − rangeStart (0);
    // - the reporter: locate() yields a local segment,
    //   translated ONCE into chapters-space (+ rangeStart)
    //   before ANY use (the position report, the STATE_ITEM
    //   boundary math, StreamReadingPosition.resolve — the
    //   ViewModel never sees a local index); the end-of-book
    //   branch's last segment is rangeEnd;
    // - Item 4's pinned block: the boundary prefetch (a)
    //   fires as today — it is one of the fill triggers
    //   that prepare the next chapter; the finished report
    //   (b) NEVER fires in wall mode — the forward SWAP is
    //   the only finished-marking door there (A1: the swap
    //   door in the ViewModel marks the chapter it leaves);
    // - auto-scroll's halt check: form unchanged (locate →
    //   STATE_ITEM), reading the local index; at the wall
    //   the drift simply stops (B9 — it never fills the
    //   wheel and never crosses);
    // - spoken follow: flatIndexOf(indexSegment(anchor), …)
    //   — the spoken range is anchor-space by contract;
    // - overlay follow: only the anchor segment is
    //   eligible; segments outside it are skipped, never
    //   translated — a neighbour joins the screen only by
    //   becoming the anchor through a swap.
    //
    // A crossing is a WHOLE-CONTENT SWAP through the
    // ViewModel's deliberate-move doors (onWallSwapNext /
    // onWallSwapPrevious): the anchor flips exactly when
    // the armed gesture is RELEASED (never mid-drag), and
    // the restore stages assert the landing — the next
    // chapter's top, the previous chapter's END — against
    // text the preparation already holds, or park on the
    // segment's LOADING state item until its fill lands
    // (the cache-miss road; never a blank chapter).
    //
    // When the wall is off, `index` IS segmentIndex,
    // `indexSegment` is the identity and the range is
    // unused — every path below is then exactly the
    // pre-wall code.
    // The composed range IS the anchor (see the block
    // comment above): derived from the anchor at every
    // composition, so the composition that first sees a
    // swap's new anchor already speaks for it — the restore
    // stages can never consume a generation against a
    // pre-swap index. (Fix Round G's B8 collapse machinery
    // existed to force exactly this for its growing range;
    // a range that cannot grow needs no forcing.)
    val rangeStart = anchorChapterIndex
    val rangeEnd = anchorChapterIndex

    val wallIndex = remember(
        chapters, rangeStart, rangeEnd, segmentStates, segmentTexts
    ) {
        SegmentIndex(
            (rangeStart..rangeEnd).map { segment ->
                SegmentIndex.Shape(
                    // The header slot carried the retired
                    // append wall's GAP; the swap composes
                    // ONE segment, so the slot never exists.
                    hasHeader = false,
                    blockCount = if (
                        stateOf(segment) == SegmentState.RESIDENT
                    ) {
                        textOf(segment)?.content?.blocks?.size
                            ?: SegmentIndex.STATE
                    } else {
                        SegmentIndex.STATE
                    }
                )
            }
        )
    }
    val index = if (wallActive) wallIndex else segmentIndex
    fun indexSegment(segment: Int): Int =
        if (wallActive) segment - rangeStart else segment

    // The wall's live reads for the never-restarting
    // callbacks (key turns, the wall connection, the
    // release observer): they must see the CURRENT
    // composition's values, not the values of the
    // composition that created them. The chapter gates are
    // the wheel's book-start/end suppression (the REA's):
    // no wheel stands past the book's own ends.
    val wallActiveNow by rememberUpdatedState(wallActive)
    val hasNextChapterNow by rememberUpdatedState(
        rangeEnd < chapters.lastIndex
    )
    val hasPreviousChapterNow by rememberUpdatedState(
        rangeStart > 0
    )
    // The swap doors, likewise read through updated-state
    // holders by the never-restarting observers.
    val onWallSwapNextNow by rememberUpdatedState(onWallSwapNext)
    val onWallSwapPreviousNow by rememberUpdatedState(
        onWallSwapPrevious
    )

    /** The wheel's live drag state (see [WallWheelState]):
     * the connection accumulates into it, the release
     * observer reads and resets it, the overlay renders
     * it. */
    val wheelState = remember { WallWheelState() }

    /** The anchor whose NEXT chapter the ⅓ trigger (in the
     * reporter below) has already requested — once per
     * anchor. */
    var wallThirdFillAnchor by remember { mutableIntStateOf(-1) }

    // Seam patch (A5): ~100 ms after every completed
    // chapter change, refresh BOTH neighbours' fills
    // through the one fill door, so the next crossing in
    // either direction swaps onto held text. (The
    // residency planner already keeps the chapters BEHIND
    // the anchor filled; this is what prepares the one
    // AHEAD between boundary triggers.) It fires on the
    // anchor's first composition too — Moon+ pre-renders
    // the neighbours at open, which is what makes a first
    // crossing bounce like a later one.
    LaunchedEffect(anchorChapterIndex, wallActive) {
        if (!wallActive) return@LaunchedEffect
        delay(100)
        chapters.getOrNull(anchorChapterIndex - 1)
            ?.let { onSegmentVisible(it.id) }
        chapters.getOrNull(anchorChapterIndex + 1)
            ?.let { onSegmentVisible(it.id) }
    }

    // Open at the anchor segment: its saved block when it is
    // already resident (a warm re-entry), else its state item
    // (the restore effect asserts the block landing when the
    // fill lands). Stage 2 refines to the line (below).
    val anchorText = textOf(anchorChapterIndex)
    val initialFlat = remember(index, anchorChapterIndex) {
        val targetOffset = when (restoreHint) {
            PageHint.FIRST -> 0
            PageHint.LAST -> anchorText?.length ?: 0
            PageHint.OFFSET -> restoreOffset
        }
        val flat = if (anchorText != null) {
            index.flatIndexOf(
                indexSegment(anchorChapterIndex),
                anchorText.blockIndexAt(targetOffset)
            )
        } else {
            index.flatIndexOf(
                indexSegment(anchorChapterIndex),
                SegmentIndex.STATE_ITEM
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
            val flat = index.flatIndexOf(
                indexSegment(anchorChapterIndex),
                SegmentIndex.STATE_ITEM
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
        val flat = index.flatIndexOf(
            indexSegment(anchorChapterIndex), blockIndex
        )
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
        val flat = index.flatIndexOf(
            indexSegment(anchorChapterIndex), blockIndex
        )
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
    LaunchedEffect(index, pinned, wallActive) {
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
            if (first.index >= index.itemCount) {
                // Wall mode: the composed range's end is the
                // last segment here (the end-of-book item
                // only composes once the range reaches the
                // book's end).
                val lastSegment = if (wallActive) {
                    rangeEnd
                } else {
                    index.segmentCount - 1
                }
                if (lastSegment < 0) return@snapshotFlow null
                val text = textOf(lastSegment) ?: return@snapshotFlow null
                return@snapshotFlow DocumentReport.Position(
                    lastSegment, text.length, 1f, last.index
                )
            }
            val rawLoc = index.locate(first.index)
                ?: return@snapshotFlow null
            // Wall mode: translate the local segment into
            // chapters-space ONCE (+ rangeStart) — every use
            // below (the position, the STATE_ITEM boundary
            // math, StreamReadingPosition.resolve) reads
            // chapters-space.
            val loc = if (wallActive) {
                rawLoc.copy(segment = rawLoc.segment + rangeStart)
            } else {
                rawLoc
            }
            val baseReport = when (loc.block) {
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
            // Fix Round G (B4): in wall mode the reading
            // chapter is the segment of the LAST VISIBLE item
            // — Moon+'s footer flips the moment the next
            // chapter's content reaches the screen's lower
            // part, and on the way up the previous chapter's
            // name stands until the current chapter's heading
            // has left the screen. So when the last visible
            // item sits in a LATER resident segment than the
            // reported one, the report names that segment at
            // its start: the reader has just crossed into it.
            // Within one segment the report stays the
            // first-visible computation above, unchanged — the
            // saved place never drifts to the screen's bottom.
            // A textless last segment never names the position
            // (the residency check), exactly as B1's contract
            // demands of the first.
            val finalReport = if (wallActive &&
                baseReport is DocumentReport.Position
            ) {
                val rawLast = if (last.index < index.itemCount) {
                    index.locate(last.index)
                } else {
                    null
                }
                val lastSegment = rawLast?.let {
                    it.segment + rangeStart
                }
                if (lastSegment != null &&
                    lastSegment > baseReport.chapterIndex &&
                    stateOf(lastSegment) == SegmentState.RESIDENT
                ) {
                    DocumentReport.Position(
                        lastSegment, 0, 0f, last.index
                    )
                } else {
                    baseReport
                }
            } else {
                baseReport
            }
            // Seam patch, fix round 1 (re-check 10c): in
            // wall mode, while the anchor's restore
            // generation is unconsumed, a Position report
            // naming any chapter but the anchor is the
            // boundary-posture artifact — the surface is
            // parked on the anchor's LOADING/FAILED state
            // item (an armed swap or a volume-key turn onto
            // an unfilled neighbour, its fill still in
            // flight) and the STATE_ITEM branch above names
            // the previous chapter at its end. Emitting it
            // would flip the ViewModel's anchor back before
            // the fill lands, and nothing would move it
            // forward again — the swap's landing fails.
            // Suppress the report until stage 1 consumes
            // the generation. Nothing truthful is lost:
            // the swap door already wrote the left
            // chapter's end progress (A1's marking +
            // moveAnchor's flush) before the anchor moved.
            // The guard lifts the moment the generation is
            // consumed — the fill landing (stage 1 asserts
            // the swap's landing) or a genuine user drag
            // retiring the restore (R2's witness consumes
            // it the same way) — and reports naming the
            // anchor itself flow throughout.
            if (wallActive &&
                consumedGeneration != restoreGeneration &&
                finalReport is DocumentReport.Position &&
                finalReport.chapterIndex != anchorChapterIndex
            ) {
                null
            } else {
                finalReport
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
                        // finished). Seam patch: in wall mode
                        // (a) still fires — it is the prefetch
                        // backstop — and (b) NEVER fires: the
                        // forward swap is the only
                        // finished-marking door there (A1).
                        if (pinned) {
                            val rawLastLoc = if (
                                report.lastVisibleIndex <
                                index.itemCount
                            ) {
                                index.locate(report.lastVisibleIndex)
                            } else {
                                null
                            }
                            val lastLoc = if (
                                wallActive && rawLastLoc != null
                            ) {
                                rawLastLoc.copy(
                                    segment = rawLastLoc.segment +
                                        rangeStart
                                )
                            } else {
                                rawLastLoc
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
                            if (!wallActive) {
                                val nextStart = index
                                    .segmentStart(
                                        indexSegment(
                                            report.chapterIndex + 1
                                        )
                                    )
                                if (nextStart >= 0 &&
                                    report.lastVisibleIndex >= nextStart
                                ) {
                                    onChapterEndReached()
                                }
                            }
                        }
                        // Seam patch (A5): in wall mode, once
                        // the reader is a third into the
                        // anchor chapter, the NEXT chapter's
                        // fill is requested through the one
                        // fill door — Moon+'s ⅓ pre-render
                        // trigger — so the wheel swap lands
                        // on held text instead of the LOADING
                        // state item. Once per anchor; the
                        // boundary trigger above stays as the
                        // backstop.
                        if (wallActive &&
                            report.chapterIndex ==
                            anchorChapterIndex &&
                            report.fraction >= 1f / 3f &&
                            wallThirdFillAnchor !=
                            anchorChapterIndex
                        ) {
                            wallThirdFillAnchor =
                                anchorChapterIndex
                            chapters.getOrNull(
                                anchorChapterIndex + 1
                            )?.let { onSegmentVisible(it.id) }
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
            // Seam patch: a key turn AT the wall crosses it
            // — the keypress is the deliberate pull, standing
            // in for the armed drag: the swap door moves the
            // anchor (the next chapter's top; the previous
            // chapter's END) and the restore stages assert
            // the landing, so the turn is spent on the swap
            // itself and no viewport scroll follows it. A
            // turn whose neighbour has not landed swaps onto
            // its LOADING state item, which requests the
            // fill. At the book's own ends the turn falls
            // through to the viewport scroll below, which
            // simply clamps. The wall inputs are read
            // through their updated-state holders because
            // this effect never restarts. (Auto-scroll never
            // does this — B9; keys are the reader's hand
            // only.)
            if (wallActiveNow) {
                if (direction > 0 &&
                    !listState.canScrollForward &&
                    hasNextChapterNow
                ) {
                    onWallSwapNextNow()
                    return@collectLatest
                }
                if (direction < 0 &&
                    !listState.canScrollBackward &&
                    hasPreviousChapterNow
                ) {
                    onWallSwapPreviousNow()
                    return@collectLatest
                }
            }
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
        index, wallActive, anchorChapterIndex
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
                item.index < index.itemCount &&
                    index.locate(item.index)?.block ==
                    SegmentIndex.STATE_ITEM
            }
            if (!halted) {
                // Fix Round G (B9), kept by the seam patch
                // (A3): the drift meeting the wall PAUSES —
                // auto-scroll never crosses; the wall waits
                // for the reader's own drag or key turn
                // (Moon+'s own auto-scroll likewise stops at
                // a chapter end). The pause is per-anchor, so
                // after a manual crossing the landing chapter
                // starts unpaused and the drift may resume
                // there by the existing tap contract.
                if (wallActive && !listState.canScrollForward) {
                    autoPaused = true
                } else {
                    listState.animateScrollBy(velocity * 0.1f)
                }
            }
        }
    }

    // Read-aloud follow — keep the spoken sentence visible.
    // The spoken range is in the ANCHOR segment's coordinates
    // (Listen reads the chapter the VM publishes as on
    // screen); the follow is gated to that segment.
    LaunchedEffect(spokenRange, index) {
        if (spokenRange == null) return@LaunchedEffect
        val text = textOf(anchorChapterIndex) ?: return@LaunchedEffect
        val blockIndex = text.blockIndexAt(spokenRange.first)
        if (blockIndex < 0) return@LaunchedEffect
        val flat = index.flatIndexOf(
            indexSegment(anchorChapterIndex), blockIndex
        )
        if (flat >= 0) listState.animateScrollToItem(flat)
    }

    // Media-overlay follow — keep the overlay's block visible,
    // whichever RESIDENT segment carries it.
    LaunchedEffect(overlayBlockId, index) {
        if (overlayBlockId == null) return@LaunchedEffect
        for (segment in chapters.indices) {
            // Wall mode: only segments inside the composed
            // range are eligible — others are skipped, never
            // translated.
            if (wallActive && segment !in rangeStart..rangeEnd) {
                continue
            }
            val text = textOf(segment) ?: continue
            val range = text.ranges.firstOrNull { it.blockId == overlayBlockId }
                ?: continue
            val flat = index.flatIndexOf(
                indexSegment(segment), range.blockIndex
            )
            if (flat >= 0) listState.animateScrollToItem(flat)
            return@LaunchedEffect
        }
    }

    // ── Seam patch: the wall connection + the release ────
    //
    // The wall has NO overscroll machinery and no visual
    // give (B2 stands): the column ends at the anchor
    // chapter's end — a REAL end, blank below the last
    // line, no stretch. This connection only ACCUMULATES
    // the un-scrollable part of a genuine drag at that end
    // — Drag-source deltas the child could not scroll,
    // while the list stands at the end — into the wheel
    // state the overlay renders:
    //
    // - pushing past the end accumulates toward NEXT
    //   (negative), pulling past the start toward PREVIOUS
    //   (positive); a drag that switches ends starts over
    //   from its own delta;
    // - content scrolling back toward the chapter un-fills
    //   the wheel by the same distance (the unwind, clamped
    //   at zero), so the fill always tracks the finger;
    // - nothing is consumed and nothing is stretched: the
    //   deltas are OBSERVED, not eaten — the list's own
    //   behaviour is untouched — and only Drag sources are
    //   read at all, which keeps the drift's, the restore's
    //   and the follows' programmatic scrolls out, so
    //   auto-scroll can never fill the wheel (B9);
    // - a FLING never crosses: Moon+ arms the change by
    //   drag alone, so onPostFling is a deliberate no-op —
    //   momentum that merely arrives at the end stops
    //   there, and the crossing waits for the drag and the
    //   release below.
    //
    // THE RELEASE comes from the pointer observer on the
    // Box (wallReleaseObserver, below), NOT from this
    // connection: a NestedScrollConnection has no finger-up
    // callback for a slow, zero-velocity release (its only
    // release-shaped callback, onPostFling, is a fling
    // path). The observer watches the raw pointer stream
    // in the Initial pass and fires on the release itself,
    // so a release at any speed — including a dead stop —
    // is seen. That is the release proof (A6).
    //
    // Fences (B10): a pinch/pan zoom in progress
    // (zoomLockedItems non-empty) freezes accumulation —
    // the drag belongs to the image. Text selection needs
    // no gate: selection drags are consumed by the
    // SelectionContainer and never enter the nested-scroll
    // chain. Inert unless the wall is active: every
    // callback checks wallActiveNow first, and the
    // modifier below only attaches the connection in wall
    // mode.
    val wallConnection = remember(wallActive) {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                if (!wallActiveNow ||
                    source != NestedScrollSource.Drag ||
                    zoomLockedItems.isNotEmpty()
                ) {
                    return Offset.Zero
                }
                // The unwind: content scrolling back toward
                // the chapter un-fills the wheel by the same
                // distance, clamped at zero — the fill
                // tracks the finger in both directions.
                val drag = wheelState.dragPx
                if (drag != 0f && consumed.y != 0f &&
                    sign(consumed.y) != sign(drag)
                ) {
                    val unwound = drag + consumed.y
                    wheelState.dragPx =
                        if (sign(unwound) == sign(drag)) {
                            unwound
                        } else {
                            0f
                        }
                }
                if (available.y < 0f &&
                    !listState.canScrollForward
                ) {
                    // Pushing past the chapter's end: NEXT.
                    // Suppressed at the book's own end.
                    if (hasNextChapterNow) {
                        val current = wheelState.dragPx
                        wheelState.dragPx =
                            if (current > 0f) {
                                available.y
                            } else {
                                current + available.y
                            }
                    }
                } else if (available.y > 0f &&
                    !listState.canScrollBackward
                ) {
                    // Pulling past the chapter's start:
                    // PREVIOUS. Suppressed at the book's
                    // own start.
                    if (hasPreviousChapterNow) {
                        val current = wheelState.dragPx
                        wheelState.dragPx =
                            if (current < 0f) {
                                available.y
                            } else {
                                current + available.y
                            }
                    }
                }
                return Offset.Zero
            }

            override suspend fun onPostFling(
                consumed: Velocity,
                available: Velocity
            ): Velocity {
                // Deliberate no-op (see the block comment):
                // a fling never arms and never crosses — the
                // release observer owns every crossing.
                return Velocity.Zero
            }
        }
    }

    // Seam patch: THE RELEASE OBSERVER (A6) — the swap's
    // trigger. A pointerInput watching the raw pointer
    // stream in the Initial pass (the readerTapToggle
    // precedent, on this same Box): it consumes NOTHING, so
    // taps, scrolls, selection and image zooms flow through
    // untouched; it only notices the moment the LAST
    // pressed pointer lifts. That event exists for every
    // release — fast, slow, or a dead stop at zero
    // velocity — which is exactly the guarantee
    // NestedScrollConnection cannot give. On a release the
    // wheel state is ALWAYS reset (an un-armed release just
    // hides the wheel; nothing changes); the swap fires
    // only when the wheel stands ARMED — a full circle of
    // accumulated drag (60 dp at the REA's 6°/dp) — the
    // wall is active, and no image zoom owns the gesture.
    // (The density is read into a local first: inside
    // pointerInput the scope IS a Density, whose own
    // `density` property would shadow the composition's.)
    val wallDensity = density
    val wallReleaseObserver = Modifier.pointerInput(Unit) {
        awaitPointerEventScope {
            var anyPressed = false
            while (true) {
                val event = awaitPointerEvent(
                    PointerEventPass.Initial
                )
                val pressedNow = event.changes.any { it.pressed }
                if (anyPressed && !pressedNow) {
                    val drag = wheelState.dragPx
                    wheelState.dragPx = 0f
                    val armedDistancePx =
                        WallWheel.FULL_CIRCLE_DEGREES /
                            WallWheel.ARC_DEGREES_PER_DP *
                            wallDensity.density
                    if (wallActiveNow &&
                        zoomLockedItems.isEmpty() &&
                        abs(drag) >= armedDistancePx
                    ) {
                        if (drag < 0f && hasNextChapterNow) {
                            onWallSwapNextNow()
                        } else if (drag > 0f &&
                            hasPreviousChapterNow
                        ) {
                            onWallSwapPreviousNow()
                        }
                    }
                }
                anyPressed = pressedNow
            }
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
            // Seam patch: the wall's release observer (see
            // its definition above) — a second Initial-pass
            // watcher on the same root; it consumes nothing.
            .then(wallReleaseObserver)
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
                // Seam patch: wall mode attaches the
                // accumulation connection only — there is no
                // visual give to apply (B2: the end is real).
                // Non-wall the chain is exactly fillMaxSize,
                // as before.
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (wallActive) {
                            Modifier.nestedScroll(wallConnection)
                        } else {
                            Modifier
                        }
                    )
            ) {
                chapters.forEachIndexed { segment, chapter ->
                    // Seam patch: the wall composes ONLY the
                    // anchor segment — every other segment
                    // emits nothing (no blocks, no state item,
                    // no boundary door, and NO gap item: the
                    // retired append wall's blank gap between
                    // composed segments is gone with the range
                    // itself). A neighbour's text is prepared
                    // through the fill door and joins the
                    // screen only by becoming the anchor
                    // through a swap. The anchor's own state
                    // item below still fires its B1 fill.
                    if (wallActive &&
                        segment !in rangeStart..rangeEnd
                    ) {
                        return@forEachIndexed
                    }
                    if (headerFor(segment)) {
                        item(key = "h-${chapter.id}") {
                            DocumentChapterHeader(
                                // Part 1: ONE clean line —
                                // the same reconciliation as
                                // the chrome header (a chapter
                                // name whose own number
                                // contradicts its position
                                // yields to the position line),
                                // set as a real chapter heading.
                                // No eyebrow, no rule: where
                                // the text carries its own
                                // title, headerFor has already
                                // kept this item out entirely.
                                title = ReaderCounters.headerLine(
                                    chapterTitle = chapter.title,
                                    chapterIndex = segment,
                                    chapterCount = chapters.size,
                                    ownNumber =
                                        app.xreader.data.extension
                                            .ChapterNumbers
                                            .parseAny(chapter.title, "")
                                ),
                                palette = palette,
                                fontFamily = fontFamily
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
                // Fix Round G: in wall mode the item stands
                // only once the composed range REACHES the
                // book's last segment — mid-book the composed
                // end is a wall, not the book's end.
                val lastSegment = if (wallActive) {
                    rangeEnd
                } else {
                    chapters.lastIndex
                }
                if (lastSegment >= 0 &&
                    (!wallActive ||
                        rangeEnd == chapters.lastIndex) &&
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
        // Seam patch: the ProgressWheel overlay — the
        // commitment gesture made visible. It reads ONLY
        // the wheel state, so drag deltas recompose this
        // overlay alone, never the list.
        if (wallActive) {
            WallWheelOverlay(
                state = wheelState,
                palette = palette,
                topInset = topInset,
                bottomInset = bottomInset
            )
        }
    }
}

/**
 * Seam patch: the wall's ProgressWheel (Moon+'s
 * views/ProgressViews/ProgressWheel) — a 40 dp circle that
 * SPINS continuously while it stands and whose arc FILLS
 * with the accumulated drag (arc = min(360°, |drag| × 6 /
 * density), the REA's formula): a full circle is the armed
 * state the release observer reads. Below the REA's 8 dp
 * of accumulated drag it does not stand at all. Bottom-
 * centre when pushing for NEXT, top-centre when pulling
 * for PREVIOUS, inset clear of the measured chrome.
 */
@Composable
private fun WallWheelOverlay(
    state: WallWheelState,
    palette: ReaderPalette,
    topInset: androidx.compose.ui.unit.Dp,
    bottomInset: androidx.compose.ui.unit.Dp
) {
    val drag = state.dragPx
    val density = LocalDensity.current
    if (drag == 0f ||
        abs(drag) <= WallWheel.SHOW_THRESHOLD_DP * density.density
    ) {
        return
    }
    val arcDegrees = minOf(
        WallWheel.FULL_CIRCLE_DEGREES,
        abs(drag) / density.density *
            WallWheel.ARC_DEGREES_PER_DP
    )
    Box(Modifier.fillMaxSize()) {
        WallWheelSpinner(
            arcDegrees = arcDegrees,
            palette = palette,
            modifier = Modifier
                .align(
                    if (drag < 0f) {
                        Alignment.BottomCenter
                    } else {
                        Alignment.TopCenter
                    }
                )
                .padding(
                    top = if (drag > 0f) {
                        topInset + 20.dp
                    } else {
                        0.dp
                    },
                    bottom = if (drag < 0f) {
                        bottomInset + 20.dp
                    } else {
                        0.dp
                    }
                )
        )
    }
}

/** The standing wheel itself: the spin animation lives in
 * this child so it is only composed (and only animating)
 * while the wheel stands — the overlay above gates on the
 * drag threshold before this is ever called. */
@Composable
private fun WallWheelSpinner(
    arcDegrees: Float,
    palette: ReaderPalette,
    modifier: Modifier = Modifier
) {
    val spinTransition = rememberInfiniteTransition(
        label = "wallWheelSpin"
    )
    val spin by spinTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = 900,
                easing = LinearEasing
            ),
            repeatMode = RepeatMode.Restart
        ),
        label = "wallWheelSpinAngle"
    )
    Canvas(modifier.size(WallWheel.SIZE)) {
        val stroke = 3.dp.toPx()
        // A soft disc so the wheel reads over text.
        drawCircle(
            color = palette.background.copy(alpha = 0.72f)
        )
        drawCircle(
            color = palette.muted.copy(alpha = 0.35f),
            style = Stroke(width = stroke)
        )
        rotate(spin) {
            drawArc(
                color = palette.accent,
                startAngle = -90f,
                sweepAngle = arcDegrees,
                useCenter = false,
                style = Stroke(
                    width = stroke,
                    cap = StrokeCap.Round
                )
            )
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

/**
 * Part 1: the app's chapter header — ONE clean line in the
 * QuickNovel / Moon+ shape: the reconciled title set as a
 * real chapter heading (titleLarge in the reading font),
 * generous space above, modest space below. The eyebrow
 * ("Chapter N"), the second title line and the rule that
 * used to stack over the book's own heading are gone; a
 * segment whose text opens with its own title composes no
 * header at all (see headerFor), so a chapter's name is
 * printed exactly once, by the book or by the app.
 */
@Composable
private fun DocumentChapterHeader(
    title: String,
    palette: ReaderPalette,
    fontFamily: FontFamily
) {
    Text(
        title,
        style = MaterialTheme.typography.titleLarge
            .copy(fontFamily = fontFamily),
        color = palette.text,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 28.dp, bottom = 12.dp)
    )
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
