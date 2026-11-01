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
        "are you a robot",
        // v1.3.3 (V132-CHECK 1): Cloudflare's BLOCK page title —
        // "Attention Required! | Cloudflare". BrightNovel's wall
        // is this page (a hard block, not a JS challenge), and
        // the phrase list missing it is why the rendered road's
        // answer read as an "empty catalogue" on device instead
        // of a check to solve.
        "attention required"
    )

    /** v1.3.2 (scope A2): the marker a challenge failure
     * carries in its error detail, so every layer above the
     * fetchers (health, view models, failure cards) can
     * recognise "this needs the one-time solve screen"
     * without re-parsing pages. */
    const val CHALLENGE_MARKER = "site-check-needed"

    /** The canonical challenge failure detail. */
    fun challengeDetail(): String =
        "$CHALLENGE_MARKER: the site is showing a human check"

    /** True when an error's detail marks a site check. */
    fun isChallengeDetail(detail: String?): Boolean {
        val text = detail?.lowercase() ?: return false
        return CHALLENGE_MARKER in text ||
            CHALLENGE_PHRASES.any { it in text }
    }

    /** Challenge markers in a raw HTML body (the fetch
     * layer's check — no document parse needed): the
     * Cloudflare phrases, or the challenge widgets'
     * fingerprints in the markup. */
    fun hasChallengeMarkersIn(html: String): Boolean {
        val text = html.lowercase()
        if (CHALLENGE_PHRASES.any { it in text }) return true
        return "challenge-form" in text || "cf-turnstile" in text ||
            "challenges.cloudflare" in text || "id=\"challenge-running\"" in text
    }

    /**
     * True when a whole PAGE is a challenge interstitial,
     * not a content page that merely mentions a phrase:
     *  - a challenge TITLE is conclusive at any size;
     *  - Cloudflare's own markup (turnstile widgets, the
     *    challenge form/platform) is conclusive at any size —
     *    prose never carries it;
     *  - the challenge PHRASES prove a check only when the
     *    page carries almost no visible prose (an
     *    interstitial is a few hundred characters of text;
     *    a chapter that happens to say "just a moment" is
     *    thousands — v1.3.2: the phrase alone on a small but
     *    real prose page must not read as a check).
     */
    fun isChallengePage(html: String): Boolean {
        val title = Regex("(?is)<title[^>]*>(.*?)</title>")
            .find(html)?.groupValues?.get(1)?.lowercase().orEmpty()
        if (CHALLENGE_PHRASES.any { it in title }) return true
        val text = html.lowercase()
        if ("challenge-form" in text || "cf-turnstile" in text ||
            "challenges.cloudflare" in text ||
            "cdn-cgi/challenge-platform" in text ||
            "id=\"challenge-running\"" in text
        ) {
            return true
        }
        if (html.length >= CHALLENGE_PAGE_MAX_BYTES) return false
        if (CHALLENGE_PHRASES.none { it in text }) return false
        val visible = html
            .replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ")
            .replace(Regex("<[^>]+>"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return visible.length < CHALLENGE_VISIBLE_MAX_CHARS
    }

    /** An interstitial's visible text is a few hundred
     * characters at most; even a short chapter is more. */
    private const val CHALLENGE_VISIBLE_MAX_CHARS = 1_500

    /** Interstitials are small; content pages are not. */
    private const val CHALLENGE_PAGE_MAX_BYTES = 65_536

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
        // WS3 (class M): the phrase may live ONLY in the
        // document title — one batch site's custom challenge
        // page carries "Just a moment" in its <title> and no
        // Cloudflare selectors anywhere, so a body-only scan
        // reported a plain HTTP error and never routed the
        // page to the site-check flow built for it.
        val text = (doc.title() + " " + (doc.body()?.text().orEmpty()))
            .lowercase()
        return CHALLENGE_PHRASES.any { it in text }
    }
}
