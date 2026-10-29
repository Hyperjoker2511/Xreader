package app.xreader.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.xreader.data.extension.BestSourcePriority
import app.xreader.data.extension.SourceHealthRecord
import app.xreader.data.extension.SourceRepairRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Source & search preferences (v1.0.2): the one Best-source ranking
 * preference (R11), repository pins and recently-used order (R14),
 * and global-search memory (R15 + the sweep's saved searches).
 *
 * Search history is local-only and NEVER synced (R02/G02) — it can
 * reveal what the user reads. All list encodings are newline rows
 * with separators stripped, the house pattern (see custom schemes).
 */
class SourcePrefs(private val store: DataStore<Preferences>) {

    val bestSourcePriority: Flow<BestSourcePriority> = store.data.map { prefs ->
        runCatching {
            BestSourcePriority.valueOf(prefs[KEY_BEST_PRIORITY] ?: "")
        }.getOrDefault(BestSourcePriority.DOWNLOADED_FIRST)
    }

    suspend fun setBestSourcePriority(priority: BestSourcePriority) =
        store.edit { it[KEY_BEST_PRIORITY] = priority.name }

    // ── Repositories (R14) ───────────────────────────────────────

    val pinnedRepoIds: Flow<Set<Long>> = store.data.map { prefs ->
        decodeIds(prefs[KEY_PINNED_REPOS])
    }

    val recentRepoIds: Flow<List<Long>> = store.data.map { prefs ->
        decodeIdList(prefs[KEY_RECENT_REPOS])
    }

    suspend fun setRepoPinned(repoId: Long, pinned: Boolean) {
        store.edit { prefs ->
            val current = decodeIds(prefs[KEY_PINNED_REPOS]).toMutableSet()
            if (pinned) current += repoId else current -= repoId
            prefs[KEY_PINNED_REPOS] = current.joinToString(",")
        }
    }

    suspend fun touchRecentRepo(repoId: Long) {
        store.edit { prefs ->
            val current = decodeIdList(prefs[KEY_RECENT_REPOS])
                .filterNot { it == repoId }
            prefs[KEY_RECENT_REPOS] = (listOf(repoId) + current)
                .take(MAX_RECENT_REPOS).joinToString(",")
        }
    }

    suspend fun clearRecentRepos() =
        store.edit { it.remove(KEY_RECENT_REPOS) }

    // ── Search memory (R15) ──────────────────────────────────────

    val recentSearches: Flow<List<String>> = store.data.map { prefs ->
        decodeLines(prefs[KEY_RECENT_SEARCHES])
    }

    val savedSearches: Flow<List<String>> = store.data.map { prefs ->
        decodeLines(prefs[KEY_SAVED_SEARCHES])
    }

    suspend fun recordSearch(query: String) {
        val clean = query.trim()
        if (clean.length < 2) return
        store.edit { prefs ->
            val current = decodeLines(prefs[KEY_RECENT_SEARCHES])
                .filterNot { it.equals(clean, ignoreCase = true) }
            prefs[KEY_RECENT_SEARCHES] = (listOf(clean) + current)
                .take(MAX_RECENT_SEARCHES)
                .joinToString("\n") { it.replace('\n', ' ') }
        }
    }

    suspend fun clearSearchHistory() =
        store.edit { it.remove(KEY_RECENT_SEARCHES) }

    suspend fun saveSearch(query: String) {
        val clean = query.trim()
        if (clean.length < 2) return
        store.edit { prefs ->
            val current = decodeLines(prefs[KEY_SAVED_SEARCHES])
                .filterNot { it.equals(clean, ignoreCase = true) }
            prefs[KEY_SAVED_SEARCHES] = (listOf(clean) + current)
                .joinToString("\n") { it.replace('\n', ' ') }
        }
    }

    suspend fun removeSavedSearch(query: String) {
        store.edit { prefs ->
            val current = decodeLines(prefs[KEY_SAVED_SEARCHES])
                .filterNot { it.equals(query.trim(), ignoreCase = true) }
            prefs[KEY_SAVED_SEARCHES] = current.joinToString("\n")
        }
    }

    // ── Source health (R10) ──────────────────────────────────────
    //
    // The last test-drive verdict per source, keyed
    // "extensionId:sourceId". One JSON object in one key; the DTO
    // is deliberately flat (state + proof + failed core parts),
    // enough for every row and header that shows health.

    val sourceHealth: Flow<Map<String, SourceHealthRecord>> = store.data.map { prefs ->
        decodeHealth(prefs[KEY_SOURCE_HEALTH])
    }

    suspend fun setSourceHealth(key: String, record: SourceHealthRecord) {
        store.edit { prefs ->
            val current = decodeHealth(prefs[KEY_SOURCE_HEALTH]).toMutableMap()
            current[key] = record
            prefs[KEY_SOURCE_HEALTH] = encodeHealth(current)
        }
    }

    suspend fun clearSourceHealth(key: String) {
        store.edit { prefs ->
            val current = decodeHealth(prefs[KEY_SOURCE_HEALTH]).toMutableMap()
            current.remove(key)
            prefs[KEY_SOURCE_HEALTH] = encodeHealth(current)
        }
    }

    // ── Last repair (v1.0.6) ─────────────────────────────────
    //
    // The last Repair's outcome per source, keyed exactly like
    // the health map ("extensionId:sourceId"), so a Repair's
    // result — saved or not, the final note, and the probe's
    // stage log — survives on the Details page and in copied
    // problem details instead of vanishing with its message.

    val sourceRepairLog: Flow<Map<String, SourceRepairRecord>> =
        store.data.map { prefs ->
            decodeRepairLog(prefs[KEY_REPAIR_LOG])
        }

    suspend fun setSourceRepairLog(key: String, record: SourceRepairRecord) {
        store.edit { prefs ->
            val current = decodeRepairLog(prefs[KEY_REPAIR_LOG]).toMutableMap()
            current[key] = record
            prefs[KEY_REPAIR_LOG] = encodeRepairLog(current)
        }
    }

    private fun decodeRepairLog(
        raw: String?
    ): Map<String, SourceRepairRecord> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            val parsed = healthJson.decodeFromString<RepairLogDto>(raw)
            parsed.entries.mapValues { (_, dto) ->
                SourceRepairRecord(
                    repairedAtMillis = dto.repairedAtMillis,
                    saved = dto.saved,
                    note = dto.note,
                    stageLines = dto.stageLines
                )
            }
        }.getOrDefault(emptyMap())
    }

    private fun encodeRepairLog(map: Map<String, SourceRepairRecord>): String =
        healthJson.encodeToString(
            RepairLogDto.serializer(),
            RepairLogDto(
                entries = map.mapValues { (_, record) ->
                    RepairEntryDto(
                        repairedAtMillis = record.repairedAtMillis,
                        saved = record.saved,
                        note = record.note,
                        stageLines = record.stageLines
                    )
                }
            )
        )

    @kotlinx.serialization.Serializable
    private data class RepairEntryDto(
        val repairedAtMillis: Long = 0,
        val saved: Boolean = false,
        val note: String = "",
        val stageLines: List<String> = emptyList()
    )

    @kotlinx.serialization.Serializable
    private data class RepairLogDto(
        val entries: Map<String, RepairEntryDto> = emptyMap()
    )

    // ── Heal-my-library sweep (v1.0.5, E1) ───────────────────────
    //
    // A versioned one-time flag: the sweep runs once per app
    // version code, so a future engine improvement can re-heal
    // older sources again. Plus the sweep's one calm report line,
    // shown on the Me page until dismissed.

    val healSweepDoneForCode: Flow<Int> =
        store.data.map { it[KEY_HEAL_DONE_FOR] ?: 0 }

    suspend fun setHealSweepDoneForCode(versionCode: Int) {
        store.edit { it[KEY_HEAL_DONE_FOR] = versionCode }
    }

    val healNote: Flow<String?> = store.data.map { it[KEY_HEAL_NOTE] }

    suspend fun setHealNote(note: String?) {
        store.edit { prefs ->
            if (note == null) {
                prefs.remove(KEY_HEAL_NOTE)
            } else {
                prefs[KEY_HEAL_NOTE] = note
            }
        }
    }

    private fun decodeHealth(raw: String?): Map<String, SourceHealthRecord> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            val parsed = healthJson.decodeFromString<HealthDto>(raw)
            parsed.entries.mapNotNull { (key, dto) ->
                val state = runCatching {
                    app.xreader.data.extension.SourceHealthState.valueOf(dto.state)
                }.getOrNull() ?: return@mapNotNull null
                key to SourceHealthRecord(
                    state = state,
                    proofBookTitle = dto.proofBookTitle,
                    proofChapterTitle = dto.proofChapterTitle,
                    proofFirstLine = dto.proofFirstLine,
                    checkedAtMillis = dto.checkedAtMillis,
                    failedCore = dto.failedCore
                )
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    private fun encodeHealth(map: Map<String, SourceHealthRecord>): String =
        healthJson.encodeToString(
            HealthDto.serializer(),
            HealthDto(
                entries = map.mapValues { (_, record) ->
                    HealthEntryDto(
                        state = record.state.name,
                        proofBookTitle = record.proofBookTitle,
                        proofChapterTitle = record.proofChapterTitle,
                        proofFirstLine = record.proofFirstLine,
                        checkedAtMillis = record.checkedAtMillis,
                        failedCore = record.failedCore
                    )
                }
            )
        )

    // ── Browser view (v1.0.6, W2.4 + TR4) ────────────────────────

    /** The one master switch for the browser view (Customise →
     * Sources & repairs). Default ON; OFF means every fetch is
     * static-only and JS-shell sites get the plain refusal —
     * exactly the pre-v1.0.6 behaviour. */
    val browserViewEnabled: Flow<Boolean> = store.data.map { prefs ->
        prefs[KEY_BROWSER_VIEW] ?: true
    }

    suspend fun setBrowserViewEnabled(enabled: Boolean) {
        store.edit { it[KEY_BROWSER_VIEW] = enabled }
    }

    /** TR4 record: extension rows whose site started serving JS
     * shells at runtime while their config is unmarked. Details
     * shows "run Repair" for these; a successful Repair clears
     * the row's flag. Ids only — no content. */
    val browserViewNeededIds: Flow<Set<Long>> = store.data.map { prefs ->
        decodeIds(prefs[KEY_BROWSER_VIEW_NEEDED])
    }

    suspend fun markBrowserViewNeeded(extensionId: Long) {
        store.edit { prefs ->
            val current = decodeIds(prefs[KEY_BROWSER_VIEW_NEEDED])
            if (extensionId !in current) {
                prefs[KEY_BROWSER_VIEW_NEEDED] =
                    (current + extensionId).joinToString(",")
            }
        }
    }

    suspend fun clearBrowserViewNeeded(extensionId: Long) {
        store.edit { prefs ->
            val current = decodeIds(prefs[KEY_BROWSER_VIEW_NEEDED]) - extensionId
            if (current.isEmpty()) {
                prefs.remove(KEY_BROWSER_VIEW_NEEDED)
            } else {
                prefs[KEY_BROWSER_VIEW_NEEDED] = current.joinToString(",")
            }
        }
    }

    @kotlinx.serialization.Serializable
    private data class HealthEntryDto(
        val state: String,
        val proofBookTitle: String = "",
        val proofChapterTitle: String = "",
        val proofFirstLine: String = "",
        val checkedAtMillis: Long = 0,
        val failedCore: List<String> = emptyList()
    )

    @kotlinx.serialization.Serializable
    private data class HealthDto(
        val entries: Map<String, HealthEntryDto> = emptyMap()
    )

    private val healthJson = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
    }

    private fun decodeIds(raw: String?): Set<Long> =
        raw?.split(',')?.mapNotNull { it.trim().toLongOrNull() }?.toSet() ?: emptySet()

    private fun decodeIdList(raw: String?): List<Long> =
        raw?.split(',')?.mapNotNull { it.trim().toLongOrNull() } ?: emptyList()

    private fun decodeLines(raw: String?): List<String> =
        raw?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

    private companion object {
        val KEY_BEST_PRIORITY = stringPreferencesKey("sources.best_priority")
        val KEY_PINNED_REPOS = stringPreferencesKey("sources.pinned_repos")
        val KEY_RECENT_REPOS = stringPreferencesKey("sources.recent_repos")
        val KEY_RECENT_SEARCHES = stringPreferencesKey("search.recent")
        val KEY_SAVED_SEARCHES = stringPreferencesKey("search.saved")
        val KEY_SOURCE_HEALTH = stringPreferencesKey("source.health_map")
        val KEY_REPAIR_LOG = stringPreferencesKey("source.repair_log_map")
        val KEY_HEAL_DONE_FOR =
            androidx.datastore.preferences.core.intPreferencesKey("heal.sweep_done_for_code")
        val KEY_HEAL_NOTE = stringPreferencesKey("heal.note")
        val KEY_BROWSER_VIEW = booleanPreferencesKey("sources.browser_view_enabled")
        val KEY_BROWSER_VIEW_NEEDED = stringPreferencesKey("sources.browser_view_needed")
        const val MAX_RECENT_REPOS = 8
        const val MAX_RECENT_SEARCHES = 12
    }
}
