package app.xreader.data.extension

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
    private val forceRender: Boolean = false
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
        rateLimiter.awaitTurn()
        return renderedFetcher.fetchRendered(url, sessionKey, permissions())
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
