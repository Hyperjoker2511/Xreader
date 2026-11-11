package app.xreader.data.extension

import app.xreader.data.db.ExtensionKind

/**
 * v1.5.2 (§8.6): provenance is DERIVED AT READ TIME from the row's
 * repo name/membership, packageId prefix and kind — never stored —
 * so the §8 commit stage cannot strip it.
 */
object SourceProvenance {
    const val SHIPPED = "Shipped with Xreader"
    const val PACK = "From a provider pack"
    const val LINK = "Added by you via link"
    const val KEIYOUSHI = "Keiyoushi extension"

    fun label(
        repoName: String?,
        inLinkRepo: Boolean,
        inBuiltInRepo: Boolean,
        packageId: String,
        kind: ExtensionKind
    ): String = when {
        kind == ExtensionKind.KEIYOUSHI -> KEIYOUSHI
        inLinkRepo -> LINK
        packageId.startsWith("packs.") -> PACK
        inBuiltInRepo -> SHIPPED
        !repoName.isNullOrBlank() -> "From repo: $repoName"
        else -> "From repo: unknown"
    }

    /** Row-level derivation for surfaces that hold only the
     * installed-extension row (no repo lookup at render time):
     * the packageId prefixes carry the same facts. */
    fun label(ext: app.xreader.data.db.InstalledExtension): String =
        when {
            ext.kind == ExtensionKind.KEIYOUSHI -> KEIYOUSHI
            ext.packageId.startsWith("link.") -> LINK
            // v1.5.2 fix (FIX-12): Live Source rows — the shape a
            // pasted-link source (BOTI) actually takes on device —
            // are link-added too and must read as such.
            ext.packageId.startsWith("live.") -> LINK
            ext.packageId.startsWith("packs.") -> PACK
            ext.packageId.startsWith("bundled.") -> SHIPPED
            else -> "From a repo"
        }
}
