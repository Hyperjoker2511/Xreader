package app.xreader.data.extension

import java.util.concurrent.ConcurrentHashMap

/**
 * v1.3.4 (T3): the shared Retry-After scheduler. When a site
 * answers 429 (or a 503 that CARRIES a Retry-After), it has
 * told the world when to come back — every fetch for that
 * source then waits out the SAME cooldown instead of each
 * caller starting its own retry clock and hammering the
 * site (the anti-storm rule from the Prompt N reviews).
 *
 * Keyed by session key (one source's cooldown never touches
 * another's). A request that arrives during the window
 * fails fast with the availability error — the honest
 * "temporarily unavailable" state — instead of reaching the
 * network at all. The clock is injectable for tests.
 */
class AvailabilityCooldowns(
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {
    private val untilMs = ConcurrentHashMap<String, Long>()

    /** The HTTP status that caused the current park (429 vs
     * 503 copy differs — the park error names its origin). */
    private val originStatus = ConcurrentHashMap<String, Int>()

    /** Park [sessionKey] until now + [retryAfterSeconds]
     * (a later, longer park wins; a shorter one never
     * shortens an existing park). */
    fun record(sessionKey: String, retryAfterSeconds: Long, status: Int? = null) {
        if (retryAfterSeconds <= 0) return
        val until = nowMs() + retryAfterSeconds * 1000
        untilMs.merge(sessionKey, until) { a, b -> maxOf(a, b) }
        if (status != null) originStatus[sessionKey] = status
    }

    /** The status that parked [sessionKey], when known. */
    fun originStatus(sessionKey: String): Int? = originStatus[sessionKey]

    /** Milliseconds left in [sessionKey]'s park; 0 = clear. */
    fun remainingMs(sessionKey: String): Long {
        val until = untilMs[sessionKey] ?: return 0
        val left = until - nowMs()
        if (left <= 0) {
            untilMs.remove(sessionKey, until)
            originStatus.remove(sessionKey)
            return 0
        }
        return left
    }

    fun clear(sessionKey: String) {
        untilMs.remove(sessionKey)
        originStatus.remove(sessionKey)
    }
}

/** The process-wide instance the fetch stack shares. */
object SharedAvailability {
    val cooldowns = AvailabilityCooldowns()
}
