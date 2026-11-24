package app.xreader.ui.sources

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
// Code 121 (W4): the scratch-M3 catalogue branch's components.
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import app.xreader.data.prefs.M3CatalogueColumns
import app.xreader.data.prefs.M3CatalogueMetadata
import app.xreader.ui.theme.LocalUiMetrics
import app.xreader.ui.theme.bottomContentPadding
import app.xreader.core.motion.listPlacementSpec
import app.xreader.ui.common.GhostPill
import app.xreader.ui.common.KitEmptyState
import app.xreader.ui.common.LoadingState
import app.xreader.ui.common.NocturneChip
import app.xreader.ui.common.PrimaryPill
import app.xreader.ui.common.SearchCapsule

/**
 * A source's catalogue (PLAN §5): Popular / Latest / Search with
 * paging. Covers load straight from the source's site; tapping a book
 * adds it to the library (details + chapter list) and opens it.
 */
@Composable
fun SourceCatalogScreen(
    viewModel: SourceCatalogViewModel,
    extensionId: Long,
    onBack: () -> Unit,
    onOpenBook: (Long) -> Unit,
    /** v1.3.2 (scope A2): the session hand-off door for a
     * failure that is a site check or a sign-in gate. */
    onSessionHandoff: (
        app.xreader.data.extension.SessionNeed.Kind
    ) -> Unit = {},
    /** Keiyoushi player (K5): open this source's own settings
     * (its extension preferences). Only wired for player
     * sources — the door itself renders only for them. */
    onOpenSourceSettings: () -> Unit = {}
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // v1.0.6.2 (F8a): search opens from an icon, like the Library —
    // the field itself only takes room while searching.
    var searchOpen by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(false)
    }
    // v1.3.1 (item 45): the long-press Add confirmation target.
    var pendingAdd by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<
            app.xreader.extensionapi.SourceBook?
        >(null)
    }

    LaunchedEffect(viewModel) {
        viewModel.openedBook.collect { bookId -> onOpenBook(bookId) }
    }

    // ── Code 121 (W4, scratch R20): the Material 3 catalogue ──
    // A medium flexible top app bar (source name; in-source
    // search in the bar), a segmented Popular/Latest row with
    // the Filters button at its trailing end (no FAB), and a
    // 2-column grid of filled cards whose metadata lives INSIDE
    // the card. Under Nocturne nothing below runs.
    if (isM3Design()) {
        M3SourceCatalogue(
            state = state,
            viewModel = viewModel,
            onBack = onBack,
            onSessionHandoff = onSessionHandoff,
            onOpenSourceSettings = onOpenSourceSettings,
            searchOpen = searchOpen,
            onSearchOpenChange = { searchOpen = it },
            pendingAdd = pendingAdd,
            onPendingAddChange = { pendingAdd = it }
        )
        return
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(start = 24.dp, end = 24.dp, top = LocalUiMetrics.current.topGap, bottom = 16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Column(Modifier.weight(1f)) {
                Text(
                    state.sourceName.ifBlank { "Source" },
                    style = MaterialTheme.typography.headlineSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                // Manga programme (M1): the source's content
                // rating, where it has one — Safe sources stay
                // unbadged, a visible Mixed/18+ source says so.
                state.ratingBadge?.let { badge ->
                    Text(
                        badge,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                // v1.2.0 (WS4 declutter): the raw base URL under
                // the name is gone — the name identifies the
                // source here; the URL lives on its Details page.
                // The header's search ICON is also gone: the
                // labelled "Search" chip in the mode row below
                // does the same job in plain words (the credo —
                // labelled beats icon-only), so search keeps
                // exactly one visible door.
            }
                // v1.3.0 (device spot-check fix): the header's
                // stray "Log in" link is gone — sign-in lives on
                // the source's Details page, where it is
                // session-aware; a bare auth link in the
                // catalogue header read as site furniture
                // leaking into the app's own chrome.
            }
        Spacer(Modifier.height(8.dp))
        // §5.4 SegmentedPills: a Row of NocturneChips, the active
        // mode selected — replaces the private ModeChip copy.
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CatalogMode.entries.forEach { mode ->
                NocturneChip(
                    label = when (mode) {
                        CatalogMode.POPULAR -> "Popular"
                        CatalogMode.LATEST -> "Latest"
                        CatalogMode.SEARCH -> "Search"
                    },
                    selected = state.mode == mode,
                    onClick = {
                        viewModel.setMode(mode)
                        if (mode == CatalogMode.SEARCH) searchOpen = true
                    }
                )
            }
        }
        // Keiyoushi player (K5): a player source's own doors —
        // Filters (when the source offers any) and its own
        // settings — labelled chips in the open, never buried.
        // Native kinds don't show this row: their settings live
        // on their own pages, exactly as before.
        if (state.isPlayerSource) {
            Spacer(Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (state.hasFilters) {
                    NocturneChip(
                        label = if (state.activeFilterCount > 0) {
                            "Filters · ${state.activeFilterCount}"
                        } else {
                            "Filters"
                        },
                        selected = state.activeFilterCount > 0,
                        onClick = { viewModel.openFilterSheet() }
                    )
                }
                NocturneChip(
                    label = "Source settings",
                    selected = false,
                    onClick = onOpenSourceSettings
                )
            }
        }
        if (state.mode == CatalogMode.SEARCH && searchOpen) {
            Spacer(Modifier.height(10.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SearchCapsule(
                    value = state.query,
                    onValueChange = { viewModel.setQuery(it) },
                    placeholder = "Search this source",
                    modifier = Modifier.weight(1f)
                )
                PrimaryPill(
                    text = "Search",
                    onClick = { viewModel.submitSearch() },
                    enabled = state.query.isNotBlank()
                )
            }
        }
        if (state.loading && state.books.isEmpty()) {
            // v1.5.8 (J3/J15): the state kit, not a bare bar — a
            // slow source (D1's budgets) shows what is happening
            // and that it is STILL loading, never a verdict while
            // its budget runs.
            Spacer(Modifier.height(10.dp))
            LoadingState(
                whatIsHappening = "Loading books from this source… " +
                    "some sources answer slowly — this is still loading, " +
                    "not a failure."
            )
        }
        if (state.loading && state.books.isNotEmpty()) {
            // v1.5.10 (H3): the progressive rule — stored rows
            // are already on screen; the pending refresh rides
            // ALONGSIDE them as a slim "Updating…" line, never
            // instead of them (catalogueSurfaceMode's
            // CONTENT_UPDATING arm).
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Updating…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(10.dp))
        // Hoisted before the null check (the #54 lesson): the
        // delegated state's nullable fields are read into locals
        // first, never force-dereferenced inside the branches.
        val errorMessage = state.errorMessage
        when {
            errorMessage != null && state.books.isEmpty() -> {
                // v1.3.2 (scope A2/A6): the failure card tells
                // the TRUE cause in plain words, and when the
                // cause is a check or a sign-in gate, the
                // hand-off is its primary door — never a
                // dead-end "check your connection".
                // v1.3.4 (U4): an availability (503/429) or DNS
                // failure leads with its honest words + Retry;
                // a sign-in/check action rides along ONLY under
                // CatalogueCopy's rules (a session-kind
                // classification, or DNS on a gated source as a
                // SECONDARY action) — never instead of the truth,
                // and raw codes never appear.
                val classification = state.fetchClassification
                val honestWords = CatalogueCopy.failureMessage(
                    classification
                )
                val need = state.sessionNeed
                val sessionOffered = CatalogueCopy
                    .offersSessionAction(classification, need)
                val primaryIsSession = !state.sourceOff &&
                    honestWords == null && need != null
                Column(Modifier.weight(1f)) {
                    KitEmptyState(
                        icon = Icons.Filled.CloudOff,
                        message = when {
                            state.sourceOff ->
                                "This source is switched off, so it " +
                                    "can't load its books."
                            honestWords != null -> honestWords
                            need ==
                                app.xreader.data.extension
                                    .SessionNeed.Kind.SITE_CHECK ->
                                "This site is showing a human check. " +
                                    "Solve it once and Xreader keeps " +
                                    "the session — after that, reading " +
                                    "works as normal."
                            need ==
                                app.xreader.data.extension
                                    .SessionNeed.Kind.SIGN_IN ->
                                "This site needs you to sign in before " +
                                    "it will share its books. Sign in " +
                                    "once on its own page; Xreader keeps " +
                                    "only the session, never your password."
                            else ->
                                "This source didn't answer. $errorMessage"
                        },
                        actionText = when {
                            state.sourceOff -> "Turn back on and try again"
                            primaryIsSession ->
                                CatalogueCopy.sessionActionLabel(need)
                            else -> "Try again"
                        },
                        onAction = {
                            when {
                                state.sourceOff ->
                                    viewModel.turnBackOnAndRetry()
                                primaryIsSession && need != null ->
                                    onSessionHandoff(need)
                                else -> viewModel.retry()
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (primaryIsSession) {
                        TextButton(
                            onClick = { viewModel.retry() },
                            modifier = Modifier.align(
                                androidx.compose.ui.Alignment.CenterHorizontally
                            )
                        ) { Text("Try again without it") }
                    } else if (sessionOffered && need != null) {
                        // The session action as a SECONDARY door
                        // beside the honest Retry (e.g. DNS on a
                        // gated source) — never instead of it.
                        TextButton(
                            onClick = { onSessionHandoff(need) },
                            modifier = Modifier.align(
                                androidx.compose.ui.Alignment.CenterHorizontally
                            )
                        ) {
                            Text(
                                CatalogueCopy.sessionActionLabel(need)
                            )
                        }
                    }
                }
            }
            !state.loading && state.books.isEmpty() -> {
                val emptyNeed = state.sessionNeed
                KitEmptyState(
                    icon = Icons.Filled.AutoStories,
                    // v1.3.4 (U4): on a gated source the empty
                    // state's copy states the reason as UNKNOWN
                    // (CatalogueCopy) — no claim a wall did it —
                    // and the solve/sign-in action is offered
                    // whenever the VM's sessionNeed is present.
                    message = CatalogueCopy.emptyMessage(
                        need = emptyNeed,
                        isSearch = state.mode == CatalogMode.SEARCH,
                        hasSession = state.hasSession
                    ),
                    actionText = when (emptyNeed) {
                        app.xreader.data.extension.SessionNeed
                            .Kind.SITE_CHECK -> "Solve the check"
                        app.xreader.data.extension.SessionNeed
                            .Kind.SIGN_IN -> "Sign in"
                        else -> null
                    },
                    onAction = if (emptyNeed != null) {
                        { onSessionHandoff(emptyNeed) }
                    } else {
                        null
                    },
                    modifier = Modifier.weight(1f)
                )
            }
            else -> {
                // v1.0.2 (R13/item 16): cover-first grammar — the
                // same visual language as Library: a grid of covers
                // with designed placeholders, not a text list.
                androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                    columns = androidx.compose.foundation.lazy.grid
                        .GridCells.Adaptive(minSize = 118.dp),
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    contentPadding = androidx.compose.foundation.layout
                        .PaddingValues(bottom = bottomContentPadding(LocalUiMetrics.current, dockVisible = false))
                ) {
                    items(
                        state.books, key = { "book-${it.id}" }
                    ) { book ->
                        // v1.0.6.2 (S1): the same shared poster
                        // card as the Library — one card everywhere.
                        app.xreader.ui.common.BookPosterCard(
                            title = book.title,
                            author = book.author?.takeIf { it.isNotBlank() },
                            coverModel = book.coverUrl
                                ?.takeIf { it.isNotBlank() },
                            metaLine = null,
                            busy = state.openingBookId == book.id,
                            onClick = { viewModel.openBook(book) },
                            // v1.3.1 (item 45): long-press is the
                            // catalogue's explicit Add to library
                            // (a plain tap only opens a preview).
                            onLongClick = { pendingAdd = book },
                            modifier = Modifier.animateItem(
                                placementSpec = listPlacementSpec()
                            )
                        )
                    }
                    if (state.hasNext) {
                        item(
                            span = {
                                androidx.compose.foundation.lazy.grid
                                    .GridItemSpan(maxLineSpan)
                            }
                        ) {
                            // §5.4: paging keeps its visible
                            // affordance — a GhostPill remainder row,
                            // not only invisible scroll.
                            GhostPill(
                                text = if (state.loading) "Loading…" else "Load more",
                                onClick = { viewModel.loadMore() },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                    if (!state.hasNext && state.page > 1 && state.books.isNotEmpty() &&
                        state.loadCompleted && !state.loading
                    ) {
                        // v1.0.6.2 (F2): the honest end of the
                        // catalogue — shown once paging actually
                        // walked past page 1 and a page added
                        // nothing new.
                        item(
                            span = {
                                androidx.compose.foundation.lazy.grid
                                    .GridItemSpan(maxLineSpan)
                            }
                        ) {
                            Text(
                                "That's everything here",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    // Keiyoushi player (K5): the filter sheet. Dismissing it
    // (scrim, back, or Cancel) restores the states it opened
    // with — only Apply changes the search.
    if (state.filterSheetOpen) {
        SourceFilterSheet(
            viewModel = viewModel,
            onDismiss = { viewModel.cancelFilterSheet() }
        )
    }

    // v1.3.1 (item 45): long-press on a cover asks plainly —
    // adding is explicit, never a side effect of browsing.
    pendingAdd?.let { book ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingAdd = null },
            title = { Text("Add to library") },
            text = {
                Text(
                    "Add “${book.title}” to your library? " +
                        "You can remove it any time from the Library."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingAdd = null
                        viewModel.addToLibrary(book)
                    }
                ) { Text("Add") }
            },
            dismissButton = {
                TextButton(onClick = { pendingAdd = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

/**
 * Code 121 (W4, scratch R20): the Material 3 source catalogue.
 * The card's metadata line carries the author — the one
 * per-book datum [app.xreader.extensionapi.SourceBook] holds
 * (there is no latest-chapter field in the catalogue model;
 * see deviation D-121-W4-2 in the build log). The "In library"
 * badge renders where the book's normalised title is in the
 * library key set the catalogue does not currently expose —
 * also D-121-W4-2.
 */
@OptIn(
    ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class
)
@Composable
private fun M3SourceCatalogue(
    state: CatalogUiState,
    viewModel: SourceCatalogViewModel,
    onBack: () -> Unit,
    onSessionHandoff: (app.xreader.data.extension.SessionNeed.Kind) -> Unit,
    onOpenSourceSettings: () -> Unit,
    searchOpen: Boolean,
    onSearchOpenChange: (Boolean) -> Unit,
    pendingAdd: app.xreader.extensionapi.SourceBook?,
    onPendingAddChange: (app.xreader.extensionapi.SourceBook?) -> Unit
) {
    val designState = app.xreader.ui.theme.LocalDesignState.current
    val columns = when (designState.catalogueColumns) {
        M3CatalogueColumns.TWO -> 2
        M3CatalogueColumns.THREE -> 3
    }
    val showMetadata =
        designState.catalogueMetadata == M3CatalogueMetadata.SHOW
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            Column {
                MediumFlexibleTopAppBar(
                    title = {
                        Text(
                            state.sourceName.ifBlank { "Source" },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    subtitle = state.ratingBadge?.let { badge ->
                        { Text(badge) }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back"
                            )
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = {
                                viewModel.setMode(CatalogMode.SEARCH)
                                onSearchOpenChange(!searchOpen)
                            }
                        ) {
                            Icon(
                                Icons.Filled.Search,
                                contentDescription = "Search this source"
                            )
                        }
                        if (state.isPlayerSource) {
                            IconButton(onClick = onOpenSourceSettings) {
                                Icon(
                                    Icons.Filled.Settings,
                                    contentDescription = "Source settings"
                                )
                            }
                        }
                    },
                    scrollBehavior = scrollBehavior
                )
                if (state.mode == CatalogMode.SEARCH && searchOpen) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = state.query,
                            onValueChange = { viewModel.setQuery(it) },
                            placeholder = { Text("Search this source") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = { viewModel.submitSearch() },
                            enabled = state.query.isNotBlank()
                        ) { Text("Search") }
                    }
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SingleChoiceSegmentedButtonRow(
                        Modifier.weight(1f)
                    ) {
                        SegmentedButton(
                            selected = state.mode == CatalogMode.POPULAR,
                            onClick = {
                                viewModel.setMode(CatalogMode.POPULAR)
                            },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = 0, count = 2
                            ),
                            modifier = Modifier.weight(1f)
                        ) { Text("Popular") }
                        SegmentedButton(
                            selected = state.mode == CatalogMode.LATEST,
                            onClick = {
                                viewModel.setMode(CatalogMode.LATEST)
                            },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = 1, count = 2
                            ),
                            modifier = Modifier.weight(1f)
                        ) { Text("Latest") }
                    }
                    if (state.hasFilters) {
                        FilledTonalButton(
                            onClick = { viewModel.openFilterSheet() }
                        ) {
                            Icon(
                                Icons.Filled.FilterList,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                if (state.activeFilterCount > 0) {
                                    "Filters · ${state.activeFilterCount}"
                                } else {
                                    "Filters"
                                },
                                modifier = Modifier.padding(start = 6.dp)
                            )
                        }
                    }
                }
            }
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            val errorMessage = state.errorMessage
            when {
                state.loading && state.books.isEmpty() -> {
                    LoadingState(
                        whatIsHappening = "Loading books from this " +
                            "source… some sources answer slowly — " +
                            "this is still loading, not a failure.",
                        modifier = Modifier.padding(top = 16.dp)
                    )
                }
                errorMessage != null && state.books.isEmpty() -> {
                    val classification = state.fetchClassification
                    val honestWords = CatalogueCopy.failureMessage(
                        classification
                    )
                    val need = state.sessionNeed
                    val sessionOffered = CatalogueCopy
                        .offersSessionAction(classification, need)
                    val primaryIsSession = !state.sourceOff &&
                        honestWords == null && need != null
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            when {
                                state.sourceOff ->
                                    "This source is switched off, so " +
                                        "it can't load its books."
                                honestWords != null -> honestWords
                                need == app.xreader.data.extension
                                    .SessionNeed.Kind.SITE_CHECK ->
                                    "This site is showing a human " +
                                        "check. Solve it once and " +
                                        "Xreader keeps the session — " +
                                        "after that, reading works " +
                                        "as normal."
                                need == app.xreader.data.extension
                                    .SessionNeed.Kind.SIGN_IN ->
                                    "This site needs you to sign in " +
                                        "before it will share its " +
                                        "books. Sign in once on its " +
                                        "own page; Xreader keeps only " +
                                        "the session, never your " +
                                        "password."
                                else ->
                                    "This source didn't answer. " +
                                        errorMessage
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(
                            onClick = {
                                when {
                                    state.sourceOff ->
                                        viewModel.turnBackOnAndRetry()
                                    primaryIsSession && need != null ->
                                        onSessionHandoff(need)
                                    else -> viewModel.retry()
                                }
                            }
                        ) {
                            Text(
                                when {
                                    state.sourceOff ->
                                        "Turn back on and try again"
                                    primaryIsSession ->
                                        CatalogueCopy.sessionActionLabel(need)
                                    else -> "Try again"
                                }
                            )
                        }
                        if (primaryIsSession) {
                            TextButton(
                                onClick = { viewModel.retry() }
                            ) { Text("Try again without it") }
                        } else if (sessionOffered && need != null) {
                            TextButton(
                                onClick = { onSessionHandoff(need) }
                            ) {
                                Text(
                                    CatalogueCopy.sessionActionLabel(need)
                                )
                            }
                        }
                    }
                }
                !state.loading && state.books.isEmpty() -> {
                    val emptyNeed = state.sessionNeed
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            CatalogueCopy.emptyMessage(
                                need = emptyNeed,
                                isSearch = state.mode == CatalogMode.SEARCH,
                                hasSession = state.hasSession
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        when (emptyNeed) {
                            app.xreader.data.extension.SessionNeed
                                .Kind.SITE_CHECK -> Button(
                                onClick = { onSessionHandoff(emptyNeed) }
                            ) { Text("Solve the check") }
                            app.xreader.data.extension.SessionNeed
                                .Kind.SIGN_IN -> Button(
                                onClick = { onSessionHandoff(emptyNeed) }
                            ) { Text("Sign in") }
                            else -> Unit
                        }
                    }
                }
                else -> {
                    if (state.loading) {
                        Text(
                            "Updating…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme
                                .onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 6.dp)
                        )
                    }
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(columns),
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = androidx.compose.foundation.layout
                            .PaddingValues(bottom = 24.dp, top = 8.dp)
                    ) {
                        items(
                            state.books, key = { "m3-book-${it.id}" }
                        ) { book ->
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme
                                        .colorScheme.surfaceContainerLow
                                ),
                                shape = MaterialTheme.shapes.medium,
                                modifier = Modifier
                                    .animateItem(
                                        placementSpec = listPlacementSpec()
                                    )
                                    .combinedClickable(
                                        onClick = {
                                            viewModel.openBook(book)
                                        },
                                        onLongClick = {
                                            onPendingAddChange(book)
                                        }
                                    )
                            ) {
                                if (book.coverUrl.isNullOrBlank()) {
                                    Box(
                                        Modifier
                                            .fillMaxWidth()
                                            .aspectRatio(2f / 3f)
                                            .background(
                                                MaterialTheme.colorScheme
                                                    .surfaceContainerHigh
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            book.title,
                                            style = MaterialTheme
                                                .typography.titleSmall,
                                            color = MaterialTheme
                                                .colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(12.dp),
                                            maxLines = 4,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                } else {
                                    AsyncImage(
                                        model = book.coverUrl,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .aspectRatio(2f / 3f)
                                            .background(
                                                MaterialTheme.colorScheme
                                                    .surfaceContainerHigh
                                            )
                                    )
                                }
                                if (showMetadata) {
                                    Column(Modifier.padding(12.dp)) {
                                        Text(
                                            book.title,
                                            style = MaterialTheme
                                                .typography.titleSmall,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        book.author
                                            ?.takeIf { it.isNotBlank() }
                                            ?.let { author ->
                                                Text(
                                                    author,
                                                    style = MaterialTheme
                                                        .typography.bodySmall,
                                                    color = MaterialTheme
                                                        .colorScheme
                                                        .onSurfaceVariant,
                                                    maxLines = 1,
                                                    overflow = TextOverflow
                                                        .Ellipsis
                                                )
                                            }
                                        if (state.openingBookId == book.id) {
                                            LinearWavyProgressIndicator(
                                                Modifier
                                                    .fillMaxWidth()
                                                    .padding(top = 8.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        if (state.hasNext) {
                            item(
                                span = { GridItemSpan(maxLineSpan) }
                            ) {
                                if (state.loading) {
                                    LinearWavyProgressIndicator(
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 12.dp)
                                    )
                                } else {
                                    FilledTonalButton(
                                        onClick = { viewModel.loadMore() },
                                        modifier = Modifier.fillMaxWidth()
                                    ) { Text("Load more") }
                                }
                            }
                        }
                        if (!state.hasNext && state.page > 1 &&
                            state.books.isNotEmpty() &&
                            state.loadCompleted && !state.loading
                        ) {
                            item(
                                span = { GridItemSpan(maxLineSpan) }
                            ) {
                                Text(
                                    "That's everything here",
                                    style = MaterialTheme
                                        .typography.bodySmall,
                                    color = MaterialTheme.colorScheme
                                        .onSurfaceVariant,
                                    textAlign = androidx.compose.ui.text
                                        .style.TextAlign.Center,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 8.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (state.filterSheetOpen) {
        SourceFilterSheet(
            viewModel = viewModel,
            onDismiss = { viewModel.cancelFilterSheet() }
        )
    }

    pendingAdd?.let { book ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { onPendingAddChange(null) },
            title = { Text("Add to library") },
            text = {
                Text(
                    "Add “${book.title}” to your library? " +
                        "You can remove it any time from the Library."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onPendingAddChange(null)
                        viewModel.addToLibrary(book)
                    }
                ) { Text("Add") }
            },
            dismissButton = {
                TextButton(
                    onClick = { onPendingAddChange(null) }
                ) { Text("Cancel") }
            }
        )
    }
}
