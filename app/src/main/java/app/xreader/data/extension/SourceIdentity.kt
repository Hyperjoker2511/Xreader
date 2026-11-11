package app.xreader.data.extension

/**
 * v1.5.2 (§3): ONE identity function for every derivation site
 * (install, Live Source install, re-derive, migration refresh).
 * Precedence is FIXED: SeedCatalog name → host-derived name →
 * og:site_name → page title. A generic title can never become a
 * display name (Failure B: BOTI installed as "Home").
 */
object SourceIdentity {

    private val GENERIC_TITLES = setOf(
        "home", "index", "welcome", "homepage", "home page",
        "main page", "top", "top page"
    )

    fun normaliseTitle(raw: String?): String =
        (raw ?: "").lowercase().trim()
            .replace(Regex("[\\s\\p{Punct}]+"), " ").trim()

    fun isGenericTitle(raw: String?, host: String?, hostDerived: String?): Boolean {
        val n = normaliseTitle(raw)
        if (n.isEmpty()) return true
        if (n in GENERIC_TITLES) return true
        if (n == normaliseTitle(host)) return true
        if (n == normaliseTitle(hostDerived)) return true
        if (n.startsWith("welcome to ")) return true
        if (n.endsWith(" home")) return true
        return false
    }

    /** Host-derived name (§3 algorithm): registrable domain's main
     * label, split on - _ ., each token capitalised, joined bare. */
    fun hostDerivedName(hostOrUrl: String?): String? {
        var host = (hostOrUrl ?: "").trim().lowercase()
        if (host.isEmpty()) return null
        host = host.substringAfter("://").substringBefore("/").substringBefore(":")
        if (host.isEmpty()) return null
        val labels = host.split(".").filter { it.isNotEmpty() }
        if (labels.isEmpty()) return null
        val main = when {
            labels.size >= 3 && labels[labels.size - 2].length <= 3 &&
                labels.last().length <= 3 -> labels[labels.size - 3]
            labels.size >= 2 -> labels[labels.size - 2]
            else -> labels[0]
        }
        val tokens = main.split('-', '_', '.').filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        return tokens.joinToString("") { tok ->
            tok.replaceFirstChar { c -> c.uppercaseChar() }
        }
    }

    /** The precedence itself. [manifestName] rides the og rung. */
    fun resolve(
        seedName: String?,
        host: String?,
        ogSiteName: String? = null,
        pageTitle: String? = null,
        manifestName: String? = null
    ): String {
        seedName?.takeIf { it.isNotBlank() && !isGenericTitle(it, host, null) }
            ?.let { return it.trim() }
        val derived = hostDerivedName(host)
        if (derived != null) return derived
        val og = ogSiteName ?: manifestName
        og?.takeIf { it.isNotBlank() && !isGenericTitle(it, host, derived) }
            ?.let { return it.trim() }
        pageTitle?.takeIf { it.isNotBlank() && !isGenericTitle(it, host, derived) }
            ?.let { return RepoManager.cleanSiteTitle(it) ?: it.trim() }
        return derived ?: (host?.removePrefix("www.")?.ifBlank { null } ?: "Source")
    }
}
