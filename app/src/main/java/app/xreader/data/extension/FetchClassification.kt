package app.xreader.data.extension

import app.xreader.core.error.AppError

/**
 * v1.3.4 (T3): failure classification v2 — ONE classifier
 * every leg (catalogue, book, chapters, text, search) reads,
 * so an honest state is the same state everywhere.
 *
 *  - [Kind.TEMPORARILY_UNAVAILABLE]: the site answered
 *    429/503 (or said so plainly) — a distinct state that
 *    carries the Retry-After when the site named one. A
 *    non-success status NEVER becomes an empty success.
 *  - [Kind.DNS_FAILURE]: the site's address didn't resolve —
 *    its own plain-words state ("couldn't find the site's
 *    address"), never a wall verdict, never a raw code.
 *  - [Kind.SESSION_CHECK] / [Kind.SIGN_IN]: the T1 lifecycle
 *    states, delegated to [SessionNeed]'s markers so the
 *    two classifiers can never disagree.
 *  - [Kind.LOCKED]: the content itself is locked (paid /
 *    stayed locked after a mint) — not an auth problem.
 *
 * Raw transport codes never appear in a classification —
 * they live only in the source's diagnostics.
 */
object FetchClassification {

    enum class Kind {
        SESSION_CHECK, SIGN_IN, LOCKED,
        TEMPORARILY_UNAVAILABLE, DNS_FAILURE, GENERIC
    }

    data class Result(
        val kind: Kind,
        val retryAfterSeconds: Long? = null,
        /** v1.3.4 (T3 refinement): within
         * TEMPORARILY_UNAVAILABLE, 429 and 503 are DIFFERENT
         * words — true = the site rate-limited us ("too many
         * requests"); false = the site itself is down-ish
         * ("temporarily unavailable"). Both honour
         * Retry-After; neither reads as empty/wall/failure. */
        val rateLimited: Boolean = false
    )

    /** The DNS marker detail the fetch layers stamp (plain
     * words — the UI shows it as-is). */
    const val DNS_MARKER = "couldn't find the site's address"

    /** The availability marker detail (plain words). */
    const val AVAILABILITY_MARKER = "temporarily unavailable"

    /** The mint-refusal markers (diagnostics-grade; the UI
     * copy for them comes from [SessionNeed]'s words). */
    const val MINT_CHALLENGE_MARKER = "read-token challenge required"
    const val SESSION_DEAD_MARKER = "the sign-in session has ended"
    const val STAYED_LOCKED_MARKER = "stayed locked after a token mint"
    const val ENCRYPTED_MARKER = "served in a protected format"

    fun classify(config: DeclarativeConfig?, error: AppError): Result =
        classifyDetail(config, error.detail, error)

    fun classifyDetail(
        config: DeclarativeConfig?,
        detail: String?,
        error: AppError? = null
    ): Result {
        val text = detail?.lowercase().orEmpty()
        if (error is AppError.RateLimited) {
            return Result(
                Kind.TEMPORARILY_UNAVAILABLE,
                error.retryAfterSeconds,
                rateLimited = "429" in text
            )
        }
        if (DNS_MARKER in text || "unknownhostexception" in text) {
            return Result(Kind.DNS_FAILURE)
        }
        if ("(http 429)" in text || "http 429" in text) {
            return Result(Kind.TEMPORARILY_UNAVAILABLE, rateLimited = true)
        }
        if (AVAILABILITY_MARKER in text || "(http 503)" in text ||
            "http 503" in text
        ) {
            return Result(Kind.TEMPORARILY_UNAVAILABLE)
        }
        if (MINT_CHALLENGE_MARKER in text ||
            SiteCheck.isChallengeDetail(detail)
        ) {
            return Result(Kind.SESSION_CHECK)
        }
        if (SESSION_DEAD_MARKER in text) {
            return Result(Kind.SIGN_IN)
        }
        // The session kinds delegate to SessionNeed so both
        // classifiers read the same markers the same way.
        val sessionKind = SessionNeed.classify(config, detail, null)
        if (sessionKind == SessionNeed.Kind.SITE_CHECK) {
            return Result(Kind.SESSION_CHECK)
        }
        if (ENCRYPTED_MARKER in text || STAYED_LOCKED_MARKER in text) {
            return Result(Kind.LOCKED)
        }
        if (sessionKind == SessionNeed.Kind.SIGN_IN) {
            return Result(Kind.SIGN_IN)
        }
        if ("paid or locked" in text) {
            return Result(Kind.LOCKED)
        }
        return Result(Kind.GENERIC)
    }
}
