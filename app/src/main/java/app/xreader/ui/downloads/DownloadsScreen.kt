package app.xreader.ui.downloads

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.xreader.core.motion.animateProgressAsState
import app.xreader.core.motion.listPlacementSpec
import app.xreader.data.db.QueueState
import app.xreader.ui.common.EmptyState
import app.xreader.ui.common.GlassCard
import app.xreader.ui.common.ScreenHeader
import app.xreader.ui.common.rememberHoverEmptyState
import app.xreader.ui.common.StatusPill
import coil.compose.AsyncImage
import java.io.File

/**
 * Downloads (Stitch design, PLAN §4): status line, active per-book
 * download cards with animated progress + pause/cancel, and
 * finished rows with "Make EPUB". All progress animates through
 * the motion engine's progress adapter (Amendment 5). (v1.0.4: the
 * Wi-Fi-only switch moved to Settings — see the note in the body.)
 */
@Composable
fun DownloadsScreen(
    viewModel: DownloadsViewModel,
    coverFile: (String?) -> File?,
    onMakeEpub: (BookDownloads) -> Unit,
    onOpenBook: (Long) -> Unit,
    /** v1.0.4 (D13): gates the empty-state hover (shell passes it). */
    reduceMotion: Boolean = false
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        ScreenHeader(title = "Downloads", eyebrow = "Queue")
        Spacer(Modifier.height(4.dp))
        Text(
            state.statusSummary,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        // v1.0.6.2 (D2): one plain storage line + Pause all /
        // Resume all for the whole queue.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                state.storageLine,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
        Spacer(Modifier.height(16.dp))

        // v1.0.4 (D13 ruling — "Settings, all download switches
        // together"): the Wi-Fi-only switch no longer lives here.
        // It moved to Settings → Customise → Downloads, beside the
        // auto-download switch. This screen keeps the queue, the
        // counts line and the storage story only.

        if (!state.loading && state.active.isEmpty() && state.finished.isEmpty()) {
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

        LazyColumn(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // v1.0.6.2 (RB): failures get their own plain
            // section with one retry for all of them — a failed
            // chapter is never just a number inside a book card.
            if (state.failedCount > 0) {
                item(key = "failed-header") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Couldn't download (${state.failedCount})",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { viewModel.retryAllFailed() }) {
                            Text("Retry all")
                        }
                    }
                }
            }
            if (state.active.isNotEmpty()) {
                item(key = "active-header") {
                    Text("Active", style = MaterialTheme.typography.titleMedium)
                }
            }
            items(state.active, key = { "active-${it.book.id}" }) { group ->
                ActiveDownloadCard(
                    group = group,
                    coverFile = coverFile,
                    errorText = viewModel.firstError(group),
                    onPause = { viewModel.pauseBook(group) },
                    onResume = {
                        group.activeItems.forEach { viewModel.resume(it) }
                        viewModel.retryFailed(group)
                    },
                    onCancel = { viewModel.cancelBook(group) },
                    modifier = Modifier.animateItem(placementSpec = listPlacementSpec())
                )
            }
            if (state.finished.isNotEmpty()) {
                item(key = "finished-header") {
                    Text("Finished", style = MaterialTheme.typography.titleMedium)
                }
            }
            items(state.finished, key = { "finished-${it.book.id}" }) { group ->
                FinishedDownloadRow(
                    group = group,
                    coverFile = coverFile,
                    onMakeEpub = { onMakeEpub(group) },
                    onOpen = { onOpenBook(group.book.id) },
                    modifier = Modifier.animateItem(placementSpec = listPlacementSpec())
                )
            }
        }
    }
}

@Composable
private fun ActiveDownloadCard(
    group: BookDownloads,
    coverFile: (String?) -> File?,
    errorText: String?,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val progress = animateProgressAsState(group.fraction, label = "downloadProgress")
    val paused = group.activeItems.any { it.state == QueueState.PAUSED } &&
        group.activeItems.none { it.state == QueueState.RUNNING }
    GlassCard(modifier = modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            AsyncImage(
                model = coverFile(group.book.coverPath),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(width = 48.dp, height = 68.dp)
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
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "${(progress * 100).toInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
                Text(
                    when {
                        group.failedItems.isNotEmpty() ->
                            "${group.failedItems.size} couldn't download — tap play to retry"
                        else -> group.statusLine
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (group.failedItems.isNotEmpty()) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
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
    GlassCard(
        modifier = modifier.fillMaxWidth(),
        onClick = onOpen,
        bouncePress = true
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(28.dp)
            )
            Column(
                Modifier
                    .weight(1f)
                    .padding(start = 12.dp)
            ) {
                Text(group.book.title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                StatusPill(
                    text = "Downloaded • ${group.readyChapters} chapters",
                    color = MaterialTheme.colorScheme.secondary
                )
            }
            TextButton(onClick = onMakeEpub) { Text("Make EPUB") }
        }
    }
}
