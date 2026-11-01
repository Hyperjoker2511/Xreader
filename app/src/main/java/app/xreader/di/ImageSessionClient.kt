package app.xreader.di

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import app.xreader.XreaderApp
import app.xreader.core.security.KeyVault
import app.xreader.data.extension.CookieParsing
import app.xreader.data.extension.SessionStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.OkHttpClient

/**
 * v1.3.2 (scope A3): the image loader's HTTP client — cover
 * requests ride the SAME session as everything else.
 *
 * v1.3.1 loaded covers through Coil's stock client: no
 * session cookies, a stock user agent. On a site behind a
 * check (the NovelFire family), the catalogue's book data
 * came through the cleared browser-view session while every
 * cover request arrived as a stranger and was refused — the
 * rows rendered as bare titles with monogram placeholders.
 *
 * v1.3.5 (P0, session lifecycle): the cookies now resolve
 * from the [SessionStore] — the app's own session authority —
 * and NEVER from the global WebView cookie jar. The jar is
 * shared scratch space: it can hold cookies a session left
 * behind after the user signed out of that source, and an
 * image request must not present a credential the app itself
 * considers forgotten. The store holds exactly the sessions
 * the user still has, keyed per source; a request takes the
 * cookies any saved session holds for its host (parent-domain
 * jars included, the store's own rule). The user agent is
 * still the system WebView's own, so a clearance cookie
 * earned in the browser view is presented the way it was
 * earned. No whitelist change: covers already only load
 * from URLs sources produced; this changes WHO the request
 * claims to be, not WHERE it may go.
 */
object ImageSessionClient {

    @Volatile
    private var userAgent: String? = null

    fun create(context: Context): Call.Factory {
        val appContext = context.applicationContext
        return OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(Interceptor { chain ->
                val request = chain.request()
                val builder = request.newBuilder()
                if (request.header("Cookie") == null) {
                    sessionCookieHeader(appContext, request.url.host)
                        ?.let { builder.header("Cookie", it) }
                }
                if (request.header("User-Agent") == null) {
                    webViewUserAgent(appContext)?.let {
                        builder.header("User-Agent", it)
                    }
                }
                chain.proceed(builder.build())
            })
            .build()
    }

    /** The Cookie header the saved sessions hold for [host],
     * or null — resolved per request from the store, so a
     * sign-out takes effect for images immediately. Any
     * failure (container not built yet, store unreadable)
     * reads as "no cookies": the request goes out anonymous
     * rather than failing. */
    private fun sessionCookieHeader(
        appContext: Context,
        host: String
    ): String? {
        val (store, vault) = sessionAuthority(appContext) ?: return null
        return runCatching {
            runBlocking { cookieHeaderFromSessions(store, vault, host) }
        }.getOrNull()
    }

    /** The app's session store + vault, reached through the
     * Application's container (the factory is built by the
     * Application itself, after the container). Null under a
     * foreign Application (tests, previews). */
    private fun sessionAuthority(
        context: Context
    ): Pair<SessionStore, KeyVault>? {
        val app = context.applicationContext as? XreaderApp ?: return null
        return runCatching {
            app.container.sessionStore to app.container.keyVault
        }.getOrNull()
    }

    /**
     * Merge the Cookie headers every saved session holds for
     * [host] (later sessions win on a name collision; session
     * keys are visited in sorted order so the result is
     * deterministic). Session keys are enumerated from the
     * vault's present-key names — names only, never values —
     * under the store's own key prefix. Internal so the
     * resolution is testable without Android.
     */
    internal suspend fun cookieHeaderFromSessions(
        store: SessionStore,
        vault: KeyVault,
        host: String
    ): String? {
        val prefix = KeyVault.sessionCookies("")
        val sessionKeys = vault.presentKeys.value
            .filter { it.startsWith(prefix) }
            .map { it.removePrefix(prefix) }
            .sorted()
        if (sessionKeys.isEmpty()) return null
        val merged = LinkedHashMap<String, String>()
        for (key in sessionKeys) {
            val data = store.load(key) ?: continue
            val header = data.cookieHeaderFor(host) ?: continue
            CookieParsing.parseCookieHeader(header)
                .forEach { (name, value) -> merged[name] = value }
        }
        if (merged.isEmpty()) return null
        return merged.entries.joinToString("; ") {
            "${it.key}=${it.value}"
        }
    }

    /** The system WebView's default user agent — the exact
     * string the render engine presents, read once from a
     * throwaway view on the main thread and cached. Null
     * when it can't be read (the request then goes out with
     * OkHttp's own agent rather than failing). */
    private fun webViewUserAgent(context: Context): String? {
        userAgent?.let { return it }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return runCatching {
                WebView(context).settings.userAgentString
            }.getOrNull()?.also { userAgent = it }
        }
        val latch = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            userAgent = runCatching {
                WebView(context).settings.userAgentString
            }.getOrNull()
            latch.countDown()
        }
        latch.await(2, TimeUnit.SECONDS)
        return userAgent
    }
}
