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
    private val client: ExtensionHttpClient
) : HostHttp {

    override fun get(url: String, headers: Map<String, String>): HostResponse =
        unwrap(runBlocking { client.get(url, headers) })

    override fun post(
        url: String,
        body: String,
        contentType: String,
        headers: Map<String, String>
    ): HostResponse =
        unwrap(runBlocking { client.post(url, body, contentType, headers) })

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
    private val rateLimiters: MutableMap<Long, RateLimiter> = mutableMapOf()
) {
    fun contextFor(
        extensionRowId: Long,
        packageId: String,
        label: String,
        permissions: () -> ExtensionPermissions
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
        return object : SourceContext {
            override val http: HostHttp = HostHttpImpl(client)
            override val html: HostHtml = HostHtmlImpl()
            override val store: HostStore = HostStoreImpl(this@HostContextFactory.store, packageId)
            override val log: HostLog = HostLogImpl(label)
        }
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
}
