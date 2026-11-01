package app.xreader.data.library

import app.xreader.data.db.Book
import app.xreader.data.db.Chapter
import app.xreader.data.db.ReadingProgress
import app.xreader.data.db.SourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Progress-linking pure-core pins (Cycle 1, F3): grouping by
 * the same-story matcher with refusals cut out, positions in
 * true-chapter-number space, and the resume plan's safety
 * rules — forward only, exact number or nearest-with-notice,
 * never silently chapter 1.
 */
class ProgressLinkingTest {

    private fun book(
        id: Long,
        title: String,
        author: String = ""
    ) = Book(
        id = id,
        title = title,
        author = author,
        sourceType = SourceType.EXTENSION,
        extensionId = 1L,
        remoteId = "src:book$id",
        addedAt = 1L,
        updatedAt = 1L
    )

    private fun chapter(
        id: Long,
        bookId: Long,
        index: Int,
        title: String,
        read: Boolean = false
    ) = Chapter(
        id = id, bookId = bookId, index = index, title = title,
        read = read, updatedAt = 1L
    )

    /** Chapters "Chapter 1..count" for [bookId], ids from
     * [firstId]; [readUpTo] chapters (by number) start read. */
    private fun numbered(
        bookId: Long,
        count: Int,
        firstId: Long,
        readUpTo: Int = 0
    ): List<Chapter> = (1..count).map { n ->
        chapter(firstId + n - 1, bookId, n - 1, "Chapter $n", read = n <= readUpTo)
    }

    // ── Grouping ──────────────────────────────────────────

    @Test
    fun `title and author variants of the same work link`() {
        val a = book(1, "Shadow Slave", "Guiltythree")
        val b = book(2, "Shadow Slave [Completed]", "")
        val library = listOf(a, b, book(3, "Another Novel", "Someone"))
        val siblings = ProgressLinker.siblingsOf(a, library, emptySet())
        assertEquals(listOf(2L), siblings.map { it.id })
    }

    @Test
    fun `different works do not link, and an author clash blocks a title match`() {
        val a = book(1, "Sword God", "Author A")
        val otherTitle = book(2, "Sword Saint", "Author A")
        val otherAuthor = book(3, "Sword God", "Author B")
        val library = listOf(a, otherTitle, otherAuthor)
        assertTrue(ProgressLinker.siblingsOf(a, library, emptySet()).isEmpty())
    }

    @Test
    fun `a refused pair is cut out in both directions`() {
        val a = book(1, "Shadow Slave", "Guiltythree")
        val b = book(2, "Shadow Slave", "Guiltythree")
        val library = listOf(a, b)
        val refused = setOf(RefusedPair.of(1L, 2L))
        assertTrue(ProgressLinker.siblingsOf(a, library, refused).isEmpty())
        assertTrue(ProgressLinker.siblingsOf(b, library, refused).isEmpty())
    }

    @Test
    fun `a group of three shares, and a refusal only severs its own pair`() {
        val a = book(1, "Shadow Slave", "Guiltythree")
        val b = book(2, "Shadow Slave", "Guiltythree")
        val c = book(3, "Shadow Slave", "Guiltythree")
        val library = listOf(a, b, c)
        assertEquals(
            setOf(2L, 3L),
            ProgressLinker.siblingsOf(a, library, emptySet()).map { it.id }.toSet()
        )
        val refused = setOf(RefusedPair.of(1L, 2L))
        // A lost B but keeps C; C still sees both.
        assertEquals(
            listOf(3L),
            ProgressLinker.siblingsOf(a, library, refused).map { it.id }
        )
        assertEquals(
            setOf(1L, 2L),
            ProgressLinker.siblingsOf(c, library, refused).map { it.id }.toSet()
        )
    }

    // ── Effective numbers ─────────────────────────────────

    @Test
    fun `effective numbers come from the chapters themselves, not stored order`() {
        // Stored newest-first: index order is 3, 2, 1.
        val chapters = listOf(
            chapter(13, 1, 0, "Chapter 3"),
            chapter(12, 1, 1, "Chapter 2"),
            chapter(11, 1, 2, "Chapter 1")
        )
        val numbers = ProgressLinker.effectiveNumbers(chapters)
        assertEquals(1f, numbers.getValue(11), 0.0001f)
        assertEquals(2f, numbers.getValue(12), 0.0001f)
        assertEquals(3f, numbers.getValue(13), 0.0001f)
    }

    @Test
    fun `unnumbered chapters ride after the numbered one they follow`() {
        val chapters = listOf(
            chapter(10, 1, 0, "Prologue"),
            chapter(11, 1, 1, "Chapter 1"),
            chapter(12, 1, 2, "Interlude"),
            chapter(13, 1, 3, "Chapter 2")
        )
        val numbers = ProgressLinker.effectiveNumbers(chapters)
        assertTrue(numbers.getValue(10) < 1f)
        assertEquals(1f, numbers.getValue(11), 0.0001f)
        assertTrue(numbers.getValue(12) in 1f..2f)
        assertEquals(2f, numbers.getValue(13), 0.0001f)
    }

    @Test
    fun `a book with no numbers at all is placed by reading order`() {
        val chapters = listOf(
            chapter(10, 1, 0, "Beginning"),
            chapter(11, 1, 1, "Middle"),
            chapter(12, 1, 2, "End")
        )
        val numbers = ProgressLinker.effectiveNumbers(chapters)
        assertEquals(1f, numbers.getValue(10), 0.0001f)
        assertEquals(2f, numbers.getValue(11), 0.0001f)
        assertEquals(3f, numbers.getValue(12), 0.0001f)
    }

    // ── Positions ─────────────────────────────────────────

    @Test
    fun `position comes from the saved progress anchor and fraction`() {
        val chapters = numbered(1, 100, firstId = 1000)
        val anchor = chapters[29] // Chapter 30
        val progress = ReadingProgress(
            bookId = 1, chapterId = anchor.id, chapterIndex = anchor.index,
            fraction = 0.5f, scrollFraction = 0.4f, updatedAt = 7L
        )
        val position = ProgressLinker.positionOf(chapters, progress)
        assertEquals(LinkPosition(30f, 0.5f, 0.4f), position)
    }

    @Test
    fun `a dangling progress pointer falls back to the read flags`() {
        val chapters = numbered(1, 100, firstId = 1000, readUpTo = 4)
        val progress = ReadingProgress(
            bookId = 1, chapterId = 999_999L, chapterIndex = 77,
            fraction = 0.5f, updatedAt = 7L
        )
        assertEquals(
            LinkPosition(4f, 1f),
            ProgressLinker.positionOf(chapters, progress)
        )
    }

    @Test
    fun `an unread book has no position`() {
        val chapters = numbered(1, 10, firstId = 1000)
        assertNull(ProgressLinker.positionOf(chapters, null))
    }

    // ── The plan ──────────────────────────────────────────

    @Test
    fun `an exact number match carries the fraction and marks earlier chapters`() {
        val target = numbered(2, 100, firstId = 2000)
        val plan = ProgressLinker.plan(
            targetChapters = target,
            ownPosition = null,
            siblingPositions = mapOf(1L to LinkPosition(50f, 0.25f, 0.4f))
        )
        assertNotNull(plan)
        plan!!
        assertTrue(plan.exact)
        assertEquals("Chapter 50", plan.targetChapter.title)
        assertEquals(0.25f, plan.fraction, 0.0001f)
        assertEquals(0.4f, plan.scrollFraction, 0.0001f)
        assertEquals(1L, plan.sourceBookId)
        // Chapters 1..49 marked, chapter 50 itself not.
        assertEquals((2000L..2048L).toList(), plan.markReadIds)
    }

    @Test
    fun `differing numbering lands on the nearest chapter and drops the fraction`() {
        // This copy numbers by tens: 10, 20, …, 100.
        val target = (1..10).map { i ->
            chapter(3000L + i, 2, i - 1, "Chapter ${i * 10}")
        }
        val plan = ProgressLinker.plan(
            targetChapters = target,
            ownPosition = null,
            siblingPositions = mapOf(1L to LinkPosition(47f, 0.3f))
        )
        assertNotNull(plan)
        plan!!
        assertFalse(plan.exact)
        assertEquals("Chapter 50", plan.targetChapter.title)
        assertEquals(0f, plan.fraction, 0.0001f)
        assertEquals(
            listOf(3001L, 3002L, 3003L, 3004L),
            plan.markReadIds
        )
    }

    @Test
    fun `a nearest-match tie lands on the lower chapter, never past unread content`() {
        val target = (1..10).map { i ->
            chapter(3000L + i, 2, i - 1, "Chapter ${i * 10}")
        }
        val plan = ProgressLinker.plan(
            targetChapters = target,
            ownPosition = null,
            siblingPositions = mapOf(1L to LinkPosition(45f, 0f))
        )
        assertEquals("Chapter 40", plan!!.targetChapter.title)
        assertFalse(plan.exact)
    }

    @Test
    fun `a sibling behind or level with this copy changes nothing`() {
        val target = numbered(2, 100, firstId = 2000)
        val own = LinkPosition(60f, 0f)
        assertNull(
            ProgressLinker.plan(target, own, mapOf(1L to LinkPosition(50f, 0.9f)))
        )
        assertNull(
            ProgressLinker.plan(
                target, LinkPosition(50f, 0.5f), mapOf(1L to LinkPosition(50f, 0.5f))
            )
        )
    }

    @Test
    fun `a far-ahead sibling on a short copy lands on its last chapter, never chapter 1`() {
        val target = numbered(2, 5, firstId = 2000)
        val plan = ProgressLinker.plan(
            targetChapters = target,
            ownPosition = null,
            siblingPositions = mapOf(1L to LinkPosition(900f, 0f))
        )
        assertNotNull(plan)
        assertEquals("Chapter 5", plan!!.targetChapter.title)
        assertFalse(plan.exact)
    }

    @Test
    fun `in a group of three the furthest sibling wins`() {
        val target = numbered(4, 100, firstId = 4000)
        val plan = ProgressLinker.plan(
            targetChapters = target,
            ownPosition = null,
            siblingPositions = mapOf(
                2L to LinkPosition(30f, 0f),
                3L to LinkPosition(80f, 0.1f)
            )
        )
        assertNotNull(plan)
        assertEquals(3L, plan!!.sourceBookId)
        assertEquals("Chapter 80", plan.targetChapter.title)
    }

    @Test
    fun `a sibling's finished chapter resumes at the next chapter, marked read`() {
        // Sibling read chapters 1..40 (read-flag position:
        // chapter 40 at fraction 1).
        val siblingChapters = numbered(1, 50, firstId = 1000, readUpTo = 40)
        val siblingPosition = ProgressLinker.positionOf(siblingChapters, null)
        assertEquals(LinkPosition(40f, 1f), siblingPosition)
        val target = numbered(2, 100, firstId = 2000)
        val plan = ProgressLinker.plan(
            targetChapters = target,
            ownPosition = null,
            siblingPositions = mapOf(1L to siblingPosition!!)
        )
        assertNotNull(plan)
        plan!!
        assertTrue(plan.exact)
        assertEquals("Chapter 41", plan.targetChapter.title)
        assertEquals(0f, plan.fraction, 0.0001f)
        // Everything up to and including chapter 40 is read.
        assertEquals((2000L..2039L).toList(), plan.markReadIds)
    }

    @Test
    fun `already-read chapters below the point are not marked again`() {
        val target = numbered(2, 100, firstId = 2000, readUpTo = 20)
        val plan = ProgressLinker.plan(
            targetChapters = target,
            ownPosition = null,
            siblingPositions = mapOf(1L to LinkPosition(30f, 0f))
        )
        assertEquals((2020L..2028L).toList(), plan!!.markReadIds)
    }
}
