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

/**
 * v1.0.6.2 (F7): which chapter a stream position NAMES. Normally
 * the first visible item's chapter — but an entry carrying zero
 * text blocks (an imported front-matter page: a cover/title spine
 * item stored as an empty chapter by older imports) can never be
 * what the reader is reading; its text is the NEXT chapter's.
 * The position resolves to the nearest text-carrying entry,
 * preferring the following one, so the header, the footer and
 * the progress always name the chapter whose words are on
 * screen. [blockCounts] maps chapterIndex → block count; a
 * negative count means "not loaded yet" (a live chapter) and
 * counts as readable — only a KNOWN-empty entry is skipped.
 */
object StreamReadingPosition {
    fun resolve(
        firstVisibleChapterIndex: Int,
        blockCounts: Map<Int, Int>
    ): Int {
        if ((blockCounts[firstVisibleChapterIndex] ?: -1) != 0) {
            return firstVisibleChapterIndex
        }
        val sorted = blockCounts.keys.sorted()
        return sorted.firstOrNull {
            it > firstVisibleChapterIndex && (blockCounts[it] ?: -1) != 0
        } ?: sorted.lastOrNull {
            it < firstVisibleChapterIndex && (blockCounts[it] ?: -1) != 0
        } ?: firstVisibleChapterIndex
    }
}

/**
 * v1.0.6.2 (F7): the centre-tap decision, kept pure so the rule
 * is testable: the centre band of the screen toggles the chrome;
 * a press only counts as a tap when it ends before the
 * long-press timeout (a held press is the paragraph menu's).
 */
object CentreTap {
    fun isCentre(xFraction: Float): Boolean = xFraction in 0.30f..0.70f

    fun isTap(durationMs: Long, longPressTimeoutMs: Long): Boolean =
        durationMs >= 0 && durationMs < longPressTimeoutMs
}
