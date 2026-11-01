package app.xreader.ui.reader

import app.xreader.data.db.Chapter
import app.xreader.domain.reader.ChapterDisplayOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * v1.3.2 (item 79 — the hotfix's #1 correctness item): the
 * reader opened "Chapter 158: A Bad Art Dealer?" under a
 * "Chapter 1 of 170" label because position was treated as
 * identity and the Oldest display sort didn't reorder a
 * newest-first stored list. These pins make identity the
 * truth: display order comes from each chapter's OWN number,
 * Start targets the chapter whose number is 1, and the
 * reader's header/footer agree with the opened chapter's own
 * heading — never with its list position.
 */
class V132IdentityReaderTest {

    private fun chapter(
        index: Int,
        number: Int,
        title: String = "Chapter $number"
    ) = Chapter(
        id = (index + 1).toLong(),
        bookId = 1L,
        index = index,
        title = title,
        remoteId = "https://x.example/book/chapter-$number",
        updatedAt = 0L
    )

    /** The device shape: 170 chapters stored NEWEST first
     * (stored index 0 is chapter 170). */
    private fun newestFirstStore(count: Int = 170): List<Chapter> =
        (0 until count).map { index ->
            val number = count - index
            chapter(
                index = index,
                number = number,
                title = if (number == 158) {
                    "Chapter 158: A Bad Art Dealer?"
                } else {
                    "Chapter $number"
                }
            )
        }

    @Test
    fun `oldest view of a newest-first store starts at chapter 1`() {
        val stored = newestFirstStore()
        val displayed = ChapterDisplayOrder.ordered(
            stored, newestFirst = false
        )
        assertEquals(170, displayed.size)
        assertEquals(
            1f,
            ChapterDisplayOrder.numberOf(displayed.first())
        )
        assertEquals(
            170f,
            ChapterDisplayOrder.numberOf(displayed.last())
        )
        // The row the user taps first IS chapter 1: its stored
        // identity (index/remoteId) is chapter 1's own.
        val firstRow = displayed.first()
        assertEquals(
            "https://x.example/book/chapter-1", firstRow.remoteId
        )
    }

    @Test
    fun `newest view mirrors the identity order`() {
        val stored = newestFirstStore()
        val displayed = ChapterDisplayOrder.ordered(
            stored, newestFirst = true
        )
        assertEquals(
            170f,
            ChapterDisplayOrder.numberOf(displayed.first())
        )
        assertEquals(
            1f,
            ChapterDisplayOrder.numberOf(displayed.last())
        )
    }

    @Test
    fun `start targets the chapter whose own number is 1`() {
        val stored = newestFirstStore()
        val first = ChapterDisplayOrder.firstChapter(stored)
        assertEquals(1f, first?.let { ChapterDisplayOrder.numberOf(it) })
        assertEquals(
            "https://x.example/book/chapter-1", first?.remoteId
        )
    }

    @Test
    fun `reader header agrees with the opened chapter's own heading`() {
        val stored = newestFirstStore()
        // The regression, end to end: Oldest view → first row →
        // the opened chapter is chapter 1, and the header shows
        // chapter 1's own heading.
        val opened = ChapterDisplayOrder.ordered(
            stored, newestFirst = false
        ).first()
        val ownNumber = ChapterDisplayOrder.numberOf(opened)
        val header = ReaderCounters.headerLine(
            chapterTitle = opened.title,
            chapterIndex = opened.index,
            chapterCount = stored.size,
            ownNumber = ownNumber
        )
        assertEquals("Chapter 1", header)
        val footer = ReaderCounters.footerLine(
            chapterIndex = opened.index,
            chapterCount = stored.size,
            ownNumber = ownNumber
        )
        assertEquals("Chapter 1 of 170", footer)
    }

    @Test
    fun `a chapter opened out of place is labelled by its own number`() {
        // The device failure in its pure form: chapter 158's
        // content sat at list position 0. Its label must be
        // 158's — never "Chapter 1 of 170".
        val stored = newestFirstStore()
        val chapter158 = stored.first {
            ChapterDisplayOrder.numberOf(it) == 158f
        }
        val header = ReaderCounters.headerLine(
            chapterTitle = chapter158.title,
            chapterIndex = 0,
            chapterCount = stored.size,
            ownNumber = 158f
        )
        assertEquals("Chapter 158: A Bad Art Dealer?", header)
        val footer = ReaderCounters.footerLine(
            chapterIndex = 0,
            chapterCount = stored.size,
            ownNumber = 158f
        )
        assertEquals("Chapter 158 of 170", footer)
        assertFalse(footer.contains("Chapter 1 of"))
    }

    @Test
    fun `unnumbered lists keep their stored order`() {
        val stored = (0 until 5).map { index ->
            Chapter(
                id = (index + 1).toLong(),
                bookId = 1L,
                index = index,
                title = "Part ${index + 1}",
                remoteId = "https://x.example/book/part-${index + 1}",
                updatedAt = 0L
            )
        }
        val displayed = ChapterDisplayOrder.ordered(
            stored, newestFirst = false
        )
        assertEquals(stored, displayed)
    }
}
