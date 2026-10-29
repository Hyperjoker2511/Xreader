package app.xreader.data.ai

import app.xreader.data.db.LoreEntity
import app.xreader.data.db.LoreType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The spoiler guard boundary (PLAN §7 gate): lore chat context is
 * built ONLY from material at or before the reader's position —
 * earlier chapter summaries, lore first seen in earlier chapters,
 * and the current chapter's text cut exactly at the read offset.
 * One chapter further in ANY of them is a hard failure.
 */
class SpoilerGuardTest {

    private fun lore(name: String, firstChapter: Int) = LoreEntity(
        id = firstChapter.toLong(),
        bookId = 1,
        type = LoreType.CHARACTER,
        name = name,
        description = "About $name",
        firstChapterIndex = firstChapter,
        updatedAt = 0
    )

    @Test
    fun `nothing beyond the reading position leaks into the context`() {
        val currentText = "a".repeat(300) + "SEEN-SO-FAR" + "b".repeat(300) +
            "NOT-YET-READ"
        val context = LoreChatContext.build(
            bookTitle = "Star Fall",
            lore = listOf(
                lore("Early Friend", 0),
                lore("Current Rival", 2),
                lore("Future Villain", 3)
            ),
            summariesBefore = listOf(
                0 to listOf("PAST-EVENT summary"),
                1 to listOf("EARLIER summary"),
                2 to listOf("CURRENT chapter summary must not appear either"),
                3 to listOf("FUTURE-EVENT summary")
            ),
            currentChapterText = currentText,
            position = ReadingPosition(
                chapterIndex = 2,
                charOffset = 312 // just after "SEEN-SO-FAR"
            )
        )
        assertTrue(context.contains("PAST-EVENT"))
        assertTrue(context.contains("EARLIER"))
        assertTrue(context.contains("Early Friend"))
        assertTrue(context.contains("Current Rival"))
        assertTrue(context.contains("SEEN-SO-FAR"))
        assertFalse("future lore leaked", context.contains("Future Villain"))
        assertFalse("future summary leaked", context.contains("FUTURE-EVENT"))
        assertFalse(
            "current chapter's own summary would describe unread text",
            context.contains("CURRENT chapter summary")
        )
        assertFalse("unread tail of current chapter leaked", context.contains("NOT-YET-READ"))
    }

    @Test
    fun `at the very start almost nothing is known`() {
        val context = LoreChatContext.build(
            bookTitle = "Star Fall",
            lore = listOf(lore("Early Friend", 0), lore("Later", 1)),
            summariesBefore = listOf(0 to listOf("FIRST summary")),
            currentChapterText = "The beginning.",
            position = ReadingPosition(chapterIndex = 0, charOffset = 4)
        )
        assertTrue(context.contains("Early Friend"))
        assertTrue(context.contains("The "))
        assertFalse(context.contains("Later"))
        assertFalse(
            "chapter 0's summary is not 'before' chapter 0",
            context.contains("FIRST summary")
        )
        assertFalse(context.contains("beginning"))
    }
}
