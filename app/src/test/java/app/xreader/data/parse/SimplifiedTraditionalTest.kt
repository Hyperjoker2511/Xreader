package app.xreader.data.parse

import app.xreader.data.prefs.ScriptMode
import app.xreader.domain.content.ChapterBlock
import app.xreader.domain.content.ChapterContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MR-6 Item 8 (SPEC-MR6-TXT-2026-11-25): Simplified↔Traditional
 * display-time conversion over the OpenCC-derived character
 * tables. The load-bearing property is length preservation —
 * conversion must never shift a char offset.
 */
class SimplifiedTraditionalTest {

    private val tables = SimplifiedTraditional.parseTables(
        stText = "学\t學\n国\t國\n书\t書\n门\t門\n",
        tsText = "學\t学\n國\t国\n書\t书\n門\t门\n"
    )

    @Test fun convertsSimplifiedToTraditional() {
        val out = SimplifiedTraditional.convert(
            "学习中国的书", ScriptMode.TO_TRADITIONAL, tables
        )
        assertEquals("學習中國的書", out)
    }

    @Test fun convertsTraditionalToSimplified() {
        val out = SimplifiedTraditional.convert(
            "學習中國的書", ScriptMode.TO_SIMPLIFIED, tables
        )
        assertEquals("学习中国的书", out)
    }

    @Test fun conversionIsLengthPreserving() {
        val source = "学习中国的大门书"
        val out = SimplifiedTraditional.convert(
            source, ScriptMode.TO_TRADITIONAL, tables
        )
        assertEquals(source.length, out.length)
    }

    @Test fun unmappedTextIsReturnedAsIs() {
        val source = "Hello world, no Chinese here."
        val out = SimplifiedTraditional.convert(
            source, ScriptMode.TO_TRADITIONAL, tables
        )
        assertSame(source, out)
    }

    @Test fun offModeNeverConverts() {
        val source = "学习"
        val out = SimplifiedTraditional.convert(source, ScriptMode.OFF, tables)
        assertSame(source, out)
    }

    @Test fun nonBmpPairsAreDropped() {
        // A pair outside the BMP would change UTF-16 length
        // accounting — the parser keeps single-char pairs only.
        val withNonBmp = SimplifiedTraditional.parseTables(
            stText = "学\t學\n𠀾\t𠁞\n",
            tsText = "學\t学\n"
        )
        assertEquals(1, withNonBmp.toTraditional.size)
        assertTrue(withNonBmp.toTraditional.containsKey('学'))
    }

    @Test fun appliesToContentBlocks() {
        val content = ChapterContent(
            listOf(
                ChapterBlock.Heading(1, "学习"),
                ChapterBlock.Paragraph.plain("中国的书")
            )
        )
        val out = SimplifiedTraditional.applyToContent(
            content, ScriptMode.TO_TRADITIONAL, tables
        )
        assertEquals("學習", (out.blocks[0] as ChapterBlock.Heading).text)
        assertEquals(
            "中國的書",
            (out.blocks[1] as ChapterBlock.Paragraph).text
        )
        // The stored original is untouched.
        assertEquals("学习", (content.blocks[0] as ChapterBlock.Heading).text)
    }

    @Test fun trimsDropBlankParagraphsAndTopBlanks() {
        val content = ChapterContent(
            listOf(
                ChapterBlock.Paragraph.plain(""),
                ChapterBlock.Paragraph.plain("  First real line.  "),
                ChapterBlock.Paragraph.plain(""),
                ChapterBlock.Paragraph.plain("Second line.")
            )
        )
        val trimmed = TxtDisplayTransform.applyTrims(
            content, trimBlankLines = true, trimPageTopBlanks = true
        )
        assertEquals(2, trimmed.blocks.size)
        assertEquals(
            "First real line.",
            (trimmed.blocks[0] as ChapterBlock.Paragraph).text
        )
        // No trims requested → the same instance comes back.
        assertSame(
            content,
            TxtDisplayTransform.applyTrims(
                content, trimBlankLines = false, trimPageTopBlanks = false
            )
        )
    }
}
