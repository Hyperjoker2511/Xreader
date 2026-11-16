package app.xreader.data.library

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Cover size (v1.6.2): the Novels grid's own size control.
 * Pins the target widths, the fit-to-screen column
 * resolution (including parity with the width tiers' counts
 * at Normal / App size 1.00, so a user who never touches the
 * control sees no change), the clamps, and the prefs
 * round-trip.
 */
class CoverSizeTest {

    private class FakeDataStore(
        initial: Preferences = emptyPreferences()
    ) : DataStore<Preferences> {
        private val state = MutableStateFlow(initial)
        override val data: Flow<Preferences> = state
        override suspend fun updateData(
            transform: suspend (Preferences) -> Preferences
        ): Preferences {
            val updated = transform(state.value)
            state.value = updated
            return updated
        }
    }

    // ── Target widths ────────────────────────────────────────

    @Test
    fun `presets name their target widths and follow scales the normal target`() {
        assertEquals(64, CoverSizeLogic.targetWidthDp(CoverSize.SMALL, 1.0f))
        assertEquals(84, CoverSizeLogic.targetWidthDp(CoverSize.NORMAL, 1.0f))
        assertEquals(118, CoverSizeLogic.targetWidthDp(CoverSize.LARGE, 1.0f))
        // Explicit sizes ignore the App size — they win for
        // covers only, exactly as picked.
        assertEquals(64, CoverSizeLogic.targetWidthDp(CoverSize.SMALL, 0.70f))
        assertEquals(118, CoverSizeLogic.targetWidthDp(CoverSize.LARGE, 1.30f))
        // Follow: the Normal target scaled by the App size.
        assertEquals(84, CoverSizeLogic.targetWidthDp(CoverSize.FOLLOW_APP, 1.0f))
        assertEquals(71, CoverSizeLogic.targetWidthDp(CoverSize.FOLLOW_APP, 0.85f))
        assertEquals(59, CoverSizeLogic.targetWidthDp(CoverSize.FOLLOW_APP, 0.70f))
        assertEquals(109, CoverSizeLogic.targetWidthDp(CoverSize.FOLLOW_APP, 1.30f))
        // An out-of-range factor is held to the App size range.
        assertEquals(109, CoverSizeLogic.targetWidthDp(CoverSize.FOLLOW_APP, 2.0f))
    }

    // ── Fit resolution ───────────────────────────────────────

    @Test
    fun `columns fit the available width and clamp to the sane range`() {
        assertEquals(4, CoverSizeLogic.columnsForWidth(371, 84))
        assertEquals(3, CoverSizeLogic.columnsForWidth(320, 84))
        assertEquals(6, CoverSizeLogic.columnsForWidth(560, 84))
        // Too narrow for even two targets: still two columns —
        // a one-column grid is the failure this control fixes.
        assertEquals(2, CoverSizeLogic.columnsForWidth(100, 84))
        // Absurd width: the cap holds.
        assertEquals(6, CoverSizeLogic.columnsForWidth(2000, 64))
    }

    @Test
    fun `follow at normal reproduces the tier counts on real phone widths`() {
        // Available width = screen width − the Library's own
        // 20 dp side padding, as the screen resolves it.
        fun followColumns(screenWidthDp: Int) =
            CoverSizeLogic.resolveColumns(
                screenWidthDp - 40, CoverSize.FOLLOW_APP, 1.0f
            )
        assertEquals(3, followColumns(360))  // COMPACT tier: 3
        assertEquals(4, followColumns(411))  // Galaxy M31 class: 4
        assertEquals(4, followColumns(480))  // REGULAR tier: 4
        assertEquals(6, followColumns(640))  // EXPANDED tier: 6
    }

    @Test
    fun `small covers fit more across and large covers fewer`() {
        // The M31's width: Small → 5 across, Large → 2 across.
        assertEquals(
            5,
            CoverSizeLogic.resolveColumns(411 - 40, CoverSize.SMALL, 1.0f)
        )
        assertEquals(
            2,
            CoverSizeLogic.resolveColumns(411 - 40, CoverSize.LARGE, 1.0f)
        )
        // The small-phone shape: at App size Small the followed
        // covers shrink too — 480 dp wide fits 5 across, not
        // the 4 the tier alone would give.
        assertEquals(
            5,
            CoverSizeLogic.resolveColumns(480 - 40, CoverSize.FOLLOW_APP, 0.85f)
        )
        // At App size 0.70 the followed grid reaches the cap.
        assertEquals(
            6,
            CoverSizeLogic.resolveColumns(480 - 40, CoverSize.FOLLOW_APP, 0.70f)
        )
    }

    // ── Prefs round-trip ─────────────────────────────────────

    @Test
    fun `cover size defaults to follow and round-trips`() = runTest {
        val prefs = LibraryPrefs(FakeDataStore())
        assertEquals(CoverSize.FOLLOW_APP, prefs.state.first().coverSize)
        assertEquals(CoverSize.FOLLOW_APP, LibraryState().coverSize)
        prefs.setCoverSize(CoverSize.LARGE)
        assertEquals(CoverSize.LARGE, prefs.state.first().coverSize)
        prefs.setCoverSize(CoverSize.SMALL)
        assertEquals(CoverSize.SMALL, prefs.state.first().coverSize)
    }

    @Test
    fun `an unreadable stored cover size reads as follow`() = runTest {
        val prefs = LibraryPrefs(
            FakeDataStore(
                preferencesOf(
                    stringPreferencesKey("library.cover_size") to "junk"
                )
            )
        )
        assertEquals(CoverSize.FOLLOW_APP, prefs.state.first().coverSize)
    }

    @Test
    fun `reset view returns cover size to follow`() = runTest {
        val prefs = LibraryPrefs(FakeDataStore())
        prefs.setCoverSize(CoverSize.LARGE)
        prefs.resetViewToDefaults()
        assertEquals(CoverSize.FOLLOW_APP, prefs.state.first().coverSize)
    }
}
