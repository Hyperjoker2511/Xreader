package app.xreader.ui.reader

import app.xreader.domain.content.ChapterBlock
import app.xreader.domain.content.ChapterContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.1.2 regression pins for the reader's counters.
 *
 * The device failure (2026-10-31, a Live-source manhwa): the
 * header pill read "Ch. 1 Vortex Scans · 1 month" — the
 * chapter's source-given NAME — while the footer read
 * "Chapter 91 of 92 · 97% · ≈1 min in chapter · ≈13 min in
 * book" — the chapter's POSITION in the stored list, with
 * progress and time estimates priced off that position. Two
 * numbering systems, one screen, flatly contradicting.
 *
 * The invariant these tests pin: header and footer report
 * ONE consistent chapter position; a chapter name that
 * contradicts the position yields to it; a name that agrees
 * (or carries no number) is shown exactly as before; and a
 * chapter with no timed words (an image chapter) shows no
 * words-per-minute estimates.
 */
class ReaderCountersTest {

    // ── the screenshot case ────────────────────────────────

    @Test
    fun imageChapterHeaderAndFooterAgreeOnPosition() {
        val header = ReaderCounters.headerLine(
            chapterTitle = "Ch. 1 Vortex Scans · 1 month",
            chapterIndex = 90,
            chapterCount = 92
        )
        val footer = ReaderCounters.footerLine(
            chapterIndex = 90, chapterCount = 92
        )
        assertEquals("Chapter 91 of 92", footer)
        assertEquals(footer, header)
    }

    // ── text-chapter behaviour is unchanged ────────────────

    @Test
    fun agreeingChapterNameIsKeptVerbatim() {
        assertEquals(
            "Chapter 214",
            ReaderCounters.headerLine("Chapter 214", 213, 1692)
        )
    }

    @Test
    fun proseTitleWithoutNumberIsKeptVerbatim() {
        assertEquals(
            "The Ambush",
            ReaderCounters.headerLine("The Ambush", 3, 40)
        )
    }

    @Test
    fun fractionalSpecialIsNotAContradiction() {
        // Ch. 10.5 sits at position 11 — the same chapter the
        // position means, half a step off, not a contradiction.
        val title = "Ch. 10.5 — Interlude"
        assertEquals(title, ReaderCounters.headerLine(title, 10, 92))
    }

    @Test
    fun blankTitleFallsBackToThePosition() {
        assertEquals(
            "Chapter 1",
            ReaderCounters.headerLine("", 0, 92)
        )
    }

    @Test
    fun footerLineIsThePositionLineAsBefore() {
        assertEquals("Chapter 1 of 92", ReaderCounters.footerLine(0, 92))
        assertEquals("", ReaderCounters.footerLine(0, 0))
    }

    // ── time estimates need timed words ────────────────────

    @Test
    fun imageOnlyChapterShowsNoTimeEstimates() {
        val content = ChapterContent(
            blocks = listOf(
                ChapterBlock.Image("p1.jpg"),
                ChapterBlock.Image("p2.jpg"),
                ChapterBlock.Image("p3.jpg")
            )
        )
        assertFalse(ReaderCounters.showTimeEstimates(content))
    }

    @Test
    fun comicChapterWithCreditLineShowsNoTimeEstimates() {
        // The screenshot's shape: image pages plus a couple of
        // stray words (a credit/caption block) — still nothing
        // a words-per-minute estimate can honestly time.
        val content = ChapterContent(
            blocks = listOf(
                ChapterBlock.Image("p1.jpg"),
                ChapterBlock.Paragraph.plain("Vortex Scans"),
                ChapterBlock.Image("p2.jpg")
            )
        )
        assertFalse(ReaderCounters.showTimeEstimates(content))
    }

    @Test
    fun textChapterShowsTimeEstimates() {
        val prose = (1..200).joinToString(" ") { "word" }
        val content = ChapterContent(
            blocks = listOf(ChapterBlock.Paragraph.plain(prose))
        )
        assertTrue(ReaderCounters.showTimeEstimates(content))
    }

    @Test
    fun missingContentShowsNoTimeEstimates() {
        assertFalse(ReaderCounters.showTimeEstimates(null))
    }
}
