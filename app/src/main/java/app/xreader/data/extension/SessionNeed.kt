package app.xreader.data.extension

import app.xreader.core.error.AppError

/**
 * The session hand-off classifier (v1.3.2, scope A2 —
 * V120-CHECK items 71–75 + the LOGIN RULE).
 *
 * When a fetch dies on something a one-time visible screen
 * can solve — a Cloudflare check the engine couldn't pass
 * alone, a login gate on a sign-in-supported source, a
 * render road whose check needs a human once — the failure
 * surfaces offer that screen instead of a dead end. This
 * pure classifier decides, from the source's config and the
 * failure's own words, whether a hand-off exists and which
 * kind it is. It serves bundled defaults and USER-ADDED
 * lanes alike (item 77: BOTI's lane gets the same hand-off
 * as a bundled source — the classifier never asks where the
 * config came from).
 */
object SessionNeed {

    enum class Kind { SITE_CHECK, SIGN_IN }

    /** The hand-off a failure calls for, or null when the
     * failure is an ordinary one (offline, a changed site,
     * a plain refusal) that no sign-in or check can solve. */
    fun classify(config: DeclarativeConfig?, error: AppError): Kind? =
        classify(config, error.detail ?: error.toString(), error)

    fun classify(
        config: DeclarativeConfig?,
        detail: String?,
        error: AppError? = null
    ): Kind? {
        val text = detail?.lowercase().orEmpty()
        // A site check the engine couldn't pass alone.
        if (SiteCheck.isChallengeDetail(detail)) return Kind.SITE_CHECK
        // A sign-in-supported source whose text leg came back
        // locked/empty, or whose request was refused as
        // unauthenticated: the sign-in screen is the road.
        if (config?.signInNeeded == true) {
            if (error is AppError.AuthFailed) return Kind.SIGN_IN
            if ("paid or locked" in text || "came back empty" in text ||
                "sign in" in text || "log in" in text
            ) {
                return Kind.SIGN_IN
            }
        }
        // A render-road source whose browser view couldn't
        // build the page (BOTI's Turnstile stalls land here):
        // the check may simply need a human once, in the
        // sanctioned screen, after which the session carries.
        if (config?.needsRendering == true &&
            ("never finished building" in text ||
                "couldn't load that page in time" in text ||
                "browser view" in text)
        ) {
            return Kind.SITE_CHECK
        }
        return null
    }

    /** The plain-words state of a session-road source
     * (recommendation R1): shown on its row and its page so
     * the reader knows BEFORE tapping into a book. Null for
     * sources that don't ride the session road at all. */
    fun stateWord(
        config: DeclarativeConfig?,
        hasSession: Boolean,
        lastErrorDetail: String?
    ): String? {
        if (config == null) return null
        if (config.signInNeeded) {
            return if (hasSession) "Signed in" else "Sign in needed"
        }
        if (!config.needsRendering) return null
        if (SiteCheck.isChallengeDetail(lastErrorDetail)) {
            return "Check needed"
        }
        return "Working"
    }
}
