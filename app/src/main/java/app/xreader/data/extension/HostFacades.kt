package app.xreader.data.extension

import app.xreader.core.error.AppResult
import app.xreader.extensionapi.HostDocument
import app.xreader.extensionapi.HostElement
import app.xreader.extensionapi.HostHtml
import app.xreader.extensionapi.HostHttp
import app.xreader.extensionapi.HostLog
import app.xreader.extensionapi.HostResponse
import app.xreader.extensionapi.HostStore
import app.xreader.extensionapi.SourceContext
import app.xreader.extensionapi.SourceException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * The host side of the extension API facades (PLAN §5). These are the
 * ONLY implementations extension code ever touches:
 *
 * - [HostHttpImpl] routes through [ExtensionHttpClient], so the domain
 *   whitelist, the user's session and the rate limit apply to every
 *   kind of source — declarative, JS and dex alike.
 * - [HostHtmlImpl] is jsoup behind the API's narrow document surface.
 * - [HostStoreImpl] is one JSON file inside the extension's own
 *   directory — keyed by the extension's identity at construction, so
 *   there is no way to even name another extension's store.
 */
class HostHttpImpl(
    private val fetcher: PageFetcher
) : HostHttp {

    /** Static-only construction (JS/dex sources, tests): the client
     * IS a [PageFetcher], so behaviour is exactly the pre-v1.0.6 path. */
    constructor(client: ExtensionHttpClient) : this(client as PageFetcher)

    override fun get(url: String, headers: Map<String, String>): HostResponse =
        unwrap(runBlocking { fetcher.get(url, headers) })

    override fun post(
        url: String,
        body: String,
        contentType: String,
        headers: Map<String, String>
    ): HostResponse =
        unwrap(runBlocking { fetcher.post(url, body, contentType, headers) })

    private fun unwrap(result: AppResult<HostResponse>): HostResponse =
        when (result) {
            is AppResult.Ok -> result.value
            is AppResult.Err -> throw SourceException(
                result.error.detail ?: result.error.toString()
            )
        }
}

class HostHtmlImpl : HostHtml {
    override fun parse(html: String, baseUrl: String): HostDocument =
        JsoupDocument(Jsoup.parse(html, baseUrl))
}

class JsoupDocument(private val document: Document) : HostDocument {
    override fun select(css: String): List<HostElement> =
        document.select(css).map { JsoupElement(it) }

    override fun selectFirst(css: String): HostElement? =
        document.selectFirst(css)?.let { JsoupElement(it) }

    override fun text(): String = document.text()

    /** The underlying jsoup document, for host-internal engines. */
    fun jsoup(): Document = document
}

class JsoupElement(private val element: Element) : HostElement {
    override fun text(): String = element.text()
    override fun html(): String = element.html()

    override fun attr(name: String): String? {
        val value = when (name.lowercase()) {
            // Resolve links/images against the page URL, like a browser.
            "href", "src" -> element.absUrl(name)
            else -> element.attr(name)
        }
        return value.ifBlank { element.attr(name).ifBlank { null } }
    }

    override fun select(css: String): List<HostElement> =
        element.select(css).map { JsoupElement(it) }

    override fun selectFirst(css: String): HostElement? =
        element.selectFirst(css)?.let { JsoupElement(it) }

    fun jsoup(): Element = element
}

class HostStoreImpl(
    private val store: ExtensionStore,
    private val packageId: String
) : HostStore {

    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()

    private fun readAll(): MutableMap<String, String> {
        val raw = store.readText(store.storeFile(packageId)) ?: return mutableMapOf()
        return runCatching {
            json.decodeFromString(JsonObject.serializer(), raw)
                .mapValues { (_, v) -> (v as? JsonPrimitive)?.content ?: "" }
                .toMutableMap()
        }.getOrDefault(mutableMapOf())
    }

    private fun writeAll(map: Map<String, String>) {
        val obj = buildJsonObject {
            map.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
        }
        store.writeText(store.storeFile(packageId), obj.toString())
    }

    override fun get(key: String): String? = synchronized(lock) { readAll()[key] }

    override fun put(key: String, value: String) {
        synchronized(lock) {
            val map = readAll()
            map[key] = value
            writeAll(map)
        }
    }

    override fun remove(key: String) {
        synchronized(lock) {
            val map = readAll()
            if (map.remove(key) != null) writeAll(map)
        }
    }
}

class HostLogImpl(private val tag: String) : HostLog {
    override fun log(message: String) {
        // android.util.Log is stubbed on the JVM; guard so host code
        // paths stay unit-testable.
        runCatching { android.util.Log.i("XreaderExt", "[$tag] $message") }
    }
}

/** Assembles the [SourceContext] an extension lives in. One per
 * extension — the scoping IS the sandbox boundary for store/session. */
class HostContextFactory(
    private val store: ExtensionStore,
    private val sessionStore: SessionStore,
    private val transport: app.xreader.data.net.HttpTransport,
    private val rateLimiters: MutableMap<Long, RateLimiter> = mutableMapOf(),
    /** v1.0.6 (W1): the browser-view fetcher + its runtime gates.
     * Both null in tests that don't exercise rendering — contexts
     * then stay on the static client, exactly as before. */
    val renderedFetcher: RenderedPageFetcher? = null,
    val renderRuntime: RenderRuntime? = null,
    /** TR4 sink: an unmarked source served a JS shell at runtime.
     * The container records it in prefs; nothing is flipped here. */
    private val onRuntimeShell: (extensionRowId: Long) -> Unit = {}
) {
    fun contextFor(
        extensionRowId: Long,
        packageId: String,
        label: String,
        permissions: () -> ExtensionPermissions,
        /** v1.0.6: live read of the source's `needsRendering` marker
         * (only declarative sources pass one). */
        needsRendering: () -> Boolean = { false }
    ): SourceContext {
        val limiter = synchronized(rateLimiters) {
            rateLimiters.getOrPut(extensionRowId) { RateLimiter() }
        }
        val client = ExtensionHttpClient(
            transport = transport,
            sessionStore = sessionStore,
            sessionKey = ExtensionHttpClient.sessionKeyFor(extensionRowId),
            label = label,
            whitelist = permissions,
            rateLimiter = limiter
        )
        val fetcher: PageFetcher = escalatingFetcherOrNull(
            client = client,
            sessionKey = ExtensionHttpClient.sessionKeyFor(extensionRowId),
            purpose = RenderPurpose.SOURCE,
            needsRendering = needsRendering,
            permissions = permissions,
            rateLimiter = limiter,
            onRuntimeShell = { onRuntimeShell(extensionRowId) }
        ) ?: client
        return object : SourceContext {
            override val http: HostHttp = HostHttpImpl(fetcher)
            override val html: HostHtml = HostHtmlImpl()
            override val store: HostStore = HostStoreImpl(this@HostContextFactory.store, packageId)
            override val log: HostLog = HostLogImpl(label)
        }
    }

    /**
     * v1.0.6 (W1.1): wraps [client] in the escalating decorator when
     * a renderer and a runtime are wired; null otherwise (callers
     * fall back to the client itself). Used by source contexts above
     * and by the probe family (LinkProber / SelectorAi), which build
     * their own clients via [clientFor].
     */
    fun escalatingFetcherOrNull(
        client: ExtensionHttpClient,
        sessionKey: String,
        purpose: RenderPurpose,
        needsRendering: () -> Boolean = { false },
        permissions: () -> ExtensionPermissions,
        rateLimiter: RateLimiter,
        onRuntimeShell: (() -> Unit)? = null,
        forceRender: Boolean = false
    ): EscalatingPageFetcher? {
        val renderer = renderedFetcher ?: return null
        val runtime = renderRuntime ?: return null
        return EscalatingPageFetcher(
            staticFetcher = client,
            renderedFetcher = renderer,
            sessionKey = sessionKey,
            purpose = purpose,
            needsRendering = needsRendering,
            permissions = permissions,
            rateLimiter = rateLimiter,
            gate = runtime.gate(),
            onRuntimeShell = onRuntimeShell,
            forceRender = forceRender
        )
    }

    /** A raw (context-free) client for probes and cover fetches. */
    fun clientFor(
        sessionKey: String,
        label: String,
        permissions: ExtensionPermissions,
        rateLimiter: RateLimiter = RateLimiter(minIntervalMs = 0)
    ): ExtensionHttpClient = ExtensionHttpClient(
        transport = transport,
        sessionStore = sessionStore,
        sessionKey = sessionKey,
        label = label,
        whitelist = { permissions },
        rateLimiter = rateLimiter
    )

    /**
     * v1.0.6.2: a raw client bound to an INSTALLED extension — its
     * own session key and parsed permissions, and the SAME rate
     * limiter its source context uses (the [rateLimiters] map is
     * the shared one [contextFor] draws from). This is the
     * byte-fetch twin of the context client: the context itself
     * only speaks text (the extension API is string-shaped), but
     * chapter images are host-side downloads, and they must travel
     * under exactly the whitelist/session/rate-limit the source's
     * page fetches travel under — never the raw transport.
     */
    fun sourceClientFor(
        extensionRowId: Long,
        label: String,
        permissions: ExtensionPermissions
    ): ExtensionHttpClient {
        val limiter = synchronized(rateLimiters) {
            rateLimiters.getOrPut(extensionRowId) { RateLimiter() }
        }
        return clientFor(
            sessionKey = ExtensionHttpClient.sessionKeyFor(extensionRowId),
            label = label,
            permissions = permissions,
            rateLimiter = limiter
        )
    }
}
