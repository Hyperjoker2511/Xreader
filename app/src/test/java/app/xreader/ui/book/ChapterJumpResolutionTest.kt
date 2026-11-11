package app.xreader.ui.book

import app.xreader.data.db.Chapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.5.7 (Finding 5, second bug): the "Jump to chapter…"
 * field's resolution contract, pinned against the owner's
 * device evidence — a 1,200-chapter Novelight-shaped book
 * where typing "1" used to FILTER to "863 of 1200" rows
 * (every row whose text contained a "1", the "14 Dec 2024"
 * dates included) and chapter 1 was unreachable.
 */
class ChapterJumpResolutionTest {

    private fun chapter(
        index: Int,
        number: Int,
        dateText: String? = null,
        title: String = "Chapter $number"
    ) = Chapter(
        bookId = 1L,
        index = index,
        title = title,
        remoteId = "https://novelight.example/book/x/chapter/${900_000 + number}",
        updatedAt = 0L,
        dateText = dateText
    )

    /** 1,200 chapters in reading order; every row carries a
     * date containing a "1" somewhere ("14 Dec 2024" style),
     * exactly the trap the old text filter fell into. */
    private val book1200: List<Chapter> = (1..1200).map { n ->
        chapter(
            index = n - 1,
            number = n,
            dateText = "14 Dec 2024"
        )
    }

    @Test
    fun `typing 1 resolves chapter 1, not 863 filtered rows`() {
        val resolved = ChapterJump.resolve("1", book1200)
        assertEquals(1, resolved?.let { ChapterJump.identityNumber(it)?.toInt() })
        assertEquals(0, ChapterJump.resolvePosition("1", book1200))
    }

    @Test
    fun `typing 863 resolves chapter 863 exactly`() {
        val resolved = ChapterJump.resolve("863", book1200)
        assertEquals(863, resolved?.let { ChapterJump.identityNumber(it)?.toInt() })
        assertEquals(862, ChapterJump.resolvePosition("863", book1200))
    }

    @Test
    fun `a date containing 1 is never matched as chapter 1`() {
        // Chapter 463's row carries "14 Dec 2024" — under the
        // old filter it surfaced for the query "1". Here the
        // only chapter "1" may resolve to is chapter 1 itself.
        val resolved = ChapterJump.resolve("1", book1200)
        assertTrue(resolved != null)
        assertEquals("Chapter 1", resolved!!.title)
        // And chapter 463 resolves by its own number only.
        assertEquals(
            "Chapter 463",
            ChapterJump.resolve("463", book1200)?.title
        )
    }

    @Test
    fun `chapter 12 is not chapter 1 - no substring matching`() {
        val small = (1..20).map { n -> chapter(index = n - 1, number = n) }
        assertEquals("Chapter 1", ChapterJump.resolve("1", small)?.title)
        assertEquals("Chapter 12", ChapterJump.resolve("12", small)?.title)
    }

    @Test
    fun `out of range, zero, empty and text entries stay put`() {
        assertNull(ChapterJump.resolve("1201", book1200))
        assertEquals(-1, ChapterJump.resolvePosition("1201", book1200))
        assertNull(ChapterJump.resolve("0", book1200))
        assertNull(ChapterJump.resolve("", book1200))
        assertNull(ChapterJump.resolve("   ", book1200))
        assertNull(ChapterJump.resolve("prologue", book1200))
        // Non-numeric text is not a jump entry at all (it
        // belongs to the separate chapter search).
        assertTrue(!ChapterJump.isJumpQuery("prologue"))
        assertTrue(ChapterJump.isJumpQuery("42"))
        assertTrue(ChapterJump.isJumpQuery(" 42 "))
    }

    @Test
    fun `novelight house style - number-first titles resolve`() {
        // Novelight prints "760 chapter" — the number leads.
        val house = (1..50).map { n ->
            chapter(index = n - 1, number = n, title = "$n chapter")
        }
        assertEquals(1, ChapterJump.resolve("1", house)
            ?.let { ChapterJump.identityNumber(it)?.toInt() })
        assertEquals(50, ChapterJump.resolve("50", house)
            ?.let { ChapterJump.identityNumber(it)?.toInt() })
    }

    @Test
    fun `decimal specials resolve, bare url ids do not hijack`() {
        val withSpecial = listOf(
            chapter(index = 0, number = 10, title = "Chapter 10"),
            chapter(index = 1, number = 0, title = "Chapter 10.5")
                .copy(remoteId = "https://x.example/chapter/777"),
            chapter(index = 2, number = 11, title = "Chapter 11")
        )
        // "10.5" is pinned by its title, parsed as a decimal.
        assertEquals(
            "Chapter 10.5",
            ChapterJump.resolve("10.5", withSpecial)?.title
        )
        // The special's URL tail (777) is a database id, not a
        // chapter number: typing it must not jump anywhere.
        assertNull(ChapterJump.resolve("777", withSpecial))
    }

    @Test
    fun `newest-first display finds the same chapter`() {
        val descending = book1200.asReversed()
        val resolved = ChapterJump.resolve("1", descending)
        assertEquals("Chapter 1", resolved?.title)
        assertEquals(1199, ChapterJump.resolvePosition("1", descending))
    }

    @Test
    fun `headless list - position 1 is not chapter 1`() {
        // The Finding-5 stored shape: chapters 464..1200 with
        // indices re-based to 0..736. "1" must resolve to
        // NOTHING (stay put) — never to the row at position 1,
        // which is chapter 464 wearing a false identity.
        val headless = (464..1200).mapIndexed { i, n ->
            chapter(index = i, number = n)
        }
        assertNull(ChapterJump.resolve("1", headless))
        // A number that IS present still resolves.
        assertEquals(
            "Chapter 464",
            ChapterJump.resolve("464", headless)?.title
        )
    }

    @Test
    fun `unnumbered books fall back to ordinal position`() {
        val titled = listOf("Prologue", "Dawn", "Dusk", "Finale")
            .mapIndexed { i, title ->
                Chapter(
                    bookId = 1L, index = i, title = title,
                    remoteId = "https://x.example/read/$i", updatedAt = 0L
                )
            }
        assertEquals("Dawn", ChapterJump.resolve("2", titled)?.title)
        assertEquals("Prologue", ChapterJump.resolve("1", titled)?.title)
        assertNull(ChapterJump.resolve("5", titled))
    }
}
