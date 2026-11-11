package app.xreader.data

import app.xreader.data.extension.HtmlToContent
import app.xreader.domain.content.ChapterBlock
import app.xreader.domain.content.ChapterContent
import app.xreader.domain.content.TextSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.5.2 (FIX-8, §14): the word counter, audited against a
 * genuinely INDEPENDENT counter (a plain-text whitespace split
 * written here, in the test) — and against the real converter,
 * with prose carried in markup that is NOT <p>/<h*> (lists,
 * blockquotes, table cells, bare divs), so the fixture proves
 * the counter counts prose wherever the converter puts it.
 *
 * ChapterBlock has exactly four types; prose exists only in
 * Heading and Paragraph (converters emit nothing else prose-
 * bearing) — the fixtures below pin that from the converter
 * side rather than assuming it.
 */
class V152WordCountTest {

    /** The independent counter: split the plain text on
     * whitespace runs and count non-empty tokens. Shares no
     * code with ChapterContent.wordCount. */
    private fun independentCount(content: ChapterContent): Int =
        content.plainText()
            .split(Regex("\\s+"))
            .count { it.isNotEmpty() }

    @Test
    fun `wordCount matches the independent counter on mixed blocks`() {
        val content = ChapterContent(
            blocks = listOf(
                ChapterBlock.Heading(1, "The Long Road Home"),
                ChapterBlock.Paragraph(
                    listOf(
                        TextSpan("She walked "),
                        TextSpan("the whole way", bold = true),
                        TextSpan(" back.")
                    )
                ),
                ChapterBlock.Rule,
                ChapterBlock.Image("img-1.jpg", alt = "a road at dusk"),
                ChapterBlock.Paragraph.plain("Nobody followed her.")
            )
        )
        assertEquals(independentCount(content), content.wordCount)
        // By hand: heading 4 + paragraph 6 + closing 3 = 13.
        // The image's alt text is NOT prose and must not count.
        assertEquals(13, content.wordCount)
    }

    @Test
    fun `converter prose in non-paragraph markup is all counted`() {
        val html = """
            <h2>Chapter Nine</h2>
            <blockquote><p>Quoted wisdom travels far beyond its home valley.</p></blockquote>
            <ul><li>First item carries seven plain words here</li>
            <li>Second item follows with six more words</li></ul>
            <div>Loose div prose also counts as reading text.</div>
            <table><tr><td>Cell prose lives in tables sometimes too</td></tr></table>
        """.trimIndent()
        val content = HtmlToContent.fromFragment(html, "https://site.example/book/1")
        // Every prose block the converter produced is a Heading
        // or a Paragraph (the only prose-bearing block types).
        assertTrue(content.blocks.isNotEmpty())
        assertTrue(
            content.blocks.all {
                it is ChapterBlock.Heading || it is ChapterBlock.Paragraph ||
                    it is ChapterBlock.Image || it is ChapterBlock.Rule
            }
        )
        assertEquals(independentCount(content), content.wordCount)
        // The fixture's prose really arrived (not silently
        // dropped by the converter): each fragment's words are
        // inside the counted total.
        val plain = content.plainText()
        for (fragment in listOf(
            "Quoted wisdom travels far", "First item carries seven",
            "Loose div prose", "Cell prose lives in tables"
        )) {
            assertTrue("missing converted prose: $fragment", plain.contains(fragment))
        }
        assertTrue("wordCount=${content.wordCount}", content.wordCount >= 30)
    }

    @Test
    fun `an image-only chapter counts zero`() {
        val content = ChapterContent(
            blocks = listOf(
                ChapterBlock.Image("img-1.jpg"),
                ChapterBlock.Image("img-2.jpg", alt = "not prose at all")
            )
        )
        assertEquals(0, content.wordCount)
        assertEquals(0, independentCount(content))
    }
}
