/*
 * Transplanted for Xreader from Komikku (core/common, Apache-2.0) with the
 * deviations named in the NOTICE file in this module.
 */
package eu.kanade.tachiyomi.network

import android.content.Context
import eu.kanade.tachiyomi.network.interceptor.UncaughtExceptionInterceptor
import eu.kanade.tachiyomi.network.interceptor.UserAgentInterceptor
import logcat.LogPriority
import okhttp3.Cache
import okhttp3.CookieJar
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Response
import okio.IOException
import tachiyomi.core.common.preference.AndroidPreferenceStore
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import kotlin.math.pow
import kotlin.random.Random

open class NetworkHelper(
    private val context: Context,
    private val preferences: NetworkPreferences,
) {

    /**
     * Xreader: convenience constructor with the extension-lib shape; the
     * two-argument donor constructor above remains the primary one. Backed by
     * the donor AndroidPreferenceStore (default SharedPreferences).
     */
    constructor(context: Context) : this(
        context,
        NetworkPreferences(AndroidPreferenceStore(context)),
    )

    // Xreader K2: the declared type is widened from the donor's
    // AndroidCookieJar to okhttp3.CookieJar so the host app can substitute
    // its own jar (the player's SessionStore-backed one — cookies resolve
    // per-source-host from Xreader's session store, never the global
    // WebView jar). The default instance is still the donor AndroidCookieJar
    // below; see NOTICE deviation 15.
    open val cookieJar: CookieJar = AndroidCookieJar()

    /**
     * Timeout in unit of seconds.
     */
    private fun clientBuilder(

        connectTimeout: Long = 30,
        readTimeout: Long = 30,
        callTimeout: Long = 120,

    ): OkHttpClient.Builder = run {
        val builder = OkHttpClient.Builder()
            .cookieJar(cookieJar)

            .connectTimeout(connectTimeout, TimeUnit.SECONDS)
            .readTimeout(readTimeout, TimeUnit.SECONDS)
            .callTimeout(callTimeout, TimeUnit.SECONDS)

            .cache(
                Cache(
                    directory = File(context.cacheDir, "network_cache"),
                    maxSize = 5L * 1024 * 1024, // 5 MiB
                ),
            )
            .addInterceptor(UncaughtExceptionInterceptor())
            .addInterceptor(UserAgentInterceptor(::defaultUserAgentProvider))



        when (preferences.dohProvider().get()) {
            PREF_DOH_CLOUDFLARE -> builder.dohCloudflare()
            PREF_DOH_GOOGLE -> builder.dohGoogle()
            PREF_DOH_ADGUARD -> builder.dohAdGuard()
            PREF_DOH_QUAD9 -> builder.dohQuad9()
            PREF_DOH_ALIDNS -> builder.dohAliDNS()
            PREF_DOH_DNSPOD -> builder.dohDNSPod()
            PREF_DOH_360 -> builder.doh360()
            PREF_DOH_QUAD101 -> builder.dohQuad101()
            PREF_DOH_MULLVAD -> builder.dohMullvad()
            PREF_DOH_CONTROLD -> builder.dohControlD()
            PREF_DOH_NJALLA -> builder.dohNajalla()
            PREF_DOH_SHECAN -> builder.dohShecan()
            else -> builder
        }
    }

    open val client by lazy {
        // Xreader K1: Komikku adds its CloudflareInterceptor here. Ours is inserted in
        // slice K2, adapted to route challenges through Xreader's sanctioned
        // solve-or-sign-in screen (see NOTICE).
        clientBuilder()
            .build()
    }


    /**
     * Timeout in unit of seconds.
     */
    private fun clientWithTimeOut(
        connectTimeout: Long = 30,
        readTimeout: Long = 30,
        callTimeout: Long = 120,
    ) = clientBuilder(connectTimeout, readTimeout, callTimeout)
        .build()

    /**
     * Allow to download a big file with retry & resume capability because
     * normally it would get a Timeout exception.
     */
    fun downloadFileWithResume(url: String, outputFile: File, progressListener: ProgressListener) {
        val client = clientWithTimeOut(
            callTimeout = 120,
        )

        var downloadedBytes: Long

        var attempt = 0

        while (attempt < MAX_RETRY) {
            try {
                // Check how much has already been downloaded
                downloadedBytes = outputFile.length()
                // Set up request with Range header to resume from the last byte
                val request = GET(
                    url = url,
                    headers = Headers.Builder()
                        .add("Range", "bytes=$downloadedBytes-")
                        .build(),
                )

                var failed = false
                client.newCachelessCallWithProgress(request, progressListener).execute().use { response ->
                    if (response.isSuccessful || response.code == 206) { // 206 indicates partial content
                        saveResponseToFile(response, outputFile, downloadedBytes)
                        if (response.isSuccessful) {
                            return
                        }
                    } else {
                        attempt++
                        logcat(LogPriority.ERROR) { "Unexpected response code: ${response.code}. Retrying..." }
                        if (response.code == 416) {
                            // 416: Range Not Satisfiable
                            outputFile.delete()
                        }
                        failed = true
                    }
                }
                if (failed) exponentialBackoff(attempt - 1)
            } catch (e: IOException) {
                logcat(LogPriority.ERROR) { "Download interrupted: ${e.message}. Retrying..." }
                // Wait or handle as needed before retrying
                attempt++
                exponentialBackoff(attempt - 1)
            }
        }
        throw IOException("Max retry attempts reached.")
    }

    // Helper function to save data incrementally
    private fun saveResponseToFile(response: Response, outputFile: File, startPosition: Long) {
        // Xreader: OkHttp 4.12 exposes Response.body as nullable (OkHttp 5, the
        // donor's version, does not) — see NOTICE.
        val body = response.body!!

        // Use RandomAccessFile to write from specific position
        RandomAccessFile(outputFile, "rw").use { file ->
            file.seek(startPosition)
            body.byteStream().use { input ->
                val buffer = ByteArray(8 * 1024)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    file.write(buffer, 0, bytesRead)
                }
            }
        }
    }

    // Increment attempt and apply exponential backoff
    private fun exponentialBackoff(attempt: Int) {
        val backoffDelay = calculateExponentialBackoff(attempt)
        Thread.sleep(backoffDelay)
    }

    // Helper function to calculate exponential backoff with jitter
    private fun calculateExponentialBackoff(attempt: Int, baseDelay: Long = 1000L, maxDelay: Long = 32000L): Long {
        // Calculate the exponential delay
        val delay = baseDelay * 2.0.pow(attempt).toLong()
        logcat(LogPriority.ERROR) { "Exponential backoff delay: $delay ms" }
        // Apply jitter by adding a random value to avoid synchronized retries in distributed systems
        return (delay + Random.nextLong(0, 1000)).coerceAtMost(maxDelay)
    }


    /**
     * @deprecated Since extension-lib 1.5
     */
    @Deprecated("The regular client handles Cloudflare by default", ReplaceWith("client"))
    @Suppress("UNUSED")
    
    open val cloudflareClient: OkHttpClient
        get() = client

    fun defaultUserAgentProvider() = preferences.defaultUserAgent().get().trim()

    companion object {

        private const val MAX_RETRY = 5

    }
}
