package app.xreader.ui.start

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.xreader.ui.common.PrimaryPill
import app.xreader.ui.common.RowItem
import app.xreader.ui.common.ScreenHeader

/**
 * The friendly recovery screen (PLAN §1.5): shown on the launch after a
 * crash. Plain words, the shareable note (timestamp + error class only —
 * never content or keys), and one way forward. Your library and progress
 * live in the database, so nothing is lost.
 *
 * v1.0.2 (R01): the root is a themed [Surface] — v1.0.1 rooted this
 * screen in a bare Column, so colour-less text fell back to
 * LocalContentColor's default (black) over the black window and the
 * headline/paragraph were invisible. The Surface paints the scheme
 * background and provides onBackground as the content colour for the
 * whole subtree. This screen never composes a Haze effect (R01
 * topology), so a persisted appearance choice cannot re-crash it.
 *
 * Nocturne reskin (§5.27, hard exception): this screen composes ZERO
 * glass machinery — no GlassCard, no frostedGlass, no Haze. The crash
 * note sits on a solid Tier-1 surface (surfaceContainerLow role,
 * 24dp corners); the restyle is tokens + type only, so the screen
 * still composes even if every glass setting is broken.
 *
 * [onSafeAppearance] is the crash-loop escape: it persistently
 * switches glass to Tinted BEFORE entering the app, so a bad
 * appearance state can never lock the user out.
 *
 * v1.5.42 (hotfix): the note card takes the column's flexible
 * space and the note text scrolls INSIDE the card, so the
 * actions below are always on screen. Before this, the column
 * never scrolled and a long crash note pushed Continue off
 * the bottom of small screens — and since the note is cleared
 * only from those actions, the screen returned on every
 * launch and locked the user out.
 */
@Composable
fun RecoveryScreen(
    note: String?,
    onContinue: () -> Unit,
    onSafeAppearance: (() -> Unit)? = null
) {
    // Code 121 (S39/T17): under Material 3, recovery is an M3
    // document page — headline, the crash note in a SCROLLING
    // filled card, and a PINNED button group (Continue filled /
    // Safe appearance tonal). The note can never push the doors
    // off-screen. Safe appearance here is design-scoped: the
    // shell's handler resets only M3's own settings and never
    // writes Nocturne's Tinted glass. The Nocturne form below
    // is untouched.
    if (app.xreader.ui.theme.isM3FamilyDesign()) {
        RecoveryScreenM3(
            note = note,
            onContinue = onContinue,
            onSafeAppearance = onSafeAppearance
        )
        return
    }
    Surface(
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            ScreenHeader(title = "Something went wrong", eyebrow = "Recovery")
            Text(
                "Xreader closed unexpectedly last time. Your books, progress " +
                    "and settings are safe — they're stored separately from " +
                    "the part that failed.",
                style = MaterialTheme.typography.bodyLarge
            )
            if (!note.isNullOrBlank()) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text("Crash note", style = MaterialTheme.typography.titleMedium)
                        Text(
                            note.trim(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .weight(1f)
                                .verticalScroll(rememberScrollState())
                        )
                        Text(
                            "Only the time and the kind of error — never your " +
                                "books or keys. You can share this note if you " +
                                "report the problem.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                Spacer(Modifier.weight(1f))
            }
            PrimaryPill(
                text = "Continue to your library",
                enabled = true,
                onClick = onContinue
            )
            if (onSafeAppearance != null) {
                RowItem(
                    icon = Icons.Filled.Shield,
                    label = "Open with safe appearance",
                    onClick = onSafeAppearance
                )
                Text(
                    "If the problem was the app's appearance (for example " +
                        "frosted glass), safe appearance switches to plain " +
                        "tinted surfaces before opening, so it can't happen " +
                        "again on the way in.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** S39 under Material 3: a document page. The crash note sits
 * in a filled card that takes the flexible space and scrolls
 * INSIDE itself; the button group is pinned beneath it, so a
 * long note can never push the doors off-screen (the settled
 * trap, designed out). No glass machinery is composed here
 * either — a broken appearance state cannot re-crash it. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun RecoveryScreenM3(
    note: String?,
    onContinue: () -> Unit,
    onSafeAppearance: (() -> Unit)?
) {
    androidx.compose.material3.Surface(
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "Something went wrong",
                style = MaterialTheme.typography.headlineMediumEmphasized
            )
            Text(
                "Xreader closed unexpectedly last time. Your books, " +
                    "progress and settings are safe — they're stored " +
                    "separately from the part that failed.",
                style = MaterialTheme.typography.bodyLarge
            )
            if (!note.isNullOrBlank()) {
                androidx.compose.material3.Card(
                    shape = MaterialTheme.shapes.medium,
                    colors = androidx.compose.material3.CardDefaults
                        .cardColors(
                            containerColor =
                                MaterialTheme.colorScheme
                                    .surfaceContainer
                        ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            "Crash note",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            note.trim(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme
                                .onSurfaceVariant,
                            modifier = Modifier
                                .weight(1f)
                                .verticalScroll(rememberScrollState())
                        )
                        Text(
                            "Only the time and the kind of error — " +
                                "never your books or keys. You can " +
                                "share this note if you report the " +
                                "problem.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme
                                .onSurfaceVariant
                        )
                    }
                }
            } else {
                Spacer(Modifier.weight(1f))
            }
            // The pinned button group: Continue (filled) /
            // Safe appearance (tonal) — always on screen.
            androidx.compose.material3.ButtonGroup(
                overflowIndicator = {},
                modifier = Modifier.fillMaxWidth()
            ) {
                customItem(
                    buttonGroupContent = {
                        androidx.compose.material3.Button(
                            onClick = onContinue,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Continue to your library")
                        }
                    },
                    menuContent = {}
                )
                if (onSafeAppearance != null) {
                    customItem(
                        buttonGroupContent = {
                            androidx.compose.material3.FilledTonalButton(
                                onClick = onSafeAppearance,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Safe appearance")
                            }
                        },
                        menuContent = {}
                    )
                }
            }
            if (onSafeAppearance != null) {
                Text(
                    "Safe appearance resets this design's appearance " +
                        "settings to their defaults before opening, so " +
                        "a broken appearance can't happen again on the " +
                        "way in. It changes nothing in the other design.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
