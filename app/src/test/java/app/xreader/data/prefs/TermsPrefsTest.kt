package app.xreader.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import app.xreader.domain.reader.TermPair
import app.xreader.domain.reader.TermReplacer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reader Terms storage pins (P0): the global list and the
 * per-book lists round-trip through the DataStore encoding,
 * per-book lists are isolated by book id (one book's terms
 * never reach another), and a book's own pair wins over a
 * global pair for the same original.
 */
class TermsPrefsTest {

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
    fun `global pairs round trip with their flags`() = runTest {
        val prefs = TermsPrefs(FakeDataStore())
        val pairs = listOf(
            TermPair("Lin Dong", "Dong-er"),
            TermPair("lin", "Lynn", matchCase = false, wholeWord = false)
        )
        prefs.setGlobalPairs(pairs)
        assertEquals(pairs, prefs.globalPairs.first())
    }

    @Test
    fun `per book pairs round trip`() = runTest {
        val prefs = TermsPrefs(FakeDataStore())
        val pairs = listOf(TermPair("Xiao Yan", "Yan"))
        prefs.setBookPairs(7L, pairs)
        assertEquals(pairs, prefs.bookPairs(7L).first())
    }

    @Test
    fun `per book lists are isolated by book id`() = runTest {
        val prefs = TermsPrefs(FakeDataStore())
        prefs.setGlobalPairs(listOf(TermPair("Global", "G")))
        prefs.setBookPairs(1L, listOf(TermPair("Alpha", "A")))
        // Book 2 has no list of its own: only the global pair
        // is in force — book 1's pair must not leak into it.
        assertEquals(
            listOf(TermPair("Global", "G")),
            prefs.pairsForBook(2L).first()
        )
        assertTrue(prefs.bookPairs(2L).first().isEmpty())
        // And in the reader itself: book 2's text is untouched
        // by book 1's term.
        val forBook2 = prefs.pairsForBook(2L).first()
        assertEquals(
            "Alpha stayed.",
            TermReplacer.replace("Alpha stayed.", forBook2).text
        )
    }

    @Test
    fun `a book pair wins over a global pair for the same original`() = runTest {
        val prefs = TermsPrefs(FakeDataStore())
        prefs.setGlobalPairs(listOf(TermPair("Lin", "Global Lin")))
        prefs.setBookPairs(3L, listOf(TermPair("Lin", "Book Lin")))
        val merged = prefs.pairsForBook(3L).first()
        assertEquals(
            "Book Lin",
            merged.first { it.original == "Lin" }.replacement
        )
        assertEquals(
            "Book Lin left.",
            TermReplacer.replace("Lin left.", merged).text
        )
    }

    @Test
    fun `sanitising drops blanks no-ops and keeps the last duplicate`() = runTest {
        val prefs = TermsPrefs(FakeDataStore())
        prefs.setGlobalPairs(
            listOf(
                TermPair("  ", "x"),
                TermPair("Same", "Same"),
                TermPair("Lin", "First"),
                TermPair("Lin", "Last")
            )
        )
        assertEquals(
            listOf(TermPair("Lin", "Last")),
            prefs.globalPairs.first()
        )
    }

    @Test
    fun `clearing a book list removes it and leaves the global list`() = runTest {
        val prefs = TermsPrefs(FakeDataStore())
        prefs.setGlobalPairs(listOf(TermPair("Global", "G")))
        prefs.setBookPairs(5L, listOf(TermPair("Alpha", "A")))
        prefs.setBookPairs(5L, emptyList())
        assertTrue(prefs.bookPairs(5L).first().isEmpty())
        assertEquals(
            listOf(TermPair("Global", "G")),
            prefs.pairsForBook(5L).first()
        )
    }

    @Test
    fun `a corrupt stored value reads as an empty list`() = runTest {
        val store = FakeDataStore()
        val prefs = TermsPrefs(store)
        store.updateData { current ->
            val mutable = current.toMutablePreferences()
            mutable[stringPreferencesKey("reader_terms_global")] = "not json"
            mutable.toPreferences()
        }
        assertTrue(prefs.globalPairs.first().isEmpty())
    }
}
