package app.xreader.data.extension

import app.xreader.domain.content.ChapterBlock
import app.xreader.domain.content.ChapterContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cleaner rules (PLAN §5/§9.4 gate): replacements land, bad patterns
 * are disabled + reported instead of crashing, and the pathological
 * regex case — a catastrophic-backtracking pattern on hostile text —
 * times out, gets disabled, and the clean RETURNS.
 */
class CleanerEngineTest {

    private fun rule(pattern: String, replacement: String = "", scope: String = "user") =
        CleanerEngine.TimedRule(CleanerRuleData(pattern, replacement), scope)

    @Test
    fun `simple replacement and removal`() {
        val outcome = CleanerEngine.clean(
            "Chapter text. Please support us at example.com! More text.",
            listOf(rule("Please support us at example\\.com! ", ""))
        )
        assertEquals("Chapter text. More text.", outcome.text)
        assertTrue(outcome.disabledRules.isEmpty())
    }

    @Test
    fun `ignore case flag is honoured`() {
        val outcome = CleanerEngine.clean(
            "FOOTER note",
            listOf(
                CleanerEngine.TimedRule(
                    CleanerRuleData("footer", "", ignoreCase = true), "global"
                )
            )
        )
        assertEquals(" note", outcome.text)
    }

    @Test
    fun `invalid pattern is disabled and reported, text passes through`() {
        val outcome = CleanerEngine.clean(
            "untouched",
            listOf(rule("([unclosed"))
        )
        assertEquals("untouched", outcome.text)
        assertEquals(1, outcome.disabledRules.size)
        assertTrue(outcome.notes.isNotEmpty())
    }

    @Test
    fun `pathological regex times out, is disabled, and the clean returns`() {
        val hostileText = "a".repeat(60_000) + "b"
        val started = System.currentTimeMillis()
        val outcome = CleanerEngine.clean(
            hostileText,
            listOf(rule("(a+)+\$", "X")),
            timeoutMsPerRule = 300
        )
        val elapsed = System.currentTimeMillis() - started
        assertTrue(
            "clean must return promptly (took ${elapsed}ms)",
            elapsed < 10_000
        )
        assertEquals(1, outcome.disabledRules.size)
        assertTrue(outcome.notes.any { it.contains("too long") })
        assertEquals(hostileText, outcome.text)
    }

    @Test
    fun `overlong pattern is refused`() {
        val outcome = CleanerEngine.clean(
            "text",
            listOf(rule("x".repeat(CleanerEngine.MAX_PATTERN_CHARS + 1)))
        )
        assertEquals(1, outcome.disabledRules.size)
    }

    @Test
    fun `content cleaning preserves untouched blocks and their spans`() {
        val untouched = ChapterBlock.Paragraph(
            listOf(
                app.xreader.domain.content.TextSpan("Bold ", bold = true),
                app.xreader.domain.content.TextSpan("text")
            )
        )
        val touched = ChapterBlock.Paragraph.plain("Remove this FOOTER please")
        val content = ChapterContent(listOf(untouched, touched))
        val (cleaned, _) = CleanerEngine.cleanContent(
            content,
            listOf(rule("FOOTER ", ""))
        )
        // Untouched block keeps its exact spans.
        assertEquals(untouched, cleaned.blocks[0])
        val second = cleaned.blocks[1] as ChapterBlock.Paragraph
        assertEquals("Remove this please", second.text)
    }

    @Test
    fun `disabled rules stop applying to later blocks`() {
        val content = ChapterContent(
            listOf(
                ChapterBlock.Paragraph.plain("aaa"),
                ChapterBlock.Paragraph.plain("aaa")
            )
        )
        // First rule kills itself (invalid); the surviving rule still runs.
        val (cleaned, outcome) = CleanerEngine.cleanContent(
            content,
            listOf(rule("(["), rule("aaa", "bbb"))
        )
        assertEquals(1, outcome.disabledRules.size)
        assertTrue(
            cleaned.blocks.all {
                (it as ChapterBlock.Paragraph).text == "bbb"
            }
        )
        assertFalse(outcome.notes.isEmpty())
    }
}
