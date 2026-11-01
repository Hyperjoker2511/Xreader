package app.xreader.di

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
 * This client attaches, per request, the cookies the
 * browser view's own jar holds for that URL (the jar the
 * render engine seeds from the session store before a load
 * and the sign-in screen writes to — one jar, both roads)
 * and the system WebView's own user agent, so a clearance
 * cookie earned in the browser view is presented the way it
 * was earned. No whitelist change: covers already only load
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
                val url = request.url.toString()
                val cookies = runCatching {
                    CookieManager.getInstance().getCookie(url)
                }.getOrNull()
                val builder = request.newBuilder()
                if (!cookies.isNullOrBlank() &&
                    request.header("Cookie") == null
                ) {
                    builder.header("Cookie", cookies)
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
