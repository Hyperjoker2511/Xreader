package app.xreader.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import app.xreader.data.db.Chapter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.5.8 (E3): the per-book scanlator filter — the store
 * round-trips through BookReaderPrefs, clear(bookId) covers
 * book-delete cleanup, and the shared exclusion rule filters
 * the chapter list (and its counts) exactly by scanlator.
 */
class V158ScanlatorFilterTest {

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

    private fun chapter(id: Long, scanlator: String?) = Chapter(
        id = id, bookId = 7, index = id.toInt(),
        title = "Chapter $id", updatedAt = 0, scanlator = scanlator
    )

    @Test
    fun `exclusion set round-trips and clear covers it`() = runTest {
        val prefs = BookReaderPrefs(FakeDataStore())
        assertTrue(prefs.excludedScanlators(7).first().isEmpty())
        prefs.setExcludedScanlators(7, setOf("Gengar"))
        assertEquals(setOf("Gengar"), prefs.excludedScanlators(7).first())
        // Per-book: another book is untouched.
        assertTrue(prefs.excludedScanlators(8).first().isEmpty())
        prefs.clear(7)
        assertTrue(prefs.excludedScanlators(7).first().isEmpty())
    }

    @Test
    fun `excluding one of three scanlators filters list and counts`() {
        val chapters = listOf(
            chapter(1, "Gengar"), chapter(2, "Asura"),
            chapter(3, "Gengar"), chapter(4, "Flame"),
            chapter(5, null)
        )
        val shown = ScanlatorFilter.apply(chapters, { it.scanlator }, setOf("Gengar"))
        assertEquals(listOf(2L, 4L, 5L), shown.map { it.id })
        // Re-include restores the full list.
        val restored = ScanlatorFilter.apply(chapters, { it.scanlator }, emptySet())
        assertEquals(chapters, restored)
    }

    @Test
    fun `a chapter with no credited scanlator is never filtered`() {
        assertTrue(!ScanlatorFilter.isExcluded(null, setOf("Gengar")))
        assertTrue(ScanlatorFilter.isExcluded("Gengar", setOf("Gengar")))
        assertTrue(!ScanlatorFilter.isExcluded("Asura", setOf("Gengar")))
    }
}
