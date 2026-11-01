package app.xreader.domain.reader

import app.xreader.domain.content.ChapterBlock
import app.xreader.domain.content.ChapterContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reader Terms engine pins (P0): whole-word boundaries, case
 * rules, longest-wins, no cascading, the space-less-script
 * (CJK) exact-text rule, and the original↔display offset map
 * the read-aloud highlight remap stands on.
 */
class TermReplacerTest {

    private fun pair(
        original: String,
        replacement: String,
        matchCase: Boolean = true,
        wholeWord: Boolean = true
    ) = TermPair(original, replacement, matchCase, wholeWord)

    @Test
    fun `whole word replaces at boundaries only`() {
        val pairs = listOf(pair("Lin", "Lynn"))
        assertEquals(
            "Lynn said Lynn left.",
            TermReplacer.replace("Lin said Lin left.", pairs).text
        )
        // Inside a longer word: not a match.
        assertEquals(
            "Lindon and McLin stayed.",
            TermReplacer.replace("Lindon and McLin stayed.", pairs).text
        )
        // Digits and underscores are word characters too.
        assertEquals(
            "Lin2 _Lin Lin_",
            TermReplacer.replace("Lin2 _Lin Lin_", pairs).text
        )
        // Start and end of the text are boundaries.
        assertEquals(
            "Lynn met Lynn",
            TermReplacer.replace("Lin met Lin", pairs).text
        )
    }

    @Test
    fun `case sensitive by default`() {
        val pairs = listOf(pair("Lin", "Lynn"))
        assertEquals(
            "lin and LIN stayed, Lynn left.",
            TermReplacer.replace("lin and LIN stayed, Lin left.", pairs).text
        )
    }

    @Test
    fun `match case off replaces every case`() {
        val pairs = listOf(pair("Lin", "Lynn", matchCase = false))
        assertEquals(
            "Lynn Lynn Lynn Lynn",
            TermReplacer.replace("lin LIN Lin lIn", pairs).text
        )
    }

    @Test
    fun `longest original wins at the same position`() {
        val pairs = listOf(
            pair("Lin", "L"),
            pair("Lin Dong", "Dong-er")
        )
        assertEquals(
            "Dong-er left, and L followed.",
            TermReplacer.replace("Lin Dong left, and Lin followed.", pairs).text
        )
    }

    @Test
    fun `replacements never cascade`() {
        val pairs = listOf(
            pair("Alpha", "Beta"),
            pair("Beta", "Gamma")
        )
        // Alpha becomes Beta — and stays Beta, even though Beta
        // is itself a term. The original Beta still becomes Gamma.
        assertEquals(
            "Beta and Gamma",
            TermReplacer.replace("Alpha and Beta", pairs).text
        )
    }

    @Test
    fun `whole word off matches exact text anywhere`() {
        val pairs = listOf(pair("cat", "dog", wholeWord = false))
        assertEquals(
            "condogenate",
            TermReplacer.replace("concatenate", pairs).text
        )
    }

    @Test
    fun `cjk terms match as exact text inside spaceless text`() {
        // The pair is whole-word by default, but Han characters
        // have no word boundaries: the name must still be found
        // inside a spaceless sentence.
        val pairs = listOf(pair("林动", "Lin Dong"))
        assertEquals(
            "Lin Dong说道：走吧。",
            TermReplacer.replace("林动说道：走吧。", pairs).text
        )
    }

    @Test
    fun `a cjk neighbour does not block a latin term`() {
        val pairs = listOf(pair("Lin", "Lynn"))
        assertEquals(
            "Lynn说道",
            TermReplacer.replace("Lin说道", pairs).text
        )
    }

    @Test
    fun `empty pairs and no match return the text unchanged`() {
        val text = "Nothing to fix here."
        assertEquals(text, TermReplacer.replace(text, emptyList()).text)
        val result = TermReplacer.replace(text, listOf(pair("Lin", "Lynn")))
        assertEquals(text, result.text)
        assertTrue(result.offsetMap.isEmpty)
    }

    @Test
    fun `empty replacement deletes the term`() {
        val pairs = listOf(pair("um", ""))
        assertEquals(
            "Well, , yes.",
            TermReplacer.replace("Well, um, yes.", pairs).text
        )
    }

    @Test
    fun `offset map translates both ways around a replacement`() {
        val original = "aa Lin bb"
        val result = TermReplacer.replace(original, listOf(pair("Lin", "Alexander")))
        assertEquals("aa Alexander bb", result.text)
        val map = result.offsetMap
        // Before the span: identity.
        assertEquals(2, map.originalToDisplay(2))
        // Span start maps to span start.
        assertEquals(3, map.originalToDisplay(3))
        // Span end (original 6) maps to display end (12).
        assertEquals(12, map.originalToDisplay(6))
        // After the span: shifted by the length difference.
        assertEquals(13, map.originalToDisplay(7))
        // And back.
        assertEquals(3, map.displayToOriginal(3))
        assertEquals(6, map.displayToOriginal(12))
        assertEquals(7, map.displayToOriginal(13))
        assertEquals(0, map.displayToOriginal(0))
    }

    @Test
    fun `apply to content replaces headings and paragraphs, keeps images`() {
        val content = ChapterContent(
            blocks = listOf(
                ChapterBlock.Heading(1, "Chapter 9: Lin"),
                ChapterBlock.Paragraph.plain("Lin Dong smiled. Lin left."),
                ChapterBlock.Image("page1.png"),
                ChapterBlock.Paragraph.plain("Then Lin slept.")
            )
        )
        val pairs = listOf(pair("Lin", "Lynn"), pair("Lin Dong", "Dong-er"))
        val result = TermReplacer.applyToContent(content, pairs)
        val blocks = result.content.blocks
        assertEquals(
            "Chapter 9: Lynn",
            (blocks[0] as ChapterBlock.Heading).text
        )
        assertEquals(
            "Dong-er smiled. Lynn left.",
            (blocks[1] as ChapterBlock.Paragraph).text
        )
        assertTrue(blocks[2] is ChapterBlock.Image)
        assertEquals(
            "Then Lynn slept.",
            (blocks[3] as ChapterBlock.Paragraph).text
        )
        // The original content is untouched.
        assertEquals(
            "Lin Dong smiled. Lin left.",
            (content.blocks[1] as ChapterBlock.Paragraph).text
        )
        // The offset map is in joined-text coordinates: the
        // display ChapterText built from the result must agree
        // with it — the second paragraph's replacement sits
        // where the map says the original's does.
        val originalJoined = ChapterText(content).text
        val displayJoined = ChapterText(result.content).text
        val originalOffset = originalJoined.indexOf("Then Lin") + 5
        val displayOffset = result.offsetMap.originalToDisplay(originalOffset)
        assertTrue(
            displayJoined.startsWith("Lynn", displayOffset)
        )
        assertEquals(
            originalOffset,
            result.offsetMap.displayToOriginal(displayOffset)
        )
    }

    @Test
    fun `apply to content replaces inside a styled span`() {
        val content = ChapterContent(
            blocks = listOf(
                ChapterBlock.Paragraph(
                    listOf(
                        app.xreader.domain.content.TextSpan("Lin ", bold = true),
                        app.xreader.domain.content.TextSpan("left.")
                    )
                )
            )
        )
        val result = TermReplacer.applyToContent(
            content, listOf(pair("Lin", "Lynn"))
        )
        val paragraph = result.content.blocks[0] as ChapterBlock.Paragraph
        assertEquals("Lynn ", paragraph.spans[0].text)
        assertTrue(paragraph.spans[0].bold)
        assertEquals("left.", paragraph.spans[1].text)
    }
}
