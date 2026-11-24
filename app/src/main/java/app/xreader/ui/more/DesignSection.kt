package app.xreader.ui.more

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.xreader.data.prefs.AppDesign
import app.xreader.data.prefs.DesignState
import app.xreader.data.prefs.M3BookAction
import app.xreader.data.prefs.M3BookHeader
import app.xreader.data.prefs.M3ColourSource
import app.xreader.data.prefs.M3ColourStyle
import app.xreader.data.prefs.M3ContentsTab
import app.xreader.data.prefs.M3DensityChoice
import app.xreader.data.prefs.M3DownloadsSort
import app.xreader.data.prefs.M3ExploreSide
import app.xreader.data.prefs.M3GalleryColumns
import app.xreader.data.prefs.M3HistoryGrouping
import app.xreader.data.prefs.M3LibraryTab
import app.xreader.data.prefs.M3ListView
import app.xreader.data.prefs.M3MotionChoice
import app.xreader.data.prefs.M3NavForm
import app.xreader.data.prefs.M3RepoSort
import app.xreader.data.prefs.M3TabLabels
import app.xreader.data.prefs.M3ThemeMode
import app.xreader.data.prefs.M3VocabSort
import app.xreader.ui.common.ColourWheelPickerDialog
import app.xreader.ui.common.NocturneSwitch
import app.xreader.ui.theme.m3DarkTheme
import app.xreader.ui.theme.resolveM3ColorScheme
// Code 121 (R30): the Material 3 Customise page's imports.
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Healing
import androidx.compose.material.icons.filled.LibraryBooks
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.collectAsState
import app.xreader.data.prefs.AppearanceValidation
import app.xreader.ui.common.ColourBar
import app.xreader.ui.common.ColourBarSelection

/**
 * Code 120 (design §2.11 + addendum M1): the Design page of
 * Customise — the whole-app design switch at the top, and below
 * it the Material 3 design's knobs, grouped under their map
 * section names (Colour theme · Navigation · Library & book
 * page · History · Reader · Motion & advanced). Each writes
 * DesignPrefs only, and Nocturne's knobs stay on Nocturne's
 * own pages, untouched. The two designs are independent
 * worlds: switching carries nothing across, and each design
 * resumes exactly as it was left.
 *
 * Code 120 completion (owner, 2026-11-24 — the book page is
 * not frozen, and the M3 design is built fully, all app): the
 * map's remaining knobs are rendered here too — the book-page
 * header/action forms (R8) and the vocabulary sort (R10) and
 * lore view (R11) under Library & book page, Downloads sort +
 * chapter size, the Explore set, and the Profile set with the
 * generated-art columns (R33). Every control below is wired to
 * its surface under Material 3; under Nocturne none of them is
 * shown and no surface reads them.
 */
@Composable
fun DesignPageContent(
    state: DesignState,
    viewModel: CustomiseViewModel
) {
    Text(
        "The app's whole design. Each design keeps its own " +
            "colours and settings — switching never carries " +
            "anything across.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    DesignChoiceCard(
        name = "Nocturne",
        line = "Violet on black glass — Xreader's own design.",
        selected = state.design == AppDesign.NOCTURNE
    ) { viewModel.setDesign(AppDesign.NOCTURNE) }
    DesignChoiceCard(
        name = "Material 3",
        line = "Google's Material 3 Expressive — dynamic " +
            "colour and the official components.",
        selected = state.design == AppDesign.MATERIAL3
    ) { viewModel.setDesign(AppDesign.MATERIAL3) }

    if (state.design != AppDesign.MATERIAL3) {
        Text(
            "Nocturne's knobs live on the pages below — Colour " +
                "theme, Glass & surfaces, Navigation and the " +
                "rest. Material 3's knobs appear here when " +
                "Material 3 is the design.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        DesignSwitch(
            label = "Launch intro",
            description = "The short intro when the app opens.",
            checked = state.launchIntro,
            onChange = viewModel::setLaunchIntro
        )
        return
    }

    // ── Colour theme (§2.11) ────────────────────────────────
    DesignSectionLabel("Colour theme")
    DesignChoice(
        label = "Colour source",
        options = listOf(
            M3ColourSource.DYNAMIC to "Dynamic colour",
            M3ColourSource.PICKED to "Picked colour"
        ),
        selected = state.colourSource,
        onPick = viewModel::setM3ColourSource
    )
    SeedRow(state = state, viewModel = viewModel)
    DesignChoice(
        label = "Colour style",
        options = listOf(
            M3ColourStyle.FIDELITY to "Fidelity",
            M3ColourStyle.TONAL_SPOT to "Tonal Spot",
            M3ColourStyle.VIBRANT to "Vibrant",
            M3ColourStyle.EXPRESSIVE to "Expressive",
            M3ColourStyle.CONTENT to "Content"
        ),
        selected = state.colourStyle,
        onPick = viewModel::setM3ColourStyle
    )
    Text(
        "Fidelity keeps your colour closest. The style " +
            "applies the moment you pick a colour; Dynamic " +
            "colour always comes from the phone as generated.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    DesignChoice(
        label = "Mode",
        options = listOf(
            M3ThemeMode.DARK to "Dark",
            M3ThemeMode.LIGHT to "Light",
            M3ThemeMode.FOLLOW_SYSTEM to "Follow system"
        ),
        selected = state.themeMode,
        onPick = viewModel::setM3ThemeMode
    )
    DesignSwitch(
        label = "Book-page cover colour",
        description = "A book's page takes its colours from " +
            "its cover while you are on it.",
        checked = state.bookPageCoverColour,
        onChange = viewModel::setM3BookPageCoverColour
    )

    // ── Navigation (§2.11) ──────────────────────────────────
    DesignSectionLabel("Navigation")
    DesignChoice(
        label = "Tab labels",
        options = listOf(
            M3TabLabels.ALWAYS to "Always",
            M3TabLabels.SELECTED_ONLY to "Selected only"
        ),
        selected = state.tabLabels,
        onPick = viewModel::setM3TabLabels
    )
    DesignChoice(
        label = "Bar or rail",
        options = listOf(
            M3NavForm.AUTO to "Auto",
            M3NavForm.BAR to "Bar",
            M3NavForm.RAIL to "Rail"
        ),
        selected = state.navForm,
        onPick = viewModel::setM3NavForm
    )

    // ── Library & book page (§2.11 + M1) ────────────────────
    DesignSectionLabel("Library & book page")
    DesignSwitch(
        label = "Continue rail",
        description = "The Continue row at the top of the Library.",
        checked = state.libraryContinueRail,
        onChange = viewModel::setM3LibraryContinueRail
    )
    DesignSwitch(
        label = "Progress on covers",
        description = "The reading-progress line on book covers.",
        checked = state.libraryProgressOnCovers,
        onChange = viewModel::setM3LibraryProgressOnCovers
    )
    DesignSwitch(
        label = "Filter row",
        description = "The status filter row in the Library.",
        checked = state.libraryFilterRow,
        onChange = viewModel::setM3LibraryFilterRow
    )
    DesignChoice(
        label = "Book page header",
        options = listOf(
            M3BookHeader.COLLAPSING_COVER to "Collapsing cover",
            M3BookHeader.COMPACT_BAR to "Compact bar"
        ),
        selected = state.bookHeader,
        onPick = viewModel::setM3BookHeader
    )
    DesignChoice(
        label = "Book page main action",
        options = listOf(
            M3BookAction.BUTTON_GROUP to "Button group",
            M3BookAction.READ_FAB to "Floating Read button"
        ),
        selected = state.bookAction,
        onPick = viewModel::setM3BookAction
    )
    DesignChoice(
        label = "Words I'm learning — sort",
        options = listOf(
            M3VocabSort.RECENT to "Recent",
            M3VocabSort.A_Z to "A–Z"
        ),
        selected = state.vocabSort,
        onPick = viewModel::setM3VocabSort
    )
    DesignChoice(
        label = "Lore — view",
        options = listOf(
            M3ListView.CARD_GRID to "Card grid",
            M3ListView.LIST to "List"
        ),
        selected = state.loreView,
        onPick = viewModel::setM3LoreView
    )
    // ── History (§2.11) ─────────────────────────────────
    DesignSectionLabel("History")
    DesignChoice(
        label = "History grouping",
        options = listOf(
            M3HistoryGrouping.NONE to "None",
            M3HistoryGrouping.BY_DAY to "By day"
        ),
        selected = state.historyGrouping,
        onPick = viewModel::setM3HistoryGrouping
    )
    DesignChoice(
        label = "History density",
        options = listOf(
            M3DensityChoice.COMFORTABLE to "Comfortable",
            M3DensityChoice.COMPACT to "Compact"
        ),
        selected = state.historyDensity,
        onPick = viewModel::setM3HistoryDensity
    )
    // ── Downloads (§2.4 R4) ───────────────────────────────
    DesignSectionLabel("Downloads")
    DesignChoice(
        label = "Downloads sort",
        options = listOf(
            M3DownloadsSort.RECENT to "Recent",
            M3DownloadsSort.TITLE to "Title",
            M3DownloadsSort.SIZE to "Size"
        ),
        selected = state.downloadsSort,
        onPick = viewModel::setM3DownloadsSort
    )
    DesignSwitch(
        label = "Chapter sizes",
        description = "Show the downloaded size on finished " +
            "books in Downloads.",
        checked = state.downloadsChapterSize,
        onChange = viewModel::setM3DownloadsChapterSize
    )
    // ── Explore (§2.11 + M1) ──────────────────────────────
    DesignSectionLabel("Explore")
    DesignChoice(
        label = "Explore opens on",
        options = listOf(
            M3ExploreSide.LAST_USED to "Last used",
            M3ExploreSide.NOVELS to "Novels",
            M3ExploreSide.MANGA to "Manga"
        ),
        selected = state.exploreSide,
        onPick = viewModel::setM3ExploreSide
    )
    DesignSwitch(
        label = "Group sources",
        description = "Built-in, Repos and Link-added sources " +
            "in their own groups on the Explore tab.",
        checked = state.exploreGrouping,
        onChange = viewModel::setM3ExploreGrouping
    )
    DesignChoice(
        label = "Source list density",
        options = listOf(
            M3DensityChoice.COMFORTABLE to "Comfortable",
            M3DensityChoice.COMPACT to "Compact"
        ),
        selected = state.exploreDensity,
        onPick = viewModel::setM3ExploreDensity
    )
    DesignChoice(
        label = "Source store view",
        options = listOf(
            M3ListView.LIST to "List",
            M3ListView.CARD_GRID to "Card grid"
        ),
        selected = state.exploreStoreView,
        onPick = viewModel::setM3ExploreStoreView
    )
    DesignChoice(
        label = "Repository sort",
        options = listOf(
            M3RepoSort.A_Z to "A–Z",
            M3RepoSort.RECENTLY_ADDED to "Recently added"
        ),
        selected = state.exploreRepoSort,
        onPick = viewModel::setM3ExploreRepoSort
    )
    // ── Profile (§2.11 + M1) ──────────────────────────────
    DesignSectionLabel("Profile")
    DesignSwitch(
        label = "Header card",
        description = "Your name, level and streak in a card " +
            "at the top of the Profile page. Off shows them " +
            "as a plain row.",
        checked = state.profileHeaderCard,
        onChange = viewModel::setM3ProfileHeaderCard
    )
    DesignChoice(
        label = "Profile doors",
        options = listOf(
            true to "List",
            false to "Grid"
        ),
        selected = state.profileDoorsList,
        onPick = viewModel::setM3ProfileDoorsList
    )
    DesignChoice(
        label = "Generated art — columns",
        options = listOf(
            M3GalleryColumns.AUTO to "Auto",
            M3GalleryColumns.TWO to "2",
            M3GalleryColumns.THREE to "3"
        ),
        selected = state.galleryColumns,
        onPick = viewModel::setM3GalleryColumns
    )
    // ── Reader (§2.11) ──────────────────────────────────────
    DesignSectionLabel("Reader")
    // W8: the Reader chrome choice is RETIRED (§8 Q3 — the
    // floating toolbar is the one M3 form; no knob is offered).
    DesignSwitch(
        label = "Selection colour row",
        description = "The colour row on the selection bar.",
        checked = state.selectionColourRow,
        onChange = viewModel::setM3SelectionColourRow
    )
    DesignChoice(
        label = "Contents opens on",
        options = listOf(
            M3ContentsTab.CHAPTERS to "Chapters",
            M3ContentsTab.BOOKMARKS to "Bookmarks",
            M3ContentsTab.HIGHLIGHTS to "Highlights"
        ),
        selected = state.contentsTab,
        onPick = viewModel::setM3ContentsTab
    )
    Text(
        "In Material 3 the reader's chrome and control " +
            "settings live here and in Customise → Reader.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    // ── Motion & advanced (§2.11) ───────────────────────────
    DesignSectionLabel("Motion & advanced")
    DesignChoice(
        label = "Motion",
        options = listOf(
            M3MotionChoice.EXPRESSIVE to "Expressive",
            M3MotionChoice.STANDARD to "Standard",
            M3MotionChoice.REDUCED to "Reduced"
        ),
        selected = state.motionChoice,
        onPick = viewModel::setM3MotionChoice
    )
    Text(
        "Standard and Reduced both use the Standard motion " +
            "scheme — no spring overshoot. Reduced is lighter " +
            "motion, for slower phones; you pick it yourself, " +
            "it is never switched on for you. Reduce motion " +
            "(Accessibility & motion) also selects the " +
            "Standard scheme.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    DesignSwitch(
        label = "Launch intro",
        description = "The short intro when the app opens.",
        checked = state.launchIntro,
        onChange = viewModel::setLaunchIntro
    )
}

/** The seed row of the Colour theme group: the current seed,
 * the saved seeds ("Your seeds"), and the door to the S37
 * wheel — the app's existing colour-wheel dialog frame, whose
 * pick becomes the seed. */
@Composable
private fun SeedRow(state: DesignState, viewModel: CustomiseViewModel) {
    val context = LocalContext.current
    val dark = m3DarkTheme(state)
    var showWheel by remember { mutableStateOf(false) }
    Text("Your colour", style = MaterialTheme.typography.bodyLarge)
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val seeds = buildList {
            state.seedArgb?.let { add(it) }
            addAll(state.savedSeeds)
        }.distinct()
        seeds.forEach { seed ->
            val isCurrent = seed == state.seedArgb &&
                state.colourSource == M3ColourSource.PICKED
            Box(
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Color(seed))
                    .border(
                        if (isCurrent) 2.dp else 1.dp,
                        if (isCurrent) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline,
                        CircleShape
                    )
                    .clickable {
                        viewModel.setM3ColourSource(M3ColourSource.PICKED)
                        viewModel.setM3Seed(seed)
                    }
            )
        }
        TextButton(onClick = { showWheel = true }) {
            Text("Pick any colour")
        }
    }
    if (showWheel) {
        val initial = state.seedArgb ?: (
            resolveM3ColorScheme(context, state, dark).primary
                .toArgb().toLong() and 0xFFFFFFFFL
            )
        ColourWheelPickerDialog(
            title = "Pick a colour",
            initialArgb = initial,
            onDismiss = { showWheel = false },
            onPick = { argb ->
                viewModel.setM3ColourSource(M3ColourSource.PICKED)
                viewModel.setM3Seed(argb)
                showWheel = false
            }
        )
    }
}

/** One design card on the Design page — the Colour theme
 * page's card idiom: name, one line, "In use" when active. */
@Composable
private fun DesignChoiceCard(
    name: String,
    line: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(
                if (selected) 2.dp else 1.dp,
                if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outline,
                RoundedCornerShape(16.dp)
            )
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleSmall)
            Text(
                line,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (selected) {
            Text(
                "In use",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun DesignSectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 8.dp)
    )
}

/** A labelled chip choice — the Customise pages' own idiom
 * (label + wrapping FilterChip row). */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun <T> DesignChoice(
    label: String,
    options: List<Pair<T, String>>,
    selected: T,
    onPick: (T) -> Unit
) {
    Text(label, style = MaterialTheme.typography.bodyLarge)
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { (value, text) ->
            FilterChip(
                selected = value == selected,
                onClick = { onPick(value) },
                label = { Text(text) }
            )
        }
    }
}

/** A switch row in the Customise idiom (label + description +
 * the kit switch). */
@Composable
private fun DesignSwitch(
    label: String,
    description: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        NocturneSwitch(
            checked = checked,
            onCheckedChange = onChange,
            modifier = Modifier,
            enabled = true
        )
    }
}

// ────────────────────────────────────────────────────────────────
// Code 122 (Settings V2 — owner's pick: Option B, the DOOR HUB):
// Customise under Material 3 is a dashboard of eleven door
// cards and NOTHING else — zero controls on the root. Each door
// carries a LIVE state summary read from the same prefs/state
// this page always read (live values in primary), and opens its
// group's own short focused page, composed in Option A's
// language: tonal group cards (surfaceContainerLow) of joined
// rows, spec SingleChoiceSegmentedButtonRows with filled picked
// states + check, spec Switches with check thumbs. Every R30
// group keeps its home and ALL its controls — the inventory is
// exactly the groups the one-page M3CustomisePage held in code
// 121 (Design · Library · Reader · Manga reader · Downloads ·
// Explore · Performance · Accessibility & motion · Storage ·
// Sources & repairs · Advanced). Nocturne's hub never calls
// this composable.
// ────────────────────────────────────────────────────────────────

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun M3CustomisePage(
    state: CustomiseViewModel.UiState,
    viewModel: CustomiseViewModel,
    onBack: () -> Unit
) {
    val design = state.design
    val appearance = state.appearance
    val library = state.library
    val reader = state.reader
    val storage by viewModel.storage.collectAsState()
    val voiceBytes by viewModel.voiceCacheBytes.collectAsState()
    val clearNote by viewModel.cacheClearNote.collectAsState()
    val repairCount by viewModel.repairCount.collectAsState()
    var confirmReset by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var confirmVoiceClear by remember { mutableStateOf(false) }
    // The open focused page's door id, or null for the hub.
    var openDoor by rememberSaveable { mutableStateOf<String?>(null) }
    BackHandler(enabled = openDoor != null) { openDoor = null }
    val doorTitles = mapOf(
        "design" to "Design",
        "library" to "Library",
        "reader" to "Reader",
        "manga" to "Manga reader",
        "downloads" to "Downloads",
        "explore" to "Explore",
        "performance" to "Performance",
        "accessibility" to "Accessibility & motion",
        "storage" to "Storage",
        "sources" to "Sources & repairs",
        "advanced" to "Advanced"
    )
    Column(Modifier.fillMaxWidth()) {
        TopAppBar(
            title = { Text(doorTitles[openDoor] ?: "Customise") },
            navigationIcon = {
                IconButton(onClick = {
                    if (openDoor != null) openDoor = null else onBack()
                }) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back"
                    )
                }
            }
        )
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            when (openDoor) {
                null -> {
                    // ── THE HUB: eleven doors, zero controls ──
                    Text(
                        "One door per group. Each door says how " +
                            "things stand right now — nothing is " +
                            "set from this page.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(
                            horizontal = 4.dp, vertical = 4.dp
                        )
                    )
                    M3DoorCard(
                        icon = Icons.Filled.Palette,
                        title = "Design",
                        summary = m3DoorSummary(
                            "Material 3" to true,
                            " · " to false,
                            when (design.colourSource) {
                                M3ColourSource.DYNAMIC -> "Dynamic colour"
                                M3ColourSource.PICKED -> "Picked colour"
                            } to true,
                            " · Motion: " to false,
                            when (design.motionChoice) {
                                M3MotionChoice.EXPRESSIVE -> "Expressive"
                                M3MotionChoice.STANDARD -> "Standard"
                                M3MotionChoice.REDUCED -> "Reduced"
                            } to true
                        ),
                        onClick = { openDoor = "design" }
                    )
                    M3DoorCard(
                        icon = Icons.Filled.LibraryBooks,
                        title = "Library",
                        summary = m3DoorSummary(
                            "Continue carousel " to false,
                            (if (design.libraryContinueRail) "on" else "off") to true,
                            " · Opens on " to false,
                            when (design.libraryDefaultTab) {
                                M3LibraryTab.ALL -> "All"
                                M3LibraryTab.READING -> "Reading"
                                M3LibraryTab.PLAN_TO_READ -> "Plan to read"
                                M3LibraryTab.COMPLETED -> "Completed"
                                M3LibraryTab.ON_HOLD -> "On hold"
                                M3LibraryTab.DROPPED -> "Dropped"
                            } to true
                        ),
                        onClick = { openDoor = "library" }
                    )
                    M3DoorCard(
                        icon = Icons.AutoMirrored.Filled.MenuBook,
                        title = "Reader",
                        summary = m3DoorSummary(
                            "Position line " to false,
                            (if (design.readerPositionLine) "on" else "off") to true,
                            " · Contents opens on " to false,
                            when (design.contentsTab) {
                                M3ContentsTab.CHAPTERS -> "Chapters"
                                M3ContentsTab.BOOKMARKS -> "Bookmarks"
                                M3ContentsTab.HIGHLIGHTS -> "Highlights"
                            } to true
                        ),
                        onClick = { openDoor = "reader" }
                    )
                    M3DoorCard(
                        icon = Icons.Filled.AutoStories,
                        title = "Manga reader",
                        summary = m3DoorSummary(
                            "CBZ opens in " to false,
                            (if (state.cbzOpensInKomikku) {
                                "Komikku reader"
                            } else {
                                "Book reader"
                            }) to true,
                            " · Image mode: " to false,
                            reader.imageMode.label to true
                        ),
                        onClick = { openDoor = "manga" }
                    )
                    M3DoorCard(
                        icon = Icons.Filled.Download,
                        title = "Downloads",
                        summary = m3DoorSummary(
                            "Finished list: " to false,
                            when (design.downloadsSort) {
                                M3DownloadsSort.RECENT -> "Recent"
                                M3DownloadsSort.TITLE -> "Title"
                                M3DownloadsSort.SIZE -> "Size"
                            } to true,
                            " · Auto-download " to false,
                            (if (library.autoDownloadTracked) "on" else "off") to true
                        ),
                        onClick = { openDoor = "downloads" }
                    )
                    M3DoorCard(
                        icon = Icons.Filled.Explore,
                        title = "Explore",
                        summary = m3DoorSummary(
                            "Opens on " to false,
                            when (design.exploreSide) {
                                M3ExploreSide.LAST_USED -> "Last used"
                                M3ExploreSide.NOVELS -> "Novels"
                                M3ExploreSide.MANGA -> "Manga"
                            } to true,
                            " · Group sources " to false,
                            (if (design.exploreGrouping) "on" else "off") to true
                        ),
                        onClick = { openDoor = "explore" }
                    )
                    M3DoorCard(
                        icon = Icons.Filled.Speed,
                        title = "Performance",
                        summary = m3DoorSummary(
                            "Tier: " to false,
                            when (appearance.performanceMode) {
                                app.xreader.data.prefs.PerformanceMode.AUTOMATIC -> "Automatic"
                                app.xreader.data.prefs.PerformanceMode.SMOOTH -> "Smooth"
                                app.xreader.data.prefs.PerformanceMode.BEST_LOOKING -> "Best looking"
                            } to true
                        ),
                        onClick = { openDoor = "performance" }
                    )
                    M3DoorCard(
                        icon = Icons.Filled.Accessibility,
                        title = "Accessibility & motion",
                        summary = m3DoorSummary(
                            "App size " to false,
                            AppearanceValidation.formatAppSize(appearance.appSize) to true,
                            " · Theme: " to false,
                            when (design.themeMode) {
                                M3ThemeMode.DARK -> "Dark"
                                M3ThemeMode.LIGHT -> "Light"
                                M3ThemeMode.FOLLOW_SYSTEM -> "System"
                            } to true
                        ),
                        onClick = { openDoor = "accessibility" }
                    )
                    val hubSizes = storage
                    M3DoorCard(
                        icon = Icons.Filled.Storage,
                        title = "Storage",
                        summary = if (hubSizes == null) {
                            m3DoorSummary("Working out sizes…" to false)
                        } else {
                            m3DoorSummary(
                                "Cache " to false,
                                app.xreader.data.files.StorageMeter
                                    .formatBytes(hubSizes.cacheBodyBytes) to true,
                                " · Listening cache " to false,
                                app.xreader.data.files.StorageMeter
                                    .formatBytes(voiceBytes ?: 0L) to true
                            )
                        },
                        onClick = { openDoor = "storage" }
                    )
                    M3DoorCard(
                        icon = Icons.Filled.Healing,
                        title = "Sources & repairs",
                        summary = if (repairCount > 0) {
                            m3DoorSummary(
                                (if (repairCount == 1) {
                                    "1 source needs repair"
                                } else {
                                    "$repairCount sources need repair"
                                }) to true,
                                " · Health of every source, in one place" to false
                            )
                        } else {
                            m3DoorSummary(
                                "No repairs waiting" to true,
                                " · Content shown: " to false,
                                state.contentVisibility.label to true
                            )
                        },
                        warn = repairCount > 0,
                        onClick = { openDoor = "sources" }
                    )
                    M3DoorCard(
                        icon = Icons.Filled.Science,
                        title = "Advanced",
                        summary = m3DoorSummary(
                            "Launch intro " to false,
                            (if (design.launchIntro) "on" else "off") to true,
                            " · Reset" to false
                        ),
                        onClick = { openDoor = "advanced" }
                    )
                    Spacer(Modifier.height(16.dp))
                }
                "design" -> {
                    // ── Design page (A-121 additions, exactly) ──
                    M3SettingsGroup("Design")
                    // Two selectable cards (radio behaviour) in
                    // the V2 language: picked = secondaryContainer
                    // fill + 2 dp primary border + filled radio
                    // with check + CURRENT pill; unpicked = tonal
                    // card, empty radio. The Nocturne card is TEXT
                    // ONLY — scheme colours, no violet drawn (T2).
                    M3DesignDoorCard(
                        name = "Material 3",
                        line = "Google's design language. Colours from " +
                            "your phone, or any colour you pick. This " +
                            "is the design you are looking at now.",
                        selected = design.design == AppDesign.MATERIAL3,
                        onClick = { viewModel.setDesign(AppDesign.MATERIAL3) }
                    )
                    M3DesignDoorCard(
                        name = "Nocturne",
                        line = "A dark glass design with a violet " +
                            "accent, built around your library's " +
                            "covers. Its own settings live there, " +
                            "not here.",
                        selected = design.design == AppDesign.NOCTURNE,
                        onClick = { viewModel.setDesign(AppDesign.NOCTURNE) }
                    )
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor =
                                MaterialTheme.colorScheme.surfaceContainerLow
                        ),
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp)
                    ) {
                        Column(Modifier.padding(18.dp)) {
                            Text(
                                "Colour",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            ColourBar(
                                selection = when (design.colourSource) {
                                    M3ColourSource.DYNAMIC -> ColourBarSelection.Dynamic
                                    M3ColourSource.PICKED -> design.seedArgb?.let {
                                        ColourBarSelection.Seed(it)
                                    }
                                },
                                onSelect = { picked ->
                                    when (picked) {
                                        ColourBarSelection.Dynamic ->
                                            viewModel.setM3ColourSource(
                                                M3ColourSource.DYNAMIC
                                            )
                                        is ColourBarSelection.Seed -> {
                                            viewModel.setM3ColourSource(
                                                M3ColourSource.PICKED
                                            )
                                            viewModel.setM3Seed(picked.argb)
                                        }
                                        ColourBarSelection.ThemeColour -> Unit
                                    }
                                },
                                recentColours = design.savedSeeds,
                                modifier = Modifier.fillMaxWidth()
                            )
                            HorizontalDivider(
                                Modifier.padding(vertical = 10.dp),
                                color = MaterialTheme.colorScheme.outlineVariant
                            )
                            // A-121-4: AMOLED dark — an option in
                            // the Design group, off by default; a
                            // colour treatment only.
                            M3SwitchRow(
                                label = "AMOLED dark",
                                description = "Pure black backgrounds in the dark theme, for AMOLED screens. Off by default.",
                                checked = design.m3AmoledDark,
                                onChange = viewModel::setM3AmoledDark
                            )
                        }
                    }
                    M3SegmentedRow(
                        label = "Motion",
                        options = listOf(
                            M3MotionChoice.EXPRESSIVE to "Expressive",
                            M3MotionChoice.STANDARD to "Standard",
                            M3MotionChoice.REDUCED to "Reduced"
                        ),
                        selected = design.motionChoice,
                        onPick = viewModel::setM3MotionChoice
                    )
                    Text(
                        "Reduced is lighter motion, for slower " +
                            "phones. It is your pick — never switched " +
                            "on automatically.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                    M3SegmentedRow(
                        label = "Navigation labels",
                        options = listOf(
                            M3TabLabels.ALWAYS to "Always",
                            M3TabLabels.SELECTED_ONLY to "Selected only"
                        ),
                        selected = design.tabLabels,
                        onPick = viewModel::setM3TabLabels
                    )
                    Spacer(Modifier.height(16.dp))
                }
                "library" -> {
                    M3SettingsGroup("Library")
                    M3GroupCard {
                        M3SwitchRow(
                            label = "Continue carousel",
                            description = "The Continue Reading carousel at the top of the Library.",
                            checked = design.libraryContinueRail,
                            onChange = viewModel::setM3LibraryContinueRail
                        )
                        M3SwitchRow(
                            label = "Progress on covers",
                            description = "The reading-progress line on book covers.",
                            checked = design.libraryProgressOnCovers,
                            onChange = viewModel::setM3LibraryProgressOnCovers
                        )
                        M3SwitchRow(
                            label = "Filter row",
                            description = "The status filter row in the Library.",
                            checked = design.libraryFilterRow,
                            onChange = viewModel::setM3LibraryFilterRow
                        )
                        M3SegmentedRow(
                            label = "Shelf density",
                            options = listOf(
                                M3DensityChoice.COMFORTABLE to "Comfortable",
                                M3DensityChoice.COMPACT to "Compact"
                            ),
                            selected = design.libraryShelfDensity,
                            onPick = viewModel::setM3LibraryShelfDensity
                        )
                        M3SegmentedRow(
                            label = "Opens on",
                            options = listOf(
                                M3LibraryTab.ALL to "All",
                                M3LibraryTab.READING to "Reading",
                                M3LibraryTab.PLAN_TO_READ to "Plan to read",
                                M3LibraryTab.COMPLETED to "Completed",
                                M3LibraryTab.ON_HOLD to "On hold",
                                M3LibraryTab.DROPPED to "Dropped"
                            ),
                            selected = design.libraryDefaultTab,
                            onPick = viewModel::setM3LibraryDefaultTab
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                }
                "reader" -> {
                    M3SettingsGroup("Reader")
                    M3GroupCard {
                        M3SwitchRow(
                            label = "Book-page cover colour",
                            description = "A book's page takes its colours from its cover while you are on it.",
                            checked = design.bookPageCoverColour,
                            onChange = viewModel::setM3BookPageCoverColour
                        )
                        M3SegmentedRow(
                            label = "Contents opens on",
                            options = listOf(
                                M3ContentsTab.CHAPTERS to "Chapters",
                                M3ContentsTab.BOOKMARKS to "Bookmarks",
                                M3ContentsTab.HIGHLIGHTS to "Highlights"
                            ),
                            selected = design.contentsTab,
                            onPick = viewModel::setM3ContentsTab
                        )
                        M3SwitchRow(
                            label = "Selection colour row",
                            description = "The colour row on the selection bar.",
                            checked = design.selectionColourRow,
                            onChange = viewModel::setM3SelectionColourRow
                        )
                        M3SwitchRow(
                            label = "Position line",
                            description = "The chapter, progress and time-left line under the reader's seek bar.",
                            checked = design.readerPositionLine,
                            onChange = viewModel::setM3ReaderPositionLine
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                }
                "manga" -> {
                    // ── Manga reader (S32's three-group language) ──
                    M3SettingsGroup("Manga reader")
                    Text(
                        "Reading mode",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                    M3GroupCard {
                        M3SegmentedRow(
                            label = "Image mode",
                            options = app.xreader.domain.reader.ImageReaderMode
                                .entries.map { it to it.label },
                            selected = reader.imageMode,
                            onPick = { mode ->
                                viewModel.updateReader { it.copy(imageMode = mode) }
                            }
                        )
                        M3SegmentedRow(
                            label = "CBZ opens in",
                            options = listOf(
                                true to "Komikku reader",
                                false to "Book reader"
                            ),
                            selected = state.cbzOpensInKomikku,
                            onPick = viewModel::setCbzOpensInKomikku
                        )
                        Text(
                            "For CBZ and CBR files on this device. If a comic " +
                                "can't open in the manga reader, it opens in " +
                                "the book reader instead.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )
                    }
                    Text(
                        "General",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                    M3GroupCard {
                        M3SwitchRow(
                            label = "Smaller tap zones",
                            description = "The turn areas shrink to the outer quarters of the screen.",
                            checked = reader.mangaSmallerTapZones,
                            onChange = { value ->
                                viewModel.updateReader {
                                    it.copy(mangaSmallerTapZones = value)
                                }
                            }
                        )
                        M3SwitchRow(
                            label = "Crop borders — paged",
                            description = "Trim the plain white/black margins off page-by-page pages.",
                            checked = reader.cropBordersPaged,
                            onChange = { value ->
                                viewModel.updateReader {
                                    it.copy(cropBordersPaged = value)
                                }
                            }
                        )
                        M3SwitchRow(
                            label = "Crop borders — webtoon",
                            description = "Trim the plain white/black margins off scrolling strips.",
                            checked = reader.cropBordersWebtoon,
                            onChange = { value ->
                                viewModel.updateReader {
                                    it.copy(cropBordersWebtoon = value)
                                }
                            }
                        )
                        M3SegmentedRow(
                            label = "Page chip",
                            options = listOf(
                                true to "Show",
                                false to "Hide"
                            ),
                            selected = design.mangaPageChip,
                            onPick = viewModel::setM3MangaPageChip
                        )
                    }
                    Text(
                        "Colour filter",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                    M3GroupCard {
                        M3SwitchRow(
                            label = "Greyscale pages",
                            description = "Read every manga page in black and white.",
                            checked = reader.mangaGrayscale,
                            onChange = { value ->
                                viewModel.updateReader {
                                    it.copy(mangaGrayscale = value)
                                }
                            }
                        )
                        M3SwitchRow(
                            label = "Invert page colours",
                            description = "Black becomes white and white becomes black.",
                            checked = reader.mangaInvertColors,
                            onChange = { value ->
                                viewModel.updateReader {
                                    it.copy(mangaInvertColors = value)
                                }
                            }
                        )
                        M3SwitchRow(
                            label = "Colour tint over pages",
                            description = "A soft colour wash over every page (sepia by default).",
                            checked = reader.mangaColorFilter,
                            onChange = { value ->
                                viewModel.updateReader {
                                    it.copy(mangaColorFilter = value)
                                }
                            }
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                }
                "downloads" -> {
                    M3SettingsGroup("Downloads")
                    M3GroupCard {
                        M3SegmentedRow(
                            label = "Finished list sort",
                            options = listOf(
                                M3DownloadsSort.RECENT to "Recent",
                                M3DownloadsSort.TITLE to "Title",
                                M3DownloadsSort.SIZE to "Size"
                            ),
                            selected = design.downloadsSort,
                            onPick = viewModel::setM3DownloadsSort
                        )
                        M3SwitchRow(
                            label = "Chapter-size line",
                            description = "Show the downloaded size on finished books in Downloads.",
                            checked = design.downloadsChapterSize,
                            onChange = viewModel::setM3DownloadsChapterSize
                        )
                        M3SwitchRow(
                            label = "Auto-download tracked books",
                            description = "New chapters of tracked books download by themselves.",
                            checked = library.autoDownloadTracked,
                            onChange = viewModel::setAutoDownloadTracked
                        )
                        M3SwitchRow(
                            label = "Wi-Fi only for prefetch",
                            description = "Chapters fetched ahead wait for Wi-Fi; a download you tap is never blocked.",
                            checked = state.downloads.wifiOnlyPrefetch,
                            onChange = viewModel::setWifiOnlyPrefetch
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                }
                "explore" -> {
                    M3SettingsGroup("Explore")
                    M3GroupCard {
                        M3SegmentedRow(
                            label = "Explore opens on",
                            options = listOf(
                                M3ExploreSide.LAST_USED to "Last used",
                                M3ExploreSide.NOVELS to "Novels",
                                M3ExploreSide.MANGA to "Manga"
                            ),
                            selected = design.exploreSide,
                            onPick = viewModel::setM3ExploreSide
                        )
                        M3SwitchRow(
                            label = "Group sources",
                            description = "Built-in, repository and link-added sources in their own groups.",
                            checked = design.exploreGrouping,
                            onChange = viewModel::setM3ExploreGrouping
                        )
                        M3SegmentedRow(
                            label = "Source list density",
                            options = listOf(
                                M3DensityChoice.COMFORTABLE to "Comfortable",
                                M3DensityChoice.COMPACT to "Compact"
                            ),
                            selected = design.exploreDensity,
                            onPick = viewModel::setM3ExploreDensity
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                }
                "performance" -> {
                    M3SettingsGroup("Performance")
                    M3GroupCard {
                        M3SegmentedRow(
                            label = "Performance tier",
                            options = listOf(
                                app.xreader.data.prefs.PerformanceMode.AUTOMATIC to "Automatic",
                                app.xreader.data.prefs.PerformanceMode.SMOOTH to "Smooth",
                                app.xreader.data.prefs.PerformanceMode.BEST_LOOKING to "Best looking"
                            ),
                            selected = appearance.performanceMode,
                            onPick = viewModel::setPerformanceMode
                        )
                        Text(
                            "Automatic lets the phone decide; Smooth takes the " +
                                "cheap rendering paths; Best looking enables " +
                                "every effect the phone can run.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                }
                "accessibility" -> {
                    M3SettingsGroup("Accessibility & motion")
                    M3GroupCard {
                        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                            Text(
                                "App size — ${AppearanceValidation.formatAppSize(appearance.appSize)}",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                "How big everything is on this phone — the whole " +
                                    "screen, not just the words. This is the only " +
                                    "size control in the app.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Slider(
                                value = appearance.appSize,
                                onValueChange = { viewModel.setAppSize(it) },
                                valueRange = AppearanceValidation.APP_SIZE_RANGE,
                                steps = 11
                            )
                        }
                        M3SwitchRow(
                            label = "Reduce motion",
                            description = "Calms the app's animations down to simple fades; also selects the Standard motion scheme.",
                            checked = appearance.reduceMotion,
                            onChange = viewModel::setReduceMotion
                        )
                        M3SegmentedRow(
                            label = "Theme mode",
                            options = listOf(
                                M3ThemeMode.DARK to "Dark",
                                M3ThemeMode.LIGHT to "Light",
                                M3ThemeMode.FOLLOW_SYSTEM to "System"
                            ),
                            selected = design.themeMode,
                            onPick = viewModel::setM3ThemeMode
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                }
                "storage" -> {
                    M3SettingsGroup("Storage")
                    val sizes = storage
                    if (sizes == null) {
                        Text(
                            "Working out sizes…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        M3GroupCard {
                            M3StorageRow(
                                "Downloaded chapters",
                                app.xreader.data.files.StorageMeter
                                    .formatBytes(sizes.downloadedTextBytes)
                            )
                            M3StorageRow(
                                "Reading cache",
                                app.xreader.data.files.StorageMeter
                                    .formatBytes(sizes.cacheBodyBytes)
                            )
                            M3StorageRow(
                                "Book files",
                                app.xreader.data.files.StorageMeter
                                    .formatBytes(sizes.bookFilesBytes)
                            )
                            M3StorageRow(
                                "Stored covers",
                                app.xreader.data.files.StorageMeter
                                    .formatBytes(sizes.coverCacheBytes)
                            )
                            M3StorageRow(
                                "Temporary files",
                                app.xreader.data.files.StorageMeter
                                    .formatBytes(sizes.tempBytes)
                            )
                            M3StorageRow(
                                "Listening cache",
                                app.xreader.data.files.StorageMeter
                                    .formatBytes(voiceBytes ?: 0L)
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { confirmClear = true }) {
                                Text("Clear caches")
                            }
                            TextButton(onClick = { confirmVoiceClear = true }) {
                                Text("Clear listening cache")
                            }
                        }
                        Text(
                            "Clearing caches removes the reading cache, the " +
                                "image cache and temporary files. The " +
                                "listening cache has its own Clear and is " +
                                "never touched by Clear caches. Downloads " +
                                "and your places stay.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (clearNote != null) {
                            Text(
                                clearNote!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }
                "sources" -> {
                    M3SettingsGroup("Sources & repairs")
                    M3GroupCard {
                        M3SegmentedRow(
                            label = "Content shown",
                            options = app.xreader.data.extension.ContentVisibility
                                .entries.map { it to it.label },
                            selected = state.contentVisibility,
                            onPick = viewModel::setContentVisibility
                        )
                        M3SegmentedRow(
                            label = "Best source wins by",
                            options = listOf(
                                app.xreader.data.extension.BestSourcePriority
                                    .DOWNLOADED_FIRST to "Downloaded first",
                                app.xreader.data.extension.BestSourcePriority
                                    .MOST_CHAPTERS to "Most chapters",
                                app.xreader.data.extension.BestSourcePriority
                                    .NEWEST_CHAPTER to "Newest chapter",
                                app.xreader.data.extension.BestSourcePriority
                                    .FASTEST to "Fastest"
                            ),
                            selected = state.bestSourcePriority,
                            onPick = viewModel::setBestSourcePriority
                        )
                        M3SwitchRow(
                            label = "Browser view for site checks",
                            description = "Sources that ask for a site check or a sign-in can open the one-time browser screen.",
                            checked = state.browserViewEnabled,
                            onChange = viewModel::setBrowserViewEnabled
                        )
                        M3SwitchRow(
                            label = "Manga extension player",
                            description = "Off means no manga extension loads at all.",
                            checked = state.keiyoushiPlayerEnabled,
                            onChange = viewModel::setKeiyoushiPlayerEnabled
                        )
                        M3SwitchRow(
                            label = "DNS-over-HTTPS",
                            description = "Source lookups go through Cloudflare (1.1.1.1) instead of the device's resolver.",
                            checked = state.dohEnabled,
                            onChange = viewModel::setDohEnabled
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                }
                "advanced" -> {
                    M3SettingsGroup("Advanced")
                    M3GroupCard {
                        M3SwitchRow(
                            label = "Launch intro",
                            description = "The short intro when the app opens.",
                            checked = design.launchIntro,
                            onChange = viewModel::setLaunchIntro
                        )
                        ListItem(
                            headlineContent = { Text("Reset customisation") },
                            supportingContent = {
                                Text(
                                    "Colours, layout and reader defaults go " +
                                        "back to how they started. Books, " +
                                        "sources and progress are not touched."
                                )
                            },
                            trailingContent = {
                                TextButton(onClick = { confirmReset = true }) {
                                    Text(
                                        "Reset",
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            },
                            colors = ListItemDefaults.colors(
                                containerColor = Color.Transparent
                            )
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset customisation?") },
            text = {
                Text(
                    "Only how Xreader looks and feels. Nothing you " +
                        "are reading, and nothing you organised, " +
                        "is removed."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    viewModel.resetCustomisation()
                }) { Text("Reset") }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) {
                    Text("Cancel")
                }
            }
        )
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear caches?") },
            text = {
                Text(
                    "Reading cache, image cache and temporary " +
                        "files will be removed and fetched again " +
                        "when needed. Stored covers, downloads and " +
                        "the listening cache stay."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    viewModel.clearCaches()
                }) { Text("Clear caches") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text("Cancel")
                }
            }
        )
    }
    if (confirmVoiceClear) {
        AlertDialog(
            onDismissRequest = { confirmVoiceClear = false },
            title = { Text("Clear listening cache?") },
            text = {
                Text(
                    "Sentences the online voice fetched ahead " +
                        "will be removed. Books, downloads and " +
                        "your places stay."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmVoiceClear = false
                    viewModel.clearVoiceCache()
                }) { Text("Clear listening cache") }
            },
            dismissButton = {
                TextButton(onClick = { confirmVoiceClear = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

/** Code 122: a door summary line — plain parts in the default
 * tone, live values (flagged true) in primary. Built from real
 * state only; a door never invents a value. */
@Composable
private fun m3DoorSummary(
    vararg parts: Pair<String, Boolean>
): androidx.compose.ui.text.AnnotatedString {
    val primary = MaterialTheme.colorScheme.primary
    return androidx.compose.ui.text.buildAnnotatedString {
        parts.forEach { (text, live) ->
            if (live) {
                withStyle(
                    androidx.compose.ui.text.SpanStyle(color = primary)
                ) { append(text) }
            } else {
                append(text)
            }
        }
    }
}

/** Code 122: one door card on the hub — filled tonal card,
 * tonal icon tile, group name, live state summary, chevron.
 * The Sources door's tile takes the tertiary container when
 * something needs repair ([warn]). The card is the only tap
 * target; the hub holds no controls. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun M3DoorCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    summary: androidx.compose.ui.text.AnnotatedString,
    warn: Boolean = false,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
    ) {
        ListItem(
            headlineContent = {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium
                )
            },
            supportingContent = { Text(summary) },
            leadingContent = {
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            if (warn) {
                                MaterialTheme.colorScheme.tertiaryContainer
                            } else {
                                MaterialTheme.colorScheme.primaryContainer
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = if (warn) {
                            MaterialTheme.colorScheme.onTertiaryContainer
                        } else {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        },
                        modifier = Modifier.size(23.dp)
                    )
                }
            },
            trailingContent = {
                Icon(
                    Icons.Filled.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            colors = ListItemDefaults.colors(
                containerColor = Color.Transparent
            )
        )
    }
}

/** Code 122: one selectable design card on the Design page —
 * the V2 filled picked state: secondaryContainer fill + 2 dp
 * primary border + filled radio with check + CURRENT pill;
 * unpicked is a tonal card with an empty radio. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun M3DesignDoorCard(
    name: String,
    line: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            }
        ),
        border = if (selected) {
            androidx.compose.foundation.BorderStroke(
                2.dp, MaterialTheme.colorScheme.primary
            )
        } else {
            androidx.compose.foundation.BorderStroke(
                1.dp, MaterialTheme.colorScheme.outlineVariant
            )
        },
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
    ) {
        Row(
            Modifier.padding(18.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .then(
                        if (selected) {
                            Modifier.background(
                                MaterialTheme.colorScheme.primary
                            )
                        } else {
                            Modifier.border(
                                2.dp,
                                MaterialTheme.colorScheme.outline,
                                CircleShape
                            )
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (selected) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    name,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    line,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (selected) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            if (selected) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(MaterialTheme.colorScheme.primary)
                        .padding(horizontal = 9.dp, vertical = 3.dp)
                ) {
                    Text(
                        "CURRENT",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
        }
    }
}

/** Code 122: one focused page's group as a filled tonal card —
 * rows inside ride transparent on the card's rung. */
@Composable
private fun M3GroupCard(content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
    ) {
        Column(Modifier.padding(8.dp)) {
            content()
        }
    }
}

@Composable
private fun M3SettingsGroup(title: String) {
    Column {
        Spacer(Modifier.height(16.dp))
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 8.dp)
        )
        HorizontalDivider(
            Modifier.padding(vertical = 8.dp),
            color = MaterialTheme.colorScheme.outlineVariant
        )
    }
}

@Composable
private fun M3SwitchRow(
    label: String,
    description: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = { Text(description) },
        trailingContent = {
            // Code 122 (Settings V2): the spec Switch — its
            // thumb carries the check when on.
            Switch(
                checked = checked,
                onCheckedChange = onChange,
                thumbContent = if (checked) {
                    {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = null,
                            modifier = Modifier.size(
                                SwitchDefaults.IconSize
                            )
                        )
                    }
                } else {
                    null
                }
            )
        },
        colors = ListItemDefaults.colors(
            containerColor = Color.Transparent
        ),
        modifier = Modifier.clickable { onChange(!checked) }
    )
}

@Composable
private fun <T> M3SegmentedRow(
    label: String,
    options: List<Pair<T, String>>,
    selected: T,
    onPick: (T) -> Unit
) {
    Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            SingleChoiceSegmentedButtonRow {
                options.forEachIndexed { index, (value, text) ->
                    // Code 122 (Settings V2): the picked segment
                    // SHOUTS politely — secondaryContainer fill,
                    // full-contrast label, check glyph; unpicked
                    // labels stay full onSurface.
                    SegmentedButton(
                        selected = value == selected,
                        onClick = { onPick(value) },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = options.size
                        ),
                        colors = SegmentedButtonDefaults.colors(
                            activeContainerColor =
                                MaterialTheme.colorScheme.secondaryContainer,
                            activeContentColor =
                                MaterialTheme.colorScheme.onSecondaryContainer,
                            activeBorderColor =
                                MaterialTheme.colorScheme.outline
                        ),
                        icon = {
                            SegmentedButtonDefaults.Icon(
                                active = value == selected
                            )
                        },
                        label = { Text(text) }
                    )
                }
            }
        }
    }
}

@Composable
private fun M3StorageRow(label: String, value: String) {
    ListItem(
        headlineContent = { Text(label) },
        trailingContent = {
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
        },
        colors = ListItemDefaults.colors(
            containerColor = Color.Transparent
        )
    )
}
