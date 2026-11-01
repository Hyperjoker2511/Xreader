package app.xreader.ui.sources

import app.xreader.data.extension.ContentRating
import app.xreader.data.extension.ContentVisibility
import app.xreader.data.extension.ForeignSite
import app.xreader.data.extension.KeiyoushiIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Manga programme (M3): the Keiyoushi repo screen's feed —
 * the loaded foreign-repo site list maps to index entries
 * with names, languages, addresses and ratings intact, and
 * KeiyoushiIndex.rows() over them states each row truly:
 * Ready only for a host with an installed manga conversion,
 * "Needs the manga conversion" otherwise, and the content
 * preference removing hidden ratings outright.
 */
class KeiyoushiRowsTest {

    private val sites = listOf(
        ForeignSite(
            name = "Zeta Panels", lang = "en",
            baseUrl = "https://zeta.example",
            contentRating = ContentRating.SAFE
        ),
        ForeignSite(
            name = "Alpha Ink", lang = "en",
            baseUrl = "https://alpha.example",
            contentRating = ContentRating.MIXED
        ),
        ForeignSite(
            name = "After Dark", lang = "en",
            baseUrl = "https://dark.example",
            contentRating = ContentRating.ADULT
        )
    )

    @Test
    fun `the sidecar sites map to entries field for field`() {
        val entries = keiyoushiEntries(sites)
        assertEquals(3, entries.size)
        val zeta = entries.first { it.name == "Zeta Panels" }
        assertEquals("en", zeta.lang)
        assertEquals("https://zeta.example", zeta.baseUrl)
        assertEquals(ContentRating.SAFE, zeta.rating)
        assertEquals("zeta.example", zeta.host)
        val dark = entries.first { it.name == "After Dark" }
        assertEquals(ContentRating.ADULT, dark.rating)
    }

    @Test
    fun `rows state ready only where a conversion is installed`() {
        val rows = KeiyoushiIndex.rows(
            entries = keiyoushiEntries(sites),
            convertedHosts = setOf("alpha.example"),
            visibility = ContentVisibility.SHOW_ALL
        )
        // Alphabetical, every row present under Show all.
        assertEquals(
            listOf("After Dark", "Alpha Ink", "Zeta Panels"),
            rows.map { it.entry.name }
        )
        val byName = rows.associateBy { it.entry.name }
        assertEquals(
            KeiyoushiIndex.State.READY, byName["Alpha Ink"]!!.state
        )
        assertEquals(
            "Ready", byName["Alpha Ink"]!!.state.label
        )
        assertEquals(
            KeiyoushiIndex.State.NEEDS_CONVERSION,
            byName["Zeta Panels"]!!.state
        )
        assertEquals(
            "Needs the manga conversion",
            byName["Zeta Panels"]!!.state.label
        )
        // The badge rides along for the rated rows.
        assertEquals("18+", byName["After Dark"]!!.badge)
    }

    @Test
    fun `the default preference hides the 18 plus rows outright`() {
        val rows = KeiyoushiIndex.rows(
            entries = keiyoushiEntries(sites),
            convertedHosts = emptySet(),
            visibility = ContentVisibility.HIDE_ADULT
        )
        assertEquals(
            listOf("Alpha Ink", "Zeta Panels"),
            rows.map { it.entry.name }
        )
        assertTrue(
            rows.all {
                it.state == KeiyoushiIndex.State.NEEDS_CONVERSION
            }
        )
    }
}
