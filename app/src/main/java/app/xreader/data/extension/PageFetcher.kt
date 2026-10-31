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
    /** v1.2.1 (Defect 4): the shared road memory — hosts that
     * have proven they serve shells render first under the
     * escalate-on-shell mode, without a static re-probe. A
     * private default keeps standalone constructions (tests,
     * one-off probes) behaving exactly as before. */
    private val shellHosts: ShellHostMemory = ShellHostMemory()
) : PageFetcher {

    private val rendered = AtomicBoolean(false)

    /** True once any render has been attempted through this fetcher. */
    val renderedAny: Boolean get() = rendered.get()

    private fun inputs(): RenderInputs = RenderInputs(
        purpose = purpose,
        needsRendering = needsRendering(),
        tier = gate.tier(),
        userDriven = gate.userDriven(),
        backgroundWork = gate.backgroundWork(),
        masterEnabled = gate.masterEnabled()
    )

    override suspend fun get(
        url: String,
        headers: Map<String, String>
    ): AppResult<HostResponse> {
        val mode = RenderPolicy.mode(inputs())
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
        if (staticResult !is AppResult.Ok) return staticResult
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

    override suspend fun post(
        url: String,
        body: String,
        contentType: String,
        headers: Map<String, String>
    ): AppResult<HostResponse> = staticFetcher.post(url, body, contentType, headers)

    private suspend fun render(url: String): AppResult<HostResponse> {
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
        return renderedFetcher.fetchRendered(
            url, sessionKey, permissions(), hint
        )
    }

    /** Whether a rendered [body] actually contains the content
     * its wait-for [selector] promised. A selector that cannot
     * be run (or a body that cannot be parsed) is NOT evidence
     * the wait failed — the page is accepted, exactly as
     * before this check existed. */
    private suspend fun renderCarriesHint(
        body: String,
        url: String,
        selector: String
    ): Boolean = withContext(Dispatchers.Default) {
        runCatching {
            Jsoup.parse(body, url).select(selector).isNotEmpty()
        }.getOrDefault(true)
    }

    /** v1.0.7: could a render actually happen through this
     * fetcher right now? (The renderer being wired is a given —
     * this instance only exists then; this checks the POLICY:
     * master switch, background work, performance tier.) The
     * prober uses it to mark a failure "worth one rendered
     * retry" only when the retry could really render. */
    fun renderAvailable(): Boolean =
        RenderPolicy.mode(inputs()) != RenderMode.STATIC_ONLY
}
