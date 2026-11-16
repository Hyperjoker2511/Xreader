package app.xreader.data.library

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.math.roundToInt

/** Library layout choices (v1.0.1 customisation; BENTO added by
 * v1.0.2 R19 — a mixed-span cover layout, optional and never the
 * forced default). */
enum class LibraryLayout { GRID, COMFORTABLE_LIST, COMPACT_LIST, BENTO }

/** Bottom bar styles (v1.0.1 customisation). */
enum class NavBarStyle { FLOATING_PILL, DOCKED_BAR }

/** Tab label styles (v1.0.1 customisation). */
enum class TabLabelStyle { ALWAYS, SELECTED_ONLY, ICONS_ONLY }

/**
 * Dock indicator styles (v1.6.2, nav-bar styles —
 * SPEC-NAVBAR-STYLES-2026-11-16 Change 1; the owner's word:
 * "All of it" after "Extra customizable"). GOOEY is the shipped
 * melt (the default — an untouched install keeps its identity,
 * only the travel timing improves). SLIDE is a filled circle
 * sliding slot to slot; BUBBLE a circle riding proud of the
 * bar's top edge, carrying the selected icon up into it; NOTCH
 * dips the bar's own top edge into a scoop around the active
 * icon; GLOW glides a small dot under the icon and tints the
 * icon with a soft glow; PLAIN is no travelling indicator at
 * all — the active slot tints instantly (the behaviour reduced
 * motion has always produced, made a first-class choice).
 *
 * Nav engine round (2026-11-16, SPEC-NAV-ENGINE-2026-11-16 —
 * the owner's "All the good onces" from the catalogue
 * research): GOOEY is REBUILT as a two-edge stretched pill
 * (the shipped neck/trail melt never escaped the pill's
 * outline on a 1-slot jump — the owner's "Gooye not working"),
 * and ten styles join: WORM (the caterpillar stretch), LENS
 * (a drawn liquid lens — no backdrop capture), EXPANDING
 * (the selected slot widens, icon + label in a pill),
 * SEGMENTED (a raised thumb in a divided track), SQUIRCLE
 * (a rounded-square cell highlight), RING (an outline ring
 * around the icon), DOT (a matte dot under the label),
 * TOPLINE (a short line at the bar's top edge), MINIMAL
 * (colour alone, no shape) and FLASHY (the selected slot's
 * icon swaps up into its label). Stored values are enum
 * NAMES — the first six keep their names and order, so every
 * stored pref from the styles pass still reads identically.
 *
 * Travel-choreography round (2026-11-16,
 * SPEC-NAV-TRAVEL-WAVE-2026-11-16 §C): WAVE joins, appended
 * last so every earlier name and ordinal stands — the bar's
 * top edge carries a travelling dip and the selected icon
 * rides in a filled circle cradled in it (the V5 reference:
 * edge, circle and icon are one parameter, the travel value).
 */
enum class NavIndicatorStyle {
    GOOEY, SLIDE, BUBBLE, NOTCH, GLOW, PLAIN,
    WORM, LENS, EXPANDING, SEGMENTED, SQUIRCLE, RING,
    DOT, TOPLINE, MINIMAL, FLASHY, WAVE
}

/** Where the dock indicator's colour comes from (v1.6.2):
 * THEME = the colour scheme's primary (the shipped behaviour);
 * CUSTOM = the user's own ARGB from the exact-colour wheel,
 * stored beside this mode in the same state. */
enum class NavIndicatorColourMode { THEME, CUSTOM }

/** Where the app opens (v1.0.1 customisation). */
enum class StartScreen { LIBRARY, CONTINUE_READING, DOWNLOADS }

/**
 * Novel cover size (v1.6.2, the owner's word: "Library covers
 * also customizable sizes"). FOLLOW_APP is the default: the
 * covers follow the App size (Small app → small covers). A
 * size picked here wins for covers only — a small UI with big
 * covers is allowed. The grid fits as many columns across as
 * the screen allows at the size's target cover width (see
 * [CoverSizeLogic]); an explicit column pick in Customise
 * still overrides the count, as it always has.
 */
enum class CoverSize { FOLLOW_APP, SMALL, NORMAL, LARGE }

/**
 * Pure cover-size → column resolution (v1.6.2). A preset names
 * a TARGET cover width in dp; the column count is however
 * many covers of that width fit the available width, held to
 * [MIN_COLUMNS]..[MAX_COLUMNS]. At the Normal target and
 * App size 1.00 the counts reproduce the width tiers' counts
 * on the phones that matter (360 dp → 3, 411 dp → 4, 480 dp
 * → 4, 600 dp → 6), so Normal changes nothing for a user who
 * never touches the control.
 */
object CoverSizeLogic {
    /** Row gap inside the Library grid (its standing 10 dp). */
    const val GAP_DP = 10
    const val MIN_COLUMNS = 2
    const val MAX_COLUMNS = 6

    const val SMALL_TARGET_DP = 64
    const val NORMAL_TARGET_DP = 84
    const val LARGE_TARGET_DP = 118

    /** The target cover width for [size]; FOLLOW_APP scales
     * the Normal target by the App size factor (itself held
     * to the App size range, 0.70–1.30). */
    fun targetWidthDp(size: CoverSize, appSize: Float): Int = when (size) {
        CoverSize.SMALL -> SMALL_TARGET_DP
        CoverSize.NORMAL -> NORMAL_TARGET_DP
        CoverSize.LARGE -> LARGE_TARGET_DP
        CoverSize.FOLLOW_APP ->
            (NORMAL_TARGET_DP * appSize.coerceIn(0.70f, 1.30f))
                .roundToInt()
    }

    /** How many [targetWidthDp]-wide covers fit
     * [availableWidthDp] with [gapDp] between them. */
    fun columnsForWidth(
        availableWidthDp: Int,
        targetWidthDp: Int,
        gapDp: Int = GAP_DP
    ): Int {
        val target = targetWidthDp.coerceAtLeast(1)
        val fits = (availableWidthDp + gapDp) / (target + gapDp)
        return fits.coerceIn(MIN_COLUMNS, MAX_COLUMNS)
    }

    /** The Novels grid's column count under [size]. */
    fun resolveColumns(
        availableWidthDp: Int,
        size: CoverSize,
        appSize: Float
    ): Int = columnsForWidth(
        availableWidthDp, targetWidthDp(size, appSize)
    )
}

/** The indicator colour the wheel opens on (v1.6.2): the
 * Nocturne violet primary — the colour the dock already wears
 * under the default scheme, so a first CUSTOM pick starts
 * from home. */
const val DEFAULT_NAV_INDICATOR_ARGB: Long = 0xFF8B5CF6L

/**
 * Library + navigation customisation state (v1.0.1). Defaults are
 * exactly the v1.0.0 behaviour — customisation is for people who go
 * looking, never a tax on everyone (the user's design credo).
 */
data class LibraryState(
    val layout: LibraryLayout = LibraryLayout.GRID,
    val gridColumns: Int = 3,
    val showContinueReading: Boolean = true,
    val showBadges: Boolean = true,
    val navBarStyle: NavBarStyle = NavBarStyle.FLOATING_PILL,
    val tabLabelStyle: TabLabelStyle = TabLabelStyle.ALWAYS,
    /** Dock indicator style (v1.6.2): GOOEY unless the user
     * picked another in Customise → Navigation. */
    val navIndicatorStyle: NavIndicatorStyle = NavIndicatorStyle.GOOEY,
    /** Dock indicator colour source (v1.6.2): THEME unless the
     * user picked an exact colour of their own. */
    val navIndicatorColourMode: NavIndicatorColourMode =
        NavIndicatorColourMode.THEME,
    /** The user's exact indicator colour (v1.6.2), ARGB as a
     * Long — read only while [navIndicatorColourMode] is
     * CUSTOM. The default is the Nocturne violet primary, so
     * the wheel opens on the colour the bar already wears. */
    val navIndicatorColourArgb: Long = DEFAULT_NAV_INDICATOR_ARGB,
    val startScreen: StartScreen = StartScreen.LIBRARY,
    /** v1.0.1 (rec. A): tracked books auto-download new chapters.
     * Default OFF; the queue's prefetch items are Wi-Fi-only inside
     * the download engine. */
    val autoDownloadTracked: Boolean = false,
    val searchQuery: String = "",
    val sort: LibrarySort = LibrarySort.RECENTLY_READ,
    val statusFilter: String = "ALL",
    /** Adaptive metrics (Patch 5): true while the user has NEVER
     * stored an explicit column choice (the columns key is
     * absent — a reset also removes it, returning to auto). In
     * auto the Library resolves its column count from the width
     * tier (UiMetrics.gridColumns); [gridColumns] above then
     * carries only the placeholder default and the Customise
     * chips keep editing the explicit value — the moment a chip
     * is tapped the key is stored, auto ends, and the explicit
     * choice wins on every phone. */
    val gridColumnsAuto: Boolean = true,
    /** Novel cover size (v1.6.2): FOLLOW_APP unless the user
     * picked a cover size of its own. Only the Novels grid
     * reads it, and only while [gridColumnsAuto] — an explicit
     * column pick overrides the count it resolves to. */
    val coverSize: CoverSize = CoverSize.FOLLOW_APP
)

enum class LibrarySort { RECENTLY_READ, TITLE, MOST_CHAPTERS, RECENTLY_ADDED }

/** Pure library list logic (v1.0.1): search + sort + status filter,
 * unit-tested apart from the UI. */
object LibraryListLogic {
    data class Row(
        val bookId: Long,
        val title: String,
        val author: String,
        val totalChapters: Int,
        val addedAt: Long,
        val lastOpenedAt: Long?,
        val hasProgress: Boolean,
        val finished: Boolean,
        val status: ReadingStatus
    )

    fun apply(rows: List<Row>, state: LibraryState): List<Row> {
        val query = state.searchQuery.trim().lowercase()
        val filtered = rows.filter { row ->
            val matchesQuery = query.isEmpty() ||
                row.title.lowercase().contains(query) ||
                row.author.lowercase().contains(query)
            val matchesStatus = when (state.statusFilter) {
                "READING" -> row.status == ReadingStatus.READING ||
                    (row.status == ReadingStatus.NONE && row.hasProgress && !row.finished)
                "COMPLETED" -> row.status == ReadingStatus.COMPLETED || row.finished
                // v1.0.2 (R23): the full status set gets its own
                // filters — the row is separate from shelves.
                "ON_HOLD" -> row.status == ReadingStatus.ON_HOLD
                "DROPPED" -> row.status == ReadingStatus.DROPPED
                "NOT_STARTED" -> !row.hasProgress && !row.finished &&
                    row.status != ReadingStatus.COMPLETED
                else -> true
            }
            matchesQuery && matchesStatus
        }
        return when (state.sort) {
            LibrarySort.RECENTLY_READ ->
                filtered.sortedByDescending { it.lastOpenedAt ?: 0L }
            LibrarySort.TITLE -> filtered.sortedBy { it.title.lowercase() }
            LibrarySort.MOST_CHAPTERS ->
                filtered.sortedByDescending { it.totalChapters }
            LibrarySort.RECENTLY_ADDED ->
                filtered.sortedByDescending { it.addedAt }
        }
    }
}

class LibraryPrefs(private val store: DataStore<Preferences>) {

    val state: Flow<LibraryState> = store.data.map { prefs ->
        LibraryState(
            layout = parse(prefs[KEY_LAYOUT], LibraryLayout.GRID),
            gridColumns = (prefs[KEY_COLUMNS] ?: 3).coerceIn(2, 4),
            gridColumnsAuto = prefs[KEY_COLUMNS] == null,
            coverSize = parse(prefs[KEY_COVER_SIZE], CoverSize.FOLLOW_APP),
            showContinueReading = prefs[KEY_HERO] ?: true,
            showBadges = prefs[KEY_BADGES] ?: true,
            navBarStyle = parse(prefs[KEY_NAV_STYLE], NavBarStyle.FLOATING_PILL),
            tabLabelStyle = parse(prefs[KEY_TAB_LABELS], TabLabelStyle.ALWAYS),
            navIndicatorStyle = parse(
                prefs[KEY_NAV_INDICATOR_STYLE], NavIndicatorStyle.GOOEY
            ),
            navIndicatorColourMode = parse(
                prefs[KEY_NAV_INDICATOR_COLOUR_MODE],
                NavIndicatorColourMode.THEME
            ),
            navIndicatorColourArgb =
                prefs[KEY_NAV_INDICATOR_COLOUR_ARGB]
                    ?: DEFAULT_NAV_INDICATOR_ARGB,
            startScreen = parse(prefs[KEY_START], StartScreen.LIBRARY),
            autoDownloadTracked = prefs[KEY_AUTO_DL] ?: false
        )
    }

    suspend fun setLayout(value: LibraryLayout) = store.edit { it[KEY_LAYOUT] = value.name }
    suspend fun setGridColumns(value: Int) =
        store.edit { it[KEY_COLUMNS] = value.coerceIn(2, 4) }

    /** Adaptive metrics (Patch 5): back to AUTO columns — removes
     * ONLY the stored explicit choice, so the Library resolves
     * its count from the width tier again. The single-key
     * equivalent of the KEY_COLUMNS removal resetViewToDefaults
     * performs on the whole view set. */
    suspend fun clearGridColumns() {
        store.edit { prefs -> prefs.remove(KEY_COLUMNS) }
    }
    suspend fun setCoverSize(value: CoverSize) =
        store.edit { it[KEY_COVER_SIZE] = value.name }
    suspend fun setShowContinueReading(value: Boolean) = store.edit { it[KEY_HERO] = value }
    suspend fun setShowBadges(value: Boolean) = store.edit { it[KEY_BADGES] = value }
    suspend fun setNavBarStyle(value: NavBarStyle) = store.edit { it[KEY_NAV_STYLE] = value.name }
    suspend fun setTabLabelStyle(value: TabLabelStyle) =
        store.edit { it[KEY_TAB_LABELS] = value.name }
    suspend fun setNavIndicatorStyle(value: NavIndicatorStyle) =
        store.edit { it[KEY_NAV_INDICATOR_STYLE] = value.name }
    suspend fun setNavIndicatorColourMode(value: NavIndicatorColourMode) =
        store.edit { it[KEY_NAV_INDICATOR_COLOUR_MODE] = value.name }

    /** Picking an exact colour IS choosing CUSTOM (v1.6.2):
     * one write stores the colour and flips the mode, so the
     * two can never disagree. The alpha byte is forced opaque —
     * the wheel's ARGB carries one, but a translucent indicator
     * would wash out against the page under the glass. */
    suspend fun setNavIndicatorColour(argb: Long) =
        store.edit { prefs ->
            prefs[KEY_NAV_INDICATOR_COLOUR_ARGB] =
                argb or 0xFF000000L
            prefs[KEY_NAV_INDICATOR_COLOUR_MODE] =
                NavIndicatorColourMode.CUSTOM.name
        }
    suspend fun setStartScreen(value: StartScreen) = store.edit { it[KEY_START] = value.name }
    suspend fun setAutoDownloadTracked(value: Boolean) =
        store.edit { it[KEY_AUTO_DL] = value }

    /** Which built-in shelves the user hid (R21 — All can never be
     * in this set; ShelfLogic doesn't offer it). Stored as builtin
     * keys ("favourites", "downloaded", "behind_on"). */
    val hiddenBuiltinShelves: Flow<Set<String>> = store.data.map { prefs ->
        prefs[KEY_HIDDEN_SHELVES]?.split(',')?.map { it.trim() }
            ?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()
    }

    suspend fun setBuiltinShelfHidden(key: String, hidden: Boolean) {
        store.edit { prefs ->
            val current = prefs[KEY_HIDDEN_SHELVES]?.split(',')
                ?.map { it.trim() }?.filter { it.isNotEmpty() }
                ?.toMutableSet() ?: mutableSetOf()
            if (hidden) current += key else current -= key
            prefs[KEY_HIDDEN_SHELVES] = current.joinToString(",")
        }
    }

    /** The Library's selected shelf chip (R21), persisted so the
     * user returns to the shelf they left. "all" when absent. */
    val selectedShelf: Flow<String> = store.data.map { prefs ->
        prefs[KEY_SELECTED_SHELF] ?: "all"
    }

    suspend fun setSelectedShelf(ref: String) =
        store.edit { it[KEY_SELECTED_SHELF] = ref }

    /** Manga programme (M3): the Library/Explore split's
     * last-used side — Novels (TEXT) or Manga — remembered
     * across restarts, shared by both screens so the two
     * always agree. Stored by enum name; anything unreadable
     * is the Novels side. */
    val librarySide: Flow<app.xreader.data.extension.MediaKind> =
        store.data.map { prefs ->
            parse(
                prefs[KEY_LIBRARY_SIDE],
                app.xreader.data.extension.MediaKind.TEXT
            )
        }

    suspend fun setLibrarySide(side: app.xreader.data.extension.MediaKind) =
        store.edit { it[KEY_LIBRARY_SIDE] = side.name }

    /** Reset customisation (R07): library VIEW choices only —
     * search query, sort and status filter are session state, not
     * customisation, and shelf hidden/selected state is library
     * organisation (R21 data), not look-and-feel. */
    suspend fun resetViewToDefaults() {
        store.edit { prefs ->
            prefs.remove(KEY_LAYOUT)
            prefs.remove(KEY_COLUMNS)
            prefs.remove(KEY_COVER_SIZE)
            prefs.remove(KEY_HERO)
            prefs.remove(KEY_BADGES)
            prefs.remove(KEY_NAV_STYLE)
            prefs.remove(KEY_TAB_LABELS)
            prefs.remove(KEY_NAV_INDICATOR_STYLE)
            prefs.remove(KEY_NAV_INDICATOR_COLOUR_MODE)
            prefs.remove(KEY_NAV_INDICATOR_COLOUR_ARGB)
            prefs.remove(KEY_START)
        }
    }

    private companion object {
        val KEY_AUTO_DL = booleanPreferencesKey("library.auto_download_tracked")
        val KEY_HIDDEN_SHELVES = stringPreferencesKey("library.hidden_shelves")
        val KEY_SELECTED_SHELF = stringPreferencesKey("library.selected_shelf")
        val KEY_LIBRARY_SIDE = stringPreferencesKey("library.side")
        val KEY_LAYOUT = stringPreferencesKey("library.layout")
        val KEY_COLUMNS = intPreferencesKey("library.grid_columns")
        val KEY_COVER_SIZE = stringPreferencesKey("library.cover_size")
        val KEY_HERO = booleanPreferencesKey("library.show_continue")
        val KEY_BADGES = booleanPreferencesKey("library.show_badges")
        val KEY_NAV_STYLE = stringPreferencesKey("library.nav_bar_style")
        val KEY_TAB_LABELS = stringPreferencesKey("library.tab_labels")
        val KEY_NAV_INDICATOR_STYLE =
            stringPreferencesKey("library.nav_indicator_style")
        val KEY_NAV_INDICATOR_COLOUR_MODE =
            stringPreferencesKey("library.nav_indicator_colour_mode")
        val KEY_NAV_INDICATOR_COLOUR_ARGB =
            longPreferencesKey("library.nav_indicator_colour_argb")
        val KEY_START = stringPreferencesKey("library.start_screen")

        inline fun <reified T : Enum<T>> parse(raw: String?, fallback: T): T =
            enumValues<T>().firstOrNull { it.name == raw } ?: fallback
    }
}
