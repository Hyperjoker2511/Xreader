package app.xreader.ui.start

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import kotlinx.coroutines.delay

/**
 * Code 120 (design §2.9 S2): the Material 3 design's launch
 * intro — a plain surface in the scheme's surface colour with
 * the app wordmark in displaySmall, dismissed by an
 * effect-default fade the moment the Library reports ready
 * (hard-capped so it can never linger). No spring choreography:
 * that language is Nocturne's, and the spatial/effect discipline
 * (§1.4) gives a launch surface the effect specs only.
 *
 * Like Nocturne's LaunchIntro it is a pure overlay composed
 * above the shell — first content is never gated on it.
 */
@Composable
fun M3LaunchIntro(
    libraryReady: Boolean,
    animationsEnabled: Boolean
) {
    var dismissed by remember { mutableStateOf(false) }
    LaunchedEffect(libraryReady) {
        if (libraryReady) dismissed = true
    }
    LaunchedEffect(Unit) {
        delay(1_200)
        dismissed = true
    }
    val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    AnimatedVisibility(
        visible = animationsEnabled && !dismissed,
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
                Text(
                    "Xreader",
                    style = MaterialTheme.typography.displaySmall
                )
            }
        }
    }
}
