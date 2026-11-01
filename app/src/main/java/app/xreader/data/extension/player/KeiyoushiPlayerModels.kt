package app.xreader.data.extension.player

import kotlinx.serialization.Serializable

/**
 * K3 (Keiyoushi player): the per-extension state machine —
 * pure data and pure transitions, so every state a shelf row
 * can show is pinned by JVM tests and the Android manager is
 * a thin executor over it.
 *
 * The states are the plan's plain-words set:
 * Not installed · Downloading · Verifying · Ready ·
 * Update available · Failed (reason named) · Incompatible
 * (reason named) — plus the two honest in-between states the
 * flow genuinely passes through (awaiting the user's consent,
 * and starting). "Update available" is a FLAG on a Ready (or
 * failed) record rather than a status: the installed version
 * keeps working while the newer one waits.
 */
enum class KeiyoushiExtStatus {
    NOT_INSTALLED,
    DOWNLOADING,
    VERIFYING,
    AWAITING_CONSENT,
    LOADING,
    READY,
    FAILED,
    INCOMPATIBLE
}

/** The durable record of one Keiyoushi package. File-backed
 * (see [FileKeiyoushiRecordStore]) — no Room migration rides
 * this slice; the InstalledExtension row mirrors identity for
 * the rest of the app (sources list, library books). */
@Serializable
data class KeiyoushiPlayerRecord(
    val packageName: String,
    val displayName: String,
    val status: KeiyoushiExtStatus = KeiyoushiExtStatus.NOT_INSTALLED,
    /** Plain-words cause for FAILED / INCOMPATIBLE; on a
     * READY record, the last notice (e.g. an update that
     * failed to stage — the installed version still works). */
    val failureReason: String? = null,
    /** Technical detail for the problem-details view. */
    val failureDetail: String? = null,
    /** The index's current version for this package. */
    val indexVersionCode: Long = 0,
    val indexVersionName: String = "",
    /** What is actually installed (0 = nothing installed). */
    val installedVersionCode: Long = 0,
    val installedVersionName: String = "",
    /** A newer index version waiting (0 = none). */
    val availableVersionCode: Long = 0,
    val entryClass: String? = null,
    val extensionLib: String = "",
    /** SHA-256 of the signing certificate, as read from the
     * verified APK (must equal the index's published key). */
    val certSha256: String? = null,
    /** SHA-256 of the installed APK bytes — the pinned
     * fingerprint, re-checked before EVERY load. */
    val apkSha256: String? = null,
    /** The fingerprint the user consented to on the install
     * sheet. A load is allowed only for exactly these bytes. */
    val consentedSha256: String? = null,
    /** A downloaded update/install awaiting consent. */
    val stagedSha256: String? = null,
    val stagedVersionCode: Long = 0,
    val stagedVersionName: String = "",
    /** Namespaced source ids ("keiyoushi:<pkg>:<mihonId>"). */
    val sourceIds: List<String> = emptyList(),
    /** Hosts this extension's sources live on — the session
     * cookies an uninstall must clear. */
    val sourceHosts: List<String> = emptyList(),
    val languages: String = "",
    /** The InstalledExtension row mirroring this package. */
    val extensionRowId: Long = 0,
    val updatedAt: Long = 0,
    /** K6 (crash safety): this package has completed at least
     * one successful load — a version on disk is PROVEN to
     * run. This, not [installedVersionCode], is what "a
     * working version exists" means for the failure rule: an
     * install's bytes are pinned before their first load, so
     * version fields alone would call an unproven install
     * "working" and land its first-load failure in READY
     * with no sources behind it. Set by LoadSucceeded;
     * restore migrates it for records a previous build left
     * READY with sources. */
    val hasLoadedOnce: Boolean = false,
    /** K6 (crash safety): a load was on the wire when the
     * record was last persisted. The manager arms this
     * immediately before the loader runs and disarms it on
     * every outcome; a record found armed at startup means
     * the process died mid-load — the package is quarantined
     * (FAILED, never auto-retried) instead of crash-looping
     * every launch. */
    val loadInFlight: Boolean = false
) {
    val isInstalled: Boolean get() = installedVersionCode > 0

    /** The Mihon source id namespaced the K4 way:
     * Mihon Long ids never collide with ours or each other's
     * because the package name rides in the middle. */
    fun namespacedSourceId(mihonId: Long): String =
        "keiyoushi:$packageName:$mihonId"

    companion object {
        fun namespacedSourceId(packageName: String, mihonId: Long): String =
            "keiyoushi:$packageName:$mihonId"
    }
}

/** State machine events. The manager fires these; the
 * machine owns which transitions exist. */
sealed interface KeiyoushiEvent {
    data object DownloadStarted : KeiyoushiEvent
    data object Downloaded : KeiyoushiEvent
    data class DownloadFailed(
        val reason: String, val detail: String? = null
    ) : KeiyoushiEvent

    data object Verified : KeiyoushiEvent
    data class VerifyFailed(
        val reason: String, val detail: String? = null
    ) : KeiyoushiEvent

    data class MarkedIncompatible(
        val reason: String, val detail: String? = null
    ) : KeiyoushiEvent

    data object LoadStarted : KeiyoushiEvent
    data class LoadSucceeded(
        val sourceIds: List<String>,
        val sourceHosts: List<String>,
        val languages: String
    ) : KeiyoushiEvent

    data class LoadFailed(
        val reason: String, val detail: String? = null
    ) : KeiyoushiEvent

    /** K6 (crash safety): the load died HARD — an Error or
     * linkage failure, or the process died with the load in
     * flight (restore applies this verdict itself). The
     * package is quarantined: FAILED even when a proven
     * version exists, and restore never auto-retries a
     * FAILED record — only the user's Install tap runs it
     * again. */
    data class LoadQuarantined(
        val reason: String, val detail: String? = null
    ) : KeiyoushiEvent
}

/**
 * The transition table. Illegal transitions throw
 * [IllegalStateException] — the manager catches that as a
 * programming error and lands the record in FAILED rather
 * than crashing the shelf.
 *
 * One deliberate subtlety: a failure while UPDATING (an
 * installed record staging a newer version) returns the
 * record to READY with the reason kept as a notice — the
 * working version is never sacrificed to a failed update.
 * "Working" means PROVEN (K6): the record has completed a
 * load before ([KeiyoushiPlayerRecord.hasLoadedOnce]). An
 * unproven install's first failure lands in FAILED — READY
 * with no live sources behind it would be a lie on the
 * shelf. A HARD failure (Error/linkage, or a process death
 * mid-load) quarantines even a proven package: FAILED until
 * the user retries.
 */
object KeiyoushiStateMachine {

    fun transition(
        record: KeiyoushiPlayerRecord,
        event: KeiyoushiEvent
    ): KeiyoushiPlayerRecord {
        fun fail(reason: String, detail: String?): KeiyoushiPlayerRecord =
            if (record.hasLoadedOnce) {
                record.copy(
                    status = KeiyoushiExtStatus.READY,
                    failureReason = reason,
                    failureDetail = detail,
                    stagedSha256 = null,
                    stagedVersionCode = 0,
                    stagedVersionName = ""
                )
            } else {
                record.copy(
                    status = KeiyoushiExtStatus.FAILED,
                    failureReason = reason,
                    failureDetail = detail,
                    stagedSha256 = null,
                    stagedVersionCode = 0,
                    stagedVersionName = ""
                )
            }

        return when (event) {
            KeiyoushiEvent.DownloadStarted -> {
                check(
                    record.status in setOf(
                        KeiyoushiExtStatus.NOT_INSTALLED,
                        KeiyoushiExtStatus.READY,
                        KeiyoushiExtStatus.FAILED,
                        KeiyoushiExtStatus.INCOMPATIBLE,
                        KeiyoushiExtStatus.AWAITING_CONSENT
                    )
                ) { "cannot start a download from ${record.status}" }
                record.copy(
                    status = KeiyoushiExtStatus.DOWNLOADING,
                    failureReason = null,
                    failureDetail = null
                )
            }

            KeiyoushiEvent.Downloaded -> {
                check(record.status == KeiyoushiExtStatus.DOWNLOADING) {
                    "download finished from ${record.status}"
                }
                record.copy(status = KeiyoushiExtStatus.VERIFYING)
            }

            is KeiyoushiEvent.DownloadFailed ->
                fail(event.reason, event.detail)

            KeiyoushiEvent.Verified -> {
                check(record.status == KeiyoushiExtStatus.VERIFYING) {
                    "verified from ${record.status}"
                }
                record.copy(status = KeiyoushiExtStatus.AWAITING_CONSENT)
            }

            is KeiyoushiEvent.VerifyFailed ->
                fail(event.reason, event.detail)

            is KeiyoushiEvent.MarkedIncompatible -> {
                check(
                    record.status in setOf(
                        KeiyoushiExtStatus.VERIFYING,
                        KeiyoushiExtStatus.LOADING,
                        KeiyoushiExtStatus.NOT_INSTALLED
                    )
                ) { "incompatible verdict from ${record.status}" }
                if (record.hasLoadedOnce) {
                    record.copy(
                        status = KeiyoushiExtStatus.READY,
                        failureReason = event.reason,
                        failureDetail = event.detail,
                        stagedSha256 = null,
                        stagedVersionCode = 0,
                        stagedVersionName = ""
                    )
                } else {
                    record.copy(
                        status = KeiyoushiExtStatus.INCOMPATIBLE,
                        failureReason = event.reason,
                        failureDetail = event.detail,
                        stagedSha256 = null,
                        stagedVersionCode = 0,
                        stagedVersionName = ""
                    )
                }
            }

            KeiyoushiEvent.LoadStarted -> {
                check(
                    record.status in setOf(
                        KeiyoushiExtStatus.AWAITING_CONSENT,
                        KeiyoushiExtStatus.NOT_INSTALLED,
                        KeiyoushiExtStatus.READY,
                        KeiyoushiExtStatus.FAILED
                    )
                ) { "load started from ${record.status}" }
                record.copy(status = KeiyoushiExtStatus.LOADING)
            }

            is KeiyoushiEvent.LoadSucceeded -> {
                check(record.status == KeiyoushiExtStatus.LOADING) {
                    "load succeeded from ${record.status}"
                }
                record.copy(
                    status = KeiyoushiExtStatus.READY,
                    failureReason = null,
                    failureDetail = null,
                    hasLoadedOnce = true,
                    sourceIds = event.sourceIds,
                    sourceHosts = event.sourceHosts,
                    languages = event.languages,
                    availableVersionCode = 0,
                    stagedSha256 = null,
                    stagedVersionCode = 0,
                    stagedVersionName = ""
                )
            }

            is KeiyoushiEvent.LoadFailed ->
                fail(event.reason, event.detail)

            is KeiyoushiEvent.LoadQuarantined -> {
                check(
                    record.status in setOf(
                        KeiyoushiExtStatus.LOADING,
                        KeiyoushiExtStatus.READY,
                        KeiyoushiExtStatus.FAILED
                    )
                ) { "quarantine from ${record.status}" }
                // FAILED even for a proven package: a hard
                // death is never waved through as "the old
                // version still works" — its sources stay out
                // of the registry until the user retries.
                record.copy(
                    status = KeiyoushiExtStatus.FAILED,
                    failureReason = event.reason,
                    failureDetail = event.detail,
                    stagedSha256 = null,
                    stagedVersionCode = 0,
                    stagedVersionName = ""
                )
            }
        }
    }

    /** A transient state left behind by a dead process (the
     * app was killed mid-download) normalises at restore:
     * an installed, consented package goes back to LOADING
     * (the restore path re-loads it); anything else reads as
     * the honest interruption. */
    fun normaliseAfterRestart(
        record: KeiyoushiPlayerRecord
    ): KeiyoushiPlayerRecord = when (record.status) {
        KeiyoushiExtStatus.DOWNLOADING,
        KeiyoushiExtStatus.VERIFYING,
        KeiyoushiExtStatus.LOADING ->
            if (record.consentedSha256 != null) {
                // An installed, consented package: the
                // restore path re-loads it (the load gate
                // re-checks the bytes before any code runs).
                record.copy(status = KeiyoushiExtStatus.LOADING)
            } else if (record.isInstalled) {
                record.copy(
                    status = KeiyoushiExtStatus.READY,
                    failureReason =
                        "an update was interrupted — the version you had still works",
                    stagedSha256 = null,
                    stagedVersionCode = 0,
                    stagedVersionName = ""
                )
            } else {
                record.copy(
                    status = KeiyoushiExtStatus.FAILED,
                    failureReason =
                        "the install was interrupted — tap Install to try again",
                    stagedSha256 = null,
                    stagedVersionCode = 0,
                    stagedVersionName = ""
                )
            }

        KeiyoushiExtStatus.AWAITING_CONSENT ->
            // The staged bytes may not have survived; the
            // consent sheet must be re-earned from a fresh
            // download, never resumed blind.
            record.copy(
                status = if (record.isInstalled) {
                    KeiyoushiExtStatus.READY
                } else {
                    KeiyoushiExtStatus.NOT_INSTALLED
                },
                stagedSha256 = null,
                stagedVersionCode = 0,
                stagedVersionName = ""
            )

        else -> record
    }
}

/**
 * The load gate — slice A's DexGate rules restated for the
 * player track (the DexGate itself keys on the facade track's
 * DEX kind): code loads only when the user consented to
 * EXACTLY the bytes on disk, and the bytes still hash to the
 * pinned fingerprint. Returns the refusal in plain words, or
 * null when the load may proceed.
 */
object KeiyoushiLoadGate {

    fun refusalReason(
        record: KeiyoushiPlayerRecord,
        onDiskSha256: String?
    ): String? {
        if (record.consentedSha256 == null) {
            return "you haven't agreed to run this source's code yet"
        }
        if (onDiskSha256 == null) {
            return "its file is missing — install it again"
        }
        if (!onDiskSha256.equals(record.apkSha256, ignoreCase = true)) {
            return "the file on disk no longer matches the fingerprint recorded at install"
        }
        if (!onDiskSha256.equals(record.consentedSha256, ignoreCase = true)) {
            return "the file changed since you agreed to it — review and agree again before it runs"
        }
        return null
    }
}

/** The uninstall cascade, as a checklist the manager
 * executes in order (and tests assert whole). Library books
 * from the extension's sources are NOT a step: they stay,
 * and their source reads as gone through the existing
 * orphan handling. */
object KeiyoushiUninstall {

    enum class Step {
        /** Drop the live Source instances from the registry. */
        REMOVE_SOURCES,
        /** Delete the .ext file. */
        REMOVE_APK,
        /** Per-extension prefs files + files/cache dirs. */
        REMOVE_NAMESPACED_DATA,
        /** SessionStore cookies for the sources' hosts +
         * the extension's own session record. */
        REMOVE_SESSION_COOKIES,
        /** The mirroring InstalledExtension row. */
        REMOVE_ROW,
        /** The player record itself. */
        REMOVE_RECORD
    }

    fun plan(record: KeiyoushiPlayerRecord): List<Step> = listOf(
        Step.REMOVE_SOURCES,
        Step.REMOVE_APK,
        Step.REMOVE_NAMESPACED_DATA,
        Step.REMOVE_SESSION_COOKIES,
        Step.REMOVE_ROW,
        Step.REMOVE_RECORD
    )
}
