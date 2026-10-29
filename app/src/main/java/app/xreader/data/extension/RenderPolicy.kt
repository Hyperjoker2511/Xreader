package app.xreader.data.extension

import app.xreader.data.prefs.PerfTier
import org.jsoup.nodes.Document

/** Why a fetch is happening — probes and installed sources escalate
 * under different triggers (plan W2.2). */
enum class RenderPurpose { PROBE, SOURCE }

/** What the fetch layer decided for one URL (plan W2.2). */
enum class RenderMode {
    /** Today's behaviour exactly: static client only. */
    STATIC_ONLY,

    /** Static first; if the body is a JS shell, re-fetch rendered
     * once and use the rendered page (TR1 / TR2 / TR4). */
    RENDER_ON_SHELL,

    /** The source is marked [DeclarativeConfig.needsRendering]:
     * every fetch goes straight to the browser view (TR3). */
    RENDER_ALWAYS
}

/** Everything [RenderPolicy] is allowed to look at — plain values,
 * so the whole decision table is unit-testable. */
data class RenderInputs(
    val purpose: RenderPurpose,
    val needsRendering: Boolean,
    val tier: PerfTier,
    /** A person is driving right now (the app is in the foreground,
     * or the call is a direct result of a tap). */
    val userDriven: Boolean,
    /** Heal sweep, downloads or a scheduled refresh — plan W5.2:
     * background work NEVER renders. */
    val backgroundWork: Boolean,
    val masterEnabled: Boolean
)

/**
 * v1.0.6 (W2): the one place that decides whether a page may be
 * rendered. Leashes (W5.1): the master switch off or background work
 * means static-only, always; on MINIMAL-tier devices rendering
 * happens only while a person is driving.
 */
object RenderPolicy {

    fun mode(inputs: RenderInputs): RenderMode {
        if (!inputs.masterEnabled) return RenderMode.STATIC_ONLY
        if (inputs.backgroundWork) return RenderMode.STATIC_ONLY
        if (inputs.tier == PerfTier.MINIMAL && !inputs.userDriven) {
            return RenderMode.STATIC_ONLY
        }
        return if (inputs.purpose == RenderPurpose.SOURCE &&
            inputs.needsRendering
        ) {
            RenderMode.RENDER_ALWAYS
        } else {
            RenderMode.RENDER_ON_SHELL
        }
    }

    fun rendersFirst(mode: RenderMode): Boolean =
        mode == RenderMode.RENDER_ALWAYS

    /** The second half of every trigger: a shell body under
     * [RenderMode.RENDER_ON_SHELL] escalates exactly once. */
    fun shouldEscalate(
        mode: RenderMode,
        isShell: Boolean,
        alreadyRendered: Boolean
    ): Boolean =
        !alreadyRendered && isShell && mode == RenderMode.RENDER_ON_SHELL
}

/**
 * v1.0.6 (W4.1): "this site wants the person, not a password" — a
 * sign-in form (the existing [LinkInference.needsLogin]) OR a
 * bot-check interstitial. The phrases/markers are the common
 * Cloudflare-family shapes; a normal book page — synopsis prose,
 * chapter rows — matches none of them.
 */
object SiteCheck {

    private val CHALLENGE_PHRASES = listOf(
        "just a moment",
        "checking your browser",
        "verify you are human",
        "are you a robot"
    )

    fun needsSiteCheck(doc: Document, pageUrl: String): Boolean =
        LinkInference.needsLogin(doc, pageUrl) || hasChallengeMarkers(doc)

    fun hasChallengeMarkers(doc: Document): Boolean {
        if (doc.select(
                "form[action*=challenge], #challenge-running, " +
                    "#challenge-form, .cf-turnstile, " +
                    "iframe[src*=challenges.cloudflare], " +
                    "iframe[src*=turnstile]"
            ).isNotEmpty()
        ) {
            return true
        }
        val text = doc.body()?.text()?.lowercase().orEmpty()
        return CHALLENGE_PHRASES.any { it in text }
    }
}
