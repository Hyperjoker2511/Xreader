package app.xreader.ui.shell

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import app.xreader.R
import app.xreader.core.motion.FADE_MS
import app.xreader.core.motion.SpringMotionEngine
import app.xreader.core.motion.fadeTween
import app.xreader.core.motion.toSpec
import app.xreader.ui.theme.NocturneCanvas
import app.xreader.ui.theme.NocturneViolet
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.delay

/**
 * v1.2.0 (WS6): the animated launch intro — the NEW launcher mark
 * (the bold white X of `ic_launcher_foreground`, the user's pick
 * "D") springs in over a violet Nocturne bloom on the pure-black
 * canvas, AnymeX/ShonenX-style, playing OVER normal startup.
 *
 * The intro is a pure overlay: it is composed as a sibling ABOVE
 * the shell, never gates, waits on, or slows the container /
 * MainScaffold startup path, and carries no clickable /
 * pointer-input surface, so touches fall through to the shell
 * beneath it even while it is on screen.
 *
 * Lifetime rules (all pinned by LaunchIntroControllerTest):
 *  - shows ONCE per cold start — [LaunchIntroShown] is a
 *    process-lifetime gate, so a configuration change / rotation
 *    (new Activity, same process) never replays it, and process
 *    death (a true cold start) resets it;
 *  - dismisses as soon as the Library is ready (the caller feeds
 *    [LaunchIntro]'s `libraryReady` from LibraryViewModel's
 *    `loading` flag, threaded out of MainScaffold);
 *  - HARD CAP: force-dismisses [LaunchIntroController.CAP_MILLIS]
 *    (1,000 ms) after it starts, even if the ready signal never
 *    arrives. There is NO artificial minimum display time;
 *  - never shows when animations are disabled for this user —
 *    the caller passes the app's full motion decision
 *    (`rememberMotionEnabled(userEnabled = !appearance.reduceMotion)`,
 *    i.e. `SpringMotionEngine.animationsEnabled` over the system
 *    animator scale AND the Appearance reduce-motion preference);
 *    if that decision flips to disabled mid-show (the stored
 *    preference landing after the first frame), the intro drops
 *    immediately.
 *
 * Motion goes ONLY through [SpringMotionEngine]: the mark scales
 * in on the Celebration family's [SpringMotionEngine.CelebrationBadge]
 * profile (the sanctioned springy/overshoot family — quick, ~2%
 * overshoot) and the glow blooms on
 * [SpringMotionEngine.CelebrationBurst]; alpha uses the engine's
 * one fade tween. No local spring / duration constants.
 */

/**
 * The pure launch-intro state machine (JVM-testable: no Android,
 * no Compose). Time is injected as [nowMillis] so tests drive the
 * cap with a fake clock.
 *
 * Phases: IDLE → SHOWING → DONE. DONE is terminal for the life of
 * the controller — a second [begin] (a recomposition, a rotation
 * that somehow kept this instance) can never restart it.
 */
class LaunchIntroController(
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
    val capMillis: Long = CAP_MILLIS
) {
    enum class Phase { IDLE, SHOWING, DONE }

    var phase: Phase = Phase.IDLE
        private set

    private var startedAtMillis: Long = 0L

    val isShowing: Boolean get() = phase == Phase.SHOWING

    /**
     * The one show decision: a fresh cold start only — not when
     * this process already showed the intro ([alreadyShown]), not
     * under reduced motion, and not when the Library is already
     * ready (no flash over content that beat the intro to screen).
     */
    fun shouldShow(
        alreadyShown: Boolean,
        reducedMotion: Boolean,
        libraryReady: Boolean = false
    ): Boolean = !alreadyShown && !reducedMotion && !libraryReady

    /**
     * Attempt to start showing. Returns true exactly once, when
     * the IDLE → SHOWING transition happens; every refusal (already
     * shown, reduced motion, library already ready, or any repeat
     * call) settles the controller at DONE and returns false.
     */
    fun begin(
        alreadyShown: Boolean,
        reducedMotion: Boolean,
        libraryReady: Boolean = false
    ): Boolean {
        if (phase != Phase.IDLE) return false
        if (!shouldShow(alreadyShown, reducedMotion, libraryReady)) {
            phase = Phase.DONE
            return false
        }
        startedAtMillis = nowMillis()
        phase = Phase.SHOWING
        return true
    }

    /** The Library-ready signal: SHOWING → DONE. No-op otherwise. */
    fun onLibraryReady(): Boolean {
        if (phase != Phase.SHOWING) return false
        phase = Phase.DONE
        return true
    }

    /** Immediate dismissal (e.g. reduced motion arrived mid-show). */
    fun dismiss(): Boolean {
        if (phase != Phase.SHOWING) return false
        phase = Phase.DONE
        return true
    }

    /**
     * The hard-cap check, driven by the overlay's watchdog with
     * the injected clock: once [capMillis] has elapsed since
     * [begin], the intro force-dismisses whether or not the ready
     * signal ever fired. Returns true only on the tick that
     * performs the force-dismiss.
     */
    fun onTick(): Boolean {
        if (phase != Phase.SHOWING) return false
        if (nowMillis() - startedAtMillis >= capMillis) {
            phase = Phase.DONE
            return true
        }
        return false
    }

    companion object {
        /** Hard cap from intro start to force-dismiss: ≤ 1 s. */
        const val CAP_MILLIS: Long = 1_000L
    }
}

/**
 * The once-per-process claim, kept separate from the controller so
 * tests can exercise a fresh instance hermetically. Backed by an
 * [AtomicBoolean]: the first claim wins, every later claim in the
 * same process loses; a new process starts unclaimed.
 */
class LaunchIntroOnceGate {
    private val claimed = AtomicBoolean(false)

    fun hasClaimed(): Boolean = claimed.get()

    /** True only for the single call that wins the claim. */
    fun tryClaim(): Boolean = claimed.compareAndSet(false, true)
}

/** The process-lifetime launch-intro gate (see [LaunchIntroOnceGate]). */
object LaunchIntroShown {
    private val gate = LaunchIntroOnceGate()

    fun hasShown(): Boolean = gate.hasClaimed()

    /** Marks the intro as shown for this process; true if this call claimed it. */
    fun markShown(): Boolean = gate.tryClaim()
}

/**
 * The overlay itself. Render it ABOVE the shell (a later sibling
 * in the same Box); it renders nothing at all when the intro is
 * not (or no longer) showing.
 *
 * [libraryReady] is the shell's first-real-content signal;
 * [animationsEnabled] is the caller's full motion decision (system
 * animator scale AND the user's reduce-motion preference, via
 * `SpringMotionEngine.animationsEnabled` — see the file header).
 */
@Composable
fun LaunchIntro(
    libraryReady: Boolean,
    animationsEnabled: Boolean,
    modifier: Modifier = Modifier
) {
    val controller = remember { LaunchIntroController() }
    // Mounted from the FIRST frame when the intro is eligible, so
    // the shell never flashes uncovered for a frame before the
    // overlay lands; the effect below makes the formal claim and
    // unmounts again if the controller refuses. `exiting` drives
    // the exit fade while `mounted` keeps the overlay composed
    // for it. Both mirror the controller, the single decision-maker.
    val eligibleAtCompose = !LaunchIntroShown.hasShown() &&
        animationsEnabled && !libraryReady
    var mounted by remember { mutableStateOf(eligibleAtCompose) }
    var exiting by remember { mutableStateOf(false) }

    // Begin once per composition instance — the process gate and
    // the controller together guarantee once per cold start.
    LaunchedEffect(Unit) {
        val began = controller.begin(
            alreadyShown = LaunchIntroShown.hasShown(),
            reducedMotion = !animationsEnabled,
            libraryReady = libraryReady
        )
        if (!began) {
            mounted = false
            return@LaunchedEffect
        }
        if (!LaunchIntroShown.markShown()) {
            // Another composition of this same cold start claimed
            // the intro first — stand down rather than double-show.
            controller.dismiss()
            mounted = false
            return@LaunchedEffect
        }
        mounted = true
        // Cap watchdog: polls the controller's injected-clock cap
        // until ANY dismissal (ready, reduced-motion flip, cap)
        // ends the show, then plays the exit fade and unmounts.
        while (controller.isShowing) {
            delay(CAP_POLL_MS)
            controller.onTick()
        }
        exiting = true
        delay(FADE_MS.toLong())
        mounted = false
    }
    // Dismiss as soon as the Library is ready — no minimum time.
    LaunchedEffect(libraryReady) {
        if (libraryReady) controller.onLibraryReady()
    }
    // Reduced motion landing mid-show drops the intro at once.
    LaunchedEffect(animationsEnabled) {
        if (!animationsEnabled) controller.dismiss()
    }

    if (!mounted) return
    AnimatedVisibility(
        visible = !exiting,
        enter = EnterTransition.None,
        exit = fadeOut(fadeTween()),
        modifier = modifier
    ) {
        LaunchIntroContent()
    }
}

/** Watchdog poll interval for the cap check (not a motion value). */
private const val CAP_POLL_MS: Long = 24L

@Composable
private fun LaunchIntroContent() {
    // Flip the spring targets after the first frame so the mark
    // animates IN from its rest state instead of starting settled.
    var sprung by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { sprung = true }
    val markScale by animateFloatAsState(
        targetValue = if (sprung) 1f else 0.72f,
        animationSpec = SpringMotionEngine.CelebrationBadge.toSpec(),
        label = "launchIntroMarkScale"
    )
    val markAlpha by animateFloatAsState(
        targetValue = if (sprung) 1f else 0f,
        animationSpec = fadeTween(),
        label = "launchIntroMarkAlpha"
    )
    val glowBloom by animateFloatAsState(
        targetValue = if (sprung) 1f else 0f,
        animationSpec = SpringMotionEngine.CelebrationBurst.toSpec(),
        label = "launchIntroGlowBloom"
    )
    // Hoisted locals (delegated-property smart casts do not compile).
    val scaleNow = markScale
    val alphaNow = markAlpha
    val bloomNow = glowBloom
    Box(
        Modifier
            .fillMaxSize()
            .background(NocturneCanvas),
        contentAlignment = Alignment.Center
    ) {
        // The violet bloom behind the mark: a Nocturne-violet
        // radial that swells + brightens with the Burst spring.
        Box(
            Modifier
                .size(300.dp)
                .drawBehind {
                    val radius = size.maxDimension * 0.72f * (0.55f + 0.45f * bloomNow)
                    drawCircle(
                        brush = Brush.radialGradient(
                            listOf(
                                NocturneViolet.copy(alpha = 0.55f * bloomNow),
                                Color.Transparent
                            ),
                            radius = radius
                        ),
                        radius = radius
                    )
                }
        )
        // THE mark: the launcher's own white X vector, large.
        Image(
            painter = painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = null,
            modifier = Modifier
                .size(148.dp)
                .graphicsLayer {
                    scaleX = scaleNow
                    scaleY = scaleNow
                    alpha = alphaNow
                }
        )
    }
}
