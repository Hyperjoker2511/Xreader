package app.xreader.data.extension

import app.xreader.core.error.AppResult
import app.xreader.data.prefs.PerfTier
import app.xreader.extensionapi.HostResponse

/**
 * v1.0.6 (W1.2): loads ONE page in the in-app browser view and returns
 * the fully-built HTML — the escape hatch for sites whose static HTML
 * is an empty JavaScript shell (D27/D28). The seam mirrors
 * [JsRuntime]: production is [WebViewRenderedFetcher] (device-bound),
 * tests use a fake, and JVM code never touches Android classes.
 *
 * Contract every implementation keeps (plan W5):
 *  - exactly one underlying view, calls strictly serialised;
 *  - the URL's host must pass [permissions] before anything loads;
 *  - session cookies are seeded from the [SessionStore] before the
 *    load and harvested back after it — the store stays the single
 *    source of truth, never a second cookie jar;
 *  - a bounded wait (settle rule + [RenderSettle.hardTimeoutMs]);
 *  - failures are typed [app.xreader.core.error.AppError]s, never
 *    raw exceptions.
 */
interface RenderedPageFetcher {

    suspend fun fetchRendered(
        url: String,
        sessionKey: String,
        permissions: ExtensionPermissions,
        waitForSelector: String? = null
    ): AppResult<HostResponse>
}

/**
 * One observation of the page while it builds itself. [elapsedMs] is
 * measured from the moment the initial document finished loading.
 */
data class RenderSettleSample(
    val readyState: String,
    val textLength: Int,
    val anchorCount: Int,
    /** Null when no early-exit selector was requested. */
    val selectorCount: Int?,
    val elapsedMs: Long
)

/**
 * The settle rule (plan W5.3), kept pure so the decision is testable
 * without a browser: the page is done when the document is complete
 * AND its visible text length and anchor count are unchanged across
 * two polls at least [MIN_SETTLE_MS] after load — or, when the caller
 * named a selector, the moment a match exists and the document has
 * left its initial loading state. v1.0.8.1: a sample still inside
 * the shell band (see [SHELL_TEXT_MIN]) is never settled — a stable
 * loading spinner is not a finished page.
 */
object RenderSettle {

    const val MIN_SETTLE_MS: Long = 1_200L
    const val POLL_INTERVAL_MS: Long = 400L

    /** v1.0.8.1: the shell band — a page under BOTH limits is
     * still a loading shell, not a settled page. Mirrors the
     * numeric bands in LinkInference.isJsShell. */
    const val SHELL_TEXT_MIN: Int = 120
    const val SHELL_ANCHORS_MIN: Int = 5

    /** v1.0.8.1: how long a wait-for selector may hold back
     * settling. App-shell data on a slow connection can take
     * well over the ~8 s seen on a fast one; the hard timeout
     * (30–45 s) still bounds the whole render. */
    const val HINT_WAIT_MAX_MS: Long = 25_000L

    /** W5.7: the view is destroyed after this long idle. */
    const val IDLE_DESTROY_MS: Long = 60_000L

    /** W5.3: 30 s on STANDARD/FULL devices, 45 s on MINIMAL ones. */
    fun hardTimeoutMs(tier: PerfTier): Long =
        if (tier == PerfTier.MINIMAL) 45_000L else 30_000L

    /** v1.3.3 (V132-CHECK 21): the WHOLE escalation's budget —
     * one static attempt, one render, its one settled retry,
     * and slack. A single rendered fetch already caps itself
     * at [hardTimeoutMs]; what was unbounded was the SUM of
     * the road (static wait + render + retry render + …),
     * which on a stalling site read as an endless spinner
     * (Ranovel). Every leg that travels the escalating road —
     * catalogue, book, list, text — inherits this cap from
     * the one shared fetcher, and expiry degrades to the
     * honest retryable "couldn't load in time" failure (which
     * the session-need classifier already maps to the solve
     * hand-off for gated sources). */
    fun fetchBudgetMs(tier: PerfTier): Long =
        hardTimeoutMs(tier) * 2 + 15_000L

    fun isSettled(samples: List<RenderSettleSample>): Boolean {
        val last = samples.lastOrNull() ?: return false
        val selectorCount = last.selectorCount
        if (selectorCount != null && selectorCount > 0 &&
            last.readyState != "loading"
        ) {
            return true
        }
        // v1.0.8.1: content-aware settling — stability is not
        // readiness. A loading spinner is perfectly stable, so a
        // sample still inside the shell band never counts as
        // settled, however long it sits unchanged; polling
        // continues until the hard timeout instead. The bands
        // are the numeric half of LinkInference.isJsShell's
        // definition (near-no text AND near-no links — either
        // one clearing its band means real content is present
        // and the stability rule below may settle the page).
        // This is the chikari.moe failure: the old rule declared
        // the spinner "settled" at ~2 s while the site's data
        // arrived at ~8 s, and every probe stage studied an
        // empty page.
        if (last.textLength < SHELL_TEXT_MIN &&
            last.anchorCount < SHELL_ANCHORS_MIN
        ) {
            return false
        }
        // v1.0.8.1: a caller that named a wait-for selector
        // means "not settled until it matches" — the stability
        // rule may not fire while the wanted content is absent.
        // The wait is capped ([HINT_WAIT_MAX_MS]): a selector
        // that never matches must not hold the render until the
        // hard timeout on every fetch.
        if (selectorCount != null && selectorCount <= 0 &&
            last.elapsedMs < HINT_WAIT_MAX_MS
        ) {
            return false
        }
        if (samples.size < 2) return false
        val previous = samples[samples.size - 2]
        return last.readyState == "complete" &&
            last.elapsedMs >= MIN_SETTLE_MS &&
            last.textLength == previous.textLength &&
            last.anchorCount == previous.anchorCount
    }
}
