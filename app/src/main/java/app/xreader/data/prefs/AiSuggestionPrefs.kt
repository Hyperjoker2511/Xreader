package app.xreader.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first

/**
 * "More like this" AI suggestions (v1.0.2, R26): the local memory
 * the plan requires — suggestions cached by book fingerprint (the
 * user's Gemini key is never spent twice on the same book) and
 * dismissals remembered. Local-only; nothing here syncs.
 */
class AiSuggestionPrefs(private val store: DataStore<Preferences>) {

    suspend fun dismissed(): Set<String> =
        store.data.first()[KEY_DISMISSED]
            ?.split("\n")?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?.toSet() ?: emptySet()

    suspend fun dismiss(normalisedTitle: String) {
        store.edit { prefs ->
            val current = prefs[KEY_DISMISSED]
                ?.split("\n")?.filter { it.isNotBlank() } ?: emptyList()
            prefs[KEY_DISMISSED] =
                (current + normalisedTitle).distinct().takeLast(300)
                    .joinToString("\n")
        }
    }

    suspend fun cached(fingerprint: String): List<String>? =
        store.data.first()[cacheKey(fingerprint)]
            ?.split("\n")?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?.takeIf { it.isNotEmpty() }

    suspend fun cache(fingerprint: String, titles: List<String>) {
        store.edit { prefs ->
            prefs[cacheKey(fingerprint)] =
                titles.take(8).joinToString("\n") { it.replace('\n', ' ') }
        }
    }

    private fun cacheKey(fingerprint: String) =
        stringPreferencesKey("aisug.cache." + fingerprint.hashCode().toString(16))

    companion object {
        private val KEY_DISMISSED = stringPreferencesKey("aisug.dismissed")

        /** Cache/dismissal identity: normalised title + author. */
        fun fingerprint(title: String, author: String?): String =
            (title + "|" + (author ?: "")).lowercase()
                .replace(Regex("[^a-z0-9| ]"), " ")
                .replace(Regex("\\s+"), " ").trim()

        fun normaliseTitle(title: String): String =
            fingerprint(title, null).substringBefore('|').trim()
    }
}
