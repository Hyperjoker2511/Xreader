@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package app.xreader.ui.library
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.PlaylistRemove
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.xreader.core.motion.animateProgressAsState
import app.xreader.core.motion.itemEnter
import app.xreader.core.motion.itemExit
import app.xreader.core.motion.listPlacementSpec
import app.xreader.core.motion.pressBounce
import app.xreader.ui.common.DialogDangerOption
import app.xreader.ui.common.EmptyState
import app.xreader.ui.common.GlassCard
import app.xreader.ui.common.GlassSheetActionRow
import app.xreader.ui.common.pressTilt
import app.xreader.ui.common.ScreenHeader
import app.xreader.ui.common.StatusPill
import app.xreader.ui.common.XreaderPrimaryButton
import coil.compose.AsyncImage
import java.io.File

/**
 * Library (Stitch design, PLAN §4): Continue Reading hero, then the
 * shelf with per-book progress. The header carries the ONE add action;
 * the import sheet (below the list) confirms each picked file with its
 * real format before anything is parsed.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
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
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        ScreenHeader(
            title = "Your library",
            eyebrow = "Xreader",
            action = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Design #1: the profile avatar lives in the
                    // Library header and opens Profile & progress.
                    // v1.0.4 (D5/D9): the bubble takes the Apple
                    // bounce press, like the mock's glass Lv bubble.
                    val avatarInteraction = remember {
                        androidx.compose.foundation.interaction.MutableInteractionSource()
                    }
                    androidx.compose.material3.Surface(
                        onClick = onProfile,
                        interactionSource = avatarInteraction,
                        shape = androidx.compose.foundation.shape.CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier
                            .size(44.dp)
                            .pressBounce(avatarInteraction)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            val avatar = state.avatarFile
                            if (avatar != null) {
                                AsyncImage(
                                    model = coverFile(avatar),
                                    contentDescription = "Your profile",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                Text(
                                    state.profileName.trim().firstOrNull()
                                        ?.uppercase() ?: "Lv",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    }
                    XreaderPrimaryButton(text = "+ Add a book", onClick = onAddBook)
                }
            }
        )
        Spacer(Modifier.height(16.dp))

        // v1.0.4 (D3): the empty decision is the pure predicate in
        // LibraryEmptyState (unit-tested), and when it fires the
        // empty state OWNS the screen — search, toolbar and chips
        // never render over it. The D13 hover drift is perf-gated:
        // full motion tier only, never under reduce-motion.
        val context = androidx.compose.ui.platform.LocalContext.current
        val totalRamMb = remember(context) {
            runCatching {
                val am = context.getSystemService(android.content.Context.ACTIVITY_SERVICE)
                    as android.app.ActivityManager
                val info = android.app.ActivityManager.MemoryInfo()
                am.getMemoryInfo(info)
                info.totalMem / (1024L * 1024L)
            }.getOrDefault(4096L)
        }
        val hoverEmpty = !reduceMotion &&
            app.xreader.core.motion.SpringMotionEngine.tierFor(
                totalRamMb, animationsOn = true
            ) != app.xreader.core.motion.SpringMotionEngine.MotionTier.Minimal
        if (app.xreader.data.library.LibraryEmptyState.show(
                loading = state.loading,
                bookCount = state.books.size,
                candidateCount = candidates.size
            )
        ) {
            EmptyState(
                title = "No books yet",
                message = "Add your first book — EPUB, PDF, TXT and more — " +
                    "and it will live here, ready to read.",
                actionLabel = "Add a book",
                onAction = onAddBook,
                modifier = Modifier.weight(1f),
                hover = hoverEmpty
            )
            return@Column
        }

        // The Library's chrome (v1.0.5, C1): header, search, ONE
        // chip row, then books — nothing else stands between the
        // reader and their shelves.
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search your library") },
            placeholder = { Text("Title or author") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        // v1.0.5 (C1/D20): the Library decluttered to ONE chip row.
        // History and Updates left the toolbar — they are bottom-
        // nav tabs already. The shelf rail below is the only row:
        // a Filter chip first (status + sort + Check for updates
        // live in its sheet), then the shelves, then "+ Shelf" and
        // "+ Add a list" trailing. The row scrolls sideways, so
        // nothing can crush here either (D2's property, kept).
        Spacer(Modifier.height(10.dp))
        androidx.compose.foundation.lazy.LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item(key = "filter") {
                val filterInteraction = remember {
                    androidx.compose.foundation.interaction.MutableInteractionSource()
                }
                val activeStatus = statusOptions.firstOrNull {
                    it.first == statusFilter && statusFilter != "ALL"
                }
                FilterChip(
                    selected = statusFilter != "ALL",
                    onClick = { filterSheetOpen = true },
                    label = {
                        Text(
                            if (activeStatus != null) {
                                "Filter · ${activeStatus.second}"
                            } else {
                                "Filter"
                            }
                        )
                    },
                    interactionSource = filterInteraction,
                    modifier = Modifier.pressBounce(filterInteraction)
                )
            }
            items(shelfChips, key = { "shelf-${it.ref}" }) { chip ->
                val chipInteraction = remember {
                    androidx.compose.foundation.interaction.MutableInteractionSource()
                }
                FilterChip(
                    selected = state.selectedShelf == chip.ref,
                    onClick = { viewModel.selectShelf(chip.ref) },
                    label = { Text("${chip.name} · ${chip.count}") },
                    interactionSource = chipInteraction,
                    modifier = Modifier.pressBounce(chipInteraction)
                )
            }
            item(key = "shelf-add") {
                val addInteraction = remember {
                    androidx.compose.foundation.interaction.MutableInteractionSource()
                }
                FilterChip(
                    selected = false,
                    onClick = { manageShelvesOpen = true },
                    label = { Text("+ Shelf") },
                    interactionSource = addInteraction,
                    modifier = Modifier.pressBounce(addInteraction)
                )
            }
            item(key = "add-list") {
                val listInteraction = remember {
                    androidx.compose.foundation.interaction.MutableInteractionSource()
                }
                FilterChip(
                    selected = false,
                    onClick = onOpenAddList,
                    label = { Text("+ Add a list") },
                    interactionSource = listInteraction,
                    modifier = Modifier.pressBounce(listInteraction)
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
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
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
            verticalArrangement = Arrangement.spacedBy(12.dp)
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
                    Text(
                        "For you — from tags you follow",
                        style = MaterialTheme.typography.titleMedium
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
                                if (cover != null) {
                                    AsyncImage(
                                        model = cover,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .size(width = 96.dp, height = 136.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                    )
                                } else {
                                    PlaceholderCover(
                                        title = book.title,
                                        modifier = Modifier
                                            .size(width = 96.dp, height = 136.dp)
                                            .clip(RoundedCornerShape(10.dp))
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
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Adding books", style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = { viewModel.clearFinishedCandidates() }) {
                            Text("Clear finished")
                        }
                    }
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
                    Text(
                        if (query.isBlank() && statusFilter == "ALL") "Recent books"
                        else "${visibleBooks.size} book(s)",
                        style = MaterialTheme.typography.titleMedium
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
                                modifier = Modifier.weight(
                                    if (bigFirst && index == 0) 2f else 1f
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
                    GlassSheetActionRow(
                        icon = Icons.Filled.MenuBook,
                        label = "Open",
                        onClick = {
                            quickActionsFor = null
                            onBookClick(target)
                        }
                    )
                    GlassSheetActionRow(
                        icon = if (isFavourite) {
                            Icons.Filled.Favorite
                        } else {
                            Icons.Filled.FavoriteBorder
                        },
                        label = if (isFavourite) {
                            "Remove from Favourites"
                        } else {
                            "Add to Favourites"
                        },
                        onClick = {
                            viewModel.setFavourite(target.book.id, !isFavourite)
                            quickActionsFor = null
                        }
                    )
                    GlassSheetActionRow(
                        icon = Icons.Filled.PlaylistAdd,
                        label = "Add to shelf…",
                        onClick = {
                            shelfPickerFor = setOf(target.book.id)
                            quickActionsFor = null
                        }
                    )
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
                        icon = Icons.Filled.CheckBox,
                        label = "Select",
                        onClick = {
                            selection = setOf(target.book.id)
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
                    Text(
                        "Reading status",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        statusOptions.forEach { (value, label) ->
                            val chipInteraction = remember {
                                androidx.compose.foundation.interaction
                                    .MutableInteractionSource()
                            }
                            FilterChip(
                                selected = statusFilter == value,
                                onClick = { statusFilter = value },
                                label = { Text(label) },
                                interactionSource = chipInteraction,
                                modifier = Modifier.pressBounce(chipInteraction)
                            )
                        }
                    }
                    Text(
                        "Sort by",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        app.xreader.data.library.LibrarySort.entries.forEach { option ->
                            val chipInteraction = remember {
                                androidx.compose.foundation.interaction
                                    .MutableInteractionSource()
                            }
                            FilterChip(
                                selected = effectiveSort == option,
                                onClick = {
                                    sort = option
                                    // R21: a user shelf remembers its sort.
                                    selectedUserShelf?.let {
                                        viewModel.setShelfSort(it.id, option)
                                    }
                                },
                                label = { Text(sortLabel(option)) },
                                interactionSource = chipInteraction,
                                modifier = Modifier.pressBounce(chipInteraction)
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

/** R19: a cover is never a blank rectangle — a book without cover
 * art gets a typographic placeholder in its own deterministic
 * palette colour with its initials. */
@Composable
internal fun PlaceholderCover(title: String, modifier: Modifier = Modifier) {
    val palette = remember(title) {
        app.xreader.ui.theme.CoverPaletteExtractor.placeholderPalette(title)
    }
    val base = palette.darkVibrant ?: palette.dominant
        ?: MaterialTheme.colorScheme.surfaceVariant
    Box(
        modifier
            .background(base),
        contentAlignment = Alignment.Center
    ) {
        Text(
            title.split(" ").mapNotNull { it.firstOrNull()?.uppercase() }
                .take(2).joinToString(""),
            style = MaterialTheme.typography.headlineMedium,
            color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.9f)
        )
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
    GlassCard(modifier = modifier) {
        Column(
            Modifier
                .combinedClickable(onClick = onOpen, onLongClick = onLongPress)
                // R30: covers lean toward the touch, subtly.
                .pressTilt(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                if (coverGlow && glowColor != null) {
                    Modifier.drawBehind {
                        drawCircle(
                            brush = androidx.compose.ui.graphics.Brush.radialGradient(
                                listOf(
                                    glowColor.copy(alpha = 0.55f),
                                    androidx.compose.ui.graphics.Color.Transparent
                                ),
                                radius = size.maxDimension * 0.8f
                            ),
                            radius = size.maxDimension * 0.8f
                        )
                    }
                } else {
                    Modifier
                }
            ) {
                if (cover != null && cover.exists()) {
                    AsyncImage(
                        model = cover,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(150.dp)
                    )
                } else {
                    PlaceholderCover(
                        title = shelfBook.book.title,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(150.dp)
                    )
                }
                if (newChapters > 0) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                            .padding(horizontal = 7.dp, vertical = 2.dp)
                    ) {
                        Text(
                            "+$newChapters",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
                if (favourite || selected == true) {
                    Box(
                        Modifier
                            .align(Alignment.TopStart)
                            .padding(6.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            if (selected == true) "✓" else "♥",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
            Text(
                shelfBook.book.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2
            )
            Text(
                "${shelfBook.book.totalChapters} chapters",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (downloaded > 0) {
                Text(
                    "$downloaded/${shelfBook.book.totalChapters} downloaded" +
                        if (unreadDownloaded > 0) " · $unreadDownloaded unread" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (showProgress && shelfBook.fraction > 0f) {
                LinearProgressIndicator(
                    progress = { shelfBook.fraction },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
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
    onContinue: () -> Unit
) {
    val progress = animateProgressAsState(hero.fraction, label = "heroProgress")
    // v1.0.4 (D7): the showcase stage from the user's Liquid Glass
    // demo — his trio (cyan → purple → pink) as a gradient stage
    // behind the glass card. Under FROSTED the card really blurs
    // the gradient; under TINTED/SOLID it reads as a slim vibrant
    // frame. The whole card is the Continue action now, with the
    // Apple bounce press; the button inside still works too.
    val glass = app.xreader.ui.theme.LocalGlassSettings.current
    Box(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .matchParentSize()
                .clip(RoundedCornerShape(glass.cardRadius + 4.dp))
                .background(
                    androidx.compose.ui.graphics.Brush.linearGradient(
                        listOf(
                            app.xreader.ui.theme.VibrantCyan.copy(alpha = 0.85f),
                            app.xreader.ui.theme.ElectricPurple.copy(alpha = 0.85f),
                            app.xreader.ui.theme.ApplePink.copy(alpha = 0.85f)
                        )
                    )
                )
        )
        GlassCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(3.dp),
            onClick = onContinue,
            bouncePress = true
        ) {
        Row(Modifier.fillMaxWidth()) {
            AsyncImage(
                model = coverFile(hero.book.coverPath),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(width = 76.dp, height = 110.dp)
            )
            Column(
                Modifier
                    .weight(1f)
                    .padding(start = 16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                StatusPill(
                    text = hero.book.localFormat.name,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(hero.book.title, style = MaterialTheme.typography.titleLarge, maxLines = 2)
                if (hero.book.author.isNotBlank()) {
                    Text(
                        hero.book.author,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                Text(
                    "${(progress * 100).toInt()}% • ${hero.book.totalChapters} chapters" +
                        timeLeftSuffix(hero, wpm),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                XreaderPrimaryButton(text = "Continue", onClick = onContinue)
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
    val coverModifier = if (compact) {
        Modifier.size(width = 32.dp, height = 45.dp)
    } else {
        Modifier.size(width = 44.dp, height = 62.dp)
    }
    GlassCard(modifier = modifier.fillMaxWidth(), onClick = onOpen) {
        Row(
            Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onOpen, onLongClick = onLongPress),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (cover != null && cover.exists()) {
                AsyncImage(
                    model = cover,
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
                Text(
                    listOfNotNull(
                        shelfBook.book.author.takeIf { it.isNotBlank() },
                        "${shelfBook.book.totalChapters} chapters",
                        if (downloaded > 0) {
                            "$downloaded downloaded" +
                                if (unreadDownloaded > 0) " ($unreadDownloaded unread)" else ""
                        } else {
                            null
                        },
                        timeLeftSuffix(shelfBook, wpm).removePrefix(" • ")
                            .takeIf { it.isNotBlank() }
                    ).joinToString(" • "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (shelfBook.fraction > 0f) {
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { progress },
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
                StatusPill(
                    text = candidate.format.name,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Text(
                "${candidate.sizeBytes / 1024} KB",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            when (state) {
                is CandidateState.Ready -> Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    XreaderPrimaryButton(
                        text = "Add to library",
                        onClick = { onImport(candidate) }
                    )
                    TextButton(onClick = { onDismiss(candidate) }) { Text("Not now") }
                }
                is CandidateState.Importing -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CircularProgressIndicator(Modifier.size(20.dp))
                    Text(
                        state.progress?.let { "Reading the book… ${(it * 100).toInt()}%" }
                            ?: "Reading the book…",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                is CandidateState.Done -> Text(
                    "Added “${state.bookTitle}” to your library",
                    style = MaterialTheme.typography.bodyMedium,
                    color = sageTextColor()
                )
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
                XreaderPrimaryButton(
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
                    }
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
                        Switch(
                            checked = key !in state.hiddenBuiltins,
                            onCheckedChange = { shown -> onBuiltinHidden(key, !shown) }
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
