package app.xreader.data.ai

import app.xreader.data.db.LoreEntity
import app.xreader.data.db.LoreType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The spoiler guard boundary (PLAN §7 gate): lore chat context is
 * built ONLY from material at or before the reader's position —
 * chapter summaries up to and including the current chapter,
 * lore first seen in earlier chapters, and the current chapter's
 * text cut exactly at the read offset. One chapter further in ANY
 * of them is a hard failure.
 *
 * v1.0.6.2 (F9): the CURRENT chapter's summary is included now —
 * it is already displayed to the reader in the Ask AI sheet, and
 * excluding it left the chat with nothing to go on at a chapter's
 * start (the model literally answered "I don't know the story
 * yet"). Future chapters' summaries remain a hard exclusion.
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
        // v1.0.6.2 (F9): the current chapter's summary is the
        // reader's own displayed summary — it belongs in context.
        assertTrue(
            "current chapter summary is in context (F9)",
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
        // v1.0.6.2 (F9): at chapter 0 the reader's own summary of
        // chapter 0 IS the material there is — it is included.
        assertTrue(
            "chapter 0's own summary is in context at chapter 0 (F9)",
            context.contains("FIRST summary")
        )
        assertFalse(context.contains("beginning"))
    }
}
