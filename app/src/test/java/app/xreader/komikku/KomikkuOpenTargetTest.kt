package app.xreader.komikku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Patch 14 (Manga Continue) proofs: the open-target resolution
 * behind KomikkuReaderViewModel.open(). The convicted defect was
 * case (b): an open handed no chapter landed on chapter 1 even
 * though ReadingProgress named the reader's real chapter.
 */
class KomikkuOpenTargetTest {

    private val ids = listOf(11L, 12L, 13L, 14L)

    @Test
    fun handedChapterWins() {
        // (a) handed chapter wins, even over a different saved one.
        assertEquals(
            13L,
            resolveMangaOpenTargetId(ids, handedChapterId = 13L, savedChapterId = 12L)
        )
        assertEquals(
            11L,
            resolveMangaOpenTargetId(ids, handedChapterId = 11L, savedChapterId = null)
        )
    }

    @Test
    fun noHandedChapterFallsBackToSavedChapter() {
        // (b) the Activity hands -1 when no chapter was specified.
        assertEquals(
            14L,
            resolveMangaOpenTargetId(ids, handedChapterId = -1L, savedChapterId = 14L)
        )
        // A stale handed id (chapter deleted since) behaves the same.
        assertEquals(
            12L,
            resolveMangaOpenTargetId(ids, handedChapterId = 999L, savedChapterId = 12L)
        )
    }

    @Test
    fun noHandedAndNoSavedFallsBackToFirstChapter() {
        // (c) nothing handed, nothing saved: the first chapter.
        assertEquals(
            11L,
            resolveMangaOpenTargetId(ids, handedChapterId = -1L, savedChapterId = null)
        )
    }

    @Test
    fun staleSavedChapterFallsBackToFirstChapter() {
        // The saved chapter itself is gone (deleted/renumbered):
        // never land on a chapter that does not exist.
        assertEquals(
            11L,
            resolveMangaOpenTargetId(ids, handedChapterId = -1L, savedChapterId = 777L)
        )
    }

    @Test
    fun emptyChapterListHasNoTarget() {
        assertNull(
            resolveMangaOpenTargetId(emptyList(), handedChapterId = -1L, savedChapterId = 12L)
        )
    }
}
