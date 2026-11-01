package app.xreader.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.xreader.domain.reader.TermPair
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer

/**
 * Reader Terms storage (P0): the user's term pairs — one GLOBAL
 * list plus one list PER BOOK — in the settings DataStore,
 * following the house prefs pattern (see [SourcePrefs]): each
 * list is one JSON value in one key, decoded leniently (a
 * corrupt value reads as an empty list, never a crash).
 *
 * Terms change only what the reader DISPLAYS. Nothing here ever
 * touches a book's stored text, and per-book lists are keyed by
 * book id, so one book's terms can never leak into another.
 */
class TermsPrefs(private val store: DataStore<Preferences>) {

    /** The global pairs — they apply to every book. */
    val globalPairs: Flow<List<TermPair>> = store.data.map { prefs ->
        decode(prefs[KEY_GLOBAL])
    }

    /** One book's own pairs. */
    fun bookPairs(bookId: Long): Flow<List<TermPair>> = store.data.map { prefs ->
        decode(prefs[bookKey(bookId)])
    }

    /**
     * The pairs in force for [bookId]: the book's own pairs
     * FIRST, then the global ones. The book wins a tie — when
     * both lists carry the same original (same case rule), the
     * book's replacement is the one the engine sees first and
     * the global duplicate is dropped here.
     */
    fun pairsForBook(bookId: Long): Flow<List<TermPair>> =
        combine(globalPairs, bookPairs(bookId)) { global, book ->
            val seen = HashSet<Pair<String, Boolean>>()
            val merged = ArrayList<TermPair>(book.size + global.size)
            for (pair in book + global) {
                if (seen.add(pair.original to pair.matchCase)) {
                    merged += pair
                }
            }
            merged
        }

    suspend fun setGlobalPairs(pairs: List<TermPair>) {
        store.edit { it[KEY_GLOBAL] = encode(sanitise(pairs)) }
    }

    suspend fun setBookPairs(bookId: Long, pairs: List<TermPair>) {
        store.edit { prefs ->
            val clean = sanitise(pairs)
            if (clean.isEmpty()) {
                prefs.remove(bookKey(bookId))
            } else {
                prefs[bookKey(bookId)] = encode(clean)
            }
        }
    }

    /** Trim originals, drop blanks and exact duplicates (same
     * original + case rule — the LAST one wins, as the most
     * recent edit). A pair whose original equals its replacement
     * is a no-op and is dropped. An empty replacement is kept:
     * it deletes the term from view, which is a real use. */
    private fun sanitise(pairs: List<TermPair>): List<TermPair> {
        val byKey = LinkedHashMap<Pair<String, Boolean>, TermPair>()
        for (pair in pairs) {
            val original = pair.original.trim()
            if (original.isEmpty()) continue
            if (original == pair.replacement) continue
            byKey[original to pair.matchCase] =
                pair.copy(original = original)
        }
        return byKey.values.toList()
    }

    private fun decode(raw: String?): List<TermPair> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(TermPair.serializer()), raw)
        }.getOrDefault(emptyList())
    }

    private fun encode(pairs: List<TermPair>): String =
        json.encodeToString(ListSerializer(TermPair.serializer()), pairs)

    private fun bookKey(bookId: Long) =
        stringPreferencesKey("reader_terms_book_$bookId")

    private companion object {
        val KEY_GLOBAL = stringPreferencesKey("reader_terms_global")
        val json = kotlinx.serialization.json.Json {
            ignoreUnknownKeys = true
        }
    }
}
