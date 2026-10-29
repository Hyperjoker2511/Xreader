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
        /** Maps transport failures onto the app's error vocabulary. */
        fun ioError(e: Exception): AppError = when (e) {
            is UnknownHostException -> AppError.NoNetwork
            is SocketTimeoutException ->
                AppError.ProviderDown("server", detail = "the server took too long to answer")
            else -> AppError.Unknown(e)
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
    private val client: OkHttpClient = defaultClient()
) : HttpTransport {

    override suspend fun execute(request: HttpTransport.Request): AppResult<HttpTransport.Response> {
        return withContext(Dispatchers.IO) {
            try {
                val builder = Request.Builder().url(request.url)
                request.headers.forEach { (name, value) -> builder.header(name, value) }
                request.basicAuth?.let { (user, password) ->
                    builder.header("Authorization", Credentials.basic(user, password))
                }
                if (request.rangeStart > 0) {
                    builder.header("Range", "bytes=${request.rangeStart}-")
                }
                // GET stays the builder default; every other verb
                // (POST forms, Phase 5's WebDAV PUT/PROPFIND) goes
                // through the generic method path.
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
                val response: Response = client.newCall(builder.build()).execute()
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
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
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
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }
}
