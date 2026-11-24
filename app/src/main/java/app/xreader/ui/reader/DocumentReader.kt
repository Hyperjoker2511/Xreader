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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import kotlin.math.max
import kotlin.math.min

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

/** Code 117 (§5.6, OQ8): the selection drag's edge
 * auto-scroll — the band at each LazyColumn edge inside
 * which a held finger scrolls the list, and the top speed
 * the band's pressure maps to. Named constants; tune the
 * numbers, not the shape. */
private const val SELECTION_EDGE_BAND_DP = 48f
private const val SELECTION_EDGE_MAX_DP_PER_SECOND = 480f

/**
 * The wall wheel's live drag state: SIGNED accumulated
 * un-scrollable drag in px — negative pushes past the
 * chapter's END (toward NEXT), positive pulls past its
 * START (toward PREVIOUS). Written by the release
 * observer's pointer stream (accumulation + the reset —
 * MR-2 Part S: the raw finger stream, never the scroll
 * pipeline); read ONLY by the wheel overlay, so a drag
 * delta recomposes the wheel alone, never the list.
 */
private class WallWheelState {
    var dragPx by mutableFloatStateOf(0f)
}

/**
 * The landing seed's memory (smoothness fix §2): which
 * generation the composition-time seed has landed, and
 * whether that landing already folded the line refinement
 * from the layout record (§3 — stage 2's fallback reads
 * [foldedFor] to stand down). Deliberately PLAIN vars in a
 * remembered holder, never snapshot state: the seed writes
 * them during composition, and a snapshot write there
 * would schedule a recomposition the landing does not
 * need. The generation the surface is born into starts
 * landed iff its anchor already has text — the creation
 * seed (initialFlat) asserts that landing, and a textless
 * birth generation lands through the park + seed when its
 * fill arrives.
 */
private class LandingSeedTracker(
    var generation: Int,
    var landed: Boolean,
    var folded: Boolean
) {
    fun isLanded(generation: Int): Boolean =
        this.generation == generation && landed

    fun foldedFor(generation: Int): Boolean =
        this.generation == generation && folded
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
    /** Moon+ full copy: a boundary CROSSING out of the anchor
     * segment — observed by the reporter from the settled
     * viewport and verified against the live layout before it
     * fires (see the report handler below). A door-equivalent
     * anchor move to the named segment: the joint-stream
     * analogue of Moon+'s pageDownToNextChapter event.
     * Position reports proper ride [onDocumentPosition] and
     * can never name another chapter. */
    onChapterCrossed: (chapterIndex: Int) -> Unit,
    /** Code 117 (§8): the selection bar's true-range arms —
     * the callback carries the model's whole range; the
     * screen fulfils Highlight / Note / Bookmark against it
     * (the split rule for spanning ranges lives there). */
    onRangeAction: (selection: ReaderTextSelection, action: ParagraphAction) -> Unit,
    /** Code 118: the colour row's apply for a LIVE selection
     * — the screen fulfils it against the model's range,
     * writing the tint's token (the split rule rides the
     * same loop as the Highlight arm). */
    onApplyHighlight: (selection: ReaderTextSelection, tintArgb: Int) -> Unit = { _, _ -> },
    /** Code 118: the colour row's apply for the EXISTING
     * state — re-tints the stored highlight's row. */
    onUpdateHighlightColor: (highlight: Highlight, tintArgb: Int) -> Unit = { _, _ -> },
    /** Code 118: the existing state's trash slot. */
    onRemoveHighlight: (highlight: Highlight) -> Unit = {},
    /** Code 118: the existing state's Note arm — the note
     * editor opens over the stored highlight. */
    onEditHighlightNote: (highlight: Highlight) -> Unit = {},
    /** Code 117 (§7.3): the model's selected text, assembled
     * by the screen from its segment texts — the bar host's
     * Copy/Share arms and text actions consume it. */
    selectionText: (ReaderTextSelection) -> String = { "" },
    /** Patch 11: the selection menu's screen-level actions. */
    onSelectionAction: (ReaderSelectionAction, String) -> Unit = { _, _ -> },
    /** SelectionBar (§9.2): the back chain's selection-layer
     * report — true while a selection-menu episode is open
     * in this surface. */
    onSelectionOpenChanged: (Boolean) -> Unit = {},
    /** SelectionBar (§9.2): the back chain's clear nonce —
     * a bump from the screen hides the selection menu
     * immediately. */
    clearSelectionSignal: Int = 0,
    /** SelectionBar (§9.4): the motion gate, fulfilled by
     * the screen as `!reduceMotion`. */
    motionEnabled: Boolean = true,
    onCenterTap: () -> Unit,
    onChromeWanted: (Boolean) -> Unit,
    onDownloadChapter: (chapterId: Long) -> Unit,
    onRetryChapter: (chapterId: Long) -> Unit,
    /** B1: a state item entered the composed range. */
    onSegmentVisible: (chapterId: Long) -> Unit,
    /** B1: the first visible item belongs to a textless
     * segment — a display state, never a position. */
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
    //   before ANY use (the position report and the crossing
    //   check — the ViewModel never sees a local index); the
    //   end-of-book branch's last segment is rangeEnd;
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
    // callbacks (key turns, the release observer): they
    // must see the CURRENT composition's values, not the
    // values of the composition that created them. The chapter gates are
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

    // Moon+ full copy: the report handler (below) reads the
    // CURRENT anchor through this holder — never the value
    // captured when its effect last restarted — to tell a
    // within-anchor position from a crossing candidate. In
    // wall mode the effect restarts on every anchor change
    // (the wall index is remembered on the anchor), so its
    // captures were always fresh; on the whole-book list the
    // restart rides on segment-map republishes, whose timing
    // against a deliberate jump is not guaranteed. The
    // code-106 restore guard that also read a generation
    // holder here is DELETED with the inference layer it
    // fenced (see the report handler): with no report able
    // to rename the chapter, there is nothing to guard.
    val anchorChapterIndexNow by rememberUpdatedState(
        anchorChapterIndex
    )

    /** The wheel's live drag state (see [WallWheelState]):
     * the release observer accumulates into it, reads and
     * resets it; the overlay renders it. */
    val wheelState = remember { WallWheelState() }

    // ── Code 117: the selection model (§3.2) ─────────────
    // The controller holds the one selection state, the drag
    // session, the layout registry and the tap-away gate.
    // This body never reads the selection/session states in
    // composition — the bar layer, the overlay and the
    // effects read them in their own scopes, so a drag frame
    // recomposes nothing here (§6.4's budget).
    val selectionController = remember { ReaderSelectionController() }
    // The controller's chapter-text door, refreshed on every
    // composition (a plain assignment — the seed-tracker
    // pattern, never a snapshot write).
    selectionController.chapterTextOf = { segment -> textOf(segment) }
    // Code 118: the controller's tap hit-test door — the
    // test itself (storedHighlightAt, below) needs this
    // body's highlights list and chapter list; the entry
    // recognisers reach it through the controller.
    selectionController.highlightAtPoint = { windowPoint ->
        storedHighlightAt(
            selectionController, chapters, highlights, windowPoint
        )
    }

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
    // (the landing seed asserts the block landing when the
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

    // Restore bookkeeping (Item 5 + the smoothness fix): a
    // generation is landed exactly once — by the creation
    // seed (initialFlat) for the generation the surface is
    // born into, by the composition-time landing seed below
    // for every later one; the seed records its landing on
    // hit OR miss. Stage 2 (line granularity) survives only
    // as the record-miss fallback and keeps its own witness.
    // Neither path is keyed on any list size (there is no
    // flat list) and neither fires once the user has moved
    // (R2). [seedTracker] is a PLAIN remembered holder, not
    // snapshot state: the seed writes it during composition,
    // and no recomposition may ride on those writes.
    val seedTracker = remember {
        LandingSeedTracker(
            generation = restoreGeneration,
            landed = anchorText != null,
            folded = false
        )
    }
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

    // Restore, the landing seed (smoothness fix §2): when
    // the generation changes — or a parked generation's text
    // arrives — the landing is asserted HERE, during the
    // composition that first speaks for the new anchor,
    // through requestScrollToItem: the list's next layout
    // pass measures the new anchor AT the landing, so no
    // frame of it is ever laid out at the previous chapter's
    // scroll position. (The retired stage-1 effect scrolled
    // only after that first layout committed — a full
    // recomposition late, the new chapter briefly drawn at
    // the stale clamped index, then snapped.) The
    // derivation is the creation path's own (initialFlat
    // above): FIRST → the segment's block 0, LAST → its
    // last block, OFFSET → restoreOffset's block. §3's fold
    // adds the line-exact scroll offset from the layout
    // record when the record exists at this moment (FIRST
    // never looks — its line is always 0). Landed on hit
    // or miss, exactly as stage 1 consumed (R1); a
    // generation the user has already dragged in is landed
    // without asserting (R2 — reachable through the park:
    // the generation publishes, the reader scrolls the
    // still-textless list, then the fill arrives).
    if (seedTracker.generation != restoreGeneration) {
        seedTracker.generation = restoreGeneration
        seedTracker.landed = false
        seedTracker.folded = false
    }
    if (!seedTracker.landed && anchorText != null) {
        if (userMovedGeneration == restoreGeneration) {
            seedTracker.landed = true
        } else {
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
                var scrollOffsetPx = 0
                if (restoreHint != PageHint.FIRST) {
                    // §3's fold: the line refinement, from
                    // the same record stage 2 reads, folded
                    // into this one assertion when the record
                    // exists. A record that exists but holds
                    // no usable line for the block still
                    // settles the question (stage 2's own
                    // verdict on such a record is "no
                    // refinement") — folded either way.
                    val chapter = chapters.getOrNull(anchorChapterIndex)
                    val layout = chapter?.let {
                        layoutService.cache.get(
                            ChapterLayoutCache.Key(
                                chapterId = it.id,
                                contentHash = ChapterLayoutCache
                                    .contentHash(anchorText),
                                fingerprint = layoutFingerprint
                            )
                        )
                    }
                    if (layout != null) {
                        seedTracker.folded = true
                        val measured = layout.measuredBlocks
                            .getOrNull(blockIndex)
                        if (measured != null &&
                            measured.lineStarts.isNotEmpty() &&
                            measured.lineHeightPx > 0f
                        ) {
                            val lineIndex = measured.lineStarts
                                .indexOfLast { it <= targetOffset }
                                .coerceAtLeast(0)
                            if (lineIndex > 0) {
                                val block = anchorText.content.blocks
                                    .getOrNull(blockIndex)
                                val topInsetPx = if (
                                    block is ChapterBlock.Heading
                                ) {
                                    with(density) { 16.dp.toPx() }
                                } else {
                                    0f
                                }
                                scrollOffsetPx =
                                    (-(topInsetPx +
                                        lineIndex * measured.lineHeightPx)
                                        ).toInt()
                            }
                        }
                    }
                }
                listState.requestScrollToItem(flat, scrollOffsetPx)
            }
            seedTracker.landed = true
        }
    }

    // Restore, the park (the kept post-layout consumer):
    // while the anchor segment has NO text, the generation's
    // landing cannot be derived — the list is merely parked
    // at the anchor's state item (composing it is what
    // triggers its fill). A post-layout scroll is the right
    // tool here: the segment has no text to misplace, and
    // the park must read the CURRENT layout to know whether
    // the state item already stands visible. When the fill
    // lands, the seed above asserts the real landing in the
    // composition that first speaks the text.
    LaunchedEffect(restoreGeneration, anchorText) {
        if (anchorText != null) return@LaunchedEffect
        if (userMovedGeneration == restoreGeneration) {
            return@LaunchedEffect
        }
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
    }

    // Restore, stage 2 (line granularity) — now the
    // record-miss FALLBACK (smoothness fix §3): the seed
    // above folds the refinement into the landing itself
    // whenever the anchor segment's layout record exists at
    // assertion time, so this effect's scroll is owed only
    // when the record was missing then (or arrived only
    // with the layoutVersion bump). On a miss THIS surface
    // measures the anchor segment with its own visible-path
    // measurer, off the composition path, and puts the
    // record — the analogue of Moon+'s rare on-the-spot
    // render. The arithmetic (A3): the block item's top
    // inset for this block (the stage-2 authority: a
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
        if (!seedTracker.isLanded(restoreGeneration) ||
            refinedGeneration == restoreGeneration
        ) {
            return@LaunchedEffect
        }
        if (userMovedGeneration == restoreGeneration) {
            refinedGeneration = restoreGeneration
            return@LaunchedEffect
        }
        // §3: a landing the seed already folded from the
        // record needs no second assertion, and a FIRST
        // landing's line is always 0 — neither may pay the
        // lookup or the measure below.
        if (seedTracker.foldedFor(restoreGeneration) ||
            restoreHint == PageHint.FIRST
        ) {
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
    //
    // Moon+ full copy — the reporter's contract. The
    // snapshotFlow below OBSERVES the settled viewport and
    // computes the position inside whichever segment is
    // first-visible; observation is all it does. What a
    // report may DO is decided here, and it is Moon+'s
    // shape: a report naming the CURRENT anchor is the
    // one-way derivation (anchor, scroll) → within-chapter
    // offset — it feeds the footer, progress and place
    // saving, and nothing else. A report naming ANOTHER
    // segment is a crossing CANDIDATE, never a position: it
    // fires the crossing door only if the viewport, read
    // LIVE at delivery time, really stands in or past that
    // segment. Otherwise the report is stale — a pre-jump
    // reading report, or the park on a neighbour's state
    // item — and it is dropped: nothing truthful is lost,
    // because the next settled report carries the anchor's
    // own place. This replaces the code-106 generation
    // guard, which fenced the same inference path only
    // while a restore was unconsumed and stood down by
    // design once the jump landed; here there is no
    // inference path left to fence — a report can never
    // rename the chapter, in any mode, at any time.
    LaunchedEffect(index, pinned, wallActive) {
        // True when the viewport, read LIVE at delivery
        // time, really stands in or past [target] relative
        // to [anchor]: forward (target > anchor) requires
        // the first visible item's segment to be at or past
        // the target; backward, at or before it. A genuine
        // crossing passes — the stream really moved into the
        // neighbouring segment. A stale report fails — after
        // a deliberate jump the live first segment IS the
        // just-landed anchor, contradicting the report's old
        // segment. A deep fling converges through sequential
        // one-step crossings from subsequent settled
        // reports, never through one report's say-so.
        val crossingConfirmed: (Int, Int) -> Boolean =
            { target, anchor ->
                val liveFirst = listState.layoutInfo
                    .visibleItemsInfo.firstOrNull()
                when {
                    liveFirst == null -> false
                    liveFirst.index >= index.itemCount -> {
                        val lastSegment = if (wallActive) {
                            rangeEnd
                        } else {
                            index.segmentCount - 1
                        }
                        if (target > anchor) {
                            lastSegment >= target
                        } else {
                            lastSegment <= target
                        }
                    }
                    else -> {
                        val raw = index.locate(liveFirst.index)
                        if (raw == null) {
                            false
                        } else {
                            val segmentNow = if (wallActive) {
                                raw.segment + rangeStart
                            } else {
                                raw.segment
                            }
                            if (target > anchor) {
                                segmentNow >= target
                            } else {
                                segmentNow <= target
                            }
                        }
                    }
                }
            }
        // Delivers a position that names the CURRENT anchor
        // (or the segment a confirmed crossing has just made
        // the anchor): the one-way derivation into the
        // ViewModel, plus the two last-visible side doors
        // that have always ridden on it — Item 4's
        // end-detection (CHAPTER_BY_CHAPTER) and the wall
        // mode ⅓ prefetch trigger. The trigger reads the
        // LIVE anchor holder, as the handler does.
        val deliverPosition: (DocumentReport.Position) -> Unit =
            { position ->
                onDocumentPosition(
                    position.chapterIndex, position.offset,
                    position.fraction
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
                        position.lastVisibleIndex <
                        index.itemCount
                    ) {
                        index.locate(position.lastVisibleIndex)
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
                    val currentText = textOf(position.chapterIndex)
                    val lastBlockIndex =
                        (currentText?.content?.blocks?.size ?: 0) - 1
                    if (lastLoc != null &&
                        lastLoc.segment == position.chapterIndex &&
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
                                    position.chapterIndex + 1
                                )
                            )
                        if (nextStart >= 0 &&
                            position.lastVisibleIndex >= nextStart
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
                    position.chapterIndex ==
                    anchorChapterIndexNow &&
                    position.fraction >= 1f / 3f &&
                    wallThirdFillAnchor !=
                    anchorChapterIndexNow
                ) {
                    wallThirdFillAnchor =
                        anchorChapterIndexNow
                    chapters.getOrNull(
                        anchorChapterIndexNow + 1
                    )?.let { onSegmentVisible(it.id) }
                }
            }
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
            // below (the position report, the crossing
            // check) reads chapters-space.
            val loc = if (wallActive) {
                rawLoc.copy(segment = rawLoc.segment + rangeStart)
            } else {
                rawLoc
            }
            val baseReport: DocumentReport? = when (loc.block) {
                SegmentIndex.STATE_ITEM -> {
                    // B1's acceptance contract: a textless
                    // first-visible segment is NEVER named as
                    // a position. Moon+ full copy: the boundary
                    // posture that used to name the PREVIOUS
                    // segment at its end here is DELETED with
                    // the rest of the inference layer — a state
                    // item is a DISPLAY state (the park while a
                    // fill is in flight), never a position
                    // source. The approach door owns the
                    // report: it requests the segment's fill
                    // and writes nothing else.
                    chapters.getOrNull(loc.segment)?.let {
                        DocumentReport.Approach(it.id)
                    }
                }
                SegmentIndex.HEADER -> DocumentReport.Position(
                    loc.segment, 0, 0f, last.index
                )
                else -> {
                    // The named segment is the one whose TEXT
                    // is on screen, and the position is
                    // computed inside it alone. The reporter
                    // no longer renames the segment (the F7
                    // resolve() call is gone from the report
                    // path): a segment with zero blocks
                    // composes no block items, so a block can
                    // only ever be first-visible in the
                    // segment that holds it — and an empty
                    // segment's header, when it has one, is
                    // the HEADER branch above.
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
            // Moon+ full copy: the report IS the base report.
            // The two rewrites that used to stand here are
            // DELETED with the inference layer they served:
            // the B4 last-visible rewrite (which renamed the
            // report to a LATER segment's start, from the
            // screen's bottom edge) and the generation guard
            // (which dropped reports naming another chapter
            // while a deliberate restore's generation was
            // unconsumed). Both existed only to negotiate
            // between scroll observation and the anchor;
            // under the copy there is nothing to negotiate —
            // the handler below delivers a report as the
            // anchor's own position, as a verified crossing,
            // or not at all.
            baseReport
        }
            .distinctUntilChanged()
            .debounce(180L)
            .collectLatest { report ->
                when (report) {
                    is DocumentReport.Position -> {
                        val anchor = anchorChapterIndexNow
                        when {
                            // The anchor's own place: the
                            // one-way derivation.
                            report.chapterIndex == anchor -> {
                                deliverPosition(report)
                            }
                            // A crossing candidate: fire the
                            // door only if the viewport, read
                            // live NOW, really stands in or
                            // past the named segment. The door
                            // moves the anchor first, so the
                            // position that follows lands as
                            // the new anchor's own place.
                            // Never in wall mode: the wheel
                            // owns crossings there, so a
                            // non-anchor report in wall mode
                            // is stale by construction and
                            // drops like any other.
                            !wallActive &&
                                crossingConfirmed(
                                    report.chapterIndex, anchor
                                ) -> {
                                onChapterCrossed(report.chapterIndex)
                                deliverPosition(report)
                            }
                            // Stale: a pre-jump reading
                            // report, or the park on a
                            // neighbour's state item. Dropped
                            // — the next settled report
                            // carries the anchor's own place.
                            else -> {
                            }
                        }
                    }
                    is DocumentReport.Approach -> {
                        onSegmentApproached(report.chapterId)
                    }
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
        // Code 117 (§5.7): a live selection drag stands the
        // drift down — a drag and an auto-scroll must never
        // drive the list in opposite directions in one frame.
        if (!config.autoScrollEnabled || autoPaused ||
            selectionController.dragSessionState.value != null
        ) return@LaunchedEffect
        val velocity = AutoScrollController.velocityPxPerSecond(config.autoScrollSpeed)
        while (true) {
            delay(100L)
            // §5.7, per tick: a session that begins mid-drift
            // parks the drift until it settles.
            if (selectionController.dragSessionState.value != null) {
                continue
            }
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
        // Code 117 (§5.7): Listen's follow stands down while a
        // selection drag owns the list; the next spoken range
        // follows as usual once the drag settles.
        if (selectionController.dragSessionState.value != null) {
            return@LaunchedEffect
        }
        val text = textOf(anchorChapterIndex) ?: return@LaunchedEffect
        val blockIndex = text.blockIndexAt(spokenRange.first)
        if (blockIndex < 0) return@LaunchedEffect
        val flat = index.flatIndexOf(
            indexSegment(anchorChapterIndex), blockIndex
        )
        if (flat >= 0) {
            listState.animateScrollToItem(flat)
        }
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
            if (flat >= 0) {
                listState.animateScrollToItem(flat)
            }
            return@LaunchedEffect
        }
    }

    // ── Code 117: the selection's scroll rules (§5.6, §3.4) ──
    // User scroll dismisses: the rising edge of
    // isScrollInProgress with a live selection and no drag
    // session clears the model — user scrolls and Listen's
    // follow-scroll alike (§9.6). Edge auto-scroll during a
    // drag is exempt by the drag-session condition, not by a
    // special case.
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .distinctUntilChanged()
            .collect { scrolling ->
                if (scrolling &&
                    selectionController.dragSessionState.value == null &&
                    (selectionController.selectionState.value != null ||
                        selectionController.existingHighlightState.value != null)
                ) {
                    selectionController.clearSelection()
                    // Code 118: the existing-highlight bar
                    // dies on scroll too — its seat geometry
                    // leaves with the composed blocks (the
                    // PDF bodies clear theirs on page change).
                    selectionController.clearExistingHighlight()
                }
            }
    }
    // §3.4: the anchor chapter changing, by ANY route — wall
    // swap, Listen's chapter advance, segment-set replacement,
    // a Contents jump to another chapter — clears the model.
    // One explicit effect (the selection state is `remember`
    // and would otherwise survive the change holding the old
    // chapter's offsets); Revision 2 D3: the clear is the one
    // operation, drag session included.
    LaunchedEffect(anchorChapterIndex) {
        selectionController.clearSelection()
        selectionController.clearExistingHighlight()
    }
    // §4.1: a typography/config change that re-lays-out text
    // clears — keyed on the layout inputs to DocumentBlock.
    LaunchedEffect(
        config.fontSizeSp, config.lineHeightMultiplier,
        config.paragraphSpacingDp, config.marginHorizontalDp,
        config.bionicReading, fontFamily
    ) {
        selectionController.clearSelection()
        selectionController.clearExistingHighlight()
    }
    // §7.4: the back chain's clear nonce performs a REAL
    // clear — tint, handles and bar die together. (Code 116's
    // nonce could only hide the bar; the framework's tint and
    // handles stood. That half-dismissal cannot recur: there
    // is one state and it is null.) Code 118: the nonce
    // clears the existing-highlight episode by the same door.
    LaunchedEffect(clearSelectionSignal) {
        if (clearSelectionSignal > 0) {
            selectionController.clearSelection()
            selectionController.clearExistingHighlight()
        }
    }
    // §5.6: edge auto-scroll while a drag session lives —
    // hosted in its own composable scope so this body never
    // reads the session state in composition.
    SelectionEdgeAutoScroll(
        controller = selectionController,
        listState = listState
    )

    // ── Seam patch + MR-2 Part S: the wall counts the ──
    // finger, not the pipeline.
    //
    // The wall has NO overscroll machinery and no visual
    // give (B2 stands): the column ends at the anchor
    // chapter's end — a REAL end, blank below the last
    // line, no stretch.
    //
    // The seam build accumulated the wheel's drag from a
    // NestedScrollConnection's post-scroll `available` —
    // the LazyColumn pipeline's leftovers. The owner's
    // seam proof convicted it: in real pulls the arc
    // filled only ~⅓ of what the same pull fills in
    // Moon+, and no release crossed. Moon+ counts
    // `noScrollDist` from the RAW TOUCH STREAM while its
    // ScrollView stands pinned (REA §(b)); MR-2 moves the
    // accumulation into the release observer's Initial-
    // pass pointer stream (below), and the connection is
    // deleted entirely:
    //
    // - ONE pointer is tracked by id (A2) — the first
    //   pressed change of the gesture; only its
    //   `positionChange().y` counts (A3: no stored
    //   positions — the change's own delta, never a
    //   difference of two remembered points). If the
    //   tracked pointer lifts while another remains
    //   pressed, accumulation stops for the gesture; the
    //   swap check still runs on the all-released
    //   transition, exactly as before.
    // - There is NO timing fence and no gesture
    //   disqualification of any kind (MR-2 fix round 1 —
    //   Moon+ has none, REA §(b)): how a gesture BEGAN
    //   never gates what it accumulates. The observer
    //   consumes nothing, so selection keeps its own
    //   handlers in the text layer, untouched by this
    //   stream.
    // - Accumulation counts only while the list stands
    //   PINNED at an end (Moon+'s 2 dp tolerance, in
    //   px), and it is the CURRENT overscroll amount,
    //   signed (Moon+'s `noScrollDist`): pinned at the
    //   end (with a next chapter) every delta moves the
    //   fill — `dy < 0` deepens it toward NEXT
    //   (negative), `dy > 0` relieves it back toward
    //   zero, clamped at zero; pinned at the start
    //   (with a previous chapter) the mirror image
    //   toward PREVIOUS (positive). Pinned at NEITHER
    //   end the list scrolls again and the fill is
    //   zero — the accumulation never survives into
    //   scrolling, so the fill always tracks the
    //   finger. A chapter shorter than the viewport
    //   pins at BOTH ends at once; the dy sign decides,
    //   and the book's own ends still suppress their
    //   side.
    // - Programmatic scrolls (the auto-scroll drift,
    //   restores, follows) produce NO pointer events, so
    //   they can never fill the wheel (B9 by
    //   construction); a FLING never crosses either —
    //   momentum after the release moves no pointer, and
    //   the crossing waits for the release below.
    //
    // THE RELEASE is the same observer's other half (the
    // seam build's A6): it notices the moment the LAST
    // pressed pointer lifts — an event that exists for
    // every release, fast, slow, or a dead stop at zero
    // velocity. On a release the wheel state is ALWAYS
    // reset (an un-armed release just hides the wheel;
    // nothing changes); the swap fires only when the
    // wheel stands ARMED — a full circle of accumulated
    // drag (60 dp at the REA's 6°/dp) — the wall is
    // active, and no image zoom owns the gesture.
    //
    // Fences (B10): a pinch/pan zoom in progress
    // (zoomLockedItems non-empty) freezes accumulation —
    // the drag belongs to the image. The observer
    // consumes NOTHING, so taps, scrolls, selection and
    // image zooms flow through untouched, and it is
    // inert unless the wall is active (the accumulation
    // checks wallActiveNow per event).
    // (The density is read into a local first: inside
    // pointerInput the scope IS a Density, whose own
    // `density` property would shadow the composition's.)
    val wallDensity = density
    val wallPinPx = 2f * wallDensity.density
    val wallReleaseObserver = Modifier.pointerInput(Unit) {
        awaitPointerEventScope {
            var anyPressed = false
            // Part S gesture tracking (A2/A3 bookkeeping
            // only — never a gate): the ONE tracked
            // pointer, by id. It resets on the
            // all-released transition, after the swap
            // check.
            var trackedId: PointerId? = null
            while (true) {
                val event = awaitPointerEvent(
                    PointerEventPass.Initial
                )
                val pressedNow = event.changes.any { it.pressed }
                if (!anyPressed && pressedNow) {
                    // Gesture start: acquire the first
                    // pressed change as the tracked pointer.
                    trackedId = event.changes
                        .firstOrNull { it.pressed }?.id
                }
                // Accumulation (Part S): the tracked
                // pointer's own movement delta, counted
                // only while the wall owns the gesture.
                val tracked = trackedId?.let { id ->
                    event.changes.firstOrNull { it.id == id }
                }
                if (tracked != null && !tracked.pressed && pressedNow) {
                    // A2: the tracked pointer lifted while
                    // another remains pressed — accumulation
                    // stops for the rest of the gesture (the
                    // release check below still runs when
                    // the last pointer lifts).
                    trackedId = null
                } else if (tracked != null) {
                    val dy = tracked.positionChange().y
                    if (dy != 0f &&
                        wallActiveNow && zoomLockedItems.isEmpty() &&
                        // Code 117 (§5.7): the wheel's
                        // accumulation stands down while a
                        // selection drag owns the pointer —
                        // with accumulation disarmed, dragPx
                        // stays 0, so the release branch below
                        // cannot swap chapters off a selection
                        // drag either.
                        selectionController.dragSessionState.value == null
                    ) {
                        val layout = listState.layoutInfo
                        val pinnedAtStart =
                            layout.totalItemsCount > 0 &&
                                listState.firstVisibleItemIndex == 0 &&
                                listState.firstVisibleItemScrollOffset <=
                                    wallPinPx
                        val lastVisible = layout.visibleItemsInfo.lastOrNull()
                        val pinnedAtEnd = lastVisible != null &&
                            layout.totalItemsCount > 0 &&
                            lastVisible.index ==
                                layout.totalItemsCount - 1 &&
                            !listState.canScrollForward
                        // Moon+'s `noScrollDist` (REA §(b),
                        // MR-2 fix round 1): the SIGNED
                        // current overscroll — while a pin
                        // holds, every delta moves the fill
                        // (dragging back relieves it toward
                        // zero, clamped at zero); while
                        // neither pin holds the list scrolls
                        // again and the fill is zero.
                        val current = wheelState.dragPx
                        wheelState.dragPx = when {
                            pinnedAtEnd && pinnedAtStart ->
                                when {
                                    // A chapter shorter
                                    // than the viewport
                                    // holds BOTH pins; the
                                    // drag's own sign picks
                                    // the side, as before.
                                    dy < 0f &&
                                        hasNextChapterNow ->
                                        min(0f, current + dy)
                                    dy > 0f &&
                                        hasPreviousChapterNow ->
                                        max(0f, current + dy)
                                    else -> current
                                }
                            pinnedAtEnd ->
                                if (hasNextChapterNow) {
                                    min(0f, current + dy)
                                } else {
                                    current
                                }
                            pinnedAtStart ->
                                if (hasPreviousChapterNow) {
                                    max(0f, current + dy)
                                } else {
                                    current
                                }
                            else -> 0f
                        }
                    }
                }
                if (anyPressed && !pressedNow) {
                    val drag = wheelState.dragPx
                    wheelState.dragPx = 0f
                    trackedId = null
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

    // Code 117: the code-116 selection-episode anchor
    // (selectionAnchor / selectionEpisodeOpen /
    // onBlockLongPressed) is DELETED with the framework
    // manager it served (§10, §15). The bar's passage arms
    // now fulfil against the model's own range (§8) — there
    // is no anchor separate from the selection that could
    // flap to null mid-episode (S1's mechanism).

    Box(
        modifier
            .fillMaxSize()
            // Initial-pass tap observation — the centre tap
            // toggles the chrome even when it lands on text.
            // The tap also drives auto-scroll's pause/resume
            // contract, as on the retired surfaces.
            .readerTapToggle(
                chapters, selectionController.tapAwayGate
            ) { xFraction, _ ->
                // Code 118: a plain tap that reaches the
                // toggle (non-prose ground — taps over prose
                // are the tap tracker's, and stand the toggle
                // down through the gate) also retires the
                // existing-highlight bar: any tap off the
                // highlight retires it, as on the PDF
                // surfaces.
                selectionController.clearExistingHighlight()
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
            // Code 117 (§5.3): the root's own window offset —
            // every window-coordinate conversion in the
            // selection geometry reads it — and the standing
            // Main-pass drag host loop. The root is an ancestor
            // of every block, is in the entry pointer's hit
            // path from its down, and is never disposed while
            // the reader lives.
            .onGloballyPositioned { coordinates ->
                selectionController.rootWindowOffset =
                    coordinates.positionInWindow()
            }
            .pointerInput(selectionController) {
                selectionDragHostLoop(selectionController)
            }
    ) {
        ReaderSelectionLayer(
            controller = selectionController,
            selectionText = selectionText,
            onSelectionAction = onSelectionAction,
            onRangeAction = onRangeAction,
            onApplyHighlight = onApplyHighlight,
            onUpdateHighlightColor = onUpdateHighlightColor,
            onRemoveHighlight = onRemoveHighlight,
            onEditHighlightNote = onEditHighlightNote,
            onSelectionOpenChanged = onSelectionOpenChanged,
            motionEnabled = motionEnabled
        ) {
            LazyColumn(
                state = listState,
                // Code 117 (§5.3): the drag scroll lock — the
                // same channel zoomLockedItems uses. While a
                // drag session lives the column does not
                // scroll; the root host consumes the drag's
                // moves besides. Either alone would do; both
                // are specified (§5.3).
                userScrollEnabled = zoomLockedItems.isEmpty() &&
                    selectionController.dragSessionState.value == null,
                contentPadding = PaddingValues(
                    start = config.marginHorizontalDp.dp,
                    end = config.marginHorizontalDp.dp,
                    top = config.marginVerticalDp.dp + topInset,
                    bottom = config.marginVerticalDp.dp + bottomInset
                ),
                // MR-2 Part S: no nested-scroll connection
                // in ANY mode — the wall's accumulation lives
                // in the release observer's raw pointer
                // stream (above), and there is no visual give
                // to apply (B2: the end is real). The chain
                // is exactly fillMaxSize, as non-wall always
                // was.
                modifier = Modifier.fillMaxSize()
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
                                fontFamily = fontFamily,
                                config = config
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
                                    selectionController = selectionController,
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
        } // ReaderSelectionLayer's content lambda (the LazyColumn)
        // Code 117 (§6.3): the selection overlay — composed
        // after the LazyColumn, before the bar Popup (a
        // separate window; the overlay's recognisers never
        // see bar taps). Exists only while a selection lives.
        ReaderSelectionOverlay(
            controller = selectionController,
            listState = listState
        )
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
 * Code 117: the bar layer's composition scope. This wrapper
 * is the ONE place in the reader that reads the selection
 * state in composition — the host gates the bar Popup on it —
 * so a drag frame recomposes this small scope and the
 * overlay, never DocumentReader's body and never a
 * DocumentBlock (§6.4's budget).
 */
@Composable
private fun ReaderSelectionLayer(
    controller: ReaderSelectionController,
    selectionText: (ReaderTextSelection) -> String,
    onSelectionAction: (ReaderSelectionAction, String) -> Unit,
    onRangeAction: (selection: ReaderTextSelection, action: ParagraphAction) -> Unit,
    onApplyHighlight: (selection: ReaderTextSelection, tintArgb: Int) -> Unit,
    onUpdateHighlightColor: (highlight: Highlight, tintArgb: Int) -> Unit,
    onRemoveHighlight: (highlight: Highlight) -> Unit,
    onEditHighlightNote: (highlight: Highlight) -> Unit,
    onSelectionOpenChanged: (Boolean) -> Unit,
    motionEnabled: Boolean,
    content: @Composable () -> Unit
) {
    val selection = controller.selectionState.value
    // Code 118: the existing-highlight episode, read in the
    // same sanctioned scope as the model. Mutual exclusion
    // (the PDF bodies' reset rule): a live selection
    // supersedes the existing bar the moment it exists.
    val existing = controller.existingHighlightState.value
    LaunchedEffect(selection) {
        if (selection != null) controller.clearExistingHighlight()
    }
    ReaderSelectionMenuHost(
        selection = selection,
        existingHighlight = existing,
        selectionRect = {
            controller.selectionRect()
                ?: controller.existingHighlightRect()
                ?: Rect.Zero
        },
        selectionText = {
            selection?.let(selectionText) ?: ""
        },
        onAction = onSelectionAction,
        onRangeAction = { action ->
            // The arms fulfil against the model's range at
            // tap time (§8) — the belt over the bar's own
            // gating (it composes only while a selection lives).
            selection?.let { onRangeAction(it, action) }
        },
        onApplyHighlight = { tintArgb ->
            // Code 118: the tint fulfils against whichever
            // episode is live — the model's range (a new
            // highlight in that colour) or the stored row
            // (a re-tint).
            val live = controller.selectionState.value
            val stored = controller.existingHighlightState.value
            if (live != null) {
                onApplyHighlight(live, tintArgb)
            } else if (stored != null) {
                onUpdateHighlightColor(stored, tintArgb)
            }
        },
        onRemoveHighlight = {
            controller.existingHighlightState.value
                ?.let(onRemoveHighlight)
        },
        onEditExistingNote = onEditHighlightNote,
        onClearExisting = { controller.clearExistingHighlight() },
        onSelectAll = {
            // §7.3: the model becomes the whole chapter the
            // selection STARTED in; the bar re-seats over the
            // widened range and does not dismiss.
            val current = controller.selectionState.value
            val text = current?.let {
                controller.chapterTextOf(it.startChapter)
            }
            if (current != null && text != null && text.length > 0) {
                controller.selectionState.value = ReaderTextSelection(
                    current.startChapter, 0,
                    current.startChapter, text.length
                )
            }
        },
        onClearSelection = { controller.clearSelection() },
        onSelectionOpenChanged = onSelectionOpenChanged,
        motionEnabled = motionEnabled,
        content = content
    )
}

/**
 * Code 117 (§5.6): edge auto-scroll while a drag session
 * lives. Composition-hosted here — never inside a list item —
 * so the origin block's disposal cannot kill it (§5.1). Per
 * frame: pressure = penetration into the 48 dp band / 48 dp;
 * velocity = pressure × 480 dp/s; scroll by velocity × frame
 * delta; then re-resolve the following edge from the
 * session's last recorded finger position — the finger is
 * stationary, the content moved — and write the range if it
 * changed. The composed frontier is the honest bound (§5.6):
 * the edge can only point into composed blocks; the §4.2
 * clamp pins it at the frontier and it advances per frame.
 */
@Composable
private fun SelectionEdgeAutoScroll(
    controller: ReaderSelectionController,
    listState: androidx.compose.foundation.lazy.LazyListState
) {
    val session = controller.dragSessionState.value
    val density = LocalDensity.current
    LaunchedEffect(session) {
        val active = session ?: return@LaunchedEffect
        val bandPx = with(density) { SELECTION_EDGE_BAND_DP.dp.toPx() }
        val maxVelocityPx = with(density) {
            SELECTION_EDGE_MAX_DP_PER_SECOND.dp.toPx()
        }
        var lastMillis = -1L
        while (true) {
            val frameMillis = withFrameMillis { it }
            if (controller.dragSessionState.value !== active) break
            val dtMillis = if (lastMillis < 0L) {
                0L
            } else {
                frameMillis - lastMillis
            }
            lastMillis = frameMillis
            if (dtMillis <= 0L) continue
            val info = listState.layoutInfo
            val viewportTop = controller.rootWindowOffset.y +
                info.viewportStartOffset
            val viewportBottom = controller.rootWindowOffset.y +
                info.viewportEndOffset
            val fingerY = active.lastFingerWindow.y
            val direction: Float
            val pressure: Float
            when {
                fingerY < viewportTop + bandPx -> {
                    direction = -1f
                    pressure = ((viewportTop + bandPx - fingerY) / bandPx)
                        .coerceIn(0f, 1f)
                }
                fingerY > viewportBottom - bandPx -> {
                    direction = 1f
                    pressure = ((fingerY - (viewportBottom - bandPx)) / bandPx)
                        .coerceIn(0f, 1f)
                }
                else -> {
                    direction = 0f
                    pressure = 0f
                }
            }
            if (pressure > 0f) {
                val delta = direction * pressure * maxVelocityPx *
                    (dtMillis / 1000f)
                listState.scroll { scrollBy(delta) }
                val next = controller.advanceDrag(
                    active, active.lastFingerWindow
                )
                if (next != null &&
                    next != controller.selectionState.value
                ) {
                    controller.selectionState.value = next
                }
            }
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
 * item belongs to a textless segment — B1's acceptance
 * contract routes it to the approach door, never to a
 * position). */
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
 * real chapter heading in the reading font (1.5× the body
 * size, bold, centred — the same treatment a book's own
 * in-flow heading gets), generous space above, modest
 * space below. The eyebrow
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
    fontFamily: FontFamily,
    config: ReaderConfig
) {
    // v1.7 (code 106): the app line carries the SAME title
    // treatment as a book's own in-flow heading — the
    // Heading branch of blockTextStyle at Moon+'s h2 level:
    // the reading font at 1.5× the body size, bold, centred,
    // line height following the body's multiplier — so a
    // chapter's name looks the same whether the book
    // printed it or the app did ("same to same").
    val titleSize = config.fontSizeSp * 1.5f
    Text(
        title,
        style = TextStyle(
            fontFamily = fontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = titleSize.sp,
            lineHeight = (titleSize * config.lineHeightMultiplier).sp,
            textAlign = TextAlign.Center
        ),
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
    /** Code 117: the selection model + registry + recogniser
     * target (§3–§5) — the prose branch captures its layout
     * into the registry, hosts the entry recognisers, and
     * carries the §9.1 semantics action, all through here. */
    selectionController: ReaderSelectionController,
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
        // D13: Moon+'s footnote box — the notes this
        // chapter's paragraphs referenced, inlined at
        // prepare time. Rendered whole (it is a fixed
        // block in the chapter's text coordinate system,
        // like an image); its own text carries no marks.
        // MR-4 (R7): a pagebreak marker renders as nothing —
        // it is a split signal for the MOBI flow, consumed by
        // the splitter; this branch is the belt-and-braces for
        // one that somehow lands in a chapter.
        is ChapterBlock.PageBreak -> Unit
        is ChapterBlock.FootnoteBox -> FootnoteBoxView(
            block = block,
            config = config,
            palette = palette,
            fontFamily = fontFamily,
            assetFile = assetFile,
            modifier = Modifier.fillMaxWidth(),
            onZoomActiveChanged = onZoomActiveChanged
        )
        else -> {
            // Remembered per block — keyed by the block, its
            // range, the highlights and the rendering inputs
            // (bionic, palette, and the spoken/overlay washes).
            // Code 118: the stored wash is each highlight's
            // OWN colour — its stored token mapped through
            // the bar's row tints, at the shipped wash alpha.
            // (Code 117 painted every highlight in the single
            // accent wash and never read the token: on a
            // Custom palette the accent is the ink colour, so
            // every wash rendered grey — CONVICTION-CODE117
            // items 1–2.)
            val highlightColor = palette.accent.copy(alpha = 0.38f)
            val spokenColor = palette.accent.copy(alpha = 0.30f)
            val overlayColor = palette.accent.copy(alpha = 0.20f)
            // Code 120 F1: the design's selection tint, read in
            // composition and keyed into the remember below —
            // a stored "scheme" highlight (written only under
            // Material 3, for the design-default tint) washes
            // in this colour at the site's uniform alpha.
            // Under Nocturne the local holds the ReaderViolet
            // value, whose 0.38 wash is exactly what the token
            // fallback renders, so the wash cannot change.
            val selectionTint =
                app.xreader.ui.theme.LocalSelectionTint.current
            val backgrounds = remember(
                block, range.start, range.end, highlights,
                spokenRange, overlayActive,
                highlightColor, spokenColor, overlayColor,
                selectionTint
            ) {
                blockBackgrounds(
                    blockStart = range.start,
                    blockEnd = range.end,
                    highlightRanges = highlights.map { it.startOffset to it.endOffset },
                    spokenRange = spokenRange,
                    overlayActive = overlayActive,
                    highlightColor = highlightColor,
                    spokenColor = spokenColor,
                    overlayColor = overlayColor,
                    highlightColors = highlights.map { highlight ->
                        if (highlight.color == "scheme") {
                            selectionTint.copy(alpha = 0.38f)
                        } else {
                            Color(
                                highlightTintForToken(highlight.color)
                            ).copy(alpha = 0.38f)
                        }
                    }
                )
            }
            val annotated = remember(block, config.bionicReading, backgrounds) {
                annotatedForBlock(
                    block = block,
                    bionic = config.bionicReading,
                    backgrounds = backgrounds
                )
            }
            // Code 117 (§10.2): the prose block's
            // combinedClickable is DELETED — its unconditional
            // click consumption was one of S2's door-shutters,
            // and its long-click wrote the episode anchor that
            // no longer exists. In its place (§5.1/§5.2): the
            // §4.1 registry capture (layout + the two origins),
            // the two entry pointerInputs, and the §9.1
            // semantics action. The block stays a plain Text —
            // with no SelectionContainer above it, absence of
            // the container is the non-selectable mechanism.
            val registryKey = chapterIndex to blockIndex
            DisposableEffect(registryKey) {
                onDispose {
                    selectionController.registry.remove(registryKey)
                }
            }
            val tapPairing = remember { SelectionTapPairing() }
            val blockDensity = LocalDensity.current
            Text(
                annotated,
                style = blockTextStyle(block, config, fontFamily),
                color = palette.text,
                onTextLayout = { layout ->
                    selectionController.registry.putLayout(
                        registryKey, layout
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned { coordinates ->
                        selectionController.registry.putPosition(
                            key = registryKey,
                            nodeOffset = coordinates.positionInWindow(),
                            size = coordinates.size,
                            // §4.1's text origin: the branch's
                            // own top padding, read from the
                            // block type — the same constant
                            // the padding below applies.
                            topInsetPx = if (
                                block is ChapterBlock.Heading
                            ) {
                                with(blockDensity) { 16.dp.toPx() }
                            } else {
                                0f
                            }
                        )
                    }
                    .pointerInput(registryKey) {
                        selectionEntryLongPressLoop(
                            selectionController, chapterIndex, blockIndex
                        )
                    }
                    .pointerInput(registryKey) {
                        selectionTapTracker(
                            selectionController, chapterIndex,
                            blockIndex, tapPairing
                        )
                    }
                    .semantics {
                        // §9.1 (Revision 1's pinned form): the
                        // accessibility floor — TalkBack's
                        // creation path, paragraph granularity.
                        customActions = listOf(
                            CustomAccessibilityAction(
                                "Select paragraph"
                            ) {
                                if (range.end > range.start) {
                                    selectionController
                                        .selectionState.value =
                                        ReaderTextSelection(
                                            chapterIndex, range.start,
                                            chapterIndex, range.end
                                        )
                                    true
                                } else {
                                    false
                                }
                            }
                        )
                    }
                    .padding(
                        // Stage 2's inset authority: a
                        // heading's rendered top padding
                        // is THIS 16.dp (body blocks: 0).
                        top = if (block is ChapterBlock.Heading) 16.dp else 0.dp,
                        bottom = config.paragraphSpacingDp.dp
                    )
            )
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

/**
 * Code 118: the tap hit-test for stored highlights — the
 * core reader's mirror of the PDF geometry overlay's
 * highlight tap, run over the selection registry (§4.1).
 * A window point resolves through the composed block whose
 * NODE bounds contain it (bounds tests use the node origin,
 * as §4.1 orders), then through that block layout's own
 * line geometry: the point must sit on the resolved char's
 * line, within the line's ink extent — a tap in the
 * paragraph gap below a highlighted line, or past a short
 * line's end, hits nothing. The resolved chapter offset is
 * then tested against the chapter's stored highlights.
 * Returns the highlight and its chapter index, or null.
 * One-shot at a gesture's down/up — never per frame.
 */
private fun storedHighlightAt(
    controller: ReaderSelectionController,
    chapters: List<Chapter>,
    highlights: List<Highlight>,
    windowPoint: Offset
): Pair<Highlight, Int>? {
    if (highlights.isEmpty()) return null
    for ((key, entry) in controller.registry.all()) {
        val layout = entry.layout ?: continue
        if (!entry.positioned) continue
        if (windowPoint.x < entry.nodeOffset.x ||
            windowPoint.x > entry.nodeOffset.x + entry.size.width ||
            windowPoint.y < entry.nodeOffset.y ||
            windowPoint.y > entry.nodeOffset.y + entry.size.height
        ) {
            continue
        }
        val chapterText = controller.chapterTextOf(key.first) ?: continue
        val range = chapterText.rangeOf(key.second) ?: continue
        if (range.end <= range.start) continue
        val local = windowPoint - entry.textOrigin
        val localOffset = layout.getOffsetForPosition(local)
        val line = layout.getLineForOffset(localOffset)
        if (local.y < layout.getLineTop(line) ||
            local.y > layout.getLineBottom(line)
        ) {
            continue
        }
        if (local.x < layout.getLineLeft(line) ||
            local.x > layout.getLineRight(line)
        ) {
            continue
        }
        val chapterOffset = range.start + localOffset
        val chapterId = chapters.getOrNull(key.first)?.id ?: continue
        val hit = highlights.firstOrNull { highlight ->
            highlight.chapterId == chapterId &&
                chapterOffset >= highlight.startOffset &&
                chapterOffset < highlight.endOffset
        }
        if (hit != null) return hit to key.first
    }
    return null
}
