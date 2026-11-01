package app.xreader.data.extension.player

import app.xreader.data.extension.ExtensionStore
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * K3: persistence for the player's per-extension records —
 * a seam so the manager's state machine runs against fakes
 * on the JVM. The production store is one JSON file in the
 * extension store (the provider-pack pattern: file-backed
 * state, no Room migration for player bookkeeping).
 */
interface KeiyoushiRecordStore {
    fun load(): List<KeiyoushiPlayerRecord>
    fun save(records: List<KeiyoushiPlayerRecord>)
}

class FileKeiyoushiRecordStore(
    private val store: ExtensionStore
) : KeiyoushiRecordStore {

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer =
        ListSerializer(KeiyoushiPlayerRecord.serializer())

    override fun load(): List<KeiyoushiPlayerRecord> {
        val raw = store.readText(store.keiyoushiPlayerStateFile())
            ?: return emptyList()
        // A corrupt state file reads as "nothing installed"
        // rather than crashing the shelf — the APK files and
        // rows still exist, and a re-install re-earns state.
        return runCatching {
            json.decodeFromString(serializer, raw)
        }.getOrDefault(emptyList())
    }

    override fun save(records: List<KeiyoushiPlayerRecord>) {
        store.writeText(
            store.keiyoushiPlayerStateFile(),
            json.encodeToString(serializer, records)
        )
    }
}
