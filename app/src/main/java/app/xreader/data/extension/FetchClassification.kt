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
 *    v1.5.40: the whole not-answering family joins it — any
 *    5xx status, a timeout, a refused/reset connection —
 *    and its user words are "the site isn't answering
 *    right now", never the DNS sentence.
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

    /** An HTTP 5xx status as the fetch layers stamp it in a
     * detail ("the site answered HTTP 522", "(HTTP 503)").
     * The space after "http" keeps a URL in a detail from
     * ever matching. */
    private val HTTP_5XX = Regex("""http (5\d\d)\b""")

    /** The mint-refusal markers (diagnostics-grade; the UI
     * copy for them comes from [SessionNeed]'s words). */
    const val MINT_CHALLENGE_MARKER = "read-token challenge required"
    const val SESSION_DEAD_MARKER = "the sign-in session has ended"
    const val STAYED_LOCKED_MARKER = "stayed locked after a token mint"
    const val ENCRYPTED_MARKER = "served in a protected format"

    /** v1.5.5 (§3): the chapter arrived in a form the parser
     * does not support (an undeclared content_format, a
     * scrambled/obfuscated payload marker) — the honest
     * "this chapter type isn't supported yet" state, distinct
     * from locked (a mint cannot fix it) and from encrypted
     * (the T2 terminal shape above). Classified as LOCKED
     * below: an honest non-generic state, never a sign-in or
     * check demand. */
    const val UNSUPPORTED_MARKER = "chapter format not supported yet"

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
        // v1.5.40 (honest error words): the rest of the
        // not-answering family lands in the same state —
        //  - any other 5xx status in the detail (a 522, origin
        //    unreachable behind its proxy, used to fall through
        //    to GENERIC and could surface with address words
        //    while the site was simply down);
        //  - a timeout ("took too long" — the transport's
        //    TIMEOUT_DETAIL and the WebView phrase alike);
        //  - a refused/reset connection (the exception class
        //    names are the detail the transport maps);
        //  - the WebView "couldn't be reached" phrase.
        // A genuine DNS failure still classifies DNS above —
        // the two states keep their different sentences.
        if (HTTP_5XX.containsMatchIn(text) || "took too long" in text ||
            "connectexception" in text || "socketexception" in text ||
            "couldn't be reached" in text
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
        if (ENCRYPTED_MARKER in text || STAYED_LOCKED_MARKER in text ||
            UNSUPPORTED_MARKER in text
        ) {
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
