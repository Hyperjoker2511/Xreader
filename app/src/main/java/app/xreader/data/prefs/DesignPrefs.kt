package app.xreader.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Code 120 (Material 3 design build): the whole-app design switch
 * and the Material 3 design's own settings, persisted in the same
 * `settings` DataStore as [AppearancePrefs] under `design.*` keys.
 *
 * The two designs are independent colour worlds: Nocturne's state
 * lives in [AppearancePrefs] and is never read or written here, and
 * nothing here touches it. Switching designs switches which state
 * the theme reads; each design resumes exactly as it was left.
 *
 * THE MIGRATION RULE (design §3 U10 — a correctness requirement,
 * not a default): an existing install's design state is read and
 * honoured exactly as found. [primeDesignDefault] is the only
 * place a design value is ever written without the user's hand:
 * it runs once, only where NO design state exists, and it writes
 * [AppDesign.MATERIAL3] only for a genuinely fresh install
 * (no database file at first container construction — the same
 * signal [AppearancePrefs.primeDefaultScheme] uses). For an
 * established install it records [AppDesign.NOCTURNE], which is
 * the state such an install is actually in (Nocturne is the only
 * design that existed before this build). An update can therefore
 * never switch an existing user to Material 3, and can never
 * switch a Material 3 user back.
 */
enum class AppDesign { NOCTURNE, MATERIAL3 }

/** The M3 colour source (§2.11 Colour theme): the phone's dynamic
 * colour, or a colour the user picked. */
enum class M3ColourSource { DYNAMIC, PICKED }

/** The MCU scheme styles the free picker offers (§1.1.1 door 2).
 * FIDELITY is the default; CONTENT is also the style the book
 * page's cover-derived scheme uses. MONOCHROME and the remaining
 * MCU variants are not offered (design §1.1.1). */
enum class M3ColourStyle { FIDELITY, TONAL_SPOT, VIBRANT, EXPRESSIVE, CONTENT }

/** The M3 design's own dark/light choice (§2.11) — separate from
 * Nocturne's theme mode; neither design reads the other's. */
enum class M3ThemeMode { DARK, LIGHT, FOLLOW_SYSTEM }

/** Theme-level motion (§1.4): Expressive is the design's motion;
 * Standard is the reduced-motion scheme and a Customise choice. */
enum class M3MotionChoice { EXPRESSIVE, STANDARD }

/** Tab label knob for the flexible bar (§2.11 Navigation). */
enum class M3TabLabels { ALWAYS, SELECTED_ONLY }

/** Bar/rail override (§2.11 Navigation): Auto follows the width
 * classes; Bar/Rail pin one form at every width. */
enum class M3NavForm { AUTO, BAR, RAIL }

/** The reader's chrome form in the M3 design (§2.6 R28): the
 * floating toolbar is the default; top-and-bottom bars is the
 * docked alternative (addendum A2). */
enum class M3ReaderChrome { FLOATING_TOOLBAR, TOP_AND_BOTTOM_BARS }

/** Contents sheet default tab (§2.11 Reader). */
enum class M3ContentsTab { CHAPTERS, BOOKMARKS, HIGHLIGHTS }

/** Explore's landing side (§2.11 Explore). */
enum class M3ExploreSide { LAST_USED, NOVELS, MANGA }

/** List density choices (§2.11). */
enum class M3DensityChoice { COMFORTABLE, COMPACT }

/** History grouping (§2.11 History). */
enum class M3HistoryGrouping { NONE, BY_DAY }

/** Downloads sort (§2.11 Downloads). */
enum class M3DownloadsSort { RECENT, TITLE, SIZE }

/** Book-page header + primary-action forms (addendum M1). */
enum class M3BookHeader { COLLAPSING_COVER, COMPACT_BAR }
enum class M3BookAction { BUTTON_GROUP, READ_FAB }

/** Small list-view/sort knobs (addendum M1). */
enum class M3VocabSort { RECENT, A_Z }
enum class M3ListView { CARD_GRID, LIST }
enum class M3RepoSort { A_Z, RECENTLY_ADDED }
enum class M3GalleryColumns { AUTO, TWO, THREE }

/**
 * The Material 3 design's full state. The defaults here are the
 * design's defaults (§2.11); [DesignPrefs] only ever overrides
 * them from stored values or the user's hand.
 */
data class DesignState(
    val design: AppDesign = AppDesign.NOCTURNE,
    // Colour theme (§2.11).
    val colourSource: M3ColourSource = M3ColourSource.DYNAMIC,
    /** The picked seed colour (ARGB), or null when none has been
     * picked yet — the picker falls back to the phone's dynamic
     * primary until one exists (design §1.1.1). */
    val seedArgb: Long? = null,
    val colourStyle: M3ColourStyle = M3ColourStyle.FIDELITY,
    /** Colours the user saved from the picker ("Your seeds"). */
    val savedSeeds: List<Long> = emptyList(),
    val themeMode: M3ThemeMode = M3ThemeMode.DARK,
    val bookPageCoverColour: Boolean = true,
    // Navigation (§2.11).
    val tabLabels: M3TabLabels = M3TabLabels.ALWAYS,
    val navForm: M3NavForm = M3NavForm.AUTO,
    // Library (§2.11 + M1).
    val libraryContinueRail: Boolean = true,
    val libraryProgressOnCovers: Boolean = true,
    val libraryFilterRow: Boolean = true,
    val bookHeader: M3BookHeader = M3BookHeader.COLLAPSING_COVER,
    val bookAction: M3BookAction = M3BookAction.BUTTON_GROUP,
    // History / Downloads (§2.11 + M1).
    val historyGrouping: M3HistoryGrouping = M3HistoryGrouping.NONE,
    val historyDensity: M3DensityChoice = M3DensityChoice.COMFORTABLE,
    val downloadsSort: M3DownloadsSort = M3DownloadsSort.RECENT,
    val downloadsChapterSize: Boolean = true,
    // Reader (§2.11).
    val readerChrome: M3ReaderChrome = M3ReaderChrome.FLOATING_TOOLBAR,
    val selectionColourRow: Boolean = true,
    val contentsTab: M3ContentsTab = M3ContentsTab.CHAPTERS,
    // Explore (§2.11 + M1).
    val exploreSide: M3ExploreSide = M3ExploreSide.LAST_USED,
    val exploreGrouping: Boolean = true,
    val exploreDensity: M3DensityChoice = M3DensityChoice.COMFORTABLE,
    val exploreStoreView: M3ListView = M3ListView.LIST,
    val exploreRepoSort: M3RepoSort = M3RepoSort.A_Z,
    // Profile (§2.11 + M1).
    val profileHeaderCard: Boolean = true,
    val profileDoorsList: Boolean = true,
    val vocabSort: M3VocabSort = M3VocabSort.RECENT,
    val loreView: M3ListView = M3ListView.CARD_GRID,
    val galleryColumns: M3GalleryColumns = M3GalleryColumns.AUTO,
    // Accessibility & motion (§2.11).
    val motionChoice: M3MotionChoice = M3MotionChoice.EXPRESSIVE,
    // Advanced (§2.11): the launch intro's own switch (the intro
    // also still obeys the motion/Reduce-motion gates).
    val launchIntro: Boolean = true
)

/** Parse + range rules for the design state — the same shape as
 * [AppearanceValidation]: stored junk degrades to the design
 * defaults, never to a crash or a half state. */
object DesignValidation {

    fun <T : Enum<T>> enumOr(raw: String?, values: Array<T>, fallback: T): T =
        values.firstOrNull { it.name == raw } ?: fallback

    /** A stored seed is honoured only as a real opaque colour. */
    fun seedOrNull(raw: Long?): Long? =
        raw?.takeIf { it in 0L..0xFFFFFFFFL && (it ushr 24) == 0xFFL }

    fun seedsOrEmpty(raw: String?): List<Long> =
        raw?.split(',')
            ?.mapNotNull { it.trim().toLongOrNull() }
            ?.mapNotNull { seedOrNull(it) }
            ?.distinct()
            ?.take(24)
            ?: emptyList()
}

class DesignPrefs(
    private val store: DataStore<Preferences>,
    /** True when the Room database file already existed at
     * container construction — an established install. Mirrors
     * [AppearancePrefs]' constructor signal. */
    private val establishedInstall: Boolean = false
) {
    val state: Flow<DesignState> = store.data.map { prefs -> prefs.toState() }

    private fun Preferences.toState(): DesignState = DesignState(
        design = DesignValidation.enumOr(
            this[KEY_DESIGN], AppDesign.values(), AppDesign.NOCTURNE
        ),
        colourSource = DesignValidation.enumOr(
            this[KEY_COLOUR_SOURCE], M3ColourSource.values(), M3ColourSource.DYNAMIC
        ),
        seedArgb = DesignValidation.seedOrNull(this[KEY_SEED]),
        colourStyle = DesignValidation.enumOr(
            this[KEY_COLOUR_STYLE], M3ColourStyle.values(), M3ColourStyle.FIDELITY
        ),
        savedSeeds = DesignValidation.seedsOrEmpty(this[KEY_SAVED_SEEDS]),
        themeMode = DesignValidation.enumOr(
            this[KEY_THEME_MODE], M3ThemeMode.values(), M3ThemeMode.DARK
        ),
        bookPageCoverColour = this[KEY_BOOK_COVER] ?: true,
        tabLabels = DesignValidation.enumOr(
            this[KEY_TAB_LABELS], M3TabLabels.values(), M3TabLabels.ALWAYS
        ),
        navForm = DesignValidation.enumOr(
            this[KEY_NAV_FORM], M3NavForm.values(), M3NavForm.AUTO
        ),
        libraryContinueRail = this[KEY_LIB_CONTINUE] ?: true,
        libraryProgressOnCovers = this[KEY_LIB_PROGRESS] ?: true,
        libraryFilterRow = this[KEY_LIB_FILTERS] ?: true,
        bookHeader = DesignValidation.enumOr(
            this[KEY_BOOK_HEADER], M3BookHeader.values(), M3BookHeader.COLLAPSING_COVER
        ),
        bookAction = DesignValidation.enumOr(
            this[KEY_BOOK_ACTION], M3BookAction.values(), M3BookAction.BUTTON_GROUP
        ),
        historyGrouping = DesignValidation.enumOr(
            this[KEY_HISTORY_GROUPING], M3HistoryGrouping.values(), M3HistoryGrouping.NONE
        ),
        historyDensity = DesignValidation.enumOr(
            this[KEY_HISTORY_DENSITY], M3DensityChoice.values(), M3DensityChoice.COMFORTABLE
        ),
        downloadsSort = DesignValidation.enumOr(
            this[KEY_DOWNLOADS_SORT], M3DownloadsSort.values(), M3DownloadsSort.RECENT
        ),
        downloadsChapterSize = this[KEY_DOWNLOADS_SIZE] ?: true,
        readerChrome = DesignValidation.enumOr(
            this[KEY_READER_CHROME], M3ReaderChrome.values(), M3ReaderChrome.FLOATING_TOOLBAR
        ),
        selectionColourRow = this[KEY_SELECTION_ROW] ?: true,
        contentsTab = DesignValidation.enumOr(
            this[KEY_CONTENTS_TAB], M3ContentsTab.values(), M3ContentsTab.CHAPTERS
        ),
        exploreSide = DesignValidation.enumOr(
            this[KEY_EXPLORE_SIDE], M3ExploreSide.values(), M3ExploreSide.LAST_USED
        ),
        exploreGrouping = this[KEY_EXPLORE_GROUPING] ?: true,
        exploreDensity = DesignValidation.enumOr(
            this[KEY_EXPLORE_DENSITY], M3DensityChoice.values(), M3DensityChoice.COMFORTABLE
        ),
        exploreStoreView = DesignValidation.enumOr(
            this[KEY_EXPLORE_STORE_VIEW], M3ListView.values(), M3ListView.LIST
        ),
        exploreRepoSort = DesignValidation.enumOr(
            this[KEY_EXPLORE_REPO_SORT], M3RepoSort.values(), M3RepoSort.A_Z
        ),
        profileHeaderCard = this[KEY_PROFILE_HEADER] ?: true,
        profileDoorsList = this[KEY_PROFILE_DOORS] ?: true,
        vocabSort = DesignValidation.enumOr(
            this[KEY_VOCAB_SORT], M3VocabSort.values(), M3VocabSort.RECENT
        ),
        loreView = DesignValidation.enumOr(
            this[KEY_LORE_VIEW], M3ListView.values(), M3ListView.CARD_GRID
        ),
        galleryColumns = DesignValidation.enumOr(
            this[KEY_GALLERY_COLUMNS], M3GalleryColumns.values(), M3GalleryColumns.AUTO
        ),
        motionChoice = DesignValidation.enumOr(
            this[KEY_MOTION], M3MotionChoice.values(), M3MotionChoice.EXPRESSIVE
        ),
        launchIntro = this[KEY_LAUNCH_INTRO] ?: true
    )

    /**
     * THE MIGRATION RULE (U10). One-shot, in the same priming
     * launch as [AppearancePrefs.primeDefaultScheme]: the first
     * run that finds the primed flag absent records the design
     * state AS FOUND — a stored design (the user's own earlier
     * choice) is left byte-identical; where no design state
     * exists, an established install is recorded as NOCTURNE
     * (the design it is already running) and only a genuinely
     * fresh install is seeded with MATERIAL3, the design default
     * for new users. After this runs once it never runs again.
     */
    suspend fun primeDesignDefault() {
        store.edit { prefs ->
            if (prefs[KEY_PRIMED] == true) return@edit
            prefs[KEY_PRIMED] = true
            if (prefs[KEY_DESIGN] == null) {
                prefs[KEY_DESIGN] = if (establishedInstall) {
                    AppDesign.NOCTURNE.name
                } else {
                    AppDesign.MATERIAL3.name
                }
            }
        }
    }

    suspend fun setDesign(value: AppDesign) =
        store.edit { it[KEY_DESIGN] = value.name }

    suspend fun setColourSource(value: M3ColourSource) =
        store.edit { it[KEY_COLOUR_SOURCE] = value.name }

    /** Picking a colour also makes it a saved seed (the picker's
     * "Your seeds" row is the history of picks, newest first). */
    suspend fun setSeed(argb: Long) {
        val clean = DesignValidation.seedOrNull(argb) ?: return
        store.edit { prefs ->
            prefs[KEY_SEED] = clean
            val seeds = DesignValidation.seedsOrEmpty(prefs[KEY_SAVED_SEEDS])
            prefs[KEY_SAVED_SEEDS] =
                (listOf(clean) + seeds.filter { it != clean })
                    .take(24).joinToString(",")
        }
    }

    suspend fun setColourStyle(value: M3ColourStyle) =
        store.edit { it[KEY_COLOUR_STYLE] = value.name }

    suspend fun setThemeMode(value: M3ThemeMode) =
        store.edit { it[KEY_THEME_MODE] = value.name }

    suspend fun setBookPageCoverColour(value: Boolean) =
        store.edit { it[KEY_BOOK_COVER] = value }

    suspend fun setTabLabels(value: M3TabLabels) =
        store.edit { it[KEY_TAB_LABELS] = value.name }

    suspend fun setNavForm(value: M3NavForm) =
        store.edit { it[KEY_NAV_FORM] = value.name }

    suspend fun setLibraryContinueRail(value: Boolean) =
        store.edit { it[KEY_LIB_CONTINUE] = value }

    suspend fun setLibraryProgressOnCovers(value: Boolean) =
        store.edit { it[KEY_LIB_PROGRESS] = value }

    suspend fun setLibraryFilterRow(value: Boolean) =
        store.edit { it[KEY_LIB_FILTERS] = value }

    suspend fun setBookHeader(value: M3BookHeader) =
        store.edit { it[KEY_BOOK_HEADER] = value.name }

    suspend fun setBookAction(value: M3BookAction) =
        store.edit { it[KEY_BOOK_ACTION] = value.name }

    suspend fun setHistoryGrouping(value: M3HistoryGrouping) =
        store.edit { it[KEY_HISTORY_GROUPING] = value.name }

    suspend fun setHistoryDensity(value: M3DensityChoice) =
        store.edit { it[KEY_HISTORY_DENSITY] = value.name }

    suspend fun setDownloadsSort(value: M3DownloadsSort) =
        store.edit { it[KEY_DOWNLOADS_SORT] = value.name }

    suspend fun setDownloadsChapterSize(value: Boolean) =
        store.edit { it[KEY_DOWNLOADS_SIZE] = value }

    suspend fun setReaderChrome(value: M3ReaderChrome) =
        store.edit { it[KEY_READER_CHROME] = value.name }

    suspend fun setSelectionColourRow(value: Boolean) =
        store.edit { it[KEY_SELECTION_ROW] = value }

    suspend fun setContentsTab(value: M3ContentsTab) =
        store.edit { it[KEY_CONTENTS_TAB] = value.name }

    suspend fun setExploreSide(value: M3ExploreSide) =
        store.edit { it[KEY_EXPLORE_SIDE] = value.name }

    suspend fun setExploreGrouping(value: Boolean) =
        store.edit { it[KEY_EXPLORE_GROUPING] = value }

    suspend fun setExploreDensity(value: M3DensityChoice) =
        store.edit { it[KEY_EXPLORE_DENSITY] = value.name }

    suspend fun setExploreStoreView(value: M3ListView) =
        store.edit { it[KEY_EXPLORE_STORE_VIEW] = value.name }

    suspend fun setExploreRepoSort(value: M3RepoSort) =
        store.edit { it[KEY_EXPLORE_REPO_SORT] = value.name }

    suspend fun setProfileHeaderCard(value: Boolean) =
        store.edit { it[KEY_PROFILE_HEADER] = value }

    suspend fun setProfileDoorsList(value: Boolean) =
        store.edit { it[KEY_PROFILE_DOORS] = value }

    suspend fun setVocabSort(value: M3VocabSort) =
        store.edit { it[KEY_VOCAB_SORT] = value.name }

    suspend fun setLoreView(value: M3ListView) =
        store.edit { it[KEY_LORE_VIEW] = value.name }

    suspend fun setGalleryColumns(value: M3GalleryColumns) =
        store.edit { it[KEY_GALLERY_COLUMNS] = value.name }

    suspend fun setMotionChoice(value: M3MotionChoice) =
        store.edit { it[KEY_MOTION] = value.name }

    suspend fun setLaunchIntro(value: Boolean) =
        store.edit { it[KEY_LAUNCH_INTRO] = value }

    private companion object {
        val KEY_PRIMED = booleanPreferencesKey("design.primed.v1")
        val KEY_DESIGN = stringPreferencesKey("design.active")
        val KEY_COLOUR_SOURCE = stringPreferencesKey("design.m3.colourSource")
        val KEY_SEED = longPreferencesKey("design.m3.seed")
        val KEY_COLOUR_STYLE = stringPreferencesKey("design.m3.colourStyle")
        val KEY_SAVED_SEEDS = stringPreferencesKey("design.m3.savedSeeds")
        val KEY_THEME_MODE = stringPreferencesKey("design.m3.themeMode")
        val KEY_BOOK_COVER = booleanPreferencesKey("design.m3.bookPageCoverColour")
        val KEY_TAB_LABELS = stringPreferencesKey("design.m3.tabLabels")
        val KEY_NAV_FORM = stringPreferencesKey("design.m3.navForm")
        val KEY_LIB_CONTINUE = booleanPreferencesKey("design.m3.libraryContinueRail")
        val KEY_LIB_PROGRESS = booleanPreferencesKey("design.m3.libraryProgressOnCovers")
        val KEY_LIB_FILTERS = booleanPreferencesKey("design.m3.libraryFilterRow")
        val KEY_BOOK_HEADER = stringPreferencesKey("design.m3.bookHeader")
        val KEY_BOOK_ACTION = stringPreferencesKey("design.m3.bookAction")
        val KEY_HISTORY_GROUPING = stringPreferencesKey("design.m3.historyGrouping")
        val KEY_HISTORY_DENSITY = stringPreferencesKey("design.m3.historyDensity")
        val KEY_DOWNLOADS_SORT = stringPreferencesKey("design.m3.downloadsSort")
        val KEY_DOWNLOADS_SIZE = booleanPreferencesKey("design.m3.downloadsChapterSize")
        val KEY_READER_CHROME = stringPreferencesKey("design.m3.readerChrome")
        val KEY_SELECTION_ROW = booleanPreferencesKey("design.m3.selectionColourRow")
        val KEY_CONTENTS_TAB = stringPreferencesKey("design.m3.contentsTab")
        val KEY_EXPLORE_SIDE = stringPreferencesKey("design.m3.exploreSide")
        val KEY_EXPLORE_GROUPING = booleanPreferencesKey("design.m3.exploreGrouping")
        val KEY_EXPLORE_DENSITY = stringPreferencesKey("design.m3.exploreDensity")
        val KEY_EXPLORE_STORE_VIEW = stringPreferencesKey("design.m3.exploreStoreView")
        val KEY_EXPLORE_REPO_SORT = stringPreferencesKey("design.m3.exploreRepoSort")
        val KEY_PROFILE_HEADER = booleanPreferencesKey("design.m3.profileHeaderCard")
        val KEY_PROFILE_DOORS = booleanPreferencesKey("design.m3.profileDoorsList")
        val KEY_VOCAB_SORT = stringPreferencesKey("design.m3.vocabSort")
        val KEY_LORE_VIEW = stringPreferencesKey("design.m3.loreView")
        val KEY_GALLERY_COLUMNS = stringPreferencesKey("design.m3.galleryColumns")
        val KEY_MOTION = stringPreferencesKey("design.m3.motionChoice")
        val KEY_LAUNCH_INTRO = booleanPreferencesKey("design.launchIntro")
    }
}
