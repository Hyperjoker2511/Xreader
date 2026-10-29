package app.xreader.ui.shell

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The immersive-route contract (v1.0.1): reader + login own the
 * whole screen; every tab destination keeps its shell chrome. */
class ShellChromeTest {

    @Test
    fun `reader and login patterns are immersive`() {
        assertTrue(ShellChrome.isImmersiveRoutePattern("reader/{bookId}/{chapterIndex}"))
        assertTrue(ShellChrome.isImmersiveRoutePattern("login"))
    }

    @Test
    fun `tab and detail routes keep chrome`() {
        assertFalse(ShellChrome.isImmersiveRoutePattern("library"))
        assertFalse(ShellChrome.isImmersiveRoutePattern("book/{bookId}"))
        assertFalse(ShellChrome.isImmersiveRoutePattern("appearance"))
        assertFalse(ShellChrome.isImmersiveRoutePattern(null))
    }

    @Test
    fun `concrete reader routes are immersive`() {
        assertTrue(ShellChrome.isImmersive("reader/42/3"))
        assertTrue(ShellChrome.isImmersive("login"))
        assertFalse(ShellChrome.isImmersive("book/42"))
        assertFalse(ShellChrome.isImmersive(null))
    }
}
