package app.xreader.ui.sources

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0 pins for field bug F1 (2026-11-01): the global-search
 * progress line's rules.
 *
 * v1.3.4 built the line as "Searching source {done + 1} of
 * {total}…" while the local library/content scan ran FIRST
 * with the total still 0 — the screen sat on "Searching
 * source 1 of 0…" looking frozen. The rules pinned here: the
 * position never exceeds the total; a zero total never poses
 * as a source count; the library phase is named, not counted;
 * the bar fraction stays in [0, 1].
 */
class V135SearchFixTest {

    @Test
    fun `position never exceeds the total`() {
        assertEquals(
            "Searching source 3 of 3…",
            SearchProgressLine.text(done = 5, total = 3, searchingLibrary = false)
        )
        assertEquals(
            "Searching source 3 of 3…",
            SearchProgressLine.text(done = 2, total = 3, searchingLibrary = false)
        )
        assertEquals(
            "Searching source 1 of 3…",
            SearchProgressLine.text(done = 0, total = 3, searchingLibrary = false)
        )
    }

    @Test
    fun `zero total never poses as a source count`() {
        val line = SearchProgressLine.text(
            done = 0, total = 0, searchingLibrary = false
        )
        assertEquals("Searching…", line)
        assertTrue("no fake count", !line.contains("of 0"))
    }

    @Test
    fun `library phase is named truthfully`() {
        assertEquals(
            "Searching your library…",
            SearchProgressLine.text(
                done = 0, total = 0, searchingLibrary = true
            )
        )
        assertEquals(
            "Searching source 1 of 4… · also searching your library",
            SearchProgressLine.text(
                done = 0, total = 4, searchingLibrary = true
            )
        )
    }

    @Test
    fun `fraction stays inside the bar`() {
        assertEquals(0f, SearchProgressLine.fraction(0, 0), 0.0001f)
        assertEquals(0f, SearchProgressLine.fraction(0, 4), 0.0001f)
        assertEquals(0.5f, SearchProgressLine.fraction(2, 4), 0.0001f)
        assertEquals(1f, SearchProgressLine.fraction(4, 4), 0.0001f)
        assertEquals(1f, SearchProgressLine.fraction(9, 4), 0.0001f)
    }
}
