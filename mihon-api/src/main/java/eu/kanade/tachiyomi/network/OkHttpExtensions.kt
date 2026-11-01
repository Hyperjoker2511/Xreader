/*
 * Transplanted for Xreader from Komikku (core/common, Apache-2.0) with the
 * deviations named in the NOTICE file in this module.
 */
package eu.kanade.tachiyomi.network

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resumeWithException

val jsonMime = "application/json; charset=utf-8".toMediaType()

// Based on https://github.com/square/okhttp/blob/master/okhttp-coroutines/src/main/kotlin/okhttp3/coroutines/ExecuteAsync.kt
// and https://github.com/gildor/kotlin-coroutines-okhttp
private suspend fun Call.await(callStack: Array<StackTraceElement>): Response {
    return suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation {
            try {
                this.cancel()
            } catch (_: Throwable) {
                // ignore
            }
        }

        this.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isCancelled) return
                val exception = IOException(e.message, e).apply { stackTrace = callStack }
                continuation.resumeWithException(exception)
            }

            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { _, value, _ ->
                    value.close()
                }
            }
        })
    }
}

suspend fun Call.await(): Response {
    val callStack = Exception().stackTrace.run { copyOfRange(1, size) }
    return await(callStack)
}

/**
 * Similar to [await] but throws [HttpException] if [Response.isSuccessful] returns false
 */
suspend fun Call.awaitSuccess(): Response {
    val callStack = Exception().stackTrace.run { copyOfRange(1, size) }
    val response = await(callStack)
    if (!response.isSuccessful) {
        response.close()
        throw HttpException(response.code).apply { stackTrace = callStack }
    }
    return response
}

fun OkHttpClient.newCachelessCallWithProgress(request: Request, listener: ProgressListener): Call {
    val progressClient = newBuilder()
        .cache(null)
        .addNetworkInterceptor { chain ->
            val originalResponse = chain.proceed(chain.request())
            originalResponse.newBuilder()
                // Xreader: body!! for OkHttp 4.12's nullable Response.body — see NOTICE.
                .body(ProgressResponseBody(originalResponse.body!!, listener))
                .build()
        }
        .build()

    return progressClient.newCall(request)
}
