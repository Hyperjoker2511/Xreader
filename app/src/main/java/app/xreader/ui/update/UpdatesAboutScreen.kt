package app.xreader.ui.update

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.xreader.data.update.AppUpdateRepository
import app.xreader.ui.common.GlassCard
import app.xreader.ui.common.SectionHeader

/**
 * Settings → "Updates & about" (user, 2026-11-01 18:36):
 * ONE separate section for everything update-shaped —
 * the manual check with the current version, the
 * auto-check switch, our links (GitHub page, Releases)
 * and the app details (version, licence, privacy).
 * Nothing update-related lives anywhere else.
 *
 * Rows keep the Me hub's uniform shape exactly (P0
 * polish): one fixed height, the same padding, the same
 * 40dp icon tile, single-line title and subtitle.
 */
@Composable
fun UpdatesAboutScreen(
    state: AppUpdateViewModel.UiState,
    onBack: () -> Unit,
    onCheckNow: () -> Unit,
    onAutoCheckChange: (Boolean) -> Unit,
    onOpenWhatsNew: () -> Unit,
    onOpenAbout: () -> Unit
) {
    val context = LocalContext.current
    fun openUrl(url: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text("Updates & about", style = MaterialTheme.typography.headlineMedium)
        }

        SectionHeader(title = "Updates")
        UpdateRow(
            icon = Icons.Filled.Refresh,
            title = "Check for updates",
            subtitle = if (state.checking) {
                "Checking…"
            } else {
                "${state.statusLine} You're on ${state.currentVersion}."
            },
            onClick = if (state.checking) null else onCheckNow,
            trailing = {
                if (state.checking) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Chevron()
                }
            }
        )
        if (state.release != null) {
            UpdateRow(
                icon = Icons.Filled.NewReleases,
                title = "What's new in ${state.release.version}",
                subtitle = "Read the release notes and update",
                onClick = onOpenWhatsNew,
                trailing = { Chevron() }
            )
        }
        UpdateRow(
            icon = Icons.Filled.SystemUpdate,
            title = "Check automatically",
            subtitle = "Once a day, when you open the app",
            onClick = { onAutoCheckChange(!state.autoCheckEnabled) },
            trailing = {
                Switch(
                    checked = state.autoCheckEnabled,
                    onCheckedChange = onAutoCheckChange
                )
            }
        )

        SectionHeader(title = "Links")
        UpdateRow(
            icon = Icons.Filled.Code,
            title = "GitHub page",
            subtitle = "Xreader's home — source and releases",
            onClick = { openUrl(AppUpdateRepository.REPO_URL) },
            trailing = { Chevron() }
        )
        UpdateRow(
            icon = Icons.Filled.NewReleases,
            title = "Releases",
            subtitle = "Every version, to download by hand",
            onClick = { openUrl(AppUpdateRepository.RELEASES_URL) },
            trailing = { Chevron() }
        )

        SectionHeader(title = "About this app")
        UpdateRow(
            icon = Icons.Filled.Info,
            title = "Version",
            subtitle = "${state.currentVersion} (${state.currentVersionCode})",
            onClick = null,
            trailing = {}
        )
        UpdateRow(
            icon = Icons.Filled.Description,
            title = "Licence",
            subtitle = "MIT Licence — and the libraries inside",
            onClick = onOpenAbout,
            trailing = { Chevron() }
        )
        UpdateRow(
            icon = Icons.Filled.PrivacyTip,
            title = "Privacy",
            subtitle = "What Xreader keeps on your device, in plain words",
            onClick = { openUrl(AppUpdateRepository.PRIVACY_URL) },
            trailing = { Chevron() }
        )
        Spacer(Modifier.height(88.dp))
    }
}

@Composable
private fun Chevron() {
    Icon(
        Icons.Filled.ChevronRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(20.dp)
    )
}

/**
 * One Updates & about row — the Me hub door's exact
 * metrics (48dp content height, 16/12 padding, 40dp
 * tile, single-line texts), so Settings reads as one
 * neat uniform column like the hub does.
 */
@Composable
private fun UpdateRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: (() -> Unit)?,
    trailing: @Composable () -> Unit
) {
    GlassCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        bouncePress = onClick != null,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().height(48.dp),
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
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            trailing()
        }
    }
}
