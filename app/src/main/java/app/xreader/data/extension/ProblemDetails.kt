package app.xreader.data.extension

/**
 * v1.0.5 (E5): one-tap problem details. When a test-drive or a
 * Repair fails, the user can copy a compact plain-text summary —
 * site host, failing step, reason, app version — instead of
 * screenshotting the app and having the failure reconstructed
 * from the picture. The format is fixed and boring on purpose:
 * it is written for a person (or a support chat) to read at a
 * glance. Pure formatter, pinned by unit tests.
 */
object ProblemDetails {

    /**
     * @param host the site's host (e.g. "novelight.net"); the
     * line is omitted when unknown.
     * @param failingStep the plain name of the step that failed
     * (e.g. "Chapter lists"); the line is omitted when the
     * failure can't be blamed on one step.
     * @param reason the plain-words failure message.
     * @param appVersion the app version name (BuildConfig).
     * @param repair the last Repair's persisted record
     * (v1.0.6); when present, its outcome, note and stage log
     * are appended, so one paste tells the whole story. When
     * absent the output is exactly what it was before.
     */
    fun format(
        host: String?,
        failingStep: String?,
        reason: String,
        appVersion: String,
        repair: SourceRepairRecord? = null,
        /** v1.0.6 (W3.5): pass true when the source reads through
         * the browser view — the payload gains a "Reading mode"
         * line. Callers that don't know pass nothing and their
         * output stays byte-identical. */
        browserView: Boolean = false,
        /** v1.0.6.1 (P6): the session's recent problems
         * ([DiagnosticLog]); when non-empty, a short history is
         * appended after the version line, so the paste carries
         * its own evidence. Callers pass the log explicitly —
         * with none passed (or an empty log) the output stays
         * byte-identical to before. */
        recentProblems: List<DiagnosticLog.Entry> = emptyList()
    ): String {
        val lines = mutableListOf("Xreader problem details")
        if (!host.isNullOrBlank()) {
            lines += "Site: ${host.trim()}"
        }
        if (!failingStep.isNullOrBlank()) {
            lines += "Failing step: ${failingStep.trim()}"
        }
        val cleanReason = reason.trim().ifBlank { "No reason was given." }
        lines += "Problem: $cleanReason"
        if (browserView) {
            lines += "Reading mode: browser view"
        }
        if (repair != null) {
            lines += if (repair.saved) {
                "Last repair: rebuilt the rules and saved them."
            } else {
                "Last repair: didn't change the rules."
            }
            if (repair.note.isNotBlank()) {
                lines += "Repair note: ${repair.note.trim()}"
            }
            if (repair.stageLines.isNotEmpty()) {
                lines += "Repair log:"
                repair.stageLines.forEach { lines += "- $it" }
            }
        }
        lines += "App version: $appVersion"
        if (recentProblems.isNotEmpty()) {
            lines += "Recent problems (this session):"
            recentProblems.forEach { entry ->
                val line = "- ${entry.timeText()} · ${entry.tag} · ${entry.message}"
                lines += if (entry.detail.isNotBlank()) {
                    "$line (${entry.detail})"
                } else {
                    line
                }
            }
        }
        return lines.joinToString("\n")
    }
}
