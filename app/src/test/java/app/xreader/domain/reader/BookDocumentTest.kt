package app.xreader.domain.reader

import app.xreader.data.db.Chapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.7 Patch 2 (item 1e): the BookDocument char map — base
 * offsets, the measured-average estimator (and its
 * all-unmeasured pin), estimate → exact refinement, boundary
 * ties in `locate`, zero-length chapters, and rebuild
 * carry-over by chapter id.
 */
class BookDocumentTest {

    private fun chapter(id: Long, index: Int, wordCount: Int = 0) = Chapter(
        id = id, bookId = 1L, index = index, title = "Chapter ${index + 1}",
        wordCount = wordCount, updatedAt = 1L
    )

    private fun chapters(count: Int, wordCount: Int = 0): List<Chapter> =
        (0 until count).map { chapter((it + 1).toLong(), it, wordCount) }

    @Test
    fun baseOffsetsAccumulate() {
        // Nothing measured: the word-count fallback prices each
        // chapter at wordCount * 6.
        val doc = BookDocument(
            listOf(
                chapter(1, 0, wordCount = 100),
                chapter(2, 1, wordCount = 200),
                chapter(3, 2, wordCount = 300)
            )
        )
        assertEquals(600, doc.lengthOf(0))
        assertEquals(1200, doc.lengthOf(1))
        assertEquals(1800, doc.lengthOf(2))
        assertEquals(0, doc.baseOf(0))
        assertEquals(600, doc.baseOf(1))
        assertEquals(1800, doc.baseOf(2))
        assertEquals(3600, doc.totalChars)
        assertFalse(doc.isExact(0))
        assertEquals(3, doc.chapterCount)
    }

    @Test
    fun unmeasuredChaptersArePricedAtTheMeasuredAverage() {
        val doc = BookDocument(chapters(3, wordCount = 10))
        doc.setExactLength(1L, 900)
        assertTrue(doc.isExact(0))
        assertEquals(900, doc.lengthOf(0))
        // The estimator amendment: NOT wordCount * 6 (= 60) —
        // the book's measured average.
        assertEquals(900, doc.lengthOf(1))
        assertEquals(900, doc.lengthOf(2))
        assertEquals(2700, doc.totalChars)
        assertEquals(1800, doc.baseOf(2))
    }

    @Test
    fun refinementUpdatesTotalAndLaterBases() {
        val doc = BookDocument(chapters(3, wordCount = 100))
        doc.setExactLength(1L, 5000)
        // Average pricing while only chapter 1 is measured.
        assertEquals(15000, doc.totalChars)
        assertEquals(10000, doc.baseOf(2))
        doc.setExactLength(3L, 100)
        // Measured average is now (5000 + 100) / 2 = 2550.
        assertEquals(5000, doc.lengthOf(0))
        assertEquals(2550, doc.lengthOf(1))
        assertEquals(100, doc.lengthOf(2))
        assertEquals(7650, doc.totalChars)
        assertEquals(7550, doc.baseOf(2))
        assertTrue(doc.isExact(2))
    }

    @Test
    fun allUnmeasuredBookDoesNotCollapse() {
        // The estimator amendment's pin: wordCount defaults to
        // 0 for unstored chapters, so the naive wordCount * 6
        // price is 1 char each — and once ONE chapter is parsed
        // the naive total collapses toward that parsed prefix
        // (a mid-book place would read ~100%). The measured
        // average keeps the whole book priced.
        val doc = BookDocument(chapters(3, wordCount = 0))
        assertEquals(3, doc.totalChars) // 1-char floor each
        doc.setExactLength(1L, 4000)
        assertEquals(12000, doc.totalChars)
        assertEquals(4000, doc.baseOf(1))
        assertEquals(8000, doc.baseOf(2))
        // Start of chapter 3 sits at 2/3 of the book, not ~1.0.
        val fraction = doc.bookOffset(2, 0).toFloat() / doc.totalChars
        assertEquals(2f / 3f, fraction, 0.001f)
    }

    @Test
    fun locateBoundaryBelongsToTheLaterChapter() {
        val doc = BookDocument(chapters(3))
        doc.setExactLength(1L, 100)
        doc.setExactLength(2L, 0) // zero-length chapter
        doc.setExactLength(3L, 100)
        assertEquals(200, doc.totalChars)
        assertEquals(0 to 0, doc.locate(0))
        assertEquals(0 to 99, doc.locate(99))
        // The boundary at 100 is chapter 3's start (index 2):
        // chapter 2 (index 1) owns no offsets.
        assertEquals(2 to 0, doc.locate(100))
        assertEquals(2 to 50, doc.locate(150))
        // offset == totalChars maps to the last chapter's end.
        assertEquals(2 to 100, doc.locate(200))
        // Past-the-end clamps the same way.
        assertEquals(2 to 100, doc.locate(10_000))
    }

    @Test
    fun bookOffsetLocateRoundTrip() {
        val doc = BookDocument(chapters(4, wordCount = 50))
        doc.setExactLength(2L, 700)
        doc.setExactLength(4L, 300)
        for (index in 0 until doc.chapterCount) {
            val length = doc.lengthOf(index)
            if (length == 0) continue
            for (local in listOf(0, length / 2, length - 1)) {
                assertEquals(
                    "round trip at chapter $index offset $local",
                    index to local,
                    doc.locate(doc.bookOffset(index, local))
                )
            }
        }
    }

    @Test
    fun zeroLengthChapterIsAddressableButOwnsNoOffsets() {
        // An image-only chapter: its display text is empty, so
        // its exact length is 0.
        val doc = BookDocument(chapters(2))
        doc.setExactLength(1L, 0)
        doc.setExactLength(2L, 500)
        assertTrue(doc.isExact(0))
        assertEquals(0, doc.lengthOf(0))
        assertEquals(0, doc.baseOf(0))
        assertEquals(0, doc.bookOffset(0, 0))
        // locate never lands inside it.
        assertEquals(1 to 0, doc.locate(0))
        assertEquals(1 to 499, doc.locate(499))
    }

    @Test
    fun rebuildCarriesExactLengthsForSurvivingChapters() {
        val doc = BookDocument(chapters(3, wordCount = 10))
        doc.setExactLength(1L, 111)
        doc.setExactLength(2L, 222)
        // Chapter 1 removed, chapter 4 added.
        val rebuilt = doc.rebuild(
            listOf(chapter(2, 0, wordCount = 10), chapter(4, 1, wordCount = 10))
        )
        assertEquals(2, rebuilt.chapterCount)
        assertTrue(rebuilt.isExact(0)) // chapter 2's exact carried
        assertEquals(222, rebuilt.lengthOf(0))
        assertFalse(rebuilt.isExact(1)) // chapter 4 is new: estimate
        // Unknown ids are ignored by the feed.
        rebuilt.setExactLength(99L, 5000)
        assertEquals(222 + 222, rebuilt.totalChars)
    }

    @Test
    fun fractionStaysOrderedUnderRefinement() {
        val doc = BookDocument(chapters(4, wordCount = 100))
        fun fractionOf(index: Int, local: Int): Float =
            doc.bookOffset(index, local).toFloat() / doc.totalChars
        val before1 = fractionOf(1, 300)
        val before2 = fractionOf(2, 300)
        assertTrue(before1 < before2)
        assertTrue(before1 in 0f..1f && before2 in 0f..1f)
        // Refining a LATER chapter (its true length is far
        // below the estimate) shifts both fractions, but their
        // order — the reader's forward progress — must hold.
        doc.setExactLength(4L, 60)
        val after1 = fractionOf(1, 300)
        val after2 = fractionOf(2, 300)
        assertTrue(after1 < after2)
        assertTrue(after1 in 0f..1f && after2 in 0f..1f)
    }
}
