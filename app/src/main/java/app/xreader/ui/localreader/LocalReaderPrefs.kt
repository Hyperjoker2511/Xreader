package app.xreader.ui.localreader

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import app.xreader.data.engine.PdfPageFilter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/**
 * v1.5.7 (Scopes B+C): the two local readers' OWN settings,
 * persisted on the shared settings DataStore — the same pattern
 * the rest of the app uses (DataStore preferences, never Room).
 *
 * The two formats keep SEPARATE setting sets on purpose (the
 * EPUB/PDF split research, §4: settings sheets must not be
 * shared): [EpubBookSettings] is typography + theme for flowing
 * text; [PdfDocSettings] is page display only — zoom, scroll
 * model and a render filter. There is deliberately no common
 * base type.
 *
 * Scope follows the standing option-scope rule: a change made
 * inside a book's reader belongs to THAT book, so every key is
 * per-book. First-open defaults come from the global reader
 * config (the ViewModel seeds the EPUB text size from it).
 *
 * v1.5.8 additions: G1 (scroll default + the one-time upgrade
 * rule), G6 (justify/hyphens), G7 (named theme presets), G8
 * (shared comforts on both sets), H1 (fit mode), H4 (margin
 * crop), H9 (spread).
 */

// ── Shared comforts (G8) ─────────────────────────────────────────

/** Orientation lock for the local readers (G8): the host
 * Activity's requested orientation while the reader is open. */
enum class ReaderOrientation { FREE, PORTRAIT, LANDSCAPE }

// ── EPUB book-mode settings ──────────────────────────────────────

enum class EpubThemeChoice { LIGHT, DARK, SEPIA }

/** Illustration-plate treatment while the DARK theme is active.
 * Applied ONLY through Readium's `imageFilter` preference
 * (split report §4) — never by post-processing pages. */
enum class EpubPlateFilter { ORIGINAL, DARKEN, INVERT }

/**
 * v1.5.8 (G7): the named theme presets — (theme, text colour,
 * page colour) triples over the SAME preference keys the free
 * swatches write. Paper is the light default the day/night
 * toggle (G4) returns to; AMOLED is the out-of-box dark
 * (Nocturne fence: the app's look is dark-first).
 */
enum class EpubThemePreset(
    val theme: EpubThemeChoice,
    val textColorArgb: Int?,
    val backgroundColorArgb: Int?
) {
    PAPER(EpubThemeChoice.LIGHT, 0xFF1F2430.toInt(), 0xFFF7F0DC.toInt()),
    SEPIA(EpubThemeChoice.SEPIA, 0xFF5B4636.toInt(), 0xFFEAD9B0.toInt()),
    NIGHT(EpubThemeChoice.DARK, 0xFFE8E3D8.toInt(), 0xFF0B0B12.toInt()),
    AMOLED(EpubThemeChoice.DARK, null, 0xFF000000.toInt());

    val isLight: Boolean get() = theme != EpubThemeChoice.DARK
}

/** Applies [preset]'s triple over the existing keys and
 * remembers it as the last preset of its kind (G4's toggle
 * memory). Pure — the day/night test drives this directly. */
fun EpubBookSettings.applyPreset(preset: EpubThemePreset): EpubBookSettings = copy(
    theme = preset.theme,
    textColorArgb = preset.textColorArgb,
    backgroundColorArgb = preset.backgroundColorArgb,
    lastLightPreset = if (preset.isLight) preset else lastLightPreset,
    lastDarkPreset = if (preset.isLight) lastDarkPreset else preset
)

/** The preset this settings set currently matches exactly, or
 * null when the reader holds a free-swatch combination. */
fun EpubBookSettings.currentPreset(): EpubThemePreset? =
    EpubThemePreset.entries.firstOrNull { preset ->
        preset.theme == theme &&
            preset.textColorArgb == textColorArgb &&
            preset.backgroundColorArgb == backgroundColorArgb
    }

/**
 * v1.5.8 (G4): the one-tap day/night flip — DARK ⇄ the last
 * light preset (Paper by default). Pure settings transform;
 * the reading position is never part of it.
 */
fun EpubBookSettings.toggleDayNight(): EpubBookSettings =
    if (theme == EpubThemeChoice.DARK) {
        applyPreset(lastLightPreset)
    } else {
        applyPreset(lastDarkPreset)
    }

data class EpubBookSettings(
    /** CSS font-family name for Readium's FontFamily, null = the
     * book's own default. */
    val fontFamilyName: String? = null,
    /** Readium base font size: 1.0 = 100%. */
    val fontSize: Double = 1.0,
    /** Readium font weight (e.g. 1.0 normal, 1.4 bolder), null = default. */
    val fontWeight: Double? = null,
    val lineHeight: Double? = null,
    /** In em, Readium range roughly -0.2..0.4; null = default. */
    val letterSpacing: Double? = null,
    /** In em; null = default. */
    val wordSpacing: Double? = null,
    /** In em; null = default. */
    val paragraphSpacing: Double? = null,
    /** In em; null = default. */
    val paragraphIndent: Double? = null,
    /** Readium page-margins factor (1.0 = default). */
    val pageMargins: Double = 1.0,
    val theme: EpubThemeChoice = EpubThemeChoice.DARK,
    /** ARGB int for Readium's textColor, null = theme default. */
    val textColorArgb: Int? = null,
    /** ARGB int for Readium's backgroundColor, null = theme default.
     * v1.5.8 (G7): the fresh default is AMOLED black — the
     * out-of-box preset is [EpubThemePreset.AMOLED]. */
    val backgroundColorArgb: Int? = 0xFF000000.toInt(),
    /** true = continuous scroll, false = paginated pages.
     * v1.5.8 (G1): the default is SCROLL — an EPUB light novel
     * opens as a scrolling book (the owner's Moon+ posture);
     * paginated is one switch away. Pre-1.5.8 stored values are
     * governed by the one-time upgrade rule in [LocalReaderPrefs]. */
    val scroll: Boolean = true,
    /** v1.5.9 (F-7b): the fresh default is ORIGINAL — plates
     * ship unfiltered (a CSS filter over full-screen plates
     * during scroll is a per-frame GPU tax). Stored values are
     * untouched: a book that stored DARKEN keeps DARKEN. */
    val plateFilter: EpubPlateFilter = EpubPlateFilter.ORIGINAL,
    /** v1.5.8 (G6): justified alignment, default ON (Moon+
     * renders this class of book justified). */
    val justify: Boolean = true,
    /** v1.5.8 (G6): hyphenation, submitted with [justify]. */
    val hyphens: Boolean = true,
    /** v1.5.8 (G4): the day/night toggle's memory. */
    val lastLightPreset: EpubThemePreset = EpubThemePreset.PAPER,
    val lastDarkPreset: EpubThemePreset = EpubThemePreset.AMOLED,
    // ── G8 shared comforts (per book, like every reader setting)
    val volumeKeyTurn: Boolean = false,
    val keepScreenOn: Boolean = false,
    val orientation: ReaderOrientation = ReaderOrientation.FREE,
    /** Auto-scroll speed multiplier (G9); 1.0 = the base rate. */
    val autoScrollSpeed: Double = 1.0,
    /** v1.5.10 (G3): the per-book USER font — the plain file
     * NAME of a font file the owner picked into the
     * app-private fonts dir (see ReadiumEpubSession's fonts
     * seam). Null = the bundled font road ([fontFamilyName]).
     * A user font is a per-book choice under the option-scope
     * rule, like every reader setting here. */
    val userFontFileName: String? = null
)

// ── PDF document-mode settings ───────────────────────────────────

enum class PdfScrollModel { PAGED, CONTINUOUS }

/** v1.5.8 (H1): how a paged page meets the screen — FIT_PAGE
 * shows the whole page (Moon+'s open posture, the default);
 * FIT_WIDTH is the shipped v1.5.7 behaviour. */
enum class PdfFitMode { FIT_PAGE, FIT_WIDTH }

data class PdfDocSettings(
    /** Remembered pinch zoom (Play Books' "Remember zoom"
     * pattern): kept across page turns, persisted per book. */
    val zoom: Float = 1f,
    val scrollModel: PdfScrollModel = PdfScrollModel.PAGED,
    /** Night display = a page-render filter, never text theming. */
    val filter: PdfPageFilter = PdfPageFilter.NONE,
    /** v1.5.8 (H1): fit choice for the paged model, per book. */
    val fitMode: PdfFitMode = PdfFitMode.FIT_PAGE,
    /** v1.5.8 (H9): landscape two-page spread, per book. */
    val spread: Boolean = false,
    // ── G8 shared comforts (per book)
    val volumeKeyTurn: Boolean = false,
    val keepScreenOn: Boolean = false,
    val orientation: ReaderOrientation = ReaderOrientation.FREE,
    /** Auto-scroll speed multiplier (H7, G9's engine). */
    val autoScrollSpeed: Double = 1.0
)

// ── Store ────────────────────────────────────────────────────────

class LocalReaderPrefs(private val store: DataStore<Preferences>) {

    @Volatile private var scrollUpgradeChecked = false
    @Volatile private var plateFilterUpgradeChecked = false

    /**
     * v1.5.8 (G1) — THE ONE-TIME UPGRADE RULE (load-bearing).
     * A stored `scroll = false` from a pre-1.5.8 build is NOT a
     * choice: the shipped saveEpub wrote the scroll key
     * UNCONDITIONALLY as part of the whole settings object on
     * ANY settings change, so any slider the owner touched in
     * the v1.5.7 proof snapshotted `scroll = false` onto his
     * books. Governed by the marker key
     * `localreader.epub.scrollDefaultVersion` (absent from
     * pre-1.5.8 stores): on first access, if the marker is
     * absent, ALL stored scroll values are cleared ONCE and the
     * marker is written; from then on the key is a genuine
     * per-book choice and is never touched again.
     */
    private suspend fun ensureScrollDefaultUpgrade() {
        if (scrollUpgradeChecked) return
        store.edit { prefs ->
            if (prefs[SCROLL_DEFAULT_VERSION] == null) {
                val doomed = prefs.asMap().keys
                    .filter { it.name.endsWith(SCROLL_KEY_SUFFIX) }
                doomed.forEach { key ->
                    @Suppress("UNCHECKED_CAST")
                    prefs.remove(key as Preferences.Key<Any>)
                }
                prefs[SCROLL_DEFAULT_VERSION] = SCROLL_DEFAULT_VERSION_VALUE
            }
        }
        scrollUpgradeChecked = true
    }

    /**
     * v1.5.10 (E1) — THE ONE-TIME PLATE-FILTER MIGRATION, in
     * [ensureScrollDefaultUpgrade]'s exact shape. v1.5.9's F-7b
     * changed only the FRESH plate-filter default to ORIGINAL
     * and stated its own limit: a book that had already stored
     * DARKEN — the pre-1.5.9 shipped default, persisted by the
     * old whole-object save on ANY settings change — kept
     * DARKEN forever, so the owner's plates stayed dimmed by a
     * value no build ever asked him to choose. Governed by the
     * marker key `localreader.epub.plateFilterVersion` (absent
     * from every shipped store): on first access, if the
     * marker is absent, every stored per-book plate-filter
     * value equal to DARKEN is CLEARED ONCE (the book falls
     * back to the fresh default, ORIGINAL) and the marker is
     * written; from then on the stored value is a genuine
     * per-book choice and is never touched again. Values other
     * than DARKEN (INVERT, ORIGINAL) are NOT touched: DARKEN is
     * the only value a build ever imposed by default. DataStore
     * only — no image path is involved anywhere (E2: the book's
     * own B/W plates are book-genuine and are never "fixed").
     */
    private suspend fun ensurePlateFilterUpgrade() {
        if (plateFilterUpgradeChecked) return
        store.edit { prefs ->
            if (prefs[PLATE_FILTER_VERSION] == null) {
                val doomed = prefs.asMap().entries
                    .filter { (key, value) ->
                        key.name.endsWith(PLATE_FILTER_KEY_SUFFIX) &&
                            value == EpubPlateFilter.DARKEN.name
                    }
                    .map { it.key }
                doomed.forEach { key ->
                    @Suppress("UNCHECKED_CAST")
                    prefs.remove(key as Preferences.Key<Any>)
                }
                prefs[PLATE_FILTER_VERSION] = PLATE_FILTER_VERSION_VALUE
            }
        }
        plateFilterUpgradeChecked = true
    }

    fun epubSettings(bookId: Long): Flow<EpubBookSettings> = flow {
        ensureScrollDefaultUpgrade()
        ensurePlateFilterUpgrade()
        emitAll(store.data.map { prefs -> prefs.toEpubSettings(bookId) })
    }

    /** Whether this book carries a STORED scroll choice (post
     * upgrade rule) — G10's FXL default applies only when it
     * does not. */
    suspend fun hasStoredEpubScroll(bookId: Long): Boolean {
        ensureScrollDefaultUpgrade()
        ensurePlateFilterUpgrade()
        return store.data.first().contains(epubScroll(bookId))
    }

    private fun Preferences.toEpubSettings(bookId: Long): EpubBookSettings =
        EpubBookSettings(
            fontFamilyName = this[epubFontFamily(bookId)],
            fontSize = this[epubFontSize(bookId)] ?: 1.0,
            fontWeight = this[epubFontWeight(bookId)],
            lineHeight = this[epubLineHeight(bookId)],
            letterSpacing = this[epubLetterSpacing(bookId)],
            wordSpacing = this[epubWordSpacing(bookId)],
            paragraphSpacing = this[epubParagraphSpacing(bookId)],
            paragraphIndent = this[epubParagraphIndent(bookId)],
            pageMargins = this[epubPageMargins(bookId)] ?: 1.0,
            theme = this[epubTheme(bookId)]?.let { name ->
                EpubThemeChoice.entries.firstOrNull { it.name == name }
            } ?: EpubThemeChoice.DARK,
            textColorArgb = this[epubTextColor(bookId)]?.toInt(),
            backgroundColorArgb = this[epubBackgroundColor(bookId)]?.toInt()
                ?: 0xFF000000.toInt(),
            scroll = this[epubScroll(bookId)] ?: true,
            plateFilter = this[epubPlateFilter(bookId)]?.let { name ->
                EpubPlateFilter.entries.firstOrNull { it.name == name }
            } ?: EpubPlateFilter.ORIGINAL,
            justify = this[epubJustify(bookId)] ?: true,
            hyphens = this[epubHyphens(bookId)] ?: true,
            lastLightPreset = this[epubLastLightPreset(bookId)]?.let { name ->
                EpubThemePreset.entries.firstOrNull { it.name == name && it.isLight }
            } ?: EpubThemePreset.PAPER,
            lastDarkPreset = this[epubLastDarkPreset(bookId)]?.let { name ->
                EpubThemePreset.entries.firstOrNull { it.name == name && !it.isLight }
            } ?: EpubThemePreset.AMOLED,
            volumeKeyTurn = this[epubVolumeKeys(bookId)] ?: false,
            keepScreenOn = this[epubKeepScreenOn(bookId)] ?: false,
            orientation = this[epubOrientation(bookId)]?.let { name ->
                ReaderOrientation.entries.firstOrNull { it.name == name }
            } ?: ReaderOrientation.FREE,
            autoScrollSpeed = this[epubAutoScrollSpeed(bookId)] ?: 1.0,
            userFontFileName = this[epubUserFontFile(bookId)]
        )

    suspend fun saveEpub(bookId: Long, settings: EpubBookSettings) {
        ensureScrollDefaultUpgrade()
        ensurePlateFilterUpgrade()
        store.edit { prefs ->
            fun <T> put(key: Preferences.Key<T>, value: T?) {
                if (value == null) prefs.remove(key) else prefs[key] = value
            }
            put(epubFontFamily(bookId), settings.fontFamilyName)
            prefs[epubFontSize(bookId)] = settings.fontSize
            put(epubFontWeight(bookId), settings.fontWeight)
            put(epubLineHeight(bookId), settings.lineHeight)
            put(epubLetterSpacing(bookId), settings.letterSpacing)
            put(epubWordSpacing(bookId), settings.wordSpacing)
            put(epubParagraphSpacing(bookId), settings.paragraphSpacing)
            put(epubParagraphIndent(bookId), settings.paragraphIndent)
            prefs[epubPageMargins(bookId)] = settings.pageMargins
            prefs[epubTheme(bookId)] = settings.theme.name
            put(epubTextColor(bookId), settings.textColorArgb?.toLong())
            put(epubBackgroundColor(bookId), settings.backgroundColorArgb?.toLong())
            prefs[epubScroll(bookId)] = settings.scroll
            prefs[epubPlateFilter(bookId)] = settings.plateFilter.name
            prefs[epubJustify(bookId)] = settings.justify
            prefs[epubHyphens(bookId)] = settings.hyphens
            prefs[epubLastLightPreset(bookId)] = settings.lastLightPreset.name
            prefs[epubLastDarkPreset(bookId)] = settings.lastDarkPreset.name
            prefs[epubVolumeKeys(bookId)] = settings.volumeKeyTurn
            prefs[epubKeepScreenOn(bookId)] = settings.keepScreenOn
            prefs[epubOrientation(bookId)] = settings.orientation.name
            prefs[epubAutoScrollSpeed(bookId)] = settings.autoScrollSpeed
            put(epubUserFontFile(bookId), settings.userFontFileName)
        }
    }

    fun pdfSettings(bookId: Long): Flow<PdfDocSettings> = store.data.map { prefs ->
        PdfDocSettings(
            zoom = (prefs[pdfZoom(bookId)] ?: 1f).coerceIn(1f, 4f),
            scrollModel = prefs[pdfScrollModel(bookId)]?.let { name ->
                PdfScrollModel.entries.firstOrNull { it.name == name }
            } ?: PdfScrollModel.PAGED,
            filter = prefs[pdfFilter(bookId)]?.let { name ->
                PdfPageFilter.entries.firstOrNull { it.name == name }
            } ?: PdfPageFilter.NONE,
            fitMode = prefs[pdfFitMode(bookId)]?.let { name ->
                PdfFitMode.entries.firstOrNull { it.name == name }
            } ?: PdfFitMode.FIT_PAGE,
            spread = prefs[pdfSpread(bookId)] ?: false,
            volumeKeyTurn = prefs[pdfVolumeKeys(bookId)] ?: false,
            keepScreenOn = prefs[pdfKeepScreenOn(bookId)] ?: false,
            orientation = prefs[pdfOrientation(bookId)]?.let { name ->
                ReaderOrientation.entries.firstOrNull { it.name == name }
            } ?: ReaderOrientation.FREE,
            autoScrollSpeed = prefs[pdfAutoScrollSpeed(bookId)] ?: 1.0
        )
    }

    suspend fun savePdf(bookId: Long, settings: PdfDocSettings) {
        store.edit { prefs ->
            prefs[pdfZoom(bookId)] = settings.zoom.coerceIn(1f, 4f)
            prefs[pdfScrollModel(bookId)] = settings.scrollModel.name
            prefs[pdfFilter(bookId)] = settings.filter.name
            prefs[pdfFitMode(bookId)] = settings.fitMode.name
            prefs[pdfSpread(bookId)] = settings.spread
            prefs[pdfVolumeKeys(bookId)] = settings.volumeKeyTurn
            prefs[pdfKeepScreenOn(bookId)] = settings.keepScreenOn
            prefs[pdfOrientation(bookId)] = settings.orientation.name
            prefs[pdfAutoScrollSpeed(bookId)] = settings.autoScrollSpeed
        }
    }

    // EPUB keys
    private fun epubFontFamily(id: Long) = stringPreferencesKey("localreader.$id.epub.font_family")
    private fun epubFontSize(id: Long) = doublePreferencesKey("localreader.$id.epub.font_size")
    private fun epubFontWeight(id: Long) = doublePreferencesKey("localreader.$id.epub.font_weight")
    private fun epubLineHeight(id: Long) = doublePreferencesKey("localreader.$id.epub.line_height")
    private fun epubLetterSpacing(id: Long) = doublePreferencesKey("localreader.$id.epub.letter_spacing")
    private fun epubWordSpacing(id: Long) = doublePreferencesKey("localreader.$id.epub.word_spacing")
    private fun epubParagraphSpacing(id: Long) = doublePreferencesKey("localreader.$id.epub.paragraph_spacing")
    private fun epubParagraphIndent(id: Long) = doublePreferencesKey("localreader.$id.epub.paragraph_indent")
    private fun epubPageMargins(id: Long) = doublePreferencesKey("localreader.$id.epub.page_margins")
    private fun epubTheme(id: Long) = stringPreferencesKey("localreader.$id.epub.theme")
    private fun epubTextColor(id: Long) = androidx.datastore.preferences.core.longPreferencesKey("localreader.$id.epub.text_color")
    private fun epubBackgroundColor(id: Long) = androidx.datastore.preferences.core.longPreferencesKey("localreader.$id.epub.background_color")
    private fun epubScroll(id: Long) = booleanPreferencesKey("localreader.$id.epub.scroll")
    private fun epubPlateFilter(id: Long) = stringPreferencesKey("localreader.$id.epub.plate_filter")
    private fun epubJustify(id: Long) = booleanPreferencesKey("localreader.$id.epub.justify")
    private fun epubHyphens(id: Long) = booleanPreferencesKey("localreader.$id.epub.hyphens")
    private fun epubLastLightPreset(id: Long) = stringPreferencesKey("localreader.$id.epub.last_light_preset")
    private fun epubLastDarkPreset(id: Long) = stringPreferencesKey("localreader.$id.epub.last_dark_preset")
    private fun epubVolumeKeys(id: Long) = booleanPreferencesKey("localreader.$id.epub.volume_keys")
    private fun epubKeepScreenOn(id: Long) = booleanPreferencesKey("localreader.$id.epub.keep_screen_on")
    private fun epubOrientation(id: Long) = stringPreferencesKey("localreader.$id.epub.orientation")
    private fun epubAutoScrollSpeed(id: Long) = doublePreferencesKey("localreader.$id.epub.autoscroll_speed")
    private fun epubUserFontFile(id: Long) = stringPreferencesKey(
        "localreader.$id" +
            app.xreader.data.engine.ReadiumEpubSession.USER_FONT_FILE_KEY_SUFFIX
    )

    // PDF keys
    private fun pdfZoom(id: Long) = floatPreferencesKey("localreader.$id.pdf.zoom")
    private fun pdfScrollModel(id: Long) = stringPreferencesKey("localreader.$id.pdf.scroll_model")
    private fun pdfFilter(id: Long) = stringPreferencesKey("localreader.$id.pdf.filter")
    private fun pdfFitMode(id: Long) = stringPreferencesKey("localreader.$id.pdf.fit_mode")
    private fun pdfSpread(id: Long) = booleanPreferencesKey("localreader.$id.pdf.spread")
    private fun pdfVolumeKeys(id: Long) = booleanPreferencesKey("localreader.$id.pdf.volume_keys")
    private fun pdfKeepScreenOn(id: Long) = booleanPreferencesKey("localreader.$id.pdf.keep_screen_on")
    private fun pdfOrientation(id: Long) = stringPreferencesKey("localreader.$id.pdf.orientation")
    private fun pdfAutoScrollSpeed(id: Long) = doublePreferencesKey("localreader.$id.pdf.autoscroll_speed")

    companion object {
        /** G1's one-time upgrade marker (absent in pre-1.5.8
         * stores by construction). */
        val SCROLL_DEFAULT_VERSION =
            intPreferencesKey("localreader.epub.scrollDefaultVersion")
        const val SCROLL_DEFAULT_VERSION_VALUE = 1
        private const val SCROLL_KEY_SUFFIX = ".epub.scroll"

        /** E1's one-time migration marker (absent in every
         * shipped store by construction). */
        val PLATE_FILTER_VERSION =
            intPreferencesKey("localreader.epub.plateFilterVersion")
        const val PLATE_FILTER_VERSION_VALUE = 1
        private const val PLATE_FILTER_KEY_SUFFIX = ".epub.plate_filter"
    }
}
