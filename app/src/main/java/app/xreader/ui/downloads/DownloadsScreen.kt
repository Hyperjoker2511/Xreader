package app.xreader.ui.downloads

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.xreader.core.motion.animateProgressAsState
import app.xreader.core.motion.listPlacementSpec
import app.xreader.data.db.DownloadKind
import app.xreader.data.db.QueueState
import app.xreader.ui.common.EmptyState
import app.xreader.ui.common.GlassCard
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
    onOpenCustomise: () -> Unit = {}
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Hoisted local (run #54's lesson): read the delegated state
    // once, then work from plain vals.
    val stateNow = state
    var tab by remember { mutableStateOf(0) }
    var finishedSort by remember { mutableStateOf(FinishedSort.SHELF) }
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        ScreenHeader(title = "Downloads", eyebrow = "Queue")
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
            val usedBytes = novelsBytes + coversBytes
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
                "Cached covers · ${formatBytes(coversBytes)}",
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
                    enabled = coversBytes > 0L,
                    onClick = { viewModel.clearCaches() }
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
            contentPadding = PaddingValues(bottom = 88.dp)
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
                        Text(
                            "Nothing in progress right now.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
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
                        modifier = Modifier.animateItem(placementSpec = listPlacementSpec())
                    )
                }
                if (stateNow.finished.isEmpty()) {
                    item(key = "finished-empty") {
                        Text(
                            "No finished downloads yet.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
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
                model = coverRequest ?: coverFile(group.book.coverPath),
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
                val chapterLine = if (group.totalChapters > 0) {
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
    modifier: Modifier = Modifier
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
                    model = coverRequest ?: coverFile(group.book.coverPath),
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
                    text = "Downloaded • ${group.readyChapters} chapters",
                    color = MaterialTheme.colorScheme.secondary
                )
                Text(
                    formatBytes(group.bytesOnDisk),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
