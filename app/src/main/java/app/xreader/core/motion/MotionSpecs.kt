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
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
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
 *    only for alpha and progress, never for travel. (The one former
 *    exception — the D8 nav-stack transitions' timed 400 ms curve —
 *    was retired by the ColorOS motion pass, 2026-11-15: stack
 *    travel rides the Spatial spring like everything else; only
 *    its Patch 8 geometry survives.)
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

/** Side-switch ENTER fade (nav engine round, 2026-11-16,
 * SPEC-NAV-ENGINE-2026-11-16 §4 — the one-clock retime): the
 * sequenced FADE-THROUGH this token family was built for.
 * The incoming side fades in over 200 ms, DELAYED by
 * [TAB_ENTER_DELAY_MS] so it only starts once the outgoing
 * side is gone — no overlap, so no ghost and no dip. Total
 * 300 ms. Since the travel-choreography round
 * (SPEC-NAV-TRAVEL-WAVE-2026-11-16 §A) TAB ROOTS swap
 * instantly and no longer use this family at all; it now
 * serves ONLY the Novels ↔ Manga side switch (which keeps
 * the fade-through verbatim) and, via [tabCrossfadeSpec],
 * the reduced-motion tab crossfade. (History: 120 ms
 * alongside the Spatial slide in the ColorOS pass; 80 ms
 * exit ghost fix in the nav-bar styles pass; 200 ms
 * fade-through for all peer switches in the nav engine
 * round.) */
const val TAB_FADE_MS = 200

/** The fade-through's sequencing delay: the incoming tab
 * waits this long — exactly the outgoing tab's fade — before
 * it starts arriving (SPEC-NAV-ENGINE-2026-11-16 §4). */
const val TAB_ENTER_DELAY_MS = 100

/** Tab-switch EXIT fade (nav engine round, 2026-11-16,
 * SPEC-NAV-ENGINE-2026-11-16 §4): the outgoing tab fades out
 * over 100 ms — gone before the incoming tab's delayed fade
 * begins, so the two screens are never on stage together. */
const val TAB_EXIT_FADE_MS = 100

/** Stack fade (ColorOS motion pass, 2026-11-15): 200 ms — the
 * segmentColorSpec precedent — under the Spatial-spring travel. */
const val STACK_FADE_MS = 200

/** Shared-axis fade (ColorOS motion pass, 2026-11-15): 160 ms
 * under the Spatial / Sheet spring travel. */
const val AXIS_FADE_MS = 160

fun <T> fadeTween() = tween<T>(durationMillis = FADE_MS, easing = FadeEasing)

/** The side-switch ENTER fade curve: [TAB_FADE_MS] delayed
 * by [TAB_ENTER_DELAY_MS] on the shared FadeEasing — the
 * fade-through's second half (SPEC-NAV-ENGINE-2026-11-16
 * §4), kept for the Novels ↔ Manga switch after tab roots
 * went instant (SPEC-NAV-TRAVEL-WAVE-2026-11-16 §A). */
fun <T> tabFadeSpec() = tween<T>(
    durationMillis = TAB_FADE_MS,
    delayMillis = TAB_ENTER_DELAY_MS,
    easing = FadeEasing
)

/** The reduced-motion tab crossfade: [TAB_FADE_MS] with NO
 * delay — under reduced motion the two screens still trade
 * places in one symmetric crossfade, as they always have
 * (SPEC-NAV-ENGINE-2026-11-16 §4 keeps that path verbatim). */
fun <T> tabCrossfadeSpec() = tween<T>(durationMillis = TAB_FADE_MS, easing = FadeEasing)

/** The tab-switch EXIT fade curve: [TAB_EXIT_FADE_MS] on the
 * shared FadeEasing (the ghost fix — see the token). */
fun <T> tabExitFadeSpec() = tween<T>(durationMillis = TAB_EXIT_FADE_MS, easing = FadeEasing)

/** The stack fade curve: [STACK_FADE_MS] on the shared FadeEasing. */
fun <T> stackFadeSpec() = tween<T>(durationMillis = STACK_FADE_MS, easing = FadeEasing)

/** The shared-axis fade curve: [AXIS_FADE_MS] on the shared
 * FadeEasing. */
fun <T> axisFadeSpec() = tween<T>(durationMillis = AXIS_FADE_MS, easing = FadeEasing)

/** The Library switch segments' colour ease (fix round §D.3):
 * 200 ms on the shared FadeEasing — the colour lands a beat
 * before the content glide finishes, so the control you tapped
 * answers first. */
fun segmentColorSpec() =
    tween<androidx.compose.ui.graphics.Color>(durationMillis = 200, easing = FadeEasing)

/* The FLASHY dock style's icon↔label swap adapter
 * (flashySwapSpec, nav engine round) is RETIRED by the
 * travel-choreography round (SPEC-NAV-TRAVEL-WAVE-2026-11-16
 * §B.1): the swap is now driven by the slot's marker coverage
 * — NavIndicatorLogic.slotCoverage read in NavSlot's layout
 * phase — so it moves on the marker's own clock and needs no
 * tween of its own. */

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
        fadeIn(axisFadeSpec())

fun sharedAxisXExit(reduceMotion: Boolean): ExitTransition =
    if (reduceMotion) fadeOut(fadeTween())
    else slideOutHorizontally(SpringMotionEngine.Spatial.toSpec()) { -it / 6 } +
        fadeOut(axisFadeSpec())

fun sharedAxisXPopEnter(reduceMotion: Boolean): EnterTransition =
    if (reduceMotion) fadeIn(fadeTween())
    else slideInHorizontally(SpringMotionEngine.Spatial.toSpec()) { -it / 6 } +
        fadeIn(axisFadeSpec())

fun sharedAxisXPopExit(reduceMotion: Boolean): ExitTransition =
    if (reduceMotion) fadeOut(fadeTween())
    else slideOutHorizontally(SpringMotionEngine.Spatial.toSpec()) { it / 6 } +
        fadeOut(axisFadeSpec())

// --- Nav stack (v1.0.4, D8; geometry retuned by Patch 8, Orion
// --- piece 2; curve superseded by the ColorOS motion pass,
// --- 2026-11-15): the user's Apple-style transitions. Geometry:
// --- pushed screens travel a SHORT distance — 1/6 of the width,
// --- the shared-axis distance (D8's original travelled the FULL
// --- width); the covered screen (forward) and the returning
// --- screen (back) drift only the engine's parallax offset,
// --- which scales with the travel (width/24 — the drift stays a
// --- quarter OF THE TRAVEL, so the background never outruns the
// --- foreground). The curve was the paste's timed 400 ms until
// --- the ColorOS pass (SPEC-MOTION-COLOROS-2026-11-15 Change 5):
// --- travel now rides the Spatial spring, the fade is the
// --- 200 ms stackFadeSpec, and the navSlideSpec / navFadeSpec /
// --- NAV_TRANSITION_MS tokens are retired. The geometry is NOT
// --- changed. Tab switches use the spring travel below — never
// --- a full-width slide.

/** Pushed screen arriving: short slide in from the right — 1/6 of
 * the width (Patch 8 retune; D8's original travelled the full
 * width) + fade. */
fun stackEnter(reduceMotion: Boolean): EnterTransition =
    if (reduceMotion) fadeIn(fadeTween())
    else slideInHorizontally(SpringMotionEngine.Spatial.toSpec()) { it / 6 } +
        fadeIn(stackFadeSpec())

/** Screen being covered: parallax drift to the engine's offset
 * (width/24 after Patch 8) + fade. */
fun stackExit(reduceMotion: Boolean): ExitTransition =
    if (reduceMotion) fadeOut(fadeTween())
    else slideOutHorizontally(SpringMotionEngine.Spatial.toSpec()) {
        -SpringMotionEngine.navParallaxOffset(it)
    } + fadeOut(stackFadeSpec())

/** Screen being returned to: starts at the engine's parallax
 * offset (width/24 after Patch 8) + fade. */
fun stackPopEnter(reduceMotion: Boolean): EnterTransition =
    if (reduceMotion) fadeIn(fadeTween())
    else slideInHorizontally(SpringMotionEngine.Spatial.toSpec()) {
        -SpringMotionEngine.navParallaxOffset(it)
    } + fadeIn(stackFadeSpec())

/** Popped screen leaving: short slide out to the right — 1/6 of
 * the width (Patch 8) + fade. */
fun stackPopExit(reduceMotion: Boolean): ExitTransition =
    if (reduceMotion) fadeOut(fadeTween())
    else slideOutHorizontally(SpringMotionEngine.Spatial.toSpec()) { it / 6 } +
        fadeOut(stackFadeSpec())

/** SlideFade's decelerate curve (the ColorOS 17 demo's own
 * values, ruled the default 2026-11-20). */
private val slideFadeDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

/** SlideFade's accelerate curve for the exit half. */
private val slideFadeAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

/**
 * Tab-to-tab — SlideFade (Micro-build M; the ColorOS 17
 * demo the owner picked 2026-11-20, "Use it as default
 * transition", pulled into M at his word). This supersedes
 * the travel-choreography round's instant swap
 * (SPEC-NAV-TRAVEL-WAVE-2026-11-16 §A) as the DEFAULT tab
 * transition: the incoming tab slides in from a quarter
 * width on the travel side over 450 ms on the decelerate
 * curve, fading in over 320 ms after a 60 ms delay and
 * scaling up from 0.94; [direction] steers it (+1 travels
 * leftward toward the later tab, −1 rightward). Under
 * [reduceMotion] the pair stays the symmetric crossfade it
 * has always been — reduced motion's behaviour is
 * unchanged. Stack pushes/pops keep the shared-axis
 * geometry above — untouched.
 */
fun tabEnter(direction: Int = 0, reduceMotion: Boolean = false): EnterTransition =
    if (reduceMotion) fadeIn(tabCrossfadeSpec())
    else slideInHorizontally(
        animationSpec = tween(450, easing = slideFadeDecelerate)
    ) { direction * (it / 4) } +
        fadeIn(tween(320, delayMillis = 60)) +
        scaleIn(
            animationSpec = tween(450, easing = slideFadeDecelerate),
            initialScale = 0.94f
        )

/** Tab-to-tab exit, SlideFade's second half: the outgoing
 * tab leaves toward a fifth width on the travel side over
 * 300 ms on the accelerate curve, fading out over 200 ms
 * and scaling down to 0.96. Under [reduceMotion] the exit
 * keeps the symmetric crossfade. */
fun tabExit(direction: Int = 0, reduceMotion: Boolean = false): ExitTransition =
    if (reduceMotion) fadeOut(tabCrossfadeSpec())
    else slideOutHorizontally(
        animationSpec = tween(300, easing = slideFadeAccelerate)
    ) { -direction * (it / 5) } +
        fadeOut(tween(200)) +
        scaleOut(
            animationSpec = tween(300),
            targetScale = 0.96f
        )

/**
 * The Library's Novels ↔ Manga side switch (fix round §D.3):
 * NOT a tab-root switch — SPEC-NAV-TRAVEL-WAVE-2026-11-16 §A
 * leaves its transition alone, so it keeps the sequenced
 * fade-through the tab family used to run (nav engine round,
 * SPEC-NAV-ENGINE-2026-11-16 §4): out over
 * [TAB_EXIT_FADE_MS], in over [TAB_FADE_MS] after the
 * [TAB_ENTER_DELAY_MS] delay; the symmetric crossfade under
 * reduced motion. It no longer delegates to the tab adapters
 * (they are instant now) — the fade-through lives here.
 * [direction] comes from the pure
 * LibrarySplit.switchDirection.
 */
fun sideSwitchEnter(direction: Int, reduceMotion: Boolean): EnterTransition =
    if (reduceMotion) fadeIn(tabCrossfadeSpec())
    else fadeIn(tabFadeSpec())

/** Side-switch exit: the fade-through's first half — see
 * [sideSwitchEnter]. */
fun sideSwitchExit(direction: Int, reduceMotion: Boolean): ExitTransition =
    if (reduceMotion) fadeOut(tabCrossfadeSpec())
    else fadeOut(tabExitFadeSpec())

/** Shared Axis Y for sheets: rises on the Sheet (heavy) spring. */
fun sharedAxisYEnter(reduceMotion: Boolean): EnterTransition =
    if (reduceMotion) fadeIn(fadeTween())
    else androidx.compose.animation.slideInVertically(
        SpringMotionEngine.Sheet.toSpec()
    ) { it / 8 } + fadeIn(axisFadeSpec())

fun sharedAxisYExit(reduceMotion: Boolean): ExitTransition =
    if (reduceMotion) fadeOut(fadeTween())
    else androidx.compose.animation.slideOutVertically(
        SpringMotionEngine.Sheet.toSpec()
    ) { it / 8 } + fadeOut(axisFadeSpec())

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

/**
 * The Apple bounce press (v1.0.4, D5 — user-supplied numbers, our
 * plumbing): press down → squash to [SpringMotionEngine.APPLE_PRESS_SCALE]
 * (0.92) with the same haptic tick as [pressSquish]; release → the
 * ApplePress spring (ζ 0.6, k 300) settles it back WITH the visible
 * overshoot the user asked for. Driven by the component's own
 * [interactionSource] exactly like [pressSquish] — the paste's raw
 * pointerInput loop is deliberately NOT used: it would bypass the
 * clickable's scroll cancellation, pressed-state semantics and
 * accessibility. Apply to the pressables the user's mocks mark:
 * cards, chips, nav items, primary buttons, the hero.
 *
 * ColorOS motion pass (2026-11-15, SPEC-MOTION-COLOROS-2026-11-15
 * Change 3): the two legs run DIFFERENT specs, chosen by the
 * target — the press leg runs MicroPress (k700 / ζ0.95) so the
 * squash lands within ~100 ms (ColorOS Flexible Feedback: the
 * control answers the instant the finger lands, even on a quick
 * tap); the release leg keeps ApplePress and its bounce. (With
 * one spec for both legs, a quick tap lifted before the squash
 * had visibly started.)
 */
fun Modifier.pressBounce(
    interactionSource: InteractionSource,
    enabled: Boolean = true,
    haptics: Boolean = true
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
    val targetScale =
        if (pressed && enabled) SpringMotionEngine.APPLE_PRESS_SCALE else 1f
    val scale by animateFloatAsState(
        targetValue = targetScale,
        animationSpec = if (targetScale == SpringMotionEngine.APPLE_PRESS_SCALE) {
            SpringMotionEngine.MicroPress.toSpec()
        } else {
            SpringMotionEngine.ApplePress.toSpec()
        },
        label = "pressBounce"
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

// --- Gooey nav (Liquid Glass, piece 4) ---------------------------------

/**
 * The gooey dock indicator's melt spring: the Animatable driving
 * the melt's travel progress runs on the engine's GooeyMelt
 * profile — the values live in [SpringMotionEngine], never in the
 * shell.
 */
fun gooeyMeltSpec(): SpringSpec<Float> = SpringMotionEngine.GooeyMelt.toSpec()

/**
 * The melt's SHORT spring (nav-bar styles pass, 2026-11-16): on
 * the MINIMAL tier the melt still travels — never a teleport —
 * but on the engine's NavTravelMinimal profile (~176 ms), so a
 * 2 GB phone pays a fraction of the full melt's frames. The
 * indicator picks between this and [gooeyMeltSpec] by tier.
 */
fun gooeyMeltShortSpec(): SpringSpec<Float> = SpringMotionEngine.NavTravelMinimal.toSpec()

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
