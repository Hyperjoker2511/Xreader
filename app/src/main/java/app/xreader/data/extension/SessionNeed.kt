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
        // v1.5.0 (steer A8): the content itself named the
        // gate — the chapter text that came back was the
        // site's own "sign in to read" wall (the serve
        // gate's short-prose heuristic). That is positive
        // evidence from the requested content, exactly the
        // anonymous-first standard, so it classifies even
        // when the source never declared itself gated.
        if ("sign in to read" in text) return Kind.SIGN_IN
        // v1.5.0 (ANONYMOUS-FIRST, user rule 2026-11-02):
        // a render-road FAILURE is no longer evidence of a
        // check. A browser view that timed out, stalled or
        // never finished building proves only that the render
        // failed — sites whose content is public were being
        // asked for a solve they never demanded. A check is
        // offered only when the received page itself carried
        // the check's shape (the fetch layer converts a real
        // challenge page into SiteCheck's challenge detail,
        // which classifies above) or a mint demanded one
        // (the markers above). Nothing else infers a gate.
        return null
    }

    /**
     * WALL-EMPTY. An EMPTY catalogue is an Ok result, so
     * [classify] never ran on it — and on a truly walled
     * source, "empty" is what a wall looks like when the
     * rendered road got a challenge page with nothing
     * extractable on it (BrightNovel's Cloudflare
     * "Attention Required" 403, live-verified).
     *
     * v1.5.0 (ANONYMOUS-FIRST, user rule 2026-11-02 —
     * SUPERSEDES the v1.3.4 T4 rule): an empty catalogue
     * ALONE is not evidence of a wall. The v1.3.4 rule keyed
     * the solve offer on the source's gated nature alone, so
     * public-but-rendered sources whose catalogue came back
     * empty for ordinary reasons (a stalled render, a changed
     * listing) kept asking for a check the site never
     * demanded — "some sites are used without sign-in but the
     * app keeps asking for sign-in and still doesn't work".
     * The offer now needs the wall's own evidence:
     * [challengeSeen] — the page the catalogue fetch actually
     * received carried a challenge's markers (the caller
     * reads them from the source's recorded page shape), or
     * the fetch's failure detail was a challenge detail.
     *
     * And a session that EXISTS is never re-asked: when
     * [hasSession] is true the answer is null whatever the
     * evidence — the catalogue states the distinct
     * signed-in-but-empty truth instead (v1.5.0, A7ii: "no
     * session" and "session present but still empty" are
     * different states with different words). A passed check
     * is never asked for twice.
     */
    fun forEmptyCatalogue(
        config: DeclarativeConfig?,
        hasSession: Boolean,
        challengeSeen: Boolean = false
    ): Kind? {
        if (config == null) return null
        if (hasSession) return null
        if (config.needsRendering) {
            return if (challengeSeen) Kind.SITE_CHECK else null
        }
        if (config.signInNeeded) return Kind.SIGN_IN
        return null
    }

    /**
     * v1.5.0 (A7ii): the evidence the catalogue screen's
     * empty state is judged on — whether the page the last
     * catalogue fetch actually received carried a human
     * check's markers. Read from the source's recorded page
     * shapes (the fetch layer logs every received page's
     * markers against the source's session key); a recorded
     * challenge failure detail counts too. Pure over plain
     * values so the rule is testable without the log.
     */
    fun challengeSeenIn(
        markers: List<String>,
        lastErrorDetail: String?
    ): Boolean =
        markers.isNotEmpty() || SiteCheck.isChallengeDetail(lastErrorDetail)

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
