package app.xreader.data.extension

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * v1.5.5 (§3): the session-update broadcast — the replacement
 * for the v1.5.2 exactly-once post-save claim.
 *
 * The old protocol made the durable PendingOperation for a
 * session key claimable EXACTLY ONCE across all five
 * sessionSavedEvents collectors (reader, book page, catalogue,
 * link-add, player bridge): whichever surface won the claim
 * retried, and every other surface's blocked operation was
 * silently dropped — the reader's chapter text most notably
 * (diagnosis §3, suspect S1: the save toast showed, the reader
 * stayed on its pre-solve error forever).
 *
 * The new shape: a session save BROADCASTS a source-scoped
 * [SessionUpdatedEvent] (session key + the source id when it
 * can be resolved), and EVERY surface holding a blocked
 * operation for that source re-issues its OWN operation. The
 * durable PendingOperation records remain as the record of
 * WHAT was blocked; they no longer gate WHO may retry.
 *
 * The bus also keeps the per-source SOLVED-AT registry (the
 * §3 transport-switch window): a save stamps its session key
 * here, in memory only, and the read-token road consults it
 * to decide whether a mint may route inside the WebView
 * context. The window is the in-memory session with a
 * 15-minute floor (Amendments): a chapter opened minutes
 * after a solve still rides the warm context, and no verdict
 * is ever latched — live answers still decide locked vs
 * unlocked on every fetch.
 */
object SessionSolvedRegistry {

    /** The post-solve routing window: the in-memory session,
     * minimum 15 minutes (v1.5.5 Amendments — NOT the base
     * spec's 60 seconds). */
    const val SOLVE_WINDOW_MS: Long = 15L * 60L * 1000L

    private val solvedAtMs = ConcurrentHashMap<String, Long>()

    private val clock: () -> Long = { System.currentTimeMillis() }

    /** Record that [sessionKey]'s session was saved (a solve
     * or sign-in completed) at [atMs]. */
    fun noteSolved(sessionKey: String, atMs: Long = clock()) {
        if (sessionKey.isBlank()) return
        solvedAtMs[sessionKey] = atMs
    }

    /** The last solve instant for [sessionKey], or null when
     * this process has seen none. */
    fun solvedAt(sessionKey: String): Long? = solvedAtMs[sessionKey]

    /** True when a solve for [sessionKey] landed inside the
     * routing window. */
    fun hasRecentSolve(
        sessionKey: String,
        nowMs: Long = clock()
    ): Boolean {
        val at = solvedAtMs[sessionKey] ?: return false
        return nowMs - at in 0..SOLVE_WINDOW_MS
    }

    fun clearForTests() = solvedAtMs.clear()
}

/** One session-save broadcast: WHICH session changed, and the
 * declarative source id it belongs to when the producer could
 * resolve it (a bundled/learned config id; null when only the
 * session key is known — collectors match on the key, exactly
 * as the old String bus did). */
data class SessionUpdatedEvent(
    val sessionKey: String,
    val sourceId: String? = null,
    val savedAtMs: Long = System.currentTimeMillis()
)

/**
 * The broadcast itself (v1.5.5, §3). AppContainer owns the one
 * production instance; the class is plain so the reality-check
 * harness can drive the exact production seam with fake
 * surfaces on the JVM.
 */
class SessionUpdateBus {

    private val _events = MutableSharedFlow<SessionUpdatedEvent>(
        extraBufferCapacity = 8
    )

    /** Every session save, source-scoped. Collectors filter
     * on [SessionUpdatedEvent.sessionKey] (and may cross-check
     * [SessionUpdatedEvent.sourceId] when present). */
    val events: SharedFlow<SessionUpdatedEvent> = _events.asSharedFlow()

    /** Broadcast a save: stamp the solved-at registry first
     * (a collector that immediately re-fetches must already
     * see the warm window), then wake every surface. */
    fun notifySaved(sessionKey: String, sourceId: String? = null) {
        SessionSolvedRegistry.noteSolved(sessionKey)
        _events.tryEmit(
            SessionUpdatedEvent(sessionKey = sessionKey, sourceId = sourceId)
        )
    }
}

/**
 * v1.5.5 (§3): the per-surface retry gate — the lifecycle
 * priority of the broadcast, kept deliberately small.
 *
 * A RESUMED (foreground) surface retries the moment the
 * broadcast lands; a BACKSTACK surface defers its retry until
 * it is next resumed, so a solve never fires a burst of
 * overlapping reloads from screens the user isn't looking at.
 * "Resumed" is read from the surface's own UI-observation
 * signal: the app collects every surface's state with
 * collectAsStateWithLifecycle, so a visible surface's state
 * flow HAS subscribers and a backstack surface's does not.
 * Each ViewModel wires its `_state.subscriptionCount` in as
 * [isResumed] and calls [onResumed] from a subscription
 * watcher; the gate itself is pure bookkeeping and is what
 * the harness exercises.
 */
class SurfaceRetryGate(
    private val surfaceName: String,
    private val isResumed: () -> Boolean
) {

    @Volatile
    private var pendingKey: String? = null

    /** The broadcast landed for [sessionKey]: retry now when
     * resumed (returns true — the caller runs its own blocked
     * operation), or park it for the next resume (false). */
    fun onSessionUpdated(sessionKey: String): Boolean {
        return if (isResumed()) {
            pendingKey = null
            true
        } else {
            pendingKey = sessionKey
            false
        }
    }

    /** The surface just became resumed: the parked session
     * key awaiting its retry, or null when none is parked
     * (the caller retries its operation for that key). The
     * park is consumed either way — a retry is issued once
     * per save, by THIS surface, for ITS OWN operation.
     * Defensive: the resumed state is re-checked here, so a
     * watcher that fires early (or twice) can never release
     * the retry while the surface is still in the backstack —
     * the park simply stays in place. */
    fun onResumed(): String? {
        if (!isResumed()) return null
        val key = pendingKey
        pendingKey = null
        return key
    }

    /** The parked key, for diagnostics/tests. */
    fun pendingSessionKey(): String? = pendingKey

    override fun toString(): String =
        "SurfaceRetryGate($surfaceName, pending=$pendingKey)"
}

/**
 * v1.5.5 (§3, capability scoping — the BOTI law): which
 * post-save roads a source's credential shape allows.
 *
 *  - The in-WebView mint + JS token harvest run ONLY for
 *    sources declaring a read-token endpoint
 *    ([CredentialSpec.operationToken] — FenrirRealm's shape).
 *  - The post-solve WebView transport switch (routing the
 *    token-gated fetch through the WebView context) runs
 *    ONLY for sources that are needsRendering OR declare a
 *    read-token endpoint — and on the token road itself it
 *    engages only where a mint exists to route.
 *  - A source with NEITHER capability (BOTI's shape: a
 *    cookie→header binding replayed by OkHttp, no operation
 *    token) follows EXACTLY the pre-v1.5.5 path after a save:
 *    broadcast + OkHttp retry, no WebView routing, no mint
 *    wait. Its binding is never bypassed.
 *
 * Pure over the config, so the harness pins the routing for
 * both shapes without a WebView.
 */
object SessionCapabilityRouting {

    /** True when the source declares a read-token endpoint —
     * the ONLY shape whose mint/harvest may run in a WebView. */
    fun hasReadToken(config: DeclarativeConfig?): Boolean =
        config?.credentials?.operationToken != null

    /** True when the in-WebView mint/harvest may run for this
     * source (read-token sources only — hard law). */
    fun webViewMintAllowed(config: DeclarativeConfig?): Boolean =
        hasReadToken(config)

    /** True when the post-solve WebView transport switch may
     * route fetches for this source at all (needsRendering or
     * read-token sources). On the token road the switch still
     * requires [hasReadToken] — a needsRendering source with
     * no token contract (BOTI) has no mint to route and its
     * fetches keep their existing rendered/OkHttp roads. */
    fun webViewTransportSwitchAllowed(config: DeclarativeConfig?): Boolean =
        config != null && (config.needsRendering || hasReadToken(config))
}
