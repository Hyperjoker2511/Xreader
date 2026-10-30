package app.xreader.data.extension

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v1.0.6.1 (P6): a bounded, in-memory history of the session's
 * recent problems — the last [MAX_ENTRIES] failures, each with
 * the failure site's own detail (for a refused link: the rejected
 * input's length and the invisible code points riding in it).
 *
 * It exists so a fault report carries its own evidence: Copy
 * details ([ProblemDetails]) appends these entries, and the next
 * diagnosis starts from facts instead of a screenshot and a
 * guess. Deliberately nothing more than this: no persistence, no
 * Room, no disk I/O, no startup cost — the log dies with the
 * process, exactly like the session it describes.
 */
object DiagnosticLog {

    data class Entry(
        val atMillis: Long,
        val tag: String,
        val message: String,
        val detail: String = ""
    ) {
        /** "07:12" wall-clock, for pasted problem details. */
        fun timeText(): String =
            SimpleDateFormat("HH:mm", Locale.ROOT).format(Date(atMillis))
    }

    private const val MAX_ENTRIES = 5

    /** Entry budget (~500 chars total, P6): the message and the
     * detail are capped at record time so no single failure can
     * bloat a copied report. */
    private const val MAX_MESSAGE_CHARS = 340
    private const val MAX_DETAIL_CHARS = 130
    private const val MAX_TAG_CHARS = 24

    private val lock = Any()
    private val buffer = ArrayDeque<Entry>()

    fun record(tag: String, message: String, detail: String = "") {
        val entry = Entry(
            atMillis = System.currentTimeMillis(),
            tag = tag.trim().take(MAX_TAG_CHARS),
            message = message.trim().take(MAX_MESSAGE_CHARS),
            detail = detail.trim().take(MAX_DETAIL_CHARS)
        )
        synchronized(lock) {
            buffer.addLast(entry)
            while (buffer.size > MAX_ENTRIES) buffer.removeFirst()
        }
    }

    /** Oldest first — the order a person reads a story in. */
    fun entries(): List<Entry> = synchronized(lock) { buffer.toList() }

    /** The log is process-wide; test suites reset it between
     * tests so one suite's failures never leak into another's
     * assertions. */
    fun clear() {
        synchronized(lock) { buffer.clear() }
    }
}
