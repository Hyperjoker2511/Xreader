package app.xreader.data.extension

import app.xreader.data.db.InstalledExtension
import app.xreader.data.db.TrustState
import app.xreader.extensionapi.ExtensionApi
import app.xreader.data.db.ExtensionKind as DbExtensionKind

/**
 * The trust engine (PLAN §9.1) — pure decisions, no I/O, so every rule
 * is pinned by unit tests:
 *
 * - An artifact is only ever installed when its bytes hash to the
 *   SHA-256 the index declared (declarative: the fingerprint computed
 *   from its rules).
 * - An update whose fingerprint or permissions grew NEVER auto-applies
 *   — it stages and the extension freezes until the user re-trusts it.
 * - An unchanged-fingerprint update may auto-apply on the next refresh
 *   when the user left "update safe sources automatically" on —
 *   EXCEPT dex, which never auto-applies anything.
 * - An artifact on disk whose hash no longer matches the pinned
 *   fingerprint means tampering: freeze, never load.
 */
object TrustEngine {

    sealed interface InstallDecision {
        data class Accept(
            val fingerprint: String,
            val permissions: ExtensionPermissions
        ) : InstallDecision

        data class Refuse(val reason: String) : InstallDecision
    }

    /**
     * Decide an install. [computedFingerprint] is the hash of what was
     * actually downloaded (artifact bytes) or computed from the rules
     * (declarative). [artifactBytesMatch] is the artifact-vs-index
     * comparison for js/dex (always true for declarative).
     */
    fun decideInstall(
        pkg: IndexPackage,
        computedFingerprint: String,
        artifactBytesMatch: Boolean
    ): InstallDecision {
        val kind = pkg.kindEnum
            ?: return InstallDecision.Refuse("Xreader doesn't know this kind of source.")
        if (pkg.apiLevel > ExtensionApi.API_LEVEL) {
            return InstallDecision.Refuse(
                "This source needs a newer Xreader (extension API ${pkg.apiLevel})."
            )
        }
        return when (kind) {
            ExtensionKind.DECLARATIVE ->
                InstallDecision.Accept(computedFingerprint, pkg.installPermissions())
            ExtensionKind.JS, ExtensionKind.DEX -> {
                if (pkg.sha256.isBlank()) {
                    InstallDecision.Refuse(
                        "The repository didn't publish a fingerprint for this source, so it can't be checked. It wasn't installed."
                    )
                } else if (!artifactBytesMatch) {
                    InstallDecision.Refuse(
                        "The downloaded file didn't match the fingerprint the repository published. It wasn't installed — the file may be damaged or replaced."
                    )
                } else {
                    InstallDecision.Accept(computedFingerprint, pkg.installPermissions())
                }
            }
        }
    }

    sealed interface UpdateDecision {
        /** Nothing newer in the index. */
        data object UpToDate : UpdateDecision

        /** Same fingerprint, same-or-narrower permissions, policy
         * allows: apply without bothering the user. */
        data object AutoApply : UpdateDecision

        /** Safe update, but the user's policy asks first. */
        data object ApplyOnTap : UpdateDecision

        /** Fingerprint changed or permissions widened: stage it,
         * freeze the extension, wait for an explicit re-trust. */
        data class RetrustRequired(val reasons: List<String>) : UpdateDecision

        data class Refuse(val reason: String) : UpdateDecision
    }

    fun decideUpdate(
        installed: InstalledExtension,
        pkg: IndexPackage,
        newFingerprint: String,
        newDeclaredPermissions: ExtensionPermissions,
        autoApplySafeUpdates: Boolean
    ): UpdateDecision {
        val kind = pkg.kindEnum
            ?: return UpdateDecision.Refuse("Xreader doesn't know this kind of source.")
        if (pkg.versionCode <= installed.versionCode) return UpdateDecision.UpToDate
        if (pkg.apiLevel > ExtensionApi.API_LEVEL) {
            return UpdateDecision.Refuse(
                "The update needs a newer Xreader (extension API ${pkg.apiLevel})."
            )
        }
        val oldPermissions = ExtensionPermissions.parse(installed.permissionsJson)
        val reasons = mutableListOf<String>()
        if (!Hashes.hashesEqual(newFingerprint, installed.sha256)) {
            reasons += "its code/rules changed"
        }
        val newDomains = newDeclaredPermissions.domains
            .map { it.normaliseHost() }.toSet()
        val oldDomains = oldPermissions.domains.map { it.normaliseHost() }.toSet()
        val widened = newDomains - oldDomains
        if (widened.isNotEmpty()) {
            reasons += "it now wants to open ${widened.joinToString(", ")}"
        }
        if (newDeclaredPermissions.canNotify && !oldPermissions.canNotify) {
            reasons += "it now wants to show notifications"
        }
        if (reasons.isNotEmpty()) return UpdateDecision.RetrustRequired(reasons)
        // Unchanged fingerprint + permissions: dex still never auto-applies.
        return if (kind != ExtensionKind.DEX && autoApplySafeUpdates) {
            UpdateDecision.AutoApply
        } else {
            UpdateDecision.ApplyOnTap
        }
    }

    /** The on-disk check run before an artifact is ever loaded: the
     * bytes must still hash to the pinned fingerprint. */
    fun artifactIntact(onDiskSha256: String?, pinnedSha256: String): Boolean =
        onDiskSha256 != null && Hashes.hashesEqual(onDiskSha256, pinnedSha256)

    /** Whether an extension row may run at all right now. Dex also
     * needs the user's opt-in (§9.1); anything not TRUSTED is frozen. */
    fun mayRun(installed: InstalledExtension): Boolean {
        if (!installed.enabled) return false
        if (installed.trustState != TrustState.TRUSTED) return false
        if (installed.kind == DbExtensionKind.DEX) {
            if (!ExtensionPermissions.parse(installed.permissionsJson).dexOptIn) return false
        }
        return true
    }
}
