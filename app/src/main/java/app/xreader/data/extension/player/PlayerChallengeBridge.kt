package app.xreader.data.extension.player

import app.xreader.core.error.CrashGuard
import app.xreader.data.extension.DiagnosticLog
import kotlinx.coroutines.CancellationException
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
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    /** v1.5.2 (re-prompt cooldown): how long a dismissed or
     * timed-out solve suppresses re-prompting for the same host. */
    private val cooldownMillis: Long = DEFAULT_COOLDOWN_MILLIS,
    /** Injectable clock so the cooldown is testable without
     * waiting out real minutes. */
    private val nowMs: () -> Long = System::currentTimeMillis
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

    private val _challengePending = MutableStateFlow(false)

    /**
     * v1.5.4 (WS-1 item 6c): TRUE while ANY challenge for this
     * host subsystem is in flight — an interactive prompt is
     * waiting for the user ([prompts] non-null), or an
     * unattended auto-solve (item 6a) is inside its 30 s latch.
     * The book page reads this to say a check is pending
     * instead of claiming to find chapters. Auto-solves are
     * announced through [noteAutoSolveStart]/[noteAutoSolveEnd]
     * and deliberately do NOT emit a [Prompt]: a prompt opens
     * the sanctioned screen in the shell, and the auto-solved
     * class must never summon the user (D5).
     */
    val challengePending: StateFlow<Boolean> = _challengePending.asStateFlow()

    private val lock = Any()
    private var pending: Pending? = null

    /** The host whose auto-solve is in flight, if any. */
    private var autoSolveHost: String? = null

    private fun refreshChallengePendingLocked() {
        _challengePending.value = pending != null || autoSolveHost != null
    }

    /** Announce an unattended auto-solve starting for [url]'s
     * host (item 6a). Informational only — see
     * [challengePending]. */
    fun noteAutoSolveStart(url: HttpUrl) {
        synchronized(lock) {
            autoSolveHost = url.host
            refreshChallengePendingLocked()
        }
    }

    /** Announce the in-flight auto-solve ending (solved or
     * not — the wait is over either way). */
    fun noteAutoSolveEnd() {
        synchronized(lock) {
            autoSolveHost = null
            refreshChallengePendingLocked()
        }
    }

    /** v1.5.2 (re-prompt cooldown): host → the instant (per
     * [nowMs]) its negative cooldown ends. Set when a prompt
     * resolves UNSOLVED (dismissal or timeout): without it, an
     * extension retrying the failed call re-entered the
     * interceptor and popped the sanctioned screen again in the
     * face of a user who had just dismissed it. */
    private val cooldownUntilMs = HashMap<String, Long>()

    private class Pending(val host: String) {
        val latch = CountDownLatch(1)
    }

    /** v1.5.10 (A2 mop-up): a contained failure from the
     * session-saved collector — recorded as a CONTAINED failure
     * via a DiagnosticLog line carrying CrashGuard's top frames.
     * That is the floor for this class: it holds no Context /
     * filesDir, so it has no access to CrashGuard's contained-note
     * recorder seam (the same DiagnosticLog-only default sink
     * RenderRuntime uses). Cancellation is never a failure and
     * is never recorded here. */
    private fun reportContained(site: String, throwable: Throwable) {
        if (throwable is CancellationException) return
        runCatching {
            DiagnosticLog.record(
                tag = "contained",
                message = "Contained failure at $site: " +
                    "${throwable.javaClass.name} — the app kept running.",
                detail = CrashGuard.framesText(throwable)
            )
        }
    }

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            CrashGuard.containmentHandler(SITE_SCOPE) { site, _, throwable ->
                reportContained(site, throwable)
            }
    )

    init {
        scope.launch {
            sessionSavedEvents.collect { key ->
                // v1.5.10 (A2 mop-up): per-emission containment,
                // mirroring AppContainer's player-enabled
                // collector — a bad emission (e.g. a throw from
                // clearanceNow) is skipped + recorded as a
                // CONTAINED failure and the collector keeps
                // collecting; the scope's containment handler
                // backstops anything that escapes this.
                // Cancellation is rethrown, never recorded or
                // swallowed. No session/clearance behaviour changes.
                runCatching {
                    val current = synchronized(lock) { pending }
                        ?: return@runCatching
                    if (key == sessionKey && clearanceNow(current.host)) {
                        current.latch.countDown()
                    }
                }.onFailure { failure ->
                    if (failure is CancellationException) throw failure
                    reportContained(SITE_COLLECT, failure)
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

        // v1.5.2 (re-prompt cooldown): a recent dismissal or
        // timeout for this host fails fast — NO prompt is
        // emitted. (A host whose clearance has since landed
        // never reaches here: the store check above answered.)
        val cooling = synchronized(lock) {
            val until = cooldownUntilMs[host]
            when {
                until == null -> false
                nowMs() < until -> true
                else -> {
                    cooldownUntilMs.remove(host)
                    false
                }
            }
        }
        if (cooling) return false

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
                        refreshChallengePendingLocked()
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
                    refreshChallengePendingLocked()
                }
                // An unsolved prompt (dismissal or timeout)
                // starts the host's re-prompt cooldown.
                if (!solved) {
                    cooldownUntilMs[host] = nowMs() + cooldownMillis
                }
            }
        }
        return solved
    }

    /** v1.5.2: an explicit user-initiated check for [host] (the
     * sources screen's sign-in/check action) clears its re-prompt
     * cooldown — the user's own tap is never throttled by a
     * dismissal they made earlier. */
    fun clearCooldown(host: String) {
        synchronized(lock) { cooldownUntilMs.remove(host) }
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

        /** The re-prompt cooldown after a dismissal or timeout. */
        const val DEFAULT_COOLDOWN_MILLIS = 120_000L

        private const val SLOT_POLL_MILLIS = 250L

        /** Containment site labels (A2 census): the scope
         * backstop and the session-saved collector. */
        const val SITE_SCOPE = "player-challenge-bridge"
        const val SITE_COLLECT = "player-challenge-bridge:session-saved"
    }
}
