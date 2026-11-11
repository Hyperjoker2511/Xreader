package app.xreader.data.sync

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first

/**
 * v1.5.2 (§15.6): the durable store of full-book-deletion tombstones
 * (natural keys), feeding SyncPayload.deletedBookKeys at gather time.
 * Written ONLY by a full book deletion; an explicit re-add of the
 * same key clears its entry. Retained indefinitely (ED-11).
 */
class DeletedBookKeysStore(private val store: DataStore<Preferences>) {

    suspend fun all(): List<DeletedBookKey> {
        val raw = store.data.first()[KEY] ?: return emptyList()
        return raw.split('\n').mapNotNull { line ->
            val parts = line.split('|')
            if (parts.size < 2) return@mapNotNull null
            val at = parts[parts.size - 1].toLongOrNull() ?: return@mapNotNull null
            DeletedBookKey(parts.dropLast(1).joinToString("|"), at)
        }
    }

    suspend fun record(bookKey: String, deletedAt: Long) {
        val next = (all().filterNot { it.bookKey == bookKey } +
            DeletedBookKey(bookKey, deletedAt))
        store.edit { it[KEY] = next.joinToString("\n") { k -> "${k.bookKey}|${k.deletedAt}" } }
    }

    suspend fun clear(bookKey: String) {
        val next = all().filterNot { it.bookKey == bookKey }
        store.edit { it[KEY] = next.joinToString("\n") { k -> "${k.bookKey}|${k.deletedAt}" } }
    }

    private companion object {
        val KEY = stringPreferencesKey("sync.deleted_book_keys")
    }
}
