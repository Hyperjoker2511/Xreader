package app.xreader.ui.localreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.mediatype.MediaType

/**
 * v1.7 Patch 1 (item 2): the EPUB mode flip carries the
 * LIVE place, not the open-time one. Flipping Scrolling ↔
 * Page-by-page mid-book used to hand over the place the
 * book opened at; the handover now reads the ViewModel's
 * live locator first and falls back to the open-time place
 * only before the first position report lands.
 */
class Patch1EpubModeFlipTest {

    // Readium 3.1.2: Url.invoke / MediaType.invoke return
    // NULLABLE results, so the locators are built from the
    // non-null factories instead (fromDecodedPath, XHTML).
    private fun locator(href: String, progression: Double): Locator =
        Locator(
            href = Url.fromDecodedPath(href),
            mediaType = MediaType.XHTML,
            title = href,
            locations = Locator.Locations(progression = progression)
        )

    @Test
    fun `live place wins over the open-time place`() {
        val openTime = locator("chapter-1.xhtml", 0.1)
        val live = locator("chapter-12.xhtml", 0.5)
        assertEquals(
            "the flip must carry the place the reader is " +
                "actually at, not where the book opened",
            live, effectiveEpubLocator(live, openTime)
        )
    }

    @Test
    fun `no live report yet falls back to the open-time place`() {
        val openTime = locator("chapter-1.xhtml", 0.1)
        assertEquals(
            "before the first position report the open-time " +
                "place is the only place there is",
            openTime, effectiveEpubLocator(null, openTime)
        )
    }

    @Test
    fun `no place at all stays null`() {
        assertNull(effectiveEpubLocator(null, null))
    }
}
