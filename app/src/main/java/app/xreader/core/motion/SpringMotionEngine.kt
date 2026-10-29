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
