package app.xreader.komikku

import app.xreader.domain.content.ChapterPage
import app.xreader.domain.content.ChapterPages
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * v1.5.8 (A7): the poison clear is claimed AFTER the fetch
 * outcome is known — there is no process-wide once-set. A
 * stored prose body is cleared; the fetch FAILS and re-stores
 * prose (the re-poisoned branch F1 §5 named); the second open
 * clears AGAIN and the retry can succeed. Drives the shipped
 * seam (PoisonClearingEnsurer, the pipeline's own logic).
 */
class V158PoisonClearReclearTest {

    @Test
    fun `failed fetch re-poison is cleared again on second open`() = runTest {
        // The stored shape: starts as a prose body with NO page
        // record (poison). ensure #1 fails and re-stores prose;
        // ensure #2 succeeds and stores a real page record.
        var storedPages: ChapterPages? = null
        var hasBody = true
        var clears = 0
        var ensureCalls = 0
        val realPages = ChapterPages(
            pages = listOf(
                ChapterPage("https://img/1.jpg"),
                ChapterPage("https://img/2.jpg")
            )
        )
        val ensurer = PoisonClearingEnsurer(
            readStoredShape = { _, _ ->
                PoisonClearingEnsurer.StoredShape(
                    hasBody = hasBody, pages = storedPages
                )
            },
            clearStoredBody = { _, _ ->
                clears++
                hasBody = false
                storedPages = null
            },
            ensure = { _, _ ->
                ensureCalls++
                if (ensureCalls == 1) {
                    // Failed fetch: the prose road re-stores its
                    // body (re-poison); no pages land.
                    hasBody = true
                    storedPages = null
                    null
                } else {
                    hasBody = true
                    storedPages = realPages
                    realPages
                }
            }
        )

        val first = ensurer.ensuredPages(bookId = 7, chapterId = 42)
        assertNull(first)
        assertEquals("first open clears the poison once", 1, clears)

        val second = ensurer.ensuredPages(bookId = 7, chapterId = 42)
        assertEquals(
            "second open clears the re-poison — no once-set block",
            2, clears
        )
        assertEquals(realPages, second)

        // Self-limiting: with a page record stored, a third open
        // clears nothing.
        val third = ensurer.ensuredPages(bookId = 7, chapterId = 42)
        assertEquals(2, clears)
        assertEquals(realPages, third)
    }

    @Test
    fun `no stored body means no clear and the fetch decides`() = runTest {
        var clears = 0
        val pages = ChapterPages(pages = listOf(ChapterPage("https://img/1.jpg")))
        val ensurer = PoisonClearingEnsurer(
            readStoredShape = { _, _ ->
                PoisonClearingEnsurer.StoredShape(hasBody = false, pages = null)
            },
            clearStoredBody = { _, _ -> clears++ },
            ensure = { _, _ -> pages }
        )
        assertEquals(pages, ensurer.ensuredPages(1, 2))
        assertEquals(0, clears)
    }
}
