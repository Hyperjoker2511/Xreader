package app.xreader.data.net

import app.xreader.core.error.AppError
import app.xreader.core.error.AppResult
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.util.concurrent.TimeUnit

/**
 * The app's HTTP seam. Everything network-shaped goes through this
 * interface so the engine (downloader, OPDS) is unit-testable against
 * a fake, and so Phase 3's extension fetches inherit one client, one
 * timeout policy and one error mapping (PLAN §9.1).
 */
interface HttpTransport {

    data class Request(
        val url: String,
        val headers: Map<String, String> = emptyMap(),
        /** Sent as an HTTP Basic Authorization header when present. */
        val basicAuth: Pair<String, String>? = null,
        /** Byte offset for a Range resume request, when > 0. */
        val rangeStart: Long = 0,
        /** Phase 3: extension sources can POST (search endpoints, form
         * logins). GET is the default; Phase 2 callers are unaffected. */
        val method: String = "GET",
        val body: String? = null,
        /** Phase 6: a raw byte body (book-file sync uploads). Wins
         * over [body] when both are set — binary content must never
         * round-trip through a String. */
        val bodyBytes: ByteArray? = null
    )

    class Response(
        val code: Int,
        val headers: Map<String, String>,
        /** Streaming body — the caller owns and must close it. */
        val body: InputStream?,
        /** True when the server honoured a Range request (206). */
        val isPartialContent: Boolean
    ) {
        val contentLength: Long
            get() = headers.entries
                .firstOrNull { it.key.equals("Content-Length", true) }
                ?.value?.toLongOrNull() ?: -1
        val retryAfterSeconds: Long?
            get() = headers.entries
                .firstOrNull { it.key.equals("Retry-After", true) }
                ?.value?.toLongOrNull()
    }

    suspend fun execute(request: Request): AppResult<Response>

    companion object {
        /** The detail every timeout carries (v1.0.6: the
         * test-drives match on this exact text to tell a slow
         * connection apart from a site that answered badly —
         * by the time the error reaches them, the exception
         * itself has been unwrapped into a plain message). */
        const val TIMEOUT_DETAIL = "the server took too long to answer"

        /** Maps transport failures onto the app's error vocabulary. */
        fun ioError(e: Exception): AppError {
            // v1.3.5 (P0, slice E2): a PLATFORM cleartext refusal
            // (an UnknownServiceException naming cleartext) is
            // shown with the same plain words as the app's own
            // pre-flight refusal — never the raw platform
            // sentence. Since the platform config now permits
            // cleartext this should not occur on Xreader's own
            // clients; the branch keeps any stack that still
            // enforces the policy honest.
            if (LocalHttpPolicy.isCleartextRefusal(e)) {
                return AppError.BadFile(LocalHttpPolicy.REFUSAL_MESSAGE)
            }
            return when (e) {
                is UnknownHostException -> AppError.NoNetwork
                is SocketTimeoutException ->
                    AppError.ProviderDown("server", detail = TIMEOUT_DETAIL)
                // v1.3.1 (item 44): the total-call timeout (and a
                // cancelled call) surfaces as an interrupted IO —
                // same plain-words timeout family, never "unknown".
                is java.io.InterruptedIOException ->
                    AppError.ProviderDown("server", detail = TIMEOUT_DETAIL)
                else -> AppError.Unknown(e)
            }
        }

        fun statusError(code: Int): AppError = when (code) {
            401, 403 -> AppError.AuthFailed("server", "HTTP $code")
            404 -> AppError.ProviderDown("server", httpCode = 404, retryable = false)
            429 -> AppError.RateLimited("server")
            in 500..599 -> AppError.ProviderDown("server", httpCode = code)
            else -> AppError.ProviderDown("server", httpCode = code, retryable = false)
        }
    }
}

class OkHttpTransport(
    private val client: OkHttpClient = defaultClient(),
    /** v1.3.3 (V132-CHECK 24): the dispatcher this
     * transport's blocking calls park on. The extension
     * transport runs on [FetchLanes.static] — its own lane,
     * isolated from rendered fetches and the rest of the
     * app's IO (see FetchLanes). */
    private val dispatcher: kotlinx.coroutines.CoroutineDispatcher =
        Dispatchers.IO
) : HttpTransport {

    override suspend fun execute(request: HttpTransport.Request): AppResult<HttpTransport.Response> {
        return withContext(dispatcher) {
            // v1.3.1 (item 44): build the call BEFORE the try so
            // coroutine cancellation can cancel it — a blocking
            // execute() otherwise ignores cancellation until a
            // socket timeout happens to fire.
            val call = try {
                val builder = Request.Builder().url(request.url)
                request.headers.forEach { (name, value) -> builder.header(name, value) }
                request.basicAuth?.let { (user, password) ->
                    builder.header("Authorization", Credentials.basic(user, password))
                }
                if (request.rangeStart > 0) {
                    builder.header("Range", "bytes=${request.rangeStart}-")
                }
                val verb = request.method.uppercase()
                if (verb != "GET") {
                    val contentType = request.headers.entries
                        .firstOrNull { it.key.equals("Content-Type", true) }
                        ?.value ?: "application/x-www-form-urlencoded"
                    val requestBody = if (request.bodyBytes != null) {
                        request.bodyBytes.toRequestBody(
                            contentType.toMediaTypeOrNull()
                        )
                    } else {
                        (request.body ?: "").toRequestBody(
                            contentType.toMediaTypeOrNull()
                        )
                    }
                    builder.method(verb, requestBody)
                }
                client.newCall(builder.build())
            } catch (e: IllegalArgumentException) {
                return@withContext AppResult.Err(
                    AppError.BadFile("that address doesn't look right")
                )
            }
            // Cancellation note: the blocking execute() below
            // cannot be interrupted mid-read by coroutine
            // cancellation — the CALL timeout above is the
            // bound that ends a stalled call (300 s worst case
            // on a dead connection, on an IO thread, never the
            // main thread), and a cancelled caller's result is
            // discarded by the withContext contract.
            try {
                val response: Response = call.execute()
                AppResult.Ok(
                    HttpTransport.Response(
                        code = response.code,
                        headers = response.headers.toMap(),
                        body = response.body?.byteStream(),
                        isPartialContent = response.code == 206
                    )
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: IllegalArgumentException) {
                AppResult.Err(AppError.BadFile("that address doesn't look right"))
            } catch (e: IOException) {
                AppResult.Err(HttpTransport.ioError(e))
            } catch (e: Exception) {
                AppResult.Err(AppError.Unknown(e))
            }
        }
    }

    companion object {
        /** v1.3.1 (item 44): the TOTAL budget for one call.
         * The per-read timeout alone cannot catch a connection
         * that trickles forever (each tiny read succeeds); a
         * call timeout can. Five minutes is far beyond any
         * legitimate page on the user's slowest network, and
         * it converts an eternal stall into the plain timeout
         * error. Downloads resume by Range, so a capped call
         * costs them nothing. */
        const val CALL_TIMEOUT_SECONDS = 300L

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()

        /**
         * Phase 3: the extension transport NEVER auto-follows
         * redirects — ExtensionHttpClient follows them manually so the
         * domain whitelist is re-checked on every hop (§9.1).
         */
        fun extensionClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }
}
