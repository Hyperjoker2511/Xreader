package app.xreader.domain

import app.xreader.domain.export.HighlightsMarkdown
import app.xreader.domain.export.QuoteCardLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 6: quote cards and the Markdown highlight export (PLAN §8). */
class ExportTest {

    // A measurer where every character is exactly 10 units wide.
    private val measure: (String) -> Float = { it.length * 10f }

    @Test
    fun `wrap breaks lines at word boundaries`() {
        val lines = QuoteCardLayout.wrap(
            "the quick brown fox jumps", maxWidth = 100f, measure = measure
        )
        // 10 chars per line at most: "the quick" (9), "brown fox" (9), "jumps".
        assertEquals(listOf("the quick", "brown fox", "jumps"), lines)
    }

    @Test
    fun `wrap hard-splits a single over-wide word`() {
        val lines = QuoteCardLayout.wrap(
            "abcdefghijklmno", maxWidth = 100f, measure = measure
        )
        assertEquals(listOf("abcdefghij", "klmno"), lines)
    }

    @Test
    fun `build caps long quotes with an ellipsis`() {
        val quote = (1..40).joinToString(" ") { "word$it" }
        val model = QuoteCardLayout.build(
            quote = quote, bookTitle = "T", chapterLabel = "Chapter 1",
            author = "A", maxWidth = 200f, measure = measure, maxLines = 3
        )
        assertEquals(3, model.quoteLines.size)
        assertTrue(model.quoteLines.last().endsWith("…"))
        assertEquals("T", model.bookTitle)
    }

    @Test
    fun `card geometry matches the share-sheet format`() {
        assertEquals(1080, QuoteCardLayout.CARD_WIDTH)
        assertEquals(1350, QuoteCardLayout.CARD_HEIGHT)
    }

    @Test
    fun `markdown export has front matter, callouts and chapter sections`() {
        val md = HighlightsMarkdown.format(
            bookTitle = "My Novel",
            author = "Some Author",
            items = listOf(
                HighlightsMarkdown.Item(
                    chapterIndex = 0, chapterTitle = "Start",
                    quotedText = "A line worth keeping.",
                    note = "Because it matters."
                ),
                HighlightsMarkdown.Item(
                    chapterIndex = 0, chapterTitle = "Start",
                    quotedText = "Second line."
                ),
                HighlightsMarkdown.Item(
                    chapterIndex = 4, chapterTitle = "",
                    quotedText = "Later line."
                )
            ),
            exportedDate = "2026-10-28"
        )
        assertTrue(md.startsWith("---\n"))
        assertTrue(md.contains("title: \"My Novel\""))
        assertTrue(md.contains("highlights: 3"))
        assertTrue(md.contains("# Highlights — My Novel"))
        assertTrue(md.contains("## Chapter 1 — Start"))
        assertTrue(md.contains("> [!quote]\n> A line worth keeping."))
        assertTrue(md.contains("> [!note]\n> Because it matters."))
        assertTrue(md.contains("## Chapter 5\n")) // untitled chapter: no suffix
    }

    @Test
    fun `markdown export handles an empty book honestly`() {
        val md = HighlightsMarkdown.format("Empty", "", emptyList(), "2026-10-28")
        assertTrue(md.contains("highlights: 0"))
        assertTrue(md.contains("_No highlights in this book yet._"))
    }

    @Test
    fun `yaml escaping keeps quoted titles valid`() {
        val md = HighlightsMarkdown.format(
            "A \"Quoted\" Title", "", emptyList(), "2026-10-28"
        )
        assertTrue(md.contains("title: \"A \\\"Quoted\\\" Title\""))
    }
}
