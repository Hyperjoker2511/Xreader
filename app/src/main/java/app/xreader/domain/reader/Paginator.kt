package app.xreader.domain.reader

/**
 * One laid-out block, ready for page packing: prose blocks arrive as
 * measured line starts (char offsets into the chapter text, ascending,
 * always including the block's own start; an empty block has just its
 * start), image/rule blocks arrive as fixed heights.
 */
data class MeasuredBlock(
    val blockIndex: Int,
    /** Global char offset where this block's text starts. */
    val blockStart: Int,
    /** Global char offset where this block's text ends (exclusive). */
    val blockEnd: Int,
    /** Start offset of every line; empty for non-prose blocks. */
    val lineStarts: List<Int> = emptyList(),
    val lineHeightPx: Float = 0f,
    val spaceBeforePx: Float = 0f,
    val spaceAfterPx: Float = 0f,
    /** Non-null for image/rule blocks: the block's fixed height. */
    val fixedHeightPx: Float? = null
) {
    val isProse: Boolean get() = fixedHeightPx == null
}

/** A run of one block's characters living on one page. */
data class PageSlice(
    val blockIndex: Int,
    val startChar: Int,
    val endChar: Int,
    /** True when the slice is a fixed-height block (image/rule). */
    val fixed: Boolean = false
)

/** One page: ordered slices; [startChar]..[endChar] is its locator span. */
data class Page(
    val slices: List<PageSlice>,
    val startChar: Int,
    val endChar: Int
)

/**
 * Greedy page packing (PLAN §6): measured lines are poured into pages
 * of [pageHeightPx]; a block's `spaceBefore` is dropped at a page top
 * (standard book typography); a line taller than the page, or an image
 * taller than the page, gets placed anyway rather than looping
 * forever. Pure — the invariants (no lost/duplicated prose characters,
 * strictly increasing page starts, locator round-trips) are property-
 * tested on the JVM with [LineWrap] as the measuring model.
 */
class Paginator(private val pageHeightPx: Float) {

    fun paginate(blocks: List<MeasuredBlock>): List<Page> {
        if (blocks.isEmpty()) return listOf(Page(emptyList(), 0, 0))
        val pages = ArrayList<Page>()
        var slices = ArrayList<PageSlice>()
        var used = 0f

        fun flush() {
            if (slices.isEmpty()) return
            pages += Page(
                slices = slices.toList(),
                startChar = slices.first().startChar,
                endChar = slices.last().endChar
            )
            slices = ArrayList()
            used = 0f
        }

        fun addSlice(blockIndex: Int, start: Int, end: Int, fixed: Boolean) {
            val last = slices.lastOrNull()
            if (last != null && last.blockIndex == blockIndex && last.fixed == fixed &&
                last.endChar == start
            ) {
                slices[slices.lastIndex] = last.copy(endChar = end)
            } else {
                slices += PageSlice(blockIndex, start, end, fixed)
            }
        }

        for (block in blocks) {
            val fixedHeight = block.fixedHeightPx
            if (fixedHeight != null) {
                var before = if (slices.isEmpty()) 0f else block.spaceBeforePx
                if (slices.isNotEmpty() &&
                    used + before + fixedHeight + block.spaceAfterPx > pageHeightPx
                ) {
                    flush()
                    before = 0f
                }
                addSlice(block.blockIndex, block.blockStart, block.blockEnd, fixed = true)
                used += before + fixedHeight + block.spaceAfterPx
                continue
            }
            // Prose: pour line by line.
            val starts = block.lineStarts.ifEmpty { listOf(block.blockStart) }
            var lineIndex = 0
            var firstLineOfBlock = true
            while (lineIndex < starts.size) {
                val lineStart = starts[lineIndex]
                val lineEnd =
                    if (lineIndex + 1 < starts.size) starts[lineIndex + 1] else block.blockEnd
                val atPageTop = slices.isEmpty()
                val before =
                    if (firstLineOfBlock && !atPageTop) block.spaceBeforePx else 0f
                val after =
                    if (lineIndex == starts.lastIndex) block.spaceAfterPx else 0f
                val needed = before + block.lineHeightPx + after
                if (!atPageTop && used + needed > pageHeightPx) {
                    flush()
                    continue // retry the same line on the fresh page
                }
                addSlice(block.blockIndex, lineStart, lineEnd, fixed = false)
                used += needed
                firstLineOfBlock = false
                lineIndex++
            }
        }
        flush()
        if (pages.isEmpty()) pages += Page(emptyList(), 0, 0)
        return pages
    }
}

/** Locator ⇄ page mapping (PLAN §6): char offsets survive any
 * re-pagination, so progress restores exactly under new typography. */
object PageLocator {

    /** The page that shows [charOffset]: the last page starting at or
     * before it; an offset past the end lands on the last page. */
    fun pageIndexForOffset(pages: List<Page>, charOffset: Int): Int {
        if (pages.isEmpty()) return 0
        var result = 0
        for ((index, page) in pages.withIndex()) {
            if (page.startChar <= charOffset) result = index else break
        }
        return result
    }

    /** The char offset a page begins at (its saved locator). */
    fun offsetForPage(pages: List<Page>, pageIndex: Int): Int =
        pages.getOrNull(pageIndex)?.startChar ?: 0

    /** 0..1 through the chapter, by char offset. */
    fun chapterFraction(charOffset: Int, chapterChars: Int): Float =
        if (chapterChars <= 0) 0f
        else (charOffset.toFloat() / chapterChars).coerceIn(0f, 1f)

    /** 0..1 through the whole book, from chapter position + in-chapter
     * fraction. Monotonic in both inputs. */
    fun bookFraction(chapterIndex: Int, chapterFraction: Float, totalChapters: Int): Float {
        if (totalChapters <= 0) return 0f
        val within = chapterFraction.coerceIn(0f, 1f)
        return ((chapterIndex + within) / totalChapters).coerceIn(0f, 1f)
    }
}
