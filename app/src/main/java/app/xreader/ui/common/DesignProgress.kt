package app.xreader.ui.common

import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.xreader.data.prefs.AppDesign
import app.xreader.ui.theme.LocalDesignState

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
    if (LocalDesignState.current.design == AppDesign.MATERIAL3) {
        LinearWavyProgressIndicator(
            progress = progress,
            modifier = modifier
        )
    } else {
        LinearProgressIndicator(
            progress = progress,
            modifier = modifier
        )
    }
}

@Composable
fun DesignLinearProgressIndeterminate(
    modifier: Modifier = Modifier
) {
    if (LocalDesignState.current.design == AppDesign.MATERIAL3) {
        LinearWavyProgressIndicator(modifier = modifier)
    } else {
        LinearProgressIndicator(modifier = modifier)
    }
}
