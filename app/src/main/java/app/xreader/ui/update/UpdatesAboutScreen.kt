package app.xreader.ui.update

import android.content.Intent
import android.net.Uri
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
import app.xreader.ui.theme.LocalDockClearance
import app.xreader.ui.theme.LocalUiMetrics
import app.xreader.ui.theme.bottomContentPadding
import app.xreader.ui.theme.effectiveBottomPadding
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
    onOpenAbout: () -> Unit,
    onUpdateNow: () -> Unit,
    onInstall: () -> Unit,
    onOpenInstallSettings: () -> Unit
) {
    // Code 121 (R38): under Material 3 this page is a list of
    // M3 rows — Check for updates (with the last-checked line),
    // the Auto-check switch, the update state (S36's card lives
    // here too when present), and doors to What's New and
    // About. The Nocturne form below is untouched.
    if (app.xreader.ui.theme.isM3FamilyDesign()) {
        UpdatesAboutScreenM3(
            state = state,
            onBack = onBack,
            onCheckNow = onCheckNow,
            onAutoCheckChange = onAutoCheckChange,
            onOpenWhatsNew = onOpenWhatsNew,
            onOpenAbout = onOpenAbout,
            onUpdateNow = onUpdateNow,
            onInstall = onInstall,
            onOpenInstallSettings = onOpenInstallSettings
        )
        return
    }
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
        // v1.5.42: the direct action lives HERE too —
        // Update / Install per phase, on the same
        // manager state as the card, not only a link
        // onward to the What's New screen.
        // v1.7 Patch 1 (item 4): "Update now" is ALWAYS
        // here, not only once a release is known — with
        // none known yet it runs the same check the
        // auto-checker runs and downloads what the check
        // finds, so one tap is the whole journey. The
        // progress block renders nothing while idle.
        UpdateProgressBlock(state.download)
        UpdatePrimaryButton(
            phase = state.download,
            onUpdateNow = onUpdateNow,
            onInstall = onInstall,
            onOpenInstallSettings = onOpenInstallSettings,
            modifier = Modifier.fillMaxWidth()
        )
        UpdateRow(
            icon = Icons.Filled.SystemUpdate,
            title = "Check automatically",
            subtitle = "Once a day, when you open the app",
            onClick = { onAutoCheckChange(!state.autoCheckEnabled) },
            trailing = {
                // Code 127 (audit F6): the kit switch on the
                // Nocturne form (the M3 form's stock Switch
                // further down is that face's own dress).
                app.xreader.ui.common.NocturneSwitch(
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
        Spacer(
            Modifier.height(
                effectiveBottomPadding(
                    bottomContentPadding(LocalUiMetrics.current, dockVisible = false),
                    LocalDockClearance.current
                )
            )
        )
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

/**
 * R38 under Material 3, rebuilt Patch B (B-6, B4b): a
 * designed Material 3 settings page — a [androidx.compose.material3.Scaffold]
 * with a stock TopAppBar, a filled status card in the
 * scheme's surfaceContainer carrying the version state, the
 * phase's primary action, and grouped [androidx.compose.material3.ListItem]
 * rows (leading icons tinted primary, chevron trailing)
 * under small section titles, divided by hairlines. No
 * glass, no Nocturne atoms.
 *
 * Patch B gaps closed here: 65 — the "Update now" primary
 * button is ALWAYS on the page, exactly as on the Nocturne
 * form (with no release known it runs the check first and
 * downloads what the check finds — AppUpdateViewModel's
 * updateNow); the old M3 branch rendered it only once a
 * release was already known. 66 — the Links group carries
 * "Releases — every version", the door the Nocturne form
 * has always had and this branch lacked.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun UpdatesAboutScreenM3(
    state: AppUpdateViewModel.UiState,
    onBack: () -> Unit,
    onCheckNow: () -> Unit,
    onAutoCheckChange: (Boolean) -> Unit,
    onOpenWhatsNew: () -> Unit,
    onOpenAbout: () -> Unit,
    onUpdateNow: () -> Unit,
    onInstall: () -> Unit,
    onOpenInstallSettings: () -> Unit
) {
    val context = LocalContext.current
    fun openUrl(url: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }
    val scheme = MaterialTheme.colorScheme
    androidx.compose.material3.Scaffold(
        topBar = {
            androidx.compose.material3.TopAppBar(
                title = { Text("Updates & about") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // ── Status card: where this install stands ─────
            androidx.compose.material3.Card(
                shape = MaterialTheme.shapes.large,
                colors = androidx.compose.material3.CardDefaults
                    .cardColors(
                        containerColor = scheme.surfaceContainer
                    ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 4.dp)
            ) {
                Row(
                    Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        Modifier
                            .size(44.dp)
                            .background(
                                scheme.primaryContainer,
                                RoundedCornerShape(50)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.SystemUpdate,
                            contentDescription = null,
                            tint = scheme.onPrimaryContainer,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Column(
                        Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            if (state.release != null) {
                                "Version ${state.release.version} is ready"
                            } else {
                                "You're on ${state.currentVersion}"
                            },
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            if (state.checking) {
                                "Checking…"
                            } else if (state.release != null) {
                                "${state.statusLine} You're on " +
                                    "${state.currentVersion}."
                            } else {
                                state.statusLine
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = scheme.onSurfaceVariant
                        )
                    }
                    if (state.checking) {
                        CircularProgressIndicator(
                            Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                    }
                }
            }

            // ── The journey: progress + the always-present
            // primary action (gap 65). The progress block
            // renders nothing while idle. ──────────────────
            UpdateProgressBlock(
                state.download,
                modifier = Modifier.padding(top = 4.dp)
            )
            UpdatePrimaryButton(
                phase = state.download,
                onUpdateNow = onUpdateNow,
                onInstall = onInstall,
                onOpenInstallSettings = onOpenInstallSettings,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp, bottom = 4.dp)
            )

            M3UpdateSectionTitle("Updates")
            M3UpdateRow(
                icon = Icons.Filled.Refresh,
                title = "Check for updates",
                subtitle = if (state.checking) {
                    "Checking…"
                } else {
                    "Ask GitHub whether a newer version is out"
                },
                onClick = if (state.checking) null else onCheckNow,
                trailing = {
                    if (state.checking) {
                        CircularProgressIndicator(
                            Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Chevron()
                    }
                }
            )
            M3UpdateRow(
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
            if (state.release != null) {
                M3UpdateRow(
                    icon = Icons.Filled.NewReleases,
                    title = "What's new in ${state.release.version}",
                    subtitle = "Read the release notes and update",
                    onClick = onOpenWhatsNew,
                    trailing = { Chevron() }
                )
            }

            androidx.compose.material3.HorizontalDivider(
                Modifier.padding(vertical = 8.dp)
            )
            M3UpdateSectionTitle("Links")
            M3UpdateRow(
                icon = Icons.Filled.Code,
                title = "GitHub page",
                subtitle = "Xreader's home — source and releases",
                onClick = { openUrl(AppUpdateRepository.REPO_URL) },
                trailing = { Chevron() }
            )
            // Gap 66: the Releases door, Nocturne's wording.
            M3UpdateRow(
                icon = Icons.Filled.NewReleases,
                title = "Releases",
                subtitle = "Every version, to download by hand",
                onClick = { openUrl(AppUpdateRepository.RELEASES_URL) },
                trailing = { Chevron() }
            )
            M3UpdateRow(
                icon = Icons.Filled.PrivacyTip,
                title = "Privacy",
                subtitle = "What Xreader keeps on your device, in plain words",
                onClick = { openUrl(AppUpdateRepository.PRIVACY_URL) },
                trailing = { Chevron() }
            )

            androidx.compose.material3.HorizontalDivider(
                Modifier.padding(vertical = 8.dp)
            )
            M3UpdateSectionTitle("About this app")
            M3UpdateRow(
                icon = Icons.Filled.Info,
                title = "Version",
                subtitle = "${state.currentVersion} (${state.currentVersionCode})",
                onClick = null,
                trailing = {}
            )
            M3UpdateRow(
                icon = Icons.Filled.Description,
                title = "Licence",
                subtitle = "MIT Licence — and the libraries inside",
                onClick = onOpenAbout,
                trailing = { Chevron() }
            )
            M3UpdateRow(
                icon = Icons.Filled.NewReleases,
                title = "What's new",
                subtitle = state.release?.let {
                    "What's new in ${it.version}"
                } ?: "Release notes for this version",
                onClick = onOpenWhatsNew,
                trailing = { Chevron() }
            )
            Spacer(
                Modifier.height(
                    effectiveBottomPadding(32.dp, LocalDockClearance.current)
                )
            )
        }
    }
}

/** Patch B (B-6): one section title on the M3 Updates &
 * about page — the M3 settings idiom (small, primary,
 * title case set in the scheme's own type). */
@Composable
private fun M3UpdateSectionTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 2.dp)
    )
}

/** Patch B (B-6): one row on the M3 Updates & about page —
 * a stock M3 ListItem: primary-tinted leading icon, one
 * headline, one supporting line, the caller's trailing
 * piece; the whole row is the tap target when it has one. */
@Composable
private fun M3UpdateRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: (() -> Unit)?,
    trailing: @Composable () -> Unit
) {
    androidx.compose.material3.ListItem(
        headlineContent = {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        leadingContent = {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        trailingContent = trailing,
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) {
                    Modifier.clickable(onClick = onClick)
                } else {
                    Modifier
                }
            )
    )
}
