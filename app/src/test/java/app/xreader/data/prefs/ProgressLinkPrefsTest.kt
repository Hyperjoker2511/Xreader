package app.xreader.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import app.xreader.data.library.RefusedPair
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Progress-link refusal storage pins (Cycle 1, F3): a refusal
 * is stored once per pair, normalised so it holds in both
 * directions, and a corrupt value reads as "no refusals"
 * rather than crashing the book page.
 */
class ProgressLinkPrefsTest {

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
    }

    @Test
    fun `a refusal round trips, normalised to one entry per pair`() = runTest {
        val prefs = ProgressLinkPrefs(FakeDataStore())
        prefs.addRefusal(17L, 3L)
        assertEquals(setOf(RefusedPair(3L, 17L)), prefs.refusedPairs.first())
        // The same pair in the other order is the same refusal.
        prefs.addRefusal(3L, 17L)
        assertEquals(setOf(RefusedPair(3L, 17L)), prefs.refusedPairs.first())
    }

    @Test
    fun `several pairs persist side by side`() = runTest {
        val prefs = ProgressLinkPrefs(FakeDataStore())
        prefs.addRefusal(1L, 2L)
        prefs.addRefusal(9L, 4L)
        assertEquals(
            setOf(RefusedPair(1L, 2L), RefusedPair(4L, 9L)),
            prefs.refusedPairs.first()
        )
    }

    @Test
    fun `a book is never refused against itself`() = runTest {
        val prefs = ProgressLinkPrefs(FakeDataStore())
        prefs.addRefusal(5L, 5L)
        assertTrue(prefs.refusedPairs.first().isEmpty())
    }

    @Test
    fun `a corrupt stored value reads as no refusals`() = runTest {
        val store = FakeDataStore()
        val prefs = ProgressLinkPrefs(store)
        store.updateData { current ->
            val mutable = current.toMutablePreferences()
            mutable[stringPreferencesKey("progress_link_refused_pairs")] =
                "not json"
            mutable.toPreferences()
        }
        assertTrue(prefs.refusedPairs.first().isEmpty())
    }
}
