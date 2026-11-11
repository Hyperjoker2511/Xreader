package app.xreader.data.extension.player

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.content.ContextCompat
import app.xreader.data.extension.CookieParsing
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.Request

/**
 * v1.5.4 (WS-1 item 6a): the in-process Cloudflare auto-solve —
 * the donor's WebViewInterceptor shape (Komikku
 * `WebViewInterceptor.kt` / `CloudflareInterceptor.kt`,
 * Apache-2.0), adapted to Xreader's store-as-truth cookie rule.
 *
 * The donor's inversion is the whole trick: the WebView is
 * created and driven on the MAIN executor in small posted
 * pieces, while the thread that WAITS on the latch is the
 * OkHttp dispatcher thread that hit the challenge. Main never
 * waits; the user is never summoned — this class is only for
 * the challenge class the donor's detection isolates as
 * needing no user (the JS/captcha class). The genuinely
 * interactive class never reaches here (the interceptor fails
 * it fast to the honest state; the sanctioned screen stays
 * the user's door for it, §5.8).
 *
 * Success is the donor's criterion, read from the WebView
 * cookie jar inline (donor-jar discipline, item 4): a
 * cf_clearance cookie for the host that DIFFERS from the one
 * present before the solve. On success the caller syncs the
 * jar into the session store (the store stays the truth)
 * before replaying the original request.
 */
object PlayerWebViewAutoSolve {

    /** §0.2 (pinned): the donor's `awaitFor30Seconds`. */
    const val CHALLENGE_AUTOSOLVE_TIMEOUT_MS = 30_000L

    private val ERROR_CODES = listOf(403, 503)
    private const val CLEARANCE_COOKIE = "cf_clearance"

    /**
     * Attempt the unattended solve for [request]'s URL.
     * Blocks the calling (OkHttp) thread for at most
     * [timeoutMs]. Returns true only when a FRESH cf_clearance
     * landed in the WebView jar. Any failure — no installed
     * context, WebView unavailable, timeout — is a plain
     * false; the caller turns it into the honest error.
     */
    fun solve(
        request: Request,
        timeoutMs: Long = CHALLENGE_AUTOSOLVE_TIMEOUT_MS
    ): Boolean {
        val context = PlayerWebViewSync.installedContext() ?: return false
        return runCatching {
            resolveWithWebView(context, request, timeoutMs)
        }.getOrDefault(false)
    }

    private fun resolveWithWebView(
        context: Context,
        originalRequest: Request,
        timeoutMs: Long
    ): Boolean {
        val executor = ContextCompat.getMainExecutor(context)
        val latch = CountDownLatch(1)
        val url = originalRequest.url.toString()

        val manager = CookieManager.getInstance()
        val oldClearance = clearanceOf(manager, url)
        // The donor drops the stale clearance before solving;
        // the jar copy dies here (the store copy is the
        // interceptor's own drop).
        if (oldClearance != null) {
            runCatching {
                manager.setCookie(
                    url,
                    "$CLEARANCE_COOKIE=; Expires=Thu, 01 Jan 1970 " +
                        "00:00:00 GMT; Path=/"
                )
                manager.flush()
            }
        }

        var webview: WebView? = null

        // Written on the main executor thread (the WebView
        // client callbacks), read here after the latch — the
        // latch's countDown/await pair provides the
        // happens-before edge for the success flag, exactly
        // as in the donor.
        var challengeFound = false
        var bypassed = false
        val headers = parseHeaders(originalRequest)

        executor.execute {
            val view = try {
                WebView(context)
            } catch (e: Exception) {
                latch.countDown()
                return@execute
            }
            webview = view
            view.settings.javaScriptEnabled = true
            view.settings.domStorageEnabled = true
            originalRequest.header("User-Agent")?.let {
                view.settings.userAgentString = it
            }
            view.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, finishedUrl: String) {
                    val clearance = clearanceOf(manager, url)
                    if (clearance != null && clearance != oldClearance) {
                        bypassed = true
                        latch.countDown()
                    }
                    if (finishedUrl == url && !challengeFound) {
                        // The first request didn't return the
                        // challenge, abort (donor).
                        latch.countDown()
                    }
                }

                override fun onReceivedHttpError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    errorResponse: WebResourceResponse?
                ) {
                    if (request?.isForMainFrame == true) {
                        if (errorResponse?.statusCode in ERROR_CODES) {
                            // Found the Cloudflare challenge page.
                            challengeFound = true
                        } else {
                            // Unlock thread, the challenge
                            // wasn't found.
                            latch.countDown()
                        }
                    }
                }
            }
            // loadUrl is a GET: the solve is host-scoped (the
            // clearance cookie is the prize), and the ORIGINAL
            // request — POSTs included — is what the
            // interceptor replays afterwards, donor-style.
            view.loadUrl(url, headers)
        }

        latch.await(timeoutMs, TimeUnit.MILLISECONDS)

        executor.execute {
            webview?.run {
                stopLoading()
                destroy()
            }
        }
        if (bypassed) {
            // The earned clearance is in the jar; the store is
            // synced by the caller before the replay.
            runCatching { manager.flush() }
        }
        return bypassed
    }

    private fun clearanceOf(manager: CookieManager, url: String): String? {
        val header = runCatching { manager.getCookie(url) }
            .getOrNull() ?: return null
        return CookieParsing.parseCookieHeader(header)[CLEARANCE_COOKIE]
    }

    /** The donor's header filter (WebViewInterceptor):
     * headers Chromium refuses in a WebView load are dropped. */
    private fun parseHeaders(request: Request): Map<String, String> {
        val unsafe = setOf(
            "content-length", "host", "trailer", "te", "upgrade",
            "cookie2", "keep-alive", "transfer-encoding", "set-cookie"
        )
        return request.headers
            .filter { (name, value) ->
                val n = name.lowercase()
                val v = value.lowercase()
                n !in unsafe && !n.startsWith("proxy-") &&
                    !(n == "connection" && v == "upgrade")
            }
            .groupBy(keySelector = { (name, _) -> name }) { (_, value) -> value }
            .mapValues { it.value.getOrNull(0).orEmpty() }
    }
}
