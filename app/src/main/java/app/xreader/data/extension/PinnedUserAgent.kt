package app.xreader.data.extension

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * v1.5.0 (steer A4): ONE user-agent for the whole source
 * engine. Before any session exists, the plain HTTP road
 * used to send OkHttp's own default agent while the hidden
 * WebView sent the device Chromium's — two fingerprints for
 * one app, and exactly the mismatch that makes a site (or
 * its bot wall) treat the post-solve native fetch as a
 * different visitor than the one that passed its check.
 *
 * The device WebView's real UA is captured ONCE at startup
 * and pinned here; the HTTP client sends it whenever no
 * session-captured UA applies, and the render fetcher sets
 * it on its WebView in the same case. A session's own
 * captured UA (from its solve) always wins over the pin —
 * that pairing (cookies + the UA they were minted under)
 * is the strongest consistency the engine can offer.
 */
object PinnedUserAgent {

    @Volatile
    private var pinned: String? = null

    fun current(): String? = pinned

    fun install(userAgent: String?) {
        val trimmed = userAgent?.trim().orEmpty()
        if (trimmed.isNotEmpty()) pinned = trimmed
    }

    /** Read the device WebView's UA once (a throwaway view
     * on the main thread, destroyed immediately) and pin
     * it. A no-op when a value is already pinned. */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun capture(context: Context) {
        if (pinned != null) return
        val ua = withContext(Dispatchers.Main) {
            runCatching {
                val view = WebView(context.applicationContext)
                val value = view.settings.userAgentString
                view.destroy()
                value
            }.getOrNull()
        }
        install(ua)
    }
}
