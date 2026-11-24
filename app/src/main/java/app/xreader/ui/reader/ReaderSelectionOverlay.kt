package app.xreader.ui.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.xreader.domain.reader.CentreTap
import app.xreader.domain.reader.ChapterText
import app.xreader.ui.localreader.expandWordRange
import app.xreader.ui.theme.ReaderPalette
import kotlin.math.max
import kotlin.math.min

/**
 * Code 117 (SPEC-OWNSELECTION §3–§6): the core reader owns its
 * selection. This file is the one home for the selection model,
 * the layout registry, the geometry (resolve / selectionRect /
 * handle anchors), the drag-session edge maths, the entry
 * recognisers that live on prose blocks, the root drag host loop,
 * and the overlay composable that draws tint + teardrop handles.
 *
 * The framework's SelectionContainer is gone from the reader
 * (§10): nothing registers with a selection manager, so there is
 * no manager state to disagree with the model below — the model
 * IS the selection, and every consumer reads it.
 */

/** §3.1: one immutable range in chapter-flattened offsets.
 * Invariants (enforced at every write): never empty, never
 * inverted, both ends inside resident chapters. Block identity
 * is derived via [ChapterText.blockIndexAt], never stored. */
data class ReaderTextSelection(
    val startChapter: Int, val start: Int,
    val endChapter: Int, val end: Int
)

/** §4.2's resolution result: a (chapterIndex, chapterOffset)
 * point, ordered in reading order. */
data class ChapterPoint(
    val chapterIndex: Int,
    val offset: Int
) : Comparable<ChapterPoint> {
    override fun compareTo(other: ChapterPoint): Int =
        if (chapterIndex != other.chapterIndex) {
            chapterIndex.compareTo(other.chapterIndex)
        } else {
            offset.compareTo(other.offset)
        }
}

/** §3.1's normalise path: the selection spanning two points,
 * or null when they coincide (a selection is never empty). */
fun selectionBetween(a: ChapterPoint, b: ChapterPoint): ReaderTextSelection? {
    if (a == b) return null
    val lo = minOf(a, b)
    val hi = maxOf(a, b)
    return ReaderTextSelection(lo.chapterIndex, lo.offset, hi.chapterIndex, hi.offset)
}

/** §5.5's flag holder: a plain Boolean beside the model,
 * written at downs and read/reset by `readerTapToggle` at ups.
 * Plain (not snapshot) state on purpose — its writers and its
 * reader are all pointer-event handlers on the one thread, and
 * its life is exactly one gesture. */
class SelectionLiveAtDown {
    var value: Boolean = false
}

/** §5.2's double-tap pairing record, one per prose block: the
 * uptime of the last clean tap that may seed a pair. A tap-away
 * never writes it (§5.2's veto is structural: a tap-away takes
 * the clear branch and leaves this at −1). */
class SelectionTapPairing {
    var lastCleanTapUptime: Long = -1L
}

/** §5.3: one selection drag. Its non-nullness in the
 * controller IS `selectionDragActive` — there is no second
 * flag for the §5.7 stand-downs to disagree with. */
class SelectionDragSession(
    val pointerId: PointerId,
    val mode: Mode,
    /** The range at creation, chapter offsets (for a spanning
     * HANDLE grab, [entryStart] is in [entryChapter] and
     * [entryEnd] in the selection's end chapter; HANDLE maths
     * reads only [anchor] + [grabbedEnd]). */
    val entryStart: Int, val entryEnd: Int,
    val entryChapter: Int,
    /** For HANDLE sessions: which edge the finger owns. */
    val grabbedEnd: Boolean,
    /** ENTRY: null until the first excursion out of the entry
     * word. HANDLE: the opposite edge, fixed at creation. */
    var anchor: ChapterPoint?,
    /** Window coordinates, updated per move (and read by the
     * §5.6 edge auto-scroll loop, whose finger is stationary
     * while the content moves). */
    var lastFingerWindow: Offset
) {
    enum class Mode { ENTRY, HANDLE }
}

/** §4.1 (Revision 1): one composed prose block's geometry —
 * ONE entry object per key, mutated in place (the
 * `onGloballyPositioned` callback fires during scroll and must
 * stay allocation-free). Two origins, by the invariant:
 * [textOrigin] ≡ the origin of [layout]'s coordinates. The
 * prose chain carries its padding INSIDE the node, so the text
 * origin sits [topInset] below the node's top-left (16 dp for
 * headings, 0 for paragraphs — read from the block type at the
 * capture site). Bounds tests use the node bounds; layout
 * maths converts through the text origin. */
class BlockLayoutEntry {
    var layout: TextLayoutResult? = null
    var nodeOffset: Offset = Offset.Zero
    var textOrigin: Offset = Offset.Zero
    var size: IntSize = IntSize.Zero
    var positioned: Boolean = false
}

/** §4.1: the registry of composed prose blocks, keyed by
 * (chapterIndex, blockIndex). Eviction is DocumentBlock's
 * DisposableEffect, so the registry holds composed blocks
 * only — visible items plus the LazyColumn's prefetch margin. */
class BlockLayoutRegistry {
    private val entries = LinkedHashMap<Pair<Int, Int>, BlockLayoutEntry>()

    fun putLayout(key: Pair<Int, Int>, layout: TextLayoutResult) {
        entries.getOrPut(key) { BlockLayoutEntry() }.layout = layout
    }

    fun putPosition(
        key: Pair<Int, Int>,
        nodeOffset: Offset,
        size: IntSize,
        topInsetPx: Float
    ) {
        val entry = entries.getOrPut(key) { BlockLayoutEntry() }
        entry.nodeOffset = nodeOffset
        entry.size = size
        entry.textOrigin = nodeOffset + Offset(0f, topInsetPx)
        entry.positioned = true
    }

    fun remove(key: Pair<Int, Int>) {
        entries.remove(key)
    }

    fun get(key: Pair<Int, Int>): BlockLayoutEntry? = entries[key]

    fun all(): Set<Map.Entry<Pair<Int, Int>, BlockLayoutEntry>> =
        entries.entries
}

/**
 * The selection's single source of truth (§3.2) plus every
 * geometry service its consumers need. Held in DocumentReader
 * (remembered); [chapterTextOf] and [rootWindowOffset] are
 * plain fields the reader refreshes as it composes and lays
 * out — the tree's seed-tracker pattern, never snapshot state.
 */
class ReaderSelectionController(
    val selectionState: MutableState<ReaderTextSelection?> =
        mutableStateOf(null),
    val dragSessionState: MutableState<SelectionDragSession?> =
        mutableStateOf(null)
) {
    val registry = BlockLayoutRegistry()
    val tapAwayGate = SelectionLiveAtDown()

    /** Segment index → that segment's flattened text. */
    var chapterTextOf: (Int) -> ChapterText? = { null }

    /** The reader root's top-left in window coordinates,
     * recorded by the root's own onGloballyPositioned. The
     * overlay fills the root, so this is its origin too. */
    var rootWindowOffset: Offset = Offset.Zero

    /** §3.4 + Revision 2 D3: the ONE clear — the selection and
     * any in-flight drag session die together, in the same
     * frame, by this single operation wherever a §3.4 trigger
     * fires. */
    fun clearSelection() {
        selectionState.value = null
        dragSessionState.value = null
    }

    // ── §4.2: position resolution ─────────────────────────

    private fun offsetInBlock(
        entry: BlockLayoutEntry,
        layout: TextLayoutResult,
        chapterIndex: Int,
        rangeStart: Int,
        windowPoint: Offset
    ): ChapterPoint {
        val local = layout.getOffsetForPosition(windowPoint - entry.textOrigin)
        return ChapterPoint(chapterIndex, rangeStart + local)
    }

    /** Finger point (window coordinates) → chapter point.
     * Step 1: a block whose recorded node bounds contain the
     * point (the size includes the bottom padding, so a finger
     * in the paragraph gap resolves to the block above's end).
     * Step 2: no candidate — the nearest selectable entry by
     * vertical distance (above → its range end; below → its
     * range start); a point beside a block (same row, outside
     * its width) resolves through that block's clamping layout.
     * Step 3: through the entry's TEXT origin (§4.1). */
    fun resolve(windowPoint: Offset): ChapterPoint? {
        var nearestAbove: ChapterPoint? = null
        var nearestAboveDistance = Float.MAX_VALUE
        var nearestBelow: ChapterPoint? = null
        var nearestBelowDistance = Float.MAX_VALUE
        var rowEntry: BlockLayoutEntry? = null
        var rowLayout: TextLayoutResult? = null
        var rowChapter = -1
        var rowRangeStart = 0
        for ((key, entry) in registry.all()) {
            val layout = entry.layout ?: continue
            if (!entry.positioned) continue
            val chapterText = chapterTextOf(key.first) ?: continue
            val range = chapterText.rangeOf(key.second) ?: continue
            if (range.end <= range.start) continue
            val top = entry.nodeOffset.y
            val bottom = top + entry.size.height
            val left = entry.nodeOffset.x
            val right = left + entry.size.width
            if (windowPoint.x in left..right &&
                windowPoint.y in top..bottom
            ) {
                return offsetInBlock(
                    entry, layout, key.first, range.start, windowPoint
                )
            }
            when {
                windowPoint.y in top..bottom -> {
                    rowEntry = entry
                    rowLayout = layout
                    rowChapter = key.first
                    rowRangeStart = range.start
                }
                windowPoint.y < top -> {
                    val distance = top - windowPoint.y
                    if (distance < nearestBelowDistance) {
                        nearestBelowDistance = distance
                        nearestBelow = ChapterPoint(key.first, range.start)
                    }
                }
                else -> {
                    val distance = windowPoint.y - bottom
                    if (distance < nearestAboveDistance) {
                        nearestAboveDistance = distance
                        nearestAbove = ChapterPoint(key.first, range.end)
                    }
                }
            }
        }
        val entry = rowEntry
        val layout = rowLayout
        if (entry != null && layout != null) {
            return offsetInBlock(
                entry, layout, rowChapter, rowRangeStart, windowPoint
            )
        }
        return if (nearestAboveDistance <= nearestBelowDistance) {
            nearestAbove ?: nearestBelow
        } else {
            nearestBelow ?: nearestAbove
        }
    }

    // ── §6/§7.2 geometry: spans, rect, handle anchors ─────

    /** The selection's span inside one block, block-local and
     * half-open, or null when the selection does not intersect
     * the block. [range] is the block's own chapter range. */
    private fun blockLocalRange(
        chapterIndex: Int,
        chapterText: ChapterText,
        range: ChapterText.BlockRange,
        selection: ReaderTextSelection
    ): Pair<Int, Int>? {
        val spanStart = when {
            chapterIndex < selection.startChapter -> return null
            chapterIndex == selection.startChapter -> selection.start
            else -> 0
        }
        val spanEnd = when {
            chapterIndex > selection.endChapter -> return null
            chapterIndex == selection.endChapter -> selection.end
            else -> chapterText.length
        }
        val start = max(spanStart, range.start) - range.start
        val end = min(spanEnd, range.end) - range.start
        return if (end > start) start to end else null
    }

    /** Per-block local selection ranges for [selection] over
     * every composed intersected block — the overlay's tint
     * input (§6.1) and the seat rect's (§7.2). */
    fun intersectedRanges(
        selection: ReaderTextSelection
    ): List<Triple<Pair<Int, Int>, BlockLayoutEntry, Pair<Int, Int>>> {
        val result =
            ArrayList<Triple<Pair<Int, Int>, BlockLayoutEntry, Pair<Int, Int>>>()
        for ((key, entry) in registry.all()) {
            if (entry.layout == null || !entry.positioned) continue
            val chapterText = chapterTextOf(key.first) ?: continue
            val range = chapterText.rangeOf(key.second) ?: continue
            val local = blockLocalRange(key.first, chapterText, range, selection)
                ?: continue
            result += Triple(key, entry, local)
        }
        return result
    }

    /** §7.2: the union rect of the selection in window
     * coordinates, from the same registry + layout calls as
     * the tint — the bar's seat source. */
    fun selectionRect(): Rect? {
        val selection = selectionState.value ?: return null
        var union: Rect? = null
        for ((_, entry, local) in intersectedRanges(selection)) {
            val layout = entry.layout ?: continue
            val bounds = layout.getSelectionPath(local.first, local.second)
                .getBounds()
            if (bounds.isEmpty) continue
            val window = bounds.translate(entry.textOrigin)
            union = union?.let {
                Rect(
                    min(it.left, window.left), min(it.top, window.top),
                    max(it.right, window.right), max(it.bottom, window.bottom)
                )
            } ?: window
        }
        return union
    }

    /** The registry entry + range of the block a character
     * offset belongs to (§3.1's derived block identity —
     * [ChapterText.blockIndexAt] resolves separators and
     * zero-width blocks to the following prose block). */
    private fun entryFor(
        chapterIndex: Int,
        charOffset: Int
    ): Pair<BlockLayoutEntry, ChapterText.BlockRange>? {
        val chapterText = chapterTextOf(chapterIndex) ?: return null
        val blockIndex = chapterText.blockIndexAt(charOffset)
        if (blockIndex < 0) return null
        val entry = registry.get(chapterIndex to blockIndex) ?: return null
        if (entry.layout == null || !entry.positioned) return null
        val range = chapterText.rangeOf(blockIndex) ?: return null
        if (range.end <= range.start) return null
        return entry to range
    }

    /** §6.2: the handles' tip points in window coordinates —
     * the start handle's tip at the first selected char's
     * bottom-left, the end handle's at the last selected
     * char's bottom-right, each converted through its block's
     * TEXT origin (§4.1, Revision 1 §6.2). Either is null when
     * its block is not composed (scrolled out of the window
     * mid-drag — the handle is off-screen by construction). */
    fun handleAnchors(): Pair<Offset?, Offset?> {
        val selection = selectionState.value ?: return null to null
        val startTip = entryFor(selection.startChapter, selection.start)
            ?.let { (entry, range) ->
                val layout = entry.layout!!
                val length = range.end - range.start
                val local = (selection.start - range.start)
                    .coerceIn(0, length)
                val bbox = layout.getBoundingBox(local)
                Offset(
                    bbox.left,
                    layout.getLineBottom(layout.getLineForOffset(local))
                ) + entry.textOrigin
            }
        val endTip = entryFor(selection.endChapter, selection.end - 1)
            ?.let { (entry, range) ->
                val layout = entry.layout!!
                val length = range.end - range.start
                val local = (selection.end - 1 - range.start)
                    .coerceIn(0, (length - 1).coerceAtLeast(0))
                val bbox = layout.getBoundingBox(local)
                Offset(
                    bbox.right,
                    layout.getLineBottom(layout.getLineForOffset(local))
                ) + entry.textOrigin
            }
        return startTip to endTip
    }

    /** §5.4/§6.2: the handles' drawn circle centres (window
     * coordinates) — the grab discs centre here, so one helper
     * serves the draw and the hit test. The body hangs below
     * the tip: centre = tip + (0, tip length + body radius). */
    fun handleCentres(
        tipLengthPx: Float,
        bodyRadiusPx: Float
    ): Pair<Offset?, Offset?> {
        val (startTip, endTip) = handleAnchors()
        val drop = tipLengthPx + bodyRadiusPx
        return (startTip?.let { it + Offset(0f, drop) }) to
            (endTip?.let { it + Offset(0f, drop) })
    }

    // ── §5.3/§5.4: the drag edge maths ────────────────────

    private fun pointAfter(point: ChapterPoint): ChapterPoint? {
        val text = chapterTextOf(point.chapterIndex) ?: return null
        if (point.offset < text.length) {
            return ChapterPoint(point.chapterIndex, point.offset + 1)
        }
        var chapter = point.chapterIndex + 1
        while (true) {
            val next = chapterTextOf(chapter) ?: return null
            if (next.length > 0) return ChapterPoint(chapter, 1)
            chapter++
        }
    }

    private fun pointBefore(point: ChapterPoint): ChapterPoint? {
        if (point.offset > 0) {
            return ChapterPoint(point.chapterIndex, point.offset - 1)
        }
        var chapter = point.chapterIndex - 1
        while (chapter >= 0) {
            val previous = chapterTextOf(chapter) ?: return null
            if (previous.length > 0) {
                return ChapterPoint(chapter, previous.length - 1)
            }
            chapter--
        }
        return null
    }

    /** One drag step: the range the model should hold for
     * [session] with the finger at [fingerWindow], or null
     * when the edge holds (whitespace under the finger — an
     * empty word expansion changes nothing, §5.3).
     *
     * ENTRY: the range is `word ∪ span(anchor, fingerEdge)` —
     * it can grow or shrink through the entry word but never
     * invert, because the anchor is chosen by the crossing
     * (first excursion beyond the word's end → anchor = word
     * start; before its start → anchor = word end) and never
     * changes again within the session.
     * HANDLE: `span(anchor, fingerEdge)` clamped — the moving
     * edge may reach the anchor but not pass it; the minimum
     * range is one character.
     * The following edge word-snaps on every move: the
     * boundary on the travel side of the finger's word (end
     * edge → word's last + 1; start edge → word's first). */
    fun advanceDrag(
        session: SelectionDragSession,
        fingerWindow: Offset
    ): ReaderTextSelection? {
        val finger = resolve(fingerWindow) ?: return null
        return when (session.mode) {
            SelectionDragSession.Mode.ENTRY -> {
                val wordStart =
                    ChapterPoint(session.entryChapter, session.entryStart)
                val wordEnd =
                    ChapterPoint(session.entryChapter, session.entryEnd)
                var anchor = session.anchor
                if (anchor == null) {
                    when {
                        finger >= wordEnd -> {
                            anchor = wordStart
                            session.anchor = anchor
                        }
                        finger < wordStart -> {
                            anchor = wordEnd
                            session.anchor = anchor
                        }
                        else -> return ReaderTextSelection(
                            session.entryChapter, session.entryStart,
                            session.entryChapter, session.entryEnd
                        )
                    }
                }
                val anchorPoint = anchor ?: return null
                val fingerText = chapterTextOf(finger.chapterIndex)
                    ?: return null
                val word = expandWordRange(fingerText.text, finger.offset)
                if (word.isEmpty()) return null
                val edge = if (finger >= anchorPoint) {
                    ChapterPoint(finger.chapterIndex, word.last + 1)
                } else {
                    ChapterPoint(finger.chapterIndex, word.first)
                }
                selectionBetween(
                    minOf(wordStart, anchorPoint, edge),
                    maxOf(wordEnd, anchorPoint, edge)
                )
            }
            SelectionDragSession.Mode.HANDLE -> {
                val anchorPoint = session.anchor ?: return null
                val fingerText = chapterTextOf(finger.chapterIndex)
                    ?: return null
                val word = expandWordRange(fingerText.text, finger.offset)
                if (word.isEmpty()) return null
                val edge = if (finger >= anchorPoint) {
                    ChapterPoint(finger.chapterIndex, word.last + 1)
                } else {
                    ChapterPoint(finger.chapterIndex, word.first)
                }
                if (session.grabbedEnd) {
                    val newEnd = if (edge <= anchorPoint) {
                        pointAfter(anchorPoint) ?: return null
                    } else {
                        edge
                    }
                    selectionBetween(anchorPoint, newEnd)
                } else {
                    val newStart = if (edge >= anchorPoint) {
                        pointBefore(anchorPoint) ?: return null
                    } else {
                        edge
                    }
                    selectionBetween(newStart, anchorPoint)
                }
            }
        }
    }
}

// ── The teardrop (§6.2) ───────────────────────────────────

/** One teardrop path: triangular tip flowing into a circular
 * body — the tip at [tip], the body hanging below it (centre
 * at tip + tip length + body radius, so total height = tip
 * length + body diameter). The same shape language serves the
 * core overlay here and the PDF overlay (§13). */
fun selectionTeardropPath(
    tip: Offset,
    tipLengthPx: Float,
    bodyRadiusPx: Float
): Path {
    val centre = Offset(tip.x, tip.y + tipLengthPx + bodyRadiusPx)
    return Path().apply {
        moveTo(tip.x, tip.y)
        lineTo(centre.x - bodyRadiusPx, centre.y)
        arcTo(
            Rect(centre, bodyRadiusPx),
            startAngleDegrees = 180f,
            sweepAngleDegrees = -180f,
            forceMoveTo = false
        )
        lineTo(tip.x, tip.y)
        close()
    }
}

// ── Entry recognisers (§5.2) ──────────────────────────────

/** §5.2's long-press half, on a prose block's root: writes the
 * §5.5 flag at its first down, then awaits the long-press. If
 * the list scrolls first, the pointer is cancelled and the
 * call returns null — no entry, scroll proceeds untouched
 * (the shipped arbitration, preserved). If the long-press
 * fires: resolve the press point (§4.2) → the word under it →
 * write the word range to the model, create the §5.3 drag
 * session for this pointer, consume the long-press change,
 * and EXIT — the root host owns the drag from the next event
 * onward; this block's disposal at any later moment is
 * expected and harmless (Revision 1 §5.1/§5.3). */
suspend fun PointerInputScope.selectionEntryLongPressLoop(
    controller: ReaderSelectionController,
    chapterIndex: Int,
    blockIndex: Int
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        // §5.5 writer (2): the flag for this gesture.
        controller.tapAwayGate.value =
            controller.selectionState.value != null
        val longPress = awaitLongPressOrCancellation(down.id)
            ?: return@awaitEachGesture
        val entry = controller.registry.get(chapterIndex to blockIndex)
            ?: return@awaitEachGesture
        val window = longPress.position + entry.nodeOffset
        val hit = controller.resolve(window) ?: return@awaitEachGesture
        val chapterText = controller.chapterTextOf(hit.chapterIndex)
            ?: return@awaitEachGesture
        val word = expandWordRange(chapterText.text, hit.offset)
        // A whitespace press selects nothing and opens no bar
        // (Moon+'s rule) — the gesture is simply over.
        if (word.isEmpty()) return@awaitEachGesture
        controller.selectionState.value = ReaderTextSelection(
            hit.chapterIndex, word.first, hit.chapterIndex, word.last + 1
        )
        controller.dragSessionState.value = SelectionDragSession(
            pointerId = down.id,
            mode = SelectionDragSession.Mode.ENTRY,
            entryStart = word.first,
            entryEnd = word.last + 1,
            entryChapter = hit.chapterIndex,
            grabbedEnd = true,
            anchor = null,
            lastFingerWindow = window
        )
        longPress.consume()
    }
}

/** §5.2's tap half, on a prose block's root: tap-away over
 * text (§5.5) and double-tap word entry, in one tracker.
 *
 * Shaped as a custom awaitEachGesture loop rather than
 * `detectTapGestures` for one verified reason: foundation
 * 1.7.6's `waitForUpOrCancellation` (detectTapGestures' tap
 * wait) returns null for a consumed up, and the shipped
 * `readerTapToggle` consumes every clean tap's up in the
 * Initial pass whenever no selection lives — a
 * detectTapGestures here could therefore never observe a
 * first tap, and its onDoubleTap could never fire (the §19
 * preservation would be void). This tracker reads the stream
 * the way the tree's watch-only observers do — it classifies
 * taps itself and never depends on an up being unconsumed.
 * It consumes exactly one thing: a tap-away's up, at that
 * tap (§5.2). A clean tap is the readerTapToggle test:
 * unmoved past touch slop, single pointer, under the
 * long-press timeout ([CentreTap.isTap]). */
suspend fun PointerInputScope.selectionTapTracker(
    controller: ReaderSelectionController,
    chapterIndex: Int,
    blockIndex: Int,
    pairing: SelectionTapPairing
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val liveAtDown = controller.selectionState.value != null
        var dragged = false
        var multiTouch = false
        var up: PointerInputChange? = null
        while (up == null) {
            val event = awaitPointerEvent()
            if (event.changes.count { it.pressed } > 1) multiTouch = true
            val change = event.changes.firstOrNull { it.id == down.id }
                ?: continue
            if ((change.position - down.position).getDistance() >
                viewConfiguration.touchSlop
            ) {
                dragged = true
            }
            if (!change.pressed) up = change
        }
        val upChange = up ?: return@awaitEachGesture
        val clean = !dragged && !multiTouch && CentreTap.isTap(
            upChange.uptimeMillis - down.uptimeMillis,
            viewConfiguration.longPressTimeoutMillis
        )
        if (!clean) {
            pairing.lastCleanTapUptime = -1L
            return@awaitEachGesture
        }
        if (liveAtDown) {
            // §5.5 tap-away over text: clear at the up, consume
            // it, and do not seed a double-tap pair (§5.2).
            upChange.consume()
            pairing.lastCleanTapUptime = -1L
            controller.clearSelection()
            return@awaitEachGesture
        }
        val last = pairing.lastCleanTapUptime
        if (last > 0L &&
            upChange.uptimeMillis - last <=
            viewConfiguration.doubleTapTimeoutMillis
        ) {
            // Double-tap entry: the word under the SECOND tap,
            // through the same resolve + expandWordRange path
            // as the long-press — and no drag session (§5.2).
            pairing.lastCleanTapUptime = -1L
            val entry = controller.registry.get(chapterIndex to blockIndex)
                ?: return@awaitEachGesture
            val window = down.position + entry.nodeOffset
            val hit = controller.resolve(window) ?: return@awaitEachGesture
            val chapterText = controller.chapterTextOf(hit.chapterIndex)
                ?: return@awaitEachGesture
            val word = expandWordRange(chapterText.text, hit.offset)
            if (!word.isEmpty()) {
                controller.selectionState.value = ReaderTextSelection(
                    hit.chapterIndex, word.first,
                    hit.chapterIndex, word.last + 1
                )
            }
        } else {
            pairing.lastCleanTapUptime = upChange.uptimeMillis
        }
    }
}

// ── The root drag host (§5.3) ─────────────────────────────

/** The reader root's standing Main-pass loop: tracks the one
 * pointer a drag session owns. Moves convert to window
 * coordinates (host-local + the root's recorded window
 * offset), run the §5.3 edge maths, write the range, and are
 * consumed. Up/cancel settles — the range stands as last
 * written; there is no rollback and no commit copy. If the
 * model is cleared mid-drag by any §3.4 trigger, the clear
 * nulls the session and this loop ignores the pointer's
 * remaining events. */
suspend fun PointerInputScope.selectionDragHostLoop(
    controller: ReaderSelectionController
) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent()
            val session = controller.dragSessionState.value ?: continue
            val change = event.changes.firstOrNull {
                it.id == session.pointerId
            } ?: continue
            if (change.pressed) {
                if (change.positionChanged()) {
                    val window = change.position +
                        controller.rootWindowOffset
                    session.lastFingerWindow = window
                    val next = controller.advanceDrag(session, window)
                    if (next != null &&
                        next != controller.selectionState.value
                    ) {
                        controller.selectionState.value = next
                    }
                    change.consume()
                }
            } else {
                controller.dragSessionState.value = null
            }
        }
    }
}

// ── The overlay (§6) ──────────────────────────────────────

/** §6.4's tint-path cache: a block's path is recomputed only
 * when its local selection range (or its layout) changed
 * since the last frame. Steady-state drag within one block:
 * one path. */
private class TintPathCache {
    class Entry(
        val layout: TextLayoutResult,
        val start: Int,
        val end: Int,
        val path: Path
    )

    val entries = mutableMapOf<Pair<Int, Int>, Entry>()
}

/**
 * §6.3: the selection overlay — inside DocumentReader's outer
 * Box, after the LazyColumn, before the bar Popup. Exists only
 * while a selection lives. Draws the live tint (§6.1) and the
 * two teardrop handles (§6.2) in one Canvas, and hosts two
 * pointerInputs: an Initial-pass watch-only tap-away watcher
 * and a Main-pass handle-grab loop. Drag TRACKING and the
 * edge auto-scroll loop are NOT here — they live at the reader
 * root (§5.3, §5.6), which cannot be disposed.
 */
@Composable
fun ReaderSelectionOverlay(
    controller: ReaderSelectionController,
    listState: LazyListState,
    palette: ReaderPalette,
    modifier: Modifier = Modifier
) {
    val selection = controller.selectionState.value ?: return
    // Redraw input (§6.3): the list's layout info — reading it
    // here subscribes this composable, so scroll frames (edge
    // auto-scroll) redraw the Canvas from the registry's fresh
    // offsets even on frames where the range itself is unchanged.
    @Suppress("UNUSED_EXPRESSION")
    listState.layoutInfo
    val tintPaths = remember { TintPathCache() }
    if (tintPaths.entries.size > 96) tintPaths.entries.clear()
    Box(
        modifier
            .fillMaxSize()
            .pointerInput(controller) {
                // (1) Tap-away watcher — Initial pass, watch-only,
                // consumes nothing (Revision 1 §6.3). The Initial
                // pass is load-bearing: children consume ups (a
                // ZoomableImage consumes its up), so a Main-pass
                // wait on this node would return null over any
                // image and §9.7's clear would never fire.
                awaitEachGesture {
                    val down = awaitFirstDown(
                        requireUnconsumed = false,
                        pass = PointerEventPass.Initial
                    )
                    // §5.5 writer (1): the flag for this gesture.
                    controller.tapAwayGate.value =
                        controller.selectionState.value != null
                    val tipPx = 9.dp.toPx()
                    val radiusPx = 11.dp.toPx()
                    val grabPx = 24.dp.toPx()
                    val downWindow = down.position +
                        controller.rootWindowOffset
                    val (startCentre, endCentre) =
                        controller.handleCentres(tipPx, radiusPx)
                    // §5.4: a down inside a grab disc is not a
                    // tap-away candidate — the handles are part
                    // of the selection, not "elsewhere".
                    val inDisc = listOfNotNull(startCentre, endCentre)
                        .any {
                            (downWindow - it).getDistance() <= grabPx
                        }
                    var sawSecondPointer = false
                    var moved = false
                    var upTime = 0L
                    while (true) {
                        val event = awaitPointerEvent(
                            PointerEventPass.Initial
                        )
                        if (event.changes.size > 1) {
                            sawSecondPointer = true
                        }
                        for (change in event.changes) {
                            if ((change.position - down.position)
                                    .getDistance() >
                                viewConfiguration.touchSlop
                            ) {
                                moved = true
                            }
                            if (!change.pressed) {
                                upTime = change.uptimeMillis
                            }
                        }
                        if (event.changes.all { !it.pressed }) break
                    }
                    val cleanTap = !sawSecondPointer && !moved &&
                        upTime > 0L && CentreTap.isTap(
                            upTime - down.uptimeMillis,
                            viewConfiguration.longPressTimeoutMillis
                        )
                    if (cleanTap && !inDisc &&
                        controller.selectionState.value != null
                    ) {
                        controller.clearSelection()
                    }
                }
            }
            .pointerInput(controller) {
                // (2) Handle-grab loop — Main pass. A down inside
                // a grab disc is consumed BEFORE the session is
                // created (§5.4's load-bearing duty: an unconsumed
                // stationary handle press would let the block
                // beneath complete its long-press and re-enter
                // selection), then this loop exits — the root
                // host tracks the drag.
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val live = controller.selectionState.value
                        ?: return@awaitEachGesture
                    val tipPx = 9.dp.toPx()
                    val radiusPx = 11.dp.toPx()
                    val grabPx = 24.dp.toPx()
                    val (startCentre, endCentre) =
                        controller.handleCentres(tipPx, radiusPx)
                    val downWindow = down.position +
                        controller.rootWindowOffset
                    val inStart = startCentre?.let {
                        (downWindow - it).getDistance() <= grabPx
                    } ?: false
                    val inEnd = endCentre?.let {
                        (downWindow - it).getDistance() <= grabPx
                    } ?: false
                    val grabbedEnd = when {
                        inStart && inEnd -> {
                            // §5.4: the handle on the down's side
                            // of the two centres' midpoint.
                            val start = startCentre!!
                            val end = endCentre!!
                            val mid = Offset(
                                (start.x + end.x) / 2f,
                                (start.y + end.y) / 2f
                            )
                            val axis = end - start
                            val side = (downWindow - mid).x * axis.x +
                                (downWindow - mid).y * axis.y
                            side >= 0f
                        }
                        inEnd -> true
                        inStart -> false
                        else -> return@awaitEachGesture
                    }
                    down.consume()
                    controller.dragSessionState.value =
                        SelectionDragSession(
                            pointerId = down.id,
                            mode = SelectionDragSession.Mode.HANDLE,
                            entryStart = live.start,
                            entryEnd = live.end,
                            entryChapter = live.startChapter,
                            grabbedEnd = grabbedEnd,
                            anchor = if (grabbedEnd) {
                                ChapterPoint(live.startChapter, live.start)
                            } else {
                                ChapterPoint(live.endChapter, live.end)
                            },
                            lastFingerWindow = downWindow
                        )
                }
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val origin = controller.rootWindowOffset
            // §6.1: the live tint — accent at 0.55 over the
            // stored washes, drawn from layout selection paths,
            // never folded into the blocks' span pipeline (a
            // span change would re-layout text per drag frame).
            val tint = palette.accent.copy(alpha = 0.55f)
            for ((key, entry, local) in
                controller.intersectedRanges(selection)) {
                val layout = entry.layout ?: continue
                val cached = tintPaths.entries[key]
                val path = if (cached != null &&
                    cached.layout === layout &&
                    cached.start == local.first &&
                    cached.end == local.second
                ) {
                    cached.path
                } else {
                    layout.getSelectionPath(local.first, local.second)
                        .also {
                            tintPaths.entries[key] =
                                TintPathCache.Entry(
                                    layout, local.first, local.second, it
                                )
                        }
                }
                withTransform({
                    translate(
                        entry.textOrigin.x - origin.x,
                        entry.textOrigin.y - origin.y
                    )
                }) {
                    drawPath(path, tint)
                }
            }
            // §6.2: both teardrops always draw; the end handle
            // draws last (on top). Never clipped by their block
            // — the overlay is above the list.
            val tipPx = 9.dp.toPx()
            val radiusPx = 11.dp.toPx()
            val (startTip, endTip) = controller.handleAnchors()
            startTip?.let { tip ->
                drawPath(
                    selectionTeardropPath(tip - origin, tipPx, radiusPx),
                    palette.accent
                )
            }
            endTip?.let { tip ->
                drawPath(
                    selectionTeardropPath(tip - origin, tipPx, radiusPx),
                    palette.accent
                )
            }
        }
    }
}
