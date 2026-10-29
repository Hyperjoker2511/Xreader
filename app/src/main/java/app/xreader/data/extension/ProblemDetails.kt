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
     */
    fun format(
        host: String?,
        failingStep: String?,
        reason: String,
        appVersion: String
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
        lines += "App version: $appVersion"
        return lines.joinToString("\n")
    }
}
