package app.xreader.ui.more

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.FindReplace
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.xreader.ui.common.GlassCard
import app.xreader.ui.common.ScreenHeader

/**
 * Me hub (PLAN §3; v1.1.0 §5.15 Nocturne pass): identity mini-card,
 * the What's-new and heal-note banners, then the uniform door list.
 *
 * v1.1.0 (§7): the Appearance row is REMOVED — AppearanceScreen is
 * dead code whose route already redirects to Customise, so the row
 * was a duplicate door. [onAppearance] stays in the signature only
 * until the shell drops its argument; nothing on this screen calls
 * it any more.
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
    /** P0 (Reader Terms): the global terms list's door. */
    onReaderTerms: () -> Unit = {},
    /** v1.3.5: the "Updates & about" section (check, switch,
     * links, app details) — its own door, nothing scattered. */
    onUpdatesAbout: () -> Unit = {},
    /** v1.0.1 "Me" header: the reader's own name/level/streak. */
    meName: String = "",
    meLevel: Int = 1,
    meTitle: String = "",
    meStreak: Int = 0,
    /** v1.0.2 (R28): one dismissible What's New note per release. */
    whatsNewVisible: Boolean = false,
    onOpenWhatsNew: () -> Unit = {},
    onDismissWhatsNew: () -> Unit = {},
    /** v1.0.5 (E1): the heal sweep's one calm report line, shown
     * once after it fixed something, then dismissed for good. */
    healNote: String? = null,
    onDismissHealNote: () -> Unit = {}
) {
    // §7: the Appearance door is gone from this list. The callback
    // is retained for call-site compatibility only (referenced so
    // the parameter stays part of the contract until the shell
    // drops its argument).
    onAppearance
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            // v1.0.2: the Me list scrolls with breathing room at
            // the fold — v1.0.1's half-cut bottom card is gone.
            // v1.1.0: bottom padding totals 88dp (dock + the
            // phone's dead bottom strip clearance).
            .verticalScroll(androidx.compose.foundation.rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp)
            .padding(bottom = 72.dp),
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
        if (healNote != null) {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "Good news about your sources",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        "While checking your sources with the improved " +
                            "test read, Xreader quietly repaired what it " +
                            "could: $healNote.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    androidx.compose.material3.TextButton(
                        onClick = onDismissHealNote
                    ) { Text("Dismiss") }
                }
            }
        }
        MeDoor(
            icon = Icons.Filled.Palette,
            title = "Customise",
            subtitle = "Colours, glass, library layout, navigation, reader look, downloads",
            onClick = onCustomise
        )
        MeDoor(
            icon = Icons.AutoMirrored.Filled.MenuBook,
            title = "Words I'm learning",
            subtitle = "Words you saved while reading, with spaced review",
            onClick = onVocab
        )
        MeDoor(
            icon = Icons.Filled.FindReplace,
            title = "Reader terms",
            subtitle = "Fix names and words in every book",
            onClick = onReaderTerms
        )
        MeDoor(
            icon = Icons.Filled.History,
            title = "History",
            subtitle = "Everything you've been reading, in time order",
            onClick = onHistory
        )
        MeDoor(
            icon = Icons.Filled.VpnKey,
            title = "Providers & keys",
            subtitle = "Gemini key, model choice, AI features",
            onClick = onProviders
        )
        MeDoor(
            icon = Icons.Filled.Sync,
            title = "Sync & backup",
            subtitle = "Google Drive, WebDAV, or a backup file",
            onClick = onSync
        )
        MeDoor(
            icon = Icons.Filled.SwapHoriz,
            title = "Migration studio",
            subtitle = "Bring your library from Mihon and other readers",
            onClick = onMigration
        )
        MeDoor(
            icon = Icons.Filled.Science,
            title = "Labs",
            subtitle = "Experimental features — eye-tracking page turns",
            onClick = onLabs
        )
        MeDoor(
            icon = Icons.Filled.SystemUpdate,
            title = "Updates & about",
            subtitle = "Check for updates, version, links and app details",
            onClick = onUpdatesAbout
        )
        MeDoor(
            icon = Icons.Filled.Info,
            title = "About & licences",
            subtitle = "Version and open-source licences",
            onClick = onAbout
        )
    }
}

/**
 * One Me-hub door (P0 polish, 2026-11-01): EVERY door is the
 * same compact row — one fixed height, the same padding, the
 * same 40dp icon tile, title and subtitle each on a single
 * line inside the row, chevron at the end. The old shape let
 * each card size itself to its subtitle, so the list landed
 * as uneven, oversized cards (the user's "asymmetric, very
 * big" note); now the hub reads as one neat uniform column.
 * The card stays the one tap target.
 */
@Composable
private fun MeDoor(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    GlassCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        bouncePress = true,
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
                        androidx.compose.foundation.shape
                            .RoundedCornerShape(16.dp)
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
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
