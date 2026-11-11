package app.xreader.ui.book

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The book page's Read guard (v1.0.4, D14): an extension book whose
 * loaded chapter list is empty blocks Read with a plain reason —
 * every other combination leaves Read alone.
 */
class ChapterReadGuardTest {

    @Test
    fun `empty list on a settled source book blocks read`() {
        // v1.5.8 (A3/A4): the settled-empty LINK source — the
        // list came back empty, and Repair is its real door.
        val reason = ChapterReadGuard.blockedReason(
            isExtensionBook = true,
            chapterCount = 0,
            pageLoading = false,
            chaptersLoading = false,
            verdict = ChaptersVerdict.EMPTY_SOURCE,
            isLinkSource = true
        )
        assertNotNull(reason)
        assertTrue(reason!!.contains("No chapters found"))
        assertTrue(reason.contains("came back empty"))
        assertTrue(reason.contains("Repair"))
    }

    @Test
    fun `error verdict keeps its honest error wording`() {
        // v1.5.8 (A3): ERROR ≠ EMPTY — the fetch threw, and the
        // pill says so with the recorded error, never "empty".
        val reason = ChapterReadGuard.blockedReason(
            isExtensionBook = true,
            chapterCount = 0,
            pageLoading = false,
            chaptersLoading = false,
            verdict = ChaptersVerdict.ERROR,
            chaptersError = "the site didn't answer",
            isLinkSource = true
        )
        assertNotNull(reason)
        assertTrue(reason!!.contains("couldn't be loaded"))
        assertTrue(reason.contains("the site didn't answer"))
        assertTrue(!reason.contains("came back empty"))
    }

    @Test
    fun `repair sentence is emitted only for link sources`() {
        // v1.5.8 (A4): player / declarative / live kinds never
        // get the Repair sentence — they name the door that
        // exists (Re-check here / the source's test-drive).
        for (verdict in ChaptersVerdict.entries) {
            val reason = ChapterReadGuard.blockedReason(
                isExtensionBook = true,
                chapterCount = 0,
                pageLoading = false,
                chaptersLoading = false,
                verdict = verdict,
                isLinkSource = false
            )
            assertNotNull(reason)
            assertTrue(
                "no Repair advice for non-link kind ($verdict)",
                !reason!!.contains("Repair")
            )
            assertTrue(reason.contains("Re-check"))
            assertTrue(reason.contains("test-drive"))
        }
        // And the link kind keeps it, both verdicts.
        for (verdict in ChaptersVerdict.entries) {
            val reason = ChapterReadGuard.blockedReason(
                isExtensionBook = true,
                chapterCount = 0,
                pageLoading = false,
                chaptersLoading = false,
                verdict = verdict,
                isLinkSource = true
            )
            assertTrue(reason!!.contains("Repair"))
        }
    }

    @Test
    fun `chapters present never blocks`() {
        assertNull(
            ChapterReadGuard.blockedReason(
                isExtensionBook = true,
                chapterCount = 12,
                pageLoading = false,
                chaptersLoading = false
            )
        )
    }

    @Test
    fun `still loading never blocks`() {
        assertNull(
            ChapterReadGuard.blockedReason(
                isExtensionBook = true,
                chapterCount = 0,
                pageLoading = true,
                chaptersLoading = false
            )
        )
        assertNull(
            ChapterReadGuard.blockedReason(
                isExtensionBook = true,
                chapterCount = 0,
                pageLoading = false,
                chaptersLoading = true
            )
        )
    }

    @Test
    fun `cta state - loading over an empty list is finding, never ready`() {
        // v1.5.1 (item 4): the crack this closes — blockedReason
        // is null while loading, which used to leave the CTA a
        // live violet pill over 0 chapters.
        org.junit.Assert.assertEquals(
            ReadCtaState.FINDING_CHAPTERS,
            ChapterReadGuard.ctaState(
                isExtensionBook = true,
                chapterCount = 0,
                pageLoading = false,
                chaptersLoading = true
            )
        )
        org.junit.Assert.assertEquals(
            ReadCtaState.FINDING_CHAPTERS,
            ChapterReadGuard.ctaState(
                isExtensionBook = true,
                chapterCount = 0,
                pageLoading = true,
                chaptersLoading = false
            )
        )
    }

    @Test
    fun `cta state - settled empty is unavailable, chapters present is ready`() {
        org.junit.Assert.assertEquals(
            ReadCtaState.CHAPTERS_UNAVAILABLE,
            ChapterReadGuard.ctaState(
                isExtensionBook = true,
                chapterCount = 0,
                pageLoading = false,
                chaptersLoading = false
            )
        )
        org.junit.Assert.assertEquals(
            ReadCtaState.READY,
            ChapterReadGuard.ctaState(
                isExtensionBook = true,
                chapterCount = 3,
                pageLoading = true,
                chaptersLoading = true
            )
        )
        org.junit.Assert.assertEquals(
            ReadCtaState.READY,
            ChapterReadGuard.ctaState(
                isExtensionBook = false,
                chapterCount = 0,
                pageLoading = true,
                chaptersLoading = false
            )
        )
    }

    @Test
    fun `local books are never blocked by this guard`() {
        assertNull(
            ChapterReadGuard.blockedReason(
                isExtensionBook = false,
                chapterCount = 0,
                pageLoading = false,
                chaptersLoading = false
            )
        )
    }
}
