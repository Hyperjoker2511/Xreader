package app.xreader.ui.start

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
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
 */
@Composable
fun RecoveryScreen(
    note: String?,
    onContinue: () -> Unit,
    onSafeAppearance: (() -> Unit)? = null
) {
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
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text("Crash note", style = MaterialTheme.typography.titleMedium)
                        Text(
                            note.trim(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
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
