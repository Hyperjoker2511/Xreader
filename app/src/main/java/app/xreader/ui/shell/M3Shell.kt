package app.xreader.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.WideNavigationRail
import androidx.compose.material3.WideNavigationRailItem
import androidx.compose.material3.WideNavigationRailValue
import androidx.compose.material3.rememberWideNavigationRailState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import app.xreader.data.db.Book
import app.xreader.data.prefs.AppearanceState
import app.xreader.data.prefs.DesignState
import app.xreader.data.prefs.M3NavForm
import app.xreader.data.prefs.M3TabLabels
import app.xreader.di.AppContainer
import app.xreader.ui.downloads.DownloadsViewModel
import app.xreader.ui.library.LibraryViewModel
import app.xreader.ui.opds.OpdsViewModel
import app.xreader.ui.sources.LinkAddViewModel
import app.xreader.ui.sources.SourcesViewModel
import app.xreader.ui.theme.LocalDockClearance

/**
 * Code 120, breakpoint-corrected in code 121 (scratch design
 * §3.1 S1 + §2.6): the Material 3 design's shell. Where
 * Nocturne's shell overlays a floating dock over a
 * mesh backdrop, this shell is the spec's own arrangement:
 *
 *  - The window background is the scheme's `surface`; there is
 *    no backdrop layer and no grain (those are Nocturne's).
 *  - Phone: a Column — content, then the FLEXIBLE navigation
 *    bar (the official current form; the baseline bar is
 *    deprecated) docked at the bottom edge. Content never runs
 *    under the bar, so [LocalDockClearance] is zero here.
 *  - The snackbar — the design's one transient — rests directly
 *    above the bar.
 *  - Width classes (scratch §2.6): Compact <600dp the bar;
 *    Medium 600–839dp the navigation rail; Expanded 840dp+ the
 *    wide rail. The Bar/Rail knob can pin bar or rail form;
 *    Auto follows the classes (code 121 corrected the split:
 *    code 120 only railed at 840dp and wide-railed at 1200dp).
 *  - Immersive routes (the reader owns its screen) hide the
 *    bar/rail exactly as the Nocturne shell hides its dock —
 *    the same shellImmersive/dockShown gates decide.
 *
 * Tab order, routes, labels and navigation behaviour are the
 * shared [Tab] system's — pinned by ShellNavTest, unchanged.
 */
@Composable
internal fun M3PhoneShell(
    navController: NavHostController,
    snackbar: SnackbarHostState,
    appearance: AppearanceState,
    container: AppContainer,
    onPhaseNote: (String) -> Unit,
    libraryViewModel: LibraryViewModel,
    downloadsViewModel: DownloadsViewModel,
    opdsViewModel: OpdsViewModel,
    sourcesViewModel: SourcesViewModel,
    linkAddViewModel: LinkAddViewModel,
    onAddBook: () -> Unit,
    onExport: (Book, String) -> Unit,
    designState: DesignState,
    appUpdateViewModel: app.xreader.ui.update.AppUpdateViewModel,
    packViewModel: app.xreader.ui.sources.ProviderPackViewModel
) {
    val dockVisible = !shellImmersive(navController) && dockShown(navController)
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        Box(Modifier.weight(1f)) {
            M3NavHostContent(
                navController, container, appearance, onPhaseNote,
                libraryViewModel, downloadsViewModel, opdsViewModel,
                sourcesViewModel, linkAddViewModel, onAddBook, onExport,
                appUpdateViewModel, packViewModel
            )
            SnackbarHost(
                snackbar,
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 8.dp)
            )
        }
        if (dockVisible) {
            M3TabBar(
                navController = navController,
                sourcesViewModel = sourcesViewModel,
                appUpdateViewModel = appUpdateViewModel,
                labelsAlways = designState.tabLabels == M3TabLabels.ALWAYS
            )
        }
    }
}

@Composable
internal fun M3RailShell(
    navController: NavHostController,
    snackbar: SnackbarHostState,
    appearance: AppearanceState,
    container: AppContainer,
    onPhaseNote: (String) -> Unit,
    libraryViewModel: LibraryViewModel,
    downloadsViewModel: DownloadsViewModel,
    opdsViewModel: OpdsViewModel,
    sourcesViewModel: SourcesViewModel,
    linkAddViewModel: LinkAddViewModel,
    onAddBook: () -> Unit,
    onExport: (Book, String) -> Unit,
    designState: DesignState,
    appUpdateViewModel: app.xreader.ui.update.AppUpdateViewModel,
    packViewModel: app.xreader.ui.sources.ProviderPackViewModel
) {
    val dockVisible = !shellImmersive(navController) && dockShown(navController)
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        // §2.6: the wide rail starts at Expanded (840dp), not at
        // the Large class — code 120 waited until 1200dp.
        val wideRail = maxWidth >= 840.dp
        Row(Modifier.fillMaxSize()) {
            if (dockVisible) {
                M3TabRail(
                    navController = navController,
                    sourcesViewModel = sourcesViewModel,
                    appUpdateViewModel = appUpdateViewModel,
                    labelsAlways = designState.tabLabels == M3TabLabels.ALWAYS,
                    wideRail = wideRail
                )
            }
            Box(Modifier.weight(1f)) {
                M3NavHostContent(
                    navController, container, appearance, onPhaseNote,
                    libraryViewModel, downloadsViewModel, opdsViewModel,
                    sourcesViewModel, linkAddViewModel, onAddBook, onExport,
                    appUpdateViewModel, packViewModel
                )
                SnackbarHost(
                    snackbar,
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 8.dp)
                )
            }
        }
    }
}

/** The NavHost as both M3 shells host it: full-size, the same
 * status-bar inset the Nocturne Scaffold applied, and zero dock
 * clearance (nothing overlays the content in this design). */
@Composable
private fun M3NavHostContent(
    navController: NavHostController,
    container: AppContainer,
    appearance: AppearanceState,
    onPhaseNote: (String) -> Unit,
    libraryViewModel: LibraryViewModel,
    downloadsViewModel: DownloadsViewModel,
    opdsViewModel: OpdsViewModel,
    sourcesViewModel: SourcesViewModel,
    linkAddViewModel: LinkAddViewModel,
    onAddBook: () -> Unit,
    onExport: (Book, String) -> Unit,
    appUpdateViewModel: app.xreader.ui.update.AppUpdateViewModel,
    packViewModel: app.xreader.ui.sources.ProviderPackViewModel
) {
    CompositionLocalProvider(LocalDockClearance provides 0.dp) {
        XreaderNavHost(
            navController, container,
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars),
            appearance.reduceMotion, onPhaseNote,
            libraryViewModel, downloadsViewModel, opdsViewModel,
            sourcesViewModel, linkAddViewModel, onAddBook, onExport,
            appUpdateViewModel = appUpdateViewModel,
            packViewModel = packViewModel
        )
    }
}

/** The tab badges, shared by bar and rail: Explore carries the
 * pending source-update count, Profile the update dot — the
 * same signals the Nocturne dock badges. */
@Composable
private fun M3TabIcon(
    tab: Tab,
    sourcesViewModel: SourcesViewModel,
    appUpdateViewModel: app.xreader.ui.update.AppUpdateViewModel
) {
    val sourcesState by sourcesViewModel.state.collectAsStateWithLifecycle()
    val updateState by appUpdateViewModel.state.collectAsStateWithLifecycle()
    val count = if (tab == Tab.Browse) {
        sourcesState.keiyoushiPendingUpdateCount
    } else {
        0
    }
    val dot = tab == Tab.Me && updateState.release != null
    BadgedBox(
        badge = {
            when {
                count > 0 -> Badge { Text(count.toString()) }
                dot -> Badge()
            }
        }
    ) {
        Icon(tab.icon, tab.label)
    }
}

/** The flexible navigation bar (A1): equal-weight items, the
 * component's own colours (indicator = secondaryContainer,
 * active label = secondary role — the library's mapping, not a
 * house one). The labels knob passes a label only where it
 * should show: always, or on the selected item only. */
@Composable
private fun M3TabBar(
    navController: NavHostController,
    sourcesViewModel: SourcesViewModel,
    appUpdateViewModel: app.xreader.ui.update.AppUpdateViewModel,
    labelsAlways: Boolean
) {
    val selected = selectedTab(navController)
    ShortNavigationBar {
        Tab.entries.forEach { tab ->
            val isSelected = tab == selected
            ShortNavigationBarItem(
                selected = isSelected,
                onClick = { navigateToTab(navController, tab) },
                icon = {
                    M3TabIcon(tab, sourcesViewModel, appUpdateViewModel)
                },
                label = if (labelsAlways || isSelected) {
                    { Text(tab.label) }
                } else {
                    null
                }
            )
        }
    }
}

/** The rail form (§2.6): the stock navigation rail at Medium
 * (600–839dp), the wide rail at Expanded (840dp) and above —
 * the caller measures the window and passes [wideRail]. Items
 * follow the same labels knob in both forms: with Selected
 * only, an unselected wide-rail item passes no label, exactly
 * as the bar does. */
@Composable
private fun M3TabRail(
    navController: NavHostController,
    sourcesViewModel: SourcesViewModel,
    appUpdateViewModel: app.xreader.ui.update.AppUpdateViewModel,
    labelsAlways: Boolean,
    wideRail: Boolean
) {
    val selected = selectedTab(navController)
    if (wideRail) {
        val railState = rememberWideNavigationRailState(
            initialValue = WideNavigationRailValue.Expanded
        )
        WideNavigationRail(state = railState) {
            Tab.entries.forEach { tab ->
                val isSelected = tab == selected
                WideNavigationRailItem(
                    selected = isSelected,
                    onClick = { navigateToTab(navController, tab) },
                    icon = {
                        M3TabIcon(tab, sourcesViewModel, appUpdateViewModel)
                    },
                    label = if (labelsAlways || isSelected) {
                        { Text(tab.label) }
                    } else {
                        null
                    },
                    railExpanded = railState.targetValue ==
                        WideNavigationRailValue.Expanded
                )
            }
        }
    } else {
        NavigationRail {
            Tab.entries.forEach { tab ->
                NavigationRailItem(
                    selected = tab == selected,
                    onClick = { navigateToTab(navController, tab) },
                    icon = {
                        M3TabIcon(tab, sourcesViewModel, appUpdateViewModel)
                    },
                    label = { Text(tab.label) },
                    alwaysShowLabel = labelsAlways
                )
            }
        }
    }
}

/** The shell form decision (§2.11 Navigation): the knob pins a
 * form, or Auto follows the width split the caller already made. */
internal fun m3UseRail(designState: DesignState, wideLayout: Boolean): Boolean =
    when (designState.navForm) {
        M3NavForm.RAIL -> true
        M3NavForm.BAR -> false
        M3NavForm.AUTO -> wideLayout
    }
