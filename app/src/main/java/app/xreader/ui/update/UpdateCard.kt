package app.xreader.ui.update

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.xreader.data.update.ReleaseParsing
import app.xreader.ui.common.GlassCard

/**
 * The floating update card (user's spec, 2026-11-01): real
 * frosted glass over the Library, a SHORT plain-words
 * summary only — never the full release notes — and exactly
 * two actions, Update Now / Ignore, with a quiet Read more
 * text link into the in-app What's New screen. Shown at
 * most once a day; it never blocks reading and dismisses
 * without consequence.
 */
@Composable
fun UpdateCard(
    state: AppUpdateViewModel.UiState,
    onUpdateNow: () -> Unit,
    onIgnore: () -> Unit,
    onReadMore: () -> Unit,
    onInstall: () -> Unit,
    onCancelDownload: () -> Unit,
    onOpenInstallSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val release = state.cardRelease ?: return
    val summary = ReleaseParsing.shortSummary(release.body)
    val downloading = state.download is AppUpdateViewModel.DownloadPhase.Running
    GlassCard(modifier = modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    Modifier
                        .size(40.dp)
                        .background(
                            MaterialTheme.colorScheme.surfaceContainerHigh,
                            RoundedCornerShape(16.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.SystemUpdate,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        "Xreader ${release.version} is ready",
                        style = MaterialTheme.typography.titleMedium
                    )
                    val size = formatDownloadSize(release.apk.sizeBytes)
                    Text(
                        "You're on ${state.currentVersion}" +
                            if (size.isNotEmpty()) " · $size download" else "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (summary.isEmpty()) {
                Text(
                    GENERIC_SUMMARY,
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    summary.forEach { line ->
                        Text(
                            "• $line",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
            UpdateProgressBlock(state.download)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                UpdatePrimaryButton(
                    phase = state.download,
                    onUpdateNow = onUpdateNow,
                    onInstall = onInstall,
                    onOpenInstallSettings = onOpenInstallSettings
                )
                TextButton(
                    onClick = if (downloading) onCancelDownload else onIgnore
                ) {
                    Text(if (downloading) "Cancel" else "Ignore")
                }
            }
            TextButton(onClick = onReadMore) {
                Text("Read more")
            }
        }
    }
}

/** Shown when a release publishes no short summary of its
 * own — one calm line, never the raw release body. */
const val GENERIC_SUMMARY = "A new version of Xreader is ready."
