package app.xreader.domain.reader

/**
 * v1.7 Moon+ document engine (Item 1) — the segment index.
 *
 * The document is the book's chapter list as a list of
 * SEGMENTS (one per chapter, keyed by chapter ID at the call
 * site). Each segment contributes a run of list items:
 * an optional chapter header, then either its blocks
 * (RESIDENT) or exactly one state item (LOADING / MISSING /
 * FAILED). This index is the arithmetic that maps between
 * (segment, item-within-segment) and flat list positions —
 * pure, allocation-light, and recomputed in O(chapters),
 * never O(total blocks): prefix sums over per-segment item
 * counts only.
 *
 * The surface composes its lazy list per segment and uses
 * this index to resolve restore targets and to name the
 * segment under the viewport's first visible item; no flat
 * list of the whole book is ever materialised (Item 6).
 */
class SegmentIndex(shapes: List<Shape>) {

    /**
     * One segment's shape. [blockCount] >= 0 means the
     * segment is RESIDENT with that many blocks; [STATE]
     * (-1) means it contributes exactly one state item.
     */
    data class Shape(
        val hasHeader: Boolean,
        val blockCount: Int
    )

    /** A resolved flat position: [segment] is the segment's
     * position in the chapter list; [block] is [HEADER] (-1)
     * for the segment's header item, [STATE_ITEM] (-2) for
     * its state item, or the block index (>= 0) otherwise. */
    data class Location(val segment: Int, val block: Int)

    private val starts: IntArray
    private val headerCounts: IntArray
    private val blockCounts: IntArray
    val segmentCount: Int = shapes.size
    val itemCount: Int

    init {
        starts = IntArray(shapes.size + 1)
        headerCounts = IntArray(shapes.size) { i -> if (shapes[i].hasHeader) 1 else 0 }
        blockCounts = IntArray(shapes.size) { i -> shapes[i].blockCount }
        var acc = 0
        shapes.forEachIndexed { i, shape ->
            starts[i] = acc
            acc += headerCounts[i] + (if (shape.blockCount >= 0) shape.blockCount else 1)
        }
        starts[shapes.size] = acc
        itemCount = acc
    }

    /** The flat index of [segment]'s first item (its header
     * when it has one, else its first body item), or -1 when
     * the segment is out of range. */
    fun segmentStart(segment: Int): Int =
        if (segment in 0 until segmentCount) starts[segment] else -1

    /** The flat index of one item of [segment]: [block] >= 0
     * addresses a block (only valid on a RESIDENT segment),
     * [HEADER] its header item, [STATE_ITEM] its state item.
     * Returns -1 when the addressed item does not exist. */
    fun flatIndexOf(segment: Int, block: Int): Int {
        if (segment !in 0 until segmentCount) return -1
        val start = starts[segment]
        val headerCount = headerCounts[segment]
        val blocks = blockCounts[segment]
        return when {
            block == HEADER -> if (headerCount > 0) start else -1
            block == STATE_ITEM ->
                if (blocks == STATE) start + headerCount else -1
            block >= 0 ->
                if (blocks > block) start + headerCount + block else -1
            else -> -1
        }
    }

    /** Resolve a flat list index back to its segment and
     * item-within-segment, or null when out of range. */
    fun locate(flatIndex: Int): Location? {
        if (flatIndex !in 0 until itemCount) return null
        // Binary search over the prefix sums: the segment is
        // the last one whose start is <= flatIndex.
        var lo = 0
        var hi = segmentCount - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (starts[mid] <= flatIndex) lo = mid else hi = mid - 1
        }
        val within = flatIndex - starts[lo]
        val headerCount = headerCounts[lo]
        return when {
            within < headerCount -> Location(lo, HEADER)
            blockCounts[lo] == STATE -> Location(lo, STATE_ITEM)
            else -> Location(lo, within - headerCount)
        }
    }

    companion object {
        /** [Shape.blockCount] value for a non-resident segment. */
        const val STATE: Int = -1
        /** [Location.block] / [flatIndexOf] address of a header item. */
        const val HEADER: Int = -1
        /** [Location.block] / [flatIndexOf] address of a state item. */
        const val STATE_ITEM: Int = -2
    }
}
