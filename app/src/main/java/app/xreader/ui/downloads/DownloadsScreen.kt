package app.xreader.ui.downloads

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.xreader.ui.theme.FrauncesSerif
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.xreader.core.motion.animateProgressAsState
import app.xreader.core.motion.listPlacementSpec
import app.xreader.data.db.DownloadKind
import app.xreader.data.db.QueueState
import app.xreader.ui.common.EmptyState
import app.xreader.ui.common.GlassCard
import app.xreader.ui.common.KitEmptyState
import app.xreader.ui.common.LoadingState
import app.xreader.ui.common.GhostPill
import app.xreader.ui.common.NocturneChip
import app.xreader.ui.common.PrimaryPill
import app.xreader.ui.common.ProgressPillBar
import app.xreader.ui.common.RowItem
import app.xreader.ui.common.ScreenHeader
import app.xreader.ui.common.SectionHeader
import app.xreader.ui.common.rememberHoverEmptyState
import app.xreader.ui.common.StatusPill
import app.xreader.ui.theme.EmberAmber
import coil.compose.AsyncImage
import coil.request.ImageRequest
import java.io.File
import app.xreader.ui.theme.LocalDockClearance
import app.xreader.ui.theme.LocalUiMetrics
import app.xreader.ui.theme.effectiveBottomPadding

/** The finished list's sort choices — all over real book fields. */
private enum class FinishedSort(val label: String) {
    SHELF("Shelf order"),
    TITLE("Title"),
    NEWEST("Newest first")
}

/**
 * Downloads (Stitch design, PLAN §4; v1.1.0 §5.12 Nocturne pass):
 * the Offline Storage card with real metering, Clean Cache and the
 * settings door; In Progress / Downloaded segmented pills with real
 * counts; the transfer queue with pause/cancel and the honest
 * "Waiting for Wi-Fi" chip; the failures banner with Retry all
 * (kept visible on both tabs — the drawing omitted it, the app
 * keeps it); finished rows with Make EPUB + Read Offline.
 * All progress animates through the motion engine's progress
 * adapter (Amendment 5). The drawing's auto-fetch banner and its
 * "Manga" wording were NOT adopted (no such rule/wording exists
 * in the app).
 */
@Composable
fun DownloadsScreen(
    viewModel: DownloadsViewModel,
    coverFile: (String?) -> File?,
    onMakeEpub: (BookDownloads) -> Unit,
    onOpenBook: (Long) -> Unit,
    /** v1.0.4 (D13): gates the empty-state hover (shell passes it). */
    reduceMotion: Boolean = false,
    /** v1.1.0 (§5.12): the storage card's settings door → Customise.
     * Optional so older call sites keep compiling; the shell wires
     * it to the Customise route. */
    onOpenCustomise: () -> Unit = {},
    /** Code 120 completion (R4): under Material 3 the finished
     * list's Sort action cycles the DESIGN's downloads-sort knob
     * (persisted in DesignPrefs) instead of the local sort; the
     * shell wires this to DesignPrefs. Default no-op keeps older
     * call sites compiling; under Nocturne it is never called. */
    onDownloadsSortChange: (
        app.xreader.data.prefs.M3DownloadsSort
    ) -> Unit = {}
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Code 120 completion (R4): the M3 downloads knobs — sort
    // and the size line. Under Nocturne neither is read and the
    // screen behaves exactly as shipped (local sort, size
    // always shown).
    val designState = app.xreader.ui.theme.LocalDesignState.current
    // Code 124: the MATERIAL3 design is removed — the
    // Nocturne composition here is the only shared one left
    // (the family face has its own EditorialDownloadsContent
    // above, which reads the downloads knobs itself).
    // Hoisted local (run #54's lesson): read the delegated state
    // once, then work from plain vals.
    val stateNow = state
    var tab by remember { mutableStateOf(0) }
    var finishedSort by remember { mutableStateOf(FinishedSort.SHELF) }
    // Code 121 (R4): the Material 3 Downloads — medium
    // flexible bar with the storage subtitle, one two-section
    // management list, wavy progress on live rows, segmented
    // list items for finished books, and the storage header
    // card only above its threshold. The Nocturne composition
    // below is unchanged.
    // Code 123: the Editorial Downloads — the approved
    // extension (DESIGN-M3-HISTORY-DOWNLOADS-V6-EXTENSION):
    // the storage card always present, counted connected
    // segments, visible delete doors (F-D3), the Wi-Fi chip's
    // dot in the design's tertiary rust (F-D5).
    if (designState.design ==
        app.xreader.data.prefs.AppDesign.EDITORIAL
    ) {
        EditorialDownloadsContent(
            state = stateNow,
            viewModel = viewModel,
            coverFile = coverFile,
            designState = designState,
            onMakeEpub = onMakeEpub,
            onOpenBook = onOpenBook,
            onOpenCustomise = onOpenCustomise,
            onDownloadsSortChange = onDownloadsSortChange
        )
        return
    }
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(start = 24.dp, end = 24.dp, top = LocalUiMetrics.current.topGap, bottom = 16.dp)
    ) {
        ScreenHeader(title = "Downloads", eyebrow = "Queue")
        if (stateNow.loading) {
            // v1.5.8 (J5/J15): while the first load runs, the
            // storage card would show 0-byte numbers that are
            // not true yet — the state kit says what is
            // happening instead; real numbers land with the load.
            Spacer(Modifier.height(16.dp))
            LoadingState(
                whatIsHappening = "Counting your downloads " +
                    "and storage…",
                modifier = Modifier.weight(1f)
            )
            return@Column
        }
        // v1.2.0 (WS4 declutter): the old "N active • M finished"
        // line under the header repeated the segmented pills'
        // counts verbatim a few centimetres below it. The pills
        // carry the counts now; the header flows straight into
        // the storage card with one gap of air.
        Spacer(Modifier.height(16.dp))

        // ── Offline Storage (§5.12.1): real metering only — book
        // files and the cover cache are measured on disk by the
        // ViewModel; the device total is the volume's StatFs total.
        GlassCard(Modifier.fillMaxWidth()) {
            SectionHeader(title = "Offline storage", modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            val novelsBytes = stateNow.storageBytes
            val coversBytes = stateNow.coverCacheBytes
            val imageCacheBytes = stateNow.imageCacheBytes
            // v1.2.1 (Defect 3): the bar's numerator counts the
            // image cache too — it used to understate the app's
            // real footprint by exactly the cache the card
            // didn't show.
            val usedBytes = novelsBytes + coversBytes + imageCacheBytes
            if (stateNow.deviceTotalBytes > 0L) {
                ProgressPillBar(
                    fraction = (usedBytes.toFloat() / stateNow.deviceTotalBytes.toFloat())
                        .coerceIn(0f, 1f),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "${formatBytes(usedBytes)} of ${formatBytes(stateNow.deviceTotalBytes)} " +
                        "on this device",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                "Novels · ${formatBytes(novelsBytes)}",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                // §12.1: stored covers are per-book assets that
                // SURVIVE Clean cache — never "cached covers".
                "Stored covers · ${formatBytes(coversBytes)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "Reading cache · ${formatBytes(stateNow.readCacheBytes)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "Image cache · ${formatBytes(imageCacheBytes)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // v1.3.1 (item 58): the auto-clean state, in plain
            // words, on the same card that counts the cache.
            Text(
                if (stateNow.autoCleanCapMb > 0) {
                    "Auto-clean on · trims over ${stateNow.autoCleanCapMb} MB"
                } else {
                    "Auto-clean off"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            // v1.2.0 (WS4): the "N books saved" chip repeated the
            // tab counts a third time on this screen — gone. The
            // row is now just the one action it exists for.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Spacer(Modifier.weight(1f))
                GhostPill(
                    text = "Clean cache",
                    modifier = Modifier,
                    // v1.3.1 (item 57): the button clears BOTH
                    // stores the card counts — it used to enable
                    // on covers alone, so with 0 B of covers and
                    // megabytes of image cache it sat disabled
                    // and "Clean cache doesn't clean".
                    // v1.5.2 fix (Track B, §12.3): covers no longer
                    // count toward enabling (Clean cache never
                    // clears them now); the READ cache — the
                    // button's main target — does.
                    enabled = (imageCacheBytes + stateNow.readCacheBytes) > 0L,
                    onClick = { viewModel.clearCaches() }
                )
            }
            // v1.5.2 fix (FIX-6c, §12.3): Clean cache failures (and
            // success) surface here in plain words — never silent.
            if (stateNow.cacheMessage != null) {
                Text(
                    stateNow.cacheMessage!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.height(4.dp))
            RowItem(
                icon = Icons.Filled.Settings,
                label = "Storage & download settings",
                modifier = Modifier.fillMaxWidth(),
                value = null,
                badge = null,
                onClick = onOpenCustomise
            )
        }
        Spacer(Modifier.height(16.dp))

        if (!stateNow.loading && stateNow.active.isEmpty() && stateNow.finished.isEmpty()) {
            // v1.0.4 (D13): the shared EmptyState with the gentle
            // hover treatment from the user's mock — tier-gated by
            // rememberHoverEmptyState, like History and Library.
            EmptyState(
                title = "Nothing downloading",
                message = "Chapters you queue will download here in the " +
                    "background, with progress you can check any time.",
                modifier = Modifier.weight(1f),
                hover = rememberHoverEmptyState(reduceMotion)
            )
            return@Column
        }

        // ── Segmented pills (§5.12.2): real counts on both tabs.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NocturneChip(
                label = "In Progress (${stateNow.active.size})",
                modifier = Modifier,
                selected = tab == 0,
                dotColor = null,
                onClick = { tab = 0 }
            )
            NocturneChip(
                label = "Downloaded (${stateNow.finished.size})",
                modifier = Modifier,
                selected = tab == 1,
                dotColor = null,
                onClick = { tab = 1 }
            )
        }
        Spacer(Modifier.height(12.dp))

        LazyColumn(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(
                bottom = effectiveBottomPadding(88.dp, LocalDockClearance.current)
            )
        ) {
            // ── Failures (§5.12.4): kept visible on BOTH tabs —
            // a failed chapter is never just a number inside a
            // book card (v1.0.6.2 RB ruling, restated in §5.12).
            if (stateNow.failedCount > 0) {
                item(key = "failed-banner") {
                    GlassCard(Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.ErrorOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                            Column(
                                Modifier
                                    .weight(1f)
                                    .padding(horizontal = 12.dp)
                            ) {
                                Text(
                                    "${stateNow.failedCount} couldn't download",
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Text(
                                    "Retry puts them back in the queue.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            PrimaryPill(
                                text = "Retry all",
                                modifier = Modifier,
                                enabled = true,
                                onClick = { viewModel.retryAllFailed() }
                            )
                        }
                    }
                }
            }
            if (tab == 0) {
                if (stateNow.active.isNotEmpty()) {
                    item(key = "active-header") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SectionHeader(
                                title = "Transfer queue",
                                modifier = Modifier.weight(1f)
                            )
                            if (stateNow.hasPausable) {
                                TextButton(onClick = { viewModel.pauseAll() }) {
                                    Text("Pause all")
                                }
                            }
                            if (stateNow.hasResumable) {
                                TextButton(onClick = { viewModel.resumeAll() }) {
                                    Text("Resume all")
                                }
                            }
                        }
                    }
                }
                items(stateNow.active, key = { "active-${it.book.id}" }) { group ->
                    ActiveDownloadCard(
                        group = group,
                        coverFile = coverFile,
                        errorText = viewModel.firstError(group),
                        waitingForWifi = group.waitingForWifi(stateNow),
                        onPause = { viewModel.pauseBook(group) },
                        onResume = {
                            group.activeItems.forEach { viewModel.resume(it) }
                            viewModel.retryFailed(group)
                        },
                        onCancel = { viewModel.cancelBook(group) },
                        modifier = Modifier.animateItem(placementSpec = listPlacementSpec())
                    )
                }
                if (stateNow.active.isEmpty()) {
                    item(key = "active-empty") {
                        // v1.5.8 (J5/J15): a deliberate empty
                        // state, not a bare line — what would
                        // fill it, in plain words.
                        KitEmptyState(
                            icon = Icons.Filled.Download,
                            message = "Nothing in progress right now. " +
                                "Downloads you start from a book page " +
                                "will show here while they run."
                        )
                    }
                }
            } else {
                if (stateNow.finished.isNotEmpty()) {
                    item(key = "finished-header") {
                                                SectionHeader(
                            title = "Downloaded",
                            modifier = Modifier.fillMaxWidth(),
                            actionText = "Sort: ${finishedSort.label}",
                            onAction = {
                                finishedSort = when (finishedSort) {
                                    FinishedSort.SHELF -> FinishedSort.TITLE
                                    FinishedSort.TITLE -> FinishedSort.NEWEST
                                    FinishedSort.NEWEST -> FinishedSort.SHELF
                                }
                            }
                        )
                    }
                }
                // Nocturne's finished-list order: the local
                // sort (Shelf / Title / Newest), cycled by the
                // header action above. (The Material 3 face's
                // own sort knob lives in EditorialDownloads-
                // Content, which reads DesignState itself.)
                // The transfer queue keeps its shipped order.
                val finishedSorted = when (finishedSort) {
                    FinishedSort.SHELF -> stateNow.finished
                    FinishedSort.TITLE -> stateNow.finished.sortedBy { it.book.title.lowercase() }
                    FinishedSort.NEWEST -> stateNow.finished
                        .sortedByDescending { it.book.updatedAt }
                }
                items(finishedSorted, key = { "finished-${it.book.id}" }) { group ->
                    FinishedDownloadRow(
                        group = group,
                        coverFile = coverFile,
                        onMakeEpub = { onMakeEpub(group) },
                        onOpen = { onOpenBook(group.book.id) },
                        modifier = Modifier.animateItem(placementSpec = listPlacementSpec()),
                        // Nocturne: the size line always
                        // shows (the family face's switch
                        // lives in its own composition).
                        showSize = true
                    )
                }
                if (stateNow.finished.isEmpty()) {
                    item(key = "finished-empty") {
                        KitEmptyState(
                            icon = Icons.Filled.CheckCircle,
                            message = "No finished downloads yet. " +
                                "When a download completes, the book " +
                                "waits here, ready to read offline."
                        )
                    }
                }
            }
        }
    }
}

/**
 * The drawing's one honest furniture gift (§5.12.3): a book is
 * waiting for Wi-Fi only when ALL of these real facts hold — the
 * Wi-Fi-only pref is on, the network is metered right now (the
 * engine's own NetworkGate snapshot in the state), and the book
 * still has a QUEUED prefetch item, which is exactly the item
 * kind the engine's Wi-Fi gate defers (DownloadEngine.process).
 */
private fun BookDownloads.waitingForWifi(state: DownloadsUiState): Boolean =
    state.wifiOnlyPrefetch && state.onMeteredNetwork &&
        items.any {
            it.state == QueueState.QUEUED && it.kind == DownloadKind.PREFETCH
        }

/**
 * v1.2.0 (WS4, sized Coil requests): decode a download card's
 * cover near its drawn size (48×68dp here) instead of the full
 * stored image. Same pattern as the Library's helper.
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

@Composable
private fun ActiveDownloadCard(
    group: BookDownloads,
    coverFile: (String?) -> File?,
    errorText: String?,
    waitingForWifi: Boolean,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val progress = animateProgressAsState(group.fraction, label = "downloadProgress")
    val paused = group.activeItems.any { it.state == QueueState.PAUSED } &&
        group.activeItems.none { it.state == QueueState.RUNNING }
    val coverRequest = rememberSizedCover(coverFile(group.book.coverPath), 48, 68)
    GlassCard(modifier = modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            AsyncImage(
                model = coverRequest ?: coverFile(group.book.coverPath)
                ?: group.book.coverUrl?.takeIf { it.isNotBlank() },
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(width = 48.dp, height = 68.dp)
                    .clip(RoundedCornerShape(14.dp))
            )
            Column(
                Modifier
                    .weight(1f)
                    .padding(start = 12.dp)
            ) {
                Text(
                    group.book.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1
                )
                val chapterLine = if (group.isLocalFile) {
                    // v1.5.6 (§3.1 row 12): the file itself, no
                    // per-chapter download states.
                    group.statusLine
                } else if (group.totalChapters > 0) {
                    "${group.readyChapters} of ${group.totalChapters} chapters"
                } else {
                    group.statusLine
                }
                Text(
                    chapterLine,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (waitingForWifi) {
                    Spacer(Modifier.height(6.dp))
                    NocturneChip(
                        label = "Waiting for Wi-Fi",
                        modifier = Modifier,
                        selected = false,
                        dotColor = EmberAmber,
                        onClick = null
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ProgressPillBar(
                        fraction = progress,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "${(progress * 100).toInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
                // v1.2.0 (WS4 declutter): the status line under
                // the progress bar used to repeat what the bar,
                // the percent and the chapter line already said
                // ("Downloading…" under a moving bar). It now
                // renders ONLY when it adds information: a
                // failure (with its retry instruction) or a
                // paused book whose chapter line isn't already
                // the status line.
                val detailLine = when {
                    group.failedItems.isNotEmpty() ->
                        "${group.failedItems.size} couldn't download — tap play to retry"
                    paused && group.totalChapters > 0 -> "Paused"
                    else -> null
                }
                if (detailLine != null) {
                    Text(
                        detailLine,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (group.failedItems.isNotEmpty()) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
                if (errorText != null && group.failedItems.isNotEmpty()) {
                    Text(
                        errorText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Column {
                IconButton(onClick = if (paused || group.failedItems.isNotEmpty()) onResume else onPause) {
                    Icon(
                        if (paused || group.failedItems.isNotEmpty()) {
                            Icons.Filled.PlayArrow
                        } else {
                            Icons.Filled.Pause
                        },
                        contentDescription = if (paused) "Resume" else "Pause"
                    )
                }
                IconButton(onClick = onCancel) {
                    Icon(Icons.Filled.Close, contentDescription = "Cancel download")
                }
            }
        }
    }
}

@Composable
private fun FinishedDownloadRow(
    group: BookDownloads,
    coverFile: (String?) -> File?,
    onMakeEpub: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    /** Code 120 completion (R4): the size line's knob — the
     * downloaded chapters' total size for this book (the only
     * size this surface carries; there are no per-chapter rows
     * here to hang per-chapter sizes on). True under Nocturne
     * and at the knob's On default. */
    showSize: Boolean = true
) {
    val coverRequest = rememberSizedCover(coverFile(group.book.coverPath), 48, 68)
    GlassCard(
        modifier = modifier.fillMaxWidth(),
        onClick = onOpen,
        bouncePress = true
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Cover with the downloaded check badge (§5.12.5).
            Box {
                AsyncImage(
                    model = coverRequest ?: coverFile(group.book.coverPath)
                ?: group.book.coverUrl?.takeIf { it.isNotBlank() },
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(width = 48.dp, height = 68.dp)
                        .clip(RoundedCornerShape(14.dp))
                )
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.secondary),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = "Downloaded",
                        tint = MaterialTheme.colorScheme.onSecondary,
                        modifier = Modifier.size(12.dp)
                    )
                }
            }
            Column(
                Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(group.book.title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                StatusPill(
                    text = if (group.isLocalFile) {
                        // v1.5.6 (§3.1 row 12): the file itself.
                        group.statusLine
                    } else {
                        "Downloaded • ${group.readyChapters} chapters"
                    },
                    color = MaterialTheme.colorScheme.secondary
                )
                if (showSize) {
                    Text(
                        formatBytes(
                            if (group.isLocalFile) group.localFileBytes else group.bytesOnDisk
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryPill(
                    text = "Read offline",
                    modifier = Modifier,
                    enabled = true,
                    onClick = onOpen
                )
                GhostPill(
                    text = "Make EPUB",
                    modifier = Modifier,
                    enabled = true,
                    onClick = onMakeEpub
                )
            }
        }
    }
}

// ── Code 121 (R4): the Material 3 Downloads ────────────────

@OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)
@Composable
private fun EditorialDownloadsContent(
    state: DownloadsUiState,
    viewModel: DownloadsViewModel,
    coverFile: (String?) -> File?,
    designState: app.xreader.data.prefs.DesignState,
    onMakeEpub: (BookDownloads) -> Unit,
    onOpenBook: (Long) -> Unit,
    onOpenCustomise: () -> Unit,
    onDownloadsSortChange: (app.xreader.data.prefs.M3DownloadsSort) -> Unit
) {
    val colors = MaterialTheme.colorScheme
    var tab by remember { mutableStateOf(0) }
    var pendingDelete by remember {
        mutableStateOf<Pair<Long, Boolean>?>(null)
    }
    val usedBytes = state.storageBytes + state.coverCacheBytes +
        state.imageCacheBytes
    val finishedSorted = when (designState.downloadsSort) {
        app.xreader.data.prefs.M3DownloadsSort.RECENT ->
            state.finished.sortedByDescending { it.book.updatedAt }
        app.xreader.data.prefs.M3DownloadsSort.TITLE ->
            state.finished.sortedBy { it.book.title.lowercase() }
        app.xreader.data.prefs.M3DownloadsSort.SIZE ->
            state.finished.sortedByDescending {
                if (it.isLocalFile) it.localFileBytes else it.bytesOnDisk
            }
    }

    if (state.loading) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            EditorialDownloadsHead()
            LoadingState(
                whatIsHappening = "Counting your downloads and storage…"
            )
        }
        return
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 20.dp, end = 20.dp, top = 16.dp, bottom = 112.dp
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item(key = "ed-dl-head") { EditorialDownloadsHead() }

        // ── Offline Storage (always — F-D4) ───────────────────
        item(key = "ed-dl-storage") {
            Surface(
                color = colors.surfaceContainerLow,
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(18.dp)) {
                    Text(
                        "Offline storage",
                        fontFamily = FrauncesSerif,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 19.sp,
                        color = colors.onSurface
                    )
                    Spacer(Modifier.height(10.dp))
                    if (state.deviceTotalBytes > 0L) {
                        androidx.compose.material3
                            .LinearProgressIndicator(
                                progress = {
                                    (usedBytes.toFloat() /
                                        state.deviceTotalBytes.toFloat())
                                        .coerceIn(0f, 1f)
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp)),
                                color = colors.primary,
                                trackColor = colors.surfaceContainerHighest,
                                strokeCap = androidx.compose.ui.graphics
                                    .StrokeCap.Round
                            )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "${formatBytes(usedBytes)} of " +
                                "${formatBytes(state.deviceTotalBytes)} " +
                                "on this device",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    EditorialMeterLine(
                        "Novels", formatBytes(state.storageBytes)
                    )
                    EditorialMeterLine(
                        "Stored covers",
                        formatBytes(state.coverCacheBytes)
                    )
                    EditorialMeterLine(
                        "Reading cache",
                        formatBytes(state.readCacheBytes)
                    )
                    EditorialMeterLine(
                        "Image cache",
                        formatBytes(state.imageCacheBytes)
                    )
                    EditorialMeterLine(
                        "Auto-clean",
                        if (state.autoCleanCapMb > 0) {
                            "On · trims over ${state.autoCleanCapMb} MB"
                        } else {
                            "Off"
                        }
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (state.cacheMessage != null) {
                            Text(
                                state.cacheMessage!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.primary,
                                modifier = Modifier.weight(1f)
                            )
                        } else {
                            Spacer(Modifier.weight(1f))
                        }
                        Surface(
                            color = colors.surfaceContainerHigh,
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.clickable(
                                enabled = (state.imageCacheBytes +
                                    state.readCacheBytes) > 0L,
                                onClick = { viewModel.clearCaches() }
                            )
                        ) {
                            Text(
                                "Clean cache",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (
                                    (state.imageCacheBytes +
                                        state.readCacheBytes) > 0L
                                ) {
                                    colors.onSurface
                                } else {
                                    colors.onSurfaceVariant
                                },
                                modifier = Modifier.padding(
                                    horizontal = 14.dp, vertical = 9.dp
                                )
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onOpenCustomise)
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Surface(
                            color = colors.secondaryContainer,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.size(38.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Filled.Settings,
                                    contentDescription = null,
                                    tint = colors.onSecondaryContainer,
                                    modifier = Modifier.size(19.dp)
                                )
                            }
                        }
                        Text(
                            "Storage & download settings",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            Icons.Filled.ChevronRight,
                            contentDescription = null,
                            tint = colors.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // ── Failures banner (both segments) ───────────────────
        if (state.failedCount > 0) {
            item(key = "ed-dl-failed") {
                Surface(
                    color = colors.errorContainer,
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Filled.ErrorOutline,
                            contentDescription = null,
                            tint = colors.onErrorContainer
                        )
                        Column(
                            Modifier
                                .weight(1f)
                                .padding(horizontal = 12.dp)
                        ) {
                            Text(
                                "${state.failedCount} couldn't download",
                                style = MaterialTheme.typography.titleSmall,
                                color = colors.onErrorContainer
                            )
                            Text(
                                "Retry puts them back in the queue.",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onErrorContainer
                            )
                        }
                        Surface(
                            color = colors.error,
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.clickable {
                                viewModel.retryAllFailed()
                            }
                        ) {
                            Text(
                                "Retry all",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.onError,
                                modifier = Modifier.padding(
                                    horizontal = 14.dp, vertical = 9.dp
                                )
                            )
                        }
                    }
                }
            }
        }

        // ── Counted connected segments ────────────────────────
        item(key = "ed-dl-segments") {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                val segments = listOf(
                    "In Progress (${state.active.size})",
                    "Downloaded (${state.finished.size})"
                )
                segments.forEachIndexed { index, label ->
                    val picked = tab == index
                    val shape = if (index == 0) {
                        RoundedCornerShape(
                            topStart = 24.dp, bottomStart = 24.dp,
                            topEnd = 6.dp, bottomEnd = 6.dp
                        )
                    } else {
                        RoundedCornerShape(
                            topStart = 6.dp, bottomStart = 6.dp,
                            topEnd = 24.dp, bottomEnd = 24.dp
                        )
                    }
                    Surface(
                        color = if (picked) {
                            colors.primary
                        } else {
                            colors.surfaceContainer
                        },
                        shape = shape,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .clickable { tab = index }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                label,
                                fontSize = 13.sp,
                                fontWeight = if (picked) {
                                    FontWeight.SemiBold
                                } else {
                                    FontWeight.Medium
                                },
                                color = if (picked) {
                                    colors.onPrimary
                                } else {
                                    colors.onSurfaceVariant
                                },
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }
            }
        }

        if (state.active.isEmpty() && state.finished.isEmpty()) {
            item(key = "ed-dl-empty") {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        "Nothing downloading",
                        fontFamily = FrauncesSerif,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 22.sp,
                        color = colors.onSurface
                    )
                    Text(
                        "Chapters you queue will download here in " +
                            "the background, with progress you can " +
                            "check any time.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style
                            .TextAlign.Center
                    )
                }
            }
        } else if (tab == 0) {
            item(key = "ed-dl-queue-head") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Transfer queue",
                        fontFamily = FrauncesSerif,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 19.sp,
                        color = colors.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    if (state.hasPausable) {
                        TextButton(onClick = { viewModel.pauseAll() }) {
                            Text("Pause all")
                        }
                    }
                    if (state.hasResumable) {
                        TextButton(onClick = { viewModel.resumeAll() }) {
                            Text("Resume all")
                        }
                    }
                }
            }
            if (state.active.isEmpty()) {
                item(key = "ed-dl-queue-empty") {
                    Text(
                        "Nothing in progress right now. Downloads " +
                            "you start from a book page will show " +
                            "here while they run.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant
                    )
                }
            }
            items(state.active, key = { "ed-active-${it.book.id}" }) { group ->
                EditorialActiveDownloadCard(
                    group = group,
                    coverFile = coverFile,
                    errorText = viewModel.firstError(group),
                    waitingForWifi = group.waitingForWifi(state),
                    onPause = { viewModel.pauseBook(group) },
                    onResume = {
                        group.activeItems.forEach { viewModel.resume(it) }
                        viewModel.retryFailed(group)
                    },
                    onCancel = { viewModel.cancelBook(group) }
                )
            }
        } else {
            item(key = "ed-dl-finished-head") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Downloaded",
                        fontFamily = FrauncesSerif,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 19.sp,
                        color = colors.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = {
                            onDownloadsSortChange(
                                when (designState.downloadsSort) {
                                    app.xreader.data.prefs
                                        .M3DownloadsSort.RECENT ->
                                        app.xreader.data.prefs
                                            .M3DownloadsSort.TITLE
                                    app.xreader.data.prefs
                                        .M3DownloadsSort.TITLE ->
                                        app.xreader.data.prefs
                                            .M3DownloadsSort.SIZE
                                    app.xreader.data.prefs
                                        .M3DownloadsSort.SIZE ->
                                        app.xreader.data.prefs
                                            .M3DownloadsSort.RECENT
                                }
                            )
                        }
                    ) {
                        Text(
                            "Sort: " + when (designState.downloadsSort) {
                                app.xreader.data.prefs
                                    .M3DownloadsSort.RECENT -> "Recent"
                                app.xreader.data.prefs
                                    .M3DownloadsSort.TITLE -> "Title"
                                app.xreader.data.prefs
                                    .M3DownloadsSort.SIZE -> "Size"
                            }
                        )
                    }
                }
            }
            if (state.finished.isEmpty()) {
                item(key = "ed-dl-finished-empty") {
                    Text(
                        "No finished downloads yet. When a download " +
                            "completes, the book waits here, ready " +
                            "to read offline.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant
                    )
                }
            }
            items(
                finishedSorted,
                key = { "ed-finished-${it.book.id}" }
            ) { group ->
                EditorialFinishedDownloadRow(
                    group = group,
                    coverFile = coverFile,
                    showSize = designState.downloadsChapterSize,
                    onOpen = { onOpenBook(group.book.id) },
                    onMakeEpub = { onMakeEpub(group) },
                    onDeleteChapters = {
                        pendingDelete = group.book.id to false
                    },
                    onDeleteBookDownloads = {
                        pendingDelete = group.book.id to true
                    }
                )
            }
        }
    }

    // The delete confirm — the code's own words (S5 pattern).
    pendingDelete?.let { (bookId, wholeBook) ->
        val title = (state.active + state.finished)
            .firstOrNull { it.book.id == bookId }
            ?.book?.title ?: "this book"
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = {
                Text(
                    if (wholeBook) {
                        "Delete this book's downloads?"
                    } else {
                        "Delete downloaded chapters?"
                    }
                )
            },
            text = {
                Text(
                    if (wholeBook) {
                        "Every downloaded chapter and download " +
                            "record for “$title” is deleted. " +
                            "The book and your place in it are kept."
                    } else {
                        "The downloaded chapters of “$title” " +
                            "are deleted. The book and your " +
                            "place in it are kept."
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        if (wholeBook) {
                            viewModel.deleteBookDownloads(bookId)
                        } else {
                            viewModel.deleteDownloadedChapters(bookId)
                        }
                    }
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun EditorialDownloadsHead() {
    val colors = MaterialTheme.colorScheme
    Column {
        Text(
            "QUEUE",
            style = MaterialTheme.typography.labelSmall,
            letterSpacing = 1.6.sp,
            color = colors.primary
        )
        Text(
            "Downloads",
            fontFamily = FrauncesSerif,
            fontWeight = FontWeight.SemiBold,
            fontSize = 34.sp,
            color = colors.onSurface
        )
    }
}

@Composable
private fun EditorialMeterLine(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun EditorialActiveDownloadCard(
    group: BookDownloads,
    coverFile: (String?) -> File?,
    errorText: String?,
    waitingForWifi: Boolean,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val progress = animateProgressAsState(
        group.fraction, label = "edDownloadProgress"
    )
    val paused = group.activeItems.any {
        it.state == QueueState.PAUSED
    } && group.activeItems.none {
        it.state == QueueState.RUNNING
    }
    Surface(
        color = colors.surfaceContainerLow,
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            app.xreader.ui.common.M3CoverThumbnail(
                title = group.book.title,
                author = group.book.author.takeIf { it.isNotBlank() },
                coverModel = coverFile(group.book.coverPath)
                    ?.takeIf { it.exists() }
                    ?: group.book.coverUrl?.takeIf { it.isNotBlank() },
                modifier = Modifier.size(width = 48.dp, height = 68.dp)
            )
            Column(Modifier.weight(1f)) {
                Text(
                    group.book.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style
                        .TextOverflow.Ellipsis
                )
                Text(
                    if (group.isLocalFile) {
                        group.statusLine
                    } else if (group.totalChapters > 0) {
                        "${group.readyChapters} of " +
                            "${group.totalChapters} chapters"
                    } else {
                        group.statusLine
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant
                )
                if (waitingForWifi) {
                    Row(
                        Modifier.padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(colors.tertiary)
                        )
                        Text(
                            "Waiting for Wi-Fi",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.onSurface
                        )
                    }
                }
                Row(
                    Modifier.padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    androidx.compose.material3
                        .LinearWavyProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.weight(1f)
                        )
                    Text(
                        "${(progress * 100).toInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.onSurface
                    )
                }
                val detailLine = when {
                    group.failedItems.isNotEmpty() ->
                        "${group.failedItems.size} couldn't " +
                            "download — tap play to retry"
                    paused && group.totalChapters > 0 -> "Paused"
                    else -> null
                }
                if (detailLine != null) {
                    Text(
                        detailLine,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (group.failedItems.isNotEmpty()) {
                            colors.error
                        } else {
                            colors.onSurfaceVariant
                        }
                    )
                }
                if (errorText != null && group.failedItems.isNotEmpty()) {
                    Text(
                        errorText,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant
                    )
                }
            }
            Column {
                Surface(
                    color = colors.primaryContainer,
                    shape = CircleShape,
                    modifier = Modifier
                        .size(40.dp)
                        .clickable(
                            onClick = if (
                                paused || group.failedItems.isNotEmpty()
                            ) {
                                onResume
                            } else {
                                onPause
                            }
                        )
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            if (
                                paused || group.failedItems.isNotEmpty()
                            ) {
                                Icons.Filled.PlayArrow
                            } else {
                                Icons.Filled.Pause
                            },
                            contentDescription = if (paused) {
                                "Resume"
                            } else {
                                "Pause"
                            },
                            tint = colors.onPrimaryContainer,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Surface(
                    color = colors.surfaceContainerHigh,
                    shape = CircleShape,
                    modifier = Modifier
                        .size(40.dp)
                        .clickable(onClick = onCancel)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "Cancel download",
                            tint = colors.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EditorialFinishedDownloadRow(
    group: BookDownloads,
    coverFile: (String?) -> File?,
    showSize: Boolean,
    onOpen: () -> Unit,
    onMakeEpub: () -> Unit,
    onDeleteChapters: () -> Unit,
    onDeleteBookDownloads: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val sizeBytes = if (group.isLocalFile) {
        group.localFileBytes
    } else {
        group.bytesOnDisk
    }
    Surface(
        color = colors.surfaceContainerLow,
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box {
                    app.xreader.ui.common.M3CoverThumbnail(
                        title = group.book.title,
                        author = group.book.author
                            .takeIf { it.isNotBlank() },
                        coverModel = coverFile(group.book.coverPath)
                            ?.takeIf { it.exists() }
                            ?: group.book.coverUrl
                                ?.takeIf { it.isNotBlank() },
                        modifier = Modifier
                            .size(width = 48.dp, height = 68.dp)
                    )
                    Surface(
                        color = colors.primary,
                        shape = CircleShape,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .size(18.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = "Downloaded",
                                tint = colors.onPrimary,
                                modifier = Modifier.size(12.dp)
                            )
                        }
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        group.book.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.onSurface,
                        maxLines = 2,
                        overflow = androidx.compose.ui.text.style
                            .TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(4.dp))
                    Surface(
                        color = colors.secondaryContainer,
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(
                            if (group.isLocalFile) {
                                group.statusLine
                            } else {
                                "Downloaded • " +
                                    "${group.readyChapters} chapters"
                            },
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.onSecondaryContainer,
                            modifier = Modifier.padding(
                                horizontal = 8.dp, vertical = 4.dp
                            )
                        )
                    }
                    // Patch B (gap 45): size shows for local-file
                    // books too — Nocturne always shows it
                    // (sizeBytes already resolves localFileBytes).
                    if (showSize) {
                        Text(
                            formatBytes(sizeBytes),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
            // Doors — all visible (F-D3).
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    color = colors.primary,
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.clickable(onClick = onOpen)
                ) {
                    Text(
                        "Read offline",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.onPrimary,
                        modifier = Modifier.padding(
                            horizontal = 12.dp, vertical = 7.dp
                        )
                    )
                }
                Surface(
                    color = colors.surfaceContainerHigh,
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.clickable(onClick = onMakeEpub)
                ) {
                    Text(
                        "Make EPUB",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.onSurface,
                        modifier = Modifier.padding(
                            horizontal = 12.dp, vertical = 7.dp
                        )
                    )
                }
            }
            // Code 124 (D22): the two deletes get their OWN
            // row — on one line with Read offline + Make EPUB
            // they ran ≈450dp wide in a ≈383dp row and the
            // labels wrapped/crushed.
            if (!group.isLocalFile) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDeleteChapters) {
                        Text(
                            "Delete chapters",
                            fontSize = 12.sp,
                            color = colors.error
                        )
                    }
                    TextButton(onClick = onDeleteBookDownloads) {
                        Text(
                            "Delete book's downloads",
                            fontSize = 12.sp,
                            color = colors.error
                        )
                    }
                }
            }
        }
    }
}
