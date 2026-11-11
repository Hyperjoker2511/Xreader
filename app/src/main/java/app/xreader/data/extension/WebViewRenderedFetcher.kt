package app.xreader.data.extension

import android.annotation.SuppressLint
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import app.xreader.core.error.AppError
import app.xreader.core.error.AppResult
import app.xreader.data.prefs.PerfTier
import app.xreader.extensionapi.HostResponse
import java.io.ByteArrayInputStream
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.Jsoup

/** v1.5.9 (Finding 2 §5.2): the allowed-hosts guard's refusal
 * detail — it NAMES the judged host, so the book page's error
 * card and Copy details convict the mechanism unaided (the
 * v1.5.8 wording named neither the host nor the book address,
 * and the stored-URL defect behind Finding 2 was invisible
 * from the device). Built in exactly one place so both guard
 * sites ([WebViewRenderedFetcher.fetchRendered] and
 * [WebViewRenderedFetcher.fetchInContext]) speak identically;
 * the construction is factored here precisely so a JVM test
 * can pin it — the fetcher itself is WebView-bound. */
internal fun renderedGuardDetail(host: String): String =
    "that page is on $host, outside this source's allowed sites"

/**
 * v1.0.6 (W1.2/W5): the production [RenderedPageFetcher] — ONE
 * reusable WebView, confined to the main thread, that loads a page,
 * waits for it to build itself ([RenderSettle]) and returns the
 * built HTML. This is an invisible engine: it has no UI surface at
 * all — no address bar, no chrome. v1.5.5 (§2/§3) narrows the
 * old blanket "no `addJavascriptInterface` (W5.6)" rule:
 * exactly ONE interface is now attached —
 * [InContextBridge.Bridge], app-owned, exposing only two
 * result callbacks (in-context fetch answers and read-token
 * harvests) — because Amendments 2 forbid reading fetch
 * results from evaluateJavascript's return value, and a bridge
 * resuming a CompletableDeferred is the only lawful channel.
 * Page scripts still get no other bridge into the app.
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
    private val tierProvider: () -> PerfTier,
    /** v1.5.5 (§2/§3): the app-foreground probe the in-context
     * roads consult before touching the shared view —
     * AppContainer wires RenderRuntime.appInForeground; the
     * default keeps direct constructions (tests) permissive. */
    private val foregroundProvider: () -> Boolean = { true }
) : RenderedPageFetcher {

    private val appContext: Context = context.applicationContext
    private val mutex = Mutex()
    private val mainHandler = Handler(Looper.getMainLooper())

    // v1.5.38 (§B mitigation): the bridge-attempt epoch. A
    // fetchViaBridge call snapshots it before queueing on
    // the mutex; cancelInFlightBridge() bumps it, and an
    // attempt whose snapshot no longer matches bails at
    // its next poll beat (or before it starts) instead of
    // holding the mutex out to its own timeout.
    private val bridgeEpoch =
        java.util.concurrent.atomic.AtomicInteger(0)

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
    ): AppResult<HostResponse> =
        fetchRendered(url, sessionKey, permissions, waitForSelector, null)

    override suspend fun fetchRendered(
        url: String,
        sessionKey: String,
        permissions: ExtensionPermissions,
        waitForSelector: String?,
        renderBudgetMs: Long?
    ): AppResult<HostResponse> {
        val host = CookieParsing.hostOf(url).orEmpty()
        if (host.isEmpty() || !permissions.allows(host)) {
            return AppResult.Err(
                AppError.AuthFailed(
                    "browser view",
                    renderedGuardDetail(host)
                )
            )
        }
        val session = runCatching { sessionStore.load(sessionKey) }.getOrNull()
        return mutex.withLock {
            // v1.5.8 (D1/F1+F2): the base clock is the tier's
            // hard timeout; the CEILING (2×) bounds the whole
            // render, and the declared budget (when the source
            // carries one) replaces the base inside
            // RenderProgress's decision — never above the
            // ceiling.
            val tier = tierProvider()
            val baseMs = RenderSettle.hardTimeoutMs(tier)
            val ceilingMs = RenderSettle.ceilingMs(tier)
            val startedAtMs = SystemClock.uptimeMillis()
            val outcome = withTimeoutOrNull(ceilingMs + MAIN_SLACK_MS) {
                withContext(Dispatchers.Main) {
                    renderOnMain(
                        url, session, permissions, waitForSelector,
                        baseMs, ceilingMs, renderBudgetMs, sessionKey
                    )
                }
            }
            // v1.5.8 (D1 observability rider): the render's
            // elapsed-vs-budget outcome is recorded under the
            // source's session key — settled at Xs, or timed
            // out at the ceiling (the bridge-served outcome is
            // recorded by the escalating fetcher's bridge leg).
            val elapsedSec =
                (SystemClock.uptimeMillis() - startedAtMs) / 1000
            when {
                outcome == null || outcome.timedOut ->
                    DiagnosticLog.record(
                        "render",
                        "render timed out at ceiling ${ceilingMs / 1000}s",
                        sessionKey
                    )
                outcome.html != null ->
                    DiagnosticLog.record(
                        "render", "render settled at ${elapsedSec}s",
                        sessionKey
                    )
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
        baseBudgetMs: Long,
        ceilingMs: Long,
        declaredBudgetMs: Long?,
        sessionKey: String
    ): RenderOutcome {
        cancelScheduledDestroy()
        val view = webView ?: createWebView().also { webView = it }
        // v1.5.8 (D1/F1): progress tracking for the deadline
        // decision — the start instant, and the last instant
        // the page demonstrably progressed (commit, readyState
        // / content change between samples, or a subresource
        // observed in shouldInterceptRequest, which runs off
        // the main thread — hence the atomic).
        val startedMs = SystemClock.uptimeMillis()
        val lastActivityMs =
            java.util.concurrent.atomic.AtomicLong(startedMs)
        // Hoisted so the finally can see it (v1.5.1 item 2:
        // a timed-out view is destroyed, not reused).
        var timedOut = false
        try {
            seedCookies(session)
            // v1.5.0 (steer A4): the session's captured UA
            // wins; without one, the pinned device UA (the
            // same string the HTTP road sends) — never a
            // third fingerprint.
            val ua = session?.userAgent?.takeIf { it.isNotBlank() }
                ?: PinnedUserAgent.current()
            ua?.let { view.settings.userAgentString = it }
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
                        // v1.3.4 (T3 refinement): only a
                        // MAIN-FRAME navigation away fails the
                        // page load. A subframe (an ad iframe, a
                        // tracker redirect) reaching for an
                        // unlisted host is simply refused —
                        // a dead subresource must never
                        // classify the page itself as failed
                        // (the NovelFire/DNS honesty rule).
                        if (request?.isForMainFrame == true) {
                            blocked = true
                        }
                        return true
                    }
                    return false
                }

                override fun onPageStarted(
                    view: WebView?,
                    url: String?,
                    favicon: android.graphics.Bitmap?
                ) {
                    // v1.5.0 (A2): the API-harvest observer —
                    // installed as the page starts so the
                    // page's own fetch/XHR traffic is recorded
                    // for the learner (see HarvestLog). It only
                    // OBSERVES: requests are never altered,
                    // blocked or replayed from the log.
                    view?.evaluateJavascript(HARVEST_HOOK, null)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    if (finishedAt == 0L) {
                        finishedAt = SystemClock.uptimeMillis()
                        // v1.5.8 (D1): a commit IS progress.
                        lastActivityMs.set(finishedAt)
                    }
                }

                // v1.5.0 (steer A5): settling may begin at
                // first commit (the DOMContentLoaded-class
                // moment) — a page whose load event hangs on
                // a slow subresource must not stall the whole
                // verdict budget waiting for onPageFinished.
                // The content-aware settle still decides when
                // the DOM is DONE; this only starts its clock.
                override fun onPageCommitVisible(
                    view: WebView?,
                    url: String?
                ) {
                    if (finishedAt == 0L) {
                        finishedAt = SystemClock.uptimeMillis()
                        // v1.5.8 (D1): a commit IS progress.
                        lastActivityMs.set(finishedAt)
                    }
                }

                // v1.5.1 (item 5): known ad / tracker
                // sub-resources are refused an empty response
                // — their scripts and pixels otherwise keep
                // the page's network busy long after the
                // catalogue arrived, delaying the settle and
                // churning in the background after a timeout.
                // Main-frame loads are never intercepted.
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    // v1.5.8 (D1/F1): a subresource load IS
                    // progress — the existing hook observes it
                    // for the deadline decision.
                    if (request != null && !request.isForMainFrame) {
                        lastActivityMs.set(SystemClock.uptimeMillis())
                    }
                    return interceptAds(request)
                }

                // v1.5.1 (item 2, adopted steer): if the
                // renderer process dies, the view is dead
                // weight — destroy the instance, fail THIS
                // fetch honestly (the poll loop sees the
                // error and settles), and tell the platform
                // we handled it. A dead renderer must never
                // take the app with it.
                override fun onRenderProcessGone(
                    view: WebView?,
                    detail: RenderProcessGoneDetail?
                ): Boolean {
                    if (loadError == null) {
                        loadError = "the browser view stopped working"
                    }
                    destroyWebViewSoon()
                    return true
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    if (request?.isForMainFrame == true && loadError == null) {
                        // v1.3.4 (T3): plain words only — a
                        // raw WebView code in the UI was the
                        // NovelFire "(error -2)" leak. The
                        // code itself goes to the diagnostics,
                        // where codes belong.
                        val code = error?.errorCode
                        if (code != null) {
                            DiagnosticLog.record(
                                "render",
                                "webview error $code for $url", sessionKey
                            )
                        }
                        loadError = WebViewErrorWords.phrase(code)
                    }
                }
            }
            view.loadUrl(url)
            val samples = mutableListOf<RenderSettleSample>()
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
                        // v1.5.8 (D1): a sample that CHANGED
                        // (readyState, text, anchors, selector)
                        // is progress at the sample's instant.
                        val previous = samples.lastOrNull()
                        if (previous == null ||
                            previous.readyState != sample.readyState ||
                            previous.textLength != sample.textLength ||
                            previous.anchorCount != sample.anchorCount ||
                            previous.selectorCount != sample.selectorCount
                        ) {
                            lastActivityMs.set(finishedAt + sample.elapsedMs)
                        }
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
                // v1.5.8 (D1/F1): the pure progress→deadline
                // decision, consulted EVERY iteration and
                // obeyed — a progressing page earns time up
                // to the ceiling; a stalled one gets its
                // verdict at the base budget (or one base
                // budget after its last activity).
                val decision = RenderProgress.evaluate(
                    RenderProgressInput(
                        committed = finishedAt != 0L,
                        readyState = samples.lastOrNull()?.readyState
                            ?: "loading",
                        lastActivityMs = lastActivityMs.get(),
                        nowMs = SystemClock.uptimeMillis(),
                        startedMs = startedMs,
                        baseBudgetMs = baseBudgetMs,
                        ceilingMs = ceilingMs,
                        declaredBudgetMs = declaredBudgetMs
                    )
                )
                if (decision is RenderDeadlineDecision.Verdict) {
                    timedOut = true
                    break
                }
            }
            // v1.5.1 (item 2): a timed-out page STOPS loading
            // here. Before this, the loop simply stopped
            // watching: the shared view kept pulling the
            // page's sub-resources in the background (the
            // uplink in the field screenshots), and the next
            // render inherited a still-busy view — the churn
            // that lagged, then froze, the whole app. The DOM
            // already built stays readable for the snapshot
            // below; the network does not continue.
            if (timedOut) {
                runCatching { view.stopLoading() }
            }
            var html: String? = null
            var pageError = loadError
            if (loadError == null && !blocked) {
                html = extractHtml(view)
                if (html == null) {
                    pageError = "the built page was too large to read"
                } else {
                    // v1.5.0 (A2): collect what the page's own
                    // scripts called while it built itself —
                    // the learner's API evidence.
                    depositHarvest(view, sessionKey)
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
        } catch (e: CancellationException) {
            // v1.5.1 (item 2): the caller left (screen closed,
            // search cancelled, budget expired) — the render
            // dies with its job and the view is destroyed, not
            // left loading behind the user's back.
            destroyWebViewSoon()
            throw e
        } finally {
            // v1.5.1 (item 2): a timed-out view is destroyed,
            // not reused — a page that never settled may be
            // wedged, and the next render deserves a clean one.
            if (timedOut) {
                destroyWebViewSoon()
            } else {
                scheduleDestroy()
            }
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
        // v1.5.0 (steer B1): real layout bounds for the
        // offscreen view. A never-measured WebView reports a
        // 0×0 viewport — Chromium throttles its timers and
        // requestAnimationFrame and can drop synthetic input,
        // which stalls exactly the JS clients this engine
        // exists to run. Measure/layout it once at the
        // device's size; it stays offscreen and invisible.
        runCatching {
            val metrics = appContext.resources.displayMetrics
            val width = metrics.widthPixels.coerceAtLeast(1)
            val height = metrics.heightPixels.coerceAtLeast(1)
            val widthSpec = View.MeasureSpec.makeMeasureSpec(
                width, View.MeasureSpec.EXACTLY
            )
            val heightSpec = View.MeasureSpec.makeMeasureSpec(
                height, View.MeasureSpec.EXACTLY
            )
            view.measure(widthSpec, heightSpec)
            view.layout(0, 0, width, height)
        }
        // v1.5.5 (§2/§3): the ONE bridge (see the class doc) —
        // attached at creation so parked fetches and token
        // harvests can report; it accepts results only and
        // exposes no app capability to the page.
        view.addJavascriptInterface(
            inContextBridge, InContextBridge.INTERFACE_NAME
        )
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
            // v1.3.5 (P0, session lifecycle): setCookie needs a
            // real URL — a bare hostname is not one, so the old
            // call could silently seed nothing. The Domain
            // attribute keeps the store's parent-domain
            // semantics (a jar saved for a parent applies to
            // its subdomains — SessionData.cookieHeaderFor).
            val (url, host) = seedCookieTarget(domain) ?: continue
            for ((name, value) in cookies) {
                manager.setCookie(url, "$name=$value; Domain=$host; Path=/")
            }
        }
        manager.flush()
    }

    private suspend fun harvestCookies(
        sessionKey: String,
        finalUrl: String,
        userAgent: String?
    ) {
        // v1.5.5 (§3.1a): flush BEFORE the harvest — the same
        // ordering the sanctioned screen's save path keeps, so
        // a cookie the view just earned is durable in the jar
        // before the store reads it back.
        runCatching { CookieManager.getInstance().flush() }
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

    /** v1.5.1 (item 5): refuse a sub-resource the extraction
     * never needs — a known ad/tracker host, or a font /
     * media payload (the catalogue's text and structure are
     * the cargo; fonts and media only keep the network and
     * the load event busy). Main-frame requests and the
     * page's own content hosts are never touched. */
    private fun interceptAds(
        request: WebResourceRequest?
    ): WebResourceResponse? {
        if (request == null || request.isForMainFrame) return null
        val host = request.url?.host
        val path = request.url?.path?.lowercase().orEmpty()
        val shedByType = SHED_EXTENSIONS.any { path.endsWith(it) }
        if (!shedByType &&
            (host == null || !AdBlockHosts.isBlocked(host))
        ) {
            return null
        }
        return WebResourceResponse(
            "text/plain", "utf-8", ByteArrayInputStream(ByteArray(0))
        )
    }

    // ── v1.5.5 (§2/§3): parked in-context fetch + token harvest ──

    /** The one JS bridge (see the class doc). Main-thread
     * confined like the view it is attached to. */
    private val inContextBridge = InContextBridge.Bridge()

    /** True while a parked load should carry the read-token
     * harvest hook (read by the park client's onPageStarted
     * fallback). Main thread. */
    private var harvestHookArmed: Boolean = false

    /** The URL the view was last parked on by [parkOnMain],
     * for warm reuse. Main thread. */
    private var parkedUrl: String? = null

    /** True once the parked document finished loading. */
    private var parkedLoaded: Boolean = false

    private val bridgeCallSeq =
        java.util.concurrent.atomic.AtomicLong(0)

    /**
     * v1.5.5 (§2): the render-backed endpoint fetch — see
     * [RenderedPageFetcher.fetchInContext]. Runs under the
     * fetcher's own mutex, so a walk's pages are strictly
     * sequential on the one warm view (the walk's per-source
     * mutex serialises walks; this serialises the view). The
     * park client's navigation guard plus the bridge timeout
     * keep a walk from stranding.
     */
    override suspend fun fetchInContext(
        url: String,
        parkUrl: String,
        sessionKey: String?,
        permissions: ExtensionPermissions,
        method: String,
        body: String?,
        headers: Map<String, String>
    ): AppResult<HostResponse>? {
        val host = CookieParsing.hostOf(url).orEmpty()
        if (host.isEmpty() || !permissions.allows(host)) {
            return AppResult.Err(
                AppError.AuthFailed(
                    "browser view",
                    renderedGuardDetail(host)
                )
            )
        }
        if (!foregroundProvider()) return null
        val session = sessionKey?.let {
            runCatching { sessionStore.load(it) }.getOrNull()
        }
        return mutex.withLock {
            if (!foregroundProvider()) return@withLock null
            withContext(Dispatchers.Main) {
                inContextFetchOnMain(
                    url, parkUrl, session, sessionKey,
                    permissions, method, body, headers
                )
            }
        }
    }

    /**
     * v1.5.5 (§3): the harvest visit — see
     * [RenderedPageFetcher.harvestTokenInContext]. Parks on
     * the reader page with the hook armed (document-start
     * where the WebView supports it, onPageStarted fallback),
     * then waits for the page's OWN mint to report through
     * the bridge.
     */
    override suspend fun harvestTokenInContext(
        parkUrl: String,
        sessionKey: String?,
        permissions: ExtensionPermissions,
        waitMs: Long
    ): TokenHarvestResult? {
        val host = CookieParsing.hostOf(parkUrl).orEmpty()
        if (host.isEmpty() || !permissions.allows(host)) return null
        if (!foregroundProvider()) return null
        val session = sessionKey?.let {
            runCatching { sessionStore.load(it) }.getOrNull()
        }
        return mutex.withLock {
            if (!foregroundProvider()) return@withLock null
            val deferred = inContextBridge.registerHarvest(host)
            try {
                withContext(Dispatchers.Main) {
                    parkOnMain(
                        parkUrl, session, permissions, armHook = true
                    )
                }
                val payload = withTimeoutOrNull(waitMs) {
                    deferred.await()
                } ?: return@withLock null
                val harvest = InContextBridge.parseHarvest(payload)
                    ?: return@withLock null
                FenrirHarvestSink.recordForHost(host, harvest)
                harvest
            } finally {
                inContextBridge.cancelHarvest(host)
            }
        }
    }

    /** Main thread: park (or reuse) the view on [parkUrl],
     * then run the bridge fetch for [url] and shape the
     * answer as a [HostResponse]. */
    private suspend fun inContextFetchOnMain(
        url: String,
        parkUrl: String,
        session: SessionData?,
        sessionKey: String?,
        permissions: ExtensionPermissions,
        method: String,
        body: String?,
        headers: Map<String, String>
    ): AppResult<HostResponse>? {
        parkOnMain(parkUrl, session, permissions, armHook = false)
        val view = webView ?: return null
        val callId = "xr-" + bridgeCallSeq.incrementAndGet()
        val deferred = inContextBridge.register(callId)
        try {
            view.evaluateJavascript(
                InContextBridge.fetchScript(
                    callId, url, method, body, headers
                ),
                null
            )
            val payload = withTimeoutOrNull(IN_CONTEXT_TIMEOUT_MS) {
                deferred.await()
            } ?: return AppResult.Err(
                AppError.ProviderDown(
                    "browser view",
                    retryable = true,
                    detail = "the in-page fetch did not answer in time"
                )
            )
            val result = InContextBridge.parseFetchResult(payload)
                ?: return AppResult.Err(
                    AppError.ProviderDown(
                        "browser view",
                        retryable = true,
                        detail = "the in-page fetch answered unreadably"
                    )
                )
            if (result.status == 0) {
                return AppResult.Err(
                    AppError.ProviderDown(
                        "browser view",
                        retryable = true,
                        detail = "the in-page fetch failed: " +
                            result.body.take(120)
                    )
                )
            }
            if (sessionKey != null) {
                harvestCookies(sessionKey, url, null)
            }
            val trimmed = result.body.trimStart()
            return AppResult.Ok(
                HostResponse(
                    status = result.status,
                    headers = if (
                        trimmed.startsWith("{") || trimmed.startsWith("[")
                    ) {
                        mapOf("Content-Type" to "application/json")
                    } else {
                        emptyMap()
                    },
                    body = result.body,
                    finalUrl = url
                )
            )
        } finally {
            inContextBridge.cancel(callId)
        }
    }

    /**
     * Main thread: leave the shared view parked on [parkUrl]
     * with the session's cookies seeded. Warm reuse when the
     * view already sits on the parked page (the §2 sequential
     * pages rule — no reload per page); otherwise a fresh
     * load behind a navigation guard: main-frame navigations
     * to a DIFFERENT HOST are refused, so a walk's page can
     * never be carried off-origin mid-walk. When [armHook]
     * is set, the read-token harvest hook rides the load.
     */
    private suspend fun parkOnMain(
        parkUrl: String,
        session: SessionData?,
        permissions: ExtensionPermissions,
        armHook: Boolean
    ) {
        cancelScheduledDestroy()
        val view = webView ?: createWebView().also { webView = it }
        seedCookies(session)
        val ua = session?.userAgent?.takeIf { it.isNotBlank() }
            ?: PinnedUserAgent.current()
        ua?.let { view.settings.userAgentString = it }
        harvestHookArmed = armHook
        if (armHook) installDocumentStartHookLocked(view)
        if (parkedUrl == parkUrl && parkedLoaded && view.url == parkUrl) {
            return
        }
        parkedUrl = parkUrl
        parkedLoaded = false
        view.stopLoading()
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val target = request?.url?.toString() ?: return false
                if (request?.isForMainFrame != true) return false
                // The navigation guard: the parked page may
                // not be carried OFF its host mid-walk (a
                // hijack/redirect chain to another site would
                // strand the walk's fetches on a foreign
                // origin). Same-host navigations — the park
                // load itself, canonical redirects, the app
                // shell's own routing — pass; in-context
                // fetch() works from any same-origin page, and
                // the fetcher's mutex means no other load can
                // interleave while a walk holds the view.
                val targetHost = CookieParsing.hostOf(target)
                val parkHost = CookieParsing.hostOf(parkUrl)
                return targetHost != null && parkHost != null &&
                    targetHost != parkHost
            }

            override fun onPageStarted(
                view: WebView?,
                url: String?,
                favicon: android.graphics.Bitmap?
            ) {
                view?.evaluateJavascript(HARVEST_HOOK, null)
                if (harvestHookArmed) {
                    // The old-WebView fallback for the token
                    // hook (document-start is attempted first
                    // in installDocumentStartHookLocked).
                    view?.evaluateJavascript(
                        InContextBridge.tokenHarvestHookScript(), null
                    )
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                if (url == parkUrl) parkedLoaded = true
            }

            override fun onRenderProcessGone(
                view: WebView?,
                detail: RenderProcessGoneDetail?
            ): Boolean {
                parkedLoaded = false
                destroyWebViewSoon()
                return true
            }
        }
        view.loadUrl(parkUrl)
        // The in-context fetch needs the page's origin + JS
        // context, not catalogue content: wait for the parked
        // document itself, bounded by the park timeout.
        val deadline = System.nanoTime() + PARK_TIMEOUT_MS * 1_000_000L
        while (!parkedLoaded && System.nanoTime() < deadline) {
            delay(150)
        }
        parkedLoaded = true
    }

    /** v1.5.5 (§3): best-effort document-start hook install.
     * The app carries no androidx.webkit dependency, so the
     * WebViewCompat call is reflective: where the class is
     * present (a transitive WebKit) and the feature supported,
     * the hook lands before any page script runs; everywhere
     * else the park client's onPageStarted injection is the
     * fallback (Amendments name both routes). Silent by
     * design — the fallback covers every failure here. */
    private fun installDocumentStartHookLocked(view: WebView) {
        runCatching {
            val compat = Class.forName("androidx.webkit.WebViewCompat")
            val feature = Class.forName("androidx.webkit.WebViewFeature")
            val featureName = feature
                .getField("DOCUMENT_START_SCRIPT").get(null) as String
            val supported = compat.getMethod(
                "isFeatureSupported", String::class.java
            ).invoke(null, featureName) as? Boolean ?: false
            if (!supported) return
            compat.getMethod(
                "addDocumentStartJavaScript",
                WebView::class.java, String::class.java, Set::class.java
            ).invoke(
                null, view,
                InContextBridge.tokenHarvestHookScript(),
                setOf("*")
            )
        }
    }

    /**
     * v1.5.1 (item 5): the fetch bridge — see
     * [RenderedPageFetcher.fetchViaBridge]. The view loads
     * the site's origin once (its own page, with the saved
     * session's cookies), then the page context fetches the
     * target URL with credentials and hands the body back.
     * No page is built for the target and nothing renders:
     * a source whose render struck out still answers its
     * catalogue through the session that already passed
     * the site's check.
     */
    /** v1.5.38 (Fix A): the store's real answer — see
     * [RenderedPageFetcher.hasSavedSession]. */
    override suspend fun hasSavedSession(
        sessionKey: String
    ): Boolean = runCatching { sessionStore.has(sessionKey) }
        .getOrDefault(true)

    /** v1.5.38 (§B mitigation): bump the bridge epoch —
     * the in-flight attempt bails at its next poll beat —
     * and stop the shared view's current load. Posted to
     * the main thread, where the view lives; harmless when
     * no attempt is running (the next attempt snapshots
     * the new epoch and proceeds normally). */
    override fun cancelInFlightBridge() {
        bridgeEpoch.incrementAndGet()
        mainHandler.post {
            runCatching { webView?.stopLoading() }
        }
    }

    override suspend fun fetchViaBridge(
        url: String,
        sessionKey: String,
        permissions: ExtensionPermissions
    ): AppResult<HostResponse>? {
        val host = CookieParsing.hostOf(url).orEmpty()
        if (host.isEmpty() || !permissions.allows(host)) return null
        val session = runCatching { sessionStore.load(sessionKey) }
            .getOrNull()
        // v1.5.38 (§B mitigation): snapshot the bridge
        // epoch BEFORE queueing on the mutex — a cancel
        // that lands while this attempt waits its turn (or
        // while it polls) retires it; see bridgeOnMain.
        val epoch = bridgeEpoch.get()
        return mutex.withLock {
            val text = withTimeoutOrNull(BRIDGE_TIMEOUT_MS) {
                withContext(Dispatchers.Main) {
                    bridgeOnMain(url, session, epoch)
                }
            }
            when {
                text == null -> null
                text == BRIDGE_ERROR -> AppResult.Err(
                    AppError.ProviderDown(
                        "browser view",
                        retryable = true,
                        detail = "the browser view couldn't fetch that page"
                    )
                )
                else -> {
                    val sep = text.indexOf('')
                    if (sep <= 0) {
                        null
                    } else {
                        harvestCookies(sessionKey, url, null)
                        AppResult.Ok(
                            HostResponse(
                                status = text.substring(0, sep)
                                    .toIntOrNull() ?: 200,
                                body = text.substring(sep + 1),
                                headers = emptyMap(),
                                finalUrl = url
                            )
                        )
                    }
                }
            }
        }
    }

    /** Runs entirely on the main thread. Returns the bridge
     * payload ("<status>\u0001<body>"), [BRIDGE_ERROR] when
     * the page's fetch failed, or null when the bridge
     * couldn't run (origin never committed, no answer in
     * time, oversized body) — null keeps the caller's own
     * verdict standing. */
    private suspend fun bridgeOnMain(
        url: String,
        session: SessionData?,
        epoch: Int
    ): String? {
        // v1.5.38 (§B mitigation): cancelled while queued
        // on the mutex — never start the WebView dance.
        if (bridgeEpoch.get() != epoch) return null
        cancelScheduledDestroy()
        val view = webView ?: createWebView().also { webView = it }
        try {
            seedCookies(session)
            val ua = session?.userAgent?.takeIf { it.isNotBlank() }
                ?: PinnedUserAgent.current()
            ua?.let { view.settings.userAgentString = it }
            val origin = originOf(url) ?: return null
            var rendererGone = false
            if (view.url == null || originOf(view.url!!) != origin) {
                var committed = false
                view.webViewClient = object : WebViewClient() {
                    // v1.5.1 (item 2): a dead renderer ends
                    // the bridge attempt (the caller keeps its
                    // own verdict) and the instance is
                    // destroyed — never left for the next call.
                    override fun onRenderProcessGone(
                        view: WebView?,
                        detail: RenderProcessGoneDetail?
                    ): Boolean {
                        rendererGone = true
                        destroyWebViewSoon()
                        return true
                    }

                    override fun onPageCommitVisible(
                        view: WebView?,
                        url: String?
                    ) {
                        committed = true
                    }

                    override fun onPageFinished(
                        view: WebView?,
                        url: String?
                    ) {
                        committed = true
                    }

                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? = interceptAds(request)
                }
                view.loadUrl(origin)
                val waitDeadline = SystemClock.uptimeMillis() +
                    BRIDGE_ORIGIN_WAIT_MS
                while (!committed && !rendererGone &&
                    bridgeEpoch.get() == epoch &&
                    SystemClock.uptimeMillis() < waitDeadline
                ) {
                    delayOnMain(200)
                }
                if (!committed || bridgeEpoch.get() != epoch) {
                    return null
                }
            }
            val urlLiteral = org.json.JSONObject.quote(url)
            val kick = "(function(){window.__xrBridge=null;" +
                "fetch($urlLiteral,{credentials:'include'})" +
                ".then(function(r){return r.text().then(" +
                "function(t){window.__xrBridge=r.status+" +
                "'\\u0001'+t;});})" +
                ".catch(function(e){window.__xrBridge=" +
                "'$BRIDGE_ERROR';});})()"
            evaluate(view, kick)
            val deadline = SystemClock.uptimeMillis() +
                BRIDGE_FETCH_WAIT_MS
            while (!rendererGone &&
                bridgeEpoch.get() == epoch &&
                SystemClock.uptimeMillis() < deadline
            ) {
                delayOnMain(250)
                val decoded = decodeJsString(
                    evaluate(view, "window.__xrBridge")
                ) ?: continue
                if (decoded.length > BRIDGE_MAX_CHARS) return null
                return decoded
            }
            return null
        } catch (e: CancellationException) {
            destroyWebViewSoon()
            throw e
        } finally {
            scheduleDestroy()
        }
    }

    /** scheme://host[:port]/ for a URL, or null. */
    private fun originOf(url: String): String? {
        val uri = runCatching { java.net.URI(url) }.getOrNull()
            ?: return null
        val scheme = uri.scheme ?: return null
        val host = uri.host ?: return null
        val port = if (uri.port > 0) ":${uri.port}" else ""
        return "$scheme://$host$port/"
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
        // v1.5.8 (D1/F3): the probe is timeout-guarded — a
        // wedged renderer yields "no sample this poll", never
        // a parked loop.
        val decoded = decodeJsString(
            RenderProbeGuard.guarded { evaluate(view, script) }
        ) ?: return null
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
        // v1.5.8 (D1/F3): timeout-guarded probe (see samplePage).
        val decoded = decodeJsString(
            RenderProbeGuard.guarded { evaluate(view, script) }
        ) ?: return false
        return (decoded.toIntOrNull() ?: 0) > 0
    }

    /** v1.5.0 (A2): read the harvest observer's record out
     * of the page and deposit it in [HarvestLog] under this
     * render's session key. Best-effort by design: a page
     * whose scripts blocked the observer simply leaves no
     * harvest, and the learner falls back to page rules. */
    private suspend fun depositHarvest(view: WebView, sessionKey: String) {
        val raw = evaluate(
            view,
            "JSON.stringify(window.__xrHarvest || [])"
        ) ?: return
        val decoded = decodeJsString(raw) ?: return
        val array = runCatching { org.json.JSONArray(decoded) }.getOrNull()
            ?: return
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val callUrl = item.optString("url")
            if (callUrl.isBlank()) continue
            val headers = mutableMapOf<String, String>()
            val headerObj = item.optJSONObject("headers")
            if (headerObj != null) {
                val keys = headerObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    headers[key] = headerObj.optString(key)
                }
            }
            HarvestLog.record(
                sessionKey,
                HarvestedCall(
                    url = callUrl,
                    method = item.optString("method", "GET"),
                    status = item.optInt("status", 0),
                    contentType = item.optString("ct").ifBlank { null },
                    requestHeaders = headers,
                    bodySample = item.optString("body"),
                    pageCookies = CookieParsing.parseCookieHeader(
                        item.optString("cookies")
                    )
                )
            )
        }
    }

    private suspend fun extractHtml(view: WebView): String? {
        // v1.5.8 (D1/F3): timeout-guarded probe (see samplePage).
        val raw = RenderProbeGuard.guarded {
            evaluate(
                view,
                "document.documentElement ? document.documentElement.outerHTML : ''"
            )
        } ?: return null
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

        /** v1.5.5 (§2/§3): the in-context budgets — one parked
         * document load, and one bridge fetch answer. */
        const val PARK_TIMEOUT_MS: Long = 20_000L
        const val IN_CONTEXT_TIMEOUT_MS: Long = 30_000L

        /** v1.5.1 (item 5): the fetch bridge's budgets — the
         * whole bridge answers well inside one render's. */
        const val BRIDGE_TIMEOUT_MS: Long = 30_000L
        const val BRIDGE_ORIGIN_WAIT_MS: Long = 10_000L
        const val BRIDGE_FETCH_WAIT_MS: Long = 20_000L

        /** A bridge body past this size is refused (the
         * caller keeps its render verdict) — evaluateJavascript
         * string returns are not a bulk channel. */
        const val BRIDGE_MAX_CHARS: Int = 4_000_000

        /** The page-context fetch's failure marker. */
        const val BRIDGE_ERROR: String = "ERR"

        /** v1.5.1 (item 5): payload types an extraction
         * render sheds — fonts and media. (Images are
         * already blocked by the view's own settings.) */
        val SHED_EXTENSIONS: List<String> = listOf(
            ".woff", ".woff2", ".ttf", ".otf", ".eot",
            ".mp4", ".webm", ".mov", ".m4v", ".mp3", ".m4a",
            ".ogg", ".wav", ".flac", ".avi", ".mkv"
        )

        /** v1.5.0 (A2): the harvest observer — wraps the
         * page's fetch and XMLHttpRequest, recording each
         * call's URL, method, status, content type, the
         * request headers the page's own client set, a
         * bounded body sample, and the page-visible cookies
         * into `window.__xrHarvest` for [depositHarvest].
         * Observation only: the originals are always called
         * through unchanged. */
        const val HARVEST_HOOK: String =
            "(function(){" +
                "if(window.__xrHarvestInstalled)return;" +
                "window.__xrHarvestInstalled=true;" +
                "window.__xrHarvest=[];" +
                "function rec(o){try{" +
                "if(window.__xrHarvest.length<60)window.__xrHarvest.push(o);" +
                "}catch(e){}}" +
                "function hdrs(h){var out={};try{if(!h)return out;" +
                "if(typeof Headers!=='undefined'&&h instanceof Headers){" +
                "h.forEach(function(v,k){out[k]=v;});}" +
                "else if(Array.isArray(h)){" +
                "h.forEach(function(p){out[p[0]]=p[1];});}" +
                "else if(typeof h==='object'){" +
                "Object.keys(h).forEach(function(k){out[k]=String(h[k]);});}" +
                "}catch(e){}return out;}" +
                "var of=window.fetch;" +
                "if(of){window.fetch=function(input,init){" +
                "var url=(typeof input==='string')?input:" +
                "((input&&input.url)||'');" +
                "var method=(init&&init.method)||" +
                "(input&&input.method)||'GET';" +
                "var headers=hdrs((init&&init.headers)||" +
                "(input&&input.headers));" +
                "var p=of.apply(this,arguments);" +
                "p.then(function(resp){try{" +
                "var clone=resp.clone();" +
                "clone.text().then(function(t){rec({" +
                "url:resp.url||url,method:method,status:resp.status," +
                "ct:resp.headers.get('content-type')||''," +
                "headers:headers,body:(t||'').slice(0,65536)," +
                "cookies:document.cookie||''});" +
                "}).catch(function(){});}catch(e){}})" +
                ".catch(function(){});" +
                "return p;};}" +
                "var oo=XMLHttpRequest.prototype.open," +
                "os=XMLHttpRequest.prototype.send," +
                "oh=XMLHttpRequest.prototype.setRequestHeader;" +
                "XMLHttpRequest.prototype.open=function(m,u){" +
                "this.__xr={method:m,url:u,headers:{}};" +
                "return oo.apply(this,arguments);};" +
                "XMLHttpRequest.prototype.setRequestHeader=" +
                "function(k,v){try{if(this.__xr)this.__xr.headers[k]=v;" +
                "}catch(e){}return oh.apply(this,arguments);};" +
                "XMLHttpRequest.prototype.send=function(){" +
                "var x=this;" +
                "x.addEventListener('load',function(){try{" +
                "var t='';" +
                "if(x.responseType===''||x.responseType==='text')" +
                "t=x.responseText||'';" +
                "else if(x.responseType==='json'&&x.response)" +
                "t=JSON.stringify(x.response);" +
                "rec({url:x.responseURL||(x.__xr&&x.__xr.url)||''," +
                "method:(x.__xr&&x.__xr.method)||'GET',status:x.status," +
                "ct:x.getResponseHeader('content-type')||''," +
                "headers:(x.__xr&&x.__xr.headers)||{}," +
                "body:(t||'').slice(0,65536)," +
                "cookies:document.cookie||''});" +
                "}catch(e){}});" +
                "return os.apply(this,arguments);};" +
                "})()"
    }
}

/**
 * v1.3.5 (P0, session lifecycle): the WebView URL + host a
 * stored cookie domain seeds through. CookieManager.setCookie
 * takes a URL; the session store keys jars by bare domain
 * ("books.example", sometimes ".example.com"), which is not
 * one. Returns (url, host), or null for a domain string with
 * no usable host in it. Pure, so the shape is testable
 * without a WebView.
 */
internal fun seedCookieTarget(domain: String): Pair<String, String>? {
    val host = domain.trim().trimStart('.')
        .substringBefore('/').lowercase()
    if (host.isEmpty() || host.contains(' ')) return null
    return "https://$host/" to host
}

/**
 * v1.3.4 (T3): WebView load errors in PLAIN WORDS. The raw
 * codes (WebViewClient.ERROR_* constants) are diagnostics —
 * they go to the DiagnosticLog at the failure site and never
 * into a user-facing string (the NovelFire catalogue card
 * showed "(error -2)" in v1.3.3; -2 is ERR_NAME_NOT_RESOLVED,
 * a DNS state with its own words and its own Retry).
 */
object WebViewErrorWords {

    fun phrase(errorCode: Int?): String = when (errorCode) {
        // ERROR_HOST_LOOKUP (-2): the name didn't resolve.
        -2 -> "${FetchClassification.DNS_MARKER} — the name " +
            "didn't resolve; check the address, or your " +
            "connection if other sites fail too"
        // ERROR_TIMEOUT (-8).
        -8 -> "the page took too long to load"
        // ERROR_CONNECT (-6) / ERROR_IO (-7): the host
        // couldn't be reached at all.
        -6, -7 -> "the site couldn't be reached — it may be " +
            "down, or the connection dropped"
        // ERROR_TOO_MANY_REDIRECTS (-9).
        -9 -> "the page kept redirecting — giving up"
        // ERROR_FAILED_SSL_HANDSHAKE (-11).
        -11 -> "the secure connection to the site failed"
        else -> "the page failed to load"
    }
}
