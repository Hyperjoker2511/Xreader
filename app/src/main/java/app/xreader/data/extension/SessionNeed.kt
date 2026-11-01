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
        // v1.3.4 (T1): the operation-token lifecycle's own
        // markers classify FIRST — a mint that demanded the
        // site's human check is a check (on the reader page,
        // where its widget lives), and a mint the site
        // refused as unauthenticated means the ACCOUNT
        // session ended. A token EXPIRY never reaches this
        // classifier at all — the coordinator renews it
        // silently, by design.
        if (FetchClassification.MINT_CHALLENGE_MARKER in text) {
            return Kind.SITE_CHECK
        }
        if (FetchClassification.SESSION_DEAD_MARKER in text) {
            return Kind.SIGN_IN
        }
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

    /**
     * WALL-EMPTY. An EMPTY catalogue is an Ok result, so
     * [classify] never ran on it — and on a gated source,
     * "empty" is exactly what a wall looks like when the
     * rendered road got a challenge page with nothing
     * extractable on it (BrightNovel's Cloudflare
     * "Attention Required" 403, live-verified).
     *
     * v1.3.4 (T4) STRENGTHENS the v1.3.3 rule: the v1.3.3
     * version offered the hand-off only when NO session was
     * stored — and on device the wall still extracted as an
     * empty success WITH a session-shaped state (V133-CHECK
     * 7), so no action appeared. The action is now keyed on
     * the source's GATED NATURE alone: a needsRendering or
     * sign-in source with an empty catalogue ALWAYS offers
     * the solve/sign-in hand-off, and the copy states the
     * reason as UNKNOWN (no claim a wall did it — the
     * received page's shape is recorded in the source's
     * diagnostics for the real answer). [hasSession] stays
     * in the signature for callers; it no longer gates the
     * action. A source that isn't gated at all keeps the
     * honest empty shelf.
     */
    fun forEmptyCatalogue(
        config: DeclarativeConfig?,
        hasSession: Boolean
    ): Kind? {
        if (config == null) return null
        if (config.needsRendering) return Kind.SITE_CHECK
        if (config.signInNeeded) return Kind.SIGN_IN
        return null
    }

    /** The plain-words state of a session-road source
     * (recommendation R1): shown on its row and its page so
     * the reader knows BEFORE tapping into a book. Null for
     * sources that don't ride the session road at all.
     * v1.3.4 (T6): [viaBackup] appends the honest suffix
     * when the last answer came from a backup host. */
    fun stateWord(
        config: DeclarativeConfig?,
        hasSession: Boolean,
        lastErrorDetail: String?,
        viaBackup: Boolean = false
    ): String? {
        if (config == null) return null
        if (config.signInNeeded) {
            return if (hasSession) {
                if (viaBackup) "Signed in · via backup address" else "Signed in"
            } else "Sign in needed"
        }
        if (!config.needsRendering) {
            // Plain sources carry no state word (R1) — EXCEPT
            // the mirror fact (T6): answering through a backup
            // address is worth one honest line wherever it
            // happens.
            return if (viaBackup) "Working · via backup address" else null
        }
        if (SiteCheck.isChallengeDetail(lastErrorDetail)) {
            return "Check needed"
        }
        return if (viaBackup) "Working · via backup address" else "Working"
    }
}
