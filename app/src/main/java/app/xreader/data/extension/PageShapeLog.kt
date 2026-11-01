package app.xreader.data.extension

import java.util.concurrent.ConcurrentHashMap

/**
 * v1.3.4 (T4): the PAGE-SHAPE record — what a fetch actually
 * received, kept per source so the next wall variant (or
 * mirror answer, or DNS death) is DATA on the source's
 * diagnostics (Repair) screen instead of a guess.
 *
 * Recorded by the fetch layer for every GET, rendered or
 * static: the HTTP status (null when no response arrived),
 * the page title, the challenge-marker hits, the host that
 * actually answered (a mirror shows itself here), and the
 * transport error's plain marker when there was one. The
 * catalogue layer adds the extracted-row count when a
 * listing comes back empty — the exact evidence the
 * BrightNovel class of failure was missing.
 *
 * REDACTED by construction: no bodies, no headers, no
 * cookies, no credentials — shape only. In-memory (the
 * current session's evidence) and mirrored into
 * [DiagnosticLog] for the shareable problem report.
 */
data class PageShape(
    val sessionKey: String,
    val status: Int? = null,
    val title: String? = null,
    val markers: List<String> = emptyList(),
    val rows: Int? = null,
    val host: String = "",
    val errorMarker: String? = null,
    val atMs: Long = 0L
) {
    /** One plain line for the diagnostics screen. */
    fun summary(): String = buildString {
        append("Last page received: ")
        append(status?.let { "HTTP $it" } ?: "no answer")
        title?.takeIf { it.isNotBlank() }?.let { append(" · “$it”") }
        if (markers.isNotEmpty()) {
            append(" · markers: ${markers.joinToString(", ")}")
        }
        rows?.let { append(" · $it rows read") }
        if (host.isNotBlank()) append(" · from $host")
        errorMarker?.let { append(" · $it") }
    }
}

object PageShapeLog {

    private val bySession = ConcurrentHashMap<String, PageShape>()

    /** The newest shape wins per field: a fetch record sets
     * the transport fields; a catalogue note adds its row
     * count to the record its own fetch just made. */
    fun record(shape: PageShape) {
        bySession.merge(shape.sessionKey, shape) { old, new ->
            new.copy(rows = new.rows ?: old.rows)
        }
        // Only NOTABLE shapes enter the shared diagnostic log
        // (it is capped and user-shareable): a wall, an error,
        // a non-answer. Healthy fetches stay in-memory only.
        val notable = shape.errorMarker != null ||
            shape.markers.isNotEmpty() ||
            (shape.status ?: 0) >= 400 || shape.status == null
        if (notable) {
            DiagnosticLog.record("page-shape", shape.summary(), shape.sessionKey)
        }
    }

    /** Attach an extracted-row count to the session's last
     * fetch record (the catalogue's empty-listing note). */
    fun noteRows(sessionKey: String, rows: Int) {
        val old = bySession[sessionKey] ?: PageShape(
            sessionKey = sessionKey, atMs = System.currentTimeMillis()
        )
        bySession[sessionKey] = old.copy(rows = rows)
    }

    fun lastFor(sessionKey: String): PageShape? = bySession[sessionKey]

    fun clearForTests() = bySession.clear()

    /** The challenge markers a body carries, by name — the
     * same phrases/markup [SiteCheck] judges by, listed for
     * the diagnostics instead of re-derived there. */
    fun markersIn(body: String): List<String> {
        val text = body.lowercase()
        val found = mutableListOf<String>()
        for (phrase in listOf(
            "just a moment", "checking your browser",
            "verify you are human", "are you a robot",
            "attention required"
        )) {
            if (phrase in text) found += phrase
        }
        if ("cf-turnstile" in text) found += "cf-turnstile"
        if ("challenge-form" in text) found += "challenge-form"
        if ("challenges.cloudflare" in text) found += "challenges.cloudflare"
        return found
    }

    /** A page's <title>, trimmed and capped — shape, not content. */
    fun titleOf(body: String): String? {
        val match = Regex("(?is)<title[^>]*>(.*?)</title>").find(body)
            ?: return null
        return match.groupValues[1].replace(Regex("\\s+"), " ")
            .trim().take(120).ifBlank { null }
    }
}
