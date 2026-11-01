package app.xreader.ui.sources

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryBooks
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.xreader.core.motion.listPlacementSpec
import app.xreader.core.motion.pressBounce
import app.xreader.data.db.InstalledExtension
import app.xreader.data.db.OpdsServer
import app.xreader.data.db.TrustState
import app.xreader.data.extension.HealthDot
import app.xreader.data.extension.SourceHealthRecord
import app.xreader.data.extension.healthDot
import app.xreader.ui.common.GlassCard
import app.xreader.ui.common.KitEmptyState
import app.xreader.ui.common.NocturneChip
import app.xreader.ui.common.PrimaryPill
import app.xreader.ui.common.QuickActionTile
import app.xreader.ui.common.ScreenHeader
import app.xreader.ui.common.SectionHeader
import app.xreader.ui.theme.primaryTextColor

/**
 * Browse (v1.0.5, C3/D23 — the neat rebuild): header, the global
 * search pill, ONE "Add a source" button, then your sources as
 * compact glass rows — extensions and OPDS library servers in
 * the same list, each with its kind labelled. Adding lives on
 * its own screen ([AddSourceScreen]) and repositories in the
 * Source store ([SourceStoreScreen]): nothing expands inside
 * this scroll any more. Per-source Health and Repair stay on
 * each source's Details page, as before.
 */
@Composable
fun SourcesScreen(
    viewModel: SourcesViewModel,
    onOpenSource: (extensionId: Long, sourceId: String) -> Unit,
    onOpenExtension: (extensionId: Long) -> Unit,
    onOpenOpdsServers: () -> Unit,
    /** v1.0.1 declutter: global search lives on its own screen. */
    onSearchAll: () -> Unit = {},
    /** v1.0.5 (C3): the Add-a-source screen and the Source store. */
    onAddSource: () -> Unit = {},
    onOpenStore: () -> Unit = {},
    /** v1.0.5 (C3/D23): OPDS servers fold into this list. */
    opdsServers: List<OpdsServer> = emptyList(),
    onOpenOpdsServer: (OpdsServer) -> Unit = {}
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        // v1.0.4 (D11): the header action bounces like the mock's.
        val checkUpdatesInteraction = remember { MutableInteractionSource() }
        ScreenHeader(
            title = "Browse",
            eyebrow = "Sources",
            action = {
                TextButton(
                    onClick = { viewModel.refreshAll() },
                    enabled = !state.busy,
                    interactionSource = checkUpdatesInteraction,
                    modifier = Modifier.pressBounce(
                        checkUpdatesInteraction, enabled = !state.busy
                    )
                ) { Text("Check for updates") }
            }
        )
        if (state.busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
        }
        Spacer(Modifier.height(12.dp))
        LazyColumn(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 88.dp)
        ) {
            // ── Search everything: the one-line glass pill ──────
            item {
                GlassCard(
                    onClick = onSearchAll,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 13.dp),
                    bouncePress = true
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            Icons.Filled.Search,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            "Search all your sources",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // ── ONE add door; the forms live on their own screen ─
            // §5.3: a full-width GlassCard door with a PrimaryPill,
            // visible — never demoted to a plain row.
            item {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    // v1.2.0 (WS4 declutter): the card used to say
                    // "Add a source" twice — a title and the pill
                    // under it. The pill IS the action and keeps
                    // the words; the card now opens with the one
                    // explanatory line, then the big visible door.
                    Text(
                        "A repository, a novel site's link, or a Legado file.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    PrimaryPill(
                        text = "Add a source",
                        onClick = onAddSource,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // ── Your sources, extensions and OPDS together ──────
            item {
                SectionHeader(
                    title = "Your sources",
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            if (state.extensions.isEmpty() && opdsServers.isEmpty()) {
                item {
                    KitEmptyState(
                        icon = Icons.Filled.LibraryBooks,
                        message = "No sources yet. Add a repository, paste a novel " +
                            "site's link, or add a library server — your sources " +
                            "live here.",
                        actionText = "Add a source",
                        onAction = onAddSource
                    )
                }
            }
            items(state.extensions, key = { "ext-${it.id}" }) { ext ->
                val firstSource = ext.sourceIds.split(",")
                    .firstOrNull()?.trim()?.takeIf { it.isNotBlank() }
                val record = firstSource?.let {
                    state.health[SourceHealthRecord.key(ext.id, it)]
                }
                SourceRow(
                    ext = ext,
                    healthRecord = record,
                    hasUpdate = state.updates[ext.packageId] != null,
                    hasPending = state.pendings.containsKey(ext.packageId),
                    addedByYou = ext.id in state.addedByYouIds,
                    onOpen = {
                        if (firstSource == null || ext.sourceIds.contains(",")) {
                            onOpenExtension(ext.id)
                        } else {
                            onOpenSource(ext.id, firstSource)
                        }
                    },
                    onDetails = { onOpenExtension(ext.id) },
                    onUpdate = {
                        state.updates[ext.packageId]?.let { viewModel.applyUpdate(it) }
                    },
                    modifier = Modifier.animateItem(placementSpec = listPlacementSpec())
                )
            }
            items(opdsServers, key = { "opds-${it.id}" }) { server ->
                OpdsServerRow(
                    server = server,
                    onOpen = { onOpenOpdsServer(server) },
                    modifier = Modifier.animateItem(placementSpec = listPlacementSpec())
                )
            }

            // ── The store and the server manager (§5.3 doors row) ─
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    QuickActionTile(
                        icon = Icons.Filled.Storefront,
                        label = "Source store",
                        modifier = Modifier.weight(1f),
                        active = false,
                        onClick = onOpenStore
                    )
                    QuickActionTile(
                        icon = Icons.Filled.LibraryBooks,
                        label = "OPDS servers",
                        modifier = Modifier.weight(1f),
                        active = false,
                        onClick = onOpenOpdsServers
                    )
                }
            }
        }
    }
}

/**
 * v1.0.5 (C3/E2): one installed source as a compact glass row —
 * letter tile, name, language · version · kind, the health dot
 * from its last honest verdict, and its one alert word when
 * something needs the reader (re-trust, update, off, broken).
 * The whole row opens the source; a source that is off or not
 * trusted opens its Details instead, where the fix lives.
 */
@Composable
private fun SourceRow(
    ext: InstalledExtension,
    healthRecord: SourceHealthRecord?,
    hasUpdate: Boolean,
    hasPending: Boolean,
    addedByYou: Boolean = false,
    onOpen: () -> Unit,
    onDetails: () -> Unit,
    onUpdate: () -> Unit,
    modifier: Modifier = Modifier
) {
    val canOpen = ext.enabled && ext.trustState == TrustState.TRUSTED
    // v1.0.6.2 (SO1/F8a): action alerts only (re-trust, update,
    // off) — the health verdict itself now rides the meta line
    // as a plain word, the same words the Details health card
    // uses, so the row stays one quiet line.
    val alert: Pair<String, Color>? = when {
        ext.trustState == TrustState.CHANGED_REQUIRES_RETRUST ->
            "Needs re-trust" to MaterialTheme.colorScheme.error
        hasPending -> "Update waiting" to MaterialTheme.colorScheme.error
        !ext.enabled -> "Off" to MaterialTheme.colorScheme.onSurfaceVariant
        else -> null
    }
    val typeLabel = if (ext.packageId.startsWith("link.")) "Link source" else null
    // §5.3 row anatomy (WS4-demoted): language, the health word
    // and the kind share ONE plain meta line; the health dot sits
    // at the row's end and an alert, when one exists, is the
    // row's only chip.
    val languageLabel = ext.languages.split(",").firstOrNull()?.uppercase()
        ?.takeIf { it.isNotBlank() }
    val healthWord = healthRecord?.shortStateWord() ?: "Not checked yet"
    GlassCard(
        modifier = modifier,
        onClick = if (canOpen) onOpen else onDetails,
        bouncePress = true,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SourceLetterTile(letter = ext.displayName.firstOrNull()?.uppercase() ?: "?")
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(
                    ext.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                // v1.2.0 (WS4 declutter): the language used to
                // ride in its own chip beside the meta line —
                // a pill's worth of visual weight for a two-letter
                // code on every row. It is plain meta text now,
                // first in the same quiet line as the health word
                // and kind; the row's only chip is an alert when
                // one exists (below). Nothing is dropped, just
                // demoted to the weight it deserves.
                Text(
                    listOfNotNull(
                        // v1.3.0 (device spot-check fix): a
                        // user-added twin of a built-in default
                        // says so in plain words at the head of
                        // the meta line — the two same-name rows
                        // are never confusable, and nothing was
                        // deleted to achieve it.
                        if (addedByYou) "Added by you" else null,
                        languageLabel, healthWord, typeLabel
                    )
                        .joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (alert != null) {
                    Spacer(Modifier.height(4.dp))
                    NocturneChip(
                        label = alert.first,
                        selected = false,
                        dotColor = alert.second
                    )
                }
            }
            HealthDotView(healthRecord.healthDot())
            if (hasUpdate) {
                TextButton(onClick = onUpdate) { Text("Update") }
            }
            TextButton(onClick = onDetails) { Text("Details") }
        }
    }
}

/** v1.0.5 (C3/D23): an OPDS library server in the same list —
 * same tile and rhythm, its kind labelled, no health dot (OPDS
 * servers carry no test-drive verdict). */
@Composable
private fun OpdsServerRow(
    server: OpdsServer,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier
) {
    GlassCard(
        modifier = modifier,
        onClick = onOpen,
        bouncePress = true,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SourceLetterTile(letter = server.name.firstOrNull()?.uppercase() ?: "?")
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(
                    server.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${server.baseUrl} · OPDS server",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun SourceLetterTile(letter: String) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
        modifier = Modifier.size(40.dp)
    ) {
        Column(
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                letter,
                style = MaterialTheme.typography.titleMedium,
                color = primaryTextColor()
            )
        }
    }
}

/** v1.0.5 (E2): the health dot — green working, amber needs
 * setup, red broken, grey never checked. The mapping itself is
 * the pure, unit-tested [healthDot]. */
@Composable
private fun HealthDotView(dot: HealthDot) {
    val color = when (dot) {
        HealthDot.GREEN -> Color(0xFF43A047)
        HealthDot.AMBER -> Color(0xFFFFB300)
        HealthDot.RED -> Color(0xFFE53935)
        HealthDot.GREY -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
    }
    Box(
        Modifier
            .padding(start = 10.dp)
            .size(10.dp)
            .clip(CircleShape)
            .background(color)
    )
}
