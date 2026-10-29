package app.xreader.data.sync

import app.xreader.data.files.BookStorage
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Deletion tombstones, kept in a small JSON file (no schema change —
 * the DB stays v1). When the user deletes a bookmark, highlight, lore
 * entity, or term, the delete sites record a tombstone here; the next
 * sync carries it in the payload so the deletion reaches the other
 * device instead of the record resurrecting from it.
 */
class TombstoneStore(
    private val storage: BookStorage,
    private val deviceIdProvider: suspend () -> String,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(Tombstone.serializer())

    private fun file() = storage.resolve("sync/tombstones.json")

    suspend fun record(kind: String, naturalKey: String) {
        val current = all()
        val next = current.filterNot { it.kind == kind && it.key == naturalKey } +
            Tombstone(kind, naturalKey, clock(), deviceIdProvider())
        write(next)
    }

    suspend fun all(): List<Tombstone> {
        val file = file()
        if (!file.isFile) return emptyList()
        return runCatching {
            json.decodeFromString(serializer, file.readText())
        }.getOrDefault(emptyList())
    }

    /** After a merge, the merged tombstone set becomes the local set
     * (bounded: newest [MAX_KEPT]; older deletions have long since
     * reached every device). */
    suspend fun replaceAll(tombstones: List<Tombstone>) {
        write(
            tombstones
                .sortedByDescending { it.deletedAt }
                .take(MAX_KEPT)
        )
    }

    private fun write(tombstones: List<Tombstone>) {
        runCatching {
            storage.writeAtomic(file(), json.encodeToString(serializer, tombstones))
        }
    }

    companion object {
        const val MAX_KEPT = 1_000
    }
}

/** One past sync, for the settings screen's visible log (PLAN §7:
 * never a silent overwrite the user can't inspect). */
@kotlinx.serialization.Serializable
data class SyncLogEntry(
    val at: Long,
    val backend: String,
    val summary: String,
    val conflicts: List<String> = emptyList()
)

class SyncLogStore(private val storage: BookStorage) {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(SyncLogEntry.serializer())

    private fun file() = storage.resolve("sync/sync-log.json")

    fun recent(limit: Int = 20): List<SyncLogEntry> {
        val file = file()
        if (!file.isFile) return emptyList()
        return runCatching {
            json.decodeFromString(serializer, file.readText())
        }.getOrDefault(emptyList()).take(limit)
    }

    fun append(entry: SyncLogEntry) {
        val next = (listOf(entry) + recent(MAX_KEPT - 1)).take(MAX_KEPT)
        runCatching {
            storage.writeAtomic(file(), json.encodeToString(serializer, next))
        }
    }

    companion object {
        const val MAX_KEPT = 50
    }
}
