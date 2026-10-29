package app.xreader.data.library

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v1.0.1 hotfix: the out-of-Room per-book metadata line —
 * round-trip, garbage tolerance, and the new-chapter maths.
 */
class BookMetaTest {

    // ── encode / decode ───────────────────────────────────────────

    @Test
    fun `encode uses the compact status tracked lastSeen new format`() {
        assertEquals(
            "READING|1|120|3",
            BookMeta.encode(
                BookMeta(
                    status = ReadingStatus.READING,
                    tracked = true,
                    lastSeenChapterCount = 120,
                    newChapterCount = 3
                )
            )
        )
        assertEquals("NONE|0|0|0", BookMeta.encode(BookMeta()))
    }

    @Test
    fun `decode round-trips encode for every status`() {
        for (status in ReadingStatus.entries) {
            val meta = BookMeta(
                status = status,
                tracked = status != ReadingStatus.NONE,
                lastSeenChapterCount = 42,
                newChapterCount = 5
            )
            assertEquals(meta, BookMeta.decode(BookMeta.encode(meta)))
        }
        assertEquals(BookMeta(), BookMeta.decode(BookMeta.encode(BookMeta())))
    }

    @Test
    fun `decode tolerates garbage and falls back to defaults`() {
        assertEquals(BookMeta(), BookMeta.decode(null))
        assertEquals(BookMeta(), BookMeta.decode(""))
        assertEquals(BookMeta(), BookMeta.decode("   "))
        assertEquals(BookMeta(), BookMeta.decode("not a meta line"))
        assertEquals(BookMeta(), BookMeta.decode("|||"))
        assertEquals(BookMeta(), BookMeta.decode("BOGUS|x|NaN|-4"))
    }

    @Test
    fun `decode tolerates partial and extra fields`() {
        assertEquals(
            BookMeta(status = ReadingStatus.COMPLETED),
            BookMeta.decode("COMPLETED")
        )
        assertEquals(
            BookMeta(
                status = ReadingStatus.ON_HOLD,
                tracked = true,
                lastSeenChapterCount = 7
            ),
            BookMeta.decode("ON_HOLD|true|7")
        )
        assertEquals(
            BookMeta(
                status = ReadingStatus.DROPPED,
                tracked = true,
                lastSeenChapterCount = 7,
                newChapterCount = 2
            ),
            BookMeta.decode("DROPPED|1|7|2|unexpected|extra")
        )
    }

    @Test
    fun `decode clamps negative counts to zero`() {
        assertEquals(
            BookMeta(status = ReadingStatus.READING),
            BookMeta.decode("READING|0|-10|-1")
        )
    }

    // ── newChapters / markSeen ────────────────────────────────────

    @Test
    fun `newChapters is the growth since last seen`() {
        val meta = BookMeta(lastSeenChapterCount = 100)
        assertEquals(15, BookMeta.newChapters(meta, currentTotal = 115))
        assertEquals(0, BookMeta.newChapters(meta, currentTotal = 100))
        // A book that shrank (chapters removed upstream) is not negative.
        assertEquals(0, BookMeta.newChapters(meta, currentTotal = 90))
    }

    @Test
    fun `newChapters is zero before a baseline exists`() {
        assertEquals(0, BookMeta.newChapters(BookMeta(), currentTotal = 250))
        assertEquals(
            0,
            BookMeta.newChapters(
                BookMeta(lastSeenChapterCount = 0, newChapterCount = 9),
                currentTotal = 250
            )
        )
    }

    @Test
    fun `markSeen sets the baseline and clears the new count`() {
        val meta = BookMeta(
            status = ReadingStatus.READING,
            tracked = true,
            lastSeenChapterCount = 100,
            newChapterCount = 12
        )
        val seen = BookMeta.markSeen(meta, currentTotal = 112)
        assertEquals(112, seen.lastSeenChapterCount)
        assertEquals(0, seen.newChapterCount)
        // Everything else is preserved.
        assertEquals(ReadingStatus.READING, seen.status)
        assertEquals(true, seen.tracked)
        assertEquals(0, BookMeta.newChapters(seen, currentTotal = 112))
        assertEquals(3, BookMeta.newChapters(seen, currentTotal = 115))
    }
}
