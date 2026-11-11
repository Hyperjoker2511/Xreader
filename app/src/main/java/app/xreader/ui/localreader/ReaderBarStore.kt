package app.xreader.ui.localreader

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * v1.5.10 (F1/F5/G2/F6, build-map Group F): the reader-chrome
 * GLOBALS store — the customisable reader bar's config, the
 * contents panel's per-reader sort order, the per-reader tap
 * preset, the tilt-to-turn switch and the PDF smart scroll
 * lock. These are Settings-side preferences (the option-scope
 * rule's global side — Moon+'s bar is global too), so they
 * live on the shared "settings" DataStore, the same store
 * [LocalReaderPrefs] is constructed over, under their own
 * `localreader.readerbar.*` / `localreader.chrome.*` keys.
 * Per-BOOK reader settings stay in [LocalReaderPrefs]; nothing
 * here is per book.
 *
 * ## F1 semantics (build-map F1, tree truth)
 *
 * The two readers ship DIFFERENT default quick rows — EPUB six
 * actions, PDF four — and there is no honest single global
 * default that reproduces both. So:
 *
 *  - BEFORE any customisation ([ReaderBarStore.customized] is
 *    false), each reader renders its OWN shipped row,
 *    byte-for-byte. The store is not consulted for the row.
 *  - ON FIRST CUSTOMISATION (from either reader's sheet or
 *    the Customise space), ONE shared [ReaderBarConfig] is
 *    written and thereafter governs BOTH readers.
 *  - Per-format availability is NAMED ([ReaderBarActionId]):
 *    an action a format cannot perform is never rendered
 *    there — Font Size is EPUB-only (a rendered PDF page has
 *    no font-size target), Text view and Bookmark are
 *    PDF-native, tilt is both.
 */

// ── The action universe ──────────────────────────────────────────

/** Which local reader a bar action is being resolved for. */
enum class ReaderFormat { EPUB, PDF }

/**
 * One reader-bar action. The first twelve are Moon+'s sheet
 * enumeration (plan F1, in Moon+'s order); [BOOKMARK_ADD] and
 * [TEXT_VIEW] are the tree's PDF-native row members — the
 * shipped PDF row carries them, F1's defaults must reproduce
 * the shipped rows exactly, and dropping them on first
 * customisation would silently delete shipped features. The
 * sheet states each row's availability instead of offering a
 * dead action (build-map F1).
 */
enum class ReaderBarActionId(
    val key: String,
    val label: String,
    val epubAvailable: Boolean,
    val pdfAvailable: Boolean
) {
    ORIENTATION("orientation", "Screen orientation", epubAvailable = true, pdfAvailable = true),
    DAY_NIGHT("dayNight", "Day / night", epubAvailable = true, pdfAvailable = true),
    SPEAK("speak", "Read aloud", epubAvailable = true, pdfAvailable = true),
    AUTOSCROLL("autoscroll", "Auto-scroll", epubAvailable = true, pdfAvailable = true),
    CHAPTERS("chapters", "Chapters", epubAvailable = true, pdfAvailable = true),
    BOOKMARKS("bookmarks", "Bookmarks", epubAvailable = true, pdfAvailable = true),
    BRIGHTNESS("brightness", "Brightness", epubAvailable = true, pdfAvailable = true),
    FONT_SIZE("fontSize", "Font size", epubAvailable = true, pdfAvailable = false),
    SEARCH("search", "Search", epubAvailable = true, pdfAvailable = true),
    TILT("tilt", "Tilt to turn page", epubAvailable = true, pdfAvailable = true),
    VISUAL_OPTIONS("visualOptions", "Visual options", epubAvailable = true, pdfAvailable = true),
    CONTROL_OPTIONS("controlOptions", "Control options", epubAvailable = true, pdfAvailable = true),
    BOOKMARK_ADD("bookmarkAdd", "Bookmark", epubAvailable = false, pdfAvailable = true),
    TEXT_VIEW("textView", "Text view", epubAvailable = false, pdfAvailable = true);

    fun availableIn(format: ReaderFormat): Boolean = when (format) {
        ReaderFormat.EPUB -> epubAvailable
        ReaderFormat.PDF -> pdfAvailable
    }

    /** The availability line the customise sheet prints under
     * a row that only one format can perform; null when the
     * action works in both readers. */
    val availabilityNote: String?
        get() = when {
            epubAvailable && pdfAvailable -> null
            epubAvailable -> "EPUB only"
            pdfAvailable -> "PDF only"
            else -> null
        }

    companion object {
        fun fromKey(key: String): ReaderBarActionId? =
            entries.firstOrNull { it.key == key }
    }
}

/**
 * The EPUB reader's shipped v1.5.9 quick row, expressed in bar
 * action ids (was: Day/night · Contents · Bookmarks · Search ·
 * Book settings · Read aloud — Book settings IS the visual
 * options sheet, Read aloud IS Speak). This is the row an
 * untouched store renders, and the seed a first EPUB
 * customisation starts from.
 */
val EPUB_SHIPPED_BAR: List<ReaderBarActionId> = listOf(
    ReaderBarActionId.DAY_NIGHT,
    ReaderBarActionId.CHAPTERS,
    ReaderBarActionId.BOOKMARKS,
    ReaderBarActionId.SEARCH,
    ReaderBarActionId.VISUAL_OPTIONS,
    ReaderBarActionId.SPEAK
)

/**
 * The PDF reader's shipped v1.5.9 quick row (was: Bookmark ·
 * Bookmarks · Text view · Page display — Page display IS the
 * visual options sheet for a document whose pages are fixed).
 */
val PDF_SHIPPED_BAR: List<ReaderBarActionId> = listOf(
    ReaderBarActionId.BOOKMARK_ADD,
    ReaderBarActionId.BOOKMARKS,
    ReaderBarActionId.TEXT_VIEW,
    ReaderBarActionId.VISUAL_OPTIONS
)

// ── The config model + codec ─────────────────────────────────────

/**
 * The ONE shared bar configuration, written on first
 * customisation. [orderedKeys] is the full row order (every
 * action, Moon+'s enumeration first); [enabledKeys] is the
 * checked subset. Rendering filters by enabled AND by the
 * reader's format availability.
 */
data class ReaderBarConfig(
    val doubleLineLayout: Boolean = false,
    val orderedKeys: List<String> = ReaderBarActionId.entries.map { it.key },
    val enabledKeys: Set<String> = emptySet()
) {
    /** The actions this config renders in [format], in order:
     * enabled, known, and performable by that format. */
    fun activeActions(format: ReaderFormat): List<ReaderBarActionId> =
        orderedKeys.mapNotNull { ReaderBarActionId.fromKey(it) }
            .filter { it.key in enabledKeys }
            .filter { it.availableIn(format) }

    fun toggled(action: ReaderBarActionId, enabled: Boolean): ReaderBarConfig =
        copy(
            enabledKeys = if (enabled) {
                enabledKeys + action.key
            } else {
                enabledKeys - action.key
            }
        )

    /** Moves the action at [fromIndex] to [toIndex] in the
     * full order (the sheet's reorder). Out-of-range moves are
     * no-ops. */
    fun moved(fromIndex: Int, toIndex: Int): ReaderBarConfig {
        if (fromIndex !in orderedKeys.indices || toIndex !in orderedKeys.indices) {
            return this
        }
        if (fromIndex == toIndex) return this
        val next = orderedKeys.toMutableList()
        val item = next.removeAt(fromIndex)
        next.add(toIndex, item)
        return copy(orderedKeys = next)
    }

    companion object {
        /** The config a first customisation seeds from a
         * reader's own shipped row: that row enabled AND
         * leading the order in its shipped arrangement (a
         * first customisation never reshuffles the row the
         * reader already knows), the remaining actions after
         * it in the Moon+ enumeration. */
        fun seededFrom(format: ReaderFormat): ReaderBarConfig {
            val shipped = when (format) {
                ReaderFormat.EPUB -> EPUB_SHIPPED_BAR
                ReaderFormat.PDF -> PDF_SHIPPED_BAR
            }
            val shippedKeys = shipped.map { it.key }
            return ReaderBarConfig(
                doubleLineLayout = false,
                orderedKeys = shippedKeys +
                    ReaderBarActionId.entries
                        .map { it.key }
                        .filter { it !in shippedKeys },
                enabledKeys = shippedKeys.toSet()
            )
        }
    }
}

/**
 * The bar codec: a single-line string,
 * `v1;d=<0|1>;o=<key,key,...>;e=<key,key,...>`. Hand-rolled
 * (no JSON) so the store has no new dependency and a corrupt
 * value decodes to null — never a crash, never a half-config.
 */
fun encodeReaderBarConfig(config: ReaderBarConfig): String {
    val order = config.orderedKeys
        .mapNotNull { ReaderBarActionId.fromKey(it)?.key }
        .distinct()
    val enabled = config.enabledKeys
        .mapNotNull { ReaderBarActionId.fromKey(it)?.key }
        .toSet()
    return buildString {
        append("v1;d=").append(if (config.doubleLineLayout) "1" else "0")
        append(";o=").append(order.joinToString(","))
        append(";e=").append(enabled.joinToString(","))
    }
}

fun decodeReaderBarConfig(raw: String?): ReaderBarConfig? {
    if (raw.isNullOrBlank()) return null
    val parts = raw.split(";")
    if (parts.firstOrNull() != "v1") return null
    var doubleLine = false
    var order: List<String>? = null
    var enabled: Set<String>? = null
    for (part in parts.drop(1)) {
        val value = part.substringAfter("=", "")
        when (part.substringBefore("=")) {
            "d" -> doubleLine = value == "1"
            "o" -> order = value.split(",")
                .mapNotNull { ReaderBarActionId.fromKey(it.trim())?.key }
                .distinct()
            "e" -> enabled = value.split(",")
                .mapNotNull { ReaderBarActionId.fromKey(it.trim())?.key }
                .toSet()
        }
    }
    val knownOrder = order?.takeIf { it.isNotEmpty() } ?: return null
    // Any action the stored order predates joins at the end,
    // so an update that adds an action never loses it.
    val completed = knownOrder + ReaderBarActionId.entries
        .map { it.key }.filter { it !in knownOrder }
    return ReaderBarConfig(
        doubleLineLayout = doubleLine,
        orderedKeys = completed,
        enabledKeys = enabled ?: emptySet()
    )
}

/**
 * The row a reader renders: the shipped row while the store
 * is untouched, the shared config's active actions once any
 * customisation exists. Pure — F1's gate test drives exactly
 * this.
 */
fun resolveReaderBar(
    customized: Boolean,
    config: ReaderBarConfig?,
    format: ReaderFormat
): List<ReaderBarActionId> =
    if (!customized || config == null) {
        when (format) {
            ReaderFormat.EPUB -> EPUB_SHIPPED_BAR
            ReaderFormat.PDF -> PDF_SHIPPED_BAR
        }
    } else {
        config.activeActions(format)
    }

// ── G2: tap-layout presets ───────────────────────────────────────

/**
 * G2's three PRESET tap layouts (Moon+ default · edges-only ·
 * nine-zone) — presets, not a grid editor; the limitation is
 * stated in the Control Options sheet itself.
 */
enum class TapLayoutPreset(val key: String, val label: String) {
    MOON_DEFAULT("moonDefault", "Moon+ default"),
    EDGES_ONLY("edgesOnly", "Edges only"),
    NINE_ZONE("nineZone", "Nine-zone");

    companion object {
        fun fromKey(key: String?): TapLayoutPreset =
            entries.firstOrNull { it.key == key } ?: MOON_DEFAULT
    }
}

/** What a tap in a zone does. */
enum class TapZoneAction { TURN_PREV, TURN_NEXT, TOGGLE_CHROME, NONE }

/**
 * The zone map for a preset, over a tap at ([xFraction],
 * [yFraction]) of the page. MOON_DEFAULT is the shipped map
 * (left third back, right third forward, centre chrome) and
 * ignores Y. EDGES_ONLY turns only on the outer fifths.
 * NINE_ZONE divides the page in thirds both ways: the middle
 * column's centre toggles chrome, its top/bottom are inert,
 * the side columns turn.
 */
fun tapActionFor(
    preset: TapLayoutPreset,
    xFraction: Float,
    yFraction: Float
): TapZoneAction {
    val x = xFraction.coerceIn(0f, 1f)
    val y = yFraction.coerceIn(0f, 1f)
    return when (preset) {
        TapLayoutPreset.MOON_DEFAULT -> when {
            x < 0.33f -> TapZoneAction.TURN_PREV
            x > 0.67f -> TapZoneAction.TURN_NEXT
            else -> TapZoneAction.TOGGLE_CHROME
        }
        TapLayoutPreset.EDGES_ONLY -> when {
            x < 0.2f -> TapZoneAction.TURN_PREV
            x > 0.8f -> TapZoneAction.TURN_NEXT
            x in 0.4f..0.6f -> TapZoneAction.TOGGLE_CHROME
            else -> TapZoneAction.NONE
        }
        TapLayoutPreset.NINE_ZONE -> {
            val col = when {
                x < 1f / 3f -> 0
                x < 2f / 3f -> 1
                else -> 2
            }
            val row = when {
                y < 1f / 3f -> 0
                y < 2f / 3f -> 1
                else -> 2
            }
            when {
                col == 0 -> TapZoneAction.TURN_PREV
                col == 2 -> TapZoneAction.TURN_NEXT
                row == 1 -> TapZoneAction.TOGGLE_CHROME
                else -> TapZoneAction.NONE
            }
        }
    }
}

// ── The store ────────────────────────────────────────────────────

class ReaderBarStore(private val store: DataStore<Preferences>) {

    /** True once ANY customisation has been written (F1's
     * one-shared-config trigger). */
    val customized: Flow<Boolean> = store.data.map { prefs ->
        prefs[KEY_CUSTOMIZED] ?: false
    }

    /** The shared config; null while untouched. */
    val config: Flow<ReaderBarConfig?> = store.data.map { prefs ->
        if (prefs[KEY_CUSTOMIZED] == true) {
            decodeReaderBarConfig(prefs[KEY_CONFIG])
        } else {
            null
        }
    }

    /**
     * Writes a customisation. The FIRST write seeds from the
     * customising reader's shipped row when the caller passes
     * a seed-only intent — concretely, callers read [config]
     * (or [ReaderBarConfig.seededFrom]) and pass the full next
     * config here; this function only persists + marks.
     */
    suspend fun saveConfig(config: ReaderBarConfig) {
        store.edit { prefs ->
            prefs[KEY_CONFIG] = encodeReaderBarConfig(config)
            prefs[KEY_CUSTOMIZED] = true
        }
    }

    // ── F5: contents sort, per reader ────────────────────────

    fun contentsSort(format: ReaderFormat): Flow<ContentsSortOrder> =
        store.data.map { prefs ->
            ContentsSortOrder.fromKey(prefs[sortKey(format)])
        }

    suspend fun saveContentsSort(format: ReaderFormat, order: ContentsSortOrder) {
        store.edit { prefs -> prefs[sortKey(format)] = order.key }
    }

    // ── G2: tap preset, per reader ───────────────────────────

    fun tapPreset(format: ReaderFormat): Flow<TapLayoutPreset> =
        store.data.map { prefs -> TapLayoutPreset.fromKey(prefs[tapKey(format)]) }

    suspend fun saveTapPreset(format: ReaderFormat, preset: TapLayoutPreset) {
        store.edit { prefs -> prefs[tapKey(format)] = preset.key }
    }

    // ── F6: tilt-to-turn switch (global, OFF by default) ─────

    val tiltEnabled: Flow<Boolean> = store.data.map { prefs ->
        prefs[KEY_TILT] ?: false
    }

    suspend fun saveTiltEnabled(enabled: Boolean) {
        store.edit { prefs -> prefs[KEY_TILT] = enabled }
    }

    // ── G2: PDF smart scroll lock (ON by default) ────────────

    val smartScrollLock: Flow<Boolean> = store.data.map { prefs ->
        prefs[KEY_SMART_LOCK] ?: true
    }

    suspend fun saveSmartScrollLock(enabled: Boolean) {
        store.edit { prefs -> prefs[KEY_SMART_LOCK] = enabled }
    }

    private fun sortKey(format: ReaderFormat) = stringPreferencesKey(
        when (format) {
            ReaderFormat.EPUB -> "localreader.contents.sort.epub"
            ReaderFormat.PDF -> "localreader.contents.sort.pdf"
        }
    )

    private fun tapKey(format: ReaderFormat) = stringPreferencesKey(
        when (format) {
            ReaderFormat.EPUB -> "localreader.tap.epub"
            ReaderFormat.PDF -> "localreader.tap.pdf"
        }
    )

    companion object {
        val KEY_CUSTOMIZED =
            booleanPreferencesKey("localreader.readerbar.customized")
        val KEY_CONFIG =
            stringPreferencesKey("localreader.readerbar.config")
        val KEY_TILT =
            booleanPreferencesKey("localreader.tilt.enabled")
        val KEY_SMART_LOCK =
            booleanPreferencesKey("localreader.pdf.smart_scroll_lock")
    }
}
