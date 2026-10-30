package app.xreader.core.error

/**
 * Every failure class in the PLAN §1.5 catalogue, as a type. The UI never
 * sees a raw exception: ViewModels map these through [ErrorText] — and that
 * mapper is an exhaustive `when`, so adding a subclass here without a
 * plain-words message is a compile error.
 *
 * Fields carry ids / class names / counts only — never API keys, cookies,
 * or book text — so errors are always safe to log and to show in the
 * "Details:" line and in a shareable crash note.
 */
sealed interface AppError {

    /**
     * Optional short technical detail for diagnostics: an exception class
     * name + message, or "HTTP <code>" plus the other side's own short
     * error snippet. Rendered by the UI as a small secondary "Details:"
     * line under the plain-words message. Never contains secrets or
     * user content.
     */
    val detail: String? get() = null

    /** No connectivity / airplane mode / DNS failure. */
    data object NoNetwork : AppError

    /** A remote service (Gemini, Drive, an OPDS server, a repo) timed out,
     * returned 5xx, or answered with an unparseable body. */
    data class ProviderDown(
        val serviceId: String,
        val httpCode: Int? = null,
        /** False for faults waiting cannot heal (4xx, malformed body). */
        val retryable: Boolean = true,
        override val detail: String? = null
    ) : AppError

    /** HTTP 429 — the service asked us to slow down. v1.0.7 gate
     * fix: gains the detail override its siblings have — a quota
     * 429's truth lives in the provider's own words ("quota
     * exceeded, check your plan"), and before this the class
     * could not carry them at all (the interface default is
     * null), so every 429 rendered as a bare "busy". */
    data class RateLimited(
        val serviceId: String,
        val retryAfterSeconds: Long? = null,
        override val detail: String? = null
    ) : AppError

    /** HTTP 401/403: a stored key/password is wrong, expired, or missing. */
    data class AuthFailed(
        val serviceId: String,
        override val detail: String? = null
    ) : AppError

    /** A book/chapter file could not be parsed. [reason] is one plain line. */
    data class ParseFailed(
        val format: String,
        val reason: String,
        override val detail: String? = null
    ) : AppError

    /** The book is DRM-protected; the app says so honestly, never fakes it. */
    data object DrmProtected : AppError

    /** An extension (source) failed behind its bridge. [sourceName] is the
     * user-facing name; the extension's own exception never crosses. */
    data class ExtensionFailed(
        val sourceName: String,
        override val detail: String? = null
    ) : AppError

    /** Sync found both sides changed the same data. */
    data class SyncConflict(val what: String) : AppError

    /** Not enough free storage on the device. */
    data object StorageFull : AppError

    /** A local database / file write failed. [operation] names the step. */
    data class StorageFailed(
        val operation: String,
        override val detail: String? = null
    ) : AppError

    /** An export could not be written to the chosen destination. */
    data class ExportFailed(val reason: String) : AppError

    /** Corrupt / empty / wrong-format file handed to an importer. */
    data class BadFile(val reason: String) : AppError

    /**
     * v1.0.6.2 (F9): a verdict that is NOT a failure — the feature
     * ran and the honest answer is "nothing to do" (the chapter is
     * already in English; there are no summaries yet; a feature is
     * switched off). Carries its own complete plain-words message:
     * UIs render it as a calm note, never as an error, and it never
     * acquires a failure prefix the way BadFile's reason does.
     */
    data class NeutralVerdict(val message: String) : AppError

    /** Anything not yet classified. Carries the exception class name only. */
    data class Unknown(val cause: Throwable) : AppError {
        val causeClass: String get() = cause.javaClass.simpleName
        override val detail: String? get() = causeClass
    }
}
