package app.xreader.domain.reader

/**
 * Active-reading time accounting (PLAN §6/§8): time only counts while
 * the reader is actually reading — interaction (page turn, scroll,
 * tap) refreshes an activity stamp, and time stops accruing
 * [idleTimeoutMs] after the last interaction (the anti-idle-farming
 * rule Phase 6's EXP engine relies on). Pure, injected clock.
 */
class SessionTracker(
    private val clock: () -> Long,
    private val idleTimeoutMs: Long = DEFAULT_IDLE_TIMEOUT_MS
) {
    private var startedAtMs: Long? = null
    private var accruedMs: Long = 0L
    private var segmentStartMs: Long? = null
    private var lastActivityMs: Long = 0L

    var wordsRead: Long = 0L
        private set
    var chaptersFinished: Int = 0
        private set

    val isRunning: Boolean get() = startedAtMs != null

    fun begin() {
        if (startedAtMs != null) return
        val now = clock()
        startedAtMs = now
        lastActivityMs = now
        segmentStartMs = now
    }

    /** Any genuine reading interaction: page turn, scroll, highlight.
     * Banks whatever the previous segment had accrued (capped at its
     * idle timeout) before starting a fresh segment, so a long silent
     * gap is never counted when reading resumes. */
    fun onActivity() {
        if (startedAtMs == null) return
        val now = clock()
        accruedMs = activeMillis(now)
        segmentStartMs = now
        lastActivityMs = now
    }

    fun addWords(words: Int) {
        if (words > 0) wordsRead += words
    }

    fun addChaptersFinished(count: Int = 1) {
        if (count > 0) chaptersFinished += count
    }

    /**
     * Active milliseconds so far. A live segment stops accruing at
     * lastActivity + idleTimeout even if nobody calls [pause] — an
     * open-but-untouched reader earns nothing.
     */
    fun activeMillis(now: Long = clock()): Long {
        val segment = segmentStartMs ?: return accruedMs
        val cap = lastActivityMs + idleTimeoutMs
        val effectiveEnd = minOf(now, cap)
        val live = maxOf(0L, effectiveEnd - segment)
        return accruedMs + live
    }

    fun activeMinutes(now: Long = clock()): Int =
        (activeMillis(now) / 60_000L).toInt()

    /** Freezes accrual (app backgrounded / reader closed mid-session). */
    fun pause() {
        if (startedAtMs == null) return
        accruedMs = activeMillis(clock())
        segmentStartMs = null
    }

    fun resume() {
        if (startedAtMs == null) return
        if (segmentStartMs == null) {
            val now = clock()
            segmentStartMs = now
            lastActivityMs = now
        }
    }

    /** Ends the session and returns its record; counters reset. */
    fun end(): Snapshot {
        val now = clock()
        val snapshot = Snapshot(
            startedAt = startedAtMs ?: now,
            endedAt = now,
            activeMinutes = activeMinutes(now),
            wordsRead = wordsRead,
            chaptersFinished = chaptersFinished
        )
        startedAtMs = null
        accruedMs = 0L
        segmentStartMs = null
        wordsRead = 0L
        chaptersFinished = 0
        return snapshot
    }

    data class Snapshot(
        val startedAt: Long,
        val endedAt: Long,
        val activeMinutes: Int,
        val wordsRead: Long,
        val chaptersFinished: Int
    )

    companion object {
        const val DEFAULT_IDLE_TIMEOUT_MS = 120_000L
    }
}
