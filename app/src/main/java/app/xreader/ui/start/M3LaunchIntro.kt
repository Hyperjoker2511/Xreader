package app.xreader.ui.start

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Code 120 (scratch design §3.1 S2): the Material 3 design's
 * launch intro — a plain surface in the scheme's surface
 * colour with the app wordmark in displaySmall, dismissed by
 * an effect-default fade the moment the Library reports ready
 * (hard-capped so it can never linger). No spring choreography:
 * that language is Nocturne's, and the spatial/effect discipline
 * (§2.4) gives a launch surface the effect specs only. The
 * wordmark wears the M3 type scale — the platform typeface
 * (§2.2/T19), never the Nocturne brand pairing.
 *
 * Like Nocturne's LaunchIntro it is a pure overlay composed
 * above the shell — first content is never gated on it.
 *
 * Code 126 (splash parity): the Xreader mark now stands above
 * the wordmark — the owner's branded-moment pick has the mark
 * animating in in BOTH designs (here it rides the surface's
 * fade, in the scheme's onSurface colour so it reads in light
 * and dark alike; Nocturne keeps its own spring + artwork).
 * Dismissal is unchanged: the Library-ready signal rules, the
 * cap is only a cap — never a timer to wait out.
 *
 * Code 127 (owner's splash-timing pick): the ready signal
 * dismisses the intro only once the enter fade has completed
 * — the same hold Nocturne's intro applies — so a fast load
 * can no longer cut the moment mid-fade. The hold lasts
 * exactly the fade (still under a second); the 1,200 ms cap
 * still bounds the whole show.
 */
@Composable
fun M3LaunchIntro(
    libraryReady: Boolean,
    animationsEnabled: Boolean
) {
    var dismissed by remember { mutableStateOf(false) }
    // The enter fade must actually play: visibility starts false
    // and flips on the first frame, so the mark genuinely
    // animates IN (an already-visible AnimatedVisibility never
    // runs its enter transition — code-126 re-check caveat).
    var entered by remember { mutableStateOf(false) }
    var enterFinished by remember { mutableStateOf(false) }
    val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    // The spec's own length is the enter fade's length. The
    // built-in motion schemes spend a TweenSpec for effects,
    // whose durationMillis is readable; any other spec shape
    // leaves the latch unset and dismissal falls to the cap
    // below (the pre-127 behaviour) rather than guessing a
    // number.
    val enterMillis =
        (effects as? androidx.compose.animation.core.TweenSpec<Float>)
            ?.durationMillis
    LaunchedEffect(libraryReady) {
        if (libraryReady && enterFinished) dismissed = true
    }
    LaunchedEffect(Unit) {
        entered = true
        // The enter fade's own length: when it has played
        // out, a ready Library dismisses the intro at once;
        // until then the moment holds (code 127).
        if (enterMillis != null) {
            delay(enterMillis.toLong())
            enterFinished = true
            if (libraryReady) dismissed = true
        }
    }
    // The hard cap, unchanged: 1,200 ms from the first frame,
    // whatever the ready signal does.
    LaunchedEffect(Unit) {
        delay(1_200)
        dismissed = true
    }
    AnimatedVisibility(
        visible = animationsEnabled && entered && !dismissed,
        enter = fadeIn(effects),
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
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
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
