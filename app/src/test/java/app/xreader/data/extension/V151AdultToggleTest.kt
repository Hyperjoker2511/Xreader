package app.xreader.data.extension

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import app.xreader.data.prefs.SourcePrefs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * v1.5.1 (full-app audit F5): the 18+ quick toggle must never
 * destroy the third ContentVisibility state. Proof at both
 * levels: the pure step logic, and the real SourcePrefs toggle
 * (atomic current+remembered write) over a fake DataStore —
 * toggle → toggle returns the EXACT original mode from every
 * starting state, including "Hide Mixed and 18+".
 */
class V151AdultToggleTest {

    private class FakeDataStore : DataStore<Preferences> {
        private val state =
            kotlinx.coroutines.flow.MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = state
        override suspend fun updateData(
            transform: suspend (Preferences) -> Preferences
        ): Preferences {
            val updated = transform(state.value)
            state.value = updated
            return updated
        }
    }

    @Test
    fun `pure logic - toggle toggle restores every starting mode`() {
        for (start in ContentVisibility.entries) {
            var remembered: ContentVisibility? = null
            val (once, rem1) = AdultToggleLogic.next(start, remembered)
            remembered = rem1
            val (twice, rem2) = AdultToggleLogic.next(once, remembered)
            assertEquals(
                "toggle→toggle from $start must restore it exactly",
                start, twice
            )
            if (start != ContentVisibility.SHOW_ALL) {
                assertNull("memory is consumed after the step back", rem2)
            } else {
                // From SHOW_ALL the second step lands back ON, so
                // the memory now holds the intermediate mode — a
                // third toggle would restore THAT exactly.
                assertEquals(ContentVisibility.DEFAULT, rem2)
            }
        }
    }

    @Test
    fun `pure logic - stepping off with a remembered strict mode restores it`() {
        val (mode, remembered) = AdultToggleLogic.next(
            ContentVisibility.SHOW_ALL, ContentVisibility.HIDE_MIXED_AND_ADULT
        )
        assertEquals(ContentVisibility.HIDE_MIXED_AND_ADULT, mode)
        assertNull(remembered)
    }

    @Test
    fun `pure logic - stepping off with no memory falls back to the default`() {
        val (mode, _) = AdultToggleLogic.next(ContentVisibility.SHOW_ALL, null)
        assertEquals(ContentVisibility.DEFAULT, mode)
        assertEquals(ContentVisibility.HIDE_ADULT, mode)
    }

    @Test
    fun `prefs - toggle toggle restores every starting mode`() = runBlocking {
        for (start in ContentVisibility.entries) {
            val prefs = SourcePrefs(FakeDataStore())
            prefs.setContentVisibility(start)
            prefs.toggleContentVisibility()
            val mid = prefs.contentVisibility.first()
            if (start != ContentVisibility.SHOW_ALL) {
                assertEquals(
                    "one toggle from $start shows everything",
                    ContentVisibility.SHOW_ALL, mid
                )
                assertEquals(
                    "the stepped-away mode is remembered",
                    start, prefs.contentVisibilityPreToggle.first()
                )
            }
            prefs.toggleContentVisibility()
            assertEquals(
                "toggle→toggle from $start restores it exactly",
                start, prefs.contentVisibility.first()
            )
        }
    }

    @Test
    fun `prefs - a deliberate three-way choice clears the toggle memory`() =
        runBlocking {
            val prefs = SourcePrefs(FakeDataStore())
            prefs.setContentVisibility(ContentVisibility.HIDE_MIXED_AND_ADULT)
            prefs.toggleContentVisibility() // ON, remembers strict mode
            assertEquals(
                ContentVisibility.HIDE_MIXED_AND_ADULT,
                prefs.contentVisibilityPreToggle.first()
            )
            // A deliberate Customise pick supersedes the memory.
            prefs.setContentVisibility(ContentVisibility.SHOW_ALL)
            assertNull(prefs.contentVisibilityPreToggle.first())
            // Stepping OFF now falls back to the default, never a
            // stale remembered mode.
            prefs.toggleContentVisibility()
            assertEquals(
                ContentVisibility.HIDE_ADULT,
                prefs.contentVisibility.first()
            )
        }
}
