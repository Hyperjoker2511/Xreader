package app.xreader.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * v1.5.8 (J15): the ONE state kit for the whole app — loading /
 * error / empty share one visual language (the Nocturne glass
 * card, 24dp radius, Jakarta headline + Inter body via the
 * theme type scale). The laws baked in:
 *  - Loading says WHAT is happening and that it is STILL
 *    loading — never a verdict (A3's law, applied visually).
 *    No bare spinner ships anywhere.
 *  - Error carries the REAL reason in plain words + the door
 *    that actually exists for that surface (A4's law) as its
 *    one primary action.
 *  - Empty (KitEmptyState / EmptyState) says what would fill
 *    it + the action that fills it.
 */

@Composable
fun LoadingState(
    whatIsHappening: String,
    modifier: Modifier = Modifier,
    /** Code 120 (Q11 + A3): in the Material 3 design a long job
     * (a probe, an install) shows the wavy line; a short wait
     * shows the experimental LoadingIndicator (the §3 U7
     * opt-in). Nocturne ignores this — its spinner is unchanged. */
    longJob: Boolean = false
) {
    if (app.xreader.ui.theme.LocalDesignState.current.design ==
        app.xreader.data.prefs.AppDesign.MATERIAL3
    ) {
        M3LoadingState(
            whatIsHappening = whatIsHappening,
            modifier = modifier,
            longJob = longJob
        )
        return
    }
    GlassCard(modifier = modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(28.dp),
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                whatIsHappening,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Text(
                "Still loading…",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * The Material 3 design's loading state (design §2.10 S40):
 * a tonal card (surfaceContainer, medium shape), the label in
 * the type scale, and the wait indicator per Q11 — the
 * experimental LoadingIndicator for short waits (the build's
 * single experimental opt-in, §3 U7/E2), the wavy line for
 * long jobs.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun M3LoadingState(
    whatIsHappening: String,
    modifier: Modifier = Modifier,
    longJob: Boolean = false
) {
    androidx.compose.material3.Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (longJob) {
                androidx.compose.material3.LinearWavyProgressIndicator(
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                androidx.compose.material3.LoadingIndicator(
                    modifier = Modifier.size(48.dp)
                )
            }
            Text(
                whatIsHappening,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Text(
                "Still loading…",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun ErrorState(
    reason: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    detail: String? = null
) {
    GlassCard(modifier = modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                reason,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )
            if (!detail.isNullOrBlank()) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
            if (actionLabel != null && onAction != null) {
                PrimaryPill(text = actionLabel, onClick = onAction)
            }
        }
    }
}
