package app.xreader.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.xreader.data.library.RefusedPair
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

/**
 * Progress-linking storage (Cycle 1, F3): ONLY the refusals
 * live here — the pairs of library books the user separated
 * with "Not the same book". The links themselves are never
 * stored: they are derived on the spot from the library by
 * the same-story matcher, so a stored link can never go
 * stale, point at a deleted book, or survive against the
 * user's word. A refusal is the one fact the matcher cannot
 * derive, so it is the one thing persisted.
 *
 * Settings DataStore, house pattern (see [TermsPrefs]): one
 * JSON value in one key, decoded leniently — a corrupt value
 * reads as "no refusals", never a crash.
 */
class ProgressLinkPrefs(private val store: DataStore<Preferences>) {

    /** Every refused pair, as an observable set. */
    val refusedPairs: Flow<Set<RefusedPair>> = store.data.map { prefs ->
        decode(prefs[KEY_REFUSED])
    }

    /** Record a refusal (either argument order — the pair is
     * stored normalised, so the refusal holds both ways). */
    suspend fun addRefusal(firstBookId: Long, secondBookId: Long) {
        if (firstBookId == secondBookId) return
        store.edit { prefs ->
            val current = decode(prefs[KEY_REFUSED])
            prefs[KEY_REFUSED] = encode(
                current + RefusedPair.of(firstBookId, secondBookId)
            )
        }
    }

    private fun decode(raw: String?): Set<RefusedPair> {
        if (raw.isNullOrBlank()) return emptySet()
        val keys = runCatching {
            json.decodeFromString(ListSerializer(String.serializer()), raw)
        }.getOrDefault(emptyList())
        return keys.mapNotNull { RefusedPair.fromKey(it) }.toSet()
    }

    private fun encode(pairs: Set<RefusedPair>): String =
        json.encodeToString(
            ListSerializer(String.serializer()),
            pairs.map { it.key() }.sorted()
        )

    private companion object {
        val KEY_REFUSED = stringPreferencesKey("progress_link_refused_pairs")
        val json = kotlinx.serialization.json.Json {
            ignoreUnknownKeys = true
        }
    }
}
