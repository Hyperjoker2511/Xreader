package app.xreader.ui.book
import app.xreader.ui.theme.NocturneOverline
import app.xreader.ui.theme.aiAccentColor
import app.xreader.ui.theme.errorTextColor
import app.xreader.ui.theme.primaryTextColor
import app.xreader.ui.theme.sageTextColor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FiberNew
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import app.xreader.data.db.Chapter
import app.xreader.data.db.DownloadState
import app.xreader.data.library.ReadingStatus
import app.xreader.ui.common.EmptyState
import app.xreader.ui.common.GlassCard
import app.xreader.ui.common.GlassSheetActionRow
import app.xreader.ui.common.XreaderPrimaryButton
import coil.compose.AsyncImage
import java.io.File

/**
 * Book Detail — Nocturne Violet reskin (2026-10-30, the user's
 * Stitch design system; reference: design/nocturne-violet/screens/
 * novel-details.html). Centred hero with violet halos and a
 * glowing cover, badge + tag chips, the big Continue pill, a
 * 4-tile quick-action row, the source banner, the reading-
 * progress card, the synopsis card and the chapter list in the
 * design's row styling.
 *
 * VISUAL RESKIN ONLY: every function the Ember-era screen had
 * survives in the new layout (favourite, reading status + Track,
 * exports, highlights, refresh, Catch me up, Lore, next unread,
 * chapter search/filters, progressive loading + Load full list,
 * per-chapter + bulk downloads, the chapters recovery doors,
 * tags, other sources/merge, reviews/comments/related, AI
 * suggestions, cleanup/remove, both dialogs, the download
 * sheet). Colours come from the ACTIVE scheme's Material roles
 * (the "Nocturne violet" scheme in AppSchemes carries the exact
 * design hexes), so the user's customiser keeps working; real
 * backdrop blur comes from the existing Haze glass path
 * (GlassCard / frostedGlass) wherever the design wants glass.
 */
@OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class
)
@Composable
fun BookDetailScreen(
    viewModel: BookDetailViewModel,
    coverFile: (String?) -> File?,
    assetFile: (bookId: Long, fileName: String) -> File,
    onBack: () -> Unit,
    onReadChapter: (chapterIndex: Int) -> Unit,
    /** v1.5.6 (§3.2): Contents taps on an in-place local book
     * carry the outline entry's target JSON (e.g. {"section": 4})
     * straight to the local reader. Null = fall back to the
     * chapter-index door (resume point). */
    onReadLocalTarget: ((bookId: Long, targetJson: String) -> Unit)? = null,
    onExportEpub: () -> Unit,
    onExportCbz: () -> Unit,
    onOpenLore: () -> Unit = {},
    onOpenRelated: (Long) -> Unit = {},
    onOpenTag: (String) -> Unit = {},
    /** v1.0.2 (R26): find an AI-suggested title across sources. */
    onFindTitle: (String) -> Unit = {},
    /** v1.0.5 (B1): the Chapters section's "Go to Repair" door —
     * lands on the source's Details page, where Repair lives. */
    onOpenSourceDetails: (extensionId: Long) -> Unit = {},
    /** v1.0.6 (W4.3): the Chapters section's site-check door —
     * opens the app-styled check screen for this book's source. */
    onSiteCheck: (extensionId: Long) -> Unit = {},
    /** v1.3.2 (scope A2): the session hand-off door — when a
     * chapters failure is a site check or a sign-in gate, the
     * recovery card offers the one-time sanctioned screen
     * (solve the check / sign in) as its primary action. */
    onSessionHandoff: (
        extensionId: Long,
        kind: app.xreader.data.extension.SessionNeed.Kind
    ) -> Unit = { _, _ -> }
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val engineLabel by viewModel.engineLabel.collectAsStateWithLifecycle()
    // v1.5.6 (§3): in-place local books show their own Contents
    // (local_outline) instead of chapter rows.
    val localOutline by viewModel.localOutline.collectAsStateWithLifecycle()
    val book = state.book
    // v1.0.5 (B2/E6): the Download sheet's open state — the three
    // choices (all / next 10 / latest unread) live there, beside
    // Start reading, never only in a long-press sheet.
    var downloadSheetOpen by remember { mutableStateOf(false) }
    /** v1.5.8 (J2/E3): the chapter-settings sheet's open state —
     * sort/display choices + the scanlator filter live there. */
    var chapterSettingsOpen by remember { mutableStateOf(false) }
    // v1.0.7 (draw-check ask): chapter search + filters — a
    // 1,692-chapter list can't be scrolled by hand, and a
    // novel split into parts buries Part 1 under Part 2.
    var chapterQuery by remember { mutableStateOf("") }
    // v1.5.7 (Finding 5, second bug): the "Jump to chapter…"
    // field's OWN text. The field used to BE chapterQuery, so
    // every keystroke filtered the list by row text — typing
    // "1" showed "863 of 1200" rows whose dates/titles contain
    // a "1". The field's text is separate state: a purely
    // numeric entry is a JUMP (ChapterJump — scroll +
    // highlight) and, since v1.5.38 (Fix C2), ALSO a number
    // search: it sets chapterQuery, whose number rung is an
    // exact identity match, so the list narrows to the named
    // chapter without the old substring flood. Non-numeric
    // text flows into chapterQuery as the text search,
    // exactly as before. The field, its placeholder and its
    // styling are byte-identical — behaviour only.
    var jumpFieldText by remember { mutableStateOf("") }
    /** The chapter row a jump landed on (transient highlight,
     * reusing the current chapter's container treatment). */
    var jumpTargetId by remember { mutableStateOf<Long?>(null) }
    /** Display position a jump is waiting to scroll to, once
     * the window has grown enough to compose the row. */
    var pendingJumpPos by remember { mutableStateOf(-1) }
    val chapterListState =
        androidx.compose.foundation.lazy.rememberLazyListState()
    var chapterStateFilter by remember {
        mutableStateOf(
            app.xreader.domain.reader.ChapterListFilter.State.ALL
        )
    }
    var chapterPartFilter by remember { mutableStateOf<String?>(null) }
    // Nocturne reskin: the reference's sort toggle. Default is
    // reading order (oldest first) — the order this screen has
    // always shown; the toggle flips the DISPLAY only, never
    // the stored list, progress or edge-loading maths.
    var newestFirst by remember { mutableStateOf(false) }
    val chapterParts = remember(state.chapters) {
        app.xreader.domain.reader.ChapterListFilter
            .availableParts(state.chapters)
    }
    val visibleChapters = remember(
        state.chapters, chapterQuery, chapterStateFilter, chapterPartFilter
    ) {
        app.xreader.domain.reader.ChapterListFilter.apply(
            state.chapters, chapterQuery, chapterStateFilter,
            chapterPartFilter
        )
    }
    // v1.3.2 (items 78c/79): the display order is keyed to
    // each chapter's OWN number, never to stored position —
    // under "Oldest" a newest-first stored list displays
    // ascending by identity; under "Newest", descending.
    val displayChapters = remember(visibleChapters, newestFirst) {
        app.xreader.domain.reader.ChapterDisplayOrder.ordered(
            visibleChapters, newestFirst
        )
    }
    // v1.3.1 (V120-CHECK 54): windowed chapter display — the
    // list renders 100 rows at a time, never the whole book
    // (a 3,229-row list never exists in full; the 2 GB tier
    // thanks this too). Display-only: stored order and the
    // full data set are untouched, and the window follows
    // the current display order in both sort directions.
    // The window resets when the book, the search or the
    // sort changes. v1.5.37 (S6): the window's end is a
    // scroll sentinel, not a button — composing it grows
    // the window by 200 and calls the same near-edge growth
    // the scroll path uses, so reaching past the fetched
    // range pulls the next chunk — never a background full
    // load.
    var chapterWindow by remember(
        state.book?.id, chapterQuery, chapterStateFilter,
        chapterPartFilter, newestFirst
    ) { mutableStateOf(100) }
    val displayedChapters = remember(displayChapters, chapterWindow) {
        displayChapters.take(chapterWindow)
    }
    // A jump target (the Continue chapter) beyond the window
    // expands the window to reach it — search and Continue
    // must never be fenced off by the window.
    LaunchedEffect(displayChapters, state.continueChapterIndex) {
        val pos = displayChapters.indexOfFirst {
            it.index == state.continueChapterIndex
        }
        if (pos >= chapterWindow) chapterWindow = pos + 1
    }
    // v1.5.37 (combined build, S6): the near-edge growth
    // trigger is HOISTED here — one flow over the list's
    // visible items reports the furthest visible chapter's
    // stored index to the same growth trigger the rows used
    // to fire individually. (The per-row LaunchedEffect it
    // replaces ran the trigger — and, behind it, a full-list
    // head-missing scan on the main thread — for every row
    // composed during a fling: BIG-BOOK-REFRESH-FORENSICS
    // Q8b, half of the big-list smear.)
    LaunchedEffect(chapterListState, displayChapters) {
        snapshotFlow {
            val byId = displayChapters.associateBy { it.id }
            chapterListState.layoutInfo.visibleItemsInfo
                .mapNotNull { item ->
                    ((item.key as? String)
                        ?.removePrefix("chapter-")
                        ?.toLongOrNull())
                        ?.let { byId[it]?.index }
                }
                .maxOrNull()
        }
            .distinctUntilChanged()
            .collect { maxIndex ->
                if (maxIndex != null) {
                    viewModel.onChapterNearEdge(maxIndex)
                }
            }
    }
    val chapterFilterActive = chapterQuery.isNotBlank() ||
        chapterStateFilter !=
        app.xreader.domain.reader.ChapterListFilter.State.ALL ||
        chapterPartFilter != null
    val context = androidx.compose.ui.platform.LocalContext.current
    val clipboard = LocalClipboardManager.current
    // Phase 6: highlights → Markdown, saved where the user picks.
    val markdownLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts
            .CreateDocument("text/markdown")
    ) { uri ->
        if (uri != null) {
            val stream = runCatching {
                context.contentResolver.openOutputStream(uri)
            }.getOrNull()
            if (stream != null) {
                viewModel.exportHighlightsMarkdown(stream) { message ->
                    viewModel.notifyMessage(message)
                }
            }
        }
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Reference top chrome: back chevron, "Book Details",
        // favourite bookmark. Translucent black in the HTML;
        // here the solid canvas (the list scrolls in its own
        // column below, so there is nothing to blur behind it).
        Row(
            Modifier
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                "Book Details",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 4.dp)
            )
            if (book != null) {
                IconButton(
                    onClick = { viewModel.setFavourite(!state.favourite) }
                ) {
                    Icon(
                        if (state.favourite) {
                            Icons.Filled.Bookmark
                        } else {
                            Icons.Filled.BookmarkBorder
                        },
                        contentDescription = if (state.favourite) {
                            "Remove from Favourites"
                        } else {
                            "Add to Favourites"
                        },
                        tint = if (state.favourite) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
        }
        if (book == null) {
            // v1.0.1: never flash "not found" while the row is still
            // loading — the genuine state is only for a book that was
            // actually deleted, and it offers the way back.
            if (state.loading) {
                Column(
                    Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator()
                }
            } else {
                EmptyState(
                    title = "Book not found",
                    message = "This book isn't in your library anymore.",
                    modifier = Modifier.weight(1f),
                    actionLabel = "Back to library",
                    onAction = onBack
                )
            }
            return@Column
        }
        LaunchedEffect(book.id) {
            viewModel.loadEnrichment()
            viewModel.loadPalette()
        }
        // v1.0.5 (B1): the Chapters section's pure state — which
        // form it renders and which recovery doors it may offer.
        val section = ChaptersSection.state(
            chapterCount = state.chapters.size,
            chaptersLoading = state.chaptersLoading,
            chaptersError = state.chaptersError,
            isExtensionBook = book.sourceType ==
                app.xreader.data.db.SourceType.EXTENSION,
            remoteId = book.remoteId,
            isLinkSource = state.isLinkSource,
            needsSiteCheck = state.sourceNeedsSiteCheck
        )
        // v1.5.7 (Finding 5): the jump scroll. The chapter rows
        // sit deep inside this page's single LazyColumn, so the
        // scroll index is the chapter's display position plus
        // the number of items the column emits before the first
        // chapter row — counted here from the same conditions
        // that gate those items below (hero, CTA, the source
        // banner, the progress card, Synopsis, the local
        // Contents block, the Chapters header). When the target
        // row is beyond the display window, the pending effect
        // below first grows the window (the field's own handler
        // already requested it), then scrolls once the row
        // exists. An out-of-range entry sets no pending
        // position at all: the page stays put.
        val chapterRowPrefixCount = 2 +
            (if (book.sourceType ==
                app.xreader.data.db.SourceType.EXTENSION
            ) 1 else 0) +
            (if (state.progress != null || state.downloadedCount > 0) {
                1
            } else {
                0
            }) +
            1 +
            (if (localOutline.isNotEmpty()) {
                1 + localOutline.size
            } else {
                0
            }) +
            (if (book.sourceType ==
                app.xreader.data.db.SourceType.LOCAL_FILE &&
                book.documentUri == null && book.filePath == null &&
                localOutline.isEmpty()
            ) 1 else 0) +
            1 +
            (if (section.kind == ChaptersSectionKind.LIST &&
                visibleChapters.isEmpty() && state.chapters.isNotEmpty()
            ) 1 else 0)
        LaunchedEffect(pendingJumpPos, displayedChapters.size) {
            val pos = pendingJumpPos
            if (pos >= 0 && pos < displayChapters.size &&
                pos >= displayedChapters.size
            ) {
                // (displayChapters = the full display list;
                // displayedChapters = its windowed head.)
                // The window reset (e.g. the jump cleared a
                // text search, a remember key) or simply hasn't
                // reached the target yet: grow it to the target
                // and let this effect re-run when the row lands.
                chapterWindow = pos + 1
            }
            if (pos >= 0 && pos < displayedChapters.size) {
                // v1.5.38 (Fix C1, V1537-PROOF-FORENSICS §C):
                // the trigger clears in the FINALLY, after the
                // scroll returns — never before it. This effect
                // is keyed on pendingJumpPos, so clearing it
                // first changed the key mid-flight and the
                // restarted effect cancelled the animation in
                // its first frame: every off-screen jump died
                // silently, in every book. The equality guard
                // keeps a NEWER jump typed during this scroll
                // from being clobbered when the old one lands.
                try {
                    chapterListState.animateScrollToItem(
                        chapterRowPrefixCount + pos
                    )
                } finally {
                    if (pendingJumpPos == pos) pendingJumpPos = -1
                }
            }
        }
        // The jump highlight is transient: it fades back to the
        // plain row after a few seconds (the highlight itself
        // is the current chapter's own container treatment).
        LaunchedEffect(jumpTargetId) {
            if (jumpTargetId != null) {
                kotlinx.coroutines.delay(2_800)
                jumpTargetId = null
            }
        }
        val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
        // Tweak 2 (Nocturne): the Continue pill's numbers, derived
        // from the ViewModel's existing progress state only —
        // chapters read + the in-chapter fraction of the chapter
        // being read, over the loaded chapter count.
        val continueChapter = state.chapters.firstOrNull {
            it.index == state.continueChapterIndex
        }
        // v1.5.7 (scope F smoothness): the read count and the
        // newest chapter index are remembered per chapter list
        // — the newest index in particular used to be recomputed
        // by maxOfOrNull INSIDE every chapter row's arguments
        // (a full-list scan per row, per recomposition).
        val readCount = remember(state.chapters) {
            state.chapters.count { it.read }
        }
        val maxChapterIndex = remember(state.chapters) {
            state.chapters.maxOfOrNull { it.index } ?: -1
        }
        val bookPercent = if (state.chapters.isEmpty()) {
            // v1.5.6 (audit §1.5 trap): a local book's progress is
            // locator-keyed — its percentage IS the stored book
            // fraction, never nulled by the empty chapter list.
            if (book.sourceType ==
                app.xreader.data.db.SourceType.LOCAL_FILE &&
                state.progress?.locatorJson != null
            ) {
                (state.progress!!.fraction * 100).toInt().coerceIn(0, 100)
            } else {
                0
            }
        } else {
            val progressNow = state.progress
            val inChapter = if (progressNow != null &&
                continueChapter?.read == false
            ) {
                progressNow.fraction
            } else {
                0f
            }
            (((readCount + inChapter) / state.chapters.size) * 100)
                .toInt().coerceIn(0, 100)
        }
        val statusLabel = when (state.bookMeta.status) {
            ReadingStatus.READING -> "Reading"
            ReadingStatus.COMPLETED -> "Completed"
            ReadingStatus.ON_HOLD -> "On hold"
            ReadingStatus.PLAN_TO_READ -> "Plan to Read"
            ReadingStatus.DROPPED -> "Dropped"
            ReadingStatus.NONE -> null
        }
        LazyColumn(
            Modifier.weight(1f),
            // v1.5.7 (Finding 5): the list state the chapter
            // jump scrolls (previously the column had no state,
            // so no programmatic scroll was possible at all).
            state = chapterListState,
            // Design margin 20dp; bottom clearance 88dp (the
            // design's own rule) — tweak 1: nothing important
            // ever sits in the phone's dead bottom strip.
            contentPadding = PaddingValues(
                start = 20.dp, end = 20.dp, top = 4.dp, bottom = 88.dp
            ),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            item(key = "hero") {
                // Reference hero: violet radial halos over the
                // black canvas, the cover centred with a soft
                // violet rim glow, then title / author / badges /
                // genre chips, all centred. (The Ember-era
                // cover-palette backdrop is replaced by the
                // design's own halos; the palette still loads
                // for the Library's use of the shared cache.)
                Box(Modifier.fillMaxWidth()) {
                    Box(
                        Modifier
                            .align(Alignment.TopCenter)
                            .offset(y = (-48).dp)
                            .size(320.dp)
                            .background(
                                Brush.radialGradient(
                                    listOf(
                                        MaterialTheme.colorScheme.primary
                                            .copy(alpha = 0.16f),
                                        Color.Transparent
                                    )
                                )
                            )
                    )
                    Box(
                        Modifier
                            .align(Alignment.TopStart)
                            .offset(x = (-64).dp, y = 150.dp)
                            .size(240.dp)
                            .background(
                                Brush.radialGradient(
                                    listOf(
                                        MaterialTheme.colorScheme.tertiary
                                            .copy(alpha = 0.13f),
                                        Color.Transparent
                                    )
                                )
                            )
                    )
                    Column(
                        Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Cover with rim glow + rating badge.
                        Box(contentAlignment = Alignment.Center) {
                            Box(
                                Modifier
                                    .size(width = 152.dp, height = 216.dp)
                                    .blur(24.dp)
                                    .background(
                                        MaterialTheme.colorScheme.primary
                                            .copy(alpha = 0.30f),
                                        RoundedCornerShape(14.dp)
                                    )
                            )
                            val coverShape = RoundedCornerShape(14.dp)
                            Box {
                                val heroCover = coverFile(book.coverPath)
                                if (heroCover != null && heroCover.exists()) {
                                    // v1.5.4 (WS-1 item 7): the
                                    // generated cover is the
                                    // loading AND error content —
                                    // "still loading" and "cover
                                    // failed" are no longer the
                                    // same blank violet box (the
                                    // related-row pattern below,
                                    // applied to the hero).
                                    coil.compose.SubcomposeAsyncImage(
                                        model = heroCover,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .size(width = 144.dp, height = 208.dp)
                                            .clip(coverShape)
                                            .border(
                                                1.dp,
                                                Color.White.copy(alpha = 0.10f),
                                                coverShape
                                            ),
                                        loading = {
                                            app.xreader.ui.library.PlaceholderCover(
                                                title = book.title,
                                                author = book.author,
                                                modifier = Modifier.fillMaxSize()
                                            )
                                        },
                                        error = {
                                            app.xreader.ui.library.PlaceholderCover(
                                                title = book.title,
                                                author = book.author,
                                                modifier = Modifier.fillMaxSize()
                                            )
                                        }
                                    )
                                } else if (!book.coverUrl.isNullOrBlank()) {
                                    // v1.5.3 (item 10, finding #3
                                    // A4.3): no local cover file —
                                    // fall back to the book's cover
                                    // URL, the exact behaviour the
                                    // Library grid already has. A
                                    // stored row whose local file
                                    // never landed (or was lost) no
                                    // longer renders a placeholder
                                    // while its URL cover exists.
                                    // v1.5.4 (WS-1 item 7): same
                                    // generated-cover loading/error
                                    // treatment as the file branch.
                                    coil.compose.SubcomposeAsyncImage(
                                        model = book.coverUrl,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .size(width = 144.dp, height = 208.dp)
                                            .clip(coverShape)
                                            .border(
                                                1.dp,
                                                Color.White.copy(alpha = 0.10f),
                                                coverShape
                                            ),
                                        loading = {
                                            app.xreader.ui.library.PlaceholderCover(
                                                title = book.title,
                                                author = book.author,
                                                modifier = Modifier.fillMaxSize()
                                            )
                                        },
                                        error = {
                                            app.xreader.ui.library.PlaceholderCover(
                                                title = book.title,
                                                author = book.author,
                                                modifier = Modifier.fillMaxSize()
                                            )
                                        }
                                    )
                                } else {
                                    app.xreader.ui.library.PlaceholderCover(
                                        title = book.title,
                                        author = book.author,
                                        modifier = Modifier
                                            .size(width = 144.dp, height = 208.dp)
                                            .clip(coverShape)
                                    )
                                }
                                book.userRating?.let { rating ->
                                    Surface(
                                        modifier = Modifier
                                            .align(Alignment.TopStart)
                                            .padding(8.dp),
                                        shape = CircleShape,
                                        color = Color.Black.copy(alpha = 0.85f)
                                    ) {
                                        Text(
                                            "★ $rating",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = aiAccentColor(),
                                            modifier = Modifier.padding(
                                                horizontal = 8.dp, vertical = 3.dp
                                            )
                                        )
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(20.dp))
                        Text(
                            book.title,
                            style = MaterialTheme.typography.headlineMedium,
                            textAlign = TextAlign.Center,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (book.author.isNotBlank()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.padding(top = 6.dp)
                            ) {
                                Text(
                                    "By",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    book.author,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = primaryTextColor()
                                )
                                Box(
                                    Modifier
                                        .size(3.dp)
                                        .background(
                                            MaterialTheme.colorScheme.outline,
                                            CircleShape
                                        )
                                )
                                Text(
                                    statusLabel ?: book.localFormat.name,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        // Metric badges (reference row): format,
                        // rating, status, chapter count, downloads.
                        androidx.compose.foundation.layout.FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(top = 14.dp)
                        ) {
                            // v1.3.3 (V132-CHECK 12, scope S8): the
                            // PROVIDER stays visible on the opened
                            // book — its name rides the badge row,
                            // so a book found through grouped
                            // search keeps its origin on its page.
                            // (User-ordered exception to the book
                            // page's frozen look: one chip, same
                            // family as its neighbours.)
                            if (state.providerName.isNotBlank() &&
                                book.sourceType ==
                                app.xreader.data.db.SourceType.EXTENSION
                            ) {
                                NocturneChip(
                                    text = state.providerName,
                                    dotColor =
                                        MaterialTheme.colorScheme.primary,
                                    textColor =
                                        MaterialTheme.colorScheme.onSurface
                                )
                            }
                            NocturneChip(
                                text = book.localFormat.name,
                                textColor = MaterialTheme.colorScheme.onSurface
                            )
                            book.userRating?.let { rating ->
                                NocturneChip(
                                    text = "★ $rating",
                                    textColor = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            statusLabel?.let { label ->
                                NocturneChip(
                                    text = label,
                                    dotColor = Color(0xFF34D399),
                                    textColor = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            NocturneChip(
                                text = "${
                                    maxOf(book.totalChapters, state.chapters.size)
                                } chapters",
                                textColor = MaterialTheme.colorScheme.onSurface
                            )
                            NocturneChip(
                                text = "${state.downloadedCount}/${state.chapters.size} downloaded",
                                textColor = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        // Genre row (reference): the book's tags
                        // as display chips; tapping opens the tag
                        // page, exactly as the Tags section does.
                        if (state.tags.isNotEmpty()) {
                            androidx.compose.foundation.layout.FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.padding(top = 10.dp)
                            ) {
                                state.tags.take(6).forEach { tag ->
                                    NocturneChip(
                                        text = app.xreader.data.library.TagRegistry
                                            .displayFor(tag.slug),
                                        onClick = { onOpenTag(tag.slug) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
            item(key = "cta") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // The big Continue pill (reference): play
                    // disc + label + chapter/progress sub-line +
                    // forward chevron, violet fill with glow.
                    // Tweak 2: mid-read it reads "Continue" with
                    // the current chapter and the book's percent.
                    val continueEnabled = state.readBlockedReason == null
                    // v1.5.1 (item 4): a blocked read NEVER
                    // renders as a live violet Start Reading
                    // pill over an empty chapter state — the
                    // honest state REPLACES the button (the
                    // v1.5.0 field book showed the full-violet
                    // pill and the broken-source warning at
                    // once, and the tap dead-ended in the
                    // reader). The pill below is inert, muted,
                    // and carries the reason itself.
                    if (state.readCtaState ==
                        app.xreader.ui.book.ReadCtaState.CHAPTERS_UNAVAILABLE
                    ) {
                        ChaptersUnavailablePill(
                            reason = state.readBlockedReason!!
                        )
                    } else if (state.readCtaState ==
                        app.xreader.ui.book.ReadCtaState.FINDING_CHAPTERS
                    ) {
                        // v1.5.1 (item 4): while the chapter list
                        // is still loading, the CTA is the SAME
                        // inert, muted pill — never a live violet
                        // button over an unproven chapter state
                        // (a load that never settles can no longer
                        // leave a tappable Start Reading behind).
                        // v1.5.4 (WS-1 item 6c): while a site
                        // check is pending, the pill SAYS so —
                        // a load parked on a challenge must not
                        // claim to be finding chapters.
                        // v1.5.10 (B1): past the honest bound
                        // the pill stops claiming "Finding" and
                        // says the source is slow, with a visible
                        // Retry that re-joins the in-flight call
                        // (never a second walk). A pending site
                        // check still outranks the stall wording.
                        ChaptersUnavailablePill(
                            // v1.5.37 (combined build, item 4):
                            // while the fetch is in flight the
                            // pill headlines the FINDING state —
                            // "Chapters unavailable" is reserved
                            // for the settled failure above.
                            title = "Finding chapters…",
                            reason = when {
                                state.challengePending ->
                                    "Waiting for the site's check…"
                                state.chaptersStalled ->
                                    "Still finding — " +
                                        "this source is slow"
                                else ->
                                    "The chapter list is still " +
                                        "loading from the source."
                            }
                        )
                        if (state.chaptersStalled &&
                            !state.challengePending
                        ) {
                            TextButton(
                                onClick = { viewModel.refreshChapters() },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Retry")
                            }
                        }
                    } else {
                    Surface(
                        onClick = { onReadChapter(state.continueChapterIndex) },
                        enabled = continueEnabled,
                        modifier = Modifier.fillMaxWidth(),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        shadowElevation = if (continueEnabled) 10.dp else 0.dp
                    ) {
                        Row(
                            Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(
                                Modifier
                                    .size(40.dp)
                                    .background(
                                        MaterialTheme.colorScheme.onPrimary
                                            .copy(alpha = 0.16f),
                                        CircleShape
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Filled.PlayArrow,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimary
                                )
                            }
                            Column(Modifier.weight(1f)) {
                                Text(
                                    if (state.progress == null) {
                                        "Start Reading"
                                    } else {
                                        "Continue"
                                    },
                                    style = MaterialTheme.typography.labelLarge,
                                    maxLines = 1
                                )
                                Text(
                                    if (state.progress == null) {
                                        continueChapter?.let {
                                            // v1.3.2 (item 79): the
                                            // pill names the chapter
                                            // by its OWN number (the
                                            // identity-first target),
                                            // never by stored position.
                                            "Chapter " +
                                                (app.xreader.domain.reader
                                                    .ChapterDisplayOrder
                                                    .displayNumber(it)
                                                    ?: "${it.index + 1}") +
                                                " · ${it.title}"
                                        } ?: "Begin at chapter 1"
                                    } else {
                                        "Chapter " +
                                            (continueChapter?.let {
                                                app.xreader.domain.reader
                                                    .ChapterDisplayOrder
                                                    .displayNumber(it)
                                            } ?: "${state.continueChapterIndex + 1}") +
                                            (continueChapter?.let {
                                                " · ${it.title}"
                                            } ?: "") +
                                            " · $bookPercent%"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimary
                                        .copy(alpha = 0.85f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }
                    }
                    // v1.5.1 (item 4): the blocked reason used
                    // to render as a second line under a still-
                    // violet pill; it now lives inside
                    // ChaptersUnavailablePill above, which
                    // replaces the button outright.
                    // Cycle 1 (F3): the progress carry-over
                    // notes sit right under the Continue pill —
                    // that pill is where the resume they
                    // describe actually happens.
                    state.carriedOverNote?.let { note ->
                        Text(
                            note,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    state.progressLinkNote?.let { note ->
                        Text(
                            note,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    // Secondary quick actions (reference 4-tile
                    // grid): Favourite / Download / Catch me up /
                    // Lore & glossary — the same actions this
                    // screen has always had, in the design's
                    // tactile tiles.
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        QuickActionTile(
                            icon = if (state.favourite) {
                                Icons.Filled.Bookmark
                            } else {
                                Icons.Filled.BookmarkBorder
                            },
                            label = if (state.favourite) {
                                "Favourited"
                            } else {
                                "Favourite"
                            },
                            active = state.favourite,
                            onClick = { viewModel.setFavourite(!state.favourite) },
                            modifier = Modifier.weight(1f)
                        )
                        if (state.hasUndownloadedChapters) {
                            QuickActionTile(
                                icon = Icons.Filled.Download,
                                label = "Download",
                                active = false,
                                onClick = { downloadSheetOpen = true },
                                modifier = Modifier.weight(1f)
                            )
                        } else {
                            QuickActionTile(
                                icon = Icons.Filled.CheckCircle,
                                label = "On device",
                                active = true,
                                onClick = null,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        QuickActionTile(
                            icon = Icons.Filled.History,
                            label = "Catch me up",
                            active = false,
                            onClick = { viewModel.storySoFar() },
                            modifier = Modifier.weight(1f)
                        )
                        QuickActionTile(
                            icon = Icons.Filled.MenuBook,
                            label = "Lore",
                            active = false,
                            onClick = onOpenLore,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    // v1.5.7 (owner-ordered 2026-11-04, re-demanded
                    // 2026-11-05 — "I told you to add refresh
                    // function on the novel page"): the first-class
                    // Refresh, QuickNovel-style, in the primary
                    // action area in the tiles' own visual language
                    // (same tier-1 tile surface, full width so its
                    // label never crushes the 4-tile row). It calls
                    // the SAME shared-seam refresh the automatic
                    // Finding-5 head heal calls
                    // (BookDetailViewModel.refreshChapters →
                    // SourceBookSync.refresh): new chapters land,
                    // the count + synopsis refresh with it, and a
                    // list that lost its beginning is restored
                    // whole by this same fetch. While it runs the
                    // tile says so and won't re-fire; the result —
                    // count, new chapters, a restored beginning, or
                    // the honest failure — arrives as the page's
                    // usual message, and a failure leaves the
                    // existing list untouched. This is the page's
                    // ONE Refresh: the buried mid-page "Refresh
                    // chapters" text link is removed below.
                    // Manga books from sources ride the same tile:
                    // their lists live in the same store through
                    // the same sync seam (see the scope-F report).
                    if (book.sourceType ==
                        app.xreader.data.db.SourceType.EXTENSION
                    ) {
                        Surface(
                            onClick = { viewModel.refreshChapters() },
                            enabled = !state.chaptersLoading,
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme
                                .surfaceContainerLow,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                Modifier.padding(
                                    horizontal = 12.dp, vertical = 10.dp
                                ),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (state.chaptersLoading) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Icon(
                                        Icons.Filled.Refresh,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme
                                            .onSurfaceVariant,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Text(
                                    if (state.chaptersLoading) {
                                        "Refreshing…"
                                    } else {
                                        "Refresh"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme
                                        .onSurfaceVariant
                                )
                                Spacer(Modifier.weight(1f))
                                Text(
                                    "Get new chapters from the source",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme
                                        .onSurfaceVariant
                                )
                            }
                        }
                    }
                    // Reading status (v1.0.1, kept): the chips
                    // WRAP (B3/D25). v1.5.4 (owner, 17:46): the
                    // five statuses get their own labelled
                    // section — unheaded, they sat under the
                    // quick-action tiles in the same pill shape
                    // as the genre tags and read as genres
                    // ("The options look like the novel
                    // geners"). The heading is the page's
                    // section style (titleMedium, as Synopsis /
                    // Chapters), and the chips are styled as
                    // the CONTROL they are: the chosen status
                    // fills violet with a check, never the
                    // muted stadium pill of a genre tag.
                    // Behaviour is byte-identical: tap sets,
                    // tap-again clears to NONE.
                    Text(
                        "Status",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(Modifier.height(8.dp))
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(
                            // v1.5.3 (item 29): all five donor
                            // statuses, in the donor tab order.
                            ReadingStatus.READING to "Reading",
                            ReadingStatus.ON_HOLD to "On hold",
                            ReadingStatus.PLAN_TO_READ to "Plan to Read",
                            ReadingStatus.COMPLETED to "Completed",
                            ReadingStatus.DROPPED to "Dropped"
                        ).forEach { (status, label) ->
                            val statusSelected =
                                state.bookMeta.status == status
                            FilterChip(
                                selected = statusSelected,
                                onClick = {
                                    viewModel.setReadingStatus(
                                        if (state.bookMeta.status == status) {
                                            ReadingStatus.NONE
                                        } else {
                                            status
                                        }
                                    )
                                },
                                label = { Text(label) },
                                leadingIcon = if (statusSelected) {
                                    {
                                        Icon(
                                            Icons.Filled.Check,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                } else {
                                    null
                                },
                                colors = androidx.compose.material3
                                    .FilterChipDefaults.filterChipColors(
                                        containerColor =
                                            MaterialTheme.colorScheme
                                                .surfaceContainerHigh,
                                        labelColor =
                                            MaterialTheme.colorScheme
                                                .onSurface,
                                        selectedContainerColor =
                                            MaterialTheme.colorScheme
                                                .primary,
                                        selectedLabelColor =
                                            MaterialTheme.colorScheme
                                                .onPrimary,
                                        selectedLeadingIconColor =
                                            MaterialTheme.colorScheme
                                                .onPrimary
                                    ),
                                border = androidx.compose.material3
                                    .FilterChipDefaults.filterChipBorder(
                                        enabled = true,
                                        selected = statusSelected,
                                        borderColor =
                                            MaterialTheme.colorScheme
                                                .outlineVariant,
                                        selectedBorderColor =
                                            MaterialTheme.colorScheme
                                                .primary
                                    )
                            )
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        androidx.compose.material3.Switch(
                            checked = state.bookMeta.tracked,
                            onCheckedChange = { viewModel.setTracked(it) }
                        )
                        Text(
                            "Track this book — tell me when new chapters land",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
            if (book.sourceType == app.xreader.data.db.SourceType.EXTENSION) {
                item(key = "source-banner") {
                    // Reference source & network banner: the
                    // host the book came from, how much of it
                    // is on the device, and the site check
                    // (browser-view sources that need setup).
                    // v1.5.4 (WS-8): no Repair door on this
                    // line — beside the chapter count it read
                    // as "this source is broken" on healthy
                    // books (owner, 2026-11-04). Repair lives
                    // on the Chapters recovery card below
                    // (failure states) and on the source's own
                    // Details page, and nowhere beside a
                    // chapter-state line. Addendum 3 (same
                    // evening): no "partial list" marker either
                    // — partiality is invisible; the line shows
                    // the plain loaded count, complete or not.
                    val host = remember(section.sourcePageUrl) {
                        section.sourcePageUrl?.let { url ->
                            runCatching { java.net.URI(url).host }
                                .getOrNull()
                        }
                    }
                    Surface(
                        Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLowest
                    ) {
                        Row(
                            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                Modifier
                                    .size(8.dp)
                                    .background(
                                        if (state.sourceNeedsSiteCheck) {
                                            Color(0xFFF59E0B)
                                        } else {
                                            Color(0xFF34D399)
                                        },
                                        CircleShape
                                    )
                            )
                            Text(
                                host ?: "Source book",
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "· ${state.chapters.size} loaded",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.weight(1f))
                            if (state.sourceNeedsSiteCheck) {
                                TextButton(
                                    onClick = {
                                        book.extensionId?.let {
                                            onSiteCheck(it)
                                        }
                                    }
                                ) { Text("Site check") }
                            }
                        }
                    }
                }
            }
            if (state.progress != null || state.downloadedCount > 0) {
                item(key = "progress") {
                    // Reference reading-progress card: header,
                    // luminescent bar, then the two micro-metric
                    // tiles — time left at the reader's own pace
                    // and what's on the device.
                    val remainingEstimate = remember(state.chapters, state.wpm) {
                        app.xreader.domain.reader.WpmEstimator
                            .estimateRemainingMinutes(
                                state.chapters.map {
                                    app.xreader.domain.reader
                                        .WpmEstimator.ChapterWords(
                                            it.read, it.wordCount
                                        )
                                },
                                state.wpm
                            )
                    }
                    val latestChapterLabel = remember(state.chapters) {
                        app.xreader.domain.reader.ChapterDateText
                            .latestRelative(
                                state.chapters.map { it.dateText }
                            )
                    }
                    GlassCard(Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Filled.History,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    "Reading Progress",
                                    style = MaterialTheme.typography.labelLarge,
                                    modifier = Modifier.padding(start = 8.dp)
                                )
                                Spacer(Modifier.weight(1f))
                                latestChapterLabel?.let { label ->
                                    Text(
                                        "Latest · $label",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme
                                            .onSurfaceVariant
                                    )
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "Chapter ${state.continueChapterIndex + 1}" +
                                        " / ${state.chapters.size}",
                                    style = MaterialTheme.typography.labelMedium
                                )
                                Spacer(Modifier.weight(1f))
                                Text(
                                    "$bookPercent%",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = primaryTextColor()
                                )
                            }
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(CircleShape)
                                    .background(
                                        MaterialTheme.colorScheme
                                            .surfaceContainerHighest
                                    )
                            ) {
                                Box(
                                    Modifier
                                        .fillMaxWidth(bookPercent / 100f)
                                        .height(8.dp)
                                        .clip(CircleShape)
                                        .background(
                                            Brush.horizontalGradient(
                                                listOf(
                                                    MaterialTheme.colorScheme
                                                        .tertiary,
                                                    MaterialTheme.colorScheme
                                                        .primary
                                                )
                                            )
                                        )
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme
                                        .surfaceContainer,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Row(
                                        Modifier.padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Box(
                                            Modifier
                                                .size(28.dp)
                                                .background(
                                                    MaterialTheme.colorScheme
                                                        .surfaceContainerHighest,
                                                    RoundedCornerShape(8.dp)
                                                ),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                Icons.Filled.Schedule,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme
                                                    .primary,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                        Column {
                                            Text(
                                                "TIME LEFT",
                                                style = NocturneOverline,
                                                color = MaterialTheme.colorScheme
                                                    .onSurfaceVariant
                                            )
                                            Text(
                                                if (remainingEstimate.first > 0) {
                                                    app.xreader.domain.reader
                                                        .WpmEstimator.formatMinutes(
                                                            remainingEstimate.first
                                                        ) + " left"
                                                } else {
                                                    "—"
                                                },
                                                style = MaterialTheme.typography
                                                    .labelMedium
                                            )
                                        }
                                    }
                                }
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme
                                        .surfaceContainer,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Row(
                                        Modifier.padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Box(
                                            Modifier
                                                .size(28.dp)
                                                .background(
                                                    MaterialTheme.colorScheme
                                                        .surfaceContainerHighest,
                                                    RoundedCornerShape(8.dp)
                                                ),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                Icons.Filled.Download,
                                                contentDescription = null,
                                                tint = sageTextColor(),
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                        Column {
                                            Text(
                                                "ON DEVICE",
                                                style = NocturneOverline,
                                                color = MaterialTheme.colorScheme
                                                    .onSurfaceVariant
                                            )
                                            Text(
                                                "${state.downloadedCount} chapters",
                                                style = MaterialTheme.typography
                                                    .labelMedium
                                            )
                                        }
                                    }
                                }
                            }
                            // Sweep addendum: the rhythm line —
                            // shown only when the cadence is
                            // actually knowable (UpdateRhythm).
                            state.expectedNextLabel?.let { label ->
                                Text(
                                    label,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme
                                        .onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
            item(key = "synopsis") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Reference synopsis card: heading + source
                    // chip, 3-line clamp with Read more.
                    GlassCard(Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "Synopsis",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Spacer(Modifier.weight(1f))
                                if (book.sourceType ==
                                    app.xreader.data.db.SourceType.EXTENSION
                                ) {
                                    NocturneChip(text = "From source")
                                }
                            }
                            if (book.description.isNotBlank()) {
                                var expanded by remember {
                                    mutableStateOf(false)
                                }
                                // v1.3.2 (item 76): the expander
                                // answers to the LAYOUT, not a
                                // character count — a synopsis
                                // that visually overflows the
                                // 3-line clamp ALWAYS offers
                                // Read more, at any length (the
                                // old length > 200 rule left
                                // shorter truncated synopses
                                // with no way to read the rest).
                                var synopsisOverflows by remember {
                                    mutableStateOf(
                                        app.xreader.domain.reader
                                            .SynopsisText
                                            .mayOverflow(book.description.length)
                                    )
                                }
                                Text(
                                    book.description,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme
                                        .onSurfaceVariant,
                                    maxLines = if (expanded) {
                                        Int.MAX_VALUE
                                    } else {
                                        3
                                    },
                                    overflow = TextOverflow.Ellipsis,
                                    onTextLayout = { layout ->
                                        if (!expanded) {
                                            synopsisOverflows =
                                                layout.hasVisualOverflow
                                        }
                                    }
                                )
                                if (expanded || synopsisOverflows) {
                                    TextButton(
                                        onClick = { expanded = !expanded }
                                    ) {
                                        Text(
                                            if (expanded) {
                                                "Show less"
                                            } else {
                                                "Read more"
                                            }
                                        )
                                        Icon(
                                            if (expanded) {
                                                Icons.Filled.ExpandLess
                                            } else {
                                                Icons.Filled.ExpandMore
                                            },
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            } else {
                                // R12/R16: the designed no-synopsis
                                // state — never a silent gap; a
                                // source book can look for its
                                // details on the user's other
                                // sources.
                                Text(
                                    "No synopsis yet for this book.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme
                                        .onSurfaceVariant
                                )
                                if (book.sourceType ==
                                    app.xreader.data.db.SourceType.EXTENSION
                                ) {
                                    TextButton(
                                        onClick = {
                                            viewModel.findDetailsFromSources()
                                        }
                                    ) {
                                        Text("Find details from a source")
                                    }
                                }
                            }
                            // Next unread (R16): one tap to the
                            // first chapter you haven't finished.
                            val nextUnread = state.chapters
                                .firstOrNull { !it.read }
                            if (nextUnread != null && state.progress != null) {
                                TextButton(
                                    onClick = { onReadChapter(nextUnread.index) }
                                ) {
                                    Text("Next unread: ${nextUnread.title}")
                                }
                            }
                        }
                    }
                    // Book tools (v1.0.5 B4/B3): exports + refresh
                    // + Copy details in ONE wrapping row of pill
                    // buttons — FlowRow keeps every label whole.
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        TextButton(onClick = onExportEpub) { Text("Export EPUB") }
                        TextButton(onClick = onExportCbz) { Text("Export CBZ") }
                        TextButton(
                            onClick = {
                                markdownLauncher.launch(
                                    (book.title.replace(Regex("[^A-Za-z0-9]+"), "-")
                                        .trim('-') + "-highlights.md")
                                )
                            }
                        ) { Text("Highlights (.md)") }
                        // v1.5.7: the buried "Refresh chapters"
                        // text link that used to sit here is
                        // REMOVED — Refresh is now the first-class
                        // action in the primary action row above
                        // (owner-ordered), and the page keeps
                        // exactly one obvious Refresh.
                        TextButton(
                            onClick = {
                                // v1.5.8 (K5): Copy details
                                // tells the truth — chapters
                                // error, the sync-seam log lines
                                // and (Fenrir) the unlock lines
                                // ride along via BookCopyDetails.
                                clipboard.setText(
                                    AnnotatedString(
                                        BookCopyDetails.build(
                                            title = book.title,
                                            author = book.author,
                                            chapterCount = state.chapters.size,
                                            downloadedCount = state.downloadedCount,
                                            statusLabel = statusLabel,
                                            description = book.description,
                                            chaptersError = state.chaptersError,
                                            isFenrirBook = BookCopyDetails.isFenrir(
                                                state.providerName
                                            ),
                                            logEntries =
                                                app.xreader.data.extension
                                                    .DiagnosticLog.entries()
                                        )
                                    )
                                )
                                viewModel.notifyMessage("Book details copied")
                            }
                        ) { Text("Copy details") }
                    }
                }
            }
            // v1.5.6 (§3.2): an in-place local book's Contents —
            // the book's own outline from its file. Tapping a row
            // hands the entry's target JSON to the local reader
            // via onReadLocalTarget when the shell provided it;
            // otherwise the chapter-index door opens the book at
            // its resume point.
            if (localOutline.isNotEmpty()) {
                item(key = "local-contents-header") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            "Contents",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            "From the book's own file — it reads exactly as published.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                items(
                    count = localOutline.size,
                    key = { i -> "local-outline-$i" }
                ) { i ->
                    val entry = localOutline[i]
                    TextButton(
                        onClick = {
                            val targetDoor = onReadLocalTarget
                            if (targetDoor != null) {
                                targetDoor(book.id, entry.targetJson)
                            } else {
                                onReadChapter(entry.idx)
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(entry.title, Modifier.fillMaxWidth())
                    }
                }
            }
            // v1.5.6 (§3.4(b)): the legacy lane's offered re-add —
            // plain words, never forced, never silent.
            if (book.sourceType ==
                app.xreader.data.db.SourceType.LOCAL_FILE &&
                book.documentUri == null && book.filePath == null &&
                localOutline.isEmpty()
            ) {
                item(key = "local-readd-offer") {
                    LegacyReAddCard(viewModel = viewModel)
                }
            }
            // v1.0.5 (B1/D26): the Chapters section ALWAYS opens
            // with its header — count and order when there is a
            // list — so the section exists even when the list
            // doesn't.
            item(key = "chapters-header") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Chapters",
                            style = MaterialTheme.typography.titleMedium
                        )
                        if (section.kind == ChaptersSectionKind.LIST) {
                            Text(
                                if (chapterFilterActive) {
                                    "  ${visibleChapters.size} of " +
                                        "${section.chapterCount}"
                                } else {
                                    "  (${section.chapterCount})"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.weight(1f))
                            // v1.5.8 (J2/E3): the chapter
                            // settings door — sort/display +
                            // the scanlator filter, one tap
                            // from the list it shapes.
                            TextButton(
                                onClick = { chapterSettingsOpen = true }
                            ) { Text("Chapter settings") }
                            val headerLatest = remember(state.chapters) {
                                app.xreader.domain.reader.ChapterDateText
                                    .latestRelative(
                                        state.chapters.map { it.dateText }
                                    )
                            }
                            headerLatest?.let { label ->
                                Box(
                                    Modifier
                                        .size(6.dp)
                                        .background(
                                            MaterialTheme.colorScheme.primary,
                                            CircleShape
                                        )
                                )
                                Text(
                                    " Latest · $label",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme
                                        .onSurfaceVariant
                                )
                            }
                        }
                    }
                    // v1.5.4 (WS-8, owner addendum 3 —
                    // supersedes the v1.0.8 announcement below
                    // in history): partiality is INVISIBLE in
                    // the normal flow. The old announcement
                    // card (loaded count + a promise that more
                    // would arrive while reading) is gone —
                    // scrolling to the loaded edge grows the
                    // list silently (each row's edge trigger
                    // resumes the walk from its stored cursor),
                    // and that is the only growth the user
                    // experiences. The ONE allowed surfacing:
                    // a load-more attempt that actually FAILED
                    // shows this plain-words retry row — a
                    // failed load is the only "broke" signal
                    // (owner, 2026-11-04).
                    if (section.kind == ChaptersSectionKind.LIST &&
                        !book.chapterListComplete &&
                        state.chaptersError != null
                    ) {
                        Surface(
                            Modifier.fillMaxWidth(),
                            // v1.5.8 (J15): the one card radius.
                            shape = RoundedCornerShape(24.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow
                        ) {
                            Row(
                                Modifier.padding(
                                    horizontal = 14.dp, vertical = 8.dp
                                ),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    "More chapters couldn't be loaded — " +
                                        "the source didn't answer.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme
                                        .onSurfaceVariant,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(
                                    onClick = { viewModel.loadFullChapterList() }
                                ) {
                                    Text("Try again")
                                }
                            }
                        }
                    }
                    if (section.kind == ChaptersSectionKind.LIST) {
                        // Reference utility bar: search capsule +
                        // sort toggle pill. (Reskin change: the
                        // capsule now shows for every list, not
                        // only 30+ chapter ones — the design's
                        // primary chapter tool; the state/part
                        // chips below keep their 30+ gate.)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            TextField(
                                // v1.5.7 (Finding 5): the field
                                // shows its OWN text now. A purely
                                // numeric entry resolves through
                                // ChapterJump, grows the display
                                // window to the target, and the
                                // pending-scroll effect above
                                // scrolls it into view with the
                                // transient highlight — and since
                                // v1.5.38 (Fix C2) it ALSO sets
                                // chapterQuery, so the number
                                // search narrows the list to the
                                // named chapter (exact identity
                                // rung — never the old substring
                                // flood). Text that isn't purely
                                // numeric keeps the old search
                                // behaviour, unchanged. An entry
                                // that resolves nowhere shows the
                                // filter's empty state: the page
                                // stays put, honestly.
                                value = jumpFieldText,
                                onValueChange = { value ->
                                    jumpFieldText = value
                                    if (ChapterJump.isJumpQuery(value)) {
                                        // v1.5.38 (Fix C2,
                                        // V1537-PROOF-FORENSICS §C):
                                        // numeric text ALSO sets
                                        // chapterQuery — the number
                                        // search the v1.5.7 split
                                        // routed away from the
                                        // filter entirely, leaving
                                        // the field's number rung
                                        // unreachable and the typed
                                        // number visibly doing
                                        // nothing. The filter's
                                        // number rung is an EXACT
                                        // identity match (see
                                        // ChapterListFilter), so the
                                        // old substring flood cannot
                                        // return through this door.
                                        chapterQuery = value
                                        // v1.0.8 (recommendation):
                                        // a chapter number past the
                                        // loaded edge loads forward
                                        // to it (kept from the old
                                        // wiring — the load is the
                                        // same, only the list's
                                        // reaction changed).
                                        ChapterJump.parseNumber(value)
                                            ?.let { number ->
                                                viewModel.onChapterNumberSearch(
                                                    number.toInt()
                                                )
                                            }
                                        // Resolution runs against
                                        // the list AS THE NEW QUERY
                                        // FILTERS IT — the same pure
                                        // derivation the screen
                                        // composes (filter over the
                                        // full list, then display
                                        // order) — so the pending
                                        // position indexes the list
                                        // the effect will actually
                                        // scroll, not the pre-
                                        // keystroke one.
                                        val jumpDisplay =
                                            app.xreader.domain.reader
                                                .ChapterDisplayOrder
                                                .ordered(
                                                    app.xreader.domain
                                                        .reader
                                                        .ChapterListFilter
                                                        .apply(
                                                            state.chapters,
                                                            value,
                                                            chapterStateFilter,
                                                            chapterPartFilter
                                                        ),
                                                    newestFirst
                                                )
                                        val pos = ChapterJump
                                            .resolvePosition(
                                                value, jumpDisplay
                                            )
                                        if (pos >= 0) {
                                            jumpTargetId =
                                                jumpDisplay[pos].id
                                            if (pos >= chapterWindow) {
                                                chapterWindow = pos + 1
                                            }
                                            pendingJumpPos = pos
                                        } else {
                                            jumpTargetId = null
                                            pendingJumpPos = -1
                                        }
                                    } else {
                                        jumpTargetId = null
                                        pendingJumpPos = -1
                                        chapterQuery = value
                                    }
                                },
                                placeholder = { Text("Jump to chapter…") },
                                leadingIcon = {
                                    Icon(
                                        Icons.Filled.Search,
                                        contentDescription = null
                                    )
                                },
                                singleLine = true,
                                shape = CircleShape,
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor =
                                        MaterialTheme.colorScheme
                                            .surfaceContainerLow,
                                    unfocusedContainerColor =
                                        MaterialTheme.colorScheme
                                            .surfaceContainerLow,
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent,
                                    disabledIndicatorColor = Color.Transparent,
                                    cursorColor =
                                        MaterialTheme.colorScheme.primary
                                ),
                                modifier = Modifier.weight(1f)
                            )
                            Surface(
                                onClick = { newestFirst = !newestFirst },
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme
                                    .surfaceContainerLow
                            ) {
                                Row(
                                    Modifier.padding(
                                        horizontal = 12.dp, vertical = 10.dp
                                    ),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        Icons.Filled.SwapVert,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme
                                            .onSurfaceVariant
                                    )
                                    Text(
                                        if (newestFirst) "Newest" else "Oldest",
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }
                        }
                        // v1.0.7 (draw-check ask): narrow by
                        // read/downloaded state, and jump
                        // straight to a part when the titles
                        // carry one (Part 1 / Part 2 …).
                        if (state.chapters.size >= 30) {
                            androidx.compose.foundation.layout.FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                FilterChip(
                                    selected = chapterStateFilter ==
                                        app.xreader.domain.reader.ChapterListFilter.State.ALL,
                                    onClick = {
                                        chapterStateFilter =
                                            app.xreader.domain.reader.ChapterListFilter.State.ALL
                                    },
                                    label = { Text("All") }
                                )
                                FilterChip(
                                    selected = chapterStateFilter ==
                                        app.xreader.domain.reader.ChapterListFilter.State.UNREAD,
                                    onClick = {
                                        chapterStateFilter =
                                            app.xreader.domain.reader.ChapterListFilter.State.UNREAD
                                    },
                                    label = { Text("Unread") }
                                )
                                FilterChip(
                                    selected = chapterStateFilter ==
                                        app.xreader.domain.reader.ChapterListFilter.State.DOWNLOADED,
                                    onClick = {
                                        chapterStateFilter =
                                            app.xreader.domain.reader.ChapterListFilter.State.DOWNLOADED
                                    },
                                    label = { Text("Downloaded") }
                                )
                                chapterParts.forEach { part ->
                                    FilterChip(
                                        selected = chapterPartFilter == part,
                                        onClick = {
                                            chapterPartFilter =
                                                if (chapterPartFilter == part) {
                                                    null
                                                } else {
                                                    part
                                                }
                                        },
                                        label = { Text(part) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
            when (section.kind) {
                ChaptersSectionKind.LIST -> Unit
                ChaptersSectionKind.LOADING -> item(key = "chapters-state") {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            "Loading the chapter list…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                ChaptersSectionKind.EMPTY_LOCAL -> item(key = "chapters-state") {
                    Text(
                        "This book has no chapters.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                ChaptersSectionKind.ERROR,
                ChaptersSectionKind.EMPTY_SOURCE -> item(key = "chapters-state") {
                    // The recovery-doors card: say what happened,
                    // then offer the ways out — Re-check (the
                    // chapter fetch AND the source's honest
                    // test-drive), the book's own page on the
                    // source's site, and Repair for link sources.
                    GlassCard(Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                if (section.kind == ChaptersSectionKind.ERROR) {
                                    "The chapter list couldn't be loaded. " +
                                        (state.chaptersError ?: "")
                                } else {
                                    "No chapters were found on the source — " +
                                        "the book is in your library, but its " +
                                        "chapter list came back empty."
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (section.kind ==
                                    ChaptersSectionKind.ERROR
                                ) {
                                    errorTextColor()
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                            state.recheckNote?.let { note ->
                                Text(
                                    note,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (state.recheckBusy) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(
                                        "Checking the source…",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            // v1.3.2 (item 77): a switched-off
                            // source gets its one-tap way back,
                            // ahead of everything else on the card.
                            if (state.sourceOff) {
                                XreaderPrimaryButton(
                                    text = "Turn back on and try again",
                                    onClick = {
                                        viewModel.turnBackOnAndRetry()
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            // v1.3.2 (scope A2): a check or a
                            // sign-in gate is SOLVABLE — the
                            // hand-off is the card's primary
                            // door, ahead of the re-check.
                            state.sessionNeed?.let { need ->
                                XreaderPrimaryButton(
                                    text = when (need) {
                                        app.xreader.data.extension
                                            .SessionNeed.Kind.SITE_CHECK ->
                                            "Solve the check"
                                        app.xreader.data.extension
                                            .SessionNeed.Kind.SIGN_IN ->
                                            "Sign in to read"
                                    },
                                    onClick = {
                                        book.extensionId?.let {
                                            onSessionHandoff(it, need)
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            XreaderPrimaryButton(
                                text = if (state.recheckBusy) {
                                    "Re-checking…"
                                } else {
                                    "Re-check now"
                                },
                                onClick = { viewModel.recheck() },
                                enabled = !state.recheckBusy,
                                modifier = Modifier.fillMaxWidth()
                            )
                            androidx.compose.foundation.layout.FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                if (section.canOpenSourcePage) {
                                    TextButton(
                                        onClick = {
                                            uriHandler.openUri(
                                                section.sourcePageUrl!!
                                            )
                                        }
                                    ) { Text("Open the source's page") }
                                }
                                if (section.canGoToRepair) {
                                    TextButton(
                                        onClick = {
                                            book.extensionId?.let {
                                                onOpenSourceDetails(it)
                                            }
                                        }
                                    ) { Text("Go to Repair") }
                                }
                                if (section.canSiteCheck) {
                                    TextButton(
                                        onClick = {
                                            book.extensionId?.let {
                                                onSiteCheck(it)
                                            }
                                        }
                                    ) { Text("Do the site check") }
                                }
                            }
                        }
                    }
                }
            }
            if (section.kind == ChaptersSectionKind.LIST &&
                visibleChapters.isEmpty() && state.chapters.isNotEmpty()
            ) {
                item(key = "chapters-filter-empty") {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "No chapters match that search or filter.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(
                            onClick = {
                                chapterQuery = ""
                                // v1.5.7: the jump field's own
                                // text clears with the search it
                                // may have fed.
                                jumpFieldText = ""
                                jumpTargetId = null
                                pendingJumpPos = -1
                                chapterStateFilter =
                                    app.xreader.domain.reader.ChapterListFilter.State.ALL
                                chapterPartFilter = null
                            }
                        ) { Text("Clear search & filters") }
                    }
                }
            }
            if (section.kind == ChaptersSectionKind.LIST) {
                items(displayedChapters, key = { "chapter-${it.id}" }) { chapter ->
                    // v1.5.37 (S6): no per-row effect and no
                    // animateItem here any more. The growth
                    // trigger is hoisted to one snapshotFlow
                    // over the visible items (above), and the
                    // rows' placement animation — an
                    // underdamped spring on every row — is
                    // what smeared big lists on any shift or
                    // sort flip (BIG-BOOK-REFRESH-FORENSICS
                    // Q8a). Rows now place instantly; the row
                    // content and design are unchanged.
                    ChapterRow(
                        chapter = chapter,
                        isCurrent = chapter.index == state.continueChapterIndex &&
                            state.progress != null,
                        // v1.5.7 (Finding 5): the jump's
                        // transient highlight, on the current
                        // chapter's own container treatment.
                        isJumpTarget = chapter.id == jumpTargetId,
                        isNew = chapter.index == maxChapterIndex &&
                            !chapter.read && state.chapters.size > 1,
                        currentPercent = if (
                            chapter.index == state.continueChapterIndex
                        ) {
                            (state.progress?.fraction?.times(100))?.toInt()
                        } else {
                            null
                        },
                        isStubBody = chapter.id in state.stubChapterIds,
                        minutesLeft = if (
                            app.xreader.data.download.ChapterTruth152
                                .displayWordCount(chapter, state.stubChapterIds) > 0
                        ) {
                            (app.xreader.data.download.ChapterTruth152
                                .displayWordCount(chapter, state.stubChapterIds) /
                                state.wpm).toInt()
                                .coerceAtLeast(1)
                        } else {
                            null
                        },
                        onClick = { onReadChapter(chapter.index) },
                        onDownload = { viewModel.downloadChapter(chapter) }
                    )
                }
                if (chapterWindow < displayChapters.size) {
                    // v1.5.37 (combined build, S6): the window's
                    // end is a SENTINEL, not a button — the
                    // owner's ask: chapters load as he scrolls.
                    // Composing the sentinel (scrolling to it)
                    // grows the window by 200 and reports the
                    // new window end to the same near-edge
                    // growth the rows use, so reveal and
                    // fetch-streaming are driven by the scroll
                    // itself. The "Showing N of M" line stays
                    // as its label; there is no button to tap.
                    // The LazyColumn's own bottom padding keeps
                    // it clear of the phone's dead bottom strip.
                    item(key = "chapters-window-more") {
                        LaunchedEffect(Unit) {
                            chapterWindow += 200
                            viewModel.onChapterNearEdge(
                                chapterWindow
                            )
                        }
                        Text(
                            "Showing ${displayedChapters.size} of " +
                                "${displayChapters.size} chapters",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme
                                .onSurfaceVariant
                        )
                    }
                }
                // v1.5.4 (WS-8, owner addendum 3): the old
                // tail row here (which told the reader more
                // chapters sat beyond the loaded ones, with a
                // tap-to-load-the-rest door) announced
                // partiality at the end of every incomplete
                // list; it is removed for the same reason as
                // the Chapters-section card. It was NOT
                // load-bearing: composing the last loaded
                // rows fires the edge trigger, which resumes
                // the walk from the cursor stored on the
                // book row — growth at the edge is silent.
            }
            item(key = "tags") {
                GlassCard(Modifier.fillMaxWidth()) {
                    TagsSection(
                        state = state,
                        engineLabel = engineLabel,
                        onOpenTag = onOpenTag,
                        onAddTag = { viewModel.addTag(it) },
                        onRemoveTag = { viewModel.removeTag(it) },
                        onSuggest = { viewModel.suggestTagsFromSynopsis() },
                        onSuggestAi = { viewModel.suggestTagsWithAi() },
                        onAcceptSuggestion = { viewModel.acceptTagSuggestion(it) },
                        onDismissSuggestion = { viewModel.dismissTagSuggestion(it) }
                    )
                }
            }
            if (state.linkedCopies.isNotEmpty()) {
                item(key = "progress-links") {
                    GlassCard(Modifier.fillMaxWidth()) {
                        ProgressLinkSection(
                            state = state,
                            onOpenCopy = { id -> onOpenRelated(id) },
                            onUnlink = { viewModel.unlinkCopy(it) }
                        )
                    }
                }
            }
            if (book.sourceType == app.xreader.data.db.SourceType.EXTENSION ||
                state.duplicates.isNotEmpty() || state.mergedCopies.isNotEmpty()
            ) {
                item(key = "sources-merge") {
                    GlassCard(Modifier.fillMaxWidth()) {
                        SourcesMergeSection(
                            state = state,
                            onLoadOtherSources = { viewModel.loadOtherSources() },
                            onOpenCopy = { id -> onOpenRelated(id) },
                            onPreviewMove = { viewModel.previewSwitch(it) },
                            onMerge = { viewModel.mergeDuplicate(it) },
                            onUnmerge = { viewModel.unmerge(it) }
                        )
                    }
                }
            }
            item(key = "reviews") {
                EnrichmentSections(
                    state = state,
                    onOpenRelated = { related ->
                        viewModel.openRelated(related) { id -> onOpenRelated(id) }
                    }
                )
            }
            item(key = "ai-suggestions") {
                AiSuggestionsSection(
                    state = state,
                    engineLabel = engineLabel,
                    onAsk = { viewModel.askAiSuggestions() },
                    onDismiss = { viewModel.dismissAiSuggestion(it) },
                    onFind = onFindTitle
                )
            }
            item(key = "book-actions") {
                BookCleanupActions(
                    viewModel = viewModel,
                    bookTitle = book.title,
                    isLocalFile = book.sourceType ==
                        app.xreader.data.db.SourceType.LOCAL_FILE,
                    onRemoved = onBack
                )
            }
        }
    }

    // Phase 5: the story-so-far recap dialog.
    if (state.storyOpen) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissStory() },
            title = { Text("Catch me up — the story so far") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    when {
                        state.storyBusy -> Text("Reading your summaries…")
                        state.storyError != null -> Text(
                            state.storyError ?: "",
                            color = errorTextColor()
                        )
                        else -> state.storyBullets?.forEach { bullet ->
                            Text("• $bullet", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissStory() }) { Text("Done") }
            }
        )
    }

    // R11: the deliberate-switch preview — nothing has changed yet;
    // the numbers here are exactly what Confirm will do.
    val preview = state.switchPreview
    val switchCandidate = state.switchCandidate
    if (preview != null && switchCandidate != null) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissSwitch() },
            title = { Text("Move to ${switchCandidate.sourceName}?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "${preview.matchedChapters} of your chapters match " +
                            "on the new source (${preview.newSourceChapters} there).",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (preview.progressChapterIndex >= 0) {
                        Text(
                            "Your place lands at chapter " +
                                "${preview.progressChapterIndex + 1}" +
                                if (preview.progressApproximate) {
                                    " (the nearest earlier match)."
                                } else {
                                    "."
                                },
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Text(
                        "Downloaded chapters stay with the old copy — " +
                            "you can download again on the new source. " +
                            "The old copy is kept, merged, and can be " +
                            "brought back any time.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (!preview.canSwitch) {
                        Text(
                            "Too few chapters match to move safely — " +
                                "this move isn't available.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = errorTextColor()
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.confirmSwitch()
                        onBack()
                    },
                    enabled = preview.canSwitch
                ) { Text("Move this book") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissSwitch() }) {
                    Text("Stay here")
                }
            }
        )
    }

    // v1.5.8 (J2/E3): the chapter-settings sheet — the donor's
    // sort/display choices the page lacked, plus the per-book
    // scanlator filter (E3's surface). Every control binds the
    // SAME state the list itself reads: sort → newestFirst,
    // display → chapterStateFilter, scanlators → the VM's
    // BookReaderPrefs exclusion set (this book only —
    // option-scope rule).
    if (chapterSettingsOpen) {
        androidx.compose.material3.ModalBottomSheet(
            onDismissRequest = { chapterSettingsOpen = false },
            sheetState = androidx.compose.material3
                .rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Column(
                Modifier
                    .verticalScroll(
                        androidx.compose.foundation.rememberScrollState()
                    )
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "Chapter settings",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    "Order",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = !newestFirst,
                        onClick = { newestFirst = false },
                        label = { Text("Oldest first") }
                    )
                    FilterChip(
                        selected = newestFirst,
                        onClick = { newestFirst = true },
                        label = { Text("Newest first") }
                    )
                }
                Text(
                    "Show",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = chapterStateFilter ==
                            app.xreader.domain.reader.ChapterListFilter.State.ALL,
                        onClick = {
                            chapterStateFilter =
                                app.xreader.domain.reader.ChapterListFilter.State.ALL
                        },
                        label = { Text("All chapters") }
                    )
                    FilterChip(
                        selected = chapterStateFilter ==
                            app.xreader.domain.reader.ChapterListFilter.State.UNREAD,
                        onClick = {
                            chapterStateFilter =
                                app.xreader.domain.reader.ChapterListFilter.State.UNREAD
                        },
                        label = { Text("Unread only") }
                    )
                    FilterChip(
                        selected = chapterStateFilter ==
                            app.xreader.domain.reader.ChapterListFilter.State.DOWNLOADED,
                        onClick = {
                            chapterStateFilter =
                                app.xreader.domain.reader.ChapterListFilter.State.DOWNLOADED
                        },
                        label = { Text("Downloaded only") }
                    )
                }
                if (state.allScanlators.isNotEmpty()) {
                    Text(
                        "Scanlators",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        "Untick a scanlator to hide its chapters " +
                            "from this book's list.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    state.allScanlators.forEach { scanlator ->
                        val included =
                            scanlator !in state.excludedScanlators
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.setExcludedScanlators(
                                        if (included) {
                                            state.excludedScanlators + scanlator
                                        } else {
                                            state.excludedScanlators - scanlator
                                        }
                                    )
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            androidx.compose.material3.Checkbox(
                                checked = included,
                                onCheckedChange = { nowIncluded ->
                                    viewModel.setExcludedScanlators(
                                        if (nowIncluded) {
                                            state.excludedScanlators - scanlator
                                        } else {
                                            state.excludedScanlators + scanlator
                                        }
                                    )
                                }
                            )
                            Text(
                                scanlator,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    // v1.0.5 (B2/E6): the Download choices sheet — the three
    // ranges a reader actually picks, each showing the count it
    // will queue (BookDownloadRanges computes both the sheet's
    // numbers and the tap's queue, so they can't disagree).
    if (downloadSheetOpen) {
        val counts = BookDownloadRanges.counts(state.chapters)
        androidx.compose.material3.ModalBottomSheet(
            onDismissRequest = { downloadSheetOpen = false },
            sheetState = androidx.compose.material3
                .rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Column(
                Modifier
                    .verticalScroll(
                        androidx.compose.foundation.rememberScrollState()
                    )
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "Download chapters",
                    style = MaterialTheme.typography.titleMedium
                )
                GlassSheetActionRow(
                    icon = Icons.Filled.Download,
                    label = "Whole book",
                    caption = "${counts[BookDownloadChoice.ALL] ?: 0} " +
                        "chapters not on this phone yet",
                    enabled = (counts[BookDownloadChoice.ALL] ?: 0) > 0,
                    onClick = {
                        downloadSheetOpen = false
                        viewModel.downloadChoice(BookDownloadChoice.ALL)
                    }
                )
                GlassSheetActionRow(
                    icon = Icons.Filled.FastForward,
                    label = "Next ${BookDownloadRanges.NEXT_COUNT}",
                    caption = "The next " +
                        "${counts[BookDownloadChoice.NEXT_10] ?: 0} " +
                        "chapters in reading order",
                    enabled = (counts[BookDownloadChoice.NEXT_10] ?: 0) > 0,
                    onClick = {
                        downloadSheetOpen = false
                        viewModel.downloadChoice(BookDownloadChoice.NEXT_10)
                    }
                )
                GlassSheetActionRow(
                    icon = Icons.Filled.FiberNew,
                    label = "Up to the latest unread",
                    caption = "${counts[BookDownloadChoice.UP_TO_LATEST_UNREAD] ?: 0} " +
                        "chapters you haven't read yet",
                    enabled = (counts[
                        BookDownloadChoice.UP_TO_LATEST_UNREAD
                    ] ?: 0) > 0,
                    onClick = {
                        downloadSheetOpen = false
                        viewModel.downloadChoice(
                            BookDownloadChoice.UP_TO_LATEST_UNREAD
                        )
                    }
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/** Nocturne chip: stadium pill on the tier-2 surface — the
 * reference's metric badges (bright text) and genre tags (muted
 * text) share this one shape. */
@Composable
private fun NocturneChip(
    text: String,
    modifier: Modifier = Modifier,
    dotColor: Color? = null,
    textColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onClick: (() -> Unit)? = null
) {
    val content: @Composable () -> Unit = {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (dotColor != null) {
                Box(
                    Modifier
                        .size(6.dp)
                        .background(dotColor, CircleShape)
                )
            }
            Text(
                text,
                style = MaterialTheme.typography.labelSmall,
                color = textColor,
                maxLines = 1
            )
        }
    }
    if (onClick != null) {
        Surface(
            onClick = onClick,
            modifier = modifier,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) { content() }
    } else {
        Surface(
            modifier = modifier,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = textColor
        ) { content() }
    }
}

/** One tile of the reference's 4-up quick-action grid: tier-1
 * rounded-2xl block, icon over label; the active state takes
 * the primary tint, exactly like the reference's In Library
 * tile. A null [onClick] renders the tile as a readout. */
@Composable
private fun QuickActionTile(
    icon: ImageVector,
    label: String,
    active: Boolean,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val tint = if (active) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val body: @Composable () -> Unit = {
        Column(
            Modifier.padding(horizontal = 4.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = tint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
    if (onClick != null) {
        Surface(
            onClick = onClick,
            modifier = modifier,
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow
        ) { body() }
    } else {
        Surface(
            modifier = modifier,
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow
        ) { body() }
    }
}

/** R22 tags: tappable chips (open the tag page), × removes with a
 * correction tombstone, plus add-your-own and synopsis suggestions.
 * INFERRED tags are labelled as suggestions. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun TagsSection(
    state: BookDetailUiState,
    engineLabel: String,
    onOpenTag: (String) -> Unit,
    onAddTag: (String) -> Unit,
    onRemoveTag: (String) -> Unit,
    onSuggest: () -> Unit,
    onSuggestAi: () -> Unit,
    onAcceptSuggestion: (app.xreader.data.ai.TagSuggestion) -> Unit,
    onDismissSuggestion: (app.xreader.data.ai.TagSuggestion) -> Unit
) {
    var draft by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Tags", style = MaterialTheme.typography.titleMedium)
        if (state.tags.isEmpty()) {
            Text(
                "No tags yet. Tags help you find books like this one.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                state.tags.forEach { tag ->
                    FilterChip(
                        selected = false,
                        onClick = { onOpenTag(tag.slug) },
                        label = {
                            Text(
                                app.xreader.data.library.TagRegistry
                                    .displayFor(tag.slug) +
                                    if (tag.origin ==
                                        app.xreader.data.db.TagOrigin.INFERRED
                                    ) {
                                        " · suggested"
                                    } else {
                                        ""
                                    }
                            )
                        },
                        trailingIcon = {
                            TextButton(onClick = { onRemoveTag(tag.slug) }) {
                                Text("×")
                            }
                        }
                    )
                }
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            androidx.compose.material3.OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                label = { Text("Add a tag") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            TextButton(
                onClick = {
                    onAddTag(draft)
                    draft = ""
                },
                enabled = draft.isNotBlank()
            ) { Text("Add") }
        }
        TextButton(onClick = onSuggest) { Text("Suggest tags from the synopsis") }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TextButton(
                onClick = onSuggestAi,
                enabled = !state.tagSuggestBusy
            ) {
                Text(
                    if (state.tagSuggestBusy) {
                        "Asking AI…"
                    } else {
                        "AI tag suggestions"
                    }
                )
            }
            app.xreader.ui.ai.EngineChip(label = engineLabel)
        }
        state.tagSuggestNote?.let { note ->
            Text(
                note,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (state.tagSuggestions.isNotEmpty()) {
            Text(
                "The AI suggests — tap ✓ to add a tag, × to dismiss it. " +
                    "Nothing is added until you tap ✓.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                state.tagSuggestions.forEach { suggestion ->
                    FilterChip(
                        selected = false,
                        onClick = { onAcceptSuggestion(suggestion) },
                        label = { Text(suggestion.displayName) },
                        trailingIcon = {
                            Row {
                                TextButton(
                                    onClick = { onAcceptSuggestion(suggestion) }
                                ) { Text("✓") }
                                TextButton(
                                    onClick = { onDismissSuggestion(suggestion) }
                                ) { Text("×") }
                            }
                        }
                    )
                }
            }
        }
    }
}

/** Cycle 1 (F3): same-story copies in the Library that share
 * reading progress with this book — the entries stay
 * separate, only progress is linked, and "Not the same book"
 * severs a wrong link for good. Additive rows in the page's
 * existing section style. */
@Composable
private fun ProgressLinkSection(
    state: BookDetailUiState,
    onOpenCopy: (Long) -> Unit,
    onUnlink: (Long) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Also in your library", style = MaterialTheme.typography.titleMedium)
        Text(
            "These copies stay separate — only your reading " +
                "progress is shared, so each one opens where " +
                "you left off in the others.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        state.linkedCopies.forEach { copy ->
            Column {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            copy.title,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            "on ${copy.sourceName}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(onClick = { onOpenCopy(copy.bookId) }) {
                        Text("Open")
                    }
                }
                TextButton(onClick = { onUnlink(copy.bookId) }) {
                    Text("Not the same book")
                }
            }
        }
    }
}

/** R11 + sweep: other sources for this work, duplicates in the
 * library, and copies this book has absorbed — all with visible,
 * reversible actions. */
@Composable
private fun SourcesMergeSection(
    state: BookDetailUiState,
    onLoadOtherSources: () -> Unit,
    onOpenCopy: (Long) -> Unit,
    onPreviewMove: (OtherSourceCandidate) -> Unit,
    onMerge: (Long) -> Unit,
    onUnmerge: (Long) -> Unit
) {
    val book = state.book ?: return
    if (book.sourceType != app.xreader.data.db.SourceType.EXTENSION &&
        state.duplicates.isEmpty() && state.mergedCopies.isEmpty()
    ) {
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (book.sourceType == app.xreader.data.db.SourceType.EXTENSION) {
            Text("Other sources", style = MaterialTheme.typography.titleMedium)
            when (state.otherSourcesState) {
                OtherSourcesState.NOT_LOADED -> TextButton(
                    onClick = onLoadOtherSources
                ) { Text("Find this book on my other sources") }
                OtherSourcesState.LOADING -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CircularProgressIndicator(Modifier.size(18.dp))
                    Text("Looking…", style = MaterialTheme.typography.bodyMedium)
                }
                OtherSourcesState.FAILED -> TextButton(
                    onClick = onLoadOtherSources
                ) { Text("That didn't work — try again") }
                OtherSourcesState.LOADED -> {
                    if (state.otherSources.isEmpty()) {
                        Text(
                            "No other source has this book.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    state.otherSources.forEach { candidate ->
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    candidate.sourceName,
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Text(
                                    if (candidate.inLibraryBookId != null) {
                                        "Already in your library" +
                                            if (candidate.downloadedCount > 0) {
                                                " · ${candidate.downloadedCount} downloaded"
                                            } else {
                                                ""
                                            }
                                    } else {
                                        "Not in your library yet"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (candidate.inLibraryBookId != null) {
                                TextButton(
                                    onClick = { onOpenCopy(candidate.inLibraryBookId) }
                                ) { Text("Open") }
                            }
                            TextButton(onClick = { onPreviewMove(candidate) }) {
                                Text("Move here…")
                            }
                        }
                    }
                }
            }
        }
        if (state.duplicates.isNotEmpty()) {
            Text(
                "Same book, another copy",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                "Merging keeps this book as the one you see; the other " +
                    "copy is hidden, not deleted, and can be unmerged.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            state.duplicates.forEach { duplicate ->
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        duplicate.title,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { onMerge(duplicate.id) }) {
                        Text("Merge into this book")
                    }
                }
            }
        }
        if (state.mergedCopies.isNotEmpty()) {
            Text("Merged copies", style = MaterialTheme.typography.titleMedium)
            state.mergedCopies.forEach { copy ->
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        copy.title,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { onUnmerge(copy.id) }) { Text("Unmerge") }
                }
            }
        }
    }
}

/**
 * One chapter row in the Nocturne styling (reference chapter
 * list): tier-2 block for unread, tier-1 dimmed for read, the
 * raised tier + violet title + "Current · N%" pill + play disc
 * for the chapter being read, a NEW pill on the newest unread
 * chapter, and the download state at the trailing edge — the
 * same four states (and retry door) the row has always had.
 */
/**
 * v1.5.1 (item 4): the honest state that REPLACES the Start
 * Reading pill when the chapter state is truly empty — same
 * slot, same pill shape (the page's look is unchanged), but
 * muted and inert: it can never read as a live button, and
 * it carries the reason itself.
 *
 * v1.5.37 (combined build, item 4): the headline follows
 * the state — [title] defaults to the settled-failure
 * headline, and the still-loading call site passes its
 * finding headline instead. Until now the pill headlined
 * "Chapters unavailable" even while a fetch was in flight
 * and its own reason line said "Finding chapters…" (the
 * owner's source-tour wart): the error headline now
 * renders only on a settled failure.
 */
@Composable
private fun ChaptersUnavailablePill(
    reason: String,
    title: String = "Chapters unavailable"
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
    ) {
        Column(
            Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1
            )
            Text(
                reason,
                style = MaterialTheme.typography.bodySmall,
                color = errorTextColor()
            )
        }
    }
}

@Composable
private fun ChapterRow(
    chapter: Chapter,
    isCurrent: Boolean,
    isNew: Boolean,
    currentPercent: Int?,
    minutesLeft: Int?,
    onClick: () -> Unit,
    onDownload: () -> Unit,
    modifier: Modifier = Modifier,
    /** v1.5.2 fix (FIX-8c): this chapter's stored body is a stub —
     * no count renders for it anywhere (§14.4). */
    isStubBody: Boolean = false,
    /** v1.5.7 (Finding 5): the jump landed on this row — it
     * takes the current chapter's container treatment while
     * the highlight lasts (the page clears it after a few
     * seconds). No other visual change. */
    isJumpTarget: Boolean = false
) {
    // v1.5.2 fix (FIX-8c/FIX-6a): the rendered count is the PROVEN
    // count (0 for stubs); the row mark is §12.10's: DOWNLOADED
    // (USER+READY, green tick) vs CACHED (AUTOMATIC+READY, hollow).
    val provenWordCount = if (isStubBody) 0 else chapter.wordCount
    val rowMark = app.xreader.data.download.ChapterTruth152.rowMark(chapter)
    val container = when {
        isCurrent || isJumpTarget ->
            MaterialTheme.colorScheme.surfaceContainerHigh
        chapter.read -> MaterialTheme.colorScheme.surfaceContainerLow
        else -> MaterialTheme.colorScheme.surfaceContainer
    }
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        // v1.5.8 (J2): the one card radius — 24dp everywhere.
        shape = RoundedCornerShape(24.dp),
        color = container
    ) {
        // v1.3.2 (item 89 — the user's own freeze override, ROW
        // SIZE ONLY): the rows read "somewhat big" on device, so
        // the row is a fifth tighter — vertical padding 12 → 8,
        // the title a step smaller (13sp/17sp against
        // titleSmall's 14/20), and the download buttons
        // visually 36dp (their Material 3 touch target stays
        // 48dp, so they remain comfortably tappable). Colours,
        // shape and layout are exactly as they were.
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        chapter.title,
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontSize = 13.sp, lineHeight = 17.sp
                        ),
                        color = when {
                            isCurrent -> primaryTextColor()
                            chapter.read -> MaterialTheme.colorScheme
                                .onSurfaceVariant
                            else -> MaterialTheme.colorScheme.onSurface
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (chapter.read) {
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = "Read",
                            tint = Color(0xFF34D399),
                            modifier = Modifier.size(14.dp)
                        )
                    }
                    if (isCurrent) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            Text(
                                "Current" + (currentPercent?.let {
                                    " · $it%"
                                } ?: ""),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme
                                    .onSecondaryContainer,
                                modifier = Modifier.padding(
                                    horizontal = 6.dp, vertical = 2.dp
                                )
                            )
                        }
                    } else if (isNew) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Text(
                                "NEW",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme
                                    .onPrimaryContainer,
                                modifier = Modifier.padding(
                                    horizontal = 6.dp, vertical = 2.dp
                                )
                            )
                        }
                    }
                }
                // The chapter's release date rides the same
                // subtitle line, relatively ("· 3 days ago") —
                // absent when the source prints no date.
                // v1.5.7 (scope F smoothness): the relative-date
                // parse is remembered per date text — it used to
                // re-parse on every row recomposition (progress
                // ticks recompose the whole visible list).
                val dateLine = remember(chapter.dateText) {
                    app.xreader.domain.reader.ChapterDateText
                        .relative(chapter.dateText)
                }
                val statusLine = when {
                    isStubBody -> "Locked — sign in to read this chapter"
                    chapter.read -> "Read" +
                        when (rowMark) {
                            app.xreader.data.download.ChapterTruth152.RowMark.DOWNLOADED ->
                                " · Downloaded"
                            app.xreader.data.download.ChapterTruth152.RowMark.CACHED ->
                                " · Cached"
                            else -> ""
                        }
                    isCurrent -> "Continue here" +
                        (minutesLeft?.let { " · ≈$it min left" } ?: "")
                    provenWordCount > 0 -> "$provenWordCount words" +
                        (minutesLeft?.let { " · ≈$it min" } ?: "")
                    else -> ""
                }
                // v1.5.8 (E2): the scanlator rides the meta
                // line dot-separated with the date, donor
                // shape — absent entirely when the source
                // credited nobody (no placeholder).
                val subtitle = listOfNotNull(
                    statusLine.ifEmpty { null },
                    dateLine,
                    chapter.scanlator?.takeIf { it.isNotBlank() }
                ).joinToString(" · ")
                if (subtitle.isNotEmpty()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            when (chapter.downloadState) {
                DownloadState.READY -> if (
                    rowMark == app.xreader.data.download.ChapterTruth152.RowMark.CACHED
                ) {
                    // §12.10: the distinct HOLLOW mark for cache —
                    // never the green downloaded tick.
                    Icon(
                        Icons.Outlined.CheckCircle,
                        contentDescription = "Cached",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = "Downloaded",
                        tint = sageTextColor()
                    )
                }
                DownloadState.DOWNLOADING, DownloadState.QUEUED -> Icon(
                    Icons.Filled.Schedule,
                    contentDescription = "Queued",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                DownloadState.FAILED -> IconButton(
                    onClick = onDownload,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.Filled.ErrorOutline,
                        contentDescription = "Download failed — retry",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
                // Manga programme (M3, additive): a partly
                // downloaded image chapter — some pages are on
                // the phone, the rest aren't. The tap fetches
                // only the missing pages.
                DownloadState.PARTIAL -> IconButton(
                    onClick = onDownload,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.Filled.Download,
                        contentDescription = "Partly downloaded — download the rest",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
                DownloadState.NONE -> {
                    if (isCurrent) {
                        Box(
                            Modifier
                                .size(28.dp)
                                .background(
                                    MaterialTheme.colorScheme.primary
                                        .copy(alpha = 0.18f),
                                    CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Filled.PlayArrow,
                                contentDescription = "Continue here",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    } else if (!chapter.remoteId.isNullOrBlank()) {
                        IconButton(
                            onClick = onDownload,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                Icons.Filled.Download,
                                contentDescription = "Download chapter"
                            )
                        }
                    } else {
                        Icon(
                            Icons.Filled.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

/** v1.0.1 provider enrichment sections, in the decluttered order:
 * Reviews → Comments → Readers also read. Honest states only: a source
 * that doesn't offer a section gets one quiet line; a source that
 * offers it but has none says that instead. Local books hide the
 * sections entirely (they were never offered). */
@Composable
private fun EnrichmentSections(
    state: BookDetailUiState,
    onOpenRelated: (app.xreader.extensionapi.SourceBook) -> Unit
) {
    val book = state.book ?: return
    if (book.sourceType != app.xreader.data.db.SourceType.EXTENSION) return
    if (!state.enrichmentLoaded) return
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Reviews
        Text("Reviews", style = MaterialTheme.typography.titleMedium)
        when {
            state.reviews == null -> Text(
                "This source doesn't share reviews.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            state.reviews.isEmpty() -> Text(
                "No reviews yet for this book on this source.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            else -> {
                val rated = state.reviews.mapNotNull { it.rating }
                if (rated.isNotEmpty()) {
                    Text(
                        "★ %.1f average from ${state.reviews.size} review(s)".format(
                            rated.sum() / rated.size
                        ),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
                state.reviews.take(10).forEach { review ->
                    Column {
                        Text(
                            review.author +
                                (review.rating?.let { " · ★ $it" } ?: "") +
                                (review.dateText?.let { " · $it" } ?: ""),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            review.text,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
        // Comments
        Text("Comments", style = MaterialTheme.typography.titleMedium)
        when {
            state.comments == null -> Text(
                "This source doesn't share comments.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            state.comments.isEmpty() -> Text(
                "No comments yet for this book on this source.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            else -> state.comments.take(10).forEach { comment ->
                Column {
                    Text(
                        comment.author +
                            (comment.dateText?.let { " · $it" } ?: ""),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(comment.text, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        // Readers also read (reference carousel): the source's
        // related books as a horizontal cover shelf.
        val related = state.related
        if (!related.isNullOrEmpty()) {
            Text("Readers also read", style = MaterialTheme.typography.titleMedium)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(related.take(10), key = { it.id }) { other ->
                    Column(
                        Modifier
                            .width(112.dp)
                            .clickable { onOpenRelated(other) }
                    ) {
                        Box(
                            Modifier
                                .size(width = 112.dp, height = 160.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    MaterialTheme.colorScheme.surfaceContainerLow
                                )
                        ) {
                            if (!other.coverUrl.isNullOrBlank()) {
                                // v1.3.3 (scope S2): a failed cover
                                // renders the designed title cover,
                                // never an invisible box.
                                coil.compose.SubcomposeAsyncImage(
                                    model = other.coverUrl,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                    loading = {
                                        app.xreader.ui.library.PlaceholderCover(
                                            title = other.title,
                                            author = other.author,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    },
                                    error = {
                                        app.xreader.ui.library.PlaceholderCover(
                                            title = other.title,
                                            author = other.author,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }
                                )
                            } else {
                                app.xreader.ui.library.PlaceholderCover(
                                    title = other.title,
                                    author = other.author,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            other.title,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            other.author ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

/**
 * v1.0.2 (R26): AI "More like this" — labelled suggestions from
 * the user's own Gemini key. No key → a Set-up line instead of a
 * dead card. Asking is always a deliberate tap (the key is never
 * spent silently), results are cached per book, dismissals are
 * remembered, and nothing is auto-added: Find hands the title to
 * global search, where adding works like any other result.
 */
@Composable
private fun AiSuggestionsSection(
    state: BookDetailUiState,
    engineLabel: String,
    onAsk: () -> Unit,
    onDismiss: (String) -> Unit,
    onFind: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "More like this — AI suggestions",
                style = MaterialTheme.typography.titleMedium
            )
            app.xreader.ui.ai.EngineChip(label = engineLabel)
        }
        when (state.aiSuggestionsState) {
            AiSuggestionsState.NO_KEY -> Text(
                "Set up an AI provider in Providers & keys to get AI " +
                    "suggestions for books like this one.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            AiSuggestionsState.NOT_ASKED -> {
                Text(
                    "Ask your AI engine for books similar to this one.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = onAsk) { Text("Ask AI") }
            }
            AiSuggestionsState.LOADING -> Text(
                "Asking…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            AiSuggestionsState.FAILED -> {
                Text(
                    "The AI couldn't answer just now.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = onAsk) { Text("Try again") }
            }
            AiSuggestionsState.READY -> {
                if (state.aiSuggestions.isEmpty()) {
                    Text(
                        "No suggestions left — you've dismissed them all.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                state.aiSuggestions.forEach { title ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            onClick = { onFind(title) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("🔍  $title")
                        }
                        TextButton(onClick = { onDismiss(title) }) {
                            Text("Dismiss")
                        }
                    }
                }
                Text(
                    "Suggestions from your AI engine, based on this book. " +
                        "Tap one to find it on your sources.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** v1.0.1 cleanup + removal: visible, labelled, with the size shown
 * BEFORE confirming and honest consequences in plain words. */
/** v1.5.6 (§3.4(b)): the legacy lane's re-add offer card. */
@Composable
private fun LegacyReAddCard(viewModel: BookDetailViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        val name = runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(
                    android.provider.OpenableColumns.DISPLAY_NAME
                )
                if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
            }
        }.getOrNull() ?: "book"
        viewModel.reAddPicked(
            displayName = name,
            documentUri = uri.toString(),
            openStream = { context.contentResolver.openInputStream(uri) }
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Re-add the original file to read this book exactly as published.",
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            "This copy was imported by an older version, which kept " +
                "only converted chapters. Adding the original file " +
                "switches the book to reading straight from it — " +
                "your place and bookmarks stay.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Button(onClick = {
            picker.launch(arrayOf("application/epub+zip", "application/pdf", "*/*"))
        }) { Text("Re-add the original file") }
    }
}

@Composable
private fun BookCleanupActions(
    viewModel: BookDetailViewModel,
    bookTitle: String,
    isLocalFile: Boolean = false,
    onRemoved: () -> Unit
) {
    var confirmCleanup by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    var confirmPermanent by remember { mutableStateOf(false) }
    val deleteContext = androidx.compose.ui.platform.LocalContext.current
    var sizeBytes by remember { mutableStateOf(0L) }
    LaunchedEffect(confirmCleanup, confirmRemove) {
        if (confirmCleanup || confirmRemove) {
            sizeBytes = viewModel.downloadedSizeBytes()
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = { confirmCleanup = true }) {
            Text("Delete downloaded chapters")
        }
        TextButton(onClick = { confirmRemove = true }) {
            Text(
                "Remove from library",
                color = errorTextColor()
            )
        }
    }
    if (confirmCleanup) {
        AlertDialog(
            onDismissRequest = { confirmCleanup = false },
            title = { Text("Delete downloaded chapters?") },
            text = {
                Text(
                    "This frees ${formatMb(sizeBytes)} on this phone. The book, " +
                        "your place in it and your bookmarks are all kept — " +
                        "chapters can be downloaded again."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmCleanup = false
                    viewModel.deleteDownloadedChapters()
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmCleanup = false }) { Text("Keep them") }
            }
        )
    }
    if (confirmPermanent) {
        // v1.5.6 (§3.7): the permanent option's own warning step.
        AlertDialog(
            onDismissRequest = { confirmPermanent = false },
            title = { Text("Delete from your device?") },
            text = {
                Text(
                    "This also deletes the file from your device — " +
                        "it cannot be undone. Android may ask you to " +
                        "confirm the file delete as well."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmPermanent = false
                    viewModel.removeLocalBook(
                        app.xreader.data.library.LocalBookDeleteMode.PERMANENT,
                        deleteContext
                    ) { onRemoved() }
                }) { Text("Delete everything") }
            },
            dismissButton = {
                TextButton(onClick = { confirmPermanent = false }) { Text("Cancel") }
            }
        )
    }
    if (confirmRemove) {
        // v1.0.5 (D2): the two remove choices stack full-width in
        // the BODY (DialogDangerOption) and "Keep the book" sits
        // alone in the button row.
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove “$bookTitle”?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (isLocalFile) {
                        // v1.5.6 (§3.7): a local book's three
                        // options — no second copy, YouTube rule.
                        Text(
                            "This book reads from its own file. " +
                                "Deleting the book never touches your " +
                                "file unless you choose the last option."
                        )
                        Spacer(Modifier.height(8.dp))
                        app.xreader.ui.common.DialogDangerOption(
                            label = "Delete book",
                            onClick = {
                                confirmRemove = false
                                viewModel.removeLocalBook(
                                    app.xreader.data.library.LocalBookDeleteMode.DELETE_BOOK,
                                    null
                                ) { onRemoved() }
                            }
                        )
                        app.xreader.ui.common.DialogDangerOption(
                            label = "Delete book and forget my reading",
                            onClick = {
                                confirmRemove = false
                                viewModel.removeLocalBook(
                                    app.xreader.data.library.LocalBookDeleteMode.FORGET,
                                    null
                                ) { onRemoved() }
                            }
                        )
                        app.xreader.ui.common.DialogDangerOption(
                            label = "Permanent delete from my device",
                            onClick = {
                                confirmRemove = false
                                confirmPermanent = true
                            }
                        )
                    } else {
                        Text(
                            "Removing the book also removes its reading progress and " +
                                "bookmarks. Your original file (if you imported one) " +
                                "stays exactly where it is — only Xreader's own copies " +
                                "can be deleted, and only if you choose that below."
                        )
                        Spacer(Modifier.height(8.dp))
                        app.xreader.ui.common.DialogDangerOption(
                            label = "Remove book only",
                            onClick = {
                                confirmRemove = false
                                viewModel.removeFromLibrary(deleteFiles = false) {
                                    onRemoved()
                                }
                            }
                        )
                        app.xreader.ui.common.DialogDangerOption(
                            label = "Remove book and downloaded chapters " +
                                "(${formatMb(sizeBytes)})",
                            onClick = {
                                confirmRemove = false
                                viewModel.removeFromLibrary(deleteFiles = true) {
                                    onRemoved()
                                }
                            }
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { confirmRemove = false }) { Text("Keep the book") }
            }
        )
    }
}

private fun formatMb(bytes: Long): String =
    if (bytes >= 1024L * 1024L) "${bytes / (1024L * 1024L)} MB"
    else "${(bytes / 1024L).coerceAtLeast(1)} KB"
