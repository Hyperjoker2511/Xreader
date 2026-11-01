package app.xreader.data.sync

import app.xreader.core.error.AppError
import app.xreader.core.error.AppResult
import app.xreader.data.net.HttpTransport
import app.xreader.data.net.LocalHttpPolicy

/**
 * One sync route (PLAN §7): a place that can hold the single sync
 * file. Drive, WebDAV, and (for backup) a user-picked local archive
 * all reduce to read/write of named byte blobs, so the engine above
 * them never cares which is in use.
 */
interface SyncBackend {
    val id: String
    val label: String

    /** The file's bytes, or null when nothing has been synced yet. */
    suspend fun read(name: String): AppResult<ByteArray?>

    /** Stores the file; returns the backend's revision token if any. */
    suspend fun write(name: String, bytes: ByteArray): AppResult<String?>

    companion object {
        const val SYNC_FILE_NAME = "xreader-sync.json"
    }
}

/**
 * WebDAV backend (Amendment 3 — ships in Phase 5, not later): the
 * same payload over plain WebDAV GET/PUT against a folder URL the
 * user runs himself (Nextcloud, a NAS, …). Credentials: username in
 * settings, password in the KeyVault — the backend receives them via
 * providers and never stores them.
 */
class WebDavBackend(
    private val transport: HttpTransport,
    private val baseUrlProvider: () -> String,
    private val credentialsProvider: suspend () -> Pair<String, String>?
) : SyncBackend {

    override val id: String = "webdav"
    override val label: String = "WebDAV"

    override suspend fun read(name: String): AppResult<ByteArray?> {
        policyRefusal()?.let { return AppResult.Err(it) }
        val url = fileUrl(name) ?: return AppResult.Err(badUrl())
        val response = when (val result = execute("GET", url, null)) {
            is AppResult.Err -> return result
            is AppResult.Ok -> result.value
        }
        return when (response.code) {
            in 200..299 -> {
                val bytes = runCatching { response.body?.readBytes() }.getOrNull()
                response.body?.close()
                AppResult.Ok(bytes)
            }
            404 -> {
                response.body?.close()
                AppResult.Ok(null)
            }
            401, 403 -> {
                response.body?.close()
                AppResult.Err(
                    AppError.AuthFailed("WebDAV", "HTTP ${response.code} — check the username and password")
                )
            }
            else -> {
                response.body?.close()
                AppResult.Err(httpError(response.code))
            }
        }
    }

    override suspend fun write(name: String, bytes: ByteArray): AppResult<String?> {
        policyRefusal()?.let { return AppResult.Err(it) }
        val url = fileUrl(name) ?: return AppResult.Err(badUrl())
        val response = when (
            val result = executeBytes("PUT", url, bytes)
        ) {
            is AppResult.Err -> return result
            is AppResult.Ok -> result.value
        }
        val etag = response.headers.entries
            .firstOrNull { it.key.equals("ETag", true) }?.value
        response.body?.close()
        return when (response.code) {
            in 200..299 -> AppResult.Ok(etag)
            401, 403 -> AppResult.Err(
                AppError.AuthFailed("WebDAV", "HTTP ${response.code} — check the username and password")
            )
            else -> AppResult.Err(httpError(response.code))
        }
    }

    /** Connection check for the settings screen: PROPFIND Depth 0 on
     * the folder. 207/200 = reachable; 401 = bad credentials. */
    suspend fun probe(): AppResult<Unit> {
        val url = baseUrlProvider().trim().trimEnd('/')
        if (url.isBlank()) return AppResult.Err(badUrl())
        LocalHttpPolicy.refusalFor(url)?.let { return AppResult.Err(it) }
        val credentials = credentialsProvider()
        val request = HttpTransport.Request(
            url = url,
            method = "PROPFIND",
            headers = mapOf("Depth" to "0"),
            basicAuth = credentials
        )
        val response = when (val result = transport.execute(request)) {
            is AppResult.Err -> return AppResult.Err(result.error)
            is AppResult.Ok -> result.value
        }
        val code = response.code
        response.body?.close()
        return when (code) {
            in 200..299 -> AppResult.Ok(Unit)
            401, 403 -> AppResult.Err(
                AppError.AuthFailed("WebDAV", "HTTP $code — check the username and password")
            )
            else -> AppResult.Err(httpError(code))
        }
    }

    private suspend fun execute(
        method: String,
        url: String,
        body: String?
    ): AppResult<HttpTransport.Response> {
        val credentials = credentialsProvider()
        return transport.execute(
            HttpTransport.Request(
                url = url,
                method = method,
                body = body,
                headers = if (method == "PUT") {
                    mapOf("Content-Type" to "application/json")
                } else {
                    emptyMap()
                },
                basicAuth = credentials
            )
        )
    }

    /** Byte-exact PUT (Phase 6 book files are binary; the JSON sync
     * payload goes through here too — same bytes it always sent). */
    private suspend fun executeBytes(
        method: String,
        url: String,
        body: ByteArray
    ): AppResult<HttpTransport.Response> {
        val credentials = credentialsProvider()
        return transport.execute(
            HttpTransport.Request(
                url = url,
                method = method,
                bodyBytes = body,
                headers = mapOf("Content-Type" to "application/octet-stream"),
                basicAuth = credentials
            )
        )
    }

    private fun fileUrl(name: String): String? {
        val base = baseUrlProvider().trim().trimEnd('/')
        if (base.isBlank() || !(base.startsWith("http://") || base.startsWith("https://"))) {
            return null
        }
        return "$base/$name"
    }

    /** v1.3.5 (P0): the folder address obeys [LocalHttpPolicy]
     * — the sync password rides these requests as Basic auth,
     * so a public plain-http folder is refused before anything
     * is sent. Home-network and on-device http stay allowed,
     * exactly like the AI-provider paths. */
    private fun policyRefusal(): AppError? =
        LocalHttpPolicy.refusalFor(baseUrlProvider().trim())

    private fun badUrl(): AppError =
        AppError.BadFile("the WebDAV address doesn't look right — check it in Sync & backup")

    private fun httpError(code: Int): AppError = when (code) {
        429 -> AppError.RateLimited("WebDAV")
        in 500..599 -> AppError.ProviderDown("WebDAV", code)
        else -> AppError.ProviderDown("WebDAV", code, retryable = false)
    }
}
