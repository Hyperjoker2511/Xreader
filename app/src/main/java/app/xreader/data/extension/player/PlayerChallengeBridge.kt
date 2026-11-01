package app.xreader.data.extension.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * K2 (Keiyoushi player): the bridge between a blocking OkHttp interceptor
 * thread that just hit a Cloudflare challenge and Xreader's sanctioned
 * solve-or-sign-in screen.
 *
 * Shape: [requestSolve] blocks the calling (OkHttp dispatcher) thread —
 * OkHttp interceptors are synchronous, so blocking is the only option,
 * and the donor's WebViewInterceptor blocks the same way on a latch. The
 * shell collects [prompts]: when a prompt appears it opens the sanctioned
 * screen (the existing "login" route, SITE_CHECK mode) at the challenged
 * URL. When the user finishes, the screen saves the session into
 * SessionStore and the container's session-saved bus fires; this bridge
 * hears its own key on that bus, verifies a cf_clearance cookie now
 * resolves for the host from the store, and releases the waiter. The
 * store — never a UI signal alone — is the success criterion, so a save
 * that landed without a usable clearance does not fake a pass.
 *
 * Prompts are one-at-a-time (the screen is a single surface): a caller
 * for the SAME host joins the in-flight prompt; a caller for another
 * host waits for the slot. Dismissal ([dismissCurrent], called by the
 * shell when the login request clears) releases the waiter, which then
 * takes its verdict from one final store check — so a save whose bus
 * event raced the dismissal still counts.
 */
class PlayerChallengeBridge(
    sessionSavedEvents: SharedFlow<String>,
    private val hasClearance: suspend (host: String) -> Boolean,
    private val sessionKey: String = SessionStoreCookieJar.PLAYER_SESSION_KEY,
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS
) {

    /** A challenge the shell should show the sanctioned screen for. */
    data class Prompt(
        val host: String,
        /** The exact URL that was challenged (the screen opens here,
         * where the site's check widget lives). */
        val url: String,
        /** The SessionStore key the screen saves under — the key this
         * bridge listens for on the session-saved bus. */
        val sessionKey: String
    )

    private val _prompts = MutableStateFlow<Prompt?>(null)

    /** The prompt currently waiting for the user, if any. */
    val prompts: StateFlow<Prompt?> = _prompts.asStateFlow()

    private val lock = Any()
    private var pending: Pending? = null

    private class Pending(val host: String) {
        val latch = CountDownLatch(1)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        scope.launch {
            sessionSavedEvents.collect { key ->
                val current = synchronized(lock) { pending } ?: return@collect
                if (key == sessionKey && clearanceNow(current.host)) {
                    current.latch.countDown()
                }
            }
        }
    }

    /**
     * Block until the challenge for [url]'s host is solved (a clearance
     * cookie resolves from the store), the user dismisses the screen, or
     * [timeoutMillis] passes. Returns the store-verified verdict.
     */
    fun requestSolve(url: HttpUrl): Boolean {
        val host = url.host
        // Another in-flight call may already have solved this host while
        // our response was on the wire.
        if (clearanceNow(host)) return true

        // Take the prompt slot, or join the in-flight prompt for this
        // host. A different host's prompt owns the screen meanwhile.
        var slot: Pending? = null
        var owner = false
        while (slot == null) {
            synchronized(lock) {
                val current = pending
                when {
                    current == null -> {
                        val mine = Pending(host)
                        pending = mine
                        owner = true
                        _prompts.value = Prompt(host, url.toString(), sessionKey)
                        slot = mine
                    }
                    current.host == host -> slot = current
                    else -> Unit // another host is prompting; poll again
                }
            }
            if (slot == null) Thread.sleep(SLOT_POLL_MILLIS)
        }
        val active = slot!!

        active.latch.await(timeoutMillis, TimeUnit.MILLISECONDS)

        // Save, dismissal, or timeout: the store is the only truth.
        val solved = clearanceNow(host)
        if (owner) {
            synchronized(lock) {
                if (pending === active) {
                    pending = null
                    _prompts.value = null
                }
            }
        }
        return solved
    }

    /** The shell calls this when the sanctioned screen's request clears
     * without a proven save: release the current waiter, whose final
     * store check decides the verdict. No-op when nothing is pending. */
    fun dismissCurrent() {
        val current = synchronized(lock) { pending } ?: return
        current.latch.countDown()
    }

    private fun clearanceNow(host: String): Boolean =
        runCatching { runBlocking { hasClearance(host) } }.getOrDefault(false)

    companion object {
        /** Long enough for a human to finish a check or sign-in on a
         * phone (the donor's silent-WebView solve allows 30 s; a person
         * at a visible screen gets longer). */
        const val DEFAULT_TIMEOUT_MILLIS = 120_000L

        private const val SLOT_POLL_MILLIS = 250L
    }
}
