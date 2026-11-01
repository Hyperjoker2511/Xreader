package app.xreader.ui.sources

import app.xreader.data.extension.ContentRating
import app.xreader.data.extension.ContentVisibility
import app.xreader.data.extension.ForeignSite
import app.xreader.data.extension.KeiyoushiIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Keiyoushi player (K3): the rows() readiness switch — a
 * row is READY when its package is installed + loaded in
 * the player; every other player state shows itself
 * honestly with its action; an untouched package with a
 * catalogue name offers Install instead of the old
 * conversion dead-end; a package-less entry keeps the
 * legacy conversion wording.
 */
class KeiyoushiPlayerRowsTest {

    private val pkg = "eu.kanade.tachiyomi.extension.en.zeta"

    private val sites = listOf(
        ForeignSite(
            name = "Zeta Panels", lang = "en",
            baseUrl = "https://zeta.example",
            contentRating = ContentRating.SAFE,
            packageName = pkg
        ),
        ForeignSite(
            name = "Orphan Ink", lang = "en",
            baseUrl = "https://orphan.example",
            contentRating = ContentRating.SAFE
            // no packageName: not a player package
        )
    )

    private fun rowsWith(
        player: Map<String, KeiyoushiIndex.PlayerPackageState>
    ): List<KeiyoushiIndex.Row> = KeiyoushiIndex.rows(
        entries = keiyoushiEntries(sites),
        convertedHosts = emptySet(),
        visibility = ContentVisibility.SHOW_ALL,
        playerStates = player
    )

    private fun zeta(
        rows: List<KeiyoushiIndex.Row>
    ): KeiyoushiIndex.Row = rows.first { it.entry.name == "Zeta Panels" }

    @Test
    fun `an untouched package offers install`() {
        val row = zeta(rowsWith(emptyMap()))
        assertEquals(KeiyoushiIndex.State.NOT_INSTALLED, row.state)
        assertEquals("Install", row.state.label)
        assertEquals(KeiyoushiIndex.RowAction.INSTALL, row.action)
    }

    @Test
    fun `a package-less entry keeps the conversion wording`() {
        val row = rowsWith(emptyMap())
            .first { it.entry.name == "Orphan Ink" }
        assertEquals(
            KeiyoushiIndex.State.NEEDS_CONVERSION, row.state
        )
        assertEquals(KeiyoushiIndex.RowAction.NONE, row.action)
    }

    @Test
    fun `an installed and loaded package is ready with remove`() {
        val row = zeta(
            rowsWith(
                mapOf(
                    pkg to KeiyoushiIndex.PlayerPackageState(
                        KeiyoushiIndex.PlayerPackageState.Kind.READY
                    )
                )
            )
        )
        assertEquals(KeiyoushiIndex.State.READY, row.state)
        assertEquals(KeiyoushiIndex.RowAction.REMOVE, row.action)
    }

    @Test
    fun `an update shows as update`() {
        val row = zeta(
            rowsWith(
                mapOf(
                    pkg to KeiyoushiIndex.PlayerPackageState(
                        KeiyoushiIndex.PlayerPackageState
                            .Kind.UPDATE_AVAILABLE
                    )
                )
            )
        )
        assertEquals(
            KeiyoushiIndex.State.UPDATE_AVAILABLE, row.state
        )
        assertEquals(KeiyoushiIndex.RowAction.UPDATE, row.action)
    }

    @Test
    fun `a staged install asks for review`() {
        val row = zeta(
            rowsWith(
                mapOf(
                    pkg to KeiyoushiIndex.PlayerPackageState(
                        KeiyoushiIndex.PlayerPackageState
                            .Kind.AWAITING_CONSENT
                    )
                )
            )
        )
        assertEquals(
            KeiyoushiIndex.State.AWAITING_CONSENT, row.state
        )
        assertEquals(KeiyoushiIndex.RowAction.REVIEW, row.action)
    }

    @Test
    fun `a failure shows its reason and offers a retry`() {
        val row = zeta(
            rowsWith(
                mapOf(
                    pkg to KeiyoushiIndex.PlayerPackageState(
                        KeiyoushiIndex.PlayerPackageState.Kind.FAILED,
                        reason = "signed by a different key"
                    )
                )
            )
        )
        assertEquals(KeiyoushiIndex.State.FAILED, row.state)
        assertEquals("signed by a different key", row.detail)
        assertEquals(KeiyoushiIndex.RowAction.INSTALL, row.action)
    }

    @Test
    fun `an incompatible package shows its reason and no action`() {
        val row = zeta(
            rowsWith(
                mapOf(
                    pkg to KeiyoushiIndex.PlayerPackageState(
                        KeiyoushiIndex.PlayerPackageState
                            .Kind.INCOMPATIBLE,
                        reason = "needs extension library 1.4"
                    )
                )
            )
        )
        assertEquals(KeiyoushiIndex.State.INCOMPATIBLE, row.state)
        assertEquals("needs extension library 1.4", row.detail)
        assertEquals(KeiyoushiIndex.RowAction.NONE, row.action)
    }

    @Test
    fun `a busy state shows its words and no action`() {
        val row = zeta(
            rowsWith(
                mapOf(
                    pkg to KeiyoushiIndex.PlayerPackageState(
                        KeiyoushiIndex.PlayerPackageState
                            .Kind.DOWNLOADING
                    )
                )
            )
        )
        assertEquals(KeiyoushiIndex.State.DOWNLOADING, row.state)
        assertEquals("Downloading…", row.state.label)
        assertEquals(KeiyoushiIndex.RowAction.NONE, row.action)
        assertNull(row.detail)
    }

    @Test
    fun `a native conversion still reads ready without the player`() {
        val rows = KeiyoushiIndex.rows(
            entries = keiyoushiEntries(sites),
            convertedHosts = setOf("zeta.example"),
            visibility = ContentVisibility.SHOW_ALL,
            playerStates = emptyMap()
        )
        assertEquals(KeiyoushiIndex.State.READY, zeta(rows).state)
        assertTrue(
            zeta(rows).action == KeiyoushiIndex.RowAction.NONE
        )
    }
}
