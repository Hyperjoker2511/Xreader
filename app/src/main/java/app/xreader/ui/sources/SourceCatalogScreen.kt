package app.xreader.ui.sources

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import app.xreader.core.motion.listPlacementSpec
import app.xreader.ui.common.GhostPill
import app.xreader.ui.common.KitEmptyState
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
    ) -> Unit = {}
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

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 24.dp, vertical = 16.dp)
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
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 10.dp))
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
                        isSearch = state.mode == CatalogMode.SEARCH
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
                        .PaddingValues(bottom = 88.dp)
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
