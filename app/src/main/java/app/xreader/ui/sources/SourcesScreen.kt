package app.xreader.ui.sources

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryBooks
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.xreader.core.motion.listPlacementSpec
import app.xreader.core.motion.pressBounce
import app.xreader.data.db.InstalledExtension
import app.xreader.data.db.OpdsServer
import app.xreader.data.db.TrustState
import app.xreader.data.extension.ContentAccess
import app.xreader.data.extension.HealthDot
import app.xreader.data.extension.SourceHealthRecord
import app.xreader.data.extension.healthDot
import app.xreader.ui.common.GlassCard
import app.xreader.ui.common.KitEmptyState
import app.xreader.ui.common.NocturneChip
import app.xreader.ui.common.PrimaryPill
import app.xreader.ui.common.QuickActionTile
import app.xreader.ui.common.ScreenHeader
import app.xreader.ui.common.SectionHeader
import app.xreader.ui.theme.primaryTextColor

/**
 * Browse (v1.0.5, C3/D23 — the neat rebuild): header, the global
 * search pill, ONE "Add a source" button, then your sources as
 * compact glass rows — extensions and OPDS library servers in
 * the same list, each with its kind labelled. Adding lives on
 * its own screen ([AddSourceScreen]) and repositories in the
 * Source store ([SourceStoreScreen]): nothing expands inside
 * this scroll any more. Per-source Health and Repair stay on
 * each source's Details page, as before.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun SourcesScreen(
    viewModel: SourcesViewModel,
    /** v1.3.2 (scope D): the all-sources search lives HERE now,
     * Mihon-style — the field at the top of Explore searches
     * every source with results streaming in per source. */
    searchViewModel: GlobalSearchViewModel,
    onOpenSource: (extensionId: Long, sourceId: String) -> Unit,
    onOpenExtension: (extensionId: Long) -> Unit,
    onOpenOpdsServers: () -> Unit,
    /** v1.0.5 (C3): the Add-a-source screen and the Source store. */
    onAddSource: () -> Unit = {},
    onOpenStore: () -> Unit = {},
    /** v1.0.5 (C3/D23): OPDS servers fold into this list. */
    opdsServers: List<OpdsServer> = emptyList(),
    onOpenOpdsServer: (OpdsServer) -> Unit = {},
    onOpenBook: (Long) -> Unit = {},
    coverFile: (String?) -> java.io.File? = { null }
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val searchState by searchViewModel.state.collectAsStateWithLifecycle()
    // The host collects searchViewModel.openedBook — a result tap
    // opens the book through the same path as everywhere else.
    val searchActive = searchState.searched
    // Manga programme (M1): the content preference on the
    // Explore list — an extension is listed when at least one
    // of its sources is listed; a package whose every source
    // is hidden disappears entirely (Mihon's model). A source
    // with no recorded rating reads as Safe.
    val visibleExtensions = state.extensions.filter { ext ->
        val ids = ext.sourceIds.split(",")
            .map { it.trim() }.filter { it.isNotBlank() }
        ids.isEmpty() || ids.any { sourceId ->
            val rating = state.sourceRatings[
                app.xreader.data.extension.SourceHealthRecord
                    .key(ext.id, sourceId)
            ] ?: app.xreader.data.extension.ContentRating.SAFE
            app.xreader.data.extension.ContentAccess
                .allows(rating, state.contentVisibility)
        }
    }
    // The badge a listed row wears: the strongest rating still
    // visible across the extension's sources (null = no badge).
    fun ratingBadgeOf(ext: InstalledExtension): String? {
        val ratings = ext.sourceIds.split(",")
            .map { it.trim() }.filter { it.isNotBlank() }
            .map { sourceId ->
                state.sourceRatings[
                    app.xreader.data.extension.SourceHealthRecord
                        .key(ext.id, sourceId)
                ] ?: app.xreader.data.extension.ContentRating.SAFE
            }
        return app.xreader.data.extension.ContentAccess
            .visibleBadge(ratings, state.contentVisibility)
    }
    // Manga programme (M3): Explore mirrors the Library's
    // split — the same remembered switch, the same rule (an
    // extension stands on a side when at least one of its
    // sources belongs to it; kindless sources are Novels).
    fun kindsOf(ext: InstalledExtension):
        List<app.xreader.data.extension.MediaKind> =
        ext.sourceIds.split(",")
            .map { it.trim() }.filter { it.isNotBlank() }
            .mapNotNull { sourceId ->
                state.sourceKinds[
                    app.xreader.data.extension.SourceHealthRecord
                        .key(ext.id, sourceId)
                ]
            }
    val sideExtensions = visibleExtensions.filter { ext ->
        app.xreader.data.library.LibrarySplit.extensionOnSide(
            kindsOf(ext), state.librarySide
        )
    }
    val novelsSideCount = visibleExtensions.count { ext ->
        app.xreader.data.library.LibrarySplit.extensionOnSide(
            kindsOf(ext), app.xreader.data.extension.MediaKind.TEXT
        )
    } + opdsServers.size
    val mangaSideCount = visibleExtensions.count { ext ->
        app.xreader.data.library.LibrarySplit.extensionOnSide(
            kindsOf(ext), app.xreader.data.extension.MediaKind.MANGA
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        // v1.0.4 (D11): the header action bounces like the mock's.
        val checkUpdatesInteraction = remember { MutableInteractionSource() }
        // v1.3.3 (addition 7): the update check scopes to real
        // added repositories only — with none to check, the
        // button hides rather than checking the built-ins
        // (which update with the app) and reporting nonsense.
        val hasCheckableRepos =
            RepoCheckPolicy.checkable(state.repos).isNotEmpty()
        ScreenHeader(
            title = "Browse",
            eyebrow = "Sources",
            action = {
                if (hasCheckableRepos) {
                    TextButton(
                        onClick = { viewModel.refreshAll() },
                        enabled = !state.busy,
                        interactionSource = checkUpdatesInteraction,
                        modifier = Modifier.pressBounce(
                            checkUpdatesInteraction, enabled = !state.busy
                        )
                    ) { Text("Check for updates") }
                }
            }
        )
        if (state.busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
        }
        Spacer(Modifier.height(12.dp))
        // M3: the Novels | Manga switch — the Library's own
        // switch, mirrored: it filters the source list below
        // by kind, and each label carries how many sources
        // stand on that side.
        app.xreader.ui.common.MediaKindSwitch(
            side = state.librarySide,
            novelsLabel = app.xreader.data.library.LibrarySplit.switchLabel(
                app.xreader.data.extension.MediaKind.TEXT, novelsSideCount
            ),
            mangaLabel = app.xreader.data.library.LibrarySplit.switchLabel(
                app.xreader.data.extension.MediaKind.MANGA, mangaSideCount
            ),
            onSelect = { viewModel.setLibrarySide(it) }
        )
        Spacer(Modifier.height(12.dp))
        LazyColumn(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 88.dp)
        ) {
            // ── Search everything, right here (v1.3.2 scope D) ─
            // Mihon-style: the field sits at the top of Explore;
            // from here it searches every source at once and the
            // results stream in per source below it. (Inside one
            // source, that source's own screen searches only it.)
            item {
                androidx.compose.material3.OutlinedTextField(
                    value = searchState.query,
                    onValueChange = { searchViewModel.setQuery(it) },
                    label = { Text("Search all your sources") },
                    singleLine = true,
                    leadingIcon = {
                        Icon(Icons.Filled.Search, contentDescription = null)
                    },
                    trailingIcon = {
                        if (searchState.query.isNotBlank() ||
                            searchState.searched
                        ) {
                            TextButton(
                                onClick = { searchViewModel.clearSearch() }
                            ) { Text("Clear") }
                        }
                    },
                    keyboardOptions = androidx.compose.foundation.text
                        .KeyboardOptions(
                            imeAction = androidx.compose.ui.text.input
                                .ImeAction.Search
                        ),
                    keyboardActions = androidx.compose.foundation.text
                        .KeyboardActions(
                            onSearch = { searchViewModel.search() }
                        ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (searchState.searching) {
                item {
                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement =
                                Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                // P0 (F1): the line's rules live
                                // in SearchProgressLine — position
                                // never exceeds the total, and the
                                // total is the real source count
                                // from the first frame, never 0
                                // while sources are searching.
                                SearchProgressLine.text(
                                    done = searchState.progressDone,
                                    total = searchState.progressTotal,
                                    searchingLibrary =
                                        searchState.searchingLibrary
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme
                                    .onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(
                                onClick = { searchViewModel.cancelSearch() }
                            ) { Text("Cancel") }
                        }
                        LinearProgressIndicator(
                            progress = {
                                SearchProgressLine.fraction(
                                    searchState.progressDone,
                                    searchState.progressTotal
                                )
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
            if (!searchState.searched &&
                searchState.recents.isNotEmpty()
            ) {
                item {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Recent searches",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme
                                .onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        // P0 field fix: Clear wipes the whole
                        // list (the prefs function existed with
                        // no UI caller until now).
                        TextButton(
                            onClick = { searchViewModel.clearRecents() }
                        ) { Text("Clear") }
                    }
                }
                items(
                    searchState.recents.take(5),
                    key = { "recent-$it" }
                ) { recent ->
                    // P0 field fix: tap runs the search again;
                    // HOLD removes just this entry — with the
                    // hint line under the list saying so.
                    GlassCard(
                        modifier = Modifier.combinedClickable(
                            onClick = {
                                searchViewModel.setQuery(recent)
                                searchViewModel.search()
                            },
                            onLongClick = {
                                searchViewModel.removeRecent(recent)
                            }
                        )
                    ) {
                        Text(
                            recent,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
                item {
                    Text(
                        "Tap one to search again — hold one to " +
                            "remove it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme
                            .onSurfaceVariant
                    )
                }
            }

            if (searchActive) {
                // ── Streaming results, grouped Novels / Manga ──
                // M3: ONE search box, results under the split's
                // two headings (the content preference already
                // filtered hidden sources out of the fan-out).
                // Inside a side: your library first, then the
                // inside-downloads hits (Novels only — manga
                // chapters carry no prose to hit), then each
                // source's own group.
                val novelLibraryBooks = searchState.libraryBooks.filter {
                    searchState.libraryKinds[it.id] !=
                        app.xreader.data.extension.MediaKind.MANGA
                }
                val mangaLibraryBooks = searchState.libraryBooks.filter {
                    searchState.libraryKinds[it.id] ==
                        app.xreader.data.extension.MediaKind.MANGA
                }
                val novelGroups = searchState.groups.filter {
                    it.kind == app.xreader.data.extension.MediaKind.TEXT
                }
                val mangaGroups = searchState.groups.filter {
                    it.kind == app.xreader.data.extension.MediaKind.MANGA
                }
                if (novelLibraryBooks.isNotEmpty() ||
                    searchState.contentHits.isNotEmpty() ||
                    novelGroups.isNotEmpty()
                ) {
                    item(key = "gs-novels-head") {
                        SectionHeader(title = "Novels")
                    }
                    if (novelLibraryBooks.isNotEmpty()) {
                        searchSubLabel("gs-lib-label-n", "In your library")
                        searchLibraryRows(
                            books = novelLibraryBooks,
                            keyPrefix = "gs-librow-n",
                            coverFile = coverFile,
                            onOpen = { searchViewModel.openLibraryBook(it) }
                        )
                    }
                    if (searchState.contentHits.isNotEmpty()) {
                        searchSubLabel(
                            "gs-hits-label", "Inside your downloads"
                        )
                        items(
                            searchState.contentHits,
                            key = { "gs-hit-${it.bookId}-${it.chapterIndex}" }
                        ) { hit ->
                            GlassCard(
                                onClick = {
                                    searchViewModel.openLibraryBook(hit.bookId)
                                }
                            ) {
                                Text(
                                    hit.bookTitle,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Text(
                                    hit.chapterTitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme
                                        .onSurfaceVariant
                                )
                                Text(
                                    "…${hit.snippet}…",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                    for (group in novelGroups) {
                        searchGroupRows(
                            group = group,
                            libraryKeys = searchState.libraryKeys,
                            workCounts = searchState.workCounts,
                            onOpen = { g, book ->
                                searchViewModel.openBook(g, book)
                            }
                        )
                    }
                }
                if (mangaLibraryBooks.isNotEmpty() ||
                    mangaGroups.isNotEmpty()
                ) {
                    item(key = "gs-manga-head") {
                        SectionHeader(title = "Manga")
                    }
                    if (mangaLibraryBooks.isNotEmpty()) {
                        searchSubLabel("gs-lib-label-m", "In your library")
                        searchLibraryRows(
                            books = mangaLibraryBooks,
                            keyPrefix = "gs-librow-m",
                            coverFile = coverFile,
                            onOpen = { searchViewModel.openLibraryBook(it) }
                        )
                    }
                    for (group in mangaGroups) {
                        searchGroupRows(
                            group = group,
                            libraryKeys = searchState.libraryKeys,
                            workCounts = searchState.workCounts,
                            onOpen = { g, book ->
                                searchViewModel.openBook(g, book)
                            }
                        )
                    }
                }
                if (!searchState.searching &&
                    searchState.groups.isEmpty() &&
                    searchState.libraryBooks.isEmpty() &&
                    searchState.contentHits.isEmpty()
                ) {
                    item {
                        Text(
                            "Nothing found for “${searchState.query}”." +
                                if (searchState.failedSources.isNotEmpty()) {
                                    " These sources couldn't be searched: " +
                                        searchState.failedSources
                                            .joinToString(", ") + "."
                                } else {
                                    " Try a different word, or check " +
                                        "the spelling."
                                },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme
                                .onSurfaceVariant
                        )
                    }
                }
                if (searchState.failedSources.isNotEmpty() &&
                    searchState.groups.isNotEmpty()
                ) {
                    item {
                        Text(
                            "These sources couldn't be searched: " +
                                searchState.failedSources
                                    .joinToString(", ") + ".",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme
                                .onSurfaceVariant
                        )
                    }
                }
                item {
                    TextButton(
                        onClick = { searchViewModel.clearSearch() }
                    ) { Text("← Back to your sources") }
                }
            }

            if (!searchActive) {
            // ── v1.3.2 (item 84): the one-time restore card ────
            // v1.3.1's failure breaker could switch sources off
            // without the user and stored no reason, so an off
            // source can't be proven breaker-off. Nothing is
            // restored silently — this card lists the off
            // sources and only the reader's own tap changes
            // anything. It shows exactly once.
            val offExtensions = visibleExtensions.filter { !it.enabled }
            if (app.xreader.data.extension.SourceRestore.shouldShowCard(
                    cardDone = state.restoreCardDone,
                    rows = offExtensions.map {
                        app.xreader.data.extension.SourceRestore.Row(
                            it.id, it.displayName, it.enabled
                        )
                    }
                )
            ) {
                item(key = "restore-card") {
                    GlassCard(Modifier.fillMaxWidth()) {
                        Text(
                            "Some sources are switched off",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Older Xreader versions could switch a " +
                                "source off after failed loads — " +
                                "without asking you. These are off " +
                                "right now: " +
                                offExtensions.take(6).joinToString(", ") {
                                    it.displayName
                                } +
                                if (offExtensions.size > 6) {
                                    " and ${offExtensions.size - 6} more"
                                } else {
                                    ""
                                } +
                                ". Turn them back on, or leave them " +
                                "off — either way, this card won't " +
                                "come back.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme
                                .onSurfaceVariant
                        )
                        Spacer(Modifier.height(10.dp))
                        PrimaryPill(
                            text = "Turn them all back on",
                            onClick = {
                                viewModel.restoreAllOffSources(
                                    offExtensions
                                )
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        app.xreader.ui.common.GhostPill(
                            text = "Leave them off",
                            onClick = { viewModel.dismissRestoreCard() },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            // ── ONE add door; the forms live on their own screen ─
            // §5.3: a full-width GlassCard door with a PrimaryPill,
            // visible — never demoted to a plain row.
            item {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    // v1.2.0 (WS4 declutter): the card used to say
                    // "Add a source" twice — a title and the pill
                    // under it. The pill IS the action and keeps
                    // the words; the card now opens with the one
                    // explanatory line, then the big visible door.
                    Text(
                        "A repository, a novel site's link, or a Legado file.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    // v1.3.4 (U1/T5): Repositories is a FIRST-CLASS
                    // door at the TOP of the sources area — same
                    // visual weight as "Add a source", beside it,
                    // plain label, no menus. (The QuickActionTile
                    // at the bottom of the list stays too.)
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement =
                            Arrangement.spacedBy(8.dp)
                    ) {
                        PrimaryPill(
                            text = "Repositories",
                            onClick = onOpenStore,
                            modifier = Modifier.weight(1f)
                        )
                        PrimaryPill(
                            text = "Add a source",
                            onClick = onAddSource,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // ── Your sources, extensions and OPDS together ──────
            item {
                SectionHeader(
                    title = "Your sources",
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            if (sideExtensions.isEmpty() &&
                (state.librarySide ==
                    app.xreader.data.extension.MediaKind.MANGA ||
                    opdsServers.isEmpty())
            ) {
                item {
                    KitEmptyState(
                        icon = Icons.Filled.LibraryBooks,
                        message = when {
                            state.librarySide ==
                                app.xreader.data.extension.MediaKind.MANGA ->
                                "No manga sources yet. Manga comes " +
                                    "from the Keiyoushi catalogue — " +
                                    "open Repositories to see which " +
                                    "sites are converted."
                            state.extensions.isEmpty() ->
                                "No sources yet. Add a repository, paste a novel " +
                                    "site's link, or add a library server — your sources " +
                                    "live here."
                            else ->
                                "No novel sources on this side — " +
                                    "your sources are under Manga."
                        },
                        actionText = if (
                            state.librarySide ==
                                app.xreader.data.extension.MediaKind.MANGA
                        ) {
                            "Open Repositories"
                        } else {
                            "Add a source"
                        },
                        onAction = if (
                            state.librarySide ==
                                app.xreader.data.extension.MediaKind.MANGA
                        ) {
                            onOpenStore
                        } else {
                            onAddSource
                        }
                    )
                }
            }
            items(sideExtensions, key = { "ext-${it.id}" }) { ext ->
                val firstSource = ext.sourceIds.split(",")
                    .firstOrNull()?.trim()?.takeIf { it.isNotBlank() }
                val record = firstSource?.let {
                    state.health[SourceHealthRecord.key(ext.id, it)]
                }
                SourceRow(
                    ext = ext,
                    healthRecord = record,
                    ratingBadge = ratingBadgeOf(ext),
                    sessionWord = firstSource?.let {
                        state.sessionWords[
                            SourceHealthRecord.key(ext.id, it)
                        ]
                    },
                    hasUpdate = state.updates[ext.packageId] != null,
                    hasPending = state.pendings.containsKey(ext.packageId),
                    addedByYou = ext.id in state.addedByYouIds,
                    onOpen = {
                        if (firstSource == null || ext.sourceIds.contains(",")) {
                            onOpenExtension(ext.id)
                        } else {
                            onOpenSource(ext.id, firstSource)
                        }
                    },
                    onDetails = { onOpenExtension(ext.id) },
                    onUpdate = {
                        state.updates[ext.packageId]?.let { viewModel.applyUpdate(it) }
                    },
                    modifier = Modifier.animateItem(placementSpec = listPlacementSpec())
                )
            }
            // OPDS servers serve books: they stand on the
            // Novels side of the split only.
            if (state.librarySide ==
                app.xreader.data.extension.MediaKind.TEXT
            ) {
                items(opdsServers, key = { "opds-${it.id}" }) { server ->
                    OpdsServerRow(
                        server = server,
                        onOpen = { onOpenOpdsServer(server) },
                        modifier = Modifier.animateItem(placementSpec = listPlacementSpec())
                    )
                }
            }

            // ── The store and the server manager (§5.3 doors row) ─
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    QuickActionTile(
                        icon = Icons.Filled.Storefront,
                        // v1.3.3 (scope S7b): the door says what
                        // it opens, in the user's own words —
                        // "Repositories", one tap from here.
                        label = "Repositories",
                        modifier = Modifier.weight(1f),
                        active = false,
                        onClick = onOpenStore
                    )
                    QuickActionTile(
                        icon = Icons.Filled.LibraryBooks,
                        label = "OPDS servers",
                        modifier = Modifier.weight(1f),
                        active = false,
                        onClick = onOpenOpdsServers
                    )
                }
            }
            }
        }
    }
}

/**
 * v1.0.5 (C3/E2): one installed source as a compact glass row —
 * letter tile, name, language · version · kind, the health dot
 * from its last honest verdict, and its one alert word when
 * something needs the reader (re-trust, update, off, broken).
 * The whole row opens the source; a source that is off or not
 * trusted opens its Details instead, where the fix lives.
 */
@Composable
private fun SourceRow(
    ext: InstalledExtension,
    healthRecord: SourceHealthRecord?,
    /** Manga programme (M1): the content-rating badge for a
     * listed source ("18+" / "Mixed"), riding the meta line;
     * null for Safe sources. */
    ratingBadge: String? = null,
    /** v1.3.2 (R1): the session word — "Check needed" /
     * "Sign in needed" / "Signed in" / "Working" — when this
     * source rides the session road. */
    sessionWord: String? = null,
    hasUpdate: Boolean,
    hasPending: Boolean,
    addedByYou: Boolean = false,
    onOpen: () -> Unit,
    onDetails: () -> Unit,
    onUpdate: () -> Unit,
    modifier: Modifier = Modifier
) {
    val canOpen = ext.enabled && ext.trustState == TrustState.TRUSTED
    // v1.0.6.2 (SO1/F8a): action alerts only (re-trust, update,
    // off) — the health verdict itself now rides the meta line
    // as a plain word, the same words the Details health card
    // uses, so the row stays one quiet line.
    val alert: Pair<String, Color>? = when {
        ext.trustState == TrustState.CHANGED_REQUIRES_RETRUST ->
            "Needs re-trust" to MaterialTheme.colorScheme.error
        hasPending -> "Update waiting" to MaterialTheme.colorScheme.error
        !ext.enabled -> "Off" to MaterialTheme.colorScheme.onSurfaceVariant
        else -> null
    }
    val typeLabel = if (ext.packageId.startsWith("link.")) "Link source" else null
    // §5.3 row anatomy (WS4-demoted): language, the health word
    // and the kind share ONE plain meta line; the health dot sits
    // at the row's end and an alert, when one exists, is the
    // row's only chip.
    val languageLabel = ext.languages.split(",").firstOrNull()?.uppercase()
        ?.takeIf { it.isNotBlank() }
    val healthWord = healthRecord?.shortStateWord() ?: "Not checked yet"
    GlassCard(
        modifier = modifier,
        onClick = if (canOpen) onOpen else onDetails,
        bouncePress = true,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SourceLetterTile(letter = ext.displayName.firstOrNull()?.uppercase() ?: "?")
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(
                    ext.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                // v1.2.0 (WS4 declutter): the language used to
                // ride in its own chip beside the meta line —
                // a pill's worth of visual weight for a two-letter
                // code on every row. It is plain meta text now,
                // first in the same quiet line as the health word
                // and kind; the row's only chip is an alert when
                // one exists (below). Nothing is dropped, just
                // demoted to the weight it deserves.
                Text(
                    listOfNotNull(
                        // v1.3.0 (device spot-check fix): a
                        // user-added twin of a built-in default
                        // says so in plain words at the head of
                        // the meta line — the two same-name rows
                        // are never confusable, and nothing was
                        // deleted to achieve it.
                        if (addedByYou) "Added by you" else null,
                        languageLabel, healthWord,
                        // M1: the rating badge, same quiet
                        // line — a listed Mixed / 18+ source
                        // says so where it stands.
                        ratingBadge,
                        // v1.3.2 (R1): the session word rides
                        // the same quiet line when it adds
                        // information the health word doesn't
                        // carry (a bare "Working" would just
                        // repeat it).
                        sessionWord?.takeIf { it != healthWord },
                        typeLabel
                    )
                        .joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (alert != null) {
                    Spacer(Modifier.height(4.dp))
                    NocturneChip(
                        label = alert.first,
                        selected = false,
                        dotColor = alert.second
                    )
                }
            }
            HealthDotView(healthRecord.healthDot())
            if (hasUpdate) {
                TextButton(onClick = onUpdate) { Text("Update") }
            }
            TextButton(onClick = onDetails) { Text("Details") }
        }
    }
}

/** v1.0.5 (C3/D23): an OPDS library server in the same list —
 * same tile and rhythm, its kind labelled, no health dot (OPDS
 * servers carry no test-drive verdict). */
@Composable
private fun OpdsServerRow(
    server: OpdsServer,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier
) {
    GlassCard(
        modifier = modifier,
        onClick = onOpen,
        bouncePress = true,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SourceLetterTile(letter = server.name.firstOrNull()?.uppercase() ?: "?")
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(
                    server.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${server.baseUrl} · OPDS server",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun SourceLetterTile(letter: String) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
        modifier = Modifier.size(40.dp)
    ) {
        Column(
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                letter,
                style = MaterialTheme.typography.titleMedium,
                color = primaryTextColor()
            )
        }
    }
}

/** v1.0.5 (E2): the health dot — green working, amber needs
 * setup, red broken, grey never checked. The mapping itself is
 * the pure, unit-tested [healthDot]. */
@Composable
private fun HealthDotView(dot: HealthDot) {
    val color = when (dot) {
        HealthDot.GREEN -> Color(0xFF43A047)
        HealthDot.AMBER -> Color(0xFFFFB300)
        HealthDot.RED -> Color(0xFFE53935)
        HealthDot.GREY -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
    }
    Box(
        Modifier
            .padding(start = 10.dp)
            .size(10.dp)
            .clip(CircleShape)
            .background(color)
    )
}

/** M3: a quiet sub-label inside one side of the grouped
 * search results ("In your library" under a Novels/Manga
 * heading) — a plain text row, never a second heading. */
private fun androidx.compose.foundation.lazy.LazyListScope.searchSubLabel(
    key: String,
    text: String
) {
    item(key = key) {
        Text(
            text,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** M3: the search results' library poster rows (three to a
 * row), shared by the Novels and Manga sides of the grouped
 * results — the row shape is the one the flat list used. */
private fun androidx.compose.foundation.lazy.LazyListScope.searchLibraryRows(
    books: List<app.xreader.data.db.Book>,
    keyPrefix: String,
    coverFile: (String?) -> java.io.File?,
    onOpen: (Long) -> Unit
) {
    items(
        books.chunked(3),
        key = { row ->
            "$keyPrefix-" + row.joinToString("-") { it.id.toString() }
        }
    ) { rowBooks ->
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            rowBooks.forEach { book ->
                app.xreader.ui.common.BookPosterCard(
                    title = book.title,
                    author = book.author.takeIf { it.isNotBlank() },
                    coverModel = coverFile(book.coverPath)
                        ?.takeIf { it.exists() },
                    metaLine = "${book.totalChapters} chapters",
                    onClick = { onOpen(book.id) },
                    modifier = Modifier.weight(1f)
                )
            }
            repeat(3 - rowBooks.size) {
                Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** M3: one source's search-result group — its provider
 * header (the Mihon shape: eyebrowed as a source with its
 * count, so a result's origin is never a guess) and its
 * poster rows — shared by both sides of the grouped results. */
private fun androidx.compose.foundation.lazy.LazyListScope.searchGroupRows(
    group: GlobalSearchGroup,
    libraryKeys: Set<String>,
    workCounts: Map<String, Int>,
    onOpen: (GlobalSearchGroup, app.xreader.extensionapi.SourceBook) -> Unit
) {
    item(key = "gs-head-${group.kind}-${group.sourceId}") {
        SectionHeader(
            title = group.sourceName,
            eyebrow = "Source · " +
                "${group.books.size} " +
                if (group.books.size == 1) {
                    "result"
                } else {
                    "results"
                }
        )
    }
    items(
        group.books.chunked(3),
        key = { row ->
            "gs-row-${group.kind}-${group.sourceId}-" +
                row.joinToString("-") { it.id }
        }
    ) { rowBooks ->
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            rowBooks.forEach { book ->
                val workKey =
                    app.xreader.data.extension.WorkMatcher
                        .normaliseTitle(book.title)
                val inLibrary = workKey in libraryKeys
                val copies = workCounts[workKey] ?: 1
                app.xreader.ui.common.BookPosterCard(
                    title = book.title,
                    author = book.author
                        ?.takeIf { it.isNotBlank() },
                    coverModel = book.coverUrl
                        ?.takeIf { it.isNotBlank() },
                    metaLine = listOfNotNull(
                        if (inLibrary) {
                            "In your library"
                        } else {
                            null
                        },
                        if (copies > 1) {
                            "On $copies sources"
                        } else {
                            null
                        }
                    ).joinToString(" · ")
                        .takeIf { it.isNotBlank() },
                    onClick = { onOpen(group, book) },
                    modifier = Modifier.weight(1f)
                )
            }
            repeat(3 - rowBooks.size) {
                Spacer(Modifier.weight(1f))
            }
        }
    }
}
