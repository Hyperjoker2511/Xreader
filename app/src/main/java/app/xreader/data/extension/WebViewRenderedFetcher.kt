package app.xreader.data.extension

import android.annotation.SuppressLint
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import app.xreader.core.error.AppError
import app.xreader.core.error.AppResult
import app.xreader.data.prefs.PerfTier
import app.xreader.extensionapi.HostResponse
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.Jsoup

/**
 * v1.0.6 (W1.2/W5): the production [RenderedPageFetcher] — ONE
 * reusable WebView, confined to the main thread, that loads a page,
 * waits for it to build itself ([RenderSettle]) and returns the
 * built HTML. This is an invisible engine: it has no UI surface at
 * all — no address bar, no chrome — and `addJavascriptInterface` is
 * never called (W5.6). Page scripts run (that's the point), but they
 * get no bridge into the app.
 *
 * Leashes (W5): strictly serial via a mutex; the per-source rate
 * budget is paid by the caller ([EscalatingPageFetcher]) before each
 * render; the view is destroyed after [RenderSettle.IDLE_DESTROY_MS]
 * idle and on memory pressure; images are blocked (covers come from
 * the static client); file/content access is off; the source's
 * whitelist is enforced before the load AND on every top-level
 * navigation; responses are capped at the static client's body cap.
 *
 * DEVICE-BOUND: JVM tests never instantiate this class — they use a
 * fake [RenderedPageFetcher] behind the same interface.
 */
class WebViewRenderedFetcher(
    context: Context,
    private val sessionStore: SessionStore,
    private val tierProvider: () -> PerfTier
) : RenderedPageFetcher {

    private val appContext: Context = context.applicationContext
    private val mutex = Mutex()
    private val mainHandler = Handler(Looper.getMainLooper())

    // ── main-thread confined ────────────────────────────────────
    private var webView: WebView? = null
    private var destroyRunnable: Runnable? = null

    private val memoryCallbacks = object : ComponentCallbacks2 {
        override fun onTrimMemory(level: Int) {
            if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
                destroyWebViewSoon()
            }
        }

        override fun onConfigurationChanged(newConfig: Configuration) = Unit

        override fun onLowMemory() = destroyWebViewSoon()
    }

    init {
        appContext.registerComponentCallbacks(memoryCallbacks)
    }

    override suspend fun fetchRendered(
        url: String,
        sessionKey: String,
        permissions: ExtensionPermissions,
        waitForSelector: String?
    ): AppResult<HostResponse> {
        val host = CookieParsing.hostOf(url).orEmpty()
        if (host.isEmpty() || !permissions.allows(host)) {
            return AppResult.Err(
                AppError.AuthFailed(
                    "browser view",
                    "that page is outside this source's allowed sites"
                )
            )
        }
        val session = runCatching { sessionStore.load(sessionKey) }.getOrNull()
        return mutex.withLock {
            val timeoutMs = RenderSettle.hardTimeoutMs(tierProvider())
            val outcome = withTimeoutOrNull(timeoutMs + MAIN_SLACK_MS) {
                withContext(Dispatchers.Main) {
                    renderOnMain(url, session, permissions, waitForSelector, timeoutMs)
                }
            }
            when {
                outcome == null -> AppResult.Err(
                    AppError.ProviderDown(
                        "browser view",
                        retryable = true,
                        detail = "the browser view couldn't load that page in time"
                    )
                )
                outcome.blockedNavigation -> AppResult.Err(
                    AppError.AuthFailed(
                        "browser view",
                        "the page tried to open a site this source isn't allowed to use"
                    )
                )
                outcome.error != null -> AppResult.Err(
                    AppError.ProviderDown(
                        "browser view",
                        retryable = true,
                        detail = outcome.error
                    )
                )
                outcome.html == null -> AppResult.Err(
                    AppError.ProviderDown(
                        "browser view",
                        retryable = true,
                        detail = "the browser view couldn't load that page"
                    )
                )
                else -> {
                    harvestCookies(sessionKey, outcome.finalUrl, outcome.userAgent)
                    // W5.3 timeout rule: a page that ran out of time
                    // still counts if what it built is a real page;
                    // a timed-out SHELL is a failure, not content.
                    // v1.0.8.1: the same verdict now applies to a
                    // snapshot that SETTLED while still a shell —
                    // with content-aware settling that should be
                    // rare, but a page that still reads as a shell
                    // is never content, however it got here.
                    val stillShell = withContext(Dispatchers.Default) {
                        runCatching {
                            LinkInference.isJsShell(
                                Jsoup.parse(outcome.html, outcome.finalUrl)
                            )
                        }.getOrDefault(false)
                    }
                    if (stillShell) {
                        AppResult.Err(
                            AppError.ProviderDown(
                                "browser view",
                                retryable = true,
                                detail = "the page never finished building itself"
                            )
                        )
                    } else {
                        AppResult.Ok(
                            HostResponse(
                                status = 200,
                                body = outcome.html,
                                headers = emptyMap(),
                                finalUrl = outcome.finalUrl
                            )
                        )
                    }
                }
            }
        }
    }

    private data class RenderOutcome(
        val html: String?,
        val finalUrl: String,
        val userAgent: String?,
        val blockedNavigation: Boolean = false,
        val timedOut: Boolean = false,
        val error: String? = null
    )

    /** Runs entirely on the main thread (the caller's withContext). */
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun renderOnMain(
        url: String,
        session: SessionData?,
        permissions: ExtensionPermissions,
        waitForSelector: String?,
        timeoutMs: Long
    ): RenderOutcome {
        cancelScheduledDestroy()
        val view = webView ?: createWebView().also { webView = it }
        try {
            seedCookies(session)
            session?.userAgent?.takeIf { it.isNotBlank() }?.let {
                view.settings.userAgentString = it
            }
            var blocked = false
            var finishedAt = 0L
            var loadError: String? = null
            view.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean {
                    val target = request?.url?.toString() ?: return false
                    val targetHost = CookieParsing.hostOf(target).orEmpty()
                    if (targetHost.isNotEmpty() &&
                        !permissions.allows(targetHost)
                    ) {
                        blocked = true
                        return true
                    }
                    return false
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    if (finishedAt == 0L) {
                        finishedAt = SystemClock.uptimeMillis()
                    }
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    if (request?.isForMainFrame == true && loadError == null) {
                        loadError =
                            "the page failed to load (error ${error?.errorCode})"
                    }
                }
            }
            view.loadUrl(url)
            val samples = mutableListOf<RenderSettleSample>()
            val deadline = SystemClock.uptimeMillis() + timeoutMs
            var timedOut = false
            var expanderClicked = false
            while (true) {
                delayOnMain(RenderSettle.POLL_INTERVAL_MS)
                if (blocked || loadError != null) break
                if (finishedAt != 0L) {
                    val sample = samplePage(
                        view, waitForSelector,
                        SystemClock.uptimeMillis() - finishedAt
                    )
                    if (sample != null) {
                        samples += sample
                        // v1.0.8.1: a book page that hides its
                        // chapter list behind a "show all
                        // chapters" expander gets ONE click
                        // before settling is allowed — the click
                        // changes the DOM, so this poll can no
                        // longer be the settled one. The latch
                        // sets on a HIT only: on app-shell sites
                        // the button does not exist until the
                        // data arrives, long after the shell
                        // document first reports "complete".
                        val clickedNow = if (!expanderClicked) {
                            clickExpander(view)
                        } else {
                            false
                        }
                        if (clickedNow) {
                            expanderClicked = true
                        } else if (RenderSettle.isSettled(samples)) {
                            break
                        }
                    }
                }
                if (SystemClock.uptimeMillis() >= deadline) {
                    timedOut = true
                    break
                }
            }
            var html: String? = null
            var pageError = loadError
            if (loadError == null && !blocked) {
                html = extractHtml(view)
                if (html == null) {
                    pageError = "the built page was too large to read"
                }
            }
            return RenderOutcome(
                html = html,
                finalUrl = view.url ?: url,
                userAgent = view.settings.userAgentString,
                blockedNavigation = blocked,
                timedOut = timedOut,
                error = pageError
            )
        } finally {
            scheduleDestroy()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView {
        val view = WebView(appContext)
        with(view.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            blockNetworkImage = true
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mediaPlaybackRequiresUserGesture = true
        }
        return view
    }

    /** Session cookies in (W5.5): the [SessionStore] stays the
     * single source of truth; the manager's jar is seeded from it
     * per render and harvested back after. */
    private fun seedCookies(session: SessionData?) {
        if (session == null) return
        val manager = CookieManager.getInstance()
        manager.setAcceptCookie(true)
        for ((domain, cookies) in session.cookies) {
            for ((name, value) in cookies) {
                manager.setCookie(domain, "$name=$value")
            }
        }
        manager.flush()
    }

    private suspend fun harvestCookies(
        sessionKey: String,
        finalUrl: String,
        userAgent: String?
    ) {
        val header = runCatching {
            CookieManager.getInstance().getCookie(finalUrl)
        }.getOrNull() ?: return
        val cookies = CookieParsing.parseCookieHeader(header)
        if (cookies.isEmpty()) return
        val cookieHost = CookieParsing.hostOf(finalUrl) ?: return
        runCatching {
            sessionStore.mergeCookies(
                sessionKey,
                cookieHost,
                cookies,
                userAgent
            )
        }
    }

    private suspend fun samplePage(
        view: WebView,
        waitForSelector: String?,
        elapsedMs: Long
    ): RenderSettleSample? {
        val selectorLiteral = waitForSelector?.let {
            org.json.JSONObject.quote(it)
        } ?: "null"
        val script = "(function(){var s=$selectorLiteral;" +
            "return document.readyState + '|' + " +
            "(document.body ? document.body.innerText.length : 0) + '|' + " +
            "document.querySelectorAll('a[href]').length + '|' + " +
            "(s ? document.querySelectorAll(s).length : -1);})()"
        val decoded = decodeJsString(evaluate(view, script)) ?: return null
        val parts = decoded.split('|')
        if (parts.size != 4) return null
        return RenderSettleSample(
            readyState = parts[0],
            textLength = parts[1].toIntOrNull() ?: return null,
            anchorCount = parts[2].toIntOrNull() ?: return null,
            selectorCount = parts[3].toIntOrNull()?.takeIf { it >= 0 },
            elapsedMs = elapsedMs
        )
    }

    /**
     * v1.0.8.1: click the page's chapter-list expander ("show
     * all…", "load more…") so the snapshot carries the expanded
     * list. Only buttons and non-navigating anchors are clicked —
     * a real href would take the view away from the page being
     * read. Returns true when at least one element was actually
     * clicked.
     */
    private suspend fun clickExpander(view: WebView): Boolean {
        val script = "(function(){var els=Array.from(" +
            "document.querySelectorAll('button, a[href=\"#\"], " +
            "a[href^=\"javascript:\"]'))" +
            ".filter(function(el){var t=(el.innerText||'')" +
            ".toLowerCase();return t.indexOf('show all')>=0||" +
            "t.indexOf('load more')>=0;});" +
            "els.forEach(function(b){b.click();});" +
            "return ''+els.length;})()"
        val decoded = decodeJsString(evaluate(view, script))
            ?: return false
        return (decoded.toIntOrNull() ?: 0) > 0
    }

    private suspend fun extractHtml(view: WebView): String? {
        val raw = evaluate(
            view,
            "document.documentElement ? document.documentElement.outerHTML : ''"
        ) ?: return null
        val decoded = decodeJsString(raw) ?: return null
        return decoded.takeIf {
            it.length <= ExtensionHttpClient.MAX_BODY_BYTES
        }
    }

    /** evaluateJavascript hands back a JSON-encoded string; unwrap it. */
    private fun decodeJsString(raw: String?): String? {
        if (raw == null || raw == "null") return null
        return runCatching {
            org.json.JSONTokener(raw).nextValue() as? String
        }.getOrNull()
    }

    private suspend fun evaluate(view: WebView, script: String): String? =
        suspendCancellableCoroutine { cont ->
            view.evaluateJavascript(script) { value ->
                if (cont.isActive) cont.resume(value)
            }
            cont.invokeOnCancellation { /* the callback checks isActive */ }
        }

    private suspend fun delayOnMain(ms: Long) {
        suspendCancellableCoroutine<Unit> { cont ->
            val runnable = Runnable {
                if (cont.isActive) cont.resume(Unit)
            }
            mainHandler.postDelayed(runnable, ms)
            cont.invokeOnCancellation { mainHandler.removeCallbacks(runnable) }
        }
    }

    private fun scheduleDestroy() {
        cancelScheduledDestroy()
        val runnable = Runnable {
            webView?.let { view ->
                runCatching { view.stopLoading() }
                runCatching { view.destroy() }
            }
            webView = null
            destroyRunnable = null
        }
        destroyRunnable = runnable
        mainHandler.postDelayed(runnable, RenderSettle.IDLE_DESTROY_MS)
    }

    private fun cancelScheduledDestroy() {
        destroyRunnable?.let { mainHandler.removeCallbacks(it) }
        destroyRunnable = null
    }

    private fun destroyWebViewSoon() {
        mainHandler.post {
            cancelScheduledDestroy()
            webView?.let { runCatching { it.destroy() } }
            webView = null
        }
    }

    private companion object {
        /** Slack over the settle budget for main-thread scheduling. */
        const val MAIN_SLACK_MS: Long = 15_000L
    }
}
