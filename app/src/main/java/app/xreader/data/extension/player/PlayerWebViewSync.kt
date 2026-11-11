package app.xreader.data.extension.player

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebView
import app.xreader.core.security.KeyVault
import app.xreader.data.extension.CookieParsing
import app.xreader.data.extension.SessionStore
import app.xreader.di.ImageSessionClient
import eu.kanade.tachiyomi.network.NetworkPreferences
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
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
 *
 * v1.5.11 (ANR forensics, Part 2): seed and harvest are
 * suspend functions on Dispatchers.IO with NO blocking
 * bridge calls anywhere in this file — the v1.5.4 inline
 * shape wrapped the store calls in a blocking bridge on
 * the CALLER's thread, which parks a main-thread caller
 * for the vault's IO.
 * The CookieManager access itself stays off Main (donor
 * rule D3 — the K1 deadlock was a Main hop; the donor's
 * AndroidCookieJar touches the same manager from OkHttp
 * dispatcher threads, and IO here is that equivalent).
 * Cookies merge ONLY into the real [SessionStore], under
 * the player's own session record
 * ([SessionStoreCookieJar.PLAYER_SESSION_KEY]) — there is
 * no second "cookie mirror" store.
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

    /** The fire-and-forget scope for the ONE caller shape
     * that provably does not read the store back (see
     * [harvestHost]). Everything order-sensitive uses the
     * suspend paths. */
    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Installed once by the container at startup. Without
     * an install (unit tests construct adapters directly)
     * every operation is a no-op. */
    fun install(context: Context, store: SessionStore, vault: KeyVault) {
        bridge = Bridge(context.applicationContext, store, vault)
    }

    val installed: Boolean get() = bridge != null

    /**
     * v1.5.12 (M5): the re-entrancy marker for [around]. A
     * caller that opens ONE bracket for a whole unit of work
     * (the page-load bracket [MihonSourceAdapter] exposes to
     * the transplanted HttpPageLoader) carries this element;
     * [around] calls made INSIDE it — the adapter's per-call
     * brackets in getImageUrl/getImage — then run their block
     * directly, with no second seed/harvest. Seed-before /
     * harvest-after semantics are preserved per unit of work
     * (per page), at one vault round-trip pair per page
     * instead of one per call (pre-v1.5.12: two per page —
     * ~1,176 round-trips on a 588-page chapter). The element
     * travels with the coroutine, so dispatcher hops inside
     * the bracket keep it; a raw-thread bridge that drops the
     * coroutine context degrades to the old per-call bracket,
     * never to no bracket.
     */
    private class AroundBracketElement : CoroutineContext.Element {
        companion object Key : CoroutineContext.Key<AroundBracketElement>
        override val key: CoroutineContext.Key<*> get() = Key
    }

    /**
     * v1.5.12 (M5): how many OUTER [around] brackets have
     * been entered (re-entrant entries are no-ops and do not
     * count). The gate's one-bracket-per-page proof reads
     * this; production only increments it.
     */
    val outerBracketEntries = AtomicInteger(0)

    /**
     * Run [block] with the WebView jar synced around it:
     * seed the store's cookies for [hosts] in, harvest the
     * jar's cookies for [hosts] back out (in a finally —
     * a call that earned a clearance and then failed still
     * keeps the clearance). No bridge or no hosts = the
     * block runs exactly as before.
     *
     * v1.5.12 (M5): RE-ENTRANT — an [around] entered while
     * an outer bracket is already in force on this coroutine
     * (see [AroundBracketElement]) runs its block with no
     * second seed/harvest.
     */
    suspend fun <T> around(hosts: List<String>, block: suspend () -> T): T {
        val active = bridge
        if (active == null || hosts.isEmpty()) return block()
        if (currentCoroutineContext()[AroundBracketElement.Key] != null) {
            return block()
        }
        outerBracketEntries.incrementAndGet()
        return withContext(AroundBracketElement()) {
            seed(active, hosts)
            try {
                block()
            } finally {
                harvest(active, hosts)
            }
        }
    }

    /** Seed the store's cookies for [hosts] into the WebView
     * jar, on IO. A host whose store read fails is skipped —
     * a seed miss degrades to the pre-sync behaviour, never
     * to a failed source call. */
    private suspend fun seed(active: Bridge, hosts: List<String>) {
        withContext(Dispatchers.IO) {
            val headers = hosts.mapNotNull { host ->
                val header = runCatching {
                    ImageSessionClient.cookieHeaderFromSessions(
                        active.store, active.vault, host
                    )
                }.getOrNull()
                if (header.isNullOrBlank()) null else host to header
            }
            if (headers.isEmpty()) return@withContext
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

    /** Harvest the WebView jar's cookies for [hosts] back
     * into the player's session record, on IO. AWAITED by
     * construction: when this returns, the merge has landed
     * in the store. */
    private suspend fun harvest(active: Bridge, hosts: List<String>) {
        withContext(Dispatchers.IO) {
            val found = readManagerCookies(hosts)
            for ((host, cookies) in found) {
                runCatching {
                    active.store.mergeCookies(
                        SessionStoreCookieJar.PLAYER_SESSION_KEY, host, cookies
                    )
                }
            }
        }
    }

    private fun readManagerCookies(
        hosts: List<String>
    ): List<Pair<String, Map<String, String>>> =
        runCatching {
            val manager = CookieManager.getInstance()
            hosts.mapNotNull { host ->
                val header = manager.getCookie("https://$host/")
                    ?: return@mapNotNull null
                val cookies = CookieParsing.parseCookieHeader(header)
                if (cookies.isEmpty()) null else host to cookies
            }
        }.getOrDefault(emptyList())

    /**
     * v1.5.11: one host's jar → store sync, AWAITED — the
     * challenge path's entry point (v1.5.4 item 6 discipline):
     * a clearance a WebView solve just earned must be IN the
     * store before the replayed request reads its cookies
     * from it, so a caller that replays must call THIS and
     * await it, never [harvestHost]. No-op when not
     * installed. Never blocks the caller thread.
     */
    suspend fun harvestHostAwaited(host: String) {
        val active = bridge ?: return
        harvest(active, listOf(host))
    }

    /** One host's jar → store sync, fire-and-forget: the
     * merge is launched on [syncScope] and this returns
     * before it lands. ONLY for callers that provably never
     * read the store back for that host afterwards.
     *
     * v1.5.11 seam note: the challenge replay in
     * CloudflareInterceptor is order-sensitive by its own
     * comment (the replay reads the store), so its current
     * call to this function races — it must move to
     * [harvestHostAwaited]. That call site lives outside
     * this file (an OkHttp interceptor thread, where awaiting
     * the suspend path is safe) and is flagged for its owner
     * in the v1.5.11 lane-D report. */
    fun harvestHost(host: String) {
        val active = bridge ?: return
        syncScope.launch {
            harvest(active, listOf(host))
        }
    }

    /** The installed application context, or null when not
     * installed. The challenge auto-solve (item 6) builds its
     * WebView from this — the same install the container
     * performs for the cookie sync. */
    fun installedContext(): Context? = bridge?.context

    /**
     * One-time UA alignment (see the class doc). Runs on
     * the main thread (a WebView is created to read the
     * device's default UA, then destroyed); failures leave
     * the donor default in force. Idempotent by construction:
     * the replacement happens only while the preference still
     * holds the donor default exactly, so no seeded-once flag
     * is needed and repeat calls are no-ops.
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
