package app.xreader.ui.localreader

import android.app.Application
import app.xreader.data.engine.RiverDocument
import app.xreader.data.engine.RiverSectionMap
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v1.7 Patch 2 (item 0e): the river position core's round trips
 * and the restore confirmation rule (0c).
 *
 * The round trips run against a REAL session over the fixture
 * publication (locators are built exactly as production builds
 * them — `locatorForSection` + [EpubRiverPosition]) and a
 * synthetic [RiverDocument] char map, so section + in-section
 * progression must survive locator → anchor → locator,
 * including the block-0 and last-block edges.
 *
 * [EpubRiverPosition.restoreConfirms] is the pure 0c rule: the
 * landing is verified against the TARGET, never against
 * wherever the first application happened to land.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class Patch2EpubRiverPositionTest {

    @get:Rule val temp = TemporaryFolder()

    /** Two sections: 4 blocks of 250 chars, then 2 blocks of
     * 200/300 chars. */
    private fun document() = RiverDocument(
        html = "",
        sections = listOf(
            RiverSectionMap(
                charCount = 1000,
                blockStarts = listOf(0, 250, 500, 750),
                blockCharCounts = listOf(250, 250, 250, 250),
                estimatedHeightPx = 800
            ),
            RiverSectionMap(
                charCount = 500,
                blockStarts = listOf(0, 200),
                blockCharCounts = listOf(200, 300),
                estimatedHeightPx = 400
            )
        ),
        totalChars = 1500,
        idTargets = emptyMap(),
        images = emptyList(),
        plateSections = emptyList(),
        linksByKey = emptyMap(),
        failedSections = 0,
        fromCache = false
    )

    @Test
    fun `locator to anchor round trip preserves section and block`() {
        val epub = LocalReaderEpubFixture.writeEpub(temp.root)
        val doc = document()
        runBlocking {
            val session = LocalReaderEpubFixture.openSession(temp.root, epub)
            try {
                val cases = listOf(
                    0 to 0, // first block edge
                    0 to 2,
                    0 to 3, // last block of the section
                    1 to 0,
                    1 to 1 // last block of the book
                )
                for ((section, block) in cases) {
                    val locator = EpubRiverPosition.locator(
                        session, doc, section, block
                    )
                    assertNotNull("locator for $section:$block", locator)
                    val anchor = EpubRiverPosition.anchorFor(
                        doc, section, locator!!.locations.progression
                    )
                    assertEquals(
                        "round trip $section:$block",
                        section to block, anchor
                    )
                }
                // The book fraction rides along: section 1,
                // block 1 = (1000 + 200) / 1500 of the book.
                val locator = EpubRiverPosition.locator(session, doc, 1, 1)
                assertEquals(
                    0.8,
                    locator!!.locations.totalProgression!!,
                    0.0001
                )
            } finally {
                session.close()
            }
        }
    }

    @Test
    fun `anchorFor edges`() {
        val doc = document()
        // No progression (a progression-less saved locator) is
        // the chapter start.
        assertEquals(0 to 0, EpubRiverPosition.anchorFor(doc, 0, null))
        assertEquals(1 to 0, EpubRiverPosition.anchorFor(doc, 1, 0.0))
        // Progression 1.0 lands on the section's last block.
        assertEquals(0 to 3, EpubRiverPosition.anchorFor(doc, 0, 1.0))
        // An unknown section resolves to its own start.
        assertEquals(5 to 0, EpubRiverPosition.anchorFor(doc, 5, 0.5))
    }

    @Test
    fun `restoreConfirms verifies the target with the exact tolerance rule`() {
        // Landed == target confirms.
        assertTrue(EpubRiverPosition.restoreConfirms(3 to 10, 3 to 10))
        // Within ±2 blocks confirms (tall blocks straddle the
        // viewport top either side of the scrolled-to element).
        assertTrue(EpubRiverPosition.restoreConfirms(3 to 12, 3 to 10))
        assertTrue(EpubRiverPosition.restoreConfirms(3 to 8, 3 to 10))
        // Beyond ±2 does not confirm.
        assertFalse(EpubRiverPosition.restoreConfirms(3 to 13, 3 to 10))
        // A section-top landing against a mid-chapter target
        // does NOT confirm — the silent-fallback signature; it
        // must not unlock position reports.
        assertFalse(EpubRiverPosition.restoreConfirms(3 to 0, 3 to 10))
        assertFalse(EpubRiverPosition.restoreConfirms(3 to 0, 3 to 3))
        // A genuine chapter-start target confirms on block 0…
        assertTrue(EpubRiverPosition.restoreConfirms(3 to 0, 3 to 0))
        // …and a block-0 landing within the tall-block window
        // of a near-start target still confirms.
        assertTrue(EpubRiverPosition.restoreConfirms(3 to 0, 3 to 2))
        assertTrue(EpubRiverPosition.restoreConfirms(3 to 1, 3 to 0))
        // A different section never confirms.
        assertFalse(EpubRiverPosition.restoreConfirms(2 to 10, 3 to 10))
    }
}
