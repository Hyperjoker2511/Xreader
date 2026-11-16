package app.xreader.core.motion

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Xreader's motion system — ColorOS 17 fluid physics, tuned SUBTLE.
 *
 * Derived from research, not copied from ReTranslate's MotionTokens:
 * see `build-logs/MOTION-RESEARCH.md`. The short version of the
 * research: OPPO publishes ColorOS's motion *principles* (Quantum
 * Animation Engine's resistance / inertia / rebound; ColorOS 16's
 * Seamless Animation — start at the point of touch, return smoothly;
 * ColorOS 17's Fluid Fusion / Progressive Motion / Flexible Feedback —
 * motion follows the finger's velocity, elements squash and stretch,
 * everything interruptible, one shared spring-and-damping family,
 * damping that follows an element's mass) but has never published
 * numeric constants. The numbers here are triangulated from those
 * principles, the Android platform's canonical spring scale
 * (AndroidX SpringForce), and second-order spring math, with
 * "subtle" made measurable: every profile damps at ζ ≥ 0.85, so the
 * settle overshoot is at most ~0.6% — motion felt as smoothness, not
 * bounce. (ReTranslate's spatial spring, ζ = 0.5, overshoots 16.3%.)
 *
 * This file is pure Kotlin — no Compose types — so the numbers, the
 * overshoot math and the resistance curve are unit-testable on the
 * JVM. Compose adapters live in MotionSpecs.kt.
 */
object SpringMotionEngine {

    /** Press squash depth (SPEC.md §D — ColorOS Flexible Feedback). */
    const val PRESS_SCALE = 0.95f

    /** Rubber-band resistance coefficient (MOTION-RESEARCH §4). */
    const val RUBBER_BAND_RESISTANCE = 0.55f

    /**
     * One spring in the family. [stiffness] is the Compose/AndroidX
     * spring constant k; [dampingRatio] is ζ. Numbers are the
     * research table (MOTION-RESEARCH §4) — change them there first.
     */
    data class SpringProfile(
        val name: String,
        val stiffness: Float,
        val dampingRatio: Float
    )

    /** Buttons/cards: deep squash (0.95), return with no visible bounce. */
    val MicroPress = SpringProfile("MicroPress", stiffness = 700f, dampingRatio = 0.95f)

    /** Nav pill slide, list placement, shared-axis travel. */
    val Spatial = SpringProfile("Spatial", stiffness = 380f, dampingRatio = 0.88f)

    /** Bottom sheets and drawers — the heavy surfaces. */
    val Sheet = SpringProfile("Sheet", stiffness = 260f, dampingRatio = 0.92f)

    /** Dialogs, menus, popovers. */
    val Dialog = SpringProfile("Dialog", stiffness = 320f, dampingRatio = 0.90f)

    /** Page-turn settle assist, reader chrome show/hide. */
    val PageAssist = SpringProfile("PageAssist", stiffness = 300f, dampingRatio = 0.90f)

    /** Release of a rubber-banded overscroll. */
    val OverscrollReturn = SpringProfile("OverscrollReturn", stiffness = 220f, dampingRatio = 0.85f)

    val profiles: List<SpringProfile> =
        listOf(MicroPress, Spatial, Sheet, Dialog, PageAssist, OverscrollReturn)

    // ── Celebration family (v1.0.1) ────────────────────────────────
    // The ONE sanctioned exception to the subtle-motion default:
    // the Me screen (the user's profile showpiece) may celebrate —
    // level rings, the streak flame's companions, particle bursts.
    // These profiles deliberately overshoot more than the app-wide
    // family; they live HERE (never as screen-local constants) and
    // every use must still settle to a static state under Reduce
    // Motion and on the low-RAM tier.

    /** Level-ring sweep and numeral spring-in on the Me screen. */
    val CelebrationRing = SpringProfile("CelebrationRing", stiffness = 180f, dampingRatio = 0.72f)

    /** Particle bursts: quick out, soft settle. */
    val CelebrationBurst = SpringProfile("CelebrationBurst", stiffness = 240f, dampingRatio = 0.80f)

    /** Achievement/badge stagger settles. */
    val CelebrationBadge = SpringProfile("CelebrationBadge", stiffness = 420f, dampingRatio = 0.78f)

    val celebrationProfiles: List<SpringProfile> =
        listOf(CelebrationRing, CelebrationBurst, CelebrationBadge)

    // ── Apple press family (v1.0.4, D5 — user-supplied) ──────────
    // The SECOND sanctioned exception to the subtle default, and
    // like the celebration family it is user-directed: he pasted
    // the Apple-style bounce (squash to 0.92, spring ζ 0.6 / k 300 —
    // a real ~9.5% overshoot on release) and the mocks apply it to
    // cards, chips, nav items, primary buttons and the hero. It
    // lives HERE (never in [profiles], whose subtle contract the
    // tests pin, and never as screen-local constants); the generic
    // press everywhere else stays MicroPress at [PRESS_SCALE].

    /** Apple press squash depth (user-supplied, D5). */
    const val APPLE_PRESS_SCALE = 0.92f

    /** Apple press spring: stiffness 300, dampingRatio 0.6 (D5). */
    val ApplePress = SpringProfile("ApplePress", stiffness = 300f, dampingRatio = 0.6f)

    val pressProfiles: List<SpringProfile> = listOf(ApplePress)

    // ── Nav stack family (v1.0.4, D8 — user-supplied; geometry
    // retuned by Patch 8) ──────────
    // The GEOMETRY is the user's design and stays: pushed screens
    // travel 1/6 of the width (Patch 8; D8's original travelled
    // the full width) and covered/returning screens drift a
    // quarter OF THE TRAVEL (width / 24, below), so the drift
    // stays proportional and the background never outruns the
    // foreground. The CURVE is no longer D8's timed 400 ms: the
    // ColorOS motion pass (2026-11-15, SPEC-MOTION-COLOROS-2026-11-15
    // Changes 2 + 5 — the owner's "all around the app" word)
    // retired the NAV_TRANSITION_MS and TAB_SWITCH_MS duration
    // tokens; stack and tab travel now ride the Spatial spring in
    // MotionSpecs, with short fade tokens (STACK_FADE_MS /
    // TAB_FADE_MS) living there. What remains in this family is
    // the pure geometry + decision math, so it stays testable.

    /** The parallax divisor: covered screens drift width / this.
     * Patch 8: 24 — the drift scaled with the shortened 1/6 travel
     * (D8's 4 was against the old full-width travel). */
    const val NAV_PARALLAX_DIVISOR = 24

    /** How far a covered/returning screen drifts for a container
     * width of [fullPx] — a quarter of the 1/6-width travel,
     * i.e. width / 24 (Patch 8; D8's was width / 4 against the
     * full-width travel). */
    fun navParallaxOffset(fullPx: Int): Int = fullPx / NAV_PARALLAX_DIVISOR

    /**
     * True when a navigation is purely between bottom tabs (both
     * endpoint routes are tab routes): those crossfade and never
     * slide. Anything touching a pushed route uses the stack
     * geometry. Pure — the shell passes its tab route set in.
     */
    fun isTabSwitch(
        initialRoute: String?,
        targetRoute: String?,
        tabRoutes: Set<String>
    ): Boolean =
        initialRoute != null && targetRoute != null &&
            initialRoute in tabRoutes && targetRoute in tabRoutes

    // ── Gooey nav family (Liquid Glass, piece 4) ───────────────
    // The FOURTH sanctioned exception to the subtle default, kept
    // out of [profiles] like the families above: the dock's
    // selection indicator MELTS between slots (the owner's React
    // Bits Gooey Nav pick), and a melt needs a visible settle.
    // History: the shipped 380 / 0.72 settled in ~285 ms with a
    // ~4% overshoot and read as a blink on the owner's phone
    // ("nothing changed"); the visible-layer fix round slowed it
    // to 150 / 0.62 (~527 ms settle, ~8.3% overshoot) — watchable,
    // but it read slow and loose. ColorOS motion pass retune
    // (2026-11-15, SPEC-MOTION-COLOROS-2026-11-15 Change 1 —
    // answering the owner's "not good premium smooth" verdict,
    // benchmark ColorOS 17): 300 / 0.72 settles in ~321 ms
    // (settleMillis, pinned by the 280–380 ms window in
    // SpringMotionEngineTest) with a ~3.8% overshoot
    // (overshootFraction) — one soft overshoot, the second
    // negligible (~0.15%), so no visible wobble; the brisker
    // spring's higher peak velocity also drives the indicator's
    // stretch geometry harder while it travels. It lives HERE
    // (never as a screen-local constant); reduced motion skips
    // the melt entirely, by the indicator's own gate, and the
    // MINIMAL tier runs the melt on the short nav-travel spring
    // (SPEC-NAVBAR-STYLES-2026-11-16: ~180 ms, still travelling —
    // never a teleport).
    //
    // Nav-bar styles retime (2026-11-16, SPEC-NAVBAR-STYLES-
    // 2026-11-16 Change 1): v1.6.1's phone proof measured the
    // melt HOPPING slot-to-slot despite the 321 ms settle — the
    // bridge phase (the neck at visible thickness) flashed past
    // inside the trip. 240 / 0.75 moves the settle to ~344 ms,
    // the top half of the SAME 280–380 ms readability window
    // (no re-pin: the window was written for exactly this
    // retime), and the indicator driver additionally warps its
    // progress through gooeyTravelProgress so the run lingers in
    // the bridge band — the stretch spans a watchable share of
    // the trip (Morphe-like) instead of flashing at the ends.
    // Overshoot at ζ 0.75 is ~2.8%: one soft settle, no wobble.

    /** Gooey dock indicator melt: stiffness 240, dampingRatio 0.75. */
    val GooeyMelt = SpringProfile("GooeyMelt", stiffness = 240f, dampingRatio = 0.75f)

    val gooeyProfiles: List<SpringProfile> = listOf(GooeyMelt)

    // ── Nav travel family (nav-bar styles, 2026-11-16) ─────────
    // The FIFTH sanctioned family, kept out of [profiles] like
    // the others: the travel spring for the dock indicator's
    // non-gooey styles (Slide / Bubble / Notch / Glow) and the
    // Library side switch's pill — ONE persistent object that
    // visibly TRAVELS (SPEC-NAVBAR-STYLES-2026-11-16's standard:
    // ~300–380 ms for a neighbouring tab, longer for farther
    // jumps — a spring's visible settle-to-the-pixel grows with
    // distance on its own). NavTravel is the Spatial profile
    // felt at Heavy mass (Quantum dynamic damping: the indicator
    // is a chunky pill, not a chip): k 236 / ζ 0.91 settles in
    // ~286 ms with no visible overshoot (~0.2%). NavTravelMinimal
    // is the MINIMAL tier's shortened travel (~176 ms): the
    // 2 GB phone still SLIDES, never teleports — and it doubles
    // as the gooey melt's short spring on that tier.

    /** Indicator travel, full tiers: stiffness 236, dampingRatio 0.91. */
    val NavTravel = SpringProfile("NavTravel", stiffness = 236f, dampingRatio = 0.91f)

    /** Indicator travel, MINIMAL tier: stiffness 640, dampingRatio 0.90. */
    val NavTravelMinimal = SpringProfile("NavTravelMinimal", stiffness = 640f, dampingRatio = 0.90f)

    val navTravelProfiles: List<SpringProfile> = listOf(NavTravel, NavTravelMinimal)

    /**
     * Quantum Animation Engine 3.0's dynamic damping, as three mass
     * classes over a base profile: light elements run stiffer and a
     * touch looser; heavy elements run softer and more damped, so a
     * sheet feels heavier than a chip. ζ is clamped to ≤ 1.0
     * (critically damped — beyond that a spring only gets sluggish).
     */
    enum class MassClass(val stiffnessMultiplier: Float, val dampingDelta: Float) {
        Light(1.25f, -0.02f),
        Standard(1.0f, 0.0f),
        Heavy(0.62f, +0.03f)
    }

    /** The profile for [base] as felt by an element of the given [mass]. */
    fun profileFor(base: SpringProfile, mass: MassClass): SpringProfile =
        SpringProfile(
            name = "${base.name}/${mass.name}",
            stiffness = base.stiffness * mass.stiffnessMultiplier,
            dampingRatio = (base.dampingRatio + mass.dampingDelta).coerceIn(0.05f, 1.0f)
        )

    /**
     * Step-response overshoot of an under-damped spring, as a fraction
     * of the travel: exp(−πζ / √(1−ζ²)). Critically/over-damped
     * springs (ζ ≥ 1) never overshoot. Pure — the research table's
     * overshoot column is this function, and the unit tests pin it.
     */
    fun overshootFraction(dampingRatio: Float): Double {
        if (dampingRatio >= 1f) return 0.0
        val z = dampingRatio.toDouble()
        return exp(-PI * z / sqrt(1.0 - z * z))
    }

    /**
     * Approximate time (ms) for a spring to settle within ~2% of its
     * target: 4 / (ζ·√k). Used by tests to keep every profile inside
     * the calm band the research set (160–320 ms).
     */
    fun settleMillis(profile: SpringProfile): Double {
        val omega = sqrt(profile.stiffness.toDouble())
        return 4000.0 / (profile.dampingRatio * omega)
    }

    /**
     * Virtual Resistance: the rubber-band curve for overscroll and
     * edge drags (MOTION-RESEARCH §4). Applied travel grows with the
     * drag but with ever-diminishing returns, bounded by [dimensionPx]
     * — never a hard stop, never 1:1. Non-positive inputs apply 0.
     */
    fun rubberBand(dragPx: Float, dimensionPx: Float): Float {
        if (dragPx <= 0f || dimensionPx <= 0f) return 0f
        val x = dragPx * RUBBER_BAND_RESISTANCE / dimensionPx
        return (1f - 1f / (x + 1f)) * dimensionPx
    }

    /** The single reduce-motion decision (pure — unit-tested). */
    fun animationsEnabled(systemAnimatorScale: Float, userEnabled: Boolean): Boolean =
        userEnabled && systemAnimatorScale > 0f

    /**
     * Motion tier for a device class (PLAN.md Amendments: the 2 GB
     * tablet must stay light). Full = springs everywhere; Minimal =
     * crossfades only (tablet / low-RAM / reduce-motion); Standard =
     * springs without the heaviest travel effects. Phase 4's
     * page-turn engine reads the same decision so the app never
     * disagrees with itself about how much motion a device gets.
     */
    enum class MotionTier { Full, Standard, Minimal }

    fun tierFor(totalRamMb: Long, animationsOn: Boolean): MotionTier = when {
        !animationsOn -> MotionTier.Minimal
        totalRamMb in 1L..2048L -> MotionTier.Minimal
        totalRamMb in 2049L..4096L -> MotionTier.Standard
        else -> MotionTier.Full
    }
}
