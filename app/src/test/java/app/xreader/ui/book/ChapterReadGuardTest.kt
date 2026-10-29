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
        val reason = ChapterReadGuard.blockedReason(
            isExtensionBook = true,
            chapterCount = 0,
            pageLoading = false,
            chaptersLoading = false
        )
        assertNotNull(reason)
        assertTrue(reason!!.contains("No chapters found"))
        assertTrue(reason.contains("Repair"))
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
