package app.xreader.data.extension

import app.xreader.core.error.AppError
import app.xreader.core.error.AppResult
import app.xreader.data.net.HttpTransport
import app.xreader.extensionapi.HostResponse
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URI

/**
 * Per-extension rate limiting: catalogue scraping shouldn't hammer a
 * small novel site (PLAN §5 — "politeness defaults"). One instance per
 * extension; the delay function is injectable for tests.
 */
class RateLimiter(
    private val minIntervalMs: Long = DEFAULT_MIN_INTERVAL_MS,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val delayMs: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) }
) {
    private val mutex = Mutex()
    private var lastRequestAt: Long = Long.MIN_VALUE / 2

    suspend fun awaitTurn() {
        val wait = mutex.withLock {
            val now = nowMs()
            val earliest = lastRequestAt + minIntervalMs
            lastRequestAt = maxOf(now, earliest)
            (earliest - now).coerceAtLeast(0)
        }
        if (wait > 0) delayMs(wait)
    }

    companion object {
        const val DEFAULT_MIN_INTERVAL_MS = 350L
    }
}

/**
 * THE security-critical class of Phase 3 (§9.1): every byte of network
 * traffic an extension causes goes through here, and the whitelist is
 * enforced inside it — checked before the request AND on every
 * redirect hop, because the underlying transport does not follow
 * redirects (the container wires it a no-redirect client). An
 * extension physically cannot call a non-whitelisted host: there is
 * simply no code path that skips the check.
 *
 * Also here: the saved login session is attached (cookies + the user's
 * browser UA), Set-Cookie responses are captured back into the
 * session, and requests are rate-limited per extension.
 */
class ExtensionHttpClient(
    private val transport: HttpTransport,
    private val sessionStore: SessionStore,
    /** KeyVault session key for this extension ("ext-<row id>"). */
    private val sessionKey: String,
    /** Human label for error messages ("NovelFull"). */
    private val label: String,
    private val whitelist: () -> ExtensionPermissions,
    private val rateLimiter: RateLimiter
) {

    suspend fun get(
        url: String,
        headers: Map<String, String> = emptyMap()
    ): AppResult<HostResponse> = request("GET", url, null, null, headers)

    suspend fun post(
        url: String,
        body: String,
        contentType: String,
        headers: Map<String, String> = emptyMap()
    ): AppResult<HostResponse> = request("POST", url, body, contentType, headers)

    private suspend fun request(
        method: String,
        url: String,
        body: String?,
        contentType: String?,
        headers: Map<String, String>
    ): AppResult<HostResponse> {
        var currentUrl = url
        var currentMethod = method
        var currentBody = body
        var hops = 0
        while (true) {
            val host = CookieParsing.hostOf(currentUrl)
                ?: return AppResult.Err(
                    AppError.ExtensionFailed(label, "‘$currentUrl’ isn't a valid web address")
                )
            // The whitelist gate — before anything leaves the device.
            if (!whitelist().allows(host)) {
                return AppResult.Err(
                    AppError.ExtensionFailed(
                        label,
                        "blocked: this source isn't allowed to open $host. " +
                            "You can allow it from the source's page if you trust it."
                    )
                )
            }
            rateLimiter.awaitTurn()
            val session = sessionStore.load(sessionKey)
            val requestHeaders = LinkedHashMap(headers)
            session?.cookieHeaderFor(host)?.let { cookie ->
                if (requestHeaders.keys.none { it.equals("Cookie", ignoreCase = true) }) {
                    requestHeaders["Cookie"] = cookie
                }
            }
            session?.userAgent?.let { ua ->
                if (requestHeaders.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
                    requestHeaders["User-Agent"] = ua
                }
            }
            if (contentType != null && currentMethod == "POST") {
                requestHeaders.putIfAbsent("Content-Type", contentType)
            }
            val response = transport.execute(
                HttpTransport.Request(
                    url = currentUrl,
                    method = currentMethod,
                    body = currentBody,
                    headers = requestHeaders
                )
            )
            when (response) {
                is AppResult.Err -> return response
                is AppResult.Ok -> {
                    val resp = response.value
                    // Capture any cookies the site set. (The shared
                    // transport's header map keeps one value per name,
                    // so this catches the common single-cookie case;
                    // the login WebView capture is the complete path.)
                    val setCookies = resp.headers.entries
                        .filter { it.key.equals("set-cookie", ignoreCase = true) }
                        .mapNotNull { CookieParsing.parseSetCookie(it.value) }
                        .toMap()
                    if (setCookies.isNotEmpty()) {
                        sessionStore.mergeCookies(sessionKey, host, setCookies)
                    }
                    val location = resp.headers.entries
                        .firstOrNull { it.key.equals("location", ignoreCase = true) }
                        ?.value
                    if (resp.code in REDIRECT_CODES && location != null) {
                        if (hops >= MAX_REDIRECTS) {
                            return AppResult.Err(
                                AppError.ExtensionFailed(
                                    label, "the site kept redirecting — giving up"
                                )
                            )
                        }
                        hops++
                        val next = runCatching {
                            URI(currentUrl).resolve(location).toString()
                        }.getOrNull() ?: return AppResult.Err(
                            AppError.ExtensionFailed(label, "the site sent a broken redirect")
                        )
                        // Loop back: the whitelist check runs again for
                        // the new host before any request is made.
                        currentUrl = next
                        if (resp.code == 303) {
                            currentMethod = "GET"
                            currentBody = null
                        }
                        continue
                    }
                    val text = readBody(resp)
                        ?: return AppResult.Err(
                            AppError.ExtensionFailed(
                                label, "the page was too large to load (over 8 MB)"
                            )
                        )
                    return AppResult.Ok(
                        HostResponse(
                            status = resp.code,
                            body = text,
                            headers = resp.headers,
                            finalUrl = currentUrl
                        )
                    )
                }
            }
        }
    }

    private fun readBody(response: HttpTransport.Response): String? {
        val stream = response.body ?: return ""
        val builder = StringBuilder()
        val buffer = ByteArray(32 * 1024)
        var total = 0L
        stream.use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > MAX_BODY_BYTES) return null
                builder.append(String(buffer, 0, read, Charsets.UTF_8))
            }
        }
        return builder.toString()
    }

    companion object {
        const val MAX_BODY_BYTES = 8L * 1024 * 1024
        const val MAX_REDIRECTS = 5
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

        /** Session key for an installed extension row. */
        fun sessionKeyFor(extensionRowId: Long): String = "ext-$extensionRowId"

        /** Provisional session key while probing an add-from-link site. */
        fun linkProbeSessionKey(host: String): String = "linkadd_${host.normaliseHost()}"
    }
}
