package app.xreader.ui.more

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import app.xreader.data.prefs.M3ListView
import app.xreader.data.prefs.M3MotionChoice
import app.xreader.data.prefs.M3NavForm
import app.xreader.data.prefs.M3ReaderChrome
import app.xreader.data.prefs.M3RepoSort
import app.xreader.data.prefs.M3TabLabels
import app.xreader.data.prefs.M3ThemeMode
import app.xreader.data.prefs.M3VocabSort
import app.xreader.ui.common.ColourWheelPickerDialog
import app.xreader.ui.common.NocturneSwitch
import app.xreader.ui.theme.m3DarkTheme
import app.xreader.ui.theme.resolveM3ColorScheme

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
    DesignChoice(
        label = "Reader chrome",
        options = listOf(
            M3ReaderChrome.FLOATING_TOOLBAR to "Floating toolbar",
            M3ReaderChrome.TOP_AND_BOTTOM_BARS to "Top-and-bottom bars"
        ),
        selected = state.readerChrome,
        onPick = viewModel::setM3ReaderChrome
    )
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
            M3MotionChoice.STANDARD to "Standard"
        ),
        selected = state.motionChoice,
        onPick = viewModel::setM3MotionChoice
    )
    Text(
        "Standard is the reduced-motion scheme; Reduce " +
            "motion (Accessibility & motion) also selects it.",
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
