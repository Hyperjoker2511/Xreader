package app.xreader.data.library

import app.xreader.data.db.Chapter
import app.xreader.data.db.ReadingProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * v1.7 Patch 2 (item 1d/1e): the ProgressLinking pin.
 *
 * The pre-existing muddle, stated in the spec and measured
 * here rather than fixed: [ProgressLinker.positionOf] reads
 * the progress row's `fraction` as the WITHIN-CHAPTER fraction
 * of the anchor chapter (it lands in `LinkPosition.fraction`,
 * documented as "the fraction of the way through that
 * chapter"), while the novel writer stores the BOOK fraction
 * there — count-based before this patch, CHAR-based from this
 * patch on (item 1b). The writer's stored meaning does not
 * change in this patch; what changes is the ruler the book
 * fraction is computed with. This test pins positionOf's
 * behaviour under a char-based book fraction so the ruler
 * change's effect on progress-linking is on the record:
 * positionOf passes the row's fraction through verbatim as
 * its within-chapter fraction, whatever ruler produced it.
 * Fixing the muddle itself is NOT in this patch.
 */
class Patch2ProgressLinkingPinTest {

    private fun chapter(id: Long, bookId: Long, index: Int, title: String) =
        Chapter(
            id = id, bookId = bookId, index = index, title = title,
            read = false, updatedAt = 1L
        )

    @Test
    fun positionOfPassesTheStoredBookFractionThroughAsWithinChapter() {
        val bookId = 5L
        val chapters = (1..10).map { n ->
            chapter(n.toLong(), bookId, n - 1, "Chapter $n")
        }
        // A place in chapter 3 whose CHAR-based book fraction
        // is 0.52 (the book's early chapters are long, so the
        // count-based ruler would have said ~0.24 for the same
        // place). The row's scrollFraction is the honest
        // within-chapter carrier: 0.40.
        val row = ReadingProgress(
            bookId = bookId,
            chapterId = 3L,
            chapterIndex = 2,
            charOffset = 12_000,
            fraction = 0.52f,
            scrollFraction = 0.40f,
            updatedAt = 1L
        )
        val position = ProgressLinker.positionOf(chapters, row)
        assertNotNull(position)
        // The anchor chapter resolves by its true number…
        assertEquals(3f, position!!.number, 0.0001f)
        // …the stored BOOK fraction is carried verbatim into
        // the within-chapter slot (the measured muddle)…
        assertEquals(0.52f, position.fraction, 0.0001f)
        // …while the row's scrollFraction rides its own field,
        // untouched by the ruler change.
        assertEquals(0.40f, position.scrollFraction, 0.0001f)
        assertEquals(3.52f, position.scalar, 0.0001f)
    }
}
