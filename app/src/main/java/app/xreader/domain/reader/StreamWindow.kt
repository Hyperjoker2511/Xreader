package app.xreader.domain.reader

/**
 * The endless stream's sliding window (v1.0.2, R17): "infinite"
 * describes the reading experience, not memory use. The window of
 * PARSED chapters around the anchor is bounded by BOTH a chapter
 * count and a parsed-character budget, grows forward for short
 * chapters, and releases content far behind while the position
 * anchor (chapter + char offset) stays exact.
 *
 * Pure planner; the reader applies [Window] by loading/releasing
 * chapter bodies. Budgets are tier-scaled by the caller
 * (PerformancePolicy): a 2 GB tablet gets a smaller char budget.
 */
object StreamWindowPlanner {

    data class Window(val fromIndex: Int, val toIndex: Int) {
        fun contains(index: Int): Boolean = index in fromIndex..toIndex
        val size: Int get() = if (toIndex < fromIndex) 0 else toIndex - fromIndex + 1
    }

    /**
     * Plan the window around [anchorIndex]. At least the anchor is
     * always included even if its chapter alone exceeds the budget
     * (a chapter must be readable). Behind the anchor we keep up to
     * [behind] chapters so a scroll back never waits.
     */
    fun plan(
        anchorIndex: Int,
        chapterCharCounts: List<Int>,
        maxChapters: Int = 10,
        charBudget: Int = 1_500_000,
        behind: Int = 2
    ): Window {
        if (chapterCharCounts.isEmpty()) return Window(0, -1)
        val anchor = anchorIndex.coerceIn(0, chapterCharCounts.lastIndex)
        var from = anchor
        var to = anchor
        var used = chapterCharCounts[anchor].coerceAtLeast(0)
        var count = 1
        // Reserve the behind chapters FIRST — a scroll back must
        // never wait — then grow forward with what budget remains.
        var behindKept = 0
        while (from - 1 >= 0 && behindKept < behind && count < maxChapters) {
            val prev = chapterCharCounts[from - 1].coerceAtLeast(0)
            if (used + prev > charBudget && count > 1) break
            from--; used += prev; count++; behindKept++
        }
        while (to + 1 <= chapterCharCounts.lastIndex && count < maxChapters) {
            val next = chapterCharCounts[to + 1].coerceAtLeast(0)
            if (used + next > charBudget && count > 1) break
            to++; used += next; count++
        }
        return Window(from, to)
    }

    /** Char budget per performance tier (chars of parsed text). */
    fun charBudgetFor(minimalTier: Boolean): Int =
        if (minimalTier) 600_000 else 1_500_000
}
