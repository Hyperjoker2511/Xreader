package app.xreader.ui.update

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.xreader.ui.theme.LocalDockClearance
import app.xreader.ui.theme.LocalUiMetrics
import app.xreader.ui.theme.bottomContentPadding
import app.xreader.ui.theme.effectiveBottomPadding
import app.xreader.data.update.NoteLine
import app.xreader.data.update.ReleaseNotesFormat
import app.xreader.ui.common.GhostPill
import app.xreader.ui.common.GlassCard
import app.xreader.ui.common.SectionHeader

/**
 * What's New (v1.3.5): the FULL release notes, read inside
 * the app — the floating card's "Read more" lands here.
 * The notes render from the release body as markdown-lite
 * (headings, bullets, paragraphs); no WebView, no browser.
 * The GitHub link at the bottom is the manual path: the
 * release page, for anyone who'd rather download there.
 */
@Composable
fun WhatsNewScreen(
    state: AppUpdateViewModel.UiState,
    onBack: () -> Unit,
    onUpdateNow: () -> Unit,
    onInstall: () -> Unit,
    onOpenInstallSettings: () -> Unit
) {
    // Code 121 (R39): under Material 3 the What's-New PAGE is
    // release notes as a DOCUMENT — version headline, then
    // per-feature headline + short paragraph sections, never
    // a plain list and never glass cards. It wears the ACTIVE
    // design, as the page always has (only the reveal wears
    // the new design). The Nocturne form below is untouched.
    if (app.xreader.ui.theme.isMaterial3Design()) {
        WhatsNewScreenM3(
            state = state,
            onBack = onBack,
            onUpdateNow = onUpdateNow,
            onInstall = onInstall,
            onOpenInstallSettings = onOpenInstallSettings
        )
        return
    }
    val context = LocalContext.current
    val release = state.release ?: state.cardRelease
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                if (release != null) "What's new in ${release.version}" else "What's new",
                style = MaterialTheme.typography.headlineMedium
            )
        }

        if (release == null) {
            GlassCard(Modifier.fillMaxWidth()) {
                Text(
                    "No update information yet. You can check from " +
                        "Profile → Updates & about.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        } else {
            GlassCard(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionHeader(
                        title = "Xreader ${release.version}"
                    )
                    Text(
                        "You're on ${state.currentVersion}. The update " +
                            "downloads inside the app and is checked " +
                            "against the release's safety checksum " +
                            "before anything is installed.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    UpdateProgressBlock(state.download)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        UpdatePrimaryButton(
                            phase = state.download,
                            onUpdateNow = onUpdateNow,
                            onInstall = onInstall,
                            onOpenInstallSettings = onOpenInstallSettings
                        )
                        GhostPill(
                            text = "GitHub page",
                            enabled = release.htmlUrl.isNotBlank(),
                            onClick = {
                                runCatching {
                                    context.startActivity(
                                        Intent(
                                            Intent.ACTION_VIEW,
                                            Uri.parse(release.htmlUrl)
                                        )
                                    )
                                }
                            }
                        )
                    }
                }
            }

            GlassCard(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    ReleaseNotesFormat.lines(release.body).forEach { line ->
                        when (line) {
                            is NoteLine.Heading -> Text(
                                line.text,
                                style = if (line.level <= 1) {
                                    MaterialTheme.typography.titleLarge
                                } else {
                                    MaterialTheme.typography.titleMedium
                                },
                                modifier = Modifier.padding(top = 6.dp)
                            )
                            is NoteLine.Bullet -> Text(
                                "• ${line.text}",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            is NoteLine.Paragraph -> Text(
                                line.text,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            NoteLine.Gap -> Spacer(Modifier.height(6.dp))
                        }
                    }
                }
            }
        }
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

/** R39's What's-New page under Material 3: a document. The
 * release body's headings become section headlines, their
 * paragraphs and bullets become the section's body — a
 * headed document, never a flat list. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun WhatsNewScreenM3(
    state: AppUpdateViewModel.UiState,
    onBack: () -> Unit,
    onUpdateNow: () -> Unit,
    onInstall: () -> Unit,
    onOpenInstallSettings: () -> Unit
) {
    val context = LocalContext.current
    val release = state.release ?: state.cardRelease
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        androidx.compose.material3.TopAppBar(
            title = {
                Text(
                    if (release != null) {
                        "What's new in ${release.version}"
                    } else {
                        "What's new"
                    }
                )
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back"
                    )
                }
            }
        )
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (release == null) {
                Text(
                    "No update information yet. You can check from " +
                        "Profile → Updates & about.",
                    style = MaterialTheme.typography.bodyLarge
                )
            } else {
                Text(
                    "Xreader ${release.version}",
                    style = MaterialTheme.typography
                        .headlineMediumEmphasized
                )
                Text(
                    "You're on ${state.currentVersion}. The update " +
                        "downloads inside the app and is checked " +
                        "against the release's safety checksum " +
                        "before anything is installed.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                UpdateProgressBlock(state.download)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    UpdatePrimaryButton(
                        phase = state.download,
                        onUpdateNow = onUpdateNow,
                        onInstall = onInstall,
                        onOpenInstallSettings = onOpenInstallSettings
                    )
                    androidx.compose.material3.OutlinedButton(
                        enabled = release.htmlUrl.isNotBlank(),
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(
                                        Intent.ACTION_VIEW,
                                        Uri.parse(release.htmlUrl)
                                    )
                                )
                            }
                        }
                    ) { Text("GitHub page") }
                }
                androidx.compose.material3.HorizontalDivider(
                    Modifier.padding(vertical = 4.dp)
                )
                ReleaseNotesFormat.lines(release.body).forEach { line ->
                    when (line) {
                        is NoteLine.Heading -> Text(
                            line.text,
                            style = if (line.level <= 1) {
                                MaterialTheme.typography
                                    .headlineSmallEmphasized
                            } else {
                                MaterialTheme.typography
                                    .titleLargeEmphasized
                            },
                            modifier = Modifier.padding(top = 10.dp)
                        )
                        is NoteLine.Bullet -> Text(
                            line.text,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(start = 12.dp)
                        )
                        is NoteLine.Paragraph -> Text(
                            line.text,
                            style = MaterialTheme.typography.bodyLarge
                        )
                        NoteLine.Gap -> Spacer(Modifier.height(4.dp))
                    }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}
