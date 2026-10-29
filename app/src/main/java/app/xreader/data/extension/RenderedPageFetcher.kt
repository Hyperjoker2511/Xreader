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
 * left its initial loading state.
 */
object RenderSettle {

    const val MIN_SETTLE_MS: Long = 1_200L
    const val POLL_INTERVAL_MS: Long = 400L

    /** W5.7: the view is destroyed after this long idle. */
    const val IDLE_DESTROY_MS: Long = 60_000L

    /** W5.3: 30 s on STANDARD/FULL devices, 45 s on MINIMAL ones. */
    fun hardTimeoutMs(tier: PerfTier): Long =
        if (tier == PerfTier.MINIMAL) 45_000L else 30_000L

    fun isSettled(samples: List<RenderSettleSample>): Boolean {
        val last = samples.lastOrNull() ?: return false
        val selectorCount = last.selectorCount
        if (selectorCount != null && selectorCount > 0 &&
            last.readyState != "loading"
        ) {
            return true
        }
        if (samples.size < 2) return false
        val previous = samples[samples.size - 2]
        return last.readyState == "complete" &&
            last.elapsedMs >= MIN_SETTLE_MS &&
            last.textLength == previous.textLength &&
            last.anchorCount == previous.anchorCount
    }
}
