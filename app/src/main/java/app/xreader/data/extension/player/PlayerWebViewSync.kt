package app.xreader.data.extension.player

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebView
import app.xreader.core.security.KeyVault
import app.xreader.data.extension.CookieParsing
import app.xreader.data.extension.SessionStore
import app.xreader.di.ImageSessionClient
import eu.kanade.tachiyomi.network.NetworkPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * v1.5.0 (Part B — Keiyoushi host fidelity): the two bridges
 * an extension's OWN WebView needs from the host, which the
 * donor (Mihon/Komikku) gets for free from its architecture
 * and Xreader must build deliberately:
 *
 * 1. COOKIE SYNC. In the donor, the OkHttp cookie jar IS
 *    the WebView's CookieManager, so when an extension runs
 *    the site's own JS client inside a WebView (the Comix
 *    technique) the cookies that client earns — clearances,
 *    session tokens — are instantly the cookies its OkHttp
 *    calls send, and vice versa. Xreader's D2 rule forbids
 *    the global WebView jar as a cookie source: the
 *    encrypted [SessionStore] is the single source of truth.
 *    This bridge restores the donor's OBSERVABLE behaviour
 *    without breaking the rule: around every source call
 *    ([around]), the store's cookies for the source's hosts
 *    are SEEDED into the WebView jar before the call, and
 *    whatever the jar holds for those hosts afterwards is
 *    HARVESTED back into the player's own session record.
 *    The store stays the truth; the WebView jar is a
 *    per-call cache, exactly like the rendered fetcher's
 *    seed/harvest discipline for native sources.
 *
 * 2. USER-AGENT ALIGNMENT. The donor's network stack sends
 *    a fixed default UA string while an extension-created
 *    WebView presents the DEVICE's WebView UA — one client
 *    wearing two faces, which strict sites fingerprint.
 *    [alignUserAgent] replaces the donor default (only the
 *    untouched default — a user-edited value is respected)
 *    with this device's real WebView UA, once, so the
 *    player's HTTP and WebView traffic present one UA.
 */
object PlayerWebViewSync {

    /** The donor's factory default UA (NetworkPreferences'
     * own default in the transplanted mihon-api) — replaced
     * by the device UA only while it is still exactly this. */
    private const val DONOR_DEFAULT_UA =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/141.0.0.0 Mobile Safari/537.36"

    private class Bridge(
        val context: Context,
        val store: SessionStore,
        val vault: KeyVault
    )

    @Volatile
    private var bridge: Bridge? = null

    /** Installed once by the container at startup. Without
     * an install (unit tests construct adapters directly)
     * every operation is a no-op. */
    fun install(context: Context, store: SessionStore, vault: KeyVault) {
        bridge = Bridge(context.applicationContext, store, vault)
    }

    val installed: Boolean get() = bridge != null

    /**
     * Run [block] with the WebView jar synced around it:
     * seed the store's cookies for [hosts] in, harvest the
     * jar's cookies for [hosts] back out (in a finally —
     * a call that earned a clearance and then failed still
     * keeps the clearance). No bridge or no hosts = the
     * block runs exactly as before.
     */
    suspend fun <T> around(hosts: List<String>, block: suspend () -> T): T {
        val active = bridge
        if (active == null || hosts.isEmpty()) return block()
        seed(active, hosts)
        try {
            return block()
        } finally {
            harvest(active, hosts)
        }
    }

    private suspend fun seed(active: Bridge, hosts: List<String>) {
        val headers = hosts.mapNotNull { host ->
            val header = runCatching {
                ImageSessionClient.cookieHeaderFromSessions(
                    active.store, active.vault, host
                )
            }.getOrNull()
            if (header.isNullOrBlank()) null else host to header
        }
        if (headers.isEmpty()) return
        withContext(Dispatchers.Main) {
            runCatching {
                val manager = CookieManager.getInstance()
                manager.setAcceptCookie(true)
                for ((host, header) in headers) {
                    for ((name, value) in
                        CookieParsing.parseCookieHeader(header)) {
                        manager.setCookie(
                            "https://$host/",
                            "$name=$value; Domain=$host; Path=/"
                        )
                    }
                }
                manager.flush()
            }
        }
    }

    private suspend fun harvest(active: Bridge, hosts: List<String>) {
        val found = withContext(Dispatchers.Main) {
            runCatching {
                val manager = CookieManager.getInstance()
                hosts.mapNotNull { host ->
                    val header = manager.getCookie("https://$host/")
                        ?: return@mapNotNull null
                    val cookies = CookieParsing.parseCookieHeader(header)
                    if (cookies.isEmpty()) null else host to cookies
                }
            }.getOrDefault(emptyList())
        }
        for ((host, cookies) in found) {
            runCatching {
                active.store.mergeCookies(
                    SessionStoreCookieJar.PLAYER_SESSION_KEY, host, cookies
                )
            }
        }
    }

    /**
     * One-time UA alignment (see the class doc). Runs on
     * the main thread (a WebView is created to read the
     * device's default UA, then destroyed); failures leave
     * the donor default in force.
     */
    suspend fun alignUserAgent(
        context: Context,
        preferences: NetworkPreferences
    ) {
        val pref = preferences.defaultUserAgent()
        if (pref.get().trim() != DONOR_DEFAULT_UA) return
        val deviceUa = withContext(Dispatchers.Main) {
            runCatching {
                val view = WebView(context.applicationContext)
                val ua = view.settings.userAgentString
                view.destroy()
                ua
            }.getOrNull()
        }
        if (!deviceUa.isNullOrBlank()) {
            pref.set(deviceUa)
        }
    }
}
