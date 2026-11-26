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
import app.xreader.data.update.UpdateDownloadPhase
import app.xreader.ui.common.XreaderPrimaryButton

/**
 * The update flow's shared pieces (v1.3.5): the card, the
 * What's New screen and the Settings section all present
 * the SAME download state the same way, driven by
 * the download's owner (UpdateDownloadManager, mirrored
 * into [AppUpdateViewModel]'s state).
 */

/** Download progress / failure / permission copy, in plain
 * words, sized for a card or a screen section alike. */
@Composable
fun UpdateProgressBlock(
    phase: UpdateDownloadPhase,
    modifier: Modifier = Modifier
) {
    when (phase) {
        UpdateDownloadPhase.None -> Unit
        is UpdateDownloadPhase.Running -> {
            Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (phase.fraction != null) {
                    app.xreader.ui.common.DesignLinearProgress(
                        progress = { phase.fraction },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "Downloading… ${(phase.fraction * 100).toInt()}%",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    app.xreader.ui.common.DesignLinearProgressIndeterminate(
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "Downloading…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        UpdateDownloadPhase.Verifying -> {
            // Patch B (B-6, B4b): in the Material 3 face the
            // verifying state carries the face's indeterminate
            // wavy indicator, like its downloading state;
            // Nocturne keeps the plain line it has always had.
            if (app.xreader.ui.theme.isM3FamilyDesign()) {
                Column(
                    modifier,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    app.xreader.ui.common
                        .DesignLinearProgressIndeterminate(
                            modifier = Modifier.fillMaxWidth()
                        )
                    Text(
                        "Checking the downloaded file…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Text(
                    "Checking the downloaded file…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = modifier
                )
            }
        }
        is UpdateDownloadPhase.Ready ->
            Text(
                "Downloaded and checked — ready to install.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = modifier
            )
        is UpdateDownloadPhase.Failed ->
            Text(
                phase.text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = modifier
            )
        UpdateDownloadPhase.NeedsInstallPermission ->
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
    phase: UpdateDownloadPhase,
    onUpdateNow: () -> Unit,
    onInstall: () -> Unit,
    onOpenInstallSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Code 127 (audit B4): the button wears the active face —
    // Nocturne's pill in Nocturne; in the Material 3 face a
    // stock filled Button, the form the face's own update
    // surfaces (RecoveryScreenM3, WhatsNewScreenM3) use for
    // their primary actions. Until now every M3 update
    // surface calling this shared button rendered the
    // Nocturne pill — in UpdateCardM3, inside a ButtonGroup
    // beside a stock FilledTonalButton.
    if (app.xreader.ui.theme.isEditorialDesign()) {
        val (label, action, enabled) = when (phase) {
            UpdateDownloadPhase.None ->
                Triple("Update now", onUpdateNow, true)
            is UpdateDownloadPhase.Running ->
                Triple("Downloading…", {}, false)
            UpdateDownloadPhase.Verifying ->
                Triple("Checking…", {}, false)
            is UpdateDownloadPhase.Ready ->
                Triple("Install", onInstall, true)
            is UpdateDownloadPhase.Failed ->
                Triple("Try again", onUpdateNow, true)
            UpdateDownloadPhase.NeedsInstallPermission ->
                Triple("Open settings", onOpenInstallSettings, true)
        }
        androidx.compose.material3.Button(
            onClick = action,
            modifier = modifier,
            enabled = enabled
        ) {
            Text(label, maxLines = 1, softWrap = false)
        }
        return
    }
    when (phase) {
        UpdateDownloadPhase.None ->
            XreaderPrimaryButton("Update now", onUpdateNow, modifier)
        is UpdateDownloadPhase.Running ->
            XreaderPrimaryButton("Downloading…", {}, modifier, enabled = false)
        UpdateDownloadPhase.Verifying ->
            XreaderPrimaryButton("Checking…", {}, modifier, enabled = false)
        is UpdateDownloadPhase.Ready ->
            XreaderPrimaryButton("Install", onInstall, modifier)
        is UpdateDownloadPhase.Failed ->
            XreaderPrimaryButton("Try again", onUpdateNow, modifier)
        UpdateDownloadPhase.NeedsInstallPermission ->
            XreaderPrimaryButton("Open settings", onOpenInstallSettings, modifier)
    }
}

/** `71,383,446` bytes → "71.4 MB" — a person's unit, not a
 * byte count, with one decimal (v1.5.42 card anatomy: the
 * size rides the "You're on …" line and must read exactly). */
fun formatDownloadSize(bytes: Long): String = when {
    bytes <= 0 -> ""
    bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1_000_000_000.0)
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000 -> "${(bytes / 1_000.0).roundToInt()} KB"
    else -> "$bytes B"
}

private fun Double.roundToInt(): Int = kotlin.math.round(this).toInt()
