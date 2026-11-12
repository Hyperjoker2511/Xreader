package app.xreader.core.error

/**
 * A failure as the UI shows it (PLAN §1.5): one plain-words message plus
 * the label of the primary recovery action. Produced only by [ErrorText].
 */
data class ErrorUi(
    val message: String,
    val recoveryLabel: String
)

/**
 * The one place [AppError]s become words. The `when` is exhaustive on
 * purpose: a new [AppError] subclass cannot ship without a plain-words
 * message here — the compiler enforces it.
 */
object ErrorText {

    /**
     * v1.5.40: the marker the Gemini error mapping stamps into
     * a 429's detail when the key's quota limit is literally 0
     * (GeminiClient.httpError writes this exact phrase). A
     * zero-limit quota is a missing allowance, not a busy
     * service, so it gets its own words below.
     */
    const val NO_ALLOWANCE_MARKER = "quota limit: 0"

    fun of(error: AppError): ErrorUi = when (error) {
        AppError.NoNetwork ->
            ErrorUi("No internet connection.", "Try again")

        is AppError.ProviderDown ->
            ErrorUi("That service isn't answering right now.", "Try again")

        is AppError.RateLimited -> {
            // v1.3.4 (T3 refinement): 429 ≠ 503 in the user's
            // words. A 503 is the SITE having trouble (v1.5.40:
            // "isn't answering right now"); a 429 is the site
            // asking US to slow down ("too many requests") —
            // with the wait it named, when it named one.
            val waitSeconds = error.retryAfterSeconds ?: 0
            val wait = when {
                waitSeconds >= 90 ->
                    " Try again in about ${(waitSeconds + 59) / 60} minutes."
                waitSeconds >= 2 ->
                    " Try again in about $waitSeconds seconds."
                else -> ""
            }
            if (NO_ALLOWANCE_MARKER in (error.detail ?: "")) {
                // v1.5.40 (honest error words): a quota whose
                // limit is 0 is not "too many requests" — the
                // free key was never given an allowance for
                // this at all, and waiting cannot heal it.
                ErrorUi(
                    "Your free key has no painting allowance — " +
                        "painting needs billing on the key.",
                    "Check settings"
                )
            } else if ("503" in (error.detail ?: "")) {
                // v1.5.40: the 503 words are the answering
                // words, matching the source surfaces.
                ErrorUi(
                    "The site isn't answering right now.$wait",
                    "Try again"
                )
            } else {
                ErrorUi(
                    "Too many requests — the site asked us to slow down.$wait",
                    "Try again"
                )
            }
        }

        is AppError.AuthFailed ->
            ErrorUi("A saved key or password didn't work — check it and try again.", "Check settings")

        is AppError.ParseFailed ->
            ErrorUi("This ${error.format} file couldn't be read. ${error.reason}", "Choose another file")

        AppError.DrmProtected ->
            ErrorUi(
                "This book is protected by DRM, so Xreader can't open it. " +
                    "A DRM-free copy (for example, converted with Calibre) will work.",
                "Choose another file"
            )

        is AppError.ExtensionFailed ->
            ErrorUi("The source “${error.sourceName}” ran into a problem.", "Try again")

        is AppError.SyncConflict ->
            ErrorUi(
                "Your ${error.what} changed on two devices. Nothing was lost — " +
                    "open Sync to choose which copy wins.",
                "Open Sync"
            )

        AppError.StorageFull ->
            ErrorUi("Your device is out of storage space.", "Free up space")

        is AppError.StorageFailed ->
            ErrorUi("Something couldn't be saved on this device.", "Try again")

        is AppError.ExportFailed ->
            ErrorUi("The export couldn't be written. ${error.reason}", "Choose another place")

        is AppError.BadFile ->
            ErrorUi("That file couldn't be used. ${error.reason}", "Choose another file")

        is AppError.NeutralVerdict ->
            ErrorUi(error.message, "Got it")

        is AppError.Unknown ->
            ErrorUi("Something went wrong.", "Try again")
    }

    /**
     * The full display text for an error state: plain words first, then the
     * technical "Details:" line when the error carries one (PLAN §1.5 —
     * the line that lets a user screenshot diagnose a failure in one go).
     */
    fun displayText(error: AppError): String {
        val base = of(error).message
        val detail = error.detail?.takeIf { it.isNotBlank() } ?: return base
        return "$base\nDetails: $detail"
    }
}
