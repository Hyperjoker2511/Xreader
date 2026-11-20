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
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
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
import kotlin.math.abs
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Actions offered on a long-pressed paragraph (passage tools, §2.4 #7).
 * v1.7 one-engine (C1): relocated to the unified scroll core from the
 * retired ScrollReader.kt; the document surface is its only
 * definition site now. */
enum class ParagraphAction { HIGHLIGHT, NOTE, BOOKMARK }

/**
 * Part 3: the chapter-end wall's feel constants — ALL of
 * them in this one object, so a feel fix round tunes the
 * wall from a single place (the spec's named requirement).
 * Distances are raw pixels unless the name says otherwise;
 * the commit distance is a Dp converted at the use site.
 */
private object WallFeel {
    /** How much of the accumulated overscroll the content
     * visually gives — the wall's resistance. */
    const val RESISTANCE = 0.45f
    /** The overscroll ceiling (px): past this the drag is
     * still consumed but the wall gives no more. */
    const val MAX_OVERSCROLL_PX = 220f
    /** The release distance that commits a crossing. */
    val COMMIT_DISTANCE = 64.dp
    /** A fling faster than this (px/s) thrown at an edge
     * commits on the fling alone. */
    const val FLING_VELOCITY = 800f
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
    /** Part 3: the chapter-end wall is live for this book
     * (Chapter-by-chapter + the owner's wall scope, decided
     * by ReaderScreen): the surface composes ONLY the anchor
     * segment, and a committed pull past its end (or start)
     * crosses via [onWallCross]. */
    wallActive: Boolean = false,
    /** Part 3: a committed wall crossing — true crosses to
     * the next chapter, false to the previous. */
    onWallCross: (next: Boolean) -> Unit = {},
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

    // ── Wall mode: local index space (Part 3, A9) ─────────
    //
    // When the wall is active the surface composes ONLY the
    // anchor segment, so a second SegmentIndex stands over
    // that one segment alone (no header — pinned mode has
    // none — and its block count or STATE, the same shape
    // rule the book-wide index uses). Every consumer below
    // reads through `index` and translates its segment
    // argument through `indexSegment`:
    //
    // - initialFlat / restore stages 1+2: flatIndexOf(
    //   indexSegment(anchor), …) — the anchor IS local
    //   segment 0;
    // - the reporter: locate() yields local segment 0,
    //   translated ONCE into chapters-space before ANY use
    //   (the position report, the STATE_ITEM boundary math,
    //   StreamReadingPosition.resolve — the ViewModel never
    //   sees a local index); the end-of-book branch's last
    //   segment is the anchor, not segmentCount − 1;
    // - Item 4's pinned block: the boundary prefetch (a)
    //   fires as today; the finished report (b) NEVER fires
    //   in wall mode — the wall commit is the only
    //   finished-marking door (A10);
    // - auto-scroll's halt check: form unchanged (locate →
    //   STATE_ITEM), reading the local index; the drift's
    //   wall meeting is A11's branch beside it;
    // - spoken follow: flatIndexOf(indexSegment(anchor), …)
    //   — the spoken range is anchor-space by contract;
    // - overlay follow: only the anchor segment is eligible
    //   in wall mode (nothing else is composed); other
    //   segments are skipped, never translated.
    //
    // When the wall is off, `index` IS segmentIndex and
    // `indexSegment` is the identity — every path below is
    // then exactly the pre-wall code.
    val wallIndex = remember(
        chapters, anchorChapterIndex, segmentStates, segmentTexts
    ) {
        SegmentIndex(
            listOf(
                SegmentIndex.Shape(
                    hasHeader = false,
                    blockCount = if (
                        stateOf(anchorChapterIndex) ==
                        SegmentState.RESIDENT
                    ) {
                        textOf(anchorChapterIndex)
                            ?.content?.blocks?.size
                            ?: SegmentIndex.STATE
                    } else {
                        SegmentIndex.STATE
                    }
                )
            )
        )
    }
    val index = if (wallActive) wallIndex else segmentIndex
    fun indexSegment(segment: Int): Int =
        if (wallActive) 0 else segment

    // The wall's live reads for the never-restarting
    // callbacks (key turns, the overscroll connection): they
    // must see the CURRENT composition's values, not the
    // values of the composition that created them.
    val anchorResidentNow by rememberUpdatedState(
        stateOf(anchorChapterIndex) == SegmentState.RESIDENT
    )
    val wallActiveNow by rememberUpdatedState(wallActive)
    val hasNextChapterNow by rememberUpdatedState(
        anchorChapterIndex < chapters.lastIndex
    )
    val hasPreviousChapterNow by rememberUpdatedState(
        anchorChapterIndex > 0
    )

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
                // Wall mode: the anchor is the only composed
                // segment — it IS the last segment here.
                val lastSegment = if (wallActive) {
                    anchorChapterIndex
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
            // Wall mode: translate the local segment (0) into
            // chapters-space ONCE — every use below (the
            // position, the STATE_ITEM boundary math,
            // StreamReadingPosition.resolve) reads
            // chapters-space.
            val loc = if (wallActive) {
                rawLoc.copy(segment = anchorChapterIndex)
            } else {
                rawLoc
            }
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
                        // finished). Part 3: in wall mode (a)
                        // still fires — it is the prefetch
                        // door — and (b) NEVER fires: the wall
                        // commit is the only finished-marking
                        // door there (A10).
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
                                    segment = anchorChapterIndex
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
            // Part 3 (A11): a key turn AT the wall crosses
            // it — the keypress is the deliberate pull,
            // standing in for the drag. Only from a RESIDENT
            // anchor (a turn onto an unfilled segment asserts
            // nothing) and never past the book's ends; the
            // wall inputs are read through their updated-state
            // holders because this effect never restarts.
            if (wallActiveNow && anchorResidentNow) {
                if (direction > 0 &&
                    !listState.canScrollForward &&
                    hasNextChapterNow
                ) {
                    onWallCross(true)
                    return@collectLatest
                }
                if (direction < 0 &&
                    !listState.canScrollBackward &&
                    hasPreviousChapterNow
                ) {
                    onWallCross(false)
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
                // Part 3 (A11): the drift meeting the wall —
                // it can scroll no further, the anchor is
                // resident and a next chapter exists: pause
                // (autoPaused is per-anchor, so the landing
                // chapter starts unpaused) and cross ONCE.
                // Landing on an unfilled segment then halts
                // by the state-item rule above, exactly as
                // the spec orders.
                if (wallActive &&
                    !listState.canScrollForward &&
                    stateOf(anchorChapterIndex) ==
                    SegmentState.RESIDENT &&
                    anchorChapterIndex < chapters.lastIndex
                ) {
                    autoPaused = true
                    onWallCross(true)
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
            // Wall mode: only the anchor segment is composed
            // — other segments are skipped, never translated.
            if (wallActive && segment != anchorChapterIndex) continue
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

    // ── Part 3: the wall's overscroll machinery (A12) ─────
    //
    // Sign convention: the stretch is POSITIVE past the
    // chapter's END (a forward pull) and NEGATIVE past its
    // START. The column's visual give is translationY =
    // −stretch × RESISTANCE — pulling past the end drags the
    // content UP against the resistance; past the start,
    // DOWN. The connection consumes only the deltas it
    // accumulates (the child has already reported it cannot
    // scroll them); dragging back unwinds the stretch in
    // onPreScroll before the child scrolls again. Releasing
    // past COMMIT_DISTANCE — slowly (onPreFling) or with a
    // fling (onPostFling) — or throwing a hard fling AT the
    // edge commits the crossing, and the stretch springs
    // home either way (a plain default spring — the feel
    // constants above are the tuning surface, not the
    // spring).
    //
    // Fences: a pinch/pan zoom in progress (zoomLockedItems
    // non-empty) freezes accumulation entirely — the drag
    // belongs to the image. Text selection needs no gate:
    // selection drags are consumed by the SelectionContainer
    // and never enter the nested-scroll chain, and only
    // Drag-source deltas are read at all — which also keeps
    // the drift's, the restore's and the follows'
    // programmatic scrolls out. Commits are gated on the
    // anchor being RESIDENT (a pull from an unfilled anchor
    // asserts nothing) and never cross the book's ends.
    // Inert unless the wall is active: every callback checks
    // wallActiveNow first, and the modifier below only
    // attaches the connection in wall mode.
    val wallScope = rememberCoroutineScope()
    val wallStretch = remember(anchorChapterIndex) {
        Animatable(0f)
    }
    val wallCommitPx = with(density) {
        WallFeel.COMMIT_DISTANCE.toPx()
    }
    val wallConnection = remember(
        wallActive, anchorChapterIndex, chapters, wallCommitPx
    ) {
        object : NestedScrollConnection {

            /** Commit on the standing stretch when it passed
             * the commit distance. Returns whether a commit
             * fired. */
            fun settleOrCommit(): Boolean {
                val stretch = wallStretch.value
                if (!anchorResidentNow) return false
                if (stretch >= wallCommitPx &&
                    hasNextChapterNow
                ) {
                    onWallCross(true)
                    return true
                }
                if (stretch <= -wallCommitPx &&
                    hasPreviousChapterNow
                ) {
                    onWallCross(false)
                    return true
                }
                return false
            }

            fun springHome() {
                wallScope.launch {
                    wallStretch.animateTo(0f, spring())
                }
            }

            override fun onPreScroll(
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                if (!wallActiveNow ||
                    source != NestedScrollSource.Drag ||
                    zoomLockedItems.isNotEmpty()
                ) {
                    return Offset.Zero
                }
                val current = wallStretch.value
                if (current == 0f) return Offset.Zero
                // Dragging back toward the chapter: unwind
                // the stretch before the child scrolls.
                return if (current > 0f && available.y > 0f) {
                    val unwind = minOf(current, available.y)
                    wallScope.launch {
                        wallStretch.snapTo(current - unwind)
                    }
                    Offset(0f, unwind)
                } else if (current < 0f && available.y < 0f) {
                    val unwind = minOf(-current, -available.y)
                    wallScope.launch {
                        wallStretch.snapTo(current + unwind)
                    }
                    Offset(0f, -unwind)
                } else {
                    Offset.Zero
                }
            }

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
                // One accumulation rule for both edges: the
                // stretch moves by the leftover the child
                // could not scroll, clamped to the ceiling —
                // negative leftover (pulling forward) grows
                // it, positive shrinks it past the start.
                val atEdge = (available.y < 0f &&
                    !listState.canScrollForward) ||
                    (available.y > 0f &&
                        !listState.canScrollBackward)
                if (!atEdge || available.y == 0f) {
                    return Offset.Zero
                }
                val next = (wallStretch.value - available.y)
                    .coerceIn(
                        -WallFeel.MAX_OVERSCROLL_PX,
                        WallFeel.MAX_OVERSCROLL_PX
                    )
                wallScope.launch { wallStretch.snapTo(next) }
                return Offset(0f, available.y)
            }

            override suspend fun onPreFling(
                available: Velocity
            ): Velocity {
                if (!wallActiveNow) return Velocity.Zero
                // The slow-drag-creeps case: the drag ended
                // with ~no fling, but the stretch stands past
                // the commit distance — the release itself
                // commits.
                if (abs(available.y) < WallFeel.FLING_VELOCITY) {
                    settleOrCommit()
                    springHome()
                }
                return Velocity.Zero
            }

            override suspend fun onPostFling(
                consumed: Velocity,
                available: Velocity
            ): Velocity {
                if (!wallActiveNow) return Velocity.Zero
                if (!settleOrCommit() && anchorResidentNow) {
                    // A hard fling thrown AT the edge commits
                    // on the fling alone, even with little
                    // accumulated stretch.
                    if (available.y < -WallFeel.FLING_VELOCITY &&
                        !listState.canScrollForward &&
                        hasNextChapterNow
                    ) {
                        onWallCross(true)
                    } else if (
                        available.y > WallFeel.FLING_VELOCITY &&
                        !listState.canScrollBackward &&
                        hasPreviousChapterNow
                    ) {
                        onWallCross(false)
                    }
                }
                springHome()
                return Velocity.Zero
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
                // Part 3: wall mode attaches the overscroll
                // connection and the resistance give. Non-wall
                // the chain is exactly fillMaxSize, as before.
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (wallActive) {
                            Modifier
                                .nestedScroll(wallConnection)
                                .graphicsLayer {
                                    translationY =
                                        -wallStretch.value *
                                            WallFeel.RESISTANCE
                                }
                        } else {
                            Modifier
                        }
                    )
            ) {
                chapters.forEachIndexed { segment, chapter ->
                    // Part 3: the wall composes ONLY the anchor
                    // segment — every other segment emits
                    // nothing (no blocks, no state item, no
                    // boundary door). The anchor's own state
                    // item below still fires its B1 fill.
                    if (wallActive && segment != anchorChapterIndex) {
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
                // Part 3: in wall mode the item belongs to the
                // ANCHOR when the anchor is the book's last
                // segment — the only composed one.
                val lastSegment = if (wallActive) {
                    anchorChapterIndex
                } else {
                    chapters.lastIndex
                }
                if (lastSegment >= 0 &&
                    (!wallActive ||
                        anchorChapterIndex == chapters.lastIndex) &&
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
