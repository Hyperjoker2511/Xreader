package app.xreader.ui.common

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.xreader.ui.theme.isM3FamilyDesign

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
 *
 * Code 124 removed the wavy branch when the old MATERIAL3
 * design went away; code 127 (audit B3) restores it for the
 * surviving Material 3 design — the kit's own header never
 * stopped promising the branch, the M3 What's-New page and
 * Updates & about felt the plain bar through this kit while
 * UpdateCard hard-coded wavy directly, and the owner's Q11
 * ruling (wavy progress for long jobs in the Material 3
 * face) is the standing word.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DesignLinearProgress(
    progress: () -> Float,
    modifier: Modifier = Modifier
) {
    if (isM3FamilyDesign()) {
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

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DesignLinearProgressIndeterminate(
    modifier: Modifier = Modifier
) {
    if (isM3FamilyDesign()) {
        LinearWavyProgressIndicator(modifier = modifier)
    } else {
        LinearProgressIndicator(modifier = modifier)
    }
}
