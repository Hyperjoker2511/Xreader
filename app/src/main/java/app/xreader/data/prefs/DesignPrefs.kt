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
 * the new-user default design (code 120–122: MATERIAL3; code 123:
 * EDITORIAL, the owner's ruling) only for a genuinely fresh install
 * (no database file at first container construction — the same
 * signal [AppearancePrefs.primeDefaultScheme] uses). For an
 * established install it records [AppDesign.NOCTURNE], which is
 * the state such an install is actually in (Nocturne is the only
 * design that existed before this build). An update can therefore
 * never switch an existing user to Material 3, and can never
 * switch a Material 3 user back.
 */
enum class AppDesign { NOCTURNE, MATERIAL3, EDITORIAL }

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
 * Standard is the reduced-motion scheme and a Customise choice.
 * REDUCED (owner approval A-121-2, 2026-11-24) is an
 * OPTIONAL, user-picked setting for lower-end devices:
 * lighter, cheaper motion for weak hardware. (That framing —
 * optional, for lower devices, user-picked only, never
 * auto-applied, never the default — is Muse's recommendation
 * in answer to the owner's question of the same day, not an
 * owner ruling.) It resolves to the same Standard motion
 * scheme — whose spatial springs are near-critically damped
 * (0.9) against Expressive's overshooting 0.6–0.8 — as an
 * explicit third segment on the Motion control, beside the
 * Reduce-motion gate. It is NEVER auto-applied by performance
 * tier and NEVER the default: Expressive stays the default,
 * and the user picks Reduced, on any device. */
enum class M3MotionChoice { EXPRESSIVE, STANDARD, REDUCED }

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

/** Code 121 (scratch design R1): the Library shelf's default
 * tab — the five manual statuses plus All. */
enum class M3LibraryTab { ALL, READING, PLAN_TO_READ, COMPLETED, ON_HOLD, DROPPED }

/** Code 121 (scratch design R8): how many chapters the book
 * page shows inline before the "See all chapters" row. */
enum class M3BookChaptersCap { FIFTY, ALL }

/** Code 121 (scratch design R20): the catalogue grid's column
 * count — 2 is the design's default, 3 the knob's other stop. */
enum class M3CatalogueColumns { TWO, THREE }

/** Code 121 (scratch design R20): whether catalogue cards
 * carry their metadata (title + latest-chapter line) inside
 * the card, or show cover only. */
enum class M3CatalogueMetadata { SHOW, COVER_ONLY }

/** Code 121 (scratch design R4): the downloads storage card
 * appears only once downloaded books pass this threshold.
 * Stored as raw bytes so the value survives enum renames. */
enum class M3StorageThreshold(val bytes: Long) {
    GB_1(1_000_000_000L),
    GB_2(2_000_000_000L),
    GB_5(5_000_000_000L),
    GB_10(10_000_000_000L)
}

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
    /** Owner approval A-121-4 (2026-11-24): AMOLED dark — an
     * OPTION, never the default. When on, the DARK scheme's
     * floor roles go true black in M3ThemeRoot; every other
     * role stays the active scheme's own. Light unaffected. */
    val m3AmoledDark: Boolean = false,
    val bookPageCoverColour: Boolean = true,
    // Navigation (§2.11).
    val tabLabels: M3TabLabels = M3TabLabels.ALWAYS,
    val navForm: M3NavForm = M3NavForm.AUTO,
    // Library (§2.11 + M1).
    val libraryContinueRail: Boolean = true,
    val libraryProgressOnCovers: Boolean = true,
    val libraryFilterRow: Boolean = true,
    // Code 121 (scratch R1 knobs): shelf density + default tab.
    // libraryContinueRail above IS the scratch "Continue
    // carousel Show/Hide" knob (same door, renamed in prose).
    val libraryShelfDensity: M3DensityChoice = M3DensityChoice.COMFORTABLE,
    val libraryDefaultTab: M3LibraryTab = M3LibraryTab.ALL,
    // STRUCK in the scratch design (kept stored, no longer read
    // by any M3 surface): R8's header is the one small bar over
    // the content-colour band and its actions the one button
    // group — there is no header/action form knob (§3.3 R8).
    val bookHeader: M3BookHeader = M3BookHeader.COLLAPSING_COVER,
    val bookAction: M3BookAction = M3BookAction.BUTTON_GROUP,
    // History / Downloads (§2.11 + M1).
    val historyGrouping: M3HistoryGrouping = M3HistoryGrouping.NONE,
    val historyDensity: M3DensityChoice = M3DensityChoice.COMFORTABLE,
    // Code 121 (scratch R3 knob): day-group headers Show/Hide —
    // the scratch design's grouping door (Show = the day-grouped
    // list, Hide = flat). historyGrouping above is the code-120
    // form of the same door, kept stored.
    val historyShowGroups: Boolean = true,
    val downloadsSort: M3DownloadsSort = M3DownloadsSort.RECENT,
    val downloadsChapterSize: Boolean = true,
    // Code 121 (scratch R4 knob): storage card threshold.
    val downloadsStorageThreshold: M3StorageThreshold = M3StorageThreshold.GB_2,
    // Reader (§2.11).
    // STRUCK in the scratch design (kept stored, no longer read
    // by any M3 surface): §8 Q3 — the floating toolbar is the
    // ONE reader chrome form; there is no toolbar-form knob.
    val readerChrome: M3ReaderChrome = M3ReaderChrome.FLOATING_TOOLBAR,
    val selectionColourRow: Boolean = true,
    val contentsTab: M3ContentsTab = M3ContentsTab.CHAPTERS,
    /** S13 knob (§3.8): the manga toolbar's page chip ("1 / 24")
     * — Show (default) / Hide. Manga-chrome only; the text
     * reader's counter chip is not governed by it. */
    val mangaPageChip: Boolean = true,
    // Code 121 (scratch R28 knob): the toolbar's position line.
    val readerPositionLine: Boolean = true,
    // Code 121 (scratch R8 knob): chapters shown inline cap.
    val bookChaptersCap: M3BookChaptersCap = M3BookChaptersCap.FIFTY,
    // Explore (§2.11 + M1).
    // Code 121: scratch R2 knob — the default side is Novels
    // (code 120 defaulted to LAST_USED; LAST_USED stays a
    // selectable value, it is no longer the design default).
    val exploreSide: M3ExploreSide = M3ExploreSide.NOVELS,
    val exploreGrouping: Boolean = true,
    val exploreDensity: M3DensityChoice = M3DensityChoice.COMFORTABLE,
    val exploreStoreView: M3ListView = M3ListView.LIST,
    val exploreRepoSort: M3RepoSort = M3RepoSort.A_Z,
    // Code 121 (scratch R2 knob): the two foot doors (Source
    // store / Add a link) Show/Hide — hidden, they live in the
    // app-bar overflow.
    val exploreStoreDoors: Boolean = true,
    // Code 121 (scratch R20 knobs): catalogue grid columns +
    // in-card metadata.
    val catalogueColumns: M3CatalogueColumns = M3CatalogueColumns.TWO,
    val catalogueMetadata: M3CatalogueMetadata = M3CatalogueMetadata.SHOW,
    // Profile (§2.11 + M1).
    val profileHeaderCard: Boolean = true,
    val profileDoorsList: Boolean = true,
    // Code 121 (scratch R31 knobs): stat cards + trophy density.
    val profileStatCards: Boolean = true,
    val profileTrophyDensity: M3DensityChoice = M3DensityChoice.COMFORTABLE,
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

    /** A stored storage threshold is honoured only as one of
     * the enum's byte values; anything else degrades to the
     * design default (2 GB). */
    fun thresholdOr(raw: Long?): M3StorageThreshold =
        M3StorageThreshold.values().firstOrNull { it.bytes == raw }
            ?: M3StorageThreshold.GB_2

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
        m3AmoledDark = this[KEY_AMOLED_DARK] ?: false,
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
        libraryShelfDensity = DesignValidation.enumOr(
            this[KEY_LIB_SHELF_DENSITY], M3DensityChoice.values(), M3DensityChoice.COMFORTABLE
        ),
        libraryDefaultTab = DesignValidation.enumOr(
            this[KEY_LIB_DEFAULT_TAB], M3LibraryTab.values(), M3LibraryTab.ALL
        ),
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
        historyShowGroups = this[KEY_HISTORY_GROUPS] ?: true,
        downloadsStorageThreshold = DesignValidation.thresholdOr(this[KEY_DOWNLOADS_THRESHOLD]),
        readerChrome = DesignValidation.enumOr(
            this[KEY_READER_CHROME], M3ReaderChrome.values(), M3ReaderChrome.FLOATING_TOOLBAR
        ),
        selectionColourRow = this[KEY_SELECTION_ROW] ?: true,
        contentsTab = DesignValidation.enumOr(
            this[KEY_CONTENTS_TAB], M3ContentsTab.values(), M3ContentsTab.CHAPTERS
        ),
        mangaPageChip = this[KEY_MANGA_PAGE_CHIP] ?: true,
        readerPositionLine = this[KEY_READER_POSITION] ?: true,
        bookChaptersCap = DesignValidation.enumOr(
            this[KEY_BOOK_CHAPTERS_CAP], M3BookChaptersCap.values(), M3BookChaptersCap.FIFTY
        ),
        exploreSide = DesignValidation.enumOr(
            this[KEY_EXPLORE_SIDE], M3ExploreSide.values(), M3ExploreSide.NOVELS
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
        exploreStoreDoors = this[KEY_EXPLORE_STORE_DOORS] ?: true,
        catalogueColumns = DesignValidation.enumOr(
            this[KEY_CATALOGUE_COLUMNS], M3CatalogueColumns.values(), M3CatalogueColumns.TWO
        ),
        catalogueMetadata = DesignValidation.enumOr(
            this[KEY_CATALOGUE_METADATA], M3CatalogueMetadata.values(), M3CatalogueMetadata.SHOW
        ),
        profileHeaderCard = this[KEY_PROFILE_HEADER] ?: true,
        profileDoorsList = this[KEY_PROFILE_DOORS] ?: true,
        profileStatCards = this[KEY_PROFILE_STAT_CARDS] ?: true,
        profileTrophyDensity = DesignValidation.enumOr(
            this[KEY_PROFILE_TROPHY_DENSITY], M3DensityChoice.values(), M3DensityChoice.COMFORTABLE
        ),
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
     * fresh install is seeded with the current new-user default.
     * After this runs once it never runs again.
     *
     * Code 123: the new-user default is EDITORIAL (the owner's
     * ruling, 2026-11-24: "Default is editorial"). Installs that
     * already primed under codes 120–122 keep the flag and are
     * never re-primed — their stored design stands untouched,
     * so no existing user is switched by this change.
     */
    suspend fun primeDesignDefault() {
        store.edit { prefs ->
            if (prefs[KEY_PRIMED] == true) return@edit
            prefs[KEY_PRIMED] = true
            if (prefs[KEY_DESIGN] == null) {
                prefs[KEY_DESIGN] = if (establishedInstall) {
                    AppDesign.NOCTURNE.name
                } else {
                    AppDesign.EDITORIAL.name
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

    /** Code 121 W8 (D-121-W7-05): clear the picked seed AND
     * the saved-seeds history. Used by Recovery's M3 Safe
     * appearance, which resets the colour source to Dynamic —
     * without this the stored seed survived inert and could
     * resurface on the next PICKED write's history. */
    suspend fun clearSeed() =
        store.edit { prefs ->
            prefs.remove(KEY_SEED)
            prefs.remove(KEY_SAVED_SEEDS)
        }

    suspend fun setColourStyle(value: M3ColourStyle) =
        store.edit { it[KEY_COLOUR_STYLE] = value.name }

    suspend fun setThemeMode(value: M3ThemeMode) =
        store.edit { it[KEY_THEME_MODE] = value.name }

    suspend fun setM3AmoledDark(value: Boolean) =
        store.edit { it[KEY_AMOLED_DARK] = value }

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

    suspend fun setMangaPageChip(value: Boolean) =
        store.edit { it[KEY_MANGA_PAGE_CHIP] = value }

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

    // Code 121 setters (scratch-design knobs).
    suspend fun setLibraryShelfDensity(value: M3DensityChoice) =
        store.edit { it[KEY_LIB_SHELF_DENSITY] = value.name }

    suspend fun setLibraryDefaultTab(value: M3LibraryTab) =
        store.edit { it[KEY_LIB_DEFAULT_TAB] = value.name }

    suspend fun setHistoryShowGroups(value: Boolean) =
        store.edit { it[KEY_HISTORY_GROUPS] = value }

    suspend fun setDownloadsStorageThreshold(value: M3StorageThreshold) =
        store.edit { it[KEY_DOWNLOADS_THRESHOLD] = value.bytes }

    suspend fun setReaderPositionLine(value: Boolean) =
        store.edit { it[KEY_READER_POSITION] = value }

    suspend fun setBookChaptersCap(value: M3BookChaptersCap) =
        store.edit { it[KEY_BOOK_CHAPTERS_CAP] = value.name }

    suspend fun setExploreStoreDoors(value: Boolean) =
        store.edit { it[KEY_EXPLORE_STORE_DOORS] = value }

    suspend fun setCatalogueColumns(value: M3CatalogueColumns) =
        store.edit { it[KEY_CATALOGUE_COLUMNS] = value.name }

    suspend fun setCatalogueMetadata(value: M3CatalogueMetadata) =
        store.edit { it[KEY_CATALOGUE_METADATA] = value.name }

    suspend fun setProfileStatCards(value: Boolean) =
        store.edit { it[KEY_PROFILE_STAT_CARDS] = value }

    suspend fun setProfileTrophyDensity(value: M3DensityChoice) =
        store.edit { it[KEY_PROFILE_TROPHY_DENSITY] = value.name }

    private companion object {
        val KEY_PRIMED = booleanPreferencesKey("design.primed.v1")
        val KEY_DESIGN = stringPreferencesKey("design.active")
        val KEY_COLOUR_SOURCE = stringPreferencesKey("design.m3.colourSource")
        val KEY_SEED = longPreferencesKey("design.m3.seed")
        val KEY_COLOUR_STYLE = stringPreferencesKey("design.m3.colourStyle")
        val KEY_SAVED_SEEDS = stringPreferencesKey("design.m3.savedSeeds")
        val KEY_THEME_MODE = stringPreferencesKey("design.m3.themeMode")
        val KEY_AMOLED_DARK = booleanPreferencesKey("design.m3.amoledDark")
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
        val KEY_MANGA_PAGE_CHIP = booleanPreferencesKey("design.m3.mangaPageChip")
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
        val KEY_LIB_SHELF_DENSITY = stringPreferencesKey("design.m3.libraryShelfDensity")
        val KEY_LIB_DEFAULT_TAB = stringPreferencesKey("design.m3.libraryDefaultTab")
        val KEY_HISTORY_GROUPS = booleanPreferencesKey("design.m3.historyShowGroups")
        val KEY_DOWNLOADS_THRESHOLD = longPreferencesKey("design.m3.downloadsStorageThresholdBytes")
        val KEY_READER_POSITION = booleanPreferencesKey("design.m3.readerPositionLine")
        val KEY_BOOK_CHAPTERS_CAP = stringPreferencesKey("design.m3.bookChaptersCap")
        val KEY_EXPLORE_STORE_DOORS = booleanPreferencesKey("design.m3.exploreStoreDoors")
        val KEY_CATALOGUE_COLUMNS = stringPreferencesKey("design.m3.catalogueColumns")
        val KEY_CATALOGUE_METADATA = stringPreferencesKey("design.m3.catalogueMetadata")
        val KEY_PROFILE_STAT_CARDS = booleanPreferencesKey("design.m3.profileStatCards")
        val KEY_PROFILE_TROPHY_DENSITY = stringPreferencesKey("design.m3.profileTrophyDensity")
    }
}
