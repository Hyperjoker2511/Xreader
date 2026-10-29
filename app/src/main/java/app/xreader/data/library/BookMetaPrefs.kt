package app.xreader.data.library

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * The DataStore sidecar for per-book user metadata (v1.0.1): reading
 * status, the Track toggle, and chapter-count bookkeeping live here —
 * keyed by bookId AND mirrored by natural key (title|author) so a
 * status survives a book being removed and re-added. NO Room schema
 * change: v1.0.0 libraries upgrade in place untouched.
 */
class BookMetaPrefs(private val store: DataStore<Preferences>) {

    val all: Flow<Map<Long, BookMeta>> = store.data.map { prefs ->
        prefs.asMap().entries.mapNotNull { (key, value) ->
            val id = key.name.removePrefix(PREFIX).toLongOrNull() ?: return@mapNotNull null
            (value as? String)?.let { id to BookMeta.decode(it) }
        }.toMap()
    }

    suspend fun get(bookId: Long): BookMeta =
        store.data.first()[key(bookId)]?.let { BookMeta.decode(it) } ?: BookMeta()

    suspend fun set(bookId: Long, meta: BookMeta) {
        store.edit { it[key(bookId)] = BookMeta.encode(meta) }
    }

    suspend fun update(bookId: Long, transform: (BookMeta) -> BookMeta) {
        val current = get(bookId)
        set(bookId, transform(current))
    }

    // ── Favourites (v1.0.2, R21) ────────────────────────────────
    // Kept as its own key family in the same sidecar file: the
    // BookMeta line format is pinned by its tests, and favourites
    // deserve a set-shaped read for the shelf rail anyway.

    val favourites: Flow<Set<Long>> = store.data.map { prefs ->
        prefs.asMap().entries.mapNotNull { (key, value) ->
            if (!key.name.startsWith(FAV_PREFIX)) return@mapNotNull null
            if (value != true) return@mapNotNull null
            key.name.removePrefix(FAV_PREFIX).toLongOrNull()
        }.toSet()
    }

    suspend fun setFavourite(bookId: Long, favourite: Boolean) {
        store.edit { prefs ->
            if (favourite) prefs[favKey(bookId)] = true
            else prefs.remove(favKey(bookId))
        }
    }

    private fun key(bookId: Long) = stringPreferencesKey("$PREFIX$bookId")
    private fun favKey(bookId: Long) =
        androidx.datastore.preferences.core.booleanPreferencesKey("$FAV_PREFIX$bookId")

    companion object {
        const val PREFIX = "bookmeta."
        const val FAV_PREFIX = "bookfav."
    }
}

/**
 * History visibility sidecar (v1.0.1): which books the user removed
 * from History, and the "cleared before" watermark. Clearing history
 * touches ONLY this file — progress, bookmarks and downloads are
 * never read or written here.
 */
class HistoryPrefs(private val store: DataStore<Preferences>) {

    data class Visibility(val hiddenBookIds: Set<String>, val clearedBeforeMillis: Long)

    val visibility: Flow<Visibility> = store.data.map { prefs ->
        Visibility(
            hiddenBookIds = prefs[KEY_HIDDEN]
                ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
                ?.toSet() ?: emptySet(),
            clearedBeforeMillis = prefs[KEY_CLEARED_BEFORE]?.toLongOrNull() ?: 0L
        )
    }

    suspend fun hide(bookId: Long) {
        store.edit { prefs ->
            val current = prefs[KEY_HIDDEN]
                ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
                ?.toMutableSet() ?: mutableSetOf()
            current.add(bookId.toString())
            prefs[KEY_HIDDEN] = current.joinToString(",")
        }
    }

    suspend fun clearAll(nowMillis: Long) {
        store.edit { it[KEY_CLEARED_BEFORE] = nowMillis.toString() }
    }

    private companion object {
        val KEY_HIDDEN = stringPreferencesKey("history.hidden_book_ids")
        val KEY_CLEARED_BEFORE = stringPreferencesKey("history.cleared_before")
    }
}
