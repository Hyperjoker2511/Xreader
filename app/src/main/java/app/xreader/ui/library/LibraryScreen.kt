@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package app.xreader.ui.library
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import app.xreader.ui.theme.errorTextColor
import app.xreader.ui.theme.sageTextColor

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.LibraryBooks
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.PlaylistRemove
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.xreader.core.motion.animateProgressAsState
import app.xreader.core.motion.itemEnter
import app.xreader.core.motion.itemExit
import app.xreader.core.motion.listPlacementSpec
import app.xreader.core.motion.pressBounce
import app.xreader.ui.common.DialogDangerOption
import app.xreader.ui.common.GlassCard
import app.xreader.ui.common.GlassSheetActionRow
import app.xreader.ui.common.KitEmptyState
import app.xreader.ui.common.NocturneChip
import app.xreader.ui.common.NocturneSwitch
import app.xreader.ui.common.PrimaryPill
import app.xreader.ui.common.ProgressPillBar
import app.xreader.ui.common.QuickActionTile
import app.xreader.ui.common.ScreenHeader
import app.xreader.ui.common.SearchCapsule
import app.xreader.ui.common.SectionHeader
import app.xreader.ui.common.StatusPill
import app.xreader.ui.common.XreaderPrimaryButton
import coil.compose.AsyncImage
import coil.request.ImageRequest
import java.io.File

/**
 * Library (Stitch design, PLAN §4): Continue Reading hero, then the
 * shelf with per-book progress. The header carries the ONE add action;
 * the import sheet (below the list) confirms each picked file with its
 * real format before anything is parsed.
 */
@OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class
)
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    coverFile: (String?) -> File?,
    reduceMotion: Boolean,
    onAddBook: () -> Unit,
    onOpenBook: (Long) -> Unit,
    onContinueReading: (bookId: Long, chapterIndex: Int) -> Unit,
    onProfile: () -> Unit = {},
    /** v1.0.2 (R27): paste-a-list import. */
    onOpenAddList: () -> Unit = {},
    coverGlow: Boolean = false
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val candidates by viewModel.candidates.collectAsStateWithLifecycle()
    // v1.0.1 library power basics: in-library search / sort / filter.
    var query by remember { mutableStateOf("") }
    // v1.0.6.2 (F6): search is an icon in the header now; the
    // field itself only appears while searching.
    var searchOpen by remember { mutableStateOf(false) }
    val searchFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(searchOpen) {
        if (searchOpen) {
            runCatching { searchFocus.requestFocus() }
        }
    }
    var sort by remember { mutableStateOf(app.xreader.data.library.LibrarySort.RECENTLY_READ) }
    var statusFilter by remember { mutableStateOf("ALL") }
    // v1.0.5 (C1/D20): status + sort + check-for-updates live in
    // ONE Filter sheet now — the toolbar keeps a single chip row.
    var filterSheetOpen by remember { mutableStateOf(false) }
    var removeTarget by remember { mutableStateOf<ShelfBook?>(null) }
    // v1.0.2 (R21/R23): quick actions, multi-select, shelf dialogs.
    var quickActionsFor by remember { mutableStateOf<ShelfBook?>(null) }
    var selection by remember { mutableStateOf<Set<Long>?>(null) }
    var manageShelvesOpen by remember { mutableStateOf(false) }
    var shelfPickerFor by remember { mutableStateOf<Set<Long>?>(null) }
    var bulkRemoveOpen by remember { mutableStateOf(false) }
    var bulkRemoveSize by remember { mutableStateOf<String?>(null) }

    // v1.0.2 (R21): shelf chips + membership from ShelfLogic —
    // the same pure logic the tests pin.
    val nowMillis = System.currentTimeMillis()
    val facts = remember(
        state.books, state.metas, state.favourites, state.bookTags, state.downloadCounts
    ) { state.shelfFacts(nowMillis) }
    val shelfChips = remember(
        facts, state.userShelves, state.memberships, state.hiddenBuiltins
    ) {
        app.xreader.data.library.ShelfLogic.chips(
            facts, state.userShelves, state.memberships, state.hiddenBuiltins, nowMillis
        )
    }
    val memberFilter = remember(
        state.selectedShelf, facts, state.userShelves, state.memberships
    ) {
        app.xreader.data.library.ShelfLogic.membersFor(
            state.selectedShelf, facts, state.userShelves, state.memberships, nowMillis
        )
    }
    val selectedUserShelf = remember(state.selectedShelf, state.userShelves) {
        state.selectedShelf.removePrefix("user:").toLongOrNull()
            ?.let { id -> state.userShelves.firstOrNull { it.id == id } }
    }
    // Per-shelf sort memory (R21): a user shelf's own sort wins.
    val effectiveSort = selectedUserShelf?.sort
        ?.let {
            runCatching { app.xreader.data.library.LibrarySort.valueOf(it) }.getOrNull()
        } ?: sort

    val visibleBooks = remember(
        state.books, state.metas, query, effectiveSort, statusFilter, memberFilter
    ) {
        val inShelf = if (memberFilter == null) {
            state.books
        } else {
            state.books.filter { it.book.id in memberFilter }
        }
        val rows = inShelf.map { shelf ->
            app.xreader.data.library.LibraryListLogic.Row(
                bookId = shelf.book.id,
                title = shelf.book.title,
                author = shelf.book.author,
                totalChapters = shelf.book.totalChapters,
                addedAt = shelf.book.addedAt,
                lastOpenedAt = shelf.book.lastOpenedAt,
                hasProgress = shelf.fraction > 0f,
                finished = shelf.book.finished,
                status = state.metas[shelf.book.id]?.status
                    ?: app.xreader.data.library.ReadingStatus.NONE
            )
        }
        val ordered = app.xreader.data.library.LibraryListLogic.apply(
            rows,
            state.libraryPrefs.copy(
                searchQuery = query, sort = effectiveSort, statusFilter = statusFilter
            )
        )
        val byId = inShelf.associateBy { it.book.id }
        ordered.mapNotNull { byId[it.bookId] }
    }

    fun onBookClick(shelfBook: ShelfBook) {
        val current = selection
        if (current != null) {
            selection = if (shelfBook.book.id in current) {
                (current - shelfBook.book.id).ifEmpty { null }
            } else {
                current + shelfBook.book.id
            }
        } else {
            viewModel.markSeen(shelfBook.book.id)
            onOpenBook(shelfBook.book.id)
        }
    }

    fun onBookLongPress(shelfBook: ShelfBook) {
        if (selection != null) {
            onBookClick(shelfBook)
        } else {
            quickActionsFor = shelfBook
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 10.dp)
    ) {
        ScreenHeader(
            title = "Library",
            eyebrow = "XREADER",
            action = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Design #1: the profile lives in the Library
                    // header and opens Profile & progress.
                    // v1.0.6: the old circle showed a bare "Lv"
                    // when no avatar was set — a label nobody
                    // could read. It is now a plain-words pill,
                    // "Level N" (the number was already in state
                    // as profileLevel), with the avatar picture
                    // kept as a small circle inside it when one
                    // is set. Same tap target, same bounce press.
                    val avatarInteraction = remember {
                        androidx.compose.foundation.interaction.MutableInteractionSource()
                    }
                    androidx.compose.material3.Surface(
                        onClick = onProfile,
                        interactionSource = avatarInteraction,
                        shape = androidx.compose.foundation.shape.CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier
                            .pressBounce(avatarInteraction)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(7.dp),
                            modifier = Modifier.padding(
                                horizontal = 12.dp, vertical = 8.dp
                            )
                        ) {
                            val avatar = state.avatarFile
                            val avatarRequest =
                                rememberSizedCover(coverFile(avatar), 22, 22)
                            if (avatar != null) {
                                AsyncImage(
                                    model = avatarRequest ?: coverFile(avatar),
                                    contentDescription = "Your profile",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(22.dp)
                                        .clip(
                                            androidx.compose.foundation
                                                .shape.CircleShape
                                        )
                                )
                            }
                            Text(
                                "Level ${state.profileLevel}",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme
                                    .onPrimaryContainer
                            )
                        }
                    }
                    // v1.0.6.2 (F6): the search entry point is a
                    // small icon here — the full-width field is
                    // gone; tapping opens the same search.
                    androidx.compose.material3.IconButton(
                        onClick = { searchOpen = !searchOpen }
                    ) {
                        androidx.compose.material3.Icon(
                            Icons.Filled.Search,
                            contentDescription = "Search your library"
                        )
                    }
                }
            }
        )
        // v1.0.6.3 (P1): "+ Add a book" lives in its OWN full-width
        // row under the header. Inside the header's action row it
        // was starved by the Level pill + search icon until its
        // label folded into a tall one-word-per-line pill. The
        // primary action of the whole app gets a full row, always.
        // (Hidden only when the empty state owns the screen — it
        // carries its own Add action.)
        if (!app.xreader.data.library.LibraryEmptyState.show(
                loading = state.loading,
                bookCount = state.books.size,
                candidateCount = candidates.size
            )
        ) {
            Spacer(Modifier.height(10.dp))
            PrimaryPill(
                text = "+ Add a book",
                onClick = onAddBook,
                modifier = Modifier.fillMaxWidth(),
                enabled = true,

            )
        }
        Spacer(Modifier.height(8.dp))

        // v1.0.4 (D3): the empty decision is the pure predicate in
        // LibraryEmptyState (unit-tested), and when it fires the
        // empty state OWNS the screen — search, toolbar and chips
        // never render over it. (The old D13 hover drift is gone:
        // the kit EmptyState is static by design.)
        if (app.xreader.data.library.LibraryEmptyState.show(
                loading = state.loading,
                bookCount = state.books.size,
                candidateCount = candidates.size
            )
        ) {
            KitEmptyState(
                icon = Icons.Filled.LibraryBooks,
                message = "No books yet — add your first book (EPUB, PDF, " +
                    "TXT and more) and it will live here, ready to read.",
                actionText = "Add a book",
                onAction = onAddBook,
                modifier = Modifier.weight(1f)
            )
            return@Column
        }

        // The Library's chrome (v1.0.5, C1): header, ONE chip
        // row, then books — nothing else stands between the
        // reader and their shelves. v1.0.6.2 (F6): the search
        // field renders only while searching (opened from the
        // header icon); its filtering behaviour is unchanged.
        if (searchOpen) {
            // Nocturne §5.1: the in-library search is the kit
            // SearchCapsule; its behaviour (live filtering by
            // title/author) is the existing one. The close button
            // keeps the old clear-and-close door beside it.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SearchCapsule(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = "Search your library — title or author",
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(searchFocus)
                )
                androidx.compose.material3.IconButton(
                    onClick = {
                        query = ""
                        searchOpen = false
                    }
                ) {
                    androidx.compose.material3.Icon(
                        Icons.Filled.Close,
                        contentDescription = "Close search"
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
        }
        // v1.0.5 (C1/D20): the Library decluttered to ONE chip row.
        // History and Updates left the toolbar — they are bottom-
        // nav tabs already. The shelf rail below is the only row:
        // a Filter chip first (status + sort + Check for updates
        // live in its sheet), then the shelves, then "+ Shelf" and
        // "+ Add a list" trailing. The row scrolls sideways, so
        // nothing can crush here either (D2's property, kept).
        // v1.2.0 (WS4 declutter): the stacked spacers above this
        // row (10 + 12 + 10) are consolidated — the header block
        // now flows into the chip row with one rhythm of air
        // instead of three accumulated gaps.
        Spacer(Modifier.height(2.dp))
        androidx.compose.foundation.lazy.LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item(key = "filter") {
                val activeStatus = statusOptions.firstOrNull {
                    it.first == statusFilter && statusFilter != "ALL"
                }
                NocturneChip(
                    label = if (activeStatus != null) {
                        "Filter & sort · ${activeStatus.second}"
                    } else {
                        "Filter & sort"
                    },
                    selected = statusFilter != "ALL",
                    onClick = { filterSheetOpen = true },
                    modifier = Modifier,
                    dotColor = null,
                
                )
            }
            items(shelfChips, key = { "shelf-${it.ref}" }) { chip ->
                NocturneChip(
                    label = "${chip.name} · ${chip.count}",
                    selected = state.selectedShelf == chip.ref,
                    onClick = { viewModel.selectShelf(chip.ref) },
                    modifier = Modifier,
                    dotColor = null,
                
                )
            }
            item(key = "shelf-add") {
                NocturneChip(
                    label = "+ Shelf",
                    selected = false,
                    onClick = { manageShelvesOpen = true },
                    modifier = Modifier,
                    dotColor = null,
                
                )
            }
            item(key = "add-list") {
                NocturneChip(
                    label = "+ Add a list",
                    selected = false,
                    onClick = onOpenAddList,
                    modifier = Modifier,
                    dotColor = null,
                
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        // v1.0.2 (R23): multi-select action bar.
        // v1.0.5 (B3): both rows SCROLL sideways — as fixed rows
        // their seven labelled buttons crowded narrow phones (the
        // same crush family as D2/D25); the count keeps its place
        // at the start of the first row instead of a weight, which
        // a scrolling row can't honour.
        selection?.let { picked ->
            // Nocturne §5.1: the batch actions ride in ONE frosted
            // capsule bar (24dp corners, Q12) instead of two bare
            // text rows.
            androidx.compose.material3.Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shadowElevation = 6.dp
            ) {
            Column(
                Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        "${picked.size} selected",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    TextButton(onClick = { shelfPickerFor = picked }) {
                        Text("Add to shelf")
                    }
                    TextButton(onClick = {
                        viewModel.bulkSetFavourite(picked, true)
                    }) { Text("Favourite") }
                    TextButton(onClick = { selection = null }) { Text("Done") }
                }
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    TextButton(onClick = { viewModel.bulkDownload(picked) }) {
                        Text("Download")
                    }
                    TextButton(onClick = { viewModel.bulkDeleteDownloads(picked) }) {
                        Text("Delete downloads")
                    }
                    TextButton(onClick = {
                        viewModel.bulkSetStatus(
                            picked, app.xreader.data.library.ReadingStatus.COMPLETED
                        )
                    }) { Text("Mark read") }
                    TextButton(onClick = {
                        bulkRemoveSize = null
                        bulkRemoveOpen = true
                    }) { Text("Remove…") }
                }
            }
            }
        }
        // v1.0.4 (D10 — the user's ruling): the auto-download switch
        // no longer lives in the Library. It moved to Settings →
        // Customise → Downloads, with the Wi-Fi-only switch beside
        // it. No trace is left here.

        // v1.0.2 (R20): pull to refresh — silent; the results are
        // read on the Updates page and the +N badges.
        androidx.compose.material3.pulltorefresh.PullToRefreshBox(
            isRefreshing = state.pullRefreshing,
            onRefresh = { viewModel.pullRefresh() },
            modifier = Modifier.weight(1f)
        ) {
        LazyColumn(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 88.dp)
        ) {
            (state.continueReading.takeIf { state.libraryPrefs.showContinueReading })?.let { hero ->
                item(key = "continue") {
                    androidx.compose.animation.AnimatedVisibility(
                        visible = true,
                        enter = itemEnter(reduceMotion),
                        exit = itemExit(reduceMotion)
                    ) {
                        ContinueReadingHero(
                            hero = hero,
                            coverFile = coverFile,
                            wpm = state.wpm,
                            continueChapterIndex = state.continueChapterIndex,
                            onContinue = {
                                onContinueReading(hero.book.id, state.continueChapterIndex)
                            }
                        )
                    }
                }
            }
            // ── v1.0.2 (R22 rec): For you — from tags you follow ──
            if (state.forYou.isNotEmpty() &&
                state.selectedShelf == "all" && query.isBlank()
            ) {
                item(key = "foryou-header") {
                    SectionHeader(title = "For you — from tags you follow",
    modifier = Modifier,
    eyebrow = null,
    actionText = null,
    onAction = null,

                    )
                }
                item(key = "foryou-row") {
                    androidx.compose.foundation.lazy.LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(state.forYou, key = { "fy-${it.book.id}" }) { shelfBook ->
                            val book = shelfBook.book
                            Column(
                                Modifier
                                    .width(96.dp)
                                    .clickable { onOpenBook(book.id) }
                            ) {
                                val cover = coverFile(book.coverPath)
                                val coverRequest =
                                    rememberSizedCover(cover, 96, 136)
                                if (cover != null) {
                                    AsyncImage(
                                        model = coverRequest ?: cover,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .size(width = 96.dp, height = 136.dp)
                                            .clip(RoundedCornerShape(14.dp))
                                    )
                                } else {
                                    PlaceholderCover(
                                        title = book.title,
                                        modifier = Modifier
                                            .size(width = 96.dp, height = 136.dp)
                                            .clip(RoundedCornerShape(14.dp))
                                    )
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    book.title,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
            if (candidates.isNotEmpty()) {
                item(key = "import-header") {
                    SectionHeader(
                        title = "Adding books",
                        actionText = "Clear finished",
                        onAction = { viewModel.clearFinishedCandidates() },
                        modifier = Modifier,
                        eyebrow = null,
                    
                    )
                }
                items(candidates, key = { "cand-${it.token()}" }) { candidateState ->
                    ImportCandidateCard(
                        state = candidateState,
                        onImport = { viewModel.importCandidate(it) },
                        onDismiss = { viewModel.dismissCandidate(it) },
                        modifier = Modifier.animateItem(placementSpec = listPlacementSpec())
                    )
                }
            }
            if (visibleBooks.isNotEmpty()) {
                item(key = "recent-header") {
                    SectionHeader(
                        title = if (query.isBlank() && statusFilter == "ALL") {
                            "Recent books"
                        } else {
                            "${visibleBooks.size} book(s)"
                        },
                        modifier = Modifier,
                        eyebrow = null,
                        actionText = null,
                        onAction = null,
                    
                    )
                }
            } else if (memberFilter != null && candidates.isEmpty()) {
                item(key = "shelf-empty") {
                    Text(
                        if (state.selectedShelf ==
                            app.xreader.data.library.ShelfLogic.BUILTIN_BEHIND_ON
                        ) {
                            "You're all caught up — nothing here is behind."
                        } else {
                            "Nothing on this shelf yet. Long-press a book " +
                                "and choose “Add to shelf”."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (state.libraryPrefs.layout ==
                app.xreader.data.library.LibraryLayout.BENTO
            ) {
                // R19 Bento: alternating rows whose first card is
                // twice as wide — a magazine shelf, same cards.
                val bentoRows = visibleBooks.chunked(3)
                items(
                    bentoRows,
                    key = { row -> "bento-" + row.joinToString("-") { it.book.id.toString() } }
                ) { rowBooks ->
                    val bigFirst = bentoRows.indexOf(rowBooks) % 2 == 0
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        rowBooks.forEachIndexed { index, shelfBook ->
                            ShelfGridCard(
                                shelfBook = shelfBook,
                                coverFile = coverFile,
                                newChapters = if (state.libraryPrefs.showBadges) {
                                    state.metas[shelfBook.book.id]?.newChapterCount ?: 0
                                } else {
                                    0
                                },
                                showProgress = state.libraryPrefs.showBadges,
                                downloaded = state.downloadCounts[shelfBook.book.id]
                                    ?.downloaded ?: 0,
                                unreadDownloaded = state.downloadCounts[shelfBook.book.id]
                                    ?.let { it.downloaded - it.readDownloaded } ?: 0,
                                favourite = shelfBook.book.id in state.favourites,
                                selected = selection?.contains(shelfBook.book.id),
                                coverGlow = coverGlow,
                                onOpen = { onBookClick(shelfBook) },
                                onLongPress = { onBookLongPress(shelfBook) },
                                // v1.2.0 (WS4): the wide 2f slot
                                // only exists when the row has a
                                // partner card to balance it — a
                                // LONE book in a bento row used to
                                // stretch to half the screen width
                                // (an oversized cover for a
                                // one-book library). It now takes
                                // one normal column, with the row's
                                // spacer weights holding the rest.
                                modifier = Modifier.weight(
                                    if (bigFirst && index == 0 &&
                                        rowBooks.size > 1
                                    ) {
                                        2f
                                    } else {
                                        1f
                                    }
                                )
                            )
                        }
                        repeat(3 - rowBooks.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            } else if (state.libraryPrefs.layout ==
                app.xreader.data.library.LibraryLayout.GRID
            ) {
                items(
                    visibleBooks.chunked(state.libraryPrefs.gridColumns),
                    key = { row -> "gridrow-" + row.joinToString("-") { it.book.id.toString() } }
                ) { rowBooks ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        rowBooks.forEach { shelfBook ->
                            ShelfGridCard(
                                shelfBook = shelfBook,
                                coverFile = coverFile,
                                newChapters = if (state.libraryPrefs.showBadges) {
                                    state.metas[shelfBook.book.id]?.newChapterCount ?: 0
                                } else {
                                    0
                                },
                                showProgress = state.libraryPrefs.showBadges,
                                downloaded = state.downloadCounts[shelfBook.book.id]
                                    ?.downloaded ?: 0,
                                unreadDownloaded = state.downloadCounts[shelfBook.book.id]
                                    ?.let { it.downloaded - it.readDownloaded } ?: 0,
                                favourite = shelfBook.book.id in state.favourites,
                                selected = selection?.contains(shelfBook.book.id),
                                coverGlow = coverGlow,
                                onOpen = { onBookClick(shelfBook) },
                                onLongPress = { onBookLongPress(shelfBook) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        repeat(state.libraryPrefs.gridColumns - rowBooks.size) {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            } else {
                items(visibleBooks, key = { "book-${it.book.id}" }) { shelfBook ->
                    ShelfRow(
                        shelfBook = shelfBook,
                        coverFile = coverFile,
                        wpm = state.wpm,
                        newChapters = if (state.libraryPrefs.showBadges) {
                            state.metas[shelfBook.book.id]?.newChapterCount ?: 0
                        } else {
                            0
                        },
                        downloaded = state.downloadCounts[shelfBook.book.id]
                            ?.downloaded ?: 0,
                        unreadDownloaded = state.downloadCounts[shelfBook.book.id]
                            ?.let { (it.downloaded - it.readDownloaded).coerceAtLeast(0) }
                            ?: 0,
                        favourite = shelfBook.book.id in state.favourites,
                        selected = selection?.contains(shelfBook.book.id) == true,
                        onOpen = { onBookClick(shelfBook) },
                        onLongPress = { onBookLongPress(shelfBook) },
                        compact = state.libraryPrefs.layout ==
                            app.xreader.data.library.LibraryLayout.COMPACT_LIST,
                        modifier = Modifier.animateItem(placementSpec = listPlacementSpec())
                    )
                }
            }
        }
        }

        // v1.0.2 (R21/R23): long-press quick actions — everything
        // labelled, nothing buried; Remove keeps its honest dialog.
        // v1.0.5 (D1): the sheet is GLASS now — icon+label rows on
        // GlassSheetActionRow (the shared glass path), hugging its
        // content via skipPartiallyExpanded instead of stretching
        // into a tall, mostly-empty black sheet of bare text lines.
        quickActionsFor?.let { target ->
            val isFavourite = target.book.id in state.favourites
            androidx.compose.material3.ModalBottomSheet(
                onDismissRequest = { quickActionsFor = null },
                sheetState = androidx.compose.material3
                    .rememberModalBottomSheetState(skipPartiallyExpanded = true)
            ) {
                Column(
                    Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        target.book.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2
                    )
                    // Nocturne §5.1: the four everyday actions as
                    // a QuickActionTile grid; the rest stay as
                    // labelled rows below — nothing is buried.
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        QuickActionTile(
                            icon = Icons.Filled.MenuBook,
                            label = "Open",
                            modifier = Modifier.weight(1f),
                            onClick = {
                                quickActionsFor = null
                                onBookClick(target)
                            },
                            active = false,
                        
                        )
                        QuickActionTile(
                            icon = if (isFavourite) {
                                Icons.Filled.Favorite
                            } else {
                                Icons.Filled.FavoriteBorder
                            },
                            label = "Favourite",
                            modifier = Modifier.weight(1f),
                            active = isFavourite,
                            onClick = {
                                viewModel.setFavourite(target.book.id, !isFavourite)
                                quickActionsFor = null
                            }
                        )
                        QuickActionTile(
                            icon = Icons.Filled.PlaylistAdd,
                            label = "Add to shelf",
                            modifier = Modifier.weight(1f),
                            onClick = {
                                shelfPickerFor = setOf(target.book.id)
                                quickActionsFor = null
                            },
                            active = false,
                        
                        )
                        QuickActionTile(
                            icon = Icons.Filled.CheckBox,
                            label = "Select",
                            modifier = Modifier.weight(1f),
                            onClick = {
                                selection = setOf(target.book.id)
                                quickActionsFor = null
                            },
                            active = false,
                        
                        )
                    }
                    selectedUserShelf?.takeIf {
                        it.kind == app.xreader.data.db.ShelfKind.MANUAL
                    }?.let { shelf ->
                        GlassSheetActionRow(
                            icon = Icons.Filled.PlaylistRemove,
                            label = "Remove from “${shelf.name}”",
                            onClick = {
                                viewModel.removeBookFromShelf(target.book.id, shelf.id)
                                quickActionsFor = null
                            }
                        )
                    }
                    GlassSheetActionRow(
                        icon = Icons.Filled.Download,
                        label = "Download book",
                        onClick = {
                            viewModel.bulkDownload(listOf(target.book.id))
                            quickActionsFor = null
                        }
                    )
                    GlassSheetActionRow(
                        icon = Icons.Filled.DeleteSweep,
                        label = "Delete downloads",
                        onClick = {
                            viewModel.bulkDeleteDownloads(listOf(target.book.id))
                            quickActionsFor = null
                        }
                    )
                    GlassSheetActionRow(
                        icon = Icons.Filled.RemoveCircleOutline,
                        label = "Remove from library…",
                        destructive = true,
                        onClick = {
                            removeTarget = target
                            quickActionsFor = null
                        }
                    )
                    Spacer(Modifier.height(24.dp))
                }
            }
        }

        // Shelf picker (single book or a whole selection).
        shelfPickerFor?.let { bookIds ->
            AlertDialog(
                onDismissRequest = { shelfPickerFor = null },
                title = { Text("Add to shelf") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val manual = state.userShelves.filter {
                            it.kind == app.xreader.data.db.ShelfKind.MANUAL
                        }
                        if (manual.isEmpty()) {
                            Text(
                                "No shelves yet — create one first.",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        manual.forEach { shelf ->
                            TextButton(onClick = {
                                viewModel.addBooksToShelf(bookIds, shelf.id)
                                shelfPickerFor = null
                            }) { Text(shelf.name) }
                        }
                        TextButton(onClick = {
                            shelfPickerFor = null
                            manageShelvesOpen = true
                        }) { Text("New shelf…") }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { shelfPickerFor = null }) { Text("Cancel") }
                }
            )
        }

        // v1.0.5 (C1/D20): the Filter sheet — everything that
        // left the toolbar lives here, in ONE place: reading
        // status, sort order, and Check for updates. Choices are
        // chips in wrapping rows, so nothing can crush here
        // either, and picking one applies it immediately.
        if (filterSheetOpen) {
            androidx.compose.material3.ModalBottomSheet(
                onDismissRequest = { filterSheetOpen = false },
                sheetState = androidx.compose.material3
                    .rememberModalBottomSheetState(skipPartiallyExpanded = true)
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 8.dp)
                        .padding(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        "Filter & sort",
                        style = MaterialTheme.typography.titleLarge
                    )
                    SectionHeader(title = "Reading status",
    modifier = Modifier,
    eyebrow = null,
    actionText = null,
    onAction = null,

                    )
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        statusOptions.forEach { (value, label) ->
                            NocturneChip(
                                label = label,
                                selected = statusFilter == value,
                                onClick = { statusFilter = value },
                                modifier = Modifier,
                                dotColor = null,
                            
                            )
                        }
                    }
                    SectionHeader(title = "Sort by",
    modifier = Modifier,
    eyebrow = null,
    actionText = null,
    onAction = null,

                    )
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        app.xreader.data.library.LibrarySort.entries.forEach { option ->
                            NocturneChip(
                                label = sortLabel(option),
                                selected = effectiveSort == option,
                                onClick = {
                                    sort = option
                                    // R21: a user shelf remembers its sort.
                                    selectedUserShelf?.let {
                                        viewModel.setShelfSort(it.id, option)
                                    }
                                },
                                modifier = Modifier,
                                dotColor = null,
                            
                            )
                        }
                    }
                    GlassSheetActionRow(
                        icon = Icons.Filled.Refresh,
                        label = if (state.checkingUpdates) {
                            "Checking for updates…"
                        } else {
                            "Check for updates"
                        },
                        caption = "Look for new chapters on every source",
                        enabled = !state.checkingUpdates,
                        onClick = {
                            viewModel.checkForUpdates()
                            filterSheetOpen = false
                        }
                    )
                    TextButton(
                        onClick = { filterSheetOpen = false },
                        modifier = Modifier.align(Alignment.End)
                    ) { Text("Done") }
                }
            }
        }

        if (manageShelvesOpen) {
            ManageShelvesDialog(
                state = state,
                onDismiss = { manageShelvesOpen = false },
                onCreate = { name, rule, value ->
                    viewModel.createShelf(name, rule, value)
                },
                onRename = { id, name -> viewModel.renameShelf(id, name) },
                onDelete = { id -> viewModel.deleteShelf(id) },
                onMove = { id, up -> viewModel.moveShelf(id, up) },
                onBuiltinHidden = { key, hidden ->
                    viewModel.setBuiltinShelfHidden(key, hidden)
                }
            )
        }

        // Bulk remove: the confirm states the exact count + size.
        if (bulkRemoveOpen) {
            val picked = selection ?: emptySet()
            val pickedBooks = state.books.filter { it.book.id in picked }
            LaunchedEffect(picked) {
                val bytes = viewModel.bulkDownloadedSizeBytes(picked)
                bulkRemoveSize =
                    app.xreader.data.files.StorageMeter.formatBytes(bytes)
            }
            // v1.0.5 (D2): choices stack full-width in the body;
            // "Keep them" sits alone in the button row (same fix
            // as the single-book Remove dialog below).
            AlertDialog(
                onDismissRequest = { bulkRemoveOpen = false },
                title = { Text("Remove ${picked.size} book(s)?") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "Removing also removes reading progress and bookmarks. " +
                                "Downloaded chapters take " +
                                (bulkRemoveSize ?: "…") + "."
                        )
                        Spacer(Modifier.height(8.dp))
                        DialogDangerOption(
                            label = "Remove books only",
                            onClick = {
                                bulkRemoveOpen = false
                                selection = null
                                viewModel.bulkRemoveBooks(
                                    pickedBooks.map { it.book }, deleteFiles = false
                                )
                            }
                        )
                        DialogDangerOption(
                            label = "Remove books and downloads",
                            onClick = {
                                bulkRemoveOpen = false
                                selection = null
                                viewModel.bulkRemoveBooks(
                                    pickedBooks.map { it.book }, deleteFiles = true
                                )
                            }
                        )
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { bulkRemoveOpen = false }) {
                        Text("Keep them")
                    }
                }
            )
        }

        removeTarget?.let { target ->
            var removeSizeMb by remember(target.book.id) { mutableStateOf<String?>(null) }
            LaunchedEffect(target.book.id) {
                val bytes = viewModel.downloadedSizeBytes(target.book)
                removeSizeMb = "%.1f MB".format(bytes / 1_000_000.0)
            }
            // v1.0.5 (D2): the dialog from the user's screenshot —
            // its choices used to be a Column crammed into the
            // confirm slot, so "Remove book and downloaded
            // chapters (0.0 MB)" wrapped against "Keep the book".
            // Now the choices stack full-width in the body and
            // Keep sits alone in the button row.
            AlertDialog(
                onDismissRequest = { removeTarget = null },
                title = { Text("Remove “${target.book.title}”?") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "Removing the book also removes its reading progress and " +
                                "bookmarks. Your original file (if you imported one) " +
                                "stays where it is."
                        )
                        Spacer(Modifier.height(8.dp))
                        DialogDangerOption(
                            label = "Remove book only",
                            onClick = {
                                removeTarget = null
                                viewModel.removeBook(target.book, deleteFiles = false)
                            }
                        )
                        DialogDangerOption(
                            label = "Remove book and downloaded chapters" +
                                (removeSizeMb?.let { " ($it)" } ?: ""),
                            onClick = {
                                removeTarget = null
                                viewModel.removeBook(target.book, deleteFiles = true)
                            }
                        )
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { removeTarget = null }) { Text("Keep the book") }
                }
            )
        }
    }
}

/**
 * v1.0.5 (C1/D20): the reading-status choices, shared by the
 * Filter chip's active label and the Filter sheet's choice row.
 * The values are the filter keys the list logic has always used.
 */
private val statusOptions: List<Pair<String, String>> = listOf(
    "ALL" to "All", "READING" to "Reading",
    "COMPLETED" to "Completed", "ON_HOLD" to "On hold",
    "DROPPED" to "Dropped", "NOT_STARTED" to "Not started"
)

private fun sortLabel(sort: app.xreader.data.library.LibrarySort): String = when (sort) {
    app.xreader.data.library.LibrarySort.RECENTLY_READ -> "Recently read"
    app.xreader.data.library.LibrarySort.TITLE -> "Title"
    app.xreader.data.library.LibrarySort.MOST_CHAPTERS -> "Most chapters"
    app.xreader.data.library.LibrarySort.RECENTLY_ADDED -> "Recently added"
}

/** R19: a cover is never a blank rectangle.
 * v1.3.3 (addition 4): a book without cover art gets a
 * DESIGNED TITLE COVER — its full title set as cover
 * typography (headline face, multi-line, cover-style
 * composition: accent rule, title block, author at the
 * foot) over its own deterministic violet-family gradient
 * ([TitleCoverDesign] — the same book wears the same
 * colour everywhere). It stays honest typography, never
 * imitation art; a real cover always wins over it. */
@Composable
internal fun PlaceholderCover(
    title: String,
    modifier: Modifier = Modifier,
    author: String? = null
) {
    val colors = remember(title) { TitleCoverDesign.gradientColors(title) }
    val accent = remember(title) { TitleCoverDesign.accentColor(title) }
    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier.background(
            androidx.compose.ui.graphics.Brush.verticalGradient(colors)
        )
    ) {
        // Tiny tiles (recent-row thumbnails) set the title
        // smaller and skip the foot line; poster sizes get
        // the full cover composition.
        val compact = maxWidth < 76.dp
        Column(
            Modifier
                .fillMaxSize()
                .padding(
                    horizontal = if (compact) 7.dp else 12.dp,
                    vertical = if (compact) 7.dp else 12.dp
                ),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Box(
                Modifier
                    .width(if (compact) 14.dp else 24.dp)
                    .height(3.dp)
                    .background(accent)
            )
            Text(
                title,
                style = if (compact) {
                    MaterialTheme.typography.labelLarge
                } else {
                    MaterialTheme.typography.titleMedium
                },
                color = androidx.compose.ui.graphics.Color.White
                    .copy(alpha = 0.96f),
                maxLines = if (compact) 4 else 6,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(vertical = 4.dp)
            )
            if (!author.isNullOrBlank() && !compact) {
                Text(
                    author,
                    style = MaterialTheme.typography.labelSmall,
                    color = androidx.compose.ui.graphics.Color.White
                        .copy(alpha = 0.72f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            } else {
                Spacer(Modifier.height(2.dp))
            }
        }
    }
}

/**
 * v1.2.0 (WS4, sized Coil requests): a cover [ImageRequest] that
 * asks Coil to decode near the size the cover is actually drawn
 * at ([widthDp] × [heightDp] display size, converted with the
 * device density) instead of decoding the full stored image into
 * a 44dp row thumbnail. Returns null for a null file so callers
 * keep their placeholder path. Always called unconditionally at
 * each site (the request is remembered by file + size), per the
 * Compose rules for conditional content.
 */
@Composable
private fun rememberSizedCover(file: File?, widthDp: Int, heightDp: Int): ImageRequest? {
    val context = LocalContext.current
    return remember(file, widthDp, heightDp) {
        if (file == null) {
            null
        } else {
            val density = context.resources.displayMetrics.density
            ImageRequest.Builder(context)
                .data(file)
                .size(
                    (widthDp * density).toInt().coerceAtLeast(1),
                    (heightDp * density).toInt().coerceAtLeast(1)
                )
                .build()
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ShelfGridCard(
    shelfBook: ShelfBook,
    coverFile: (String?) -> File?,
    newChapters: Int,
    showProgress: Boolean,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
    downloaded: Int = 0,
    unreadDownloaded: Int = 0,
    favourite: Boolean = false,
    selected: Boolean? = null,
    coverGlow: Boolean = false
) {
    val cover = coverFile(shelfBook.book.coverPath)
    val glowColor = remember(shelfBook.book.title) {
        app.xreader.ui.theme.CoverPaletteExtractor
            .placeholderPalette(shelfBook.book.title).vibrant
    }
    // v1.0.6.2 (F6 + S1): the shared poster card — the cover IS
    // the card (2:3, full-bleed, no slab), title below in a fixed
    // two-line area, ONE metadata line, progress as a thin line.
    // The unread count rides the same line as the chapter count
    // (the spec's "3228 chapters · 12 unread" shape).
    val unread = shelfBook.book.totalChapters -
        (shelfBook.fraction * shelfBook.book.totalChapters).toInt()
    // v1.2.0 (WS4): every fragment of the ONE meta line must earn
    // its place — a 0-chapter fragment said nothing, and when no
    // fragment survives the line is null (the poster card hides
    // a null line entirely instead of reserving an empty row).
    val metaLine = listOfNotNull(
        if (shelfBook.book.totalChapters > 0) {
            "${shelfBook.book.totalChapters} chapters"
        } else {
            null
        },
        if (downloaded > 0) "$downloaded downloaded" else null,
        if (shelfBook.fraction > 0f && shelfBook.fraction < 1f && unread > 0) {
            "$unread unread"
        } else {
            null
        }
    ).joinToString(" · ")
    app.xreader.ui.common.BookPosterCard(
        title = shelfBook.book.title,
        author = shelfBook.book.author.takeIf { it.isNotBlank() },
        coverModel = cover?.takeIf { it.exists() },
        metaLine = metaLine.ifBlank { null },
        onClick = onOpen,
        onLongClick = onLongPress,
        progressFraction = if (showProgress) shelfBook.fraction else null,
        topEndBadge = if (newChapters > 0) "+$newChapters" else null,
        topStartBadge = when {
            selected == true -> "✓"
            favourite -> "♥"
            else -> null
        },
        glowColor = if (coverGlow) glowColor else null,
        tilt = true,
        modifier = modifier
    )
}

private fun CandidateState.token(): String = when (this) {
    is CandidateState.Ready -> candidate.token
    is CandidateState.Importing -> candidate.token
    is CandidateState.Done -> candidate.token
    is CandidateState.Failed -> candidate.token
}

@Composable
private fun ContinueReadingHero(
    hero: ShelfBook,
    coverFile: (String?) -> File?,
    wpm: Double,
    continueChapterIndex: Int,
    onContinue: () -> Unit
) {
    val progress = animateProgressAsState(hero.fraction, label = "heroProgress")
    // Nocturne §5.1: the old trio-gradient stage is replaced by the
    // frozen book page's HALO recipe — a 320dp primary-16% radial at
    // top-centre + a 240dp tertiary-13% radial, role colours only.
    // The GlassCard and the whole-card Continue tap are unchanged.
    // v1.2.0 (WS4, the wasted-middle fix): the halo used to be two
    // layout Boxes (320dp / 240dp) inside this wrap Box. A Box
    // sizes to its LARGEST child and offsets do not shrink the
    // measured size, so the wrap measured 320dp tall while the
    // card is ~160dp — leaving a ~160dp band of dead space under
    // the hero, between it and Recent books, in every short
    // library. The halo is now drawn with drawBehind (identical
    // gradients, radii and top-centre placement), which never
    // affects layout: the hero is exactly as tall as its card,
    // and the sections below sit directly under it.
    val scheme = MaterialTheme.colorScheme
    val heroCoverRequest = rememberSizedCover(coverFile(hero.book.coverPath), 60, 88)
    Box(
        Modifier
            .fillMaxWidth()
            .drawBehind {
                val topCenter = Offset(size.width / 2f, 0f)
                val primaryRadius = 160.dp.toPx()
                drawCircle(
                    brush = androidx.compose.ui.graphics.Brush.radialGradient(
                        listOf(
                            scheme.primary.copy(alpha = 0.16f),
                            androidx.compose.ui.graphics.Color.Transparent
                        ),
                        center = topCenter,
                        radius = primaryRadius
                    ),
                    center = topCenter,
                    radius = primaryRadius
                )
                val tertiaryRadius = 120.dp.toPx()
                drawCircle(
                    brush = androidx.compose.ui.graphics.Brush.radialGradient(
                        listOf(
                            scheme.tertiary.copy(alpha = 0.13f),
                            androidx.compose.ui.graphics.Color.Transparent
                        ),
                        center = topCenter,
                        radius = tertiaryRadius
                    ),
                    center = topCenter,
                    radius = tertiaryRadius
                )
            }
    ) {
        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            onClick = onContinue,
            bouncePress = true
        ) {
        Row(Modifier.fillMaxWidth()) {
            // Cover: 14dp corners with the halo glow underlay
            // (blurred primary-30% disc behind the art).
            Box {
                Box(
                    Modifier
                        .size(width = 60.dp, height = 88.dp)
                        .offset(y = 6.dp)
                        .background(
                            androidx.compose.ui.graphics.Brush.radialGradient(
                                listOf(
                                    scheme.primary.copy(alpha = 0.30f),
                                    androidx.compose.ui.graphics.Color.Transparent
                                )
                            )
                        )
                )
                AsyncImage(
                    model = heroCoverRequest ?: coverFile(hero.book.coverPath),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(width = 60.dp, height = 88.dp)
                        .clip(RoundedCornerShape(14.dp))
                )
            }
            Column(
                Modifier
                    .weight(1f)
                    .padding(start = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // v1.2.0 (WS4): the format chip only renders when
                // the format is actually known — an UNKNOWN book
                // (every source book, most imports before sniffing)
                // used to wear a chip that literally read
                // "UNKNOWN", saying nothing in the loudest place
                // on the screen.
                if (hero.book.localFormat !=
                    app.xreader.data.db.BookFormat.UNKNOWN
                ) {
                    StatusPill(
                        text = hero.book.localFormat.name,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Text(hero.book.title, style = MaterialTheme.typography.titleLarge, maxLines = 1)
                if (hero.book.author.isNotBlank()) {
                    Text(
                        hero.book.author,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
                ProgressPillBar(fraction = progress, modifier = Modifier.fillMaxWidth())
                Text(
                    // A 0-chapter count says nothing (a source
                    // book whose list hasn't loaded) — the percent
                    // and the time-left suffix carry the line.
                    listOfNotNull(
                        "${(progress * 100).toInt()}%",
                        if (hero.book.totalChapters > 0) {
                            "${hero.book.totalChapters} chapters"
                        } else {
                            null
                        },
                        timeLeftSuffix(hero, wpm).removePrefix(" • ")
                            .takeIf { it.isNotBlank() }
                    ).joinToString(" • "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                PrimaryPill(
                    text = "Continue · Ch. ${continueChapterIndex + 1}",
                    onClick = onContinue,
                    modifier = Modifier,
                    enabled = true,
                
                )
            }
        }
        }
    }
}

/** " • ≈3 h left" for a partly-read book with known length. */
private fun timeLeftSuffix(shelfBook: ShelfBook, wpm: Double): String {
    val fraction = shelfBook.fraction
    val totalWords = shelfBook.book.totalWords
    if (fraction <= 0f || fraction >= 1f || totalWords <= 0 || wpm <= 0) return ""
    val remaining = (totalWords * (1f - fraction)).toInt()
    if (remaining <= 0) return ""
    val minutes = (remaining / wpm).toInt().coerceAtLeast(1)
    return " • " + app.xreader.domain.reader.WpmEstimator.formatMinutes(minutes) + " left"
}

@Composable
private fun ShelfRow(
    shelfBook: ShelfBook,
    coverFile: (String?) -> File?,
    wpm: Double,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    newChapters: Int = 0,
    onLongPress: () -> Unit = {},
    /** v1.0.1: Compact list = smaller cover, tighter row. */
    compact: Boolean = false,
    downloaded: Int = 0,
    unreadDownloaded: Int = 0,
    favourite: Boolean = false,
    selected: Boolean = false
) {
    val progress = animateProgressAsState(shelfBook.fraction, label = "shelfProgress")
    val cover = coverFile(shelfBook.book.coverPath)
    // v1.2.0 (WS4): decode the row cover near its drawn size.
    val coverRequest = rememberSizedCover(
        cover?.takeIf { it.exists() },
        if (compact) 32 else 44,
        if (compact) 45 else 62
    )
    val coverModifier = (if (compact) {
        Modifier.size(width = 32.dp, height = 45.dp)
    } else {
        Modifier.size(width = 44.dp, height = 62.dp)
    }).clip(RoundedCornerShape(12.dp))
    GlassCard(modifier = modifier.fillMaxWidth(), onClick = onOpen) {
        Row(
            Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onOpen, onLongClick = onLongPress),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (cover != null && cover.exists()) {
                AsyncImage(
                    model = coverRequest ?: cover,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = coverModifier
                )
            } else {
                PlaceholderCover(title = shelfBook.book.title, modifier = coverModifier)
            }
            Column(
                Modifier
                    .weight(1f)
                    .padding(start = 12.dp)
            ) {
                Text(
                    (if (selected) "✓ " else "") +
                        shelfBook.book.title +
                        (if (favourite) " ♥" else ""),
                    style = MaterialTheme.typography.titleMedium, maxLines = 1
                )
                // v1.2.0 (WS4): the meta line renders only when
                // at least one fragment has something to say —
                // a "0 chapters" fragment (and an otherwise empty
                // line reserving a row of height) is gone.
                val rowMeta = listOfNotNull(
                    shelfBook.book.author.takeIf { it.isNotBlank() },
                    if (shelfBook.book.totalChapters > 0) {
                        "${shelfBook.book.totalChapters} chapters"
                    } else {
                        null
                    },
                    if (downloaded > 0) {
                        "$downloaded downloaded" +
                            if (unreadDownloaded > 0) " ($unreadDownloaded unread)" else ""
                    } else {
                        null
                    },
                    timeLeftSuffix(shelfBook, wpm).removePrefix(" • ")
                        .takeIf { it.isNotBlank() }
                ).joinToString(" • ")
                if (rowMeta.isNotBlank()) {
                    Text(
                        rowMeta,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (shelfBook.fraction > 0f) {
                    Spacer(Modifier.height(6.dp))
                    ProgressPillBar(
                        fraction = progress,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            if (shelfBook.fraction > 0f) {
                Text(
                    "${(progress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun ImportCandidateCard(
    state: CandidateState,
    onImport: (app.xreader.data.importer.ImportCandidate) -> Unit,
    onDismiss: (app.xreader.data.importer.ImportCandidate) -> Unit,
    modifier: Modifier = Modifier
) {
    val candidate = when (state) {
        is CandidateState.Ready -> state.candidate
        is CandidateState.Importing -> state.candidate
        is CandidateState.Done -> state.candidate
        is CandidateState.Failed -> state.candidate
    }
    GlassCard(modifier = modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    candidate.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                // Nocturne §5.1: the card's chip is its STATUS
                // (Ready / Importing / Done / Failed); the format
                // rides the size line below instead.
                StatusPill(
                    text = when (state) {
                        is CandidateState.Ready -> "Ready"
                        is CandidateState.Importing -> "Importing"
                        is CandidateState.Done -> "Done"
                        is CandidateState.Failed -> "Failed"
                    },
                    color = when (state) {
                        is CandidateState.Failed -> MaterialTheme.colorScheme.error
                        is CandidateState.Done -> MaterialTheme.colorScheme.secondary
                        else -> MaterialTheme.colorScheme.primary
                    }
                )
            }
            Text(
                // Same UNKNOWN rule as the hero chip: an
                // unrecognised format (a Failed candidate) shows
                // its size only, never the word "UNKNOWN".
                listOfNotNull(
                    if (candidate.format !=
                        app.xreader.data.db.BookFormat.UNKNOWN
                    ) {
                        candidate.format.name
                    } else {
                        null
                    },
                    "${candidate.sizeBytes / 1024} KB"
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            when (state) {
                is CandidateState.Ready -> Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    PrimaryPill(
                        text = "Add to library",
                        onClick = { onImport(candidate) },
                        modifier = Modifier,
                        enabled = true,
                    
                    )
                    TextButton(onClick = { onDismiss(candidate) }) { Text("Not now") }
                }
                is CandidateState.Importing -> Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val importProgress = state.progress
                    if (importProgress != null) {
                        ProgressPillBar(
                            fraction = importProgress,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(Modifier.size(20.dp))
                        Text(
                            importProgress?.let { "Reading the book… ${(it * 100).toInt()}%" }
                                ?: "Reading the book…",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
                is CandidateState.Done -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "Added “${state.bookTitle}” to your library",
                        style = MaterialTheme.typography.bodyMedium,
                        color = sageTextColor(),
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { onDismiss(candidate) }) { Text("Dismiss") }
                }
                is CandidateState.Failed -> Column {
                    Text(
                        state.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = errorTextColor()
                    )
                    TextButton(onClick = { onDismiss(candidate) }) { Text("Dismiss") }
                }
            }
        }
    }
}

/**
 * Shelf management (v1.0.2, R21): create manual or smart shelves,
 * rename/reorder/delete user shelves (books are never deleted with
 * a shelf), and show/hide the hideable built-ins. All is permanent.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ManageShelvesDialog(
    state: LibraryUiState,
    onDismiss: () -> Unit,
    onCreate: (String, app.xreader.data.db.SmartRule?, String?) -> Unit,
    onRename: (Long, String) -> Unit,
    onDelete: (Long) -> Unit,
    onMove: (Long, Boolean) -> Unit,
    onBuiltinHidden: (String, Boolean) -> Unit
) {
    var newName by remember { mutableStateOf("") }
    var newRule by remember { mutableStateOf<app.xreader.data.db.SmartRule?>(null) }
    var newRuleValue by remember { mutableStateOf("") }
    var ruleMenuOpen by remember { mutableStateOf(false) }
    var renamingId by remember { mutableStateOf<Long?>(null) }
    var renameText by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<app.xreader.data.db.Shelf?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Shelves") },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("New shelf", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("Shelf name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Box {
                    TextButton(onClick = { ruleMenuOpen = true }) {
                        Text(
                            "Fills itself: " + when (newRule) {
                                null -> "No — I add books myself"
                                app.xreader.data.db.SmartRule.TAG -> "By tag"
                                app.xreader.data.db.SmartRule.UPDATED_THIS_WEEK ->
                                    "Updated this week"
                                app.xreader.data.db.SmartRule.UNREAD_DOWNLOADS ->
                                    "Unread downloads"
                                app.xreader.data.db.SmartRule.DOWNLOADED -> "Downloaded"
                                app.xreader.data.db.SmartRule.READING_STATUS ->
                                    "By reading status"
                            }
                        )
                    }
                    DropdownMenu(
                        expanded = ruleMenuOpen,
                        onDismissRequest = { ruleMenuOpen = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("No — I add books myself") },
                            onClick = { newRule = null; ruleMenuOpen = false }
                        )
                        DropdownMenuItem(
                            text = { Text("By tag") },
                            onClick = {
                                newRule = app.xreader.data.db.SmartRule.TAG
                                ruleMenuOpen = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Updated this week") },
                            onClick = {
                                newRule = app.xreader.data.db.SmartRule.UPDATED_THIS_WEEK
                                ruleMenuOpen = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Unread downloads") },
                            onClick = {
                                newRule = app.xreader.data.db.SmartRule.UNREAD_DOWNLOADS
                                ruleMenuOpen = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Downloaded") },
                            onClick = {
                                newRule = app.xreader.data.db.SmartRule.DOWNLOADED
                                ruleMenuOpen = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("By reading status") },
                            onClick = {
                                newRule = app.xreader.data.db.SmartRule.READING_STATUS
                                ruleMenuOpen = false
                            }
                        )
                    }
                }
                if (newRule == app.xreader.data.db.SmartRule.TAG) {
                    OutlinedTextField(
                        value = newRuleValue,
                        onValueChange = { newRuleValue = it },
                        label = { Text("Tag (e.g. Male MC)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (newRule == app.xreader.data.db.SmartRule.READING_STATUS) {
                    OutlinedTextField(
                        value = newRuleValue,
                        onValueChange = { newRuleValue = it },
                        label = { Text("Status (READING, COMPLETED, ON_HOLD, DROPPED)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                PrimaryPill(
                    text = "Add shelf",
                    onClick = {
                        if (newName.isNotBlank()) {
                            val value = when (newRule) {
                                app.xreader.data.db.SmartRule.TAG ->
                                    app.xreader.data.library.TagRegistry
                                        .slugFor(newRuleValue)
                                app.xreader.data.db.SmartRule.READING_STATUS ->
                                    newRuleValue.trim().uppercase()
                                else -> null
                            }
                            onCreate(newName, newRule, value)
                            newName = ""
                            newRule = null
                            newRuleValue = ""
                        }
                    },
                    modifier = Modifier,
                    enabled = true,
                
                )

                Text("Built-in shelves", style = MaterialTheme.typography.titleSmall)
                listOf(
                    app.xreader.data.library.ShelfLogic.BUILTIN_FAVOURITES to "Favourites",
                    app.xreader.data.library.ShelfLogic.BUILTIN_DOWNLOADED to "Downloaded",
                    app.xreader.data.library.ShelfLogic.BUILTIN_BEHIND_ON to "Behind on"
                ).forEach { (key, label) ->
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(label, Modifier.weight(1f))
                        NocturneSwitch(
                            checked = key !in state.hiddenBuiltins,
                            onCheckedChange = { shown -> onBuiltinHidden(key, !shown) },
                            modifier = Modifier,
                            enabled = true,
                        
                        )
                    }
                }
                Text(
                    "“All” always stays — it is your whole library.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (state.userShelves.isNotEmpty()) {
                    Text("Your shelves", style = MaterialTheme.typography.titleSmall)
                    state.userShelves.forEach { shelf ->
                        Column {
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    shelf.name +
                                        if (shelf.kind ==
                                            app.xreader.data.db.ShelfKind.SMART
                                        ) {
                                            " (smart)"
                                        } else {
                                            ""
                                        },
                                    Modifier.weight(1f)
                                )
                                TextButton(onClick = { onMove(shelf.id, true) }) {
                                    Text("↑")
                                }
                                TextButton(onClick = { onMove(shelf.id, false) }) {
                                    Text("↓")
                                }
                                TextButton(onClick = {
                                    renamingId = shelf.id
                                    renameText = shelf.name
                                }) { Text("Rename") }
                                TextButton(onClick = { deleteTarget = shelf }) {
                                    Text("Delete")
                                }
                            }
                            if (renamingId == shelf.id) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedTextField(
                                        value = renameText,
                                        onValueChange = { renameText = it },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f)
                                    )
                                    TextButton(onClick = {
                                        onRename(shelf.id, renameText)
                                        renamingId = null
                                    }) { Text("Save") }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        }
    )

    deleteTarget?.let { shelf ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete shelf “${shelf.name}”?") },
            text = {
                Text("The shelf goes away. Your books stay in the library.")
            },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(shelf.id)
                    deleteTarget = null
                }) { Text("Delete shelf") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Keep it") }
            }
        )
    }
}
