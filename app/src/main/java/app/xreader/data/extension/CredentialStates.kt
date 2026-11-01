package app.xreader.data.extension

/**
 * v1.3.4 (session v2, T1): the four credential LIFECYCLES,
 * modelled separately because the sites themselves keep
 * them separate (TOKEN-SESSION-RESEARCH §1f: FenrirRealm's
 * own client never confuses read-token expiry with being
 * signed out — v1.3.3's loop came from Xreader conflating
 * them). A source's user-facing state word is a COMPOSITION
 * of these axes (see [SessionNeed.stateWord]); no single
 * axis may impersonate another:
 *
 *  - a read token expiring is [OperationCredentialState]
 *    business and NEVER surfaces as "Sign in needed";
 *  - a site being down is [UpstreamAvailabilityState] and
 *    never reads as a sign-in or check problem;
 *  - the human check ([BrowserVerificationState]) is owed
 *    to the SITE, once, in the sanctioned screen — its
 *    success is defined by the next native operation being
 *    accepted, not by the screen closing.
 */
enum class AccountSessionState {
    /** The source needs no account (or none has been probed). */
    NOT_APPLICABLE,
    /** No stored session for this source. */
    ABSENT,
    /** A session is stored (its cookies ride the fetches). */
    PRESENT,
    /** The site itself said the session is over (an
     * auth-shaped refusal on the mint/fetch) — signing in
     * again is the honest next step. */
    DEAD
}

enum class BrowserVerificationState {
    /** No check outstanding. */
    CLEAR,
    /** The site is asking for its human check (a challenge
     * page, or a mint's `challenge_required`). */
    CHECK_NEEDED
}

enum class OperationCredentialState {
    /** The source declares no operation token. */
    NOT_APPLICABLE,
    /** No token minted yet. */
    ABSENT,
    /** A token is held inside its renewal margin. */
    VALID,
    /** A mint is in flight (single-flight: callers share it). */
    MINTING,
    /** The held token is inside its renewal margin / expired. */
    STALE,
    /** The mint demanded the human check first. */
    CHALLENGE_REQUIRED,
    /** The mint was rate-limited; the shared cooldown runs. */
    RATE_LIMITED
}

enum class UpstreamAvailabilityState {
    /** The site is answering. */
    OK,
    /** 503/429 — the site asked the world to come back later. */
    TEMPORARILY_UNAVAILABLE,
    /** The site's address didn't resolve (DNS), or the host
     * couldn't be reached at all. */
    UNREACHABLE
}

/** The four axes at a moment, for diagnostics and the state
 * word composition. Every field defaults to its neutral
 * value so a plain cookie source composes exactly the words
 * it had before session v2 existed. */
data class CredentialSnapshot(
    val account: AccountSessionState = AccountSessionState.NOT_APPLICABLE,
    val verification: BrowserVerificationState = BrowserVerificationState.CLEAR,
    val operation: OperationCredentialState = OperationCredentialState.NOT_APPLICABLE,
    val availability: UpstreamAvailabilityState = UpstreamAvailabilityState.OK
)
