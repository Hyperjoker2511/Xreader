package app.xreader.ui.common

import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Code 120, references updated in code 121 (scratch §3.10
 * S40 + §8 Q11): progress wears the active design's
 * indicator. Under Nocturne these are the stock linear
 * indicator exactly as before; under Material 3, long jobs
 * (downloads, probes, installs, the update download) take
 * the wavy progress indicator — the spec's long-job
 * indicator — while short waits take the loading indicators
 * (StateKit). Call sites opt in per surface; other progress
 * lines are deliberately untouched. The circular wavy form
 * lives in StateKit as DesignCircularWavyProgress.
 */
@Composable
fun DesignLinearProgress(
    progress: () -> Float,
    modifier: Modifier = Modifier
) {
    // Code 124: the wavy branch belonged to the removed
    // MATERIAL3 design. Both surviving designs take the plain
    // indicator here (the Material 3 design's own progress
    // language is the plain bar — code 123 deviation #6);
    // wavy progress still lives at its direct call sites.
    LinearProgressIndicator(
        progress = progress,
        modifier = modifier
    )
}

@Composable
fun DesignLinearProgressIndeterminate(
    modifier: Modifier = Modifier
) {
    LinearProgressIndicator(modifier = modifier)
}
