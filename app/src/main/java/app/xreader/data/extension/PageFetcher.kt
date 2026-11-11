package app.xreader.data.extension

import app.xreader.core.error.AppError
import app.xreader.core.error.AppResult
import app.xreader.extensionapi.HostResponse
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup

/**
 * v1.0.6 (W1.1): the suspend-shaped fetch seam under `HostHttpImpl`.
 * [ExtensionHttpClient] implements it directly (the static path);
 * [EscalatingPageFetcher] decorates it with the browser view. POSTs
 * are never rendered — forms stay on the static client exactly as
 * today.
 */
interface PageFetcher {

    suspend fun get(
        url: String,
        headers: Map<String, String> = emptyMap()
    ): AppResult<HostResponse>

    suspend fun post(
        url: String,
        body: String,
        contentType: String,
        headers: Map<String, String> = emptyMap()
    ): AppResult<HostResponse>
}

/** v1.3.2 (scope A1): the HTTP statuses that mean "the site
 * refused the plain request" — a block, a gate or a rate
 * refusal the browser view's session may pass. */
private val BLOCK_STATUSES = setOf(401, 403, 429, 503)

/** The same verdicts as they appear inside a transport
 * error's detail ("HTTP 403 for …"). */
private val BLOCK_STATUS = Regex("HTTP (401|403|429|503)\\b")

/**
 * v1.2.1 (Defect 4): the fetch layer's ROAD MEMORY — the set of
 * hosts that have actually SERVED a JavaScript shell to this
 * process. The standing order is plain road first, hidden
 * render only as fallback; but once a host has proven it
 * serves shells, re-proving it with a static fetch on every
 * later page is pure latency (the shell is a few KB that can
 * never parse), so later fetches to a proven host render
 * first. One instance is shared by every fetcher the host
 * context factory builds, so the memory survives the
 * per-operation fetcher instances. Process-lifetime only by
 * design: after a process restart a shell host pays ONE
 * static probe again — cheap, and it keeps the memory honest
 * if a site stops being a shell.
 */
class ShellHostMemory {

    private val hosts =
        java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** True when [url]'s host has served a shell before. */
    fun isProvenShell(url: String): Boolean {
        val host = hostOf(url) ?: return false
        return host in hosts
    }

    /** Record that [url]'s host served a shell. */
    fun record(url: String) {
        hostOf(url)?.let { hosts += it }
    }

    private fun hostOf(url: String): String? =
        runCatching { java.net.URI(url).host }
            .getOrNull()?.lowercase()?.removePrefix("www.")
}

/**
 * v1.0.6 (W1.1/W2): static fetch first; when [RenderPolicy] allows
 * and the static body parses as a JavaScript shell
 * ([LinkInference.isJsShell]), the SAME url is fetched once through
 * the browser view and the rendered page replaces the shell. For a
 * source marked `needsRendering` the static attempt is skipped
 * entirely (TR3: zero static requests).
 *
 * Session coherence (W1.4): renders go through the wrapped client's
 * own [RateLimiter] and the client's session key, so rendered and
 * static fetches share one rate budget and one cookie session.
 *
 * One instance serves one logical operation (a probe, a source), so
 * [renderedAny] can report "a render was attempted" to the caller —
 * the prober uses it for its honest copy and for marking the saved
 * config (TR1/Q5).
 */
class EscalatingPageFetcher(
    private val staticFetcher: PageFetcher,
    private val renderedFetcher: RenderedPageFetcher,
    private val sessionKey: String,
    private val purpose: RenderPurpose,
    private val needsRendering: () -> Boolean = { false },
    private val permissions: () -> ExtensionPermissions,
    private val rateLimiter: RateLimiter,
    private val gate: RenderGate,
    /** TR4: fired when an UNMARKED source serves a shell at runtime
     * (the caller records "this source now needs the browser view";
     * the config itself is never flipped silently). */
    private val onRuntimeShell: (() -> Unit)? = null,
    /** v1.0.7: the caller already KNOWS the static page cannot
     * prove itself (a probe whose plain drive failed on page
     * furniture) — render every fetch first, policy permitting.
     * RenderPolicy's own table is untouched: its PROBE mode
     * still never renders first on its own. */
    private val forceRender: Boolean = false,
    /** v1.0.8.1: the wait-for-content hint handed to every
     * render through this fetcher — a live read, like
     * [needsRendering]; the probe's lane drive supplies the
     * lane's declared selector. Null = no hint. */
    private val waitForSelector: () -> String? = { null },
    /** v1.5.8 (D1/F2): the source's DECLARED render budget — a
     * live read, threaded exactly like [waitForSelector]
     * (ExtensionService's config lambda → HostContextFactory →
     * here → fetchRendered's budget parameter). Null = no
     * declared budget: the progress-extended default governs. */
    private val renderBudgetMs: () -> Long? = { null },
    /** v1.2.1 (Defect 4): the shared road memory — hosts that
     * have proven they serve shells render first under the
     * escalate-on-shell mode, without a static re-probe. A
     * private default keeps standalone constructions (tests,
     * one-off probes) behaving exactly as before. */
    private val shellHosts: ShellHostMemory = ShellHostMemory(),
    /** v1.3.3 (V132-CHECK 21): the whole-escalation time
     * budget — see [RenderSettle.fetchBudgetMs]. Injectable
     * so the gate can pin the degraded state with a short
     * budget instead of waiting out the real one. */
    private val budgetMs: () -> Long = {
        RenderSettle.fetchBudgetMs(gate.tier())
    }
) : PageFetcher {

    private val rendered = AtomicBoolean(false)

    /** True once any render has been attempted through this fetcher. */
    val renderedAny: Boolean get() = rendered.get()

    private fun inputs(suppressed: Boolean = false): RenderInputs =
        RenderInputs(
            purpose = purpose,
            needsRendering = needsRendering(),
            tier = gate.tier(),
            userDriven = gate.userDriven(),
            // v1.5.1 (item 2): a suppressed fetch (the global
            // search fan-out) reads as background work to the
            // policy — static-only, never waiting on a render.
            backgroundWork = gate.backgroundWork() || suppressed,
            masterEnabled = gate.masterEnabled()
        )

    override suspend fun get(
        url: String,
        headers: Map<String, String>
    ): AppResult<HostResponse> {
        // v1.3.3 (V132-CHECK 21): the hard time budget over
        // the WHOLE escalation. Whatever the road below is
        // doing — a stalled static read, a render that never
        // settles, its retry — the caller is owed a terminal
        // answer inside the budget: the retryable timeout
        // failure, which gated sources classify as Check
        // needed (solve action) and every other surface shows
        // as a plain failure with Retry. An endless spinner
        // is the one forbidden outcome.
        val suppressed =
            kotlinx.coroutines.currentCoroutineContext()[RenderSuppression.Key] != null
        // v1.5.12 (Front 1, S1): the content-road render cap —
        // a chapter CONTENT fetch (the read path) carrying
        // [ContentFetchCap] never enters render-first or
        // full-render escalation through the shared WebView:
        // a reading tap must never place a 60–105 s WebView
        // load on the main thread. The capped road is the
        // static road plus the fetch bridge (see
        // [getContentCapped]); catalogue / book / chapter-list
        // fetches never carry the marker and keep their
        // existing escalation (they are how walled sites get
        // read at all).
        val contentCapped =
            kotlinx.coroutines.currentCoroutineContext()[ContentFetchCap.Key] != null
        // v1.5.8 (D1): the outer cap rises in step with a
        // declared render budget (effective ceiling + 45 s),
        // or it would silently re-impose the old limit over a
        // budget the render itself was granted.
        val declaredBudget = renderBudgetMs()
        val effectiveBudgetMs = if (declaredBudget != null) {
            maxOf(budgetMs(), declaredBudget + 45_000L)
        } else {
            budgetMs()
        }
        val budgeted = kotlinx.coroutines.withTimeoutOrNull(effectiveBudgetMs) {
            getEscalated(url, headers, suppressed, contentCapped)
        }
        return budgeted ?: AppResult.Err(
            AppError.ProviderDown(
                "source",
                retryable = true,
                detail = "couldn't load that page in time"
            )
        )
    }

    private suspend fun getEscalated(
        url: String,
        headers: Map<String, String>,
        suppressed: Boolean = false,
        contentCapped: Boolean = false
    ): AppResult<HostResponse> {
        // v1.5.12 (Front 1, S1): the content road never
        // renders — see [getContentCapped]. This branch runs
        // before every render-first / escalation decision
        // below, so no code path from a capped fetch can
        // reach [render] (and therefore fetchRendered).
        if (contentCapped) {
            return getContentCapped(url, headers)
        }
        val mode = RenderPolicy.mode(inputs(suppressed))
        if (forceRender && mode != RenderMode.STATIC_ONLY) {
            return render(url)
        }
        if (RenderPolicy.rendersFirst(mode)) {
            return render(url)
        }
        // v1.2.1 (Defect 4): the road memory. Under the
        // escalate-on-shell mode, a host that has ALREADY
        // served a shell in this process renders first — the
        // plain road was tried, it provably cannot parse
        // there, and paying it again on every page of the
        // session is the latency the user feels. The policy
        // leashes still bind: this branch is only reachable
        // in RENDER_ON_SHELL mode (master off, background
        // work, and the MINIMAL tier's tap-only rule have
        // already answered STATIC_ONLY above).
        //
        // Runtime reads only (purpose SOURCE): a PROBE keeps
        // its v1.0.7 render economy — the plain pass may
        // render the pasted page, but a book page's render
        // stays the rendered retry's to spend, so a shell
        // home page must not let the memory walk the whole
        // drive rendered in one unforced pass. (Probes still
        // RECORD proven hosts below, so the sources they
        // install open at full speed.)
        if (mode == RenderMode.RENDER_ON_SHELL &&
            purpose == RenderPurpose.SOURCE &&
            shellHosts.isProvenShell(url)
        ) {
            return render(url)
        }
        val staticResult = staticFetcher.get(url, headers)
        if (staticResult !is AppResult.Ok) {
            // v1.3.4 (T3): an availability park is a terminal,
            // honest answer — the site asked the world to wait
            // out its Retry-After. It NEVER escalates to a
            // render (a render would just spend the budget
            // re-asking a question the site already answered).
            if ((staticResult as AppResult.Err).error
                    is AppError.RateLimited
            ) {
                return staticResult
            }
            // v1.3.2 (scope A1/A6): a block-shaped transport
            // failure — the site refused the plain request
            // (401/403/429/503 in the error detail) — escalates
            // to the browser view when policy allows a render
            // at all. v1.3.1 returned the refusal untouched, so
            // a source whose catalogue squeaked through static
            // died at the chapter-text fetch with "check your
            // connection" wording for what was a site block.
            // Genuine connectivity failures (offline, DNS,
            // timeouts) never escalate: they are not blocks,
            // and their wording stays honest.
            val detail = (staticResult as AppResult.Err).error.detail ?: ""
            val blockShaped = BLOCK_STATUS.containsMatchIn(detail)
            if (blockShaped && mode != RenderMode.STATIC_ONLY) {
                val renderedResult = render(url)
                if (renderedResult is AppResult.Ok) return renderedResult
                val renderedDetail =
                    (renderedResult as AppResult.Err).error.detail
                // The render hit the check too: ITS error is
                // the actionable one (it offers the solve
                // screen); any other render failure leaves the
                // original refusal standing.
                if (SiteCheck.isChallengeDetail(renderedDetail)) {
                    return renderedResult
                }
            }
            return staticResult
        }
        // v1.3.4 (T3): STATUS BEFORE BODY. A 429/503 that is
        // NOT itself a challenge page is the site saying
        // "temporarily unavailable" — a distinct, honest
        // state (NovelBuddy's 503 JSON rendered into an
        // empty catalogue in v1.3.3 is the failure this
        // kills: the render of a JSON error payload parsed
        // as zero rows). The answer never escalates and
        // never becomes an empty success; the Retry-After,
        // when the site named one, parks the source on the
        // shared scheduler.
        val response = staticResult.value
        if ((response.status == 429 || response.status == 503) &&
            !SiteCheck.isChallengePage(response.body)
        ) {
            val retryAfter = response.headers.entries
                .firstOrNull {
                    it.key.equals("Retry-After", ignoreCase = true)
                }?.value?.trim()?.toLongOrNull()
            if (retryAfter != null) {
                SharedAvailability.cooldowns.record(
                    sessionKey, retryAfter, status = response.status
                )
            } else if (response.status == 429) {
                SharedAvailability.cooldowns.record(
                    sessionKey, ExtensionHttpClient.DEFAULT_429_COOLDOWN_SECONDS,
                    status = 429
                )
            }
            return AppResult.Err(
                AppError.RateLimited(
                    "source",
                    retryAfterSeconds = retryAfter,
                    detail = "the site is " +
                        "${FetchClassification.AVAILABILITY_MARKER} " +
                        "(HTTP ${response.status})"
                )
            )
        }
        // v1.3.2 (scope A1): an HTTP block status or a
        // challenge page that arrived AS a response (the
        // static client hands error statuses through as
        // responses) escalates the same way — the browser
        // view's session is the one road through a check.
        val blocked = response.status in BLOCK_STATUSES ||
            SiteCheck.isChallengePage(response.body)
        if (blocked && mode != RenderMode.STATIC_ONLY) {
            val renderedResult = render(url)
            if (renderedResult is AppResult.Ok) return renderedResult
            val renderedDetail =
                (renderedResult as? AppResult.Err)?.error?.detail
            if (SiteCheck.isChallengeDetail(renderedDetail)) {
                return renderedResult
            }
            // The render failed some other way: the static
            // block stands as the honest answer.
            return staticResult
        }
        val isShell = withContext(Dispatchers.Default) {
            runCatching {
                LinkInference.isJsShell(
                    Jsoup.parse(staticResult.value.body, url)
                )
            }.getOrDefault(false)
        }
        // Escalation is per FETCH (alreadyRendered = false):
        // this call escalates at most once by construction —
        // the rendered result is returned directly. [rendered]
        // is the fetcher-lifetime record the prober reads, NOT
        // a gate on later fetches: a shell site serves a shell
        // for EVERY page, and each one must be able to escalate.
        if (!RenderPolicy.shouldEscalate(mode, isShell, alreadyRendered = false)) {
            return staticResult
        }
        // v1.2.1 (Defect 4): this host has now PROVEN it
        // serves shells — remember the road so the session's
        // later fetches skip the static probe.
        shellHosts.record(url)
        if (purpose == RenderPurpose.SOURCE && !needsRendering()) {
            onRuntimeShell?.invoke()
        }
        return render(url)
    }

    /**
     * v1.5.12 (Front 1, S1): the CAPPED content road — static
     * fetch first, the WebView FETCH BRIDGE as the only
     * escalation, and NO full render ever (no [render] call,
     * so `fetchRendered` is unreachable from here by
     * construction). The bridge fetches through the parked
     * page's own network context with the saved session —
     * it builds no page for the target — so a walled or
     * shell-served chapter can still be read without a
     * 60–105 s main-thread WebView load.
     *
     * The honest states survive exactly as on the static-only
     * road: an availability park (429/503) is terminal, and
     * when the bridge cannot answer, the static road's own
     * answer stands — a block stays a block (the callers'
     * check-needed / sign-in-needed classification reads it
     * exactly as it reads a failed render's fallback on the
     * uncapped road, which also returns the static result).
     */
    private suspend fun getContentCapped(
        url: String,
        headers: Map<String, String>
    ): AppResult<HostResponse> {
        val staticResult = staticFetcher.get(url, headers)
        if (staticResult !is AppResult.Ok) {
            // The same block-shaped transport escalation as
            // the uncapped road (scope A1), minus the render:
            // the bridge is the only second road.
            if ((staticResult as AppResult.Err).error
                    is AppError.RateLimited
            ) {
                return staticResult
            }
            val detail = (staticResult as AppResult.Err).error.detail ?: ""
            if (BLOCK_STATUS.containsMatchIn(detail)) {
                bridgeFetch(url)?.let { return it }
            }
            return staticResult
        }
        val response = staticResult.value
        // STATUS BEFORE BODY (T3), unchanged: a non-challenge
        // 429/503 is the site's availability answer — terminal
        // on every road, never escalated.
        if ((response.status == 429 || response.status == 503) &&
            !SiteCheck.isChallengePage(response.body)
        ) {
            val retryAfter = response.headers.entries
                .firstOrNull {
                    it.key.equals("Retry-After", ignoreCase = true)
                }?.value?.trim()?.toLongOrNull()
            if (retryAfter != null) {
                SharedAvailability.cooldowns.record(
                    sessionKey, retryAfter, status = response.status
                )
            } else if (response.status == 429) {
                SharedAvailability.cooldowns.record(
                    sessionKey, ExtensionHttpClient.DEFAULT_429_COOLDOWN_SECONDS,
                    status = 429
                )
            }
            return AppResult.Err(
                AppError.RateLimited(
                    "source",
                    retryAfterSeconds = retryAfter,
                    detail = "the site is " +
                        "${FetchClassification.AVAILABILITY_MARKER} " +
                        "(HTTP ${response.status})"
                )
            )
        }
        val blocked = response.status in BLOCK_STATUSES ||
            SiteCheck.isChallengePage(response.body)
        if (blocked) {
            // v1.5.38 (Fix A, V1537-PROOF-FORENSICS §A): the
            // static answer is ALREADY a convicted challenge
            // and NO session exists for this key — the
            // bridge leg can only re-ask the same URL with
            // an empty cookie jar and harvest the same
            // challenge body, up to 30 s later. Return the
            // static answer NOW: the source throws its
            // challenge detail and the check offer surfaces
            // in ~1–3 s. With a session in existence, or a
            // block that is not a challenge, the bridge runs
            // exactly as before (the FenrirRealm pattern).
            if (SiteCheck.isChallengePage(response.body) &&
                !renderedFetcher.hasSavedSession(sessionKey)
            ) {
                return staticResult
            }
            bridgeFetch(url)?.let { return it }
            return staticResult
        }
        val isShell = withContext(Dispatchers.Default) {
            runCatching {
                LinkInference.isJsShell(
                    Jsoup.parse(staticResult.value.body, url)
                )
            }.getOrDefault(false)
        }
        if (isShell) {
            // The host has proven it serves shells — the road
            // memory still records it (the catalogue roads of
            // this host may render-first later, exactly as on
            // the uncapped road), and the runtime-shell signal
            // still fires; the CONTENT answer itself comes
            // from the bridge or stands as the shell (the
            // caller's parse then fails honestly, never a
            // silently empty chapter).
            shellHosts.record(url)
            if (purpose == RenderPurpose.SOURCE && !needsRendering()) {
                onRuntimeShell?.invoke()
            }
            bridgeFetch(url)?.let { return it }
            return staticResult
        }
        return staticResult
    }

    override suspend fun post(
        url: String,
        body: String,
        contentType: String,
        headers: Map<String, String>
    ): AppResult<HostResponse> = staticFetcher.post(url, body, contentType, headers)

    /**
     * v1.3.2 (scope A1/A2/A6): every render passes through
     * this verdict on its way out. A render that lands on a
     * challenge interstitial the engine couldn't pass alone
     * is NOT a page — it becomes the typed challenge failure
     * the hand-off offers the one-time solve screen for
     * (v1.3.1 handed the interstitial over as content, and
     * the catalogue parse then reported a dishonest empty
     * list). A render of a JSON endpoint (an API road behind
     * a check) comes back wrapped by the browser in a lone
     * <pre> — the JSON is unwrapped so the caller's parser
     * sees the payload it asked for.
     */
    private suspend fun render(url: String): AppResult<HostResponse> {
        // v1.5.1 (items 2 + 5): the containment memory. A
        // source whose render already failed once (or which
        // spent its attempt window) does NOT render again —
        // its fetch goes to the WebView fetch bridge with the
        // saved session. And a render that fails HERE earns
        // the strike and one immediate bridge attempt before
        // the failure is handed back, so a catalogue open
        // lands on the bridge in the same tap instead of
        // dead-ending on "never finished loading".
        if (!RenderHealth.renderAllowed(sessionKey)) {
            bridgeFetch(url)?.let { return it }
        }
        RenderHealth.noteAttempt(sessionKey)
        val settled = renderSettled(url)
        if (settled !is AppResult.Ok) {
            RenderHealth.recordFailure(sessionKey)
            bridgeFetch(url)?.let { return it }
            return settled
        }
        RenderHealth.recordSuccess(sessionKey)
        val result = settled
        val body = result.value.body
        // v1.3.4 (T4): the rendered road's page shape joins
        // the source's diagnostics too — for walled sources
        // the render is often the ONLY road that sees the
        // wall, and its markers are the evidence.
        PageShapeLog.record(
            PageShape(
                sessionKey = sessionKey,
                status = result.value.status,
                title = PageShapeLog.titleOf(body),
                markers = PageShapeLog.markersIn(body),
                host = CookieParsing.hostOf(result.value.finalUrl)
                    ?: CookieParsing.hostOf(url) ?: "",
                atMs = System.currentTimeMillis()
            )
        )
        if (SiteCheck.isChallengePage(body)) {
            return AppResult.Err(
                AppError.ProviderDown(
                    "source",
                    retryable = true,
                    detail = SiteCheck.challengeDetail()
                )
            )
        }
        val json = unwrapRenderedJson(body)
        return if (json != null) {
            AppResult.Ok(result.value.copy(body = json))
        } else {
            result
        }
    }

    /** The JSON a browser view wraps in a lone <pre> when it
     * renders an API URL directly; null when the body is a
     * real page. Pure string work — the payload must be the
     * pre's whole content and must look like JSON. */
    private fun unwrapRenderedJson(body: String): String? {
        val trimmed = body.trim()
        if (!trimmed.startsWith("<")) return null
        val doc = runCatching { Jsoup.parse(trimmed) }.getOrNull()
            ?: return null
        val pres = doc.select("pre")
        if (pres.size != 1) return null
        val text = pres.first()?.text()?.trim() ?: return null
        if (!text.startsWith("{") && !text.startsWith("[")) return null
        // The pre must be the page's only content — a real
        // page that quotes JSON in one block keeps its HTML.
        val bodyText = doc.body()?.text()?.trim() ?: return null
        return if (bodyText == text) text else null
    }

    private suspend fun renderSettled(url: String): AppResult<HostResponse> {
        rendered.set(true)
        val hint = waitForSelector()
        val first = renderOnce(url, hint)
        if (hint == null || first !is AppResult.Ok) return first
        if (renderCarriesHint(first.value.body, url, hint)) return first
        // v1.0.9.1: the render came back WITHOUT the content it
        // was told to wait for. That is a real outcome, not a
        // contradiction: the wait is capped (RenderSettle's
        // HINT_WAIT_MAX_MS) well inside the render's hard
        // timeout, so on a slow connection an app-shell page can
        // settle on its chrome alone — header/footer clear both
        // shell bands (anchors >= 5, text >= 120) while the data
        // the caller reads is still in flight — and the browser
        // view's own shell verdict passes that chrome as a built
        // page. The caller's parse then finds nothing and, for a
        // listing, reports a legitimately-looking EMPTY
        // catalogue (the chikari.moe v1.0.9 device failure: the
        // hint reached the renderer, the catalogue still came
        // back empty). A render that never produced its
        // waited-for content must not be handed over as content:
        // render once more (the retry's settle window is a fresh
        // wait, and the site's scripts are cached by then), and
        // if the content still isn't there, fail the fetch the
        // way the browser view itself fails a page that never
        // built itself — an honest, retryable error instead of
        // a silent empty page.
        val second = renderOnce(url, hint)
        if (second !is AppResult.Ok) return second
        if (renderCarriesHint(second.value.body, url, hint)) return second
        return AppResult.Err(
            AppError.ProviderDown(
                "browser view",
                retryable = true,
                detail = "the page never finished building itself"
            )
        )
    }

    private suspend fun renderOnce(
        url: String,
        hint: String?
    ): AppResult<HostResponse> {
        rateLimiter.awaitTurn()
        // v1.5.8 (D1/F2): the declared budget reaches the
        // fetcher's timeout parameter on every render.
        return renderedFetcher.fetchRendered(
            url, sessionKey, permissions(), hint, renderBudgetMs()
        )
    }

    /** Whether a rendered [body] actually contains the content
     * its wait-for [selector] promised. v1.5.1 (item 5,
     * adopted steer): a matched node must carry something —
     * non-empty text, an href of its own, or a book link
     * inside it; an empty shell node that merely EXISTS (a
     * placeholder the app shell rendered before its data
     * arrived) is not the content the wait promised. A
     * selector that cannot be run (or a body that cannot be
     * parsed) is NOT evidence the wait failed — the page is
     * accepted, exactly as before this check existed. */
    private suspend fun renderCarriesHint(
        body: String,
        url: String,
        selector: String
    ): Boolean = withContext(Dispatchers.Default) {
        renderCarriesHintCheck(body, url, selector)
    }

    /** v1.0.7: could a render actually happen through this
     * fetcher right now? (The renderer being wired is a given —
     * this instance only exists then; this checks the POLICY:
     * master switch, background work, performance tier.) The
     * prober uses it to mark a failure "worth one rendered
     * retry" only when the retry could really render. */
    fun renderAvailable(): Boolean =
        RenderPolicy.mode(inputs()) != RenderMode.STATIC_ONLY

    /** v1.5.1 (item 5): one bridge attempt for [url] through
     * the rendered fetcher, paying the same rate budget a
     * render pays. Only a bridge SUCCESS is returned — a
     * bridge failure or absence leaves the caller's own
     * verdict standing, never a second, vaguer error. */
    private suspend fun bridgeFetch(
        url: String
    ): AppResult<HostResponse>? {
        rateLimiter.awaitTurn()
        val bridged = runCatching {
            renderedFetcher.fetchViaBridge(url, sessionKey, permissions())
        }.getOrNull() ?: return null
        // v1.5.8 (D1 observability rider): a bridge-served
        // answer is one of the render road's three outcomes —
        // it is recorded under the source's session key like
        // the fetcher's own settled / timed-out records.
        if (bridged is AppResult.Ok) {
            DiagnosticLog.record("render", "bridge-served", sessionKey)
        }
        return bridged as? AppResult.Ok
    }
}

/** Attribute names NAMED IN a selector itself (`[data-page]`,
 * `[href~=x]` → data-page, href) — K4's guard set. */
private val SELECTOR_ATTRIBUTE = Regex("""\[\s*([A-Za-z0-9_-]+)""")

/**
 * v1.5.8 (K4): the render-carries check, pure for JVM proofs.
 * A matched node carries content when it has non-blank text, an
 * href of its own, a descendant anchor — OR an attribute NAMED
 * IN THE SELECTOR ITSELF present and non-blank on it (a payload
 * like BrightNovel's rides `data-page` with no text at all).
 * Selector-named attributes ONLY: no general attribute
 * acceptance. An unparseable body/selector is NOT evidence the
 * wait failed — the page is accepted (returns true).
 */
internal fun renderCarriesHintCheck(
    body: String,
    url: String,
    selector: String
): Boolean = runCatching {
    val namedAttributes = SELECTOR_ATTRIBUTE.findAll(selector)
        .map { it.groupValues[1] }
        .toList()
    Jsoup.parse(body, url).select(selector).any { node ->
        node.text().isNotBlank() ||
            node.attr("href").isNotBlank() ||
            node.select("a[href]").isNotEmpty() ||
            namedAttributes.any { name -> node.attr(name).isNotBlank() }
    }
}.getOrDefault(true)

/**
 * v1.5.1 (item 2): a coroutine-context marker meaning "this
 * fetch must never wait on the render tier". The global
 * search fan-out wraps each source's search in it: a sick
 * source (BrightNovel's never-settling render was the field
 * case) then contributes its static answer or nothing inside
 * its own strict timeout, and can no longer wedge the whole
 * search behind a 30–75 s render budget. [EscalatingPageFetcher]
 * reads the marker as background work for [RenderPolicy] —
 * static-only, the same leash background work already had.
 */
object RenderSuppression : kotlin.coroutines.CoroutineContext.Element {
    override val key: kotlin.coroutines.CoroutineContext.Key<RenderSuppression>
        get() = Key

    object Key : kotlin.coroutines.CoroutineContext.Key<RenderSuppression>
}

/**
 * v1.5.12 (Front 1, S1): a coroutine-context marker meaning
 * "this fetch is a chapter CONTENT fetch — cap it to the
 * static road + the fetch bridge; never full-render". The
 * Fenrir fetch-road forensics' fix-shape 1: a reading tap
 * must never place a 60–105 s WebView load on the main
 * thread, while catalogue / book / chapter-list fetches
 * (which never carry this marker) keep the full escalation
 * that walled sites are read through at all.
 * [EscalatingPageFetcher] reads the marker exactly like
 * [RenderSuppression]; [HostHttpImpl] installs it from
 * [ContentRoadCap] for the blocking source road.
 */
object ContentFetchCap : kotlin.coroutines.CoroutineContext.Element {
    override val key: kotlin.coroutines.CoroutineContext.Key<ContentFetchCap>
        get() = Key

    object Key : kotlin.coroutines.CoroutineContext.Key<ContentFetchCap>
}

/**
 * v1.5.12 (Front 1, S1): the BLOCKING seam of the content
 * cap. [DeclarativeSource.chapterContent] is blocking host
 * code (the Source API is blocking), so its content road
 * marks itself with this thread-local scope; [HostHttpImpl]
 * — the one bridge every declarative fetch crosses — reads
 * it on the calling thread and installs [ContentFetchCap]
 * into the fetcher call's coroutine context. Scoped strictly
 * to the chapter-content entry: details / catalogue /
 * chapter-list calls never run inside the scope, so their
 * roads are untouched. Nested scopes restore the outer
 * value, so a content fetch inside a content fetch stays
 * capped and nothing leaks past the chapter read.
 */
object ContentRoadCap {

    private val active = ThreadLocal<Boolean>()

    /** v1.5.12 gate fix (Group A): the learner's opt-out.
     * While set on the calling thread, [isActive] reads
     * false even inside a [runCapped] scope — the chapter
     * read's own wrapper still enters its cap scope, but
     * the bridge ([HostHttpImpl]) must not install the cap
     * for a test-drive read. A separate thread-local (not a
     * clearing of [active]) is what makes this work:
     * [DeclarativeSource.chapterContent] unconditionally
     * re-enters [runCapped], so merely clearing [active]
     * around the call would be undone by the read itself. */
    private val optedOut = ThreadLocal<Boolean>()

    /** True when the calling thread is inside a chapter
     * content read that has not opted out (see [runUncapped]). */
    fun isActive(): Boolean = active.get() == true && optedOut.get() != true

    /** Run [block] with the content cap marked on this
     * thread. */
    fun <T> runCapped(block: () -> T): T {
        val previous = active.get()
        active.set(true)
        try {
            return block()
        } finally {
            if (previous == null) {
                active.remove()
            } else {
                active.set(previous)
            }
        }
    }

    /** v1.5.12 gate fix (Group A): run [block] OUTSIDE the
     * content cap. The cap's target is the shipped reader's
     * reading tap; the learner's explicit add-site test-drive
     * (LinkProber) is not a reading tap — for JS-shell sites
     * the rendered road is the ONLY way its test read can
     * find chapter text, so a capped drive fails every such
     * site as NotProven and breaks the universal-source rule
     * (a pasted link must be provable). Only the drive's own
     * chapter test-reads run inside this scope; the shipped
     * reader's chapterContent stays capped. Nested scopes
     * restore the outer value. */
    fun <T> runUncapped(block: () -> T): T {
        val previous = optedOut.get()
        optedOut.set(true)
        try {
            return block()
        } finally {
            if (previous == null) {
                optedOut.remove()
            } else {
                optedOut.set(previous)
            }
        }
    }
}
