package app.xreader.data.library

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Library layout choices (v1.0.1 customisation; BENTO added by
 * v1.0.2 R19 — a mixed-span cover layout, optional and never the
 * forced default). */
enum class LibraryLayout { GRID, COMFORTABLE_LIST, COMPACT_LIST, BENTO }

/** Bottom bar styles (v1.0.1 customisation). */
enum class NavBarStyle { FLOATING_PILL, DOCKED_BAR }

/** Tab label styles (v1.0.1 customisation). */
enum class TabLabelStyle { ALWAYS, SELECTED_ONLY, ICONS_ONLY }

/** Where the app opens (v1.0.1 customisation). */
enum class StartScreen { LIBRARY, CONTINUE_READING, DOWNLOADS }

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
    val startScreen: StartScreen = StartScreen.LIBRARY,
    /** v1.0.1 (rec. A): tracked books auto-download new chapters.
     * Default OFF; the queue's prefetch items are Wi-Fi-only inside
     * the download engine. */
    val autoDownloadTracked: Boolean = false,
    val searchQuery: String = "",
    val sort: LibrarySort = LibrarySort.RECENTLY_READ,
    val statusFilter: String = "ALL"
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
            showContinueReading = prefs[KEY_HERO] ?: true,
            showBadges = prefs[KEY_BADGES] ?: true,
            navBarStyle = parse(prefs[KEY_NAV_STYLE], NavBarStyle.FLOATING_PILL),
            tabLabelStyle = parse(prefs[KEY_TAB_LABELS], TabLabelStyle.ALWAYS),
            startScreen = parse(prefs[KEY_START], StartScreen.LIBRARY),
            autoDownloadTracked = prefs[KEY_AUTO_DL] ?: false
        )
    }

    suspend fun setLayout(value: LibraryLayout) = store.edit { it[KEY_LAYOUT] = value.name }
    suspend fun setGridColumns(value: Int) =
        store.edit { it[KEY_COLUMNS] = value.coerceIn(2, 4) }
    suspend fun setShowContinueReading(value: Boolean) = store.edit { it[KEY_HERO] = value }
    suspend fun setShowBadges(value: Boolean) = store.edit { it[KEY_BADGES] = value }
    suspend fun setNavBarStyle(value: NavBarStyle) = store.edit { it[KEY_NAV_STYLE] = value.name }
    suspend fun setTabLabelStyle(value: TabLabelStyle) =
        store.edit { it[KEY_TAB_LABELS] = value.name }
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

    /** Reset customisation (R07): library VIEW choices only —
     * search query, sort and status filter are session state, not
     * customisation, and shelf hidden/selected state is library
     * organisation (R21 data), not look-and-feel. */
    suspend fun resetViewToDefaults() {
        store.edit { prefs ->
            prefs.remove(KEY_LAYOUT)
            prefs.remove(KEY_COLUMNS)
            prefs.remove(KEY_HERO)
            prefs.remove(KEY_BADGES)
            prefs.remove(KEY_NAV_STYLE)
            prefs.remove(KEY_TAB_LABELS)
            prefs.remove(KEY_START)
        }
    }

    private companion object {
        val KEY_AUTO_DL = booleanPreferencesKey("library.auto_download_tracked")
        val KEY_HIDDEN_SHELVES = stringPreferencesKey("library.hidden_shelves")
        val KEY_SELECTED_SHELF = stringPreferencesKey("library.selected_shelf")
        val KEY_LAYOUT = stringPreferencesKey("library.layout")
        val KEY_COLUMNS = intPreferencesKey("library.grid_columns")
        val KEY_HERO = booleanPreferencesKey("library.show_continue")
        val KEY_BADGES = booleanPreferencesKey("library.show_badges")
        val KEY_NAV_STYLE = stringPreferencesKey("library.nav_bar_style")
        val KEY_TAB_LABELS = stringPreferencesKey("library.tab_labels")
        val KEY_START = stringPreferencesKey("library.start_screen")

        inline fun <reified T : Enum<T>> parse(raw: String?, fallback: T): T =
            enumValues<T>().firstOrNull { it.name == raw } ?: fallback
    }
}
