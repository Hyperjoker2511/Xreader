package app.xreader.data.ai

import app.xreader.data.db.ReadingProgress
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Catch me up spoiler bound (v1.0.3): the recap may use
 * summaries only up to the reading position. A chapter still being
 * read is excluded — its summary would describe its ending — until
 * the reader is effectively at its end.
 */
class CatchUpBoundsTest {

    private fun progress(chapterIndex: Int, fraction: Float) = ReadingProgress(
        bookId = 1,
        chapterId = 100L + chapterIndex,
        chapterIndex = chapterIndex,
        fraction = fraction,
        updatedAt = 0L
    )

    @Test
    fun `no progress means nothing is safe`() {
        assertEquals(-1, CatchUpBounds.throughIndex(null, 10))
    }

    @Test
    fun `part-way through a chapter excludes that chapter`() {
        assertEquals(3, CatchUpBounds.throughIndex(progress(4, 0.5f), 10))
        assertEquals(0, CatchUpBounds.throughIndex(progress(1, 0.2f), 10))
    }

    @Test
    fun `part-way through the first chapter means nothing yet`() {
        assertEquals(-1, CatchUpBounds.throughIndex(progress(0, 0.5f), 10))
    }

    @Test
    fun `effectively finished chapter is included`() {
        assertEquals(4, CatchUpBounds.throughIndex(progress(4, 0.95f), 10))
        assertEquals(
            4,
            CatchUpBounds.throughIndex(
                progress(4, CatchUpBounds.FINISHED_FRACTION), 10
            )
        )
    }

    @Test
    fun `the bound never exceeds the book`() {
        assertEquals(2, CatchUpBounds.throughIndex(progress(9, 1f), 3))
        assertEquals(-1, CatchUpBounds.throughIndex(progress(0, 1f), 0))
    }
}
