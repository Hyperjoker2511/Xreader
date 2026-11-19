package app.xreader.ui.book

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.7 Patch 1 (item 3b + 3c): the small-label fixes that
 * are pure logic.
 *
 * 3b — the Continue pill's sub-line names the chapter ONCE:
 * a chapter titled "Chapter N" must not render as
 * "Chapter N · Chapter N".
 *
 * 3c — time-left only once reading has begun: a progress
 * row that merely exists at 0% is NOT started.
 */
class Patch1SmallLabelsTest {

    // ── 3b: Continue pill sub-line dedupe ───────────────────

    @Test
    fun `titled chapter renders number and title once each`() {
        assertEquals(
            "Chapter 11 · The Awakening",
            continuePillSubLine(
                chapterNumber = "11",
                chapterTitle = "The Awakening",
                bookPercent = null
            )
        )
    }

    @Test
    fun `chapter titled Chapter N renders once`() {
        assertEquals(
            "Chapter 11",
            continuePillSubLine(
                chapterNumber = "11",
                chapterTitle = "Chapter 11",
                bookPercent = null
            )
        )
    }

    @Test
    fun `chapter titled Chapter N colon subtitle renders once`() {
        assertEquals(
            "Chapter 11: The Return",
            continuePillSubLine(
                chapterNumber = "11",
                chapterTitle = "Chapter 11: The Return",
                bookPercent = null
            )
        )
    }

    @Test
    fun `started book appends the percent after the deduped head`() {
        assertEquals(
            "Chapter 11 · 42%",
            continuePillSubLine(
                chapterNumber = "11",
                chapterTitle = "Chapter 11",
                bookPercent = 42
            )
        )
        assertEquals(
            "Chapter 11 · The Awakening · 42%",
            continuePillSubLine(
                chapterNumber = "11",
                chapterTitle = "The Awakening",
                bookPercent = 42
            )
        )
    }

    @Test
    fun `chapter 1 never matches a Chapter 11 title`() {
        assertEquals(
            "Chapter 1 · Chapter 11",
            continuePillSubLine(
                chapterNumber = "1",
                chapterTitle = "Chapter 11",
                bookPercent = null
            )
        )
    }

    @Test
    fun `blank title renders the number alone`() {
        assertEquals(
            "Chapter 11",
            continuePillSubLine(
                chapterNumber = "11",
                chapterTitle = "   ",
                bookPercent = null
            )
        )
    }

    // ── 3c: time-left only once reading has started ─────────

    @Test
    fun `no progress and nothing read is not started`() {
        assertFalse(hasStartedReading(null, false))
    }

    @Test
    fun `a progress row at zero percent is not started`() {
        assertFalse(hasStartedReading(0f, false))
    }

    @Test
    fun `progress past zero is started`() {
        assertTrue(hasStartedReading(0.01f, false))
    }

    @Test
    fun `a read chapter is started even with no progress row`() {
        assertTrue(hasStartedReading(null, true))
    }

    @Test
    fun `a read chapter is started even at zero percent`() {
        assertTrue(hasStartedReading(0f, true))
    }
}
