package app.xreader.ui.update

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.xreader.data.update.ReleaseParsing
import app.xreader.data.update.UpdateDownloadPhase
import app.xreader.ui.common.GlassCard

/**
 * The update card (v1.5.42 redesign, from the YTXTube study —
 * design/behaviour adopted, code our own): a CENTRED modal —
 * the host in MainScaffold owns the dimmed scrim and the
 * spring pop-in. One glance gives the new version, the
 * current version + download size on ONE quiet line, and the
 * gist of the changes; one primary button runs the whole
 * journey (Update now → downloading → Install).
 *
 * Built on [GlassCard], so it rides the app's own glass
 * pipeline: real Haze frosted glass where the device and the
 * appearance settings support it, and the same shape on a
 * solid Tier surface in plain mode (API < 31 / low RAM) —
 * designed in both, never a fallback box.
 *
 * Getting out is always possible (the owner's "can't back
 * out" fix): the × button, the scrim, Android Back and
 * "Later" dismiss in the resting state; DURING a download
 * the same doors become "Hide" — the card goes away and the
 * download continues in the background under its
 * notification, while Cancel stays the one explicit stop.
 *
 * The SHA-256 verify-before-install gate is unchanged: an
 * Install is offered only for a checksum-matched file, and
 * the permission detour stays in plain words, in place.
 */
@Composable
fun UpdateCard(
    state: AppUpdateViewModel.UiState,
    onUpdateNow: () -> Unit,
    onDismiss: () -> Unit,
    onReadMore: () -> Unit,
    onInstall: () -> Unit,
    onCancelDownload: () -> Unit,
    onOpenInstallSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val release = state.cardRelease ?: return
    val scheme = MaterialTheme.colorScheme
    val downloading = state.download is UpdateDownloadPhase.Running
    GlassCard(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(20.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // ── Header: chip + version lines + close ──────
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    Modifier
                        .size(44.dp)
                        .background(
                            scheme.primary.copy(alpha = 0.16f),
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.SystemUpdate,
                        contentDescription = null,
                        tint = scheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Column(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        "Xreader ${release.version} is ready",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val size = formatDownloadSize(release.apk.sizeBytes)
                    Text(
                        "You're on ${state.currentVersion}" +
                            if (size.isNotEmpty()) " · $size" else "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant,
                        // One line, always — the size never
                        // wraps into an orphan word (the
                        // v1.5.41 card's "71 MB / download"
                        // break); a starved width ellipsises
                        // instead of folding.
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Close",
                        tint = scheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // ── What's new: the gist, in the card ─────────
            val summary = ReleaseParsing.shortSummary(release.body)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "What's new",
                    style = MaterialTheme.typography.titleSmall
                )
                Column(
                    Modifier
                        .heightIn(max = 172.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    if (summary.isEmpty()) {
                        Text(
                            GENERIC_SUMMARY,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    } else {
                        summary.forEach { line ->
                            Text(
                                "• $line",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }

            // ── Phase: pill progress while downloading,
            // the shared plain-words block otherwise ──────
            if (downloading) {
                val running = state.download as UpdateDownloadPhase.Running
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    PillProgress(fraction = running.fraction)
                    Text(
                        if (running.fraction != null) {
                            "${(running.fraction * 100).toInt()}% · Downloading…"
                        } else {
                            "Downloading…"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant
                    )
                }
            } else {
                UpdateProgressBlock(state.download)
            }

            // ── Actions: one journey button + the doors ───
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                UpdatePrimaryButton(
                    phase = state.download,
                    onUpdateNow = onUpdateNow,
                    onInstall = onInstall,
                    onOpenInstallSettings = onOpenInstallSettings,
                    modifier = Modifier.weight(1f)
                )
                if (downloading) {
                    TextButton(onClick = onDismiss) { Text("Hide") }
                    TextButton(onClick = onCancelDownload) { Text("Cancel") }
                } else {
                    TextButton(onClick = onDismiss) { Text("Later") }
                }
            }

            // ── Read more + the quiet provenance line ─────
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onReadMore) { Text("Read more") }
                Spacer(Modifier.weight(1f))
                Text(
                    "From GitHub Releases · ${release.tagName}",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** The slim pill progress (6dp, fully rounded, violet fill
 * on a quiet track) — a null [fraction] (unknown length)
 * shows a calm partial bar. */
@Composable
private fun PillProgress(fraction: Float?) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier
            .fillMaxWidth()
            .height(6.dp)
            .background(
                scheme.onSurface.copy(alpha = 0.12f),
                RoundedCornerShape(50)
            )
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction?.coerceIn(0f, 1f) ?: 0.35f)
                .fillMaxHeight()
                .background(scheme.primary, RoundedCornerShape(50))
        )
    }
}

/** Shown when a release publishes no short summary of its
 * own — one calm line, never the raw release body. */
const val GENERIC_SUMMARY = "A new version of Xreader is ready."
