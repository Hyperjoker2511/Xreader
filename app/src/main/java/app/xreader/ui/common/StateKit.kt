package app.xreader.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.xreader.data.prefs.AppDesign
import app.xreader.ui.theme.LocalDesignState

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
    /** In the Material 3 design (scratch S40 + §8 Q1/Q11): a
     * long job shows wavy progress; a short wait shows the
     * experimental loading indicators — the bare form on a
     * full-surface wait, the contained form inside a surface
     * ([contained] = true). Nocturne ignores both flags — its
     * spinner is unchanged. */
    longJob: Boolean = false,
    contained: Boolean = false
) {
    if (LocalDesignState.current.design == AppDesign.MATERIAL3) {
        M3LoadingState(
            whatIsHappening = whatIsHappening,
            modifier = modifier,
            longJob = longJob,
            contained = contained
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
 * The Material 3 design's loading state (scratch §3.10 S40,
 * settled §8 Q1): NO card — the state is a component of the
 * surface it sits in, on the surface itself. A full-surface
 * short wait is the plain (bare) [LoadingIndicator] centred
 * with a bodyMedium line; a short wait inside a surface is
 * the [ContainedLoadingIndicator]; a long job is wavy
 * progress (linear here; [DesignCircularWavyProgress] is
 * the circular form for ring-shaped homes like S22's timer).
 * Both loading indicators are the experimental expressive
 * forms — the owner's recorded opt-in (§8 Q1); there is no
 * pre-1.5 circular fallback anywhere in this design.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun M3LoadingState(
    whatIsHappening: String,
    modifier: Modifier = Modifier,
    longJob: Boolean = false,
    contained: Boolean = false
) {
    Column(
        modifier.fillMaxWidth().padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        when {
            longJob -> LinearWavyProgressIndicator(
                modifier = Modifier.fillMaxWidth()
            )
            contained -> ContainedLoadingIndicator(
                modifier = Modifier.size(48.dp)
            )
            else -> LoadingIndicator(
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

/** The circular wavy form for long jobs whose home is a ring
 * (scratch S40's "wavy linear/circular progress"; S22's timer
 * ring is the standing caller shape). Determinate when
 * [progress] is given, indeterminate when it is null. Under
 * Nocturne this is the stock circular indicator, unchanged. */
@Composable
fun DesignCircularWavyProgress(
    progress: (() -> Float)?,
    modifier: Modifier = Modifier
) {
    if (LocalDesignState.current.design == AppDesign.MATERIAL3) {
        if (progress != null) {
            CircularWavyProgressIndicator(
                progress = progress,
                modifier = modifier
            )
        } else {
            CircularWavyProgressIndicator(modifier = modifier)
        }
    } else {
        if (progress != null) {
            CircularProgressIndicator(
                progress = progress,
                modifier = modifier
            )
        } else {
            CircularProgressIndicator(modifier = modifier)
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
    if (LocalDesignState.current.design == AppDesign.MATERIAL3) {
        M3ErrorState(
            reason = reason,
            modifier = modifier,
            actionLabel = actionLabel,
            onAction = onAction,
            detail = detail
        )
        return
    }
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

/**
 * The Material 3 design's error state (scratch S40): no card —
 * the plain reason in bodyMedium, the detail line where held,
 * and the door as a FILLED button (Retry where the caller
 * passes a retry; the caller's label is honoured verbatim).
 */
@Composable
private fun M3ErrorState(
    reason: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    detail: String? = null
) {
    Column(
        modifier.fillMaxWidth().padding(20.dp),
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
            Button(onClick = onAction) { Text(actionLabel) }
        }
    }
}

/**
 * The Material 3 design's empty state (scratch S40): a
 * headline line naming what would fill the surface, one body
 * line, and the door that fills it as a FILLED button — a
 * component of the surface, never a card and never a
 * full-screen takeover. New M3 surfaces call this; the shared
 * [EmptyState]/KitEmptyState in their own files keep their
 * Nocturne forms untouched.
 */
@Composable
fun M3EmptyState(
    headline: String,
    body: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            headline,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        if (actionLabel != null && onAction != null) {
            Button(onClick = onAction) { Text(actionLabel) }
        }
    }
}
