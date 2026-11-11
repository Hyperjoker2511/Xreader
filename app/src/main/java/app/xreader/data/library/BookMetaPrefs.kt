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

    /** v1.5.2 (§13.1): resolvers between Room row ids and the sync
     * layer's natural bookKeys, registered by AppContainer. When
     * registered, the favourite set is stored BY NATURAL KEY
     * (prefix [FAVK_PREFIX]) — immune to rowid reuse and row
     * recreation. Unregistered (bare unit tests), the legacy row-id
     * keys remain the fallback so isolated tests keep their shape. */
    @Volatile var naturalKeyForId: (suspend (Long) -> String?)? = null
    @Volatile var idForNaturalKey: (suspend (String) -> Long?)? = null

    /** The favourite truth (§13.1): natural keys. */
    val favouritesByKey: Flow<Set<String>> = store.data.map { prefs ->
        prefs.asMap().entries.mapNotNull { (key, value) ->
            if (!key.name.startsWith(FAVK_PREFIX)) return@mapNotNull null
            if (value != true) return@mapNotNull null
            key.name.removePrefix(FAVK_PREFIX)
        }.toSet()
    }

    /** Row-id view for the existing shelf consumers: natural keys
     * resolved against current books; unresolvable keys are simply
     * absent (they name nothing — §13.4). */
    val favourites: Flow<Set<Long>> = store.data.map { prefs ->
        val byKey = prefs.asMap().entries.mapNotNull { (key, value) ->
            if (!key.name.startsWith(FAVK_PREFIX)) return@mapNotNull null
            if (value != true) return@mapNotNull null
            key.name.removePrefix(FAVK_PREFIX)
        }.toSet()
        val resolver = idForNaturalKey
        val resolved = if (resolver != null && byKey.isNotEmpty()) {
            byKey.mapNotNull { resolver(it) }.toSet()
        } else emptySet()
        val legacy = prefs.asMap().entries.mapNotNull { (key, value) ->
            if (!key.name.startsWith(FAV_PREFIX)) return@mapNotNull null
            if (key.name.startsWith(FAVK_PREFIX)) return@mapNotNull null
            if (value != true) return@mapNotNull null
            key.name.removePrefix(FAV_PREFIX).toLongOrNull()
        }.toSet()
        resolved + legacy
    }

    suspend fun setFavourite(bookId: Long, favourite: Boolean) {
        val natural = naturalKeyForId?.invoke(bookId)
        if (natural != null) {
            setFavouriteByKey(natural, favourite)
            // A stale legacy key for the same row must not resurrect.
            store.edit { prefs -> prefs.remove(favKey(bookId)) }
        } else {
            store.edit { prefs ->
                if (favourite) prefs[favKey(bookId)] = true
                else prefs.remove(favKey(bookId))
            }
        }
    }

    suspend fun setFavouriteByKey(bookKey: String, favourite: Boolean) {
        store.edit { prefs ->
            val k = androidx.datastore.preferences.core
                .booleanPreferencesKey("$FAVK_PREFIX$bookKey")
            if (favourite) prefs[k] = true else prefs.remove(k)
        }
    }

    suspend fun clearFavouriteByKey(bookKey: String) = setFavouriteByKey(bookKey, false)

    /**
     * v1.5.2 (FIX-7, §13.5): the production merge transfer —
     * when two rows of one book merge, the SURVIVOR's key is
     * favourite if EITHER row's key was (union, never loss),
     * and the duplicate's key is cleared. Called by
     * BookDetailViewModel.mergeDuplicate.
     */
    suspend fun transferFavouriteOnMerge(
        survivorKey: String,
        duplicateKey: String
    ) {
        if (survivorKey == duplicateKey) return
        val current = favouritesByKey.first()
        val merged = FavouriteKeys.mergedKeys(
            survivorKey, duplicateKey, current
        )
        if (survivorKey in merged) {
            setFavouriteByKey(survivorKey, true)
        }
        if (duplicateKey in current) {
            clearFavouriteByKey(duplicateKey)
        }
    }

    /** §13.4/§13.6: one-time re-key of legacy row-id favourites,
     * run by the §8 pass Phase 0 BEFORE any consumer migrates.
     * Returns (converted, dropped) counts. */
    suspend fun migrateLegacyFavourites(
        rowIdToNaturalKey: Map<Long, String>
    ): FavouriteKeys.Migration {
        val legacy = store.data.first().asMap().entries.mapNotNull { (key, value) ->
            if (!key.name.startsWith(FAV_PREFIX)) return@mapNotNull null
            if (key.name.startsWith(FAVK_PREFIX)) return@mapNotNull null
            if (value != true) return@mapNotNull null
            key.name.removePrefix(FAV_PREFIX).toLongOrNull()
        }.toSet()
        val migration = FavouriteKeys.migrate(legacy, rowIdToNaturalKey)
        store.edit { prefs ->
            legacy.forEach { prefs.remove(favKey(it)) }
            migration.converted.forEach { key ->
                prefs[androidx.datastore.preferences.core
                    .booleanPreferencesKey("$FAVK_PREFIX$key")] = true
            }
        }
        return migration
    }

    private fun key(bookId: Long) = stringPreferencesKey("$PREFIX$bookId")
    private fun favKey(bookId: Long) =
        androidx.datastore.preferences.core.booleanPreferencesKey("$FAV_PREFIX$bookId")

    companion object {
        const val PREFIX = "bookmeta."
        const val FAV_PREFIX = "bookfav."
        const val FAVK_PREFIX = "bookfavk."
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
