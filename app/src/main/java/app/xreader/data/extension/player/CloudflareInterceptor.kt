package app.xreader.data.extension.player

import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.Jsoup
import java.io.IOException

/**
 * K2 (Keiyoushi player): Cloudflare's challenge, routed to Xreader's
 * sanctioned screen.
 *
 * Adapted from Komikku's CloudflareInterceptor (Apache-2.0). The
 * DETECTION is the donor's, unchanged: a 403/503 from a Cloudflare
 * server whose body carries the challenge page's error elements.
 *
 * The substitution (named): the donor solves the challenge in a hidden
 * WebView and watches the WebView cookie jar for a fresh cf_clearance.
 * Xreader's invisible-engine rule forbids a hidden browsing surface —
 * the user must see Xreader's own UI — so the solve is handed to
 * [PlayerChallengeBridge], which opens the sanctioned solve-or-sign-in
 * screen (the one visible exception the user approved) and waits for
 * the clearance to land in SessionStore. The donor's other steps are
 * kept: the challenged response is closed, the stale cf_clearance is
 * dropped before solving, the original request is re-proceeded after a
 * solve, and any failure surfaces as an [IOException] (the only error
 * type OkHttp's async pipeline handles gracefully).
 */
class CloudflareInterceptor(
    private val cookieJar: SessionStoreCookieJar,
    private val bridge: PlayerChallengeBridge
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        if (!shouldIntercept(response)) {
            return response
        }
        try {
            response.close()
            cookieJar.removeCookie(request.url.host, CF_CLEARANCE_COOKIE)
            if (bridge.requestSolve(request.url)) {
                return chain.proceed(request)
            }
            throw IOException(
                "The site's check could not be completed for ${request.url.host}. " +
                    "Open the source once, finish the check on the screen it shows, " +
                    "then try again."
            )
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            // Because OkHttp's enqueue only handles IOExceptions, wrap
            // anything else so we don't crash the entire app (donor).
            throw IOException(e)
        }
    }

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

    private companion object {
        val ERROR_CODES = listOf(403, 503)
        val SERVER_CHECK = arrayOf("cloudflare-nginx", "cloudflare")
        const val CF_CLEARANCE_COOKIE = "cf_clearance"
    }
}
