package app.xreader.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import app.xreader.data.db.Book
import app.xreader.data.prefs.AppearanceState
import app.xreader.data.prefs.DesignState
import app.xreader.di.AppContainer
import app.xreader.ui.downloads.DownloadsViewModel
import app.xreader.ui.library.LibraryViewModel
import app.xreader.ui.opds.OpdsViewModel
import app.xreader.ui.sources.LinkAddViewModel
import app.xreader.ui.sources.SourcesViewModel
import app.xreader.ui.theme.LocalDockClearance

/**
 * Code 123 (S1, the Editorial shell): the floating pill nav
 * the Paper & Sage design draws on its Library and Explore
 * pages, carried into the app as the Editorial face's shell —
 * the owner's approved copies are the spec:
 *
 *  - A pill floating with margins on all sides (12dp sides,
 *    16dp foot), 68dp high, 28dp radius, surface-container-high
 *    ground, a small shadow. It OVERLAYS the content (the
 *    design's pages run beneath it), so the shell provides
 *    [LocalDockClearance] and scroll ends repay it.
 *  - The picked item expands into a primary pill carrying
 *    icon AND label; unpicked items are icon-only 48dp
 *    circles.
 *  - The Profile item carries the design's 8dp tertiary dot
 *    while Profile is not the active tab and a newer release
 *    is known (the app's existing Profile signal — the dot
 *    means "something waits on the Me side", as drawn).
 *  - The snackbar rests directly above the pill.
 *  - Immersive routes hide the pill behind the same
 *    shellImmersive/dockShown gates every shell obeys.
 *
 * At rail widths (600dp+) the Editorial face takes the M3
 * family's rail shell (M3Shell.kt) dressed by the Editorial
 * theme — the design draws no rail, and the family's rail is
 * its honest wide form.
 */

/** The clearance the floating pill needs at a scroll end:
 * 68dp pill + 16dp foot margin + breathing room. */
private val EditorialDockClearance = 100.dp

@Composable
internal fun EditorialPhoneShell(
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
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        CompositionLocalProvider(
            LocalDockClearance provides
                if (dockVisible) EditorialDockClearance else 0.dp
        ) {
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
        SnackbarHost(
            snackbar,
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = if (dockVisible) 92.dp else 8.dp)
        )
        if (dockVisible) {
            EditorialTabBar(
                navController = navController,
                appUpdateViewModel = appUpdateViewModel,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 16.dp)
            )
        }
    }
}

@Composable
private fun EditorialTabBar(
    navController: NavHostController,
    appUpdateViewModel: app.xreader.ui.update.AppUpdateViewModel,
    modifier: Modifier = Modifier
) {
    val selected = selectedTab(navController)
    val updateState by appUpdateViewModel.state.collectAsStateWithLifecycle()
    val updateAvailable = updateState.release != null
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(68.dp),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 6.dp
    ) {
        Row(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Tab.entries.forEach { tab ->
                val isSelected = tab == selected
                if (isSelected) {
                    Row(
                        Modifier
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                            .clickable { navigateToTab(navController, tab) }
                            .height(48.dp)
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            tab.icon, tab.label,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(22.dp)
                        )
                        Text(
                            tab.label,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                } else {
                    Box(
                        Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .clickable { navigateToTab(navController, tab) },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            tab.icon, tab.label,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp)
                        )
                        // The design's Profile dot: tertiary,
                        // 8dp, at the icon's top-right — drawn
                        // while a newer release is known and
                        // Profile is not the active tab.
                        if (tab == Tab.Me && updateAvailable) {
                            Box(
                                Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(top = 6.dp, end = 6.dp)
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(
                                        MaterialTheme.colorScheme.tertiary
                                    )
                            )
                        }
                    }
                }
            }
        }
    }
}
