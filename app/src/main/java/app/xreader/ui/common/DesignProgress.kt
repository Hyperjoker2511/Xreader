package app.xreader.ui.common

import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.xreader.data.prefs.AppDesign
import app.xreader.ui.theme.LocalDesignState

/**
 * Code 120 (design §1.6 + addendum A3): progress wears the
 * active design's indicator. Under Nocturne these are the
 * stock linear indicator exactly as before; under Material 3,
 * long jobs (downloads, probes, installs, the update download)
 * take the wavy progress indicator — the spec's long-job
 * indicator — while short waits keep the plain line / the
 * loading indicator (Q11's ruling). Call sites opt in per
 * surface, as A3 names them; other progress lines are
 * deliberately untouched.
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
