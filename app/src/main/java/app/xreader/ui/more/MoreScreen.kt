package app.xreader.ui.more

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
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
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.clickable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.xreader.ui.common.GlassCard
import app.xreader.ui.common.ScreenHeader
import app.xreader.ui.theme.LocalDockClearance
import app.xreader.ui.theme.LocalUiMetrics
import app.xreader.ui.theme.UiTier
import app.xreader.ui.theme.effectiveBottomPadding

/**
 * Me hub (PLAN §3; v1.1.0 §5.15 Nocturne pass): identity mini-card,
 * the What's-new and heal-note banners, then the ten doors.
 * Patch 7: the doors render as an Orion-style card dashboard
 * (see MeDoorCard) instead of the old one-line rows.
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
    onDismissHealNote: () -> Unit = {},
    /** Patch 10: the Generated art gallery door, carrying the
     * LIVE count of painted pictures (fed from the lore-art
     * table at the Me call site). */
    onGeneratedArt: () -> Unit = {},
    artCount: Int = 0
) {
    // §7: the Appearance door is gone from this list. The callback
    // is retained for call-site compatibility only (referenced so
    // the parameter stays part of the contract until the shell
    // drops its argument).
    onAppearance
    // Patch 7: the dashboard's tier read — COMPACT lays the door
    // cards in a single column, and the root top gap is the
    // tier's standard topGap (Part C). v1.6.2: read through
    // LocalUiMetrics (the theme's provided row) rather than
    // the raw tier function, so the user's App size scales
    // this screen like every other — the tier itself rides
    // along untouched in the scaled row.
    val metrics = LocalUiMetrics.current
    // Code 120 completion (§2.11 Profile + M1): the M3 knobs
    // for this hub — the header-card form and the doors
    // list/grid. Under Nocturne neither is read: the identity
    // rides its GlassCard and the doors keep the shipped
    // Orion arrangement, whatever the knobs say.
    val designState = app.xreader.ui.theme.LocalDesignState.current
    val isM3Me = designState.design ==
        app.xreader.data.prefs.AppDesign.MATERIAL3
    // Code 121 (R5): under Material 3 the Me hub is the spec's
    // page — medium top app bar, identity header, doors as a
    // divider list, the heal note as a filled tonal card, and
    // the What's-New row only while an unseen note exists.
    // The note's seen-state is its OWN (the shell's
    // whatsNewSeen store): this screen only receives the
    // visibility boolean and the open/dismiss doors — it
    // neither reads nor writes the update reveal's record.
    // Nocturne renders the shipped hub below, untouched.
    if (isM3Me) {
        M3MeHub(
            meName = meName,
            meLevel = meLevel,
            meTitle = meTitle,
            meStreak = meStreak,
            whatsNewVisible = whatsNewVisible,
            onOpenWhatsNew = onOpenWhatsNew,
            healNote = healNote,
            onDismissHealNote = onDismissHealNote,
            artCount = artCount,
            onProfile = onProfile,
            onCustomise = onCustomise,
            onSync = onSync,
            onProviders = onProviders,
            onGeneratedArt = onGeneratedArt,
            onVocab = onVocab,
            onAbout = onAbout
        )
        return
    }
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            // v1.0.2: the Me list scrolls with breathing room at
            // the fold — v1.0.1's half-cut bottom card is gone.
            // v1.1.0: bottom padding totals 88dp (dock + the
            // phone's dead bottom strip clearance).
            // Visible-layer fix round (§A): the dock overlays
            // now, so the terminal padding repays the published
            // clearance when it exceeds the standing 72.dp.
            .verticalScroll(androidx.compose.foundation.rememberScrollState())
            .padding(
                start = 24.dp, end = 24.dp,
                top = metrics.topGap, bottom = 16.dp
            )
            .padding(
                bottom = effectiveBottomPadding(72.dp, LocalDockClearance.current)
            ),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ScreenHeader(title = "Me", eyebrow = "Xreader")
        // v1.0.1: "Me" — the personal home base is the FIRST thing
        // on this screen, one tap, labelled. (Visibility law: who
        // uses it if it's not visible?)
        // Code 120 completion: under Material 3 the header knob
        // picks the form — Card (this shipped GlassCard) or
        // Plain (the same identity row with no card around it).
        if (isM3Me && !designState.profileHeaderCard) {
            MeIdentityRow(
                meName = meName,
                meLevel = meLevel,
                meTitle = meTitle,
                meStreak = meStreak,
                onClick = onProfile,
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            GlassCard(
                onClick = onProfile,
                modifier = Modifier.fillMaxWidth(),
                bouncePress = true
            ) {
                MeIdentityRow(
                    meName = meName,
                    meLevel = meLevel,
                    meTitle = meTitle,
                    meStreak = meStreak,
                    onClick = null,
                    modifier = Modifier.fillMaxWidth()
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
                        "The Editorial Library, The Stage book page, " +
                            "settings rebuilt as a door hub, and sources " +
                            "restored and repaired. The full list lives " +
                            "in About.",
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
        // Patch 7 (Orion dashboard): the ten doors as a card
        // grid inside this same scrolling Column — chunked
        // rows, never a LazyVerticalGrid nested in the scroll.
        // Each row's cards share one height and every subtitle
        // gets two lines (see MeDoorCard). COMPACT width lays
        // the cards in a single column instead.
        val doors = listOf(
            MeDoorData(
                icon = Icons.Filled.Palette,
                title = "Customise",
                subtitle = "Colours, glass, library layout, navigation, reader look, downloads",
                onClick = onCustomise
            ),
            MeDoorData(
                icon = Icons.AutoMirrored.Filled.MenuBook,
                title = "Words I'm learning",
                subtitle = "Words you saved while reading, with spaced review",
                onClick = onVocab
            ),
            MeDoorData(
                icon = Icons.Filled.History,
                title = "History",
                subtitle = "Everything you've been reading, in time order",
                onClick = onHistory
            ),
            MeDoorData(
                icon = Icons.Filled.VpnKey,
                title = "Providers & keys",
                subtitle = "Gemini key, model choice, AI features",
                onClick = onProviders
            ),
            MeDoorData(
                icon = Icons.Filled.Image,
                title = "Generated art",
                subtitle = if (artCount == 1) "1 image" else "$artCount images",
                onClick = onGeneratedArt
            ),
            MeDoorData(
                icon = Icons.Filled.Sync,
                title = "Sync & backup",
                subtitle = "Google Drive, WebDAV, or a backup file",
                onClick = onSync
            ),
            MeDoorData(
                icon = Icons.Filled.SwapHoriz,
                title = "Migration studio",
                subtitle = "Bring your library from Mihon and other readers",
                onClick = onMigration
            ),
            MeDoorData(
                icon = Icons.Filled.Science,
                title = "Labs",
                subtitle = "Experimental features — eye-tracking page turns",
                onClick = onLabs
            ),
            MeDoorData(
                icon = Icons.Filled.SystemUpdate,
                title = "Updates & about",
                subtitle = "Check for updates, version, links and app details",
                onClick = onUpdatesAbout
            ),
            MeDoorData(
                icon = Icons.Filled.Info,
                title = "About & licences",
                subtitle = "Version and open-source licences",
                onClick = onAbout
            )
        )
        if (isM3Me && designState.profileDoorsList) {
            // Code 120 completion: the M3 doors form — full-
            // width list rows (the design's default). Grid
            // (the knob's other form) is the shipped Orion
            // arrangement below, which Nocturne always uses.
            doors.forEach { door ->
                MeDoorListRow(door, Modifier.fillMaxWidth())
            }
        } else if (metrics.tier == UiTier.COMPACT) {
            doors.forEach { door ->
                MeDoorCard(door, Modifier.fillMaxWidth())
            }
        } else {
            doors.chunked(2).forEach { rowDoors ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Max),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    rowDoors.forEach { door ->
                        MeDoorCard(
                            door,
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                        )
                    }
                }
            }
        }
    }
}

/** One Me-hub door's facts (Patch 7): the dashboard grid and
 * the compact single column render the same ten doors from
 * this one list — icon, label, subtitle, destination. */
private data class MeDoorData(
    val icon: ImageVector,
    val title: String,
    val subtitle: String,
    val onClick: () -> Unit
)

/**
 * One dashboard door card (Patch 7): a big tappable
 * [GlassCard] — the door's icon in its badge, the plain-word
 * label, and the subtitle given TWO lines. The old uniform
 * row squeezed the subtitle onto ONE line, so most doors'
 * words were cut mid-word ("naviga…", "in t…") — the defect
 * this grid fixes: minLines = 2 also keeps paired cards'
 * text blocks the same height, so each row reads level.
 * The card stays the one tap target, with the same bounce
 * press the hub's rows used.
 */
@Composable
private fun MeDoorCard(door: MeDoorData, modifier: Modifier = Modifier) {
    // v1.6.2: the provided (App-size-scaled) row, like the
    // dashboard root above — the door's min height is one of
    // the sizes the App size exists to scale.
    val metrics = LocalUiMetrics.current
    GlassCard(
        onClick = door.onClick,
        modifier = modifier.heightIn(min = metrics.cardMinHeight),
        bouncePress = true,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier
                    .size(42.dp)
                    .background(
                        MaterialTheme.colorScheme.surfaceContainerHigh,
                        androidx.compose.foundation.shape
                            .RoundedCornerShape(16.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    door.icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
            }
            Text(
                door.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                door.subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                minLines = 2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Code 120 completion: the Me hub's identity row — avatar
 * initial, name, level · title · streak, chevron — shared by
 * the header's Card form (inside the shipped GlassCard,
 * [onClick] null: the card is the tap target) and the M3
 * Plain form (the bare row, itself the tap target).
 */
@Composable
private fun MeIdentityRow(
    meName: String,
    meLevel: Int,
    meTitle: String,
    meStreak: Int,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    Row(
        modifier.then(
            if (onClick != null) {
                Modifier.clickable(onClick = onClick)
            } else {
                Modifier
            }
        ),
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

/**
 * Code 120 completion: one Me door as a full-width list row —
 * the icon in its badge, the plain-word title, the subtitle
 * given its two lines, and the forward chevron. The M3 doors
 * form (the design's default); the grid form is MeDoorCard.
 */
@Composable
private fun MeDoorListRow(door: MeDoorData, modifier: Modifier = Modifier) {
    GlassCard(
        onClick = door.onClick,
        modifier = modifier,
        bouncePress = true,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                Modifier
                    .size(42.dp)
                    .background(
                        MaterialTheme.colorScheme.surfaceContainerHigh,
                        androidx.compose.foundation.shape
                            .RoundedCornerShape(16.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    door.icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    door.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    door.subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
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

/**
 * Code 121 (R5): the Me hub in the Material 3 design — a
 * medium top app bar ("Profile"), the heal note as a filled
 * tonal card at the top (its own dismiss, its own state), the
 * identity header (shape-cropped avatar, emphasized name,
 * title/level line), the What's-New row ONLY while an unseen
 * note exists, and the doors as a divider list. No door grid,
 * no showpiece card.
 */
@OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)
@Composable
private fun M3MeHub(
    meName: String,
    meLevel: Int,
    meTitle: String,
    meStreak: Int,
    whatsNewVisible: Boolean,
    onOpenWhatsNew: () -> Unit,
    healNote: String?,
    onDismissHealNote: () -> Unit,
    artCount: Int,
    onProfile: () -> Unit,
    onCustomise: () -> Unit,
    onSync: () -> Unit,
    onProviders: () -> Unit,
    onGeneratedArt: () -> Unit,
    onVocab: () -> Unit,
    onAbout: () -> Unit
) {
    val avatarShape = androidx.compose.material3.MaterialShapes
        .Cookie9Sided.toShape()
    Column(Modifier.fillMaxSize()) {
        androidx.compose.material3.MediumTopAppBar(
            title = { Text("Profile") }
        )
        Column(
            Modifier
                .verticalScroll(
                    androidx.compose.foundation.rememberScrollState()
                )
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (healNote != null) {
                androidx.compose.material3.Card(
                    colors = androidx.compose.material3.CardDefaults
                        .cardColors(
                            containerColor = MaterialTheme
                                .colorScheme.secondaryContainer
                        ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            "Fixed what it could: $healNote",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme
                                .onSecondaryContainer
                        )
                        androidx.compose.material3.TextButton(
                            onClick = onDismissHealNote
                        ) { Text("Dismiss") }
                    }
                }
            }
            // Identity header.
            androidx.compose.material3.ListItem(
                headlineContent = {
                    Text(
                        if (meName.isBlank()) "Me" else meName,
                        style = MaterialTheme.typography
                            .headlineSmallEmphasized,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                supportingContent = {
                    Text(
                        "Level $meLevel · $meTitle" +
                            if (meStreak > 0) {
                                " · $meStreak-day streak"
                            } else {
                                ""
                            }
                    )
                },
                leadingContent = {
                    Box(
                        Modifier
                            .size(56.dp)
                            .clip(avatarShape)
                            .background(
                                MaterialTheme.colorScheme
                                    .primaryContainer
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            meName.trim().firstOrNull()
                                ?.uppercase() ?: "M",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme
                                .onPrimaryContainer
                        )
                    }
                },
                trailingContent = {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                colors = androidx.compose.material3.ListItemDefaults
                    .colors(
                        containerColor = androidx.compose.ui.graphics
                            .Color.Transparent
                    ),
                modifier = Modifier.clickable(onClick = onProfile)
            )
            if (whatsNewVisible) {
                // Code 122 (Settings V2, Option A language for
                // R5): the What's-New note is a filled card above
                // the doors card, like the heal note — its
                // seen-state rules are untouched.
                androidx.compose.material3.Card(
                    onClick = onOpenWhatsNew,
                    colors = androidx.compose.material3.CardDefaults
                        .cardColors(
                            containerColor = MaterialTheme
                                .colorScheme.surfaceContainerLow
                        ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    androidx.compose.material3.ListItem(
                        headlineContent = {
                            Text("What's new in this version")
                        },
                        supportingContent = {
                            Text("See what this update brought")
                        },
                        leadingContent = {
                            Icon(
                                Icons.Filled.SystemUpdate,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        },
                        trailingContent = {
                            androidx.compose.material3.Badge {
                                Text("New")
                            }
                        },
                        colors = androidx.compose.material3
                            .ListItemDefaults.colors(
                                containerColor = androidx.compose.ui
                                    .graphics.Color.Transparent
                            )
                    )
                }
            }
            androidx.compose.material3.HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant
            )
            val doors = listOf(
                MeDoorData(
                    icon = Icons.Filled.Palette,
                    title = "Customise",
                    subtitle = "Design, colours, reader and the rest",
                    onClick = onCustomise
                ),
                MeDoorData(
                    icon = Icons.Filled.Sync,
                    title = "Sync & backup",
                    subtitle = "Google Drive, WebDAV, or a backup file",
                    onClick = onSync
                ),
                MeDoorData(
                    icon = Icons.Filled.VpnKey,
                    title = "AI providers",
                    subtitle = "Gemini key, model choice, AI features",
                    onClick = onProviders
                ),
                MeDoorData(
                    icon = Icons.Filled.Image,
                    title = "Generated art",
                    subtitle = if (artCount == 1) {
                        "1 image"
                    } else {
                        "$artCount images"
                    },
                    onClick = onGeneratedArt
                ),
                MeDoorData(
                    icon = Icons.AutoMirrored.Filled.MenuBook,
                    title = "Vocabulary",
                    subtitle = "Words you saved while reading",
                    onClick = onVocab
                ),
                MeDoorData(
                    icon = Icons.Filled.Info,
                    title = "About",
                    subtitle = "Version and open-source licences",
                    onClick = onAbout
                )
            )
            // Code 122 (Settings V2, Option A language for R5):
            // the settings doors are ONE tonal card of
            // SegmentedListItems — tonal icon tile, name,
            // one-line description, chevron — instead of the
            // old flat divider list.
            androidx.compose.material3.Card(
                colors = androidx.compose.material3.CardDefaults
                    .cardColors(
                        containerColor = MaterialTheme
                            .colorScheme.surfaceContainerLow
                    ),
                shape = androidx.compose.foundation.shape
                    .RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    Modifier.padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    doors.forEachIndexed { index, door ->
                        androidx.compose.material3.SegmentedListItem(
                            onClick = door.onClick,
                            shapes = androidx.compose.material3
                                .ListItemDefaults.segmentedShapes(
                                    index = index,
                                    count = doors.size
                                ),
                            colors = androidx.compose.material3
                                .ListItemDefaults.colors(
                                    containerColor = MaterialTheme
                                        .colorScheme.surfaceContainer
                                ),
                            leadingContent = {
                                Box(
                                    Modifier
                                        .size(40.dp)
                                        .background(
                                            MaterialTheme.colorScheme
                                                .primaryContainer,
                                            androidx.compose.foundation
                                                .shape.RoundedCornerShape(
                                                    12.dp
                                                )
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        door.icon,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme
                                            .onPrimaryContainer,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            },
                            trailingContent = {
                                Icon(
                                    Icons.AutoMirrored.Filled
                                        .ArrowForward,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme
                                        .onSurfaceVariant
                                )
                            },
                            supportingContent = { Text(door.subtitle) }
                        ) {
                            Text(door.title)
                        }
                    }
                }
            }
        }
    }
}
