package app.xreader.data.extension

import app.xreader.data.prefs.UpdatePrefs
import app.xreader.data.update.UpdateGate
import app.xreader.data.update.UpdateHttp
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Patch A (code 131): the SOURCE-PACK loader — the quiet
 * channel that lets a bundled source's DEFINITION be
 * corrected between app releases, as data.
 *
 * The maintainer publishes pack documents in the Xreader
 * GitHub repo's `source-pack/` folder: an `index.json`
 * (a JSON array of pack ids) and one `<id>.json` per pack,
 * each a single [DeclarativeConfig] in exactly the shape
 * the app's stored configs use. FreeWebNovel is the first
 * (and, at this build, only) live pack.
 *
 * The pass, in full:
 *  1. At most once per local day — its own stamp in
 *     [UpdatePrefs], separate from the updater's — fetch
 *     the index. A pass that cannot reach the index does
 *     NOT stamp the day, so a later app open may retry
 *     (the updater's own failed-check rule).
 *  2. For every listed id that names a bundled spec
 *     ([BundledDefaults.SPECS]), fetch its pack document.
 *     Ids that name no bundled spec are ignored — a pack
 *     can only ever speak about a bundled row.
 *  3. A document takes effect only after it validates
 *     WHOLE ([SourcePackCodec.parseAndValidate]). Only a
 *     validated document is ever written to app-private
 *     storage ([ExtensionStore.sourcePackFile], through the
 *     store's temp-file + rename write), so the stored pack
 *     is always the last GOOD one: a failed, corrupt or
 *     invalid download changes nothing.
 *  4. The validated pack is handed to the apply seam
 *     ([applyPack] — RepoManager's overlay, which rewrites
 *     the bundled row's stored definition through the same
 *     write path the bundled install pass uses), and only
 *     then replaces the stored pack file. The previously
 *     stored pack is handed over with it, so the seam can
 *     tell a pack-to-pack move from a definition the user
 *     changed themselves.
 *
 * Every failure is silent. The pass is fire-and-forget on
 * the injected (app) scope: it never blocks app startup,
 * the update check, or any surface, and it has no UI, no
 * settings door and no user-facing text anywhere.
 */
class SourcePackLoader(
    private val http: UpdateHttp,
    private val prefs: UpdatePrefs,
    private val store: ExtensionStore,
    private val scope: CoroutineScope,
    /** The apply seam: (newly validated pack, previously
     * stored pack or null) — wired to
     * RepoManager.applySourcePackOverlay in production. */
    private val applyPack: suspend (
        pack: DeclarativeConfig,
        previousPack: DeclarativeConfig?
    ) -> Unit,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val zone: ZoneId = ZoneId.systemDefault()
) {

    /** Fire-and-forget entry point, called from the SAME
     * site that triggers the updater's daily auto-check
     * (AppUpdateViewModel.onAppOpen). Returns immediately;
     * the pass runs on the injected background scope and
     * swallows every failure. */
    fun triggerDailyPass() {
        scope.launch {
            try {
                runDailyPassIfDue()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Silent by design: a pack pass that fails
                // leaves every stored pack and definition
                // exactly as it was.
            }
        }
    }

    /** One daily pass when today has not had one yet. */
    suspend fun runDailyPassIfDue() {
        val today = UpdateGate.dayOf(nowMs(), zone)
        if (prefs.sourcePackLastCheckDay() == today) return
        val indexText = http.getText(INDEX_URL) ?: return
        val ids = parseIndex(indexText) ?: return
        // The index answered: the pass has run for today,
        // whatever the individual pack fetches do below.
        prefs.setSourcePackLastCheckDay(today)
        val bundledIds = BundledDefaults.SPECS
            .map { it.configId }
            .toSet()
        for (id in ids) {
            if (id !in bundledIds) continue
            try {
                fetchOnePack(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // One pack's failure never blocks the rest,
                // and never touches the stored last-good.
                continue
            }
        }
    }

    /** Fetch, validate, store and apply one pack. Any
     * failure leaves the previously stored pack — and the
     * live definition — untouched. */
    private suspend fun fetchOnePack(configId: String) {
        val raw = http.getText(packUrl(configId)) ?: return
        val pack = SourcePackCodec.parseAndValidate(raw, configId)
            ?: return
        val file = store.sourcePackFile(configId)
        val previousRaw = store.readText(file)
        val previous = previousRaw?.let {
            SourcePackCodec.parseAndValidate(it, configId)
        }
        // Apply BEFORE the stored file moves: if the apply
        // fails, the stored pack and the live definition are
        // still the same (previous) generation, and a later
        // pass retries from a consistent state.
        applyPack(pack, previous)
        if (previousRaw != raw) {
            // ExtensionStore.writeText writes a temp file and
            // renames it over the target, so a torn write can
            // never produce a half pack; and this line is only
            // reached with a fully validated document, so the
            // previously stored good pack survives every
            // failed or corrupt download above.
            store.writeText(file, raw)
        }
    }

    /** The index document: a JSON array of pack id strings.
     * Anything else is not an index — the pass treats it as
     * a failed fetch (no day stamp, nothing changes). */
    private fun parseIndex(raw: String): List<String>? {
        val root = runCatching { indexJson.parseToJsonElement(raw) }
            .getOrNull() as? JsonArray ?: return null
        return root.mapNotNull { element ->
            (element as? JsonPrimitive)?.contentOrNull
                ?.takeIf { it.isNotBlank() }
        }
    }

    companion object {
        const val INDEX_URL =
            "https://raw.githubusercontent.com/Hyperjoker2511/" +
                "Xreader/main/source-pack/index.json"

        fun packUrl(configId: String): String =
            "https://raw.githubusercontent.com/Hyperjoker2511/" +
                "Xreader/main/source-pack/$configId.json"

        private val indexJson = Json { ignoreUnknownKeys = true }
    }
}

/**
 * Patch A (code 131): parse + validation for one source-pack
 * document — the ONLY road a downloaded (or stored) pack
 * takes into the app. Returns the parsed config, or null
 * when the document must change NOTHING.
 *
 * The parse uses the same Json configuration the app uses
 * for its stored configs (the [DeclarativeFingerprints]
 * codec's shape: unknown keys ignored, defaults encoded) —
 * a pack is written in exactly the stored-config dialect.
 * [DeclarativeConfig]'s own required fields (details,
 * chapter) are enforced by the decoder itself.
 *
 * The sanity beyond parsing mirrors what the bundled
 * install path relies on a definition having:
 *  - the config's id IS the pack id it was fetched/stored
 *    under (which the loader has already matched against a
 *    bundled spec's configId);
 *  - a non-blank, http(s)-shaped baseUrl with a real host;
 *  - at least one catalogue door (search, popular or
 *    latest) — a definition that can list nothing is not
 *    a definition.
 */
object SourcePackCodec {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun parseAndValidate(
        raw: String,
        expectedId: String
    ): DeclarativeConfig? {
        val config = runCatching {
            json.decodeFromString(DeclarativeConfig.serializer(), raw)
        }.getOrNull() ?: return null
        if (config.id != expectedId) return null
        if (config.baseUrl.isBlank()) return null
        val lower = config.baseUrl.trim().lowercase()
        if (!lower.startsWith("https://") &&
            !lower.startsWith("http://")
        ) {
            return null
        }
        if (config.baseUrl.normaliseHost().isBlank()) return null
        if (config.search == null && config.popular == null &&
            config.latest == null
        ) {
            return null
        }
        return config
    }
}
