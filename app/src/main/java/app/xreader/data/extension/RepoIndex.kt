package app.xreader.data.extension

import app.xreader.core.error.AppError
import app.xreader.core.error.AppResult
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The repository index format (PLAN §5): one JSON document per repo,
 * listing installable packages. Declarative packages carry their full
 * selector rules inline (there is no artifact to download); JS/Dex
 * packages point at an artifact whose SHA-256 MUST be declared here —
 * the index is the supply chain, and an undeclared hash is a refusal.
 *
 * Parsed defensively: unknown fields ignored, unknown package kinds
 * skipped (never crash on a newer repo), bad entries dropped with the
 * rest of the catalogue intact.
 */
@Serializable
data class RepoIndex(
    val name: String = "",
    val packages: List<IndexPackage> = emptyList()
)

@Serializable
data class IndexPackage(
    val name: String,
    val pkg: String,
    val versionName: String = "1.0",
    val versionCode: Long = 1,
    /** "declarative" | "js" | "dex" — anything else is skipped. */
    val kind: String = "declarative",
    val artifactUrl: String? = null,
    /** Required for js/dex; declarative fingerprints are computed from
     * the rules themselves. */
    val sha256: String = "",
    val apiLevel: Int = 1,
    val languages: List<String> = emptyList(),
    /** Dex only: the ExtensionFactory implementation class name. */
    val entryClass: String? = null,
    val permissions: IndexPermissions = IndexPermissions(),
    /** Declarative only: the full selector rules, one per source. */
    val sources: List<DeclarativeConfig> = emptyList(),
    /** JS/Dex only: catalogue metadata (the code provides the sources
     * at load time; for JS the bundle is a single source = first entry). */
    val sourcesMeta: List<IndexSourceMeta> = emptyList()
) {
    val kindEnum: ExtensionKind? get() = ExtensionKind.fromWire(kind)

    /** Source ids this package provides, from whichever shape it has. */
    val sourceIds: List<String>
        get() = if (kindEnum == ExtensionKind.DECLARATIVE) {
            sources.map { it.id }
        } else {
            sourcesMeta.map { it.id }
        }

    /** All hosts the package's sources live on — folded into the
     * declared domains so the whitelist always covers the source's own
     * site even when an index forgets to list it. */
    val sourceHosts: List<String>
        get() = (sources.map { it.baseUrl } + sourcesMeta.map { it.baseUrl })
            .map { it.normaliseHost() }.filter { it.isNotEmpty() }.distinct()

    /** The permission set to pin at install: index-declared domains +
     * the sources' own hosts. */
    fun installPermissions(): ExtensionPermissions = ExtensionPermissions(
        domains = (permissions.domains.map { it.normaliseHost() } + sourceHosts)
            .filter { it.isNotEmpty() }.distinct(),
        canUseNetwork = true,
        canNotify = permissions.canNotify
    )
}

@Serializable
data class IndexPermissions(
    val domains: List<String> = emptyList(),
    val canNotify: Boolean = false
)

@Serializable
data class IndexSourceMeta(
    val id: String,
    val name: String,
    val lang: String = "en",
    val baseUrl: String
)

enum class ExtensionKind {
    DECLARATIVE, JS, DEX;

    companion object {
        fun fromWire(raw: String): ExtensionKind? = when (raw.trim().lowercase()) {
            "declarative", "rules" -> DECLARATIVE
            "js", "javascript", "quickjs" -> JS
            "dex", "apk", "jar" -> DEX
            else -> null
        }
    }

    /** Plain-words kind label for consent sheets and detail screens. */
    val label: String
        get() = when (this) {
            DECLARATIVE -> "rule-based source (no code runs)"
            JS -> "JavaScript source"
            DEX -> "app-code source"
        }
}

/** Parse + validate an index body. Returns the index, or a plain
 * [AppError.ParseFailed] when the document is unusable. */
fun parseRepoIndex(body: String): AppResult<RepoIndex> {
    val json = Json { ignoreUnknownKeys = true; isLenient = true }
    val raw = runCatching { json.decodeFromString(RepoIndex.serializer(), body) }
        .getOrElse {
            return AppResult.Err(
                AppError.ParseFailed("repository list", "the list wasn't in the expected format")
            )
        }
    // Drop entries we cannot ever install safely; keep the rest.
    val usable = raw.packages.filter { pkg ->
        val kind = pkg.kindEnum ?: return@filter false
        pkg.name.isNotBlank() && pkg.pkg.isNotBlank() && when (kind) {
            ExtensionKind.DECLARATIVE -> pkg.sources.isNotEmpty()
            ExtensionKind.JS, ExtensionKind.DEX ->
                !pkg.artifactUrl.isNullOrBlank() && pkg.sha256.isNotBlank() &&
                    pkg.sourcesMeta.isNotEmpty() &&
                    (kind == ExtensionKind.JS || !pkg.entryClass.isNullOrBlank())
        }
    }
    return AppResult.Ok(RepoIndex(name = raw.name, packages = usable))
}
