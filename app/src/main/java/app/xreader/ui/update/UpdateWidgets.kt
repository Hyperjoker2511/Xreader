package app.xreader.ui.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.xreader.ui.common.XreaderPrimaryButton

/**
 * The update flow's shared pieces (v1.3.5): the card, the
 * What's New screen and the Settings section all present
 * the SAME download state the same way, driven by
 * [AppUpdateViewModel].
 */

/** Download progress / failure / permission copy, in plain
 * words, sized for a card or a screen section alike. */
@Composable
fun UpdateProgressBlock(
    phase: AppUpdateViewModel.DownloadPhase,
    modifier: Modifier = Modifier
) {
    when (phase) {
        AppUpdateViewModel.DownloadPhase.None -> Unit
        is AppUpdateViewModel.DownloadPhase.Running -> {
            Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (phase.fraction != null) {
                    LinearProgressIndicator(
                        progress = { phase.fraction },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "Downloading… ${(phase.fraction * 100).toInt()}%",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        "Downloading…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        AppUpdateViewModel.DownloadPhase.Verifying ->
            Text(
                "Checking the downloaded file…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = modifier
            )
        is AppUpdateViewModel.DownloadPhase.Ready ->
            Text(
                "Downloaded and checked — ready to install.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = modifier
            )
        is AppUpdateViewModel.DownloadPhase.Failed ->
            Text(
                phase.text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = modifier
            )
        AppUpdateViewModel.DownloadPhase.NeedsInstallPermission ->
            Text(
                "Android needs your OK before Xreader can install " +
                    "updates. Tap Open settings, turn on “Allow from " +
                    "this source” for Xreader, then come back and " +
                    "tap Install.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = modifier
            )
    }
}

/** The one primary action, labelled by where the flow is:
 * Update now → (downloading) → Install; a failure retries;
 * the permission detour routes to Settings. */
@Composable
fun UpdatePrimaryButton(
    phase: AppUpdateViewModel.DownloadPhase,
    onUpdateNow: () -> Unit,
    onInstall: () -> Unit,
    onOpenInstallSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    when (phase) {
        AppUpdateViewModel.DownloadPhase.None ->
            XreaderPrimaryButton("Update now", onUpdateNow, modifier)
        is AppUpdateViewModel.DownloadPhase.Running ->
            XreaderPrimaryButton("Downloading…", {}, modifier, enabled = false)
        AppUpdateViewModel.DownloadPhase.Verifying ->
            XreaderPrimaryButton("Checking…", {}, modifier, enabled = false)
        is AppUpdateViewModel.DownloadPhase.Ready ->
            XreaderPrimaryButton("Install", onInstall, modifier)
        is AppUpdateViewModel.DownloadPhase.Failed ->
            XreaderPrimaryButton("Try again", onUpdateNow, modifier)
        AppUpdateViewModel.DownloadPhase.NeedsInstallPermission ->
            XreaderPrimaryButton("Open settings", onOpenInstallSettings, modifier)
    }
}

/** `57,915,742` bytes → "55 MB" — a person's unit, not a
 * byte count. */
fun formatDownloadSize(bytes: Long): String = when {
    bytes <= 0 -> ""
    bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1_000_000_000.0)
    bytes >= 1_000_000 -> "${(bytes / 1_000_000.0).roundToInt()} MB"
    bytes >= 1_000 -> "${(bytes / 1_000.0).roundToInt()} KB"
    else -> "$bytes B"
}

private fun Double.roundToInt(): Int = kotlin.math.round(this).toInt()
