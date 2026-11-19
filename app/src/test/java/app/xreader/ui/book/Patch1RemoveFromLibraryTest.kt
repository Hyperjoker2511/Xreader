package app.xreader.ui.book

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.7 Patch 1 (item 1): the un-favourite prompt verdict —
 * the "also remove from library?" prompt appears exactly
 * when the favourite was the book's ONLY library claim.
 * Any other claim (downloaded chapters, a reading status,
 * a shelf) keeps un-favourite plain, with no prompt.
 */
class Patch1RemoveFromLibraryTest {

    @Test
    fun `no other claim prompts`() {
        assertTrue(
            favouriteIsOnlyLibraryClaim(
                downloaded = false, hasStatus = false, onShelf = false
            )
        )
    }

    @Test
    fun `downloaded chapters never prompt`() {
        assertFalse(
            favouriteIsOnlyLibraryClaim(
                downloaded = true, hasStatus = false, onShelf = false
            )
        )
    }

    @Test
    fun `a reading status never prompts`() {
        assertFalse(
            favouriteIsOnlyLibraryClaim(
                downloaded = false, hasStatus = true, onShelf = false
            )
        )
    }

    @Test
    fun `a shelf never prompts`() {
        assertFalse(
            favouriteIsOnlyLibraryClaim(
                downloaded = false, hasStatus = false, onShelf = true
            )
        )
    }

    @Test
    fun `every claim together never prompts`() {
        assertFalse(
            favouriteIsOnlyLibraryClaim(
                downloaded = true, hasStatus = true, onShelf = true
            )
        )
    }
}
