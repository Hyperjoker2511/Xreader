package app.xreader.data.extension

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import okhttp3.Interceptor
import okhttp3.Response

/**
 * v1.5.0 (steer B3): per-host politeness for hosts that
 * punish bursts. Some sites (Comix is the standing
 * exhibit) answer an "unusually high number of requests"
 * with automatic IP blocks measured in days — for a reader
 * app, being blocked is far worse than being slow. For a
 * flagged host the engine therefore:
 *
 *  - caps concurrent requests (images included) at
 *    [MAX_CONCURRENT_PER_HOST];
 *  - spaces request starts at least [MIN_SPACING_MS]
 *    apart;
 *  - skips speculative prefetch entirely (the manga
 *    reader checks [isHostile] before warming pages).
 *
 * Flagging is by host suffix and deliberately short: a
 * host earns its place here from observed behaviour
 * (documented, published rate-limit hostility), not from
 * a single bad day. Unflagged hosts take exactly the code
 * path they always did — the interceptor is a pass-through
 * and the reader's preload is unchanged.
 */
object HostPoliteness {

    /** Host suffixes under politeness rules. */
    private val HOSTILE_SUFFIXES = listOf("comix.to")

    const val MAX_CONCURRENT_PER_HOST = 2
    const val MIN_SPACING_MS = 250L

    fun isHostile(host: String?): Boolean {
        val value = host?.lowercase()?.trim().orEmpty()
        if (value.isEmpty()) return false
        return HOSTILE_SUFFIXES.any { suffix ->
            value == suffix || value.endsWith(".$suffix")
        }
    }

    fun isHostileUrl(url: String?): Boolean =
        isHostile(CookieParsing.hostOf(url.orEmpty()))

    // ── throttle state (per host) ──────────────────────────

    private class Gate {
        val semaphore = Semaphore(MAX_CONCURRENT_PER_HOST, true)
        var lastStartMs: Long = 0L
    }

    private val gates = ConcurrentHashMap<String, Gate>()

    private fun gateFor(host: String): Gate =
        gates.getOrPut(host.lowercase()) { Gate() }

    /** Block until this host's next request may start
     * (concurrency + spacing). Only ever called for flagged
     * hosts. */
    fun awaitTurn(host: String) {
        val gate = gateFor(host)
        gate.semaphore.acquire()
        try {
            synchronized(gate) {
                val now = System.currentTimeMillis()
                val wait = gate.lastStartMs + MIN_SPACING_MS - now
                if (wait > 0) {
                    Thread.sleep(wait)
                }
                gate.lastStartMs = System.currentTimeMillis()
            }
        } catch (interrupted: InterruptedException) {
            gate.semaphore.release()
            Thread.currentThread().interrupt()
            throw interrupted
        }
    }

    fun release(host: String) {
        gates[host.lowercase()]?.semaphore?.release()
    }
}

/**
 * The OkHttp half of [HostPoliteness]: flagged-host
 * requests wait their turn before proceeding and release
 * the slot when the response arrives (the semaphore is
 * released on response close as well as on return, via the
 * response body wrapper OkHttp gives interceptors — here:
 * release in a finally around proceed, which covers the
 * header phase; bodies stream after, an accepted slack —
 * the cap's purpose is burst-shaping, not accounting).
 */
class HostPolitenessInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val host = chain.request().url.host
        if (!HostPoliteness.isHostile(host)) {
            return chain.proceed(chain.request())
        }
        HostPoliteness.awaitTurn(host)
        try {
            return chain.proceed(chain.request())
        } finally {
            HostPoliteness.release(host)
        }
    }
}
