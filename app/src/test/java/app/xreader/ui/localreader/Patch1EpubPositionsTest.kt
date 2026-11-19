package app.xreader.ui.localreader

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.7 Patch 1 (item 6a): the EPUB positions index is
 * persisted per book — a re-open takes the stored seam
 * instead of walking the PositionsService again. Pins the
 * encoding (strict decode, never a half index), the store
 * round-trip, and the section-count validity gate.
 */
class Patch1EpubPositionsTest {

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

    private val index = EpubPositionsIndex(
        sectionCount = 3,
        starts = listOf(0, 48, 96),
        counts = listOf(48, 48, 52)
    )

    @Test
    fun `encode decode round-trips`() {
        assertEquals(index, decodeEpubPositionsIndex(encodeEpubPositionsIndex(index)))
    }

    @Test
    fun `decode rejects malformed input`() {
        assertNull(decodeEpubPositionsIndex(null))
        assertNull(decodeEpubPositionsIndex(""))
        assertNull(decodeEpubPositionsIndex("not-an-index"))
        assertNull(decodeEpubPositionsIndex("3|0,48,96"))
        assertNull(decodeEpubPositionsIndex("3|0,48,96|48,48"))
        assertNull(decodeEpubPositionsIndex("3|0,48|48,48,52"))
        assertNull(decodeEpubPositionsIndex("x|0,48,96|48,48,52"))
        assertNull(decodeEpubPositionsIndex("3|0,48,zz|48,48,52"))
        assertNull(decodeEpubPositionsIndex("3|0,48,96|48,48,-1"))
        assertNull(decodeEpubPositionsIndex("3|4,48,96|48,48,52"))
        assertNull(decodeEpubPositionsIndex("3|0,96,48|48,48,52"))
        assertNull(decodeEpubPositionsIndex("0||"))
    }

    @Test
    fun `store round-trips the index per book`() = runTest {
        val prefs = LocalReaderPrefs(FakeDataStore())
        assertNull(prefs.epubPositions(7L))
        prefs.saveEpubPositions(7L, index)
        assertEquals(index, prefs.epubPositions(7L))
        // Another book's index never leaks across.
        assertNull(prefs.epubPositions(8L))
    }

    @Test
    fun `validity gate is the section count`() {
        assertFalse(storedPositionsUsable(null, 3))
        assertTrue(storedPositionsUsable(index, 3))
        assertFalse(storedPositionsUsable(index, 4))
        assertFalse(storedPositionsUsable(index, 2))
    }
}
