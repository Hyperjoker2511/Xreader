package app.xreader.domain.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** R17 stream window budgets. */
class StreamWindowTest {

    @Test
    fun `window grows forward and keeps chapters behind`() {
        val counts = List(30) { 10_000 }
        val window = StreamWindowPlanner.plan(
            anchorIndex = 10, chapterCharCounts = counts,
            maxChapters = 10, charBudget = 1_500_000, behind = 2
        )
        assertTrue(window.contains(10))
        assertEquals(8, window.fromIndex)
        assertEquals(17, window.toIndex) // 10 chapters total: 8..17
        assertEquals(10, window.size)
    }

    @Test
    fun `char budget stops growth for long chapters`() {
        val counts = List(30) { 400_000 }
        val window = StreamWindowPlanner.plan(
            anchorIndex = 5, chapterCharCounts = counts,
            maxChapters = 10, charBudget = 1_000_000, behind = 2
        )
        // Behind is reserved first: anchor (400k) + one behind
        // (800k); a third chapter either way would break the budget.
        assertEquals(4, window.fromIndex)
        assertEquals(5, window.toIndex)
    }

    @Test
    fun `the anchor is always readable even when it alone exceeds budget`() {
        val counts = listOf(50, 5_000_000, 50)
        val window = StreamWindowPlanner.plan(
            anchorIndex = 1, chapterCharCounts = counts,
            maxChapters = 10, charBudget = 100_000, behind = 2
        )
        assertTrue(window.contains(1))
    }

    @Test
    fun `edges clamp at both ends of the book`() {
        val counts = List(5) { 1_000 }
        val start = StreamWindowPlanner.plan(0, counts, behind = 2)
        assertEquals(0, start.fromIndex)
        val end = StreamWindowPlanner.plan(4, counts, behind = 2)
        assertEquals(4, end.toIndex)
        assertEquals(2, end.fromIndex)
    }

    @Test
    fun `empty book gives an empty window`() {
        val window = StreamWindowPlanner.plan(0, emptyList())
        assertEquals(0, window.size)
    }
}
