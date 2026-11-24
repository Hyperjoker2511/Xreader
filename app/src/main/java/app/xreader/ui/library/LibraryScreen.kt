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
import app.xreader.ui.theme.LocalDockClearance
import app.xreader.ui.theme.LocalAppSize
import app.xreader.ui.theme.LocalUiMetrics
import app.xreader.ui.theme.effectiveBottomPadding
import app.xreader.ui.theme.errorTextColor
import app.xreader.ui.theme.sageTextColor

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.togetherWith
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
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.PlaylistRemove
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.xreader.core.motion.animateProgressAsState
import app.xreader.core.motion.itemEnter
import app.xreader.core.motion.sideSwitchEnter
import app.xreader.core.motion.sideSwitchExit
import app.xreader.core.motion.itemExit
import app.xreader.core.motion.listPlacementSpec
import app.xreader.core.motion.pressBounce
import app.xreader.ui.common.DialogDangerOption
import app.xreader.ui.common.GlassCard
import app.xreader.ui.common.GlassSheetActionRow
import app.xreader.ui.common.KitEmptyState
import app.xreader.ui.common.LoadingState
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
    /** Visible-layer fix round (§E): the filter sheet's
     * "New chapters" row — opens the updates page. */
    onOpenUpdates: () -> Unit = {},
    coverGlow: Boolean = false,
    /** Code 122 (S36): the update card's state + doors, hosted
     * IN the M3 Library's content flow (above the Cover Story)
     * instead of the retired overlay. Null (the default) =
     * no card; the shell wires the real AppUpdateViewModel. */
    updateState: app.xreader.ui.update.AppUpdateViewModel.UiState? = null,
    onUpdateNow: () -> Unit = {},
    onDismissUpdate: () -> Unit = {},
    onReadMoreUpdate: () -> Unit = {},
    onInstallUpdate: () -> Unit = {},
    onCancelUpdateDownload: () -> Unit = {},
    onOpenUpdateInstallSettings: () -> Unit = {},
    /** Nav-bar styles (v1.6.2): the device performance tier the
     * shell resolved — the Novels↔Manga switch's indicator
     * travel shortens on MINIMAL, like the dock's. */
    indicatorPerfTier: app.xreader.data.prefs.PerfTier =
        app.xreader.data.prefs.PerfTier.FULL
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val candidates by viewModel.candidates.collectAsStateWithLifecycle()
    // Code 120: the Material 3 design's Library knobs (§2.11)
    // gate their Nocturne-era equivalents ONLY in the M3
    // design — under Nocturne every condition below is
    // vacuously true and the screen is byte-identical.
    val designState = app.xreader.ui.theme.LocalDesignState.current
    val m3Design = designState.design ==
        app.xreader.data.prefs.AppDesign.MATERIAL3
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
    // v1.5.34 (item 2): the permanent-warning step + context the
    // delete sheet needed moved INTO the shared
    // BookDeleteDialogs with the sheet itself.
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
    // Manga programme (M3): the split comes FIRST — every
    // shelf fact, chip count, membership and list below is
    // built from the ACTIVE SIDE's books only, so the Novels
    // page and the Manga page are each complete little
    // libraries of their own kind.
    val sideBooks = remember(state.books, state.bookKinds, state.librarySide) {
        app.xreader.data.library.LibrarySplit.onSide(
            state.books, state.librarySide
        ) { shelf ->
            app.xreader.data.library.LibrarySplit
                .kindOf(shelf.book.id, state.bookKinds)
        }
    }
    val facts = remember(
        sideBooks, state.metas, state.favourites, state.bookTags, state.downloadCounts
    ) { state.shelfFactsFor(sideBooks, nowMillis) }
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
        sideBooks, state.metas, query, effectiveSort, statusFilter, memberFilter
    ) {
        val inShelf = if (memberFilter == null) {
            sideBooks
        } else {
            sideBooks.filter { it.book.id in memberFilter }
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

    // Adaptive metrics (Patch 5, spec correction): the cover
    // grid's column count. An explicit Customise choice always
    // wins (gridColumnsAuto == false — the user tapped a chip);
    // a user who never chose gets the width tier's count for the
    // phone in hand. Only the RESOLVED count changes — the
    // chunked-row mechanics below are untouched, and the bento
    // layout keeps its own fixed 3. v1.6.2: the NOVELS side in
    // Auto resolves its count from the Cover size instead (see
    // sideGridColumns inside the side lambda); this outer value
    // still carries the explicit pick for both sides and the
    // tier count for the Manga side.
    val uiMetrics = LocalUiMetrics.current
    val gridColumns = if (state.libraryPrefs.gridColumnsAuto) {
        uiMetrics.gridColumns
    } else {
        state.libraryPrefs.gridColumns
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

    // Code 121 (R1/S4): the Material 3 design's Library is a
    // separate composition (M3LibraryContent, below) — medium
    // flexible bar, Continue carousel, status PrimaryTabRow,
    // wide filled shelf cards, the S4 sheet. The Nocturne
    // composition below is byte-identical to before this build.
    if (m3Design) {
        M3LibraryContent(
            state = state,
            candidates = candidates,
            viewModel = viewModel,
            coverFile = coverFile,
            designState = designState,
            onAddBook = onAddBook,
            onOpenBook = onOpenBook,
            onContinueReading = onContinueReading,
            onOpenAddList = onOpenAddList,
            onOpenUpdates = onOpenUpdates,
            updateState = updateState,
            onUpdateNow = onUpdateNow,
            onDismissUpdate = onDismissUpdate,
            onReadMoreUpdate = onReadMoreUpdate,
            onInstallUpdate = onInstallUpdate,
            onCancelUpdateDownload = onCancelUpdateDownload,
            onOpenUpdateInstallSettings = onOpenUpdateInstallSettings
        )
        return
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(start = 20.dp, end = 20.dp, top = LocalUiMetrics.current.topGap, bottom = 10.dp)
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
        // Manga programme (M3): the Novels | Manga switch at
        // the TOP of the Library — two pages, one tap apart,
        // never a mixed grid. Each side's label carries its
        // new-chapter count from the library-update pass, so
        // "what's new" on the other side is one glance away.
        if (!app.xreader.data.library.LibraryEmptyState.show(
                loading = state.loading,
                bookCount = state.books.size,
                candidateCount = candidates.size
            )
        ) {
            val newBySide = remember(state.metas, state.bookKinds) {
                app.xreader.data.library.LibrarySplit
                    .newChaptersBySide(state.metas, state.bookKinds)
            }
            Spacer(Modifier.height(10.dp))
            app.xreader.ui.common.MediaKindSwitch(
                side = state.librarySide,
                novelsLabel = app.xreader.data.library.LibrarySplit.switchLabel(
                    app.xreader.data.extension.MediaKind.TEXT,
                    newBySide[app.xreader.data.extension.MediaKind.TEXT] ?: 0
                ),
                mangaLabel = app.xreader.data.library.LibrarySplit.switchLabel(
                    app.xreader.data.extension.MediaKind.MANGA,
                    newBySide[app.xreader.data.extension.MediaKind.MANGA] ?: 0
                ),
                onSelect = { viewModel.setLibrarySide(it) },
                // Nav-bar styles (v1.6.2): the switch's pill is a
                // 2-stop indicator in the dock's language — the
                // user's style + colour, the tier's travel.
                indicatorStyle = state.libraryPrefs.navIndicatorStyle,
                indicatorColour = if (
                    state.libraryPrefs.navIndicatorColourMode ==
                    app.xreader.data.library.NavIndicatorColourMode.CUSTOM
                ) {
                    androidx.compose.ui.graphics.Color(
                        state.libraryPrefs.navIndicatorColourArgb.toInt()
                    )
                } else {
                    androidx.compose.ui.graphics.Color.Unspecified
                },
                perfTier = indicatorPerfTier,
                reduceMotion = reduceMotion
            )
        }
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

        // v1.5.8 (J1/J15): the first load with an empty shelf
        // used to render silent chrome over nothing — the state
        // kit says what is happening while the shelf loads.
        if (state.loading && state.books.isEmpty() && candidates.isEmpty()) {
            LoadingState(
                whatIsHappening = "Loading your library…",
                modifier = Modifier.weight(1f)
            )
            return@Column
        }

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
            if (!m3Design || designState.libraryFilterRow) {
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

        // Visible-layer fix round (§D.3): the CONTENT REGION
        // glides between the Novels and Manga sides — slide +
        // fade at the tab switch's own numbers (width/5,
        // TAB_SWITCH_MS), direction from the pure
        // LibrarySplit.switchDirection. The header, the switch,
        // the add row and the chip rail above stay fixed: the
        // control you tapped must not move. Each side keeps its
        // own scroll position (two list states), so flipping
        // back returns you to where you were on that side.
        val novelsListState = rememberLazyListState()
        val mangaListState = rememberLazyListState()
        AnimatedContent(
            targetState = state.librarySide,
            modifier = Modifier.weight(1f),
            transitionSpec = {
                val dir = app.xreader.data.library.LibrarySplit
                    .switchDirection(initialState, targetState)
                sideSwitchEnter(dir, reduceMotion).togetherWith(
                    sideSwitchExit(dir, reduceMotion)
                ).using(SizeTransform(clip = true))
            },
            label = "librarySide"
        ) { side ->
            // The lambda's side — NOT state.librarySide: during
            // the flight both sides exist at once, and each must
            // render its OWN books. The derivations below mirror
            // the outer ones (which stay for the chip rail and
            // the switch labels), keyed on this side.
            val sideBooks = remember(state.books, state.bookKinds, side) {
                app.xreader.data.library.LibrarySplit.onSide(
                    state.books, side
                ) { shelf ->
                    app.xreader.data.library.LibrarySplit
                        .kindOf(shelf.book.id, state.bookKinds)
                }
            }
            val sideFacts = remember(
                sideBooks, state.metas, state.favourites,
                state.bookTags, state.downloadCounts
            ) { state.shelfFactsFor(sideBooks, nowMillis) }
            val memberFilter = remember(
                state.selectedShelf, sideFacts,
                state.userShelves, state.memberships
            ) {
                app.xreader.data.library.ShelfLogic.membersFor(
                    state.selectedShelf, sideFacts,
                    state.userShelves, state.memberships, nowMillis
                )
            }
            val visibleBooks = remember(
                sideBooks, state.metas, query, effectiveSort,
                statusFilter, memberFilter
            ) {
                val inShelf = if (memberFilter == null) {
                    sideBooks
                } else {
                    sideBooks.filter { it.book.id in memberFilter }
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
                        searchQuery = query, sort = effectiveSort,
                        statusFilter = statusFilter
                    )
                )
                val byId = inShelf.associateBy { it.book.id }
                ordered.mapNotNull { byId[it.bookId] }
            }
            // Cover size (v1.6.2): the NOVELS grid's column
            // count. An explicit column pick still wins (the
            // outer gridColumns carries it, for both sides);
            // in Auto the Novels side resolves its count from
            // the Cover size — the target cover width fitted
            // to the screen, allowing for this screen's own
            // 20 dp side padding. The Manga side keeps the
            // standing resolution untouched.
            val appSize = LocalAppSize.current
            val screenWidthDp = LocalConfiguration.current.screenWidthDp
            val sideGridColumns = if (
                side == app.xreader.data.extension.MediaKind.TEXT &&
                state.libraryPrefs.gridColumnsAuto
            ) {
                app.xreader.data.library.CoverSizeLogic.resolveColumns(
                    availableWidthDp = screenWidthDp - 40,
                    size = state.libraryPrefs.coverSize,
                    appSize = appSize
                )
            } else {
                gridColumns
            }
        // v1.0.2 (R20): pull to refresh — silent; the results are
        // read on the Updates page and the +N badges.
        androidx.compose.material3.pulltorefresh.PullToRefreshBox(
            isRefreshing = state.pullRefreshing,
            onRefresh = { viewModel.pullRefresh() },
            modifier = Modifier.fillMaxSize()
        ) {
        LazyColumn(
            state = if (side ==
                app.xreader.data.extension.MediaKind.MANGA
            ) {
                mangaListState
            } else {
                novelsListState
            },
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(
                bottom = effectiveBottomPadding(
                    88.dp, LocalDockClearance.current
                )
            )
        ) {
            // v1.5.3 (§5.3, CONFIRMED; finding #9): the Continue
            // hero belongs to the whole library — it renders ONLY
            // when the All shelf is selected, never over a
            // filtered shelf (Favourites, a status shelf, …).
            (state.continueReading.takeIf {
                state.libraryPrefs.showContinueReading &&
                    state.selectedShelf == "all" &&
                    (!m3Design || designState.libraryContinueRail)
            })?.let { hero ->
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
                                    ?.takeIf { it.exists() }
                                val coverRequest =
                                    rememberSizedCover(cover, 96, 136)
                                if (cover != null ||
                                    !book.coverUrl.isNullOrBlank()
                                ) {
                                    AsyncImage(
                                        model = coverRequest ?: cover
                                        ?: shelfBook.book.coverUrl?.takeIf { it.isNotBlank() },
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
                        onPassword = { cand, pw -> viewModel.importCandidate(cand, pw) },
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
            } else if (sideBooks.isEmpty() && candidates.isEmpty()) {
                // M3: this SIDE is empty (the library itself
                // isn't) — say which side and where its books
                // come from, in plain words.
                item(key = "side-empty") {
                    Text(
                        if (side ==
                            app.xreader.data.extension.MediaKind.MANGA
                        ) {
                            "No manga here yet. Manga lives in " +
                                "Explore — open the Keiyoushi " +
                                "catalogue, add a series, and it " +
                                "will live on this side."
                        } else {
                            "No novels on this side — your books " +
                                "are waiting under Manga."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else if (memberFilter != null && candidates.isEmpty()) {
                item(key = "shelf-empty") {
                    Text(
                        when {
                            state.selectedShelf ==
                                app.xreader.data.library.ShelfLogic.BUILTIN_BEHIND_ON ->
                                "You're all caught up — nothing here is behind."
                            // v1.5.3 (item 27, finding #9 strand
                            // A1): "Add to shelf" writes MANUAL
                            // shelves and can never populate
                            // Favourites — name the act that works.
                            state.selectedShelf ==
                                app.xreader.data.library.ShelfLogic.BUILTIN_FAVOURITES ->
                                "Nothing here yet. Open a book and " +
                                    "tap Favourite to keep it here."
                            // v1.5.3 (item 30): status shelves —
                            // name the status and where it is set.
                            app.xreader.data.library.ShelfLogic
                                .statusForBuiltin(state.selectedShelf) != null ->
                                "Nothing here yet. Mark a book as " +
                                    app.xreader.data.library.ShelfLogic
                                        .builtinName(state.selectedShelf) +
                                    " from its book page."
                            else ->
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
                    visibleBooks.chunked(sideGridColumns),
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
                        repeat(sideGridColumns - rowBooks.size) {
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
                    // Visible-layer fix round (§E): the updates
                    // PAGE gets its door — until now the route had
                    // no navigate call site anywhere (the audit's
                    // one fully-dead feature). The caption is the
                    // live count from the same metas the Updates
                    // page itself reads; the row is always
                    // present, even at zero — a door that vanishes
                    // when empty teaches nothing.
                    val newChapterCounts = state.metas.values
                        .mapNotNull {
                            it.newChapterCount.takeIf { n -> n > 0 }
                        }
                    GlassSheetActionRow(
                        icon = Icons.Filled.NewReleases,
                        label = "New chapters",
                        caption = if (newChapterCounts.isNotEmpty()) {
                            "${newChapterCounts.sum()} new across " +
                                "${newChapterCounts.size} book(s)"
                        } else {
                            "Nothing new right now"
                        },
                        onClick = {
                            filterSheetOpen = false
                            onOpenUpdates()
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

        // v1.5.34 (item 2): the delete sheet is the shared
        // BookDeleteDialogs (ui/common) — the same composable
        // History's rows open, so both surfaces offer the one
        // sheet with the one deletion logic. The handlers stay
        // this ViewModel's own.
        app.xreader.ui.common.BookDeleteDialogs(
            book = removeTarget?.book,
            downloadedSizeBytes = { viewModel.downloadedSizeBytes(it) },
            onDismiss = { removeTarget = null },
            onRemoveBook = { book, deleteFiles ->
                viewModel.removeBook(book, deleteFiles)
            },
            onRemoveLocalBook = { book, mode, context ->
                viewModel.removeLocalBook(book, mode, context)
            }
        )
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
        progressFraction = if (showProgress &&
            (app.xreader.ui.theme.LocalDesignState.current.design !=
                app.xreader.data.prefs.AppDesign.MATERIAL3 ||
                app.xreader.ui.theme.LocalDesignState.current
                    .libraryProgressOnCovers)
        ) {
            shelfBook.fraction
        } else {
            null
        },
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
    is CandidateState.PasswordNeeded -> candidate.token
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
                    model = heroCoverRequest ?: coverFile(hero.book.coverPath)
                        ?: hero.book.coverUrl?.takeIf { it.isNotBlank() },
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
                    // v1.7 OE-4 patch (Fix B): a PDF hero's place
                    // is a page, never a fabricated chapter.
                    text = if (
                        hero.book.localFormat ==
                        app.xreader.data.db.BookFormat.PDF
                    ) {
                        "Continue · Page ${continueChapterIndex + 1}"
                    } else {
                        "Continue · Ch. ${continueChapterIndex + 1}"
                    },
                    onClick = onContinue,
                    modifier = Modifier,
                    enabled = true
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
    // v1.5.7 (scope F smoothness): the cover's on-disk existence
    // is probed ONCE per cover file, remembered — this row used
    // to call File.exists() three times per recomposition (a
    // filesystem stat on the main thread, per visible row, on
    // every progress/download tick that recomposed the list).
    val coverOnDisk = remember(cover) { cover?.exists() == true }
    val coverFileOrNull = if (coverOnDisk) cover else null
    // v1.2.0 (WS4): decode the row cover near its drawn size.
    val coverRequest = rememberSizedCover(
        coverFileOrNull,
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
            if (coverOnDisk ||
                !shelfBook.book.coverUrl.isNullOrBlank()
            ) {
                AsyncImage(
                    model = coverRequest
                        ?: coverFileOrNull
                        ?: shelfBook.book.coverUrl?.takeIf { it.isNotBlank() },
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
    onPassword: (app.xreader.data.importer.ImportCandidate, String) -> Unit,
    modifier: Modifier = Modifier
) {
    val candidate = when (state) {
        is CandidateState.Ready -> state.candidate
        is CandidateState.Importing -> state.candidate
        is CandidateState.Done -> state.candidate
        is CandidateState.Failed -> state.candidate
        is CandidateState.PasswordNeeded -> state.candidate
    }
    // v1.5.4 (WS-7, addendum C): the password prompt for a locked
    // PDF. The entered text lives only in this composition until
    // it is handed to the import call — never stored anywhere.
    var passwordPrompt by remember { mutableStateOf(false) }
    var passwordText by remember { mutableStateOf("") }
    if (passwordPrompt) {
        AlertDialog(
            onDismissRequest = { passwordPrompt = false; passwordText = "" },
            title = { Text("This file needs a password") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Enter the password to add “${candidate.displayName}”. " +
                            "It's used once to open the file and never stored.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    OutlinedTextField(
                        value = passwordText,
                        onValueChange = { passwordText = it },
                        label = { Text("Password") },
                        singleLine = true,
                        visualTransformation =
                            androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Password
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = passwordText.isNotEmpty(),
                    onClick = {
                        val entered = passwordText
                        passwordPrompt = false
                        passwordText = ""
                        onPassword(candidate, entered)
                    }
                ) { Text("Unlock and add") }
            },
            dismissButton = {
                TextButton(
                    onClick = { passwordPrompt = false; passwordText = "" }
                ) { Text("Cancel") }
            }
        )
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
                        is CandidateState.PasswordNeeded -> "Locked"
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
                is CandidateState.Done -> Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
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
                    // v1.5.4 (WS-7): the honesty line — a partial
                    // import says what it lost, in plain words. A
                    // clean import (zero) shows no line at all.
                    if (state.imagesDropped > 0) {
                        Text(
                            "Added — but ${state.imagesDropped} " +
                                (if (state.imagesDropped == 1) "picture" else "pictures") +
                                " in this file couldn't be read",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                is CandidateState.PasswordNeeded -> Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        if (state.wrongPassword) {
                            "That password didn't open this file. " +
                                "Check it and try again."
                        } else {
                            "This file is locked with a password. " +
                                "Enter it and the book is added in full."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PrimaryPill(
                            text = "Enter password",
                            onClick = { passwordPrompt = true },
                            modifier = Modifier,
                            enabled = true
                        )
                        TextButton(onClick = { onDismiss(candidate) }) { Text("Dismiss") }
                    }
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
                    // v1.5.1 (UI audit F7): enabled only with a name —
                    // before, enabled=true was hardcoded and a tap on a
                    // blank name silently no-oped while the pill
                    // rendered full-strength violet.
                    enabled = newName.isNotBlank()
                )

                Text("Built-in shelves", style = MaterialTheme.typography.titleSmall)
                listOf(
                    app.xreader.data.library.ShelfLogic.BUILTIN_FAVOURITES to "Favourites",
                    app.xreader.data.library.ShelfLogic.BUILTIN_DOWNLOADED to "Downloaded",
                    app.xreader.data.library.ShelfLogic.BUILTIN_BEHIND_ON to "Behind on",
                    // v1.5.3 (item 30): the five status shelves
                    // get the same hideable treatment.
                    app.xreader.data.library.ShelfLogic.BUILTIN_STATUS_READING to "Reading",
                    app.xreader.data.library.ShelfLogic.BUILTIN_STATUS_ON_HOLD to "On-Hold",
                    app.xreader.data.library.ShelfLogic.BUILTIN_STATUS_PLAN_TO_READ to "Plan to Read",
                    app.xreader.data.library.ShelfLogic.BUILTIN_STATUS_COMPLETED to "Completed",
                    app.xreader.data.library.ShelfLogic.BUILTIN_STATUS_DROPPED to "Dropped"
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

// ── Code 121 (R1 + S4): the Material 3 Library ─────────────
// A separate composition from the Nocturne Library above: the
// Nocturne path never reaches this code, and this code reads
// only Material 3 components + the shared atoms (S6).

/** R1's status tabs: All + the five true statuses ONLY.
 * Favourites and Downloaded are flags — they live only in
 * S4's Show checkboxes, never here. The string keys are the
 * LibraryListLogic filter keys; PLAN_TO_READ is filtered
 * locally below (the shared logic has no key for it). */
private val m3StatusTabs: List<Pair<String, String>> = listOf(
    "ALL" to "All",
    "READING" to "Reading",
    "PLAN_TO_READ" to "Plan to read",
    "COMPLETED" to "Completed",
    "ON_HOLD" to "On hold",
    "DROPPED" to "Dropped"
)

/** S4's sort list (spec order). AUTHOR and PROGRESS have no
 * LibrarySort key, so the M3 shelf orders locally for those. */
private enum class M3LibSort(val label: String) {
    RECENTLY_READ("Recently read"),
    TITLE("Title A–Z"),
    AUTHOR("Author A–Z"),
    RECENTLY_ADDED("Recently added"),
    PROGRESS("Progress")
}

private fun m3StatusLabel(
    status: app.xreader.data.library.ReadingStatus
): String = when (status) {
    app.xreader.data.library.ReadingStatus.READING -> "Reading"
    app.xreader.data.library.ReadingStatus.PLAN_TO_READ -> "Plan to read"
    app.xreader.data.library.ReadingStatus.COMPLETED -> "Completed"
    app.xreader.data.library.ReadingStatus.ON_HOLD -> "On hold"
    app.xreader.data.library.ReadingStatus.DROPPED -> "Dropped"
    app.xreader.data.library.ReadingStatus.NONE -> ""
}

// ── Code 122 (Wave 3): the M3 Library as THE EDITORIAL ─────
// A front page built only from MaterialTheme.colorScheme roles
// (DESIGN-M3-LIBRARY-V5-EDITORIAL-2026-11-24, Addendum A token
// map governs: roles, never hex) and the completed page of
// Addendum B: masthead → sections strip → Cover Story → Manga
// Desk → Also in progress → departments. The Novels|Manga side
// switch is gone from this composition: the page covers the
// whole library, manga is found via LibrarySplit.kindOf (the
// same signal the split uses), and the Desk is a door onto a
// manga wall where the five statuses still organise manga.

/** The sections strip's chips: All + the five statuses, in the
 * mockup's short labels ("Plan", not "Plan to read"). Keys are
 * the same filter keys m3StatusTabs has always used. */
private val editorialChips: List<Pair<String, String>> = listOf(
    "ALL" to "All",
    "READING" to "Reading",
    "ON_HOLD" to "On hold",
    "PLAN_TO_READ" to "Plan",
    "COMPLETED" to "Completed",
    "DROPPED" to "Dropped"
)

/** A book's effective department: its set status, else the
 * progress-derived one the M3 tabs have always used (finished
 * → Completed; part-read → Reading). NONE + unstarted stays
 * NONE — it belongs to no department. */
private fun editorialStatusOf(
    state: LibraryUiState,
    shelf: ShelfBook
): app.xreader.data.library.ReadingStatus {
    val status = state.metas[shelf.book.id]?.status
        ?: app.xreader.data.library.ReadingStatus.NONE
    if (status != app.xreader.data.library.ReadingStatus.NONE) return status
    return when {
        shelf.book.finished -> app.xreader.data.library.ReadingStatus.COMPLETED
        shelf.fraction > 0f -> app.xreader.data.library.ReadingStatus.READING
        else -> app.xreader.data.library.ReadingStatus.NONE
    }
}

private fun editorialIsManga(
    state: LibraryUiState,
    shelf: ShelfBook
): Boolean = app.xreader.data.library.LibrarySplit.kindOf(
    shelf.book.id, state.bookKinds
) == app.xreader.data.extension.MediaKind.MANGA

/** The standfirst: the synopsis' (Book.description's) first
 * line, per the design's own fallback rule — null when the
 * book carries no synopsis, and the caller then sets the
 * byline/line alone. */
private fun editorialStandfirst(shelf: ShelfBook): String? =
    shelf.book.description.lines()
        .firstOrNull { it.isNotBlank() }?.trim()?.takeIf { it.isNotBlank() }

/** The small tertiary pill (COVER STORY tag / NEW / FAVOURITE /
 * MANGA tags): tertiaryContainer fill, onTertiaryContainer ink. */
@Composable
private fun EditorialPill(text: String) {
    androidx.compose.material3.Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = RoundedCornerShape(6.dp)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp
            ),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun EditorialCover(
    shelf: ShelfBook,
    state: LibraryUiState,
    coverFile: (String?) -> File?,
    widthDp: Int,
    heightDp: Int,
    selected: Boolean,
    mangaTag: Boolean,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier
            .size(width = widthDp.dp, height = heightDp.dp)
            .then(
                if (selected) {
                    Modifier.background(scheme.primary, RoundedCornerShape(14.dp))
                } else {
                    Modifier
                }
            )
            .padding(if (selected) 2.5.dp else 0.dp)
    ) {
        app.xreader.ui.common.M3CoverThumbnail(
            title = shelf.book.title,
            author = shelf.book.author.takeIf { it.isNotBlank() },
            coverModel = coverFile(shelf.book.coverPath)?.takeIf { it.exists() }
                ?: shelf.book.coverUrl?.takeIf { it.isNotBlank() },
            modifier = Modifier.fillMaxSize(),
            shape = RoundedCornerShape(12.dp)
        )
        if (mangaTag) {
            Box(Modifier.align(Alignment.TopEnd).padding(7.dp)) {
                EditorialPill("MANGA")
            }
        }
        if (selected) {
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(7.dp)
                    .size(23.dp)
                    .background(scheme.primary, androidx.compose.foundation.shape.CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = "Selected",
                    tint = scheme.onPrimary,
                    modifier = Modifier.size(15.dp)
                )
            }
        }
        if (shelf.fraction > 0f && shelf.fraction < 1f) {
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(5.dp)
                    .background(scheme.scrim.copy(alpha = 0.45f))
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(shelf.fraction.coerceIn(0f, 1f))
                        .height(5.dp)
                        .background(scheme.primary)
                )
            }
        }
    }
}

/** One cover card on a shelf / beneath a department feature:
 * cover (selection ring + check + NEW pill per the design),
 * title, and the caller's sub-line. */
@Composable
private fun EditorialCoverCard(
    shelf: ShelfBook,
    state: LibraryUiState,
    coverFile: (String?) -> File?,
    subLine: String,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val manga = editorialIsManga(state, shelf)
    val widthDp = if (manga) 92 else 104
    Column(
        Modifier
            .width(widthDp.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        EditorialCover(
            shelf = shelf, state = state, coverFile = coverFile,
            widthDp = widthDp, heightDp = 148,
            selected = selected, mangaTag = manga
        )
        Spacer(Modifier.height(8.dp))
        Text(
            shelf.book.title,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(top = 2.dp)
        ) {
            val newCount = state.metas[shelf.book.id]?.newChapterCount ?: 0
            if (newCount > 0) {
                EditorialPill("$newCount NEW")
            }
            Text(
                subLine,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** A ruled section header: letterspaced title, its count, and
 * the primary See-all door. */
@Composable
private fun EditorialSectionHeader(
    title: String,
    countText: String,
    onSeeAll: (() -> Unit)? = null
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge.copy(
                fontWeight = FontWeight.ExtraBold, letterSpacing = 1.6.sp
            )
        )
        Text(
            countText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp)
        )
        if (onSeeAll != null) {
            Spacer(Modifier.weight(1f))
            Row(
                Modifier.clickable { onSeeAll() },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "See all",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = MaterialTheme.colorScheme.primary
                )
                Icon(
                    Icons.Filled.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

/** The status line a department feature / beneath-row carries,
 * from real state only: percent, status words, downloaded. */
private fun editorialFeatureLine(
    state: LibraryUiState,
    shelf: ShelfBook
): String {
    val status = editorialStatusOf(state, shelf)
    val pct = (shelf.fraction * 100).toInt()
    val downloaded = (state.downloadCounts[shelf.book.id]?.downloaded ?: 0) > 0
    return when (status) {
        app.xreader.data.library.ReadingStatus.COMPLETED -> "Finished · 100% read"
        app.xreader.data.library.ReadingStatus.DROPPED -> "Dropped · $pct% read"
        app.xreader.data.library.ReadingStatus.PLAN_TO_READ ->
            "Plan · not started" + if (downloaded) " · downloaded" else ""
        app.xreader.data.library.ReadingStatus.ON_HOLD ->
            if (shelf.fraction > 0f) "$pct% read · on hold" else "On hold"
        app.xreader.data.library.ReadingStatus.READING -> "$pct% read"
        app.xreader.data.library.ReadingStatus.NONE ->
            if (shelf.fraction > 0f) "$pct% read" else "Not started"
    }
}

/** A Manga Desk cover line, in the design's format, from real
 * state only: the resolved chapter place ("Ch. <parsed>", from
 * state.placeLabels — the chapter at the book's progress
 * index, labelled by ChapterDisplayOrder) when the book has
 * one, then percent / status / downloaded. A book with no
 * resolved place prints no chapter segment — never a
 * position. */
private fun editorialCoverLine(
    state: LibraryUiState,
    shelf: ShelfBook
): String {
    val status = editorialStatusOf(state, shelf)
    val pct = (shelf.fraction * 100).toInt()
    val downloaded = (state.downloadCounts[shelf.book.id]?.downloaded ?: 0) > 0
    val tail = when (status) {
        app.xreader.data.library.ReadingStatus.ON_HOLD -> "on hold"
        app.xreader.data.library.ReadingStatus.PLAN_TO_READ ->
            "on the plan" + if (downloaded) " · downloaded" else ""
        app.xreader.data.library.ReadingStatus.COMPLETED -> "finished"
        app.xreader.data.library.ReadingStatus.DROPPED -> "dropped"
        else -> if (shelf.fraction > 0f) "$pct%" else "not started"
    }
    val chapterSegment = state.placeLabels[shelf.book.id]
        ?.chapterNumber?.let { "Ch. $it" }
    return listOfNotNull(chapterSegment, tail).joinToString(" · ")
}

@OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)
@Composable
private fun M3LibraryContent(
    state: LibraryUiState,
    candidates: List<CandidateState>,
    viewModel: LibraryViewModel,
    coverFile: (String?) -> File?,
    designState: app.xreader.data.prefs.DesignState,
    onAddBook: () -> Unit,
    onOpenBook: (Long) -> Unit,
    onContinueReading: (bookId: Long, chapterIndex: Int) -> Unit,
    onOpenAddList: () -> Unit,
    onOpenUpdates: () -> Unit,
    updateState: app.xreader.ui.update.AppUpdateViewModel.UiState? = null,
    onUpdateNow: () -> Unit = {},
    onDismissUpdate: () -> Unit = {},
    onReadMoreUpdate: () -> Unit = {},
    onInstallUpdate: () -> Unit = {},
    onCancelUpdateDownload: () -> Unit = {},
    onOpenUpdateInstallSettings: () -> Unit = {}
) {
    var tabKey by remember { mutableStateOf("ALL") }
    var m3Sort by remember { mutableStateOf(M3LibSort.RECENTLY_READ) }
    var downloadedOnly by remember { mutableStateOf(false) }
    var favouritesOnly by remember { mutableStateOf(false) }
    var sheetOpen by remember { mutableStateOf(false) }
    var searchOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var addMenuOpen by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<ShelfBook?>(null) }
    var selection by remember { mutableStateOf<Set<Long>?>(null) }
    var removeTarget by remember { mutableStateOf<ShelfBook?>(null) }
    var bulkRemoveOpen by remember { mutableStateOf(false) }
    var bulkRemoveSize by remember { mutableStateOf<String?>(null) }
    var mangaWall by remember { mutableStateOf(false) }

    val scheme = MaterialTheme.colorScheme
    // The Editorial covers the WHOLE library (no side split):
    // departments organise by status, manga by kind.
    val allBooks = state.books
    fun statusOf(shelf: ShelfBook) = editorialStatusOf(state, shelf)
    fun isManga(shelf: ShelfBook) = editorialIsManga(state, shelf)
    val mangaBooks = remember(allBooks, state.bookKinds, state.metas) {
        allBooks.filter { isManga(it) }
            .sortedByDescending { it.book.lastOpenedAt ?: 0L }
    }
    val mangaInProgress = mangaBooks.count {
        statusOf(it) == app.xreader.data.library.ReadingStatus.READING
    }
    val deskLead = mangaBooks.firstOrNull {
        statusOf(it) == app.xreader.data.library.ReadingStatus.READING
    } ?: mangaBooks.firstOrNull()
    // Continue: the state's hero first (its chapter index is the
    // only one the state carries), else the most recent
    // in-progress book.
    val inProgress = remember(allBooks, state.metas) {
        allBooks.filter {
            statusOf(it) == app.xreader.data.library.ReadingStatus.READING &&
                it.fraction < 1f
        }.sortedByDescending { it.book.lastOpenedAt ?: 0L }
    }
    val lead = state.continueReading ?: inProgress.firstOrNull()
    val alsoInProgress = inProgress.filter {
        it.book.id != lead?.book?.id && it.book.id != deskLead?.book?.id
    }
    val departments = remember(allBooks, state.metas) {
        listOf(
            app.xreader.data.library.ReadingStatus.ON_HOLD,
            app.xreader.data.library.ReadingStatus.PLAN_TO_READ,
            app.xreader.data.library.ReadingStatus.COMPLETED,
            app.xreader.data.library.ReadingStatus.DROPPED
        ).map { status ->
            status to allBooks.filter { statusOf(it) == status }
                .sortedByDescending { it.book.lastOpenedAt ?: 0L }
        }.filter { it.second.isNotEmpty() }
    }
    val unclassified = remember(allBooks, state.metas) {
        allBooks.filter {
            statusOf(it) == app.xreader.data.library.ReadingStatus.NONE
        }
    }
    val finishedCount = allBooks.count {
        statusOf(it) == app.xreader.data.library.ReadingStatus.COMPLETED
    }
    val filterLive = downloadedOnly || favouritesOnly ||
        m3Sort != M3LibSort.RECENTLY_READ
    val frontPage = tabKey == "ALL" && query.isBlank() &&
        !downloadedOnly && !favouritesOnly && !mangaWall

    fun matchesFlags(shelf: ShelfBook): Boolean =
        (!downloadedOnly ||
            (state.downloadCounts[shelf.book.id]?.downloaded ?: 0) > 0) &&
            (!favouritesOnly || shelf.book.id in state.favourites)
    fun matchesQuery(shelf: ShelfBook): Boolean = query.isBlank() ||
        shelf.book.title.lowercase().contains(query.trim().lowercase()) ||
        shelf.book.author.lowercase().contains(query.trim().lowercase())
    fun sorted(books: List<ShelfBook>): List<ShelfBook> = when (m3Sort) {
        M3LibSort.RECENTLY_READ -> books.sortedByDescending { it.book.lastOpenedAt ?: 0L }
        M3LibSort.TITLE -> books.sortedBy { it.book.title.lowercase() }
        M3LibSort.AUTHOR -> books.sortedBy { it.book.author.lowercase() }
        M3LibSort.RECENTLY_ADDED -> books.sortedByDescending { it.book.addedAt }
        M3LibSort.PROGRESS -> books.sortedByDescending { it.fraction }
    }
    // The collapsed view (a status pick, the manga wall, search,
    // or a live flag): one department, expanded, as M3 cards.
    val listBooks = remember(
        allBooks, state.metas, state.favourites, state.downloadCounts,
        tabKey, m3Sort, downloadedOnly, favouritesOnly, query, mangaWall,
        state.bookKinds
    ) {
        val pool = if (mangaWall) mangaBooks else allBooks
        val filtered = pool.filter { shelf ->
            val matchesTab = when (tabKey) {
                "READING" -> statusOf(shelf) ==
                    app.xreader.data.library.ReadingStatus.READING
                "PLAN_TO_READ" -> statusOf(shelf) ==
                    app.xreader.data.library.ReadingStatus.PLAN_TO_READ
                "COMPLETED" -> statusOf(shelf) ==
                    app.xreader.data.library.ReadingStatus.COMPLETED
                "ON_HOLD" -> statusOf(shelf) ==
                    app.xreader.data.library.ReadingStatus.ON_HOLD
                "DROPPED" -> statusOf(shelf) ==
                    app.xreader.data.library.ReadingStatus.DROPPED
                else -> true
            }
            matchesTab && matchesFlags(shelf) && matchesQuery(shelf)
        }
        sorted(filtered)
    }

    fun onCoverClick(shelf: ShelfBook) {
        val current = selection
        if (current != null) {
            selection = if (shelf.book.id in current) {
                (current - shelf.book.id).ifEmpty { null }
            } else {
                current + shelf.book.id
            }
        } else {
            viewModel.markSeen(shelf.book.id)
            onOpenBook(shelf.book.id)
        }
    }
    fun onCoverLongClick(shelf: ShelfBook) {
        val current = selection
        selection = if (current == null) {
            setOf(shelf.book.id)
        } else if (shelf.book.id in current) {
            (current - shelf.book.id).ifEmpty { null }
        } else {
            current + shelf.book.id
        }
    }

    val libraryEmpty = !state.loading && state.books.isEmpty() &&
        candidates.isEmpty()
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        // Selection bar (long-press multi-select) — unchanged
        // actions from the code-121 M3 Library.
        selection?.let { picked ->
            androidx.compose.material3.Surface(
                color = scheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        "${picked.size} selected",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    TextButton(onClick = {
                        viewModel.bulkSetFavourite(picked, true)
                    }) { Text("Favourite") }
                    TextButton(onClick = {
                        viewModel.bulkDownload(picked)
                    }) { Text("Download") }
                    TextButton(onClick = {
                        viewModel.bulkSetStatus(
                            picked,
                            app.xreader.data.library.ReadingStatus.COMPLETED
                        )
                    }) { Text("Mark read") }
                    TextButton(onClick = {
                        bulkRemoveSize = null
                        bulkRemoveOpen = true
                    }) { Text("Remove…") }
                    TextButton(onClick = { selection = null }) {
                        Text("Done")
                    }
                }
            }
        }
        if (state.loading && state.books.isEmpty() && candidates.isEmpty()) {
            LoadingState(
                whatIsHappening = "Loading your library…",
                modifier = Modifier.weight(1f)
            )
            return@Column
        }
        if (libraryEmpty) {
            Column(
                Modifier.weight(1f).fillMaxWidth().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "No books yet",
                    style = MaterialTheme.typography.headlineSmall
                )
                Text(
                    "Add your first book (EPUB, PDF, TXT and more) " +
                        "and it will live here, ready to read.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant
                )
                androidx.compose.material3.ExtendedFloatingActionButton(
                    onClick = onAddBook,
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("Add a book") }
                )
            }
            return@Column
        }
        androidx.compose.material3.pulltorefresh.PullToRefreshBox(
            isRefreshing = state.pullRefreshing,
            onRefresh = { viewModel.pullRefresh() },
            modifier = Modifier.weight(1f)
        ) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 4.dp, bottom = 88.dp)
            ) {
                // ── 1. The masthead ──────────────────────────
                item(key = "ed-masthead") {
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val dayName = remember {
                                java.time.LocalDate.now().dayOfWeek
                                    .getDisplayName(
                                        java.time.format.TextStyle.FULL,
                                        java.util.Locale.ENGLISH
                                    ).uppercase()
                            }
                            Text(
                                "$dayName · YOUR SHELF AT A GLANCE",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 2.sp
                                ),
                                color = scheme.primary,
                                modifier = Modifier.weight(1f)
                            )
                            FilledTonalIconButton(
                                onClick = { searchOpen = !searchOpen }
                            ) {
                                Icon(
                                    Icons.Filled.Search,
                                    contentDescription = "Search your library"
                                )
                            }
                            Box {
                                IconButton(
                                    onClick = { addMenuOpen = true },
                                    modifier = Modifier.background(
                                        scheme.primary,
                                        androidx.compose.foundation.shape.CircleShape
                                    )
                                ) {
                                    Icon(
                                        Icons.Filled.Add,
                                        contentDescription = "Add a book",
                                        tint = scheme.onPrimary
                                    )
                                }
                                DropdownMenu(
                                    expanded = addMenuOpen,
                                    onDismissRequest = { addMenuOpen = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Import a file") },
                                        onClick = {
                                            addMenuOpen = false
                                            onAddBook()
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Add a list") },
                                        onClick = {
                                            addMenuOpen = false
                                            onOpenAddList()
                                        }
                                    )
                                }
                            }
                        }
                        Text(
                            "Library",
                            style = MaterialTheme.typography.displayMedium.copy(
                                fontWeight = FontWeight.Black,
                                letterSpacing = (-1.2).sp
                            ),
                            modifier = Modifier.padding(top = 10.dp)
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(top = 7.dp)
                        ) {
                            Text(
                                "${allBooks.size} titles",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontWeight = FontWeight.Bold
                                )
                            )
                            Text("·", style = MaterialTheme.typography.bodySmall,
                                color = scheme.onSurfaceVariant)
                            Text(
                                "${inProgress.size} in progress",
                                style = MaterialTheme.typography.bodySmall,
                                color = scheme.onSurfaceVariant
                            )
                            Text("·", style = MaterialTheme.typography.bodySmall,
                                color = scheme.onSurfaceVariant)
                            Text(
                                "${mangaBooks.size} manga",
                                style = MaterialTheme.typography.bodySmall,
                                color = scheme.onSurfaceVariant
                            )
                            Text("·", style = MaterialTheme.typography.bodySmall,
                                color = scheme.onSurfaceVariant)
                            Text(
                                "$finishedCount finished",
                                style = MaterialTheme.typography.bodySmall,
                                color = scheme.onSurfaceVariant
                            )
                        }
                        // The double rule: one thick on-surface
                        // line over one hairline.
                        Box(
                            Modifier.padding(top = 12.dp).fillMaxWidth()
                                .height(3.dp).background(scheme.onSurface)
                        )
                        Box(
                            Modifier.padding(top = 3.dp).fillMaxWidth()
                                .height(1.dp).background(scheme.outlineVariant)
                        )
                    }
                }
                if (searchOpen) {
                    item(key = "ed-search") {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            placeholder = {
                                Text("Search your library — title or author")
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                }
                // ── 2. The sections strip ────────────────────
                item(key = "ed-sections") {
                    Row(
                        Modifier.fillMaxWidth()
                            .padding(start = 16.dp, end = 8.dp, top = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "SECTIONS",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.8.sp
                            ),
                            color = scheme.onSurfaceVariant
                        )
                        androidx.compose.foundation.lazy.LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            items(editorialChips, key = { "chip-${it.first}" }) { (key, label) ->
                                FilterChip(
                                    selected = tabKey == key,
                                    onClick = { tabKey = key },
                                    label = { Text(label) },
                                    leadingIcon = if (tabKey == key) {
                                        {
                                            Icon(
                                                Icons.Filled.Check,
                                                contentDescription = null,
                                                modifier = Modifier.size(17.dp)
                                            )
                                        }
                                    } else {
                                        null
                                    }
                                )
                            }
                        }
                        Box {
                            IconButton(onClick = { sheetOpen = true }) {
                                Icon(
                                    Icons.Filled.Tune,
                                    contentDescription = "Filter and sort",
                                    tint = scheme.onSurfaceVariant
                                )
                            }
                            if (filterLive) {
                                Box(
                                    Modifier.align(Alignment.TopEnd)
                                        .padding(4.dp).size(8.dp)
                                        .background(
                                            scheme.primary,
                                            androidx.compose.foundation.shape.CircleShape
                                        )
                                )
                            }
                        }
                    }
                }
                // ── S36: the update card, in flow ────────────
                if (updateState?.cardRelease != null) {
                    item(key = "ed-update") {
                        app.xreader.ui.update.UpdateCard(
                            state = updateState,
                            onUpdateNow = onUpdateNow,
                            onDismiss = onDismissUpdate,
                            onReadMore = onReadMoreUpdate,
                            onInstall = onInstallUpdate,
                            onCancelDownload = onCancelUpdateDownload,
                            onOpenInstallSettings = onOpenUpdateInstallSettings,
                            modifier = Modifier.padding(
                                horizontal = 16.dp, vertical = 12.dp
                            )
                        )
                    }
                }
                if (candidates.isNotEmpty()) {
                    item(key = "ed-import-header") {
                        Row(
                            Modifier.fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "Adding books",
                                style = MaterialTheme.typography.titleMedium
                            )
                            TextButton(onClick = {
                                viewModel.clearFinishedCandidates()
                            }) { Text("Clear finished") }
                        }
                    }
                    items(candidates, key = { "ed-cand-${it.token()}" }) { candidateState ->
                        Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                            ImportCandidateCard(
                                state = candidateState,
                                onImport = { viewModel.importCandidate(it) },
                                onDismiss = { viewModel.dismissCandidate(it) },
                                onPassword = { cand, pw ->
                                    viewModel.importCandidate(cand, pw)
                                }
                            )
                        }
                    }
                }
                if (mangaWall) {
                    item(key = "ed-wall-head") {
                        Row(
                            Modifier.fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = { mangaWall = false }) {
                                Icon(
                                    Icons.Filled.ArrowBack,
                                    contentDescription = "Back to the front page"
                                )
                            }
                            Column {
                                Text(
                                    "THE MANGA DESK",
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.Black,
                                        letterSpacing = 1.6.sp
                                    )
                                )
                                Text(
                                    "${mangaBooks.size} SERIES · " +
                                        "$mangaInProgress IN PROGRESS",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 1.6.sp
                                    ),
                                    color = scheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                if (!frontPage) {
                    if (listBooks.isEmpty() && candidates.isEmpty()) {
                        item(key = "ed-list-empty") {
                            Text(
                                if (mangaWall && mangaBooks.isEmpty()) {
                                    "No manga here yet. Manga lives in " +
                                        "Explore — open the Keiyoushi " +
                                        "catalogue, add a series, and it " +
                                        "will live at this desk."
                                } else {
                                    "Nothing matches these filters. " +
                                        "Change the section or the Show " +
                                        "filters to see more of your library."
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = scheme.onSurfaceVariant,
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                    }
                    items(listBooks, key = { "ed-book-${it.book.id}" }) { shelfBook ->
                        val status = statusOf(shelfBook)
                        val downloaded = state.downloadCounts[shelfBook.book.id]
                            ?.downloaded ?: 0
                        val supporting = listOfNotNull(
                            m3StatusLabel(status).takeIf { it.isNotBlank() },
                            if (shelfBook.fraction > 0f) {
                                "${(shelfBook.fraction * 100).toInt()}%"
                            } else {
                                null
                            },
                            if (downloaded > 0) "$downloaded downloaded" else null,
                            if (shelfBook.book.id in state.favourites) {
                                "Favourite"
                            } else {
                                null
                            }
                        ).joinToString(" · ")
                        Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                            app.xreader.ui.common.M3BookCard(
                                title = shelfBook.book.title,
                                author = shelfBook.book.author
                                    .takeIf { it.isNotBlank() },
                                coverModel = coverFile(shelfBook.book.coverPath)
                                    ?.takeIf { it.exists() }
                                    ?: shelfBook.book.coverUrl
                                        ?.takeIf { it.isNotBlank() },
                                supportingLine = supporting.ifBlank { null },
                                progressFraction = if (
                                    designState.libraryProgressOnCovers &&
                                    shelfBook.fraction > 0f
                                ) {
                                    shelfBook.fraction
                                } else {
                                    null
                                },
                                selected = selection?.contains(shelfBook.book.id) == true,
                                onClick = { onCoverClick(shelfBook) },
                                onLongClick = { onCoverLongClick(shelfBook) },
                                trailingContent = {
                                    Box {
                                        IconButton(
                                            onClick = { menuFor = shelfBook }
                                        ) {
                                            Icon(
                                                Icons.Filled.MoreVert,
                                                contentDescription =
                                                    "Actions for ${shelfBook.book.title}"
                                            )
                                        }
                                        DropdownMenu(
                                            expanded = menuFor?.book?.id ==
                                                shelfBook.book.id,
                                            onDismissRequest = { menuFor = null }
                                        ) {
                                            DropdownMenuItem(
                                                text = { Text("Open book page") },
                                                onClick = {
                                                    menuFor = null
                                                    viewModel.markSeen(shelfBook.book.id)
                                                    onOpenBook(shelfBook.book.id)
                                                }
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Download") },
                                                onClick = {
                                                    menuFor = null
                                                    viewModel.bulkDownload(
                                                        listOf(shelfBook.book.id)
                                                    )
                                                }
                                            )
                                            HorizontalDivider()
                                            listOf(
                                                app.xreader.data.library
                                                    .ReadingStatus.READING,
                                                app.xreader.data.library
                                                    .ReadingStatus.PLAN_TO_READ,
                                                app.xreader.data.library
                                                    .ReadingStatus.COMPLETED,
                                                app.xreader.data.library
                                                    .ReadingStatus.ON_HOLD,
                                                app.xreader.data.library
                                                    .ReadingStatus.DROPPED
                                            ).forEach { target ->
                                                DropdownMenuItem(
                                                    text = {
                                                        Text(
                                                            (if (status == target) {
                                                                "✓ "
                                                            } else {
                                                                ""
                                                            }) + m3StatusLabel(target)
                                                        )
                                                    },
                                                    onClick = {
                                                        menuFor = null
                                                        viewModel.bulkSetStatus(
                                                            listOf(shelfBook.book.id),
                                                            target
                                                        )
                                                    }
                                                )
                                            }
                                            HorizontalDivider()
                                            DropdownMenuItem(
                                                text = {
                                                    Text(
                                                        "Remove from library…",
                                                        color = scheme.error
                                                    )
                                                },
                                                onClick = {
                                                    menuFor = null
                                                    removeTarget = shelfBook
                                                }
                                            )
                                        }
                                    }
                                }
                            )
                        }
                    }
                } else {
                    // ── 3. The Cover Story ───────────────────
                    if (lead != null && designState.libraryContinueRail) {
                        item(key = "ed-lead") {
                            val hero = lead
                            val isHero = state.continueReading?.book?.id ==
                                hero.book.id
                            Column(
                                Modifier.padding(horizontal = 16.dp, vertical = 16.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    EditorialPill("COVER STORY")
                                    Text(
                                        "CONTINUE READING",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontWeight = FontWeight.Bold,
                                            letterSpacing = 2.sp
                                        ),
                                        color = scheme.primary
                                    )
                                }
                                Row(
                                    Modifier.padding(top = 13.dp)
                                        .combinedClickable(
                                            onClick = { onCoverClick(hero) },
                                            onLongClick = { onCoverLongClick(hero) }
                                        ),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    EditorialCover(
                                        shelf = hero, state = state,
                                        coverFile = coverFile,
                                        widthDp = 148, heightDp = 216,
                                        selected = selection?.contains(hero.book.id) == true,
                                        mangaTag = false
                                    )
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            hero.book.title,
                                            style = MaterialTheme.typography.headlineSmall.copy(
                                                fontWeight = FontWeight.Bold
                                            ),
                                            maxLines = 3,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        editorialStandfirst(hero)?.let { standfirst ->
                                            Text(
                                                standfirst,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = scheme.onSurfaceVariant,
                                                maxLines = 3,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.padding(top = 8.dp)
                                            )
                                        }
                                        // Byline: format + the
                                        // continue chapter's place —
                                        // "FORMAT · CH. <parsed> ·
                                        // <TITLE>", from the VM's
                                        // resolved place label
                                        // (state.placeLabels: the
                                        // chapter at the progress
                                        // index, labelled by
                                        // ChapterDisplayOrder from
                                        // its own number + title,
                                        // the book page's
                                        // derivation). No resolved
                                        // place → no place segment:
                                        // a position is never
                                        // printed as a chapter.
                                        val formatName = hero.book.localFormat.name
                                        val heroPlace =
                                            state.placeLabels[hero.book.id]
                                        val byline = listOfNotNull(
                                            formatName.takeIf {
                                                hero.book.localFormat !=
                                                    app.xreader.data.db.BookFormat.UNKNOWN
                                            },
                                            // The resolved place
                                            // belongs to the hero
                                            // only; a non-hero lead
                                            // shows its count.
                                            if (!isHero) {
                                                if (hero.book.totalChapters > 0) {
                                                    "${hero.book.totalChapters} CHAPTERS"
                                                } else {
                                                    null
                                                }
                                            } else if (heroPlace != null) {
                                                val isPdf =
                                                    hero.book.localFormat ==
                                                        app.xreader.data.db.BookFormat.PDF
                                                val unit = if (isPdf) {
                                                    "PAGE"
                                                } else {
                                                    "CH."
                                                }
                                                val word = if (isPdf) {
                                                    "Page"
                                                } else {
                                                    "Chapter"
                                                }
                                                val num =
                                                    heroPlace.chapterNumber
                                                val title =
                                                    heroPlace.chapterTitle
                                                when {
                                                    num == null -> title
                                                    title.isNullOrBlank() ->
                                                        "$unit $num"
                                                    title.equals(
                                                        "$word $num",
                                                        ignoreCase = true
                                                    ) || title.startsWith(
                                                        "$word $num ",
                                                        ignoreCase = true
                                                    ) || title.startsWith(
                                                        "$word $num:",
                                                        ignoreCase = true
                                                    ) -> title
                                                    else ->
                                                        "$unit $num · $title"
                                                }
                                            } else {
                                                null
                                            }
                                        ).joinToString(" · ")
                                        if (byline.isNotBlank()) {
                                            Text(
                                                byline,
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    fontWeight = FontWeight.Bold,
                                                    letterSpacing = 1.4.sp
                                                ),
                                                color = scheme.onSurfaceVariant,
                                                modifier = Modifier.padding(top = 10.dp)
                                            )
                                        }
                                        Spacer(Modifier.weight(1f))
                                        LinearWavyProgressIndicator(
                                            progress = {
                                                hero.fraction.coerceIn(0f, 1f)
                                            },
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.padding(top = 9.dp)
                                        ) {
                                            Text(
                                                "${(hero.fraction * 100).toInt()}%",
                                                style = MaterialTheme.typography.bodyMedium.copy(
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                            )
                                            Spacer(Modifier.weight(1f))
                                            Button(
                                                onClick = {
                                                    if (selection != null) {
                                                        onCoverClick(hero)
                                                    } else if (isHero) {
                                                        onContinueReading(
                                                            hero.book.id,
                                                            state.continueChapterIndex
                                                        )
                                                    } else {
                                                        viewModel.markSeen(hero.book.id)
                                                        onOpenBook(hero.book.id)
                                                    }
                                                }
                                            ) {
                                                Icon(
                                                    Icons.Filled.PlayArrow,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(19.dp)
                                                )
                                                Spacer(Modifier.width(6.dp))
                                                Text("Resume")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        item(key = "ed-hairline") {
                            Box(
                                Modifier.padding(horizontal = 16.dp)
                                    .fillMaxWidth().height(1.dp)
                                    .background(scheme.outlineVariant)
                            )
                        }
                    }
                    // ── 4. The Manga Desk ────────────────────
                    if (mangaBooks.isNotEmpty()) {
                        item(key = "ed-desk") {
                            Row(
                                Modifier.padding(top = 14.dp).fillMaxWidth()
                                    .background(scheme.tertiaryContainer)
                                    .clickable { mangaWall = true }
                                    .padding(
                                        start = 16.dp, end = 16.dp,
                                        top = 14.dp, bottom = 16.dp
                                    ),
                                horizontalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        "THE MANGA DESK",
                                        style = MaterialTheme.typography.titleMedium.copy(
                                            fontWeight = FontWeight.Black,
                                            letterSpacing = 1.6.sp
                                        ),
                                        color = scheme.onTertiaryContainer
                                    )
                                    Text(
                                        "${mangaBooks.size} SERIES · " +
                                            "$mangaInProgress IN PROGRESS",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontWeight = FontWeight.Bold,
                                            letterSpacing = 1.6.sp
                                        ),
                                        color = scheme.onTertiaryContainer
                                            .copy(alpha = 0.75f),
                                        modifier = Modifier.padding(top = 3.dp)
                                    )
                                    mangaBooks.forEach { shelf ->
                                        Text(
                                            buildAnnotatedString {
                                                withStyle(
                                                    SpanStyle(fontWeight = FontWeight.Bold)
                                                ) {
                                                    append(shelf.book.title)
                                                }
                                                append(" — ")
                                                append(editorialCoverLine(state, shelf))
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = scheme.onTertiaryContainer,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.padding(top = 9.dp)
                                        )
                                    }
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(top = 11.dp)
                                    ) {
                                        Text(
                                            "Open the desk",
                                            style = MaterialTheme.typography.bodyMedium.copy(
                                                fontWeight = FontWeight.Bold
                                            ),
                                            color = scheme.onTertiaryContainer
                                        )
                                        Icon(
                                            Icons.Filled.ChevronRight,
                                            contentDescription = null,
                                            tint = scheme.onTertiaryContainer,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                                if (deskLead != null) {
                                    // The lead manga's tall cover
                                    // breaks out of the band's top
                                    // edge — the page's one
                                    // deliberate overflow.
                                    EditorialCover(
                                        shelf = deskLead, state = state,
                                        coverFile = coverFile,
                                        widthDp = 92, heightDp = 148,
                                        selected = false, mangaTag = true,
                                        modifier = Modifier.offset(y = (-30).dp)
                                    )
                                }
                            }
                        }
                    }
                    // ── 5. Also in progress ──────────────────
                    if (alsoInProgress.isNotEmpty()) {
                        item(key = "ed-also-head") {
                            Box(Modifier.padding(top = 18.dp, bottom = 10.dp)) {
                                EditorialSectionHeader(
                                    title = "ALSO IN PROGRESS",
                                    countText = "${alsoInProgress.size} more",
                                    onSeeAll = { tabKey = "READING" }
                                )
                            }
                        }
                        item(key = "ed-also-shelf") {
                            val carouselState =
                                androidx.compose.material3.carousel
                                    .rememberCarouselState { alsoInProgress.size }
                            androidx.compose.material3.carousel
                                .HorizontalUncontainedCarousel(
                                    state = carouselState,
                                    itemWidth = 104.dp,
                                    itemSpacing = 14.dp,
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) { index ->
                                    val shelf = alsoInProgress[index]
                                    EditorialCoverCard(
                                        shelf = shelf, state = state,
                                        coverFile = coverFile,
                                        subLine = "${(shelf.fraction * 100).toInt()}%",
                                        selected = selection?.contains(shelf.book.id) == true,
                                        onClick = { onCoverClick(shelf) },
                                        onLongClick = { onCoverLongClick(shelf) }
                                    )
                                }
                        }
                    }
                    // ── 6. Departments ───────────────────────
                    departments.forEachIndexed { deptIndex, (status, books) ->
                        val deptTitle = when (status) {
                            app.xreader.data.library.ReadingStatus.ON_HOLD -> "ON HOLD"
                            app.xreader.data.library.ReadingStatus.PLAN_TO_READ -> "PLAN"
                            app.xreader.data.library.ReadingStatus.COMPLETED -> "COMPLETED"
                            app.xreader.data.library.ReadingStatus.DROPPED -> "DROPPED"
                            else -> m3StatusLabel(status).uppercase()
                        }
                        val deptKey = when (status) {
                            app.xreader.data.library.ReadingStatus.PLAN_TO_READ ->
                                "PLAN_TO_READ"
                            else -> status.name
                        }
                        item(key = "ed-dept-head-$deptKey") {
                            Box(Modifier.padding(top = 18.dp, bottom = 10.dp)) {
                                EditorialSectionHeader(
                                    title = deptTitle,
                                    countText = if (books.size == 1) {
                                        "1 title"
                                    } else {
                                        "${books.size} titles"
                                    },
                                    onSeeAll = { tabKey = deptKey }
                                )
                            }
                        }
                        item(key = "ed-dept-feature-$deptKey") {
                            val feature = books.first()
                            val flip = deptIndex % 2 == 1
                            Row(
                                Modifier.fillMaxWidth()
                                    .padding(horizontal = 16.dp)
                                    .combinedClickable(
                                        onClick = { onCoverClick(feature) },
                                        onLongClick = { onCoverLongClick(feature) }
                                    ),
                                horizontalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                if (!flip) {
                                    EditorialCover(
                                        shelf = feature, state = state,
                                        coverFile = coverFile,
                                        widthDp = 104, heightDp = 148,
                                        selected = selection?.contains(feature.book.id) == true,
                                        mangaTag = isManga(feature)
                                    )
                                }
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        feature.book.title,
                                        style = MaterialTheme.typography.titleMedium.copy(
                                            fontWeight = FontWeight.Bold
                                        ),
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    editorialStandfirst(feature)?.let { standfirst ->
                                        Text(
                                            standfirst,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = scheme.onSurfaceVariant,
                                            maxLines = 3,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.padding(top = 5.dp)
                                        )
                                    }
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        modifier = Modifier.padding(top = 8.dp)
                                    ) {
                                        if (feature.book.id in state.favourites) {
                                            EditorialPill("FAVOURITE")
                                        }
                                        Text(
                                            editorialFeatureLine(state, feature),
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        )
                                    }
                                }
                                if (flip) {
                                    EditorialCover(
                                        shelf = feature, state = state,
                                        coverFile = coverFile,
                                        widthDp = 104, heightDp = 148,
                                        selected = selection?.contains(feature.book.id) == true,
                                        mangaTag = isManga(feature)
                                    )
                                }
                            }
                        }
                        if (books.size > 1) {
                            item(key = "ed-dept-beneath-$deptKey") {
                                androidx.compose.foundation.lazy.LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    modifier = Modifier.padding(top = 14.dp)
                                ) {
                                    items(
                                        books.drop(1),
                                        key = { "ed-beneath-${it.book.id}" }
                                    ) { shelf ->
                                        EditorialCoverCard(
                                            shelf = shelf, state = state,
                                            coverFile = coverFile,
                                            subLine = editorialFeatureLine(state, shelf),
                                            selected = selection?.contains(shelf.book.id) == true,
                                            onClick = { onCoverClick(shelf) },
                                            onLongClick = { onCoverLongClick(shelf) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                    // Books with no status and no progress belong
                    // to no department — they still get a shelf
                    // here so nothing in the library is unreachable
                    // from the front page.
                    if (unclassified.isNotEmpty()) {
                        item(key = "ed-unclassified-head") {
                            Box(Modifier.padding(top = 18.dp, bottom = 10.dp)) {
                                EditorialSectionHeader(
                                    title = "ON THE SHELF",
                                    countText = if (unclassified.size == 1) {
                                        "1 title"
                                    } else {
                                        "${unclassified.size} titles"
                                    }
                                )
                            }
                        }
                        item(key = "ed-unclassified-shelf") {
                            androidx.compose.foundation.lazy.LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                                contentPadding = PaddingValues(horizontal = 16.dp)
                            ) {
                                items(
                                    unclassified,
                                    key = { "ed-uncl-${it.book.id}" }
                                ) { shelf ->
                                    EditorialCoverCard(
                                        shelf = shelf, state = state,
                                        coverFile = coverFile,
                                        subLine = editorialFeatureLine(state, shelf),
                                        selected = selection?.contains(shelf.book.id) == true,
                                        onClick = { onCoverClick(shelf) },
                                        onLongClick = { onCoverLongClick(shelf) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // S4 — the filter/sort sheet: Sort radio list, Show
    // checkboxes (the flags' only home), "New chapters" door.
    if (sheetOpen) {
        androidx.compose.material3.ModalBottomSheet(
            onDismissRequest = { sheetOpen = false },
            sheetState = androidx.compose.material3
                .rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp)
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    "Sort",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                M3LibSort.entries.forEach { option ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { m3Sort = option }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        androidx.compose.material3.RadioButton(
                            selected = m3Sort == option,
                            onClick = { m3Sort = option }
                        )
                        Text(
                            option.label,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
                Text(
                    "Show",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { downloadedOnly = !downloadedOnly }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    androidx.compose.material3.Checkbox(
                        checked = downloadedOnly,
                        onCheckedChange = { downloadedOnly = it }
                    )
                    Text(
                        "Downloaded only",
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { favouritesOnly = !favouritesOnly }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    androidx.compose.material3.Checkbox(
                        checked = favouritesOnly,
                        onCheckedChange = { favouritesOnly = it }
                    )
                    Text(
                        "Favourites only",
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                val newChapterCounts = state.metas.values
                    .mapNotNull { it.newChapterCount.takeIf { n -> n > 0 } }
                androidx.compose.material3.ListItem(
                    headlineContent = { Text("New chapters") },
                    supportingContent = {
                        Text(
                            if (newChapterCounts.isNotEmpty()) {
                                "${newChapterCounts.sum()} new across " +
                                    "${newChapterCounts.size} book(s)"
                            } else {
                                "Nothing new right now"
                            }
                        )
                    },
                    leadingContent = {
                        Icon(
                            Icons.Filled.NewReleases,
                            contentDescription = null
                        )
                    },
                    modifier = Modifier.clickable {
                        sheetOpen = false
                        onOpenUpdates()
                    }
                )
            }
        }
    }

    // Bulk remove (selection bar's Remove door).
    if (bulkRemoveOpen) {
        val picked = selection ?: emptySet()
        val pickedBooks = state.books.filter { it.book.id in picked }
        LaunchedEffect(picked) {
            val bytes = viewModel.bulkDownloadedSizeBytes(picked)
            bulkRemoveSize =
                app.xreader.data.files.StorageMeter.formatBytes(bytes)
        }
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

    // S5's one delete dialog, shared with the Nocturne path.
    app.xreader.ui.common.BookDeleteDialogs(
        book = removeTarget?.book,
        downloadedSizeBytes = { viewModel.downloadedSizeBytes(it) },
        onDismiss = { removeTarget = null },
        onRemoveBook = { book, deleteFiles ->
            viewModel.removeBook(book, deleteFiles)
        },
        onRemoveLocalBook = { book, mode, context ->
            viewModel.removeLocalBook(book, mode, context)
        }
    )
}
