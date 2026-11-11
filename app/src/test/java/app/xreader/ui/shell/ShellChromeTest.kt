package app.xreader.ui.shell

import app.xreader.domain.reader.ReaderRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The immersive-route contract: reading surfaces own the whole
 * screen; every tab destination keeps its shell chrome.
 * v1.5.8 (F1/F3): the local readers and the Ask AI page join the
 * novel reader + login (Finding 5: the local route was born
 * unregistered in v1.5.6 and the dock stayed over both readers). */
class ShellChromeTest {

    @Test
    fun `reader and login patterns are immersive`() {
        assertTrue(ShellChrome.isImmersiveRoutePattern("reader/{bookId}/{chapterIndex}"))
        assertTrue(ShellChrome.isImmersiveRoutePattern("login"))
    }

    @Test
    fun `local reader pattern is immersive (F1)`() {
        assertTrue(ShellChrome.isImmersiveRoutePattern("local/{bookId}?target={target}"))
    }

    @Test
    fun `ask ai pattern is immersive (F3)`() {
        assertTrue(ShellChrome.isImmersiveRoutePattern("ai/{bookId}/{chapterIndex}"))
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

    @Test
    fun `concrete local and ai routes are immersive`() {
        assertTrue(ShellChrome.isImmersive("local/42"))
        assertTrue(ShellChrome.isImmersive("local/42?target=%7B%22page%22%3A3%7D"))
        assertTrue(ShellChrome.isImmersive("ai/42/3"))
        assertFalse(ShellChrome.isImmersive("library"))
    }

    /**
     * The enumeration test Finding 5 named: EVERY verdict shape
     * [dispatchReaderRoute] can produce must resolve to an
     * immersive treatment — an in-shell route whose pattern AND
     * concrete form are immersive, or (Komikku) no shell route at
     * all because the verdict opens its own Activity, which the
     * shell can never wrap in chrome. A future reader route
     * registered without immersion fails here the day it is born.
     */
    @Test
    fun `every reader route verdict resolves to an immersive treatment`() {
        val verdicts: List<ReaderRoute> = listOf(
            ReaderRoute.NovelReader(bookId = 42L, chapterIndex = 3),
            ReaderRoute.LocalDoc(bookId = 42L),
            ReaderRoute.Komikku(bookId = 42L, chapterRowId = 7L)
        )
        var activityVerdicts = 0
        for (verdict in verdicts) {
            val target = readerRouteShellTarget(verdict)
            if (target == null) {
                // No shell route: the verdict must be the Activity
                // one — immersive by construction.
                activityVerdicts++
                assertTrue(verdict is ReaderRoute.Komikku)
                continue
            }
            assertTrue(
                "concrete route $target must be immersive",
                ShellChrome.isImmersive(target)
            )
        }
        assertEquals(1, activityVerdicts)

        // And the PATTERNS those verdicts navigate under are the
        // registered immersive patterns (the shell tests the
        // destination's pattern, not the concrete route).
        assertTrue(ShellChrome.isImmersiveRoutePattern("reader/{bookId}/{chapterIndex}"))
        assertTrue(ShellChrome.isImmersiveRoutePattern("local/{bookId}?target={target}"))
    }

    @Test
    fun `komikku verdict has no shell target`() {
        assertNull(readerRouteShellTarget(ReaderRoute.Komikku(1L, null)))
        assertEquals(
            "reader/9/2",
            readerRouteShellTarget(ReaderRoute.NovelReader(9L, 2))
        )
        assertEquals(
            "local/9",
            readerRouteShellTarget(ReaderRoute.LocalDoc(9L))
        )
    }
}
