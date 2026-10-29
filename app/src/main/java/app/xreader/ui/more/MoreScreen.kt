package app.xreader.ui.more

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.xreader.ui.common.GlassCard
import app.xreader.ui.common.ScreenHeader

/**
 * More (PLAN §3): the settings home in embryo. Phase 1 ships the two
 * destinations that exist — Appearance and About & licences; Providers &
 * keys, Sync & backup and Profile join this list in their own phases,
 * in this same glass-row language.
 */
@Composable
fun MoreScreen(
    onAppearance: () -> Unit,
    onLabs: () -> Unit,
    onAbout: () -> Unit,
    onProviders: () -> Unit = {},
    onSync: () -> Unit = {},
    onProfile: () -> Unit = {},
    onMigration: () -> Unit = {},
    onHistory: () -> Unit = {},
    onCustomise: () -> Unit = {},
    onVocab: () -> Unit = {},
    /** v1.0.1 "Me" header: the reader's own name/level/streak. */
    meName: String = "",
    meLevel: Int = 1,
    meTitle: String = "",
    meStreak: Int = 0,
    /** v1.0.2 (R28): one dismissible What's New note per release. */
    whatsNewVisible: Boolean = false,
    onOpenWhatsNew: () -> Unit = {},
    onDismissWhatsNew: () -> Unit = {}
) {
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            // v1.0.2: the Me list scrolls with breathing room at
            // the fold — v1.0.1's half-cut bottom card is gone.
            .verticalScroll(androidx.compose.foundation.rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ScreenHeader(title = "Me", eyebrow = "Xreader")
        // v1.0.1: "Me" — the personal home base is the FIRST thing
        // on this screen, one tap, labelled. (Visibility law: who
        // uses it if it's not visible?)
        GlassCard(
            onClick = onProfile,
            modifier = Modifier.fillMaxWidth(),
            bouncePress = true
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Box(
                    Modifier
                        .size(52.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        meName.trim().firstOrNull()?.uppercase() ?: "M",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        if (meName.isBlank()) "Me" else meName,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        "Level $meLevel · $meTitle" +
                            if (meStreak > 0) " · $meStreak-day streak" else "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (whatsNewVisible) {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "What's new in this version",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        "Shelves and tags, a source health checker with " +
                            "repairs, “Bring my library”, an endless reader, " +
                            "and this new Me card. The full list lives in " +
                            "About.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        androidx.compose.material3.TextButton(
                            onClick = onOpenWhatsNew
                        ) { Text("See what's new") }
                        androidx.compose.material3.TextButton(
                            onClick = onDismissWhatsNew
                        ) { Text("Dismiss") }
                    }
                }
            }
        }
        MoreRow(
            title = "Customise",
            subtitle = "Colours, glass, library layout, navigation, reader look, downloads",
            onClick = onCustomise
        )
        MoreRow(
            title = "Words I'm learning",
            subtitle = "Words you saved while reading, with spaced review",
            onClick = onVocab
        )
        MoreRow(
            title = "History",
            subtitle = "Everything you've been reading, in time order",
            onClick = onHistory
        )
        MoreRow(
            title = "Providers & keys",
            subtitle = "Gemini key, model choice, AI features",
            onClick = onProviders
        )
        MoreRow(
            title = "Sync & backup",
            subtitle = "Google Drive, WebDAV, or a backup file",
            onClick = onSync
        )
        MoreRow(
            title = "Migration studio",
            subtitle = "Bring your library from Mihon and other readers",
            onClick = onMigration
        )
        MoreRow(
            title = "Appearance",
            subtitle = "Theme, glass, motion",
            onClick = onAppearance
        )
        MoreRow(
            title = "Labs",
            subtitle = "Experimental features — eye-tracking page turns",
            onClick = onLabs
        )
        MoreRow(
            title = "About & licences",
            subtitle = "Version and open-source licences",
            onClick = onAbout
        )
    }
}

@Composable
private fun MoreRow(title: String, subtitle: String, onClick: () -> Unit) {
    // v1.0.4 (D12): destination rows bounce like the mock's glass
    // settings rows (GlassCard carries the press plumbing).
    GlassCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        bouncePress = true
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
