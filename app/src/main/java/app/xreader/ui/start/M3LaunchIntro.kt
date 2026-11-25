package app.xreader.ui.start

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import app.xreader.ui.shell.LaunchIntroShown
import kotlinx.coroutines.delay

/**
 * Code 120 (scratch design §3.1 S2): the Material 3 design's
 * launch intro — a plain surface in the scheme's surface
 * colour with the app wordmark in displaySmall, dismissed by
 * an effect-default fade when the Library reports ready
 * (hard-capped so it can never linger). No spring choreography:
 * that language is Nocturne's, and the spatial/effect discipline
 * (§2.4) gives a launch surface the effect specs only. The
 * wordmark wears the M3 type scale — the platform typeface
 * (§2.2/T19), never the Nocturne brand pairing.
 *
 * Code 126 (splash parity): the Xreader mark stands above the
 * wordmark — the owner's branded-moment pick has the mark
 * animating in in BOTH designs (here it rides the effects fade,
 * in the scheme's onSurface colour so it reads in light and
 * dark alike; Nocturne keeps its own spring + artwork).
 *
 * Code 127 (owner's splash-timing pick): the ready signal
 * dismisses the intro only once the enter fade has completed
 * — the same hold Nocturne's intro applies — so a fast load
 * can no longer cut the moment mid-fade. The hold lasts
 * exactly the fade (still under a second); the 1,200 ms cap
 * still bounds the whole show.
 *
 * Code 128 (F-B — the splash-order conviction): this intro is
 * now a GATE, with the same discipline as Nocturne's
 * LaunchIntro. The owner's code-127 proof video showed the
 * Library shell and its "Loading your library" state painting
 * BEFORE the branded moment, which then faded in over them:
 * the old composable started invisible (its AnimatedVisibility
 * entry flipped a frame late) and its entry was a fadeIn of
 * the whole surface, so the shell showed through the intro's
 * own arrival. Now:
 *  - the Surface is composed OPAQUE from the first frame
 *    (EnterTransition.None) — the shell, composed beneath it
 *    in MainActivity's Box, loads under the intro and never
 *    paints visibly before it;
 *  - only the mark + wordmark animate in, INSIDE the opaque
 *    surface (their own alpha, on the effects spec);
 *  - the once-per-process claim is shared with Nocturne's
 *    intro ([LaunchIntroShown]) — MainActivity latches ONE
 *    intro face per cold start (from the splash mirror), so
 *    the claim can never be spent by the wrong face;
 *  - dismissal keeps the code-127 hold — the ready signal
 *    lands only once the enter fade has completed — bounded
 *    by the same 1,200 ms hard cap;
 *  - [onSettled] reports every terminal path (shown-and-done,
 *    refused, claimed elsewhere, reduced motion) so the
 *    caller can stop composing the gate entirely.
 */
@OptIn(
    androidx.compose.material3
        .ExperimentalMaterial3ExpressiveApi::class
)
@Composable
fun M3LaunchIntro(
    libraryReady: Boolean,
    animationsEnabled: Boolean,
    onSettled: () -> Unit = {}
) {
    // Mounted from the FIRST frame when the intro is eligible,
    // so the shell never flashes uncovered before the cover
    // lands; the effect below makes the formal claim and
    // settles at once if the gate refuses.
    val eligibleAtCompose = !LaunchIntroShown.hasShown() &&
        animationsEnabled && !libraryReady
    var mounted by remember { mutableStateOf(eligibleAtCompose) }
    var exiting by remember { mutableStateOf(false) }
    var dismissed by remember { mutableStateOf(false) }
    // The ready signal and the enter fade's completion are
    // latched separately; dismissal needs BOTH (code 127).
    var readySeen by remember { mutableStateOf(false) }
    var enterFinished by remember { mutableStateOf(false) }
    // The content's own enter: flipped after the claim, so the
    // mark + wordmark genuinely animate in inside the surface.
    var entered by remember { mutableStateOf(false) }

    val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    // The spec's own length is the enter fade's length. The
    // built-in motion schemes spend a TweenSpec for effects,
    // whose durationMillis is readable; any other spec shape
    // leaves the latch unset and dismissal falls to the cap
    // below rather than guessing a number.
    val enterMillis =
        (effects as? androidx.compose.animation.core.TweenSpec<Float>)
            ?.durationMillis

    // The claim + the show's lifetime: once dismissed (ready
    // AND entered, reduced-motion flip, or the cap), play the
    // exit fade, unmount, and settle the gate in MainActivity.
    LaunchedEffect(Unit) {
        if (!eligibleAtCompose || !LaunchIntroShown.markShown()) {
            mounted = false
            onSettled()
            return@LaunchedEffect
        }
        entered = true
        if (enterMillis != null) {
            delay(enterMillis.toLong())
            enterFinished = true
            if (readySeen) dismissed = true
        }
        // The hard cap, unchanged: 1,200 ms from the first
        // frame, whatever the ready signal does.
        var waited = enterMillis?.toLong() ?: 0L
        while (!dismissed && waited < 1_200L) {
            delay(24)
            waited += 24
        }
        dismissed = true
        exiting = true
        delay((enterMillis ?: 400).toLong())
        mounted = false
        onSettled()
    }
    // The Library is ready: latch it, and dismiss at once if
    // the enter fade has already completed.
    LaunchedEffect(libraryReady) {
        if (libraryReady) {
            readySeen = true
            if (enterFinished) dismissed = true
        }
    }
    // Reduced motion landing mid-show drops the intro at once.
    LaunchedEffect(animationsEnabled) {
        if (!animationsEnabled) dismissed = true
    }

    if (!mounted) return
    AnimatedVisibility(
        visible = !exiting,
        enter = EnterTransition.None,
        exit = fadeOut(effects)
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                // The branded moment INSIDE the opaque cover:
                // mark + wordmark fade in on the effects spec
                // while the surface itself never translucifies.
                val contentAlpha by animateFloatAsState(
                    targetValue = if (entered) 1f else 0f,
                    animationSpec = effects,
                    label = "m3IntroContentAlpha"
                )
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.graphicsLayer {
                        alpha = contentAlpha
                    }
                ) {
                    Image(
                        painter = painterResource(
                            app.xreader.R.drawable.ic_launcher_foreground
                        ),
                        contentDescription = null,
                        colorFilter = ColorFilter.tint(
                            MaterialTheme.colorScheme.onSurface
                        ),
                        modifier = Modifier.size(72.dp)
                    )
                    Text(
                        "Xreader",
                        style = MaterialTheme.typography.displaySmall
                    )
                }
            }
        }
    }
}
