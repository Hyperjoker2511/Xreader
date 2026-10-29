package app.xreader.data.extension

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What an extension declares it may do, plus the user's overrides —
 * this JSON is the `permissionsJson` column of InstalledExtension
 * (PLAN §5/§9.1). Parsed defensively at the boundary: a corrupt blob
 * degrades to "no domains, no notifications" (fail closed), never a
 * crash and never a widening.
 */
@Serializable
data class ExtensionPermissions(
    /** Hosts the extension declared in its repo index. The host HTTP
     * client allows exactly these (plus their subdomains), minus
     * [blockedDomains]. */
    val domains: List<String> = emptyList(),
    /** Master network switch (declared; the user can also cut it). */
    val canUseNetwork: Boolean = true,
    /** Declared by the index; defaults OFF (§9.1). */
    val canNotify: Boolean = false,
    /** Domains the USER has switched off after install. */
    val blockedDomains: List<String> = emptyList(),
    /** Dex only: the user's explicit opt-in after the strong-wording
     * consent sheet. Without this, dex code is never loaded. */
    val dexOptIn: Boolean = false
) {
    /** The effective whitelist: declared minus user-blocked, normalised. */
    fun allowedDomains(): Set<String> =
        domains.map { it.normaliseHost() }.filter { it.isNotEmpty() }.toSet() -
            blockedDomains.map { it.normaliseHost() }.toSet()

    /** True when [host] is a declared domain or one of its subdomains
     * and the user hasn't blocked it (blocking a domain blocks its
     * subdomains too). This is THE whitelist rule — pure, and pinned
     * by tests. */
    fun allows(host: String): Boolean {
        if (!canUseNetwork) return false
        val h = host.normaliseHost()
        if (h.isEmpty()) return false
        val blocked = blockedDomains.map { it.normaliseHost() }.filter { it.isNotEmpty() }
        if (blocked.any { d -> h == d || h.endsWith(".$d") }) return false
        return allowedDomains().any { d -> h == d || h.endsWith(".$d") }
    }

    /** Plain-words consent summary for the install sheet (§9.1). */
    fun consentSummary(kindLabel: String): String {
        val sites = if (domains.isEmpty()) {
            "no websites"
        } else {
            domains.joinToString(", ")
        }
        val notify = if (canNotify) " It may show notifications." else ""
        return "This $kindLabel can open: $sites.$notify " +
            "It cannot see your books, your other sources, or your saved passwords."
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(raw: String?): ExtensionPermissions {
            if (raw.isNullOrBlank()) return ExtensionPermissions()
            return runCatching {
                json.decodeFromString(serializer(), raw)
            }.getOrDefault(ExtensionPermissions())
        }

        fun ExtensionPermissions.encode(): String =
            json.encodeToString(serializer(), this)
    }
}

/** Hosts compare lowercase, without scheme, port, or trailing dot. */
fun String.normaliseHost(): String {
    var h = trim().lowercase()
    h = h.removePrefix("https://").removePrefix("http://")
    h = h.substringBefore('/').substringBefore('?')
    h = h.substringBefore(':') // port
    return h.trimEnd('.')
}
