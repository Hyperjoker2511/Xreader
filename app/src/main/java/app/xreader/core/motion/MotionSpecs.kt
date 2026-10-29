package app.xreader.core.motion

import android.provider.Settings
import android.view.HapticFeedbackConstants
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import app.xreader.core.motion.SpringMotionEngine.MassClass
import app.xreader.core.motion.SpringMotionEngine.SpringProfile

/**
 * Compose adapters over [SpringMotionEngine] — the only place the
 * engine's numbers become `SpringSpec`s, easings and modifiers, so
 * every screen animates from the same researched table
 * (build-logs/MOTION-RESEARCH.md §4).
 *
 * **App-wide rule (user, 2026-10-28; PLAN.md Amendment 5): subtle
 * animation throughout the app, not just navigation — and this file
 * (with [SpringMotionEngine]) is the SINGLE motion source.** Every
 * animated element in every phase — screen transitions, list item
 * placement and entrances, cards, the Continue Reading hero, sheets,
 * dialogs, progress, overscroll — goes through these adapters and the
 * engine's subtle springs. No screen may declare its own spring,
 * tween, stiffness, damping or duration constants; if a surface needs
 * a motion behaviour that isn't here, the behaviour is added HERE,
 * from an engine profile, never locally.
 *
 * ColorOS behaviours encoded here, per the research:
 *  - position is ALWAYS a spring (velocity-continuous, interruptible
 *    mid-flight — ColorOS 17's Progressive Motion); tweens are used
 *    only for alpha and progress, never for travel;
 *  - press feedback SQUASHES to 0.95 (Flexible Feedback) with a crisp
 *    haptic tick lined up with the visual, and springs back on the
 *    MicroPress profile (ζ 0.95 — no visible bounce on release);
 *  - shared-axis travel is short (1/6 of width): calm, not theatrical.
 */

/** A Compose spring spec from an engine profile. */
fun <T> SpringProfile.toSpec(): SpringSpec<T> =
    spring(stiffness = stiffness, dampingRatio = dampingRatio)

/** A Compose spring spec for [base] felt at the given [mass]. */
fun <T> specFor(base: SpringProfile, mass: MassClass): SpringSpec<T> =
    SpringMotionEngine.profileFor(base, mass).toSpec()

/** Fade curve — platform FastOutSlowIn, cubic-bezier(0.4, 0.0, 0.2, 1.0). */
val FadeEasing = CubicBezierEasing(0.4f, 0.0f, 0.2f, 1.0f)

/** Progress curve — emphasized, cubic-bezier(0.2, 0.0, 0.0, 1.0). */
val ProgressEasing = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)

const val FADE_MS = 240
const val PROGRESS_MS = 300

fun <T> fadeTween() = tween<T>(durationMillis = FADE_MS, easing = FadeEasing)

fun <T> progressTween() = tween<T>(durationMillis = PROGRESS_MS, easing = ProgressEasing)

/**
 * True when motion should play: system animator scale > 0 AND the
 * user's animations setting on. The setting joins via Appearance;
 * the parameter exists now so call sites never change.
 */
@Composable
fun rememberMotionEnabled(userEnabled: Boolean = true): Boolean {
    val context = LocalContext.current
    val systemScale = remember(context) {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            )
        }.getOrDefault(1f)
    }
    return SpringMotionEngine.animationsEnabled(systemScale, userEnabled)
}

// --- Shared Axis X on the Spatial spring: forward navigation travels
// --- a short distance + fades; interruption keeps velocity because
// --- both halves are spring/tween specs over the live value.

fun sharedAxisXEnter(reduceMotion: Boolean): EnterTransition =
    if (reduceMotion) fadeIn(fadeTween())
    else slideInHorizontally(SpringMotionEngine.Spatial.toSpec()) { it / 6 } +
        fadeIn(fadeTween())

fun sharedAxisXExit(reduceMotion: Boolean): ExitTransition =
    if (reduceMotion) fadeOut(fadeTween())
    else slideOutHorizontally(SpringMotionEngine.Spatial.toSpec()) { -it / 6 } +
        fadeOut(fadeTween())

fun sharedAxisXPopEnter(reduceMotion: Boolean): EnterTransition =
    if (reduceMotion) fadeIn(fadeTween())
    else slideInHorizontally(SpringMotionEngine.Spatial.toSpec()) { -it / 6 } +
        fadeIn(fadeTween())

fun sharedAxisXPopExit(reduceMotion: Boolean): ExitTransition =
    if (reduceMotion) fadeOut(fadeTween())
    else slideOutHorizontally(SpringMotionEngine.Spatial.toSpec()) { it / 6 } +
        fadeOut(fadeTween())

/** Shared Axis Y for sheets: rises on the Sheet (heavy) spring. */
fun sharedAxisYEnter(reduceMotion: Boolean): EnterTransition =
    if (reduceMotion) fadeIn(fadeTween())
    else androidx.compose.animation.slideInVertically(
        SpringMotionEngine.Sheet.toSpec()
    ) { it / 8 } + fadeIn(fadeTween())

fun sharedAxisYExit(reduceMotion: Boolean): ExitTransition =
    if (reduceMotion) fadeOut(fadeTween())
    else androidx.compose.animation.slideOutVertically(
        SpringMotionEngine.Sheet.toSpec()
    ) { it / 8 } + fadeOut(fadeTween())

/**
 * The ColorOS press (Flexible Feedback): press down → squash to
 * [SpringMotionEngine.PRESS_SCALE] with a crisp haptic tick; release
 * → the MicroPress spring settles it back with no visible bounce.
 * Apply to every clickable card / button, passing the same
 * [interactionSource] the clickable uses. [haptics] lets the user's
 * haptic setting (and the Minimal motion tier) silence the tick.
 */
fun Modifier.pressSquish(
    interactionSource: InteractionSource,
    enabled: Boolean = true,
    haptics: Boolean = true,
    pressedScale: Float = SpringMotionEngine.PRESS_SCALE
): Modifier = composed {
    val view = LocalView.current
    var pressed by remember { mutableStateOf(false) }
    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> {
                    pressed = true
                    if (enabled && haptics) {
                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    }
                }
                is PressInteraction.Release, is PressInteraction.Cancel -> pressed = false
            }
        }
    }
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) pressedScale else 1f,
        animationSpec = SpringMotionEngine.MicroPress.toSpec(),
        label = "pressSquish"
    )
    graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

// --- Lists: item placement + entrances ------------------------------------

/**
 * The one placement spec for `Lazy*` lists (`Modifier.animateItem`):
 * items glide to their new slots on the Spatial spring instead of
 * jumping. Every list in every phase uses this — no per-screen specs.
 */
fun listPlacementSpec(): SpringSpec<IntOffset> = SpringMotionEngine.Spatial.toSpec()

/** Entrance for list items / cards / the hero: a short fade (alpha is a
 * tween by the engine's rules — position never tweens). */
fun itemEnter(reduceMotion: Boolean): EnterTransition =
    if (reduceMotion) fadeIn(tween(0)) else fadeIn(fadeTween())

fun itemExit(reduceMotion: Boolean): ExitTransition =
    if (reduceMotion) fadeOut(tween(0)) else fadeOut(fadeTween())

// --- Parallax (PLAN §2.2: Library header background at ~0.5× rate) -------

/**
 * Pure parallax math: how far a background layer translates for a given
 * foreground scroll. [rate] is the background's speed relative to the
 * foreground (0.5 = half speed). Kept pure so the rule is testable and
 * every parallax surface computes identically.
 */
fun parallaxTranslationPx(scrollPx: Float, rate: Float): Float {
    val r = rate.coerceIn(0f, 1f)
    return -scrollPx * (1f - r)
}

/** Applies [parallaxTranslationPx] to a background layer. [scrollPx] is
 * the foreground's current scroll offset (e.g. a LazyList's). */
fun Modifier.parallaxLayer(scrollPx: Float, rate: Float = 0.5f): Modifier =
    graphicsLayer { translationY = parallaxTranslationPx(scrollPx, rate) }

// --- Progress ---------------------------------------------------------------

/**
 * The one animated-progress adapter: a value eased on the engine's
 * progress curve (emphasised bézier, [PROGRESS_MS]). Progress bars,
 * download fills and the level ring all animate through this.
 */
@Composable
fun animateProgressAsState(target: Float, label: String = "progress"): Float {
    val animated by animateFloatAsState(
        targetValue = target.coerceIn(0f, 1f),
        animationSpec = progressTween(),
        label = label
    )
    return animated
}
