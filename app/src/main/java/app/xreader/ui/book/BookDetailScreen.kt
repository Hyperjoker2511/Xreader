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
import androidx.compose.material.icons.filled.CheckCircle
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
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.AlertDialog
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.xreader.core.motion.listPlacementSpec
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
    onSiteCheck: (extensionId: Long) -> Unit = {}
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val engineLabel by viewModel.engineLabel.collectAsStateWithLifecycle()
    val book = state.book
    // v1.0.5 (B2/E6): the Download sheet's open state — the three
    // choices (all / next 10 / latest unread) live there, beside
    // Start reading, never only in a long-press sheet.
    var downloadSheetOpen by remember { mutableStateOf(false) }
    // v1.0.7 (draw-check ask): chapter search + filters — a
    // 1,692-chapter list can't be scrolled by hand, and a
    // novel split into parts buries Part 1 under Part 2.
    var chapterQuery by remember { mutableStateOf("") }
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
    val displayChapters = remember(visibleChapters, newestFirst) {
        if (newestFirst) visibleChapters.asReversed() else visibleChapters
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
        val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
        // Tweak 2 (Nocturne): the Continue pill's numbers, derived
        // from the ViewModel's existing progress state only —
        // chapters read + the in-chapter fraction of the chapter
        // being read, over the loaded chapter count.
        val continueChapter = state.chapters.firstOrNull {
            it.index == state.continueChapterIndex
        }
        val readCount = state.chapters.count { it.read }
        val bookPercent = if (state.chapters.isEmpty()) {
            0
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
            ReadingStatus.DROPPED -> "Dropped"
            ReadingStatus.NONE -> null
        }
        LazyColumn(
            Modifier.weight(1f),
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
                                    AsyncImage(
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
                                            )
                                    )
                                } else {
                                    app.xreader.ui.library.PlaceholderCover(
                                        title = book.title,
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
                                            "Chapter ${it.index + 1} · ${it.title}"
                                        } ?: "Begin at chapter 1"
                                    } else {
                                        "Chapter ${state.continueChapterIndex + 1}" +
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
                    state.readBlockedReason?.let { reason ->
                        Text(
                            reason,
                            style = MaterialTheme.typography.bodyMedium,
                            color = errorTextColor()
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
                    // Reading status chips + Track toggle
                    // (v1.0.1, kept): the chips WRAP (B3/D25).
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(
                            ReadingStatus.READING to "Reading",
                            ReadingStatus.COMPLETED to "Completed",
                            ReadingStatus.ON_HOLD to "On hold",
                            ReadingStatus.DROPPED to "Dropped"
                        ).forEach { (status, label) ->
                            FilterChip(
                                selected = state.bookMeta.status == status,
                                onClick = {
                                    viewModel.setReadingStatus(
                                        if (state.bookMeta.status == status) {
                                            ReadingStatus.NONE
                                        } else {
                                            status
                                        }
                                    )
                                },
                                label = { Text(label) }
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
                    // Reference source & network banner, with
                    // this screen's real source doors: the host
                    // the book came from, how much of it is on
                    // the device, Repair (link sources) and the
                    // site check (browser-view sources that need
                    // setup). The Chapters recovery card below
                    // still carries its own doors for failures.
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
                                "· ${state.chapters.size} loaded" +
                                    if (!book.chapterListComplete) {
                                        " · partial list"
                                    } else {
                                        ""
                                    },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.weight(1f))
                            if (state.isLinkSource) {
                                TextButton(
                                    onClick = {
                                        book.extensionId?.let {
                                            onOpenSourceDetails(it)
                                        }
                                    }
                                ) { Text("Repair") }
                            }
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
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (book.description.length > 200) {
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
                        if (book.sourceType ==
                            app.xreader.data.db.SourceType.EXTENSION
                        ) {
                            TextButton(
                                onClick = { viewModel.refreshChapters() }
                            ) {
                                Text("Refresh chapters")
                            }
                        }
                        TextButton(
                            onClick = {
                                clipboard.setText(
                                    AnnotatedString(
                                        buildString {
                                            append(book.title)
                                            if (book.author.isNotBlank()) {
                                                append("\nby ${book.author}")
                                            }
                                            append(
                                                "\n${state.chapters.size} chapters" +
                                                    " · ${state.downloadedCount}" +
                                                    " downloaded"
                                            )
                                            statusLabel?.let {
                                                append("\nStatus: $it")
                                            }
                                            if (book.description.isNotBlank()) {
                                                append("\n\n${book.description}")
                                            }
                                        }
                                    )
                                )
                                viewModel.notifyMessage("Book details copied")
                            }
                        ) { Text("Copy details") }
                    }
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
                    // v1.0.8 (recommendation): a partially loaded
                    // book says so plainly — tweak 3: the loaded
                    // prefix is the normal state, the remainder
                    // and its one-tap door sit in-design.
                    if (section.kind == ChaptersSectionKind.LIST &&
                        !book.chapterListComplete
                    ) {
                        Surface(
                            Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
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
                                    "${state.chapters.size} loaded · more " +
                                        "chapters load as you read",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme
                                        .onSurfaceVariant,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(
                                    onClick = { viewModel.loadFullChapterList() }
                                ) {
                                    Text("Load full list")
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
                                value = chapterQuery,
                                onValueChange = { value ->
                                    chapterQuery = value
                                    // v1.0.8 (recommendation): a
                                    // chapter-number search past
                                    // the loaded edge loads forward
                                    // to it.
                                    value.trim().toIntOrNull()?.let { number ->
                                        viewModel.onChapterNumberSearch(number)
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
                                chapterStateFilter =
                                    app.xreader.domain.reader.ChapterListFilter.State.ALL
                                chapterPartFilter = null
                            }
                        ) { Text("Clear search & filters") }
                    }
                }
            }
            if (section.kind == ChaptersSectionKind.LIST) {
                items(displayChapters, key = { "chapter-${it.id}" }) { chapter ->
                    // v1.0.8: composing a row near the loaded edge
                    // of a partially loaded book grows the list —
                    // scrolling IS the demand signal.
                    LaunchedEffect(chapter.id) {
                        viewModel.onChapterNearEdge(chapter.index)
                    }
                    ChapterRow(
                        chapter = chapter,
                        isCurrent = chapter.index == state.continueChapterIndex &&
                            state.progress != null,
                        isNew = chapter.index ==
                            (state.chapters.maxOfOrNull { it.index } ?: -1) &&
                            !chapter.read && state.chapters.size > 1,
                        currentPercent = if (
                            chapter.index == state.continueChapterIndex
                        ) {
                            (state.progress?.fraction?.times(100))?.toInt()
                        } else {
                            null
                        },
                        minutesLeft = if (chapter.wordCount > 0) {
                            (chapter.wordCount / state.wpm).toInt()
                                .coerceAtLeast(1)
                        } else {
                            null
                        },
                        onClick = { onReadChapter(chapter.index) },
                        onDownload = { viewModel.downloadChapter(chapter) },
                        modifier = Modifier.animateItem(
                            placementSpec = listPlacementSpec()
                        )
                    )
                }
                if (!book.chapterListComplete) {
                    // Tweak 3: the not-yet-loaded remainder, in
                    // design — a quiet tier-1 row that says the
                    // list continues past what's loaded and
                    // carries the same Load full list door.
                    item(key = "chapters-more") {
                        Surface(
                            onClick = { viewModel.loadFullChapterList() },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow
                        ) {
                            Row(
                                Modifier.padding(
                                    horizontal = 14.dp, vertical = 12.dp
                                ),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    Icons.Filled.ChevronRight,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme
                                        .onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    "More chapters beyond these " +
                                        "${state.chapters.size} — tap to load " +
                                        "the full list",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme
                                        .onSurfaceVariant
                                )
                            }
                        }
                    }
                }
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
                BookCleanupActions(viewModel = viewModel, bookTitle = book.title, onRemoved = onBack)
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
@Composable
private fun ChapterRow(
    chapter: Chapter,
    isCurrent: Boolean,
    isNew: Boolean,
    currentPercent: Int?,
    minutesLeft: Int?,
    onClick: () -> Unit,
    onDownload: () -> Unit,
    modifier: Modifier = Modifier
) {
    val container = when {
        isCurrent -> MaterialTheme.colorScheme.surfaceContainerHigh
        chapter.read -> MaterialTheme.colorScheme.surfaceContainerLow
        else -> MaterialTheme.colorScheme.surfaceContainer
    }
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = container
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
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
                        style = MaterialTheme.typography.titleSmall,
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
                val statusLine = when {
                    chapter.read -> "Read" +
                        if (chapter.downloadState == DownloadState.READY) {
                            " · Downloaded"
                        } else {
                            ""
                        }
                    isCurrent -> "Continue here" +
                        (minutesLeft?.let { " · ≈$it min left" } ?: "")
                    chapter.wordCount > 0 -> "${chapter.wordCount} words" +
                        (minutesLeft?.let { " · ≈$it min" } ?: "")
                    else -> ""
                }
                val dateLine = app.xreader.domain.reader.ChapterDateText
                    .relative(chapter.dateText)
                val subtitle = listOfNotNull(
                    statusLine.ifEmpty { null },
                    dateLine
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
                DownloadState.READY -> Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = "Downloaded",
                    tint = sageTextColor()
                )
                DownloadState.DOWNLOADING, DownloadState.QUEUED -> Icon(
                    Icons.Filled.Schedule,
                    contentDescription = "Queued",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                DownloadState.FAILED -> IconButton(onClick = onDownload) {
                    Icon(
                        Icons.Filled.ErrorOutline,
                        contentDescription = "Download failed — retry",
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
                        IconButton(onClick = onDownload) {
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
                                AsyncImage(
                                    model = other.coverUrl,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                app.xreader.ui.library.PlaceholderCover(
                                    title = other.title,
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
@Composable
private fun BookCleanupActions(
    viewModel: BookDetailViewModel,
    bookTitle: String,
    onRemoved: () -> Unit
) {
    var confirmCleanup by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
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
    if (confirmRemove) {
        // v1.0.5 (D2): the two remove choices stack full-width in
        // the BODY (DialogDangerOption) and "Keep the book" sits
        // alone in the button row.
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove “$bookTitle”?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
