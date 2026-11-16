package app.xreader.data.library

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Nav-bar styles (v1.6.2): the two new LibraryPrefs values —
 * the indicator style and its colour (mode + ARGB) — default
 * to the shipped look, round-trip, validate on read, and
 * clear with the rest of the view reset.
 */
class LibraryPrefsNavIndicatorTest {

    private class FakeDataStore : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = state
        override suspend fun updateData(
            transform: suspend (Preferences) -> Preferences
        ): Preferences {
            val updated = transform(state.value)
            state.value = updated
            return updated
        }

        fun seed(prefs: Preferences) {
            state.value = prefs
        }
    }

    @Test
    fun `defaults are the shipped look`() = runTest {
        val state = LibraryPrefs(FakeDataStore()).state.first()
        assertEquals(NavIndicatorStyle.GOOEY, state.navIndicatorStyle)
        assertEquals(
            NavIndicatorColourMode.THEME, state.navIndicatorColourMode
        )
        assertEquals(
            DEFAULT_NAV_INDICATOR_ARGB, state.navIndicatorColourArgb
        )
    }

    @Test
    fun `every indicator style round-trips`() = runTest {
        val prefs = LibraryPrefs(FakeDataStore())
        NavIndicatorStyle.entries.forEach { style ->
            prefs.setNavIndicatorStyle(style)
            assertEquals(style, prefs.state.first().navIndicatorStyle)
        }
    }

    @Test
    fun `picking a colour stores it and flips the mode to custom`() = runTest {
        val prefs = LibraryPrefs(FakeDataStore())
        prefs.setNavIndicatorColour(0xFF336699L)
        val state = prefs.state.first()
        assertEquals(NavIndicatorColourMode.CUSTOM, state.navIndicatorColourMode)
        assertEquals(0xFF336699L, state.navIndicatorColourArgb)
    }

    @Test
    fun `a picked colour is forced opaque`() = runTest {
        val prefs = LibraryPrefs(FakeDataStore())
        prefs.setNavIndicatorColour(0x00336699L)
        assertEquals(0xFF336699L, prefs.state.first().navIndicatorColourArgb)
    }

    @Test
    fun `returning to theme keeps the stored colour`() = runTest {
        val prefs = LibraryPrefs(FakeDataStore())
        prefs.setNavIndicatorColour(0xFF336699L)
        prefs.setNavIndicatorColourMode(NavIndicatorColourMode.THEME)
        val state = prefs.state.first()
        assertEquals(NavIndicatorColourMode.THEME, state.navIndicatorColourMode)
        assertEquals(0xFF336699L, state.navIndicatorColourArgb)
    }

    @Test
    fun `unknown stored values fall back to the shipped look`() = runTest {
        val store = FakeDataStore()
        store.seed(
            mutablePreferencesOf(
                stringPreferencesKey("library.nav_indicator_style") to "WOBBLY",
                stringPreferencesKey("library.nav_indicator_colour_mode") to "NEON"
            )
        )
        val state = LibraryPrefs(store).state.first()
        assertEquals(NavIndicatorStyle.GOOEY, state.navIndicatorStyle)
        assertEquals(
            NavIndicatorColourMode.THEME, state.navIndicatorColourMode
        )
    }

    @Test
    fun `the style set is the sixteen of the nav engine round`() = runTest {
        // SPEC-NAV-ENGINE-2026-11-16 §2: the original six keep
        // their names (stored prefs read identically) and ten
        // join — the count and the new names are pinned so a
        // rename can never silently orphan a stored choice.
        assertEquals(16, NavIndicatorStyle.entries.size)
        listOf(
            "WORM", "LENS", "EXPANDING", "SEGMENTED", "SQUIRCLE",
            "RING", "DOT", "TOPLINE", "MINIMAL", "FLASHY"
        ).forEach { name ->
            assertEquals(name, NavIndicatorStyle.valueOf(name).name)
        }
        // The original six keep their stored order.
        assertEquals(
            listOf("GOOEY", "SLIDE", "BUBBLE", "NOTCH", "GLOW", "PLAIN"),
            NavIndicatorStyle.entries.take(6).map { it.name }
        )
    }

    @Test
    fun `the view reset clears the indicator choices`() = runTest {
        val prefs = LibraryPrefs(FakeDataStore())
        prefs.setNavIndicatorStyle(NavIndicatorStyle.NOTCH)
        prefs.setNavIndicatorColour(0xFF336699L)
        prefs.resetViewToDefaults()
        val state = prefs.state.first()
        assertEquals(NavIndicatorStyle.GOOEY, state.navIndicatorStyle)
        assertEquals(
            NavIndicatorColourMode.THEME, state.navIndicatorColourMode
        )
        assertEquals(
            DEFAULT_NAV_INDICATOR_ARGB, state.navIndicatorColourArgb
        )
    }
}
