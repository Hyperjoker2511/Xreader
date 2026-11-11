package app.xreader.ui.localreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * v1.5.10 (F4) gate: the within-chapter position ("(3/12)").
 * It builds ONLY on the shipped position model plus
 * navigator-granular values the wiring supplies — no
 * dependency on F2. Unknowns render nothing, never a guess.
 */
class V1510StatusLineChapterTest {

    // ── withinChapterPosition ───────────────────────────────

    @Test
    fun `zero-based index renders one-based`() {
        assertEquals(3 to 12, withinChapterPosition(2, 12))
        assertEquals(1 to 12, withinChapterPosition(0, 12))
        assertEquals(12 to 12, withinChapterPosition(11, 12))
    }

    @Test
    fun `unknowns are null not guesses`() {
        assertNull(withinChapterPosition(null, 12))
        assertNull(withinChapterPosition(2, null))
        assertNull(withinChapterPosition(null, null))
        assertNull(withinChapterPosition(2, 0))
    }

    @Test
    fun `stale indices clamp into range`() {
        assertEquals(12 to 12, withinChapterPosition(99, 12))
        assertEquals(1 to 12, withinChapterPosition(-3, 12))
    }

    // ── withinChapterFromSpan ───────────────────────────────

    @Test
    fun `progression span estimate lands mid chapter`() {
        // Chapter spans 20%..30% of a 100-page book → 10
        // pages; half the chapter read = starting page 6.
        assertEquals(
            6 to 10,
            withinChapterFromSpan(0.5f, 0.20, 0.30, 100)
        )
    }

    @Test
    fun `span estimate clamps at chapter edges`() {
        assertEquals(
            1 to 10,
            withinChapterFromSpan(0f, 0.20, 0.30, 100)
        )
        assertEquals(
            10 to 10,
            withinChapterFromSpan(1f, 0.20, 0.30, 100)
        )
    }

    @Test
    fun `span estimate unknown when unmeasurable`() {
        assertNull(withinChapterFromSpan(0.5f, 0.30, 0.20, 100))
        assertNull(withinChapterFromSpan(0.5f, 0.20, 0.30, 0))
        // A chapter estimating below one page carries nothing.
        assertNull(withinChapterFromSpan(0.5f, 0.0, 0.001, 100))
        assertNull(withinChapterFromSpan(0.5f, null, 0.30, 100))
        assertNull(withinChapterFromSpan(0.5f, 0.20, null, 100))
    }

    // ── status line rendering ───────────────────────────────

    private fun progress() = EpubProgress(
        fraction = 0.225f,
        chapterTitle = "Chapter 4",
        sectionIndex = 3,
        sectionCount = 20
    )

    @Test
    fun `within chapter renders in parens before section count`() {
        val line = epubStatusLineContent(
            progress = progress(),
            withinChapter = 3 to 12,
            batteryPercent = 80,
            clockText = "21:30"
        )
        assertEquals("(3/12) · 4/20 · 22%", line.positionText)
        assertEquals("80%  ·  21:30  ·  Chapter 4  ·  (3/12) · 4/20 · 22%", line.render())
    }

    @Test
    fun `without within chapter the v1_5_8 line is unchanged`() {
        val p = progress()
        assertEquals(
            epubStatusLineContent(p, 80, "21:30"),
            epubStatusLineContent(
                progress = p,
                withinChapter = null,
                batteryPercent = 80,
                clockText = "21:30"
            )
        )
    }
}
