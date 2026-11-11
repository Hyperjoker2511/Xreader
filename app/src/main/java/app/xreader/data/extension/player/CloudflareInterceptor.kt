package app.xreader.data.extension.player

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import java.io.IOException

/**
 * K2 (Keiyoushi player): Cloudflare's challenge, split by class.
 *
 * Adapted from Komikku's CloudflareInterceptor (Apache-2.0). The
 * DETECTION is the donor's, unchanged: a 403/503 from a Cloudflare
 * server whose body carries the challenge page's error elements.
 *
 * v1.5.4 (WS-1 item 6 — the challenge split, donor rule D5):
 * the donor-detected class is the JS/captcha class — the one the
 * donor's detection already isolates as needing no user. It is
 * AUTO-SOLVED in-process, donor-style ([PlayerWebViewAutoSolve]:
 * WebView work on the main executor, this OkHttp thread waiting
 * on a latch ≤ 30 s), the earned clearance is synced into the
 * session store, and the original request — POSTs included, the
 * Madara `admin-ajax.php` case — is replayed ONCE. On failure
 * the call throws the honest [IOException] below.
 *
 * Any OTHER Cloudflare-served error page (the managed-check
 * interstitial, the WAF block wall — a Cloudflare page, but not
 * the donor's captcha class) is the genuinely interactive
 * class: a content load NEVER waits on it (the pre-v1.5.4 shape
 * parked this thread up to 120 s per call on
 * [PlayerChallengeBridge.requestSolve], inside loads that
 * presented as a spinner). The load fails fast into the same
 * honest state, and the solve stays where it has always been
 * offered — the sanctioned screen, from the source's own
 * surface, user-initiated. The bridge's requestSolve remains
 * for exactly that path; it is no longer on any load path.
 *
 * A response that is neither class passes through untouched,
 * exactly as before.
 */
class CloudflareInterceptor(
    private val cookieJar: SessionStoreCookieJar,
    private val bridge: PlayerChallengeBridge,
    /** v1.5.12 (Front 1, S5): the awaited post-solve harvest —
     * injectable so gate tests can stand in for
     * [PlayerWebViewSync] (a hung harvest is a test seam, not
     * a device wait); production is the sync's awaited form.
     * Declared BEFORE [autoSolve] so existing trailing-lambda
     * constructions keep binding to the solver. */
    private val harvest: suspend (String) -> Unit =
        { host -> PlayerWebViewSync.harvestHostAwaited(host) },
    /** v1.5.12 (Front 1, S5): the harvest's bound — well under
     * the player client's 120 s call timeout, so a hung
     * harvest can never park this OkHttp dispatcher thread
     * for the whole call (see [awaitHarvestBounded]). */
    private val harvestTimeoutMs: Long = HARVEST_TIMEOUT_MS,
    /** The unattended solve for the donor class — injectable
     * so gate tests can stand in for the WebView; production
     * uses [PlayerWebViewAutoSolve]. */
    private val autoSolve: (Request) -> Boolean =
        { request -> PlayerWebViewAutoSolve.solve(request) }
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        if (shouldIntercept(response)) {
            return autoSolveAndReplay(chain, request, response)
        }
        if (isCloudflareWall(response)) {
            // The interactive class: fail fast, never wait.
            val host = request.url.host
            response.close()
            throw IOException(siteCheckMessage(host))
        }
        return response
    }

    /** The donor class: drop the stale clearance, auto-solve
     * unattended (≤30 s latch on this OkHttp thread), sync the
     * earned clearance into the store, replay once. */
    private fun autoSolveAndReplay(
        chain: Interceptor.Chain,
        request: Request,
        response: Response
    ): Response {
        try {
            response.close()
            // v1.5.2 fix (FIX-13): the stale-clearance drop clears
            // BOTH Cloudflare names (cf_clearance and __cf_bm),
            // and the jar clears them across every session record
            // for the host — reads merge across records, so a
            // player-record-only clear could leave a stale twin
            // in another record shadowing the fresh solve.
            cookieJar.removeCookies(
                request.url.host,
                listOf(CF_CLEARANCE_COOKIE, CF_BM_COOKIE)
            )
            bridge.noteAutoSolveStart(request.url)
            val solved = try {
                autoSolve(request)
            } finally {
                bridge.noteAutoSolveEnd()
            }
            if (solved) {
                // The clearance sits in the WebView jar; the
                // store is the truth every replayed request
                // reads its cookies from, so sync BEFORE the
                // replay, not in some later call's finally.
                // v1.5.11: AWAITED harvest, via runBlocking on
                // this OkHttp interceptor thread (blocking,
                // non-main, so awaiting is safe here). The
                // ordering is load-bearing — the just-earned
                // clearance must be IN the store before the
                // replay below reads it; never fire-and-forget
                // (harvestHost) at this site.
                // v1.5.12 (Front 1, S5): the await is BOUNDED
                // (see [awaitHarvestBounded]).
                awaitHarvestBounded(request.url.host)
                return chain.proceed(request)
            }
            throw IOException(siteCheckMessage(request.url.host))
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            // Because OkHttp's enqueue only handles IOExceptions, wrap
            // anything else so we don't crash the entire app (donor).
            throw IOException(e)
        }
    }

    /**
     * v1.5.12 (Front 1, S5): the post-solve harvest, awaited
     * under a bound. The v1.5.11 awaited shape had NO timeout
     * of its own: a hung harvest (a wedged vault write, a
     * stuck CookieManager) would park this OkHttp dispatcher
     * thread until the player client's 120 s call timeout
     * killed the whole call. The bound ([HARVEST_TIMEOUT_MS],
     * well under that timeout) caps the wait: on timeout the
     * replay proceeds WITHOUT the fresh harvest — the replay
     * may then re-challenge, which this flow already handles
     * honestly (the caller sees the check state and the
     * sanctioned solve stays available), and that is strictly
     * better than the pre-v1.5.11 fire-and-forget race this
     * await replaced. Internal (not private) so the gate can
     * pin the bound directly as well as through [intercept].
     */
    internal fun awaitHarvestBounded(host: String) {
        runBlocking {
            withTimeoutOrNull(harvestTimeoutMs) { harvest(host) }
        }
    }

    private fun siteCheckMessage(host: String): String =
        "The site's check could not be completed for $host. " +
            "Open the source once, finish the check on the screen it shows, " +
            "then try again."

    /** The donor's challenge detection, verbatim in substance. */
    private fun shouldIntercept(response: Response): Boolean {
        val server = response.header("Server")
        return if (response.code in ERROR_CODES && server != null && server in SERVER_CHECK) {
            val document = Jsoup.parse(
                response.peekBody(Long.MAX_VALUE).string(),
                response.request.url.toString()
            )
            // Solve only on captcha, not on geo block (donor).
            document.getElementById("challenge-error-title") != null ||
                document.getElementById("challenge-error-text") != null
        } else {
            false
        }
    }

    /**
     * The interactive wall class: a Cloudflare-served error
     * page that is NOT the donor's captcha class — the managed
     * check interstitial ("Just a moment…", challenge-platform)
     * or the WAF block page (cf-error-details). Identified by
     * Cloudflare's own page furniture, so a site's own 403 (or
     * a bare denial with no CF page) still passes through.
     */
    private fun isCloudflareWall(response: Response): Boolean {
        val server = response.header("Server")
        if (response.code !in ERROR_CODES || server == null || server !in SERVER_CHECK) {
            return false
        }
        val body = runCatching {
            response.peekBody(MAX_WALL_PEEK_BYTES).string()
        }.getOrNull() ?: return false
        return WALL_MARKERS.any { it in body }
    }

    // v1.5.12 (Front 1, S5): internal, not private, so the
    // gate test can assert the shipped harvest bound stays
    // well under the call timeout. Constants only.
    internal companion object {
        /** v1.5.12 (Front 1, S5): the post-solve harvest bound
         * — 10 s against the player client's 120 s call
         * timeout: long enough for a vault merge (millisecond
         * scale by design), short enough that a hung harvest
         * costs one possibly-rechallenged replay, never the
         * whole call. */
        const val HARVEST_TIMEOUT_MS = 10_000L
        val ERROR_CODES = listOf(403, 503)
        val SERVER_CHECK = arrayOf("cloudflare-nginx", "cloudflare")
        const val CF_CLEARANCE_COOKIE = "cf_clearance"
        const val CF_BM_COOKIE = "__cf_bm"
        const val MAX_WALL_PEEK_BYTES = 512L * 1024L
        val WALL_MARKERS = listOf(
            "cf-error-details",
            "challenge-platform",
            "Just a moment",
            "/cdn-cgi/styles/cf.errors"
        )
    }
}
