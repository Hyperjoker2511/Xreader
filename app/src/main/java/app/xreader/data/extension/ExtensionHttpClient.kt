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
    private val rateLimiter: RateLimiter,
    /** v1.2.0 (B1): byte fetches (chapter images) get their OWN,
     * much lighter limiter — the 350 ms page politeness interval
     * must not serialise a chapter's images one by one. Null =
     * this client makes its own (unshared) byte limiter; the
     * host factory passes a per-extension shared one so the
     * context client and the byte-fetch twin pace together. */
    byteRateLimiter: RateLimiter? = null
) : PageFetcher {

    private val byteLimiter: RateLimiter = byteRateLimiter
        ?: RateLimiter(minIntervalMs = BYTE_MIN_INTERVAL_MS)

    override suspend fun get(
        url: String,
        headers: Map<String, String>
    ): AppResult<HostResponse> = request("GET", url, null, null, headers)

    override suspend fun post(
        url: String,
        body: String,
        contentType: String,
        headers: Map<String, String>
    ): AppResult<HostResponse> = request("POST", url, body, contentType, headers)

    private suspend fun request(
        method: String,
        url: String,
        body: String?,
        contentType: String?,
        headers: Map<String, String>
    ): AppResult<HostResponse> =
        when (
            val raw = requestRaw(
                method, url, body, contentType, headers,
                ::readBody, PAGE_TOO_LARGE_MESSAGE,
                limiter = rateLimiter
            )
        ) {
            is AppResult.Err -> raw
            is AppResult.Ok -> AppResult.Ok(
                HostResponse(
                    status = raw.value.status,
                    body = raw.value.body,
                    headers = raw.value.headers,
                    finalUrl = raw.value.finalUrl
                )
            )
        }

    /**
     * v1.0.6.2: one GET for raw bytes (chapter images) through the
     * SAME guarded path as page fetches — the whitelist is checked
     * before the request and on every redirect hop, the saved
     * session is attached, the rate limit is honoured. Only the
     * body handling differs: bytes come back untouched instead of
     * being decoded as text (the text path would mangle binary).
     * Unlike page fetches, whose callers read the status
     * themselves, a non-2xx answer is an Err here: an image either
     * arrived or it didn't.
     */
    suspend fun getBytes(
        url: String,
        headers: Map<String, String> = emptyMap()
    ): AppResult<ByteArray> =
        when (
            val raw = requestRaw(
                "GET", url, null, null, headers,
                ::readByteBody, FILE_TOO_LARGE_MESSAGE,
                limiter = byteLimiter
            )
        ) {
            is AppResult.Err -> raw
            is AppResult.Ok ->
                if (raw.value.status in 200..299) {
                    AppResult.Ok(raw.value.body)
                } else {
                    AppResult.Err(
                        AppError.ExtensionFailed(
                            label, "the site answered HTTP ${raw.value.status}"
                        )
                    )
                }
        }

    /** One fetched response with its body already read by the
     * caller's reader ([readBody] for pages, [readByteBody] for
     * files) — the loop below is shared so the whitelist, session
     * and redirect handling exist in exactly one place. */
    private data class RawResponse<T>(
        val status: Int,
        val body: T,
        val headers: Map<String, String>,
        val finalUrl: String
    )

    private suspend fun <T> requestRaw(
        method: String,
        url: String,
        body: String?,
        contentType: String?,
        headers: Map<String, String>,
        readBody: (HttpTransport.Response) -> T?,
        tooLargeMessage: String,
        limiter: RateLimiter
    ): AppResult<RawResponse<T>> {
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
            limiter.awaitTurn()
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
                    // v1.3.1 (item 44): body reads run on IO,
                    // never on the caller's thread — on a
                    // trickling connection this loop is where a
                    // main-thread caller used to park.
                    val parsed = kotlinx.coroutines.withContext(
                        kotlinx.coroutines.Dispatchers.IO
                    ) { readBody(resp) }
                        ?: return AppResult.Err(
                            AppError.ExtensionFailed(label, tooLargeMessage)
                        )
                    return AppResult.Ok(
                        RawResponse(
                            status = resp.code,
                            body = parsed,
                            headers = resp.headers,
                            finalUrl = currentUrl
                        )
                    )
                }
            }
        }
    }

    /** The byte twin of [readBody]: same size cap, no decoding. */
    private fun readByteBody(response: HttpTransport.Response): ByteArray? {
        val stream = response.body ?: return ByteArray(0)
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(32 * 1024)
        var total = 0L
        stream.use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > MAX_BODY_BYTES) return null
                out.write(buffer, 0, read)
            }
        }
        return out.toByteArray()
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
        /** v1.2.0 (B1): the byte limiter's spacing — enough to
         * stay polite per host, light enough that a 30-image
         * chapter's floor is ~2 s of spacing, not ~10 s. */
        const val BYTE_MIN_INTERVAL_MS = 60L

        const val MAX_BODY_BYTES = 8L * 1024 * 1024
        const val MAX_REDIRECTS = 5
        const val PAGE_TOO_LARGE_MESSAGE = "the page was too large to load (over 8 MB)"
        const val FILE_TOO_LARGE_MESSAGE = "the file was too large to load (over 8 MB)"
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

        /** Session key for an installed extension row. */
        fun sessionKeyFor(extensionRowId: Long): String = "ext-$extensionRowId"

        /** Provisional session key while probing an add-from-link site. */
        fun linkProbeSessionKey(host: String): String = "linkadd_${host.normaliseHost()}"
    }
}
