package app.xreader.data.db

import java.security.MessageDigest

/**
 * v1.5.2 (§1): the durable pending-operation store. A completed
 * solve/sign-in is heard WITHOUT any event: promotion reconciles the
 * record against the vault's ground truth (a content watermark —
 * SessionData carries cookies + UA and no timestamp) whenever a
 * surface resumes, the solve screen exits, or the app foregrounds.
 * READY → RUNNING is a single compare-and-swap claim (§1 exactly-once
 * about EFFECT: blocked actions behind a claim are idempotent).
 *
 * Round-15 closure (§1 operation trace): EVERY state transition is
 * persisted WITH the record as a "FROM>TO@millis:CATEGORY" trace
 * entry (ids, hosts, category, status only — never credential
 * values). The two transitions several actors can race — promotion
 * (WAITING → READY) and the claim (READY → RUNNING), plus RUNNING
 * recovery at process start — go through compare-and-swap DAO
 * updates, and only the CAS winner appends its trace entry, so the
 * trace can never show a promotion or a claim twice.
 */
class PendingOperationStore(private val dao: PendingOperationDao) {

    companion object {
        const val EXPIRY_MS: Long = 30L * 60L * 1000L
        val TERMINAL = setOf("SUCCEEDED", "FAILED", "CANCELLED", "EXPIRED")

        fun watermarkOf(sessionData: String?): String {
            if (sessionData == null) return "none"
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(sessionData.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }.take(32)
        }
    }

    /** Append one transition to the record's persisted trace. The
     * caller has already landed the state change (for raced
     * transitions, only the CAS winner calls this). */
    private suspend fun appendTrace(
        sessionKey: String, from: String, to: String, now: Long, category: String
    ) {
        val op = dao.getByKey(sessionKey) ?: return
        val entry = "$from>$to@$now:$category"
        val trace = if (op.trace.isBlank()) entry else "${op.trace};$entry"
        dao.upsert(op.copy(trace = trace))
    }

    suspend fun register(
        sessionKey: String,
        flowKind: String,
        host: String,
        currentWatermark: String,
        category: String = "NONE",
        now: Long
    ): PendingOperation {
        val existing = dao.getByKey(sessionKey)
        existing?.takeIf { it.state !in TERMINAL }?.let { return it }
        // v1.5.2 fix (FIX-2): a TERMINAL record must not block a new
        // operation for the same key — the old upsert keyed on the
        // autoincrement PK only, so the fresh row collided with the
        // sessionKey UNIQUE index and the caller's runCatching
        // swallowed it (a legitimate second solve never registered).
        if (existing != null) dao.deleteByKey(sessionKey)
        val op = PendingOperation(
            sessionKey = sessionKey, flowKind = flowKind, host = host,
            state = "WAITING", createdAt = now, watermark = currentWatermark,
            category = category,
            trace = "NONE>WAITING@$now:$category"
        )
        dao.upsert(op)
        return dao.getByKey(sessionKey) ?: op
    }

    /** Event-free promotion evaluation. Returns the (possibly updated)
     * record. RUNNING found at process start returns to READY (§1
     * RUNNING recovery). */
    suspend fun evaluate(
        sessionKey: String,
        currentWatermark: String,
        now: Long,
        processStart: Boolean = false
    ): PendingOperation? {
        var op = dao.getByKey(sessionKey) ?: return null
        if (op.state in TERMINAL) return op
        if (now - op.createdAt > EXPIRY_MS) {
            dao.setState(sessionKey, "EXPIRED")
            appendTrace(sessionKey, op.state, "EXPIRED", now, op.category)
            return op.copy(state = "EXPIRED")
        }
        if (processStart && op.state == "RUNNING") {
            // CAS: exactly one start-up evaluator records the
            // recovery; a loser simply re-reads the READY record.
            if (dao.recoverRunning(sessionKey) == 1) {
                appendTrace(sessionKey, "RUNNING", "READY", now, op.category)
            }
            op = dao.getByKey(sessionKey) ?: return null
        }
        if (op.state == "WAITING" && currentWatermark != op.watermark) {
            // CAS promotion: however many surfaces evaluate at the
            // same moment, WAITING → READY happens exactly once.
            if (dao.promoteToReady(sessionKey) == 1) {
                appendTrace(sessionKey, "WAITING", "READY", now, op.category)
            }
            op = dao.getByKey(sessionKey) ?: return null
        }
        return op
    }

    /** The atomic claim: true only for the single winner. The
     * winner's transition is traced; a loser's attempt leaves no
     * trace entry and no state change. */
    suspend fun claim(
        sessionKey: String,
        token: String,
        now: Long = System.currentTimeMillis()
    ): Boolean {
        val won = dao.claim(sessionKey, token) == 1
        if (won) {
            val category = dao.getByKey(sessionKey)?.category ?: "NONE"
            appendTrace(sessionKey, "READY", "RUNNING", now, category)
        }
        return won
    }

    suspend fun finish(
        sessionKey: String,
        success: Boolean,
        failureClass: String? = null,
        now: Long = System.currentTimeMillis()
    ) {
        val before = dao.getByKey(sessionKey) ?: return
        if (before.state in TERMINAL) return
        val state = if (success) "SUCCEEDED" else "FAILED"
        dao.setState(sessionKey, state)
        appendTrace(sessionKey, before.state, state, now, before.category)
        if (failureClass != null) {
            dao.getByKey(sessionKey)?.let {
                dao.upsert(it.copy(lastFailureClass = failureClass))
            }
        }
    }

    /** §1 named abandonment callers only: explicit close, supersede,
     * source deletion. */
    suspend fun cancel(
        sessionKey: String,
        now: Long = System.currentTimeMillis()
    ) {
        val op = dao.getByKey(sessionKey) ?: return
        if (op.state in TERMINAL) return
        dao.setState(sessionKey, "CANCELLED")
        appendTrace(sessionKey, op.state, "CANCELLED", now, op.category)
    }

    suspend fun prune(now: Long): Int =
        dao.pruneTerminalBefore(now - 7L * 24L * 3600L * 1000L)
}
