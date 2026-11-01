package app.xreader.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0 pins (2026-11-01): recent-search removal.
 *
 * Until this round recent searches could never be deleted —
 * the rows were tap-only, no remove-one existed, and
 * clearSearchHistory() had no UI caller (the user long-pressed
 * a recent and nothing happened). These pins cover the prefs
 * half: remove-one (case-insensitive, others untouched),
 * remove-last empties the list, and clear wipes all.
 */
class SearchMemoryTest {

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
    fun `remove one recent leaves the others in order`() = runTest {
        val prefs = SourcePrefs(FakeDataStore())
        prefs.recordSearch("Chikari")
        prefs.recordSearch("Shadow Slave")
        prefs.recordSearch("Novel Buddy")
        assertEquals(
            listOf("Novel Buddy", "Shadow Slave", "Chikari"),
            prefs.recentSearches.first()
        )
        prefs.removeRecentSearch("shadow slave")
        assertEquals(
            listOf("Novel Buddy", "Chikari"),
            prefs.recentSearches.first()
        )
    }

    @Test
    fun `removing the last recent empties the list`() = runTest {
        val prefs = SourcePrefs(FakeDataStore())
        prefs.recordSearch("Chikari")
        prefs.removeRecentSearch("Chikari")
        assertTrue(prefs.recentSearches.first().isEmpty())
    }

    @Test
    fun `clear history wipes every recent`() = runTest {
        val prefs = SourcePrefs(FakeDataStore())
        prefs.recordSearch("Chikari")
        prefs.recordSearch("Shadow Slave")
        prefs.clearSearchHistory()
        assertTrue(prefs.recentSearches.first().isEmpty())
    }

    @Test
    fun `removing an unknown recent changes nothing`() = runTest {
        val prefs = SourcePrefs(FakeDataStore())
        prefs.recordSearch("Chikari")
        prefs.removeRecentSearch("Never Searched")
        assertEquals(listOf("Chikari"), prefs.recentSearches.first())
    }
}
