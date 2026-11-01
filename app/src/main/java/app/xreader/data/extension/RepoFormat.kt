package app.xreader.data.extension

import app.xreader.core.error.AppResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Repo-format recognition (v1.0.1 hotfix): when the user points Xreader
 * at a repository index that turns out to belong to ANOTHER reader
 * app, the app should say so in plain words — naming the app and the
 * sites it lists — instead of failing with a generic parse error.
 *
 * This file is the pure detector: raw fetched text in, a sealed
 * [RepoFormat] out. It never throws; malformed input is
 * [RepoFormat.Unknown] with a plain-words reason.
 *
 * Detection order matters, because the formats overlap:
 * 1. Xreader's own shape (see [RepoIndex]) — an object carrying a
 *    `packages` array the existing parser accepts, or a bare
 *    declarative-config document/array.
 * 2. Legado — entries carrying `bookSourceName` + `bookSourceUrl`.
 * 3. Mihon / Tachiyomi — array entries carrying `pkg`/`apk` +
 *    `sources` (each source has name/lang/id/baseUrl). Entries
 *    carrying `pkg`/`apk` but NO readable sources are a PLUGIN
 *    index (v1.0.8.3): the sites are code inside the one plugin
 *    file the index points at, so the repo is still named —
 *    with an empty site list and the plugin flag set.
 * 4. LNReader — entries carrying a site / plugin URL (`site`,
 *    `siteUrl`, `plugin.url`, `source.site`, …) plus a name or id.
 * 5. Generic — any entry exposing an http(s) URL in a field named
 *    like `baseUrl` / `site` / `url` / `homepage`:
 *    "another reader app".
 */

/** One site listed by a foreign repository index. */
@kotlinx.serialization.Serializable
data class ForeignSite(
    val name: String,
    val lang: String = "",
    val baseUrl: String,
    /** v1.0.9: for LNReader entries, the plugin file's URL and
     * the multisrc family its filename declares ([family] tag),
     * when it carries one — a templated family installs as a
     * ready-made source instead of going through the probe. */
    val pluginUrl: String? = null,
    val family: String? = null,
    /** v1.0.9: the plugin's id inside its repo index (LNReader),
     * the stable half of a family install's identity. */
    val pluginId: String? = null,
    /** Manga programme (M1): the site's content rating when
     * its index declares one — the tachiyomix index form
     * carries a per-extension `contentWarning`, mapped by
     * [KeiyoushiIndex.ratingForContentWarning]. SAFE when the
     * index says nothing (the general-catalogue default).
     * Defaulted so every sidecar written before it decodes
     * unchanged. */
    val contentRating: ContentRating = ContentRating.SAFE,
    /** Keiyoushi player (K3): the extension package that
     * carries this site in a tachiyomix index — the player's
     * install unit, and the join key for the row's player
     * state. Empty for ecosystems that don't package sites.
     * Defaulted so older sidecars decode unchanged. */
    val packageName: String = ""
)

sealed interface RepoFormat {
    /** The text is Xreader's own repository format. */
    object XreaderIndex : RepoFormat

    /** The text is another reader app's repository format. */
    @kotlinx.serialization.Serializable
    data class ForeignRepo(
        val ecosystemName: String,
        val sites: List<ForeignSite>,
        /**
         * v1.0.8.3: the index is a PLUGIN index — its entries
         * point at one plugin file (a .jar/.apk) and list no
         * sites at all, because the sites exist only as code
         * inside that file (Kototoro's repository is the
         * verified shape). [sites] is empty by construction;
         * the UI explains the plugin instead of inviting the
         * user to pick from a list that isn't there.
         */
        val pluginIndex: Boolean = false,
        /**
         * v1.0.9: the index is a STUB — every entry in it is an
         * upgrade nag ("Outdated App", "Update to …") pointing
         * at the ecosystem's own website, published so old
         * clients stop instead of showing an empty repo
         * (tachiyomix repos ship exactly this as their
         * index.min.json now that the real catalogue lives in
         * index.json / index.pb). The repo-add walk treats a
         * stub as "keep looking at the sibling files"; it is
         * only ever shown when nothing better was found.
         */
        val stubIndex: Boolean = false
    ) : RepoFormat

    /** The text matches nothing we recognise. */
    data class Unknown(val reason: String) : RepoFormat
}

/** Entry point: [RepoFormatDetector.detect]. */
fun detectRepoFormat(text: String): RepoFormat = RepoFormatDetector.detect(text)

object RepoFormatDetector {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun detect(text: String): RepoFormat {
        if (text.isBlank()) return RepoFormat.Unknown("the reply was empty")
        // v1.0.8.3: a binary index (Novel Sourcery's index.pb is
        // the verified shape) decodes to text full of U+FFFD
        // replacement marks. Name that honestly instead of the
        // generic not-JSON reason — the link IS a repository,
        // in a format Xreader can't read yet.
        if (text.count { it == '�' } > 8) {
            return RepoFormat.Unknown(
                "the list was a binary data file (a protobuf-style index) " +
                    "that couldn't be read"
            )
        }
        val root = runCatching { json.parseToJsonElement(text) }.getOrElse {
            return RepoFormat.Unknown("the reply wasn't JSON at all")
        }
        return try {
            when (root) {
                is JsonObject -> detectObject(root, text)
                is JsonArray -> detectArray(root)
                else -> RepoFormat.Unknown("the reply was JSON, but not a list or a catalogue")
            }
        } catch (e: Exception) {
            RepoFormat.Unknown("the list couldn't be read (${e.message?.take(80) ?: "unknown error"})")
        }
    }

    // ── top-level shapes ────────────────────────────────────────────

    private fun detectObject(root: JsonObject, rawText: String): RepoFormat {
        // Xreader's own index: an object whose `packages` is an array
        // the existing parser accepts. Checked first so a Xreader
        // index — whose sources carry baseUrl fields — is never
        // misread as a foreign repo.
        val packages = root["packages"]
        if (packages is JsonArray) {
            return if (parseRepoIndex(rawText) is AppResult.Ok) {
                RepoFormat.XreaderIndex
            } else {
                RepoFormat.Unknown(
                    "the list looked like an Xreader repository but couldn't be read"
                )
            }
        }
        // A single declarative config saved on its own.
        if (looksLikeDeclarativeConfig(root)) return RepoFormat.XreaderIndex

        // v1.0.9: the tachiyomix JSON index (Novel Sourcery's
        // index.json is the verified shape — the JSON twin of
        // index.pb): an object whose extensionList.extensions[]
        // carry sources with homeUrl fields.
        detectTachiyomix(root)?.let { return it }

        // Entries nested under a collection key, or a dictionary of
        // entry objects, or the object itself as a single entry.
        val nested = entriesFromObject(root)
        if (nested.isNotEmpty()) {
            detectForeign(nested)?.let { return it }
        }
        detectForeign(listOf(root))?.let { return it }
        return RepoFormat.Unknown(
            "the JSON object didn't match any known repository format"
        )
    }

    private fun detectArray(root: JsonArray): RepoFormat {
        val entries = root.mapNotNull { it as? JsonObject }
        if (entries.isEmpty()) {
            return RepoFormat.Unknown("the JSON list held no readable entries")
        }
        // Legado and Mihon have unmistakable markers; they win over
        // the Xreader-array fallback and the looser checks below.
        detectLegado(entries)?.let { return it }
        detectMihon(entries)?.let { return it }
        // A bare list of Xreader declarative configs (the shape
        // DeclarativeFingerprints.encode writes).
        if (entries.all { looksLikeDeclarativeConfig(it) }) {
            return RepoFormat.XreaderIndex
        }
        detectForeign(entries)?.let { return it }
        return RepoFormat.Unknown(
            "the JSON list didn't match any known repository format"
        )
    }

    /** Foreign checks that apply to an object root (Legado/Mihon are
     * array formats, but a single-object Legado file still counts). */
    private fun detectForeign(entries: List<JsonObject>): RepoFormat.ForeignRepo? =
        detectLegado(entries)
            ?: detectMihon(entries)
            ?: detectLnReader(entries)
            ?: detectGeneric(entries)

    // ── per-ecosystem detectors ─────────────────────────────────────

    /**
     * The tachiyomix JSON index (v1.0.9): the object form of the
     * protobuf index — `extensionList.extensions[]`, each
     * extension carrying `sources[]` with `name` / `language` /
     * `homeUrl` (verified verbatim against Novel Sourcery's
     * published index.json: 171 extensions, 180 sources). The
     * repository names itself in the top-level `name`.
     */
    private fun detectTachiyomix(root: JsonObject): RepoFormat.ForeignRepo? {
        val list = root["extensionList"] as? JsonObject ?: return null
        val extensions = list["extensions"] as? JsonArray ?: return null
        val sites = mutableListOf<ForeignSite>()
        for (element in extensions) {
            val extension = element as? JsonObject ?: continue
            val sources = extension["sources"] as? JsonArray ?: continue
            // Manga programme (M1): the extension's declared
            // content warning rides onto each of its sites, so
            // a rated catalogue (Keiyoushi's) keeps its ratings
            // through the foreign-repo sidecar.
            val rating = KeiyoushiIndex.ratingForContentWarning(
                stringValue(extension, "contentWarning")
            )
            // K3: the carrying package rides onto each site —
            // the Keiyoushi player's install unit and the row
            // state join key.
            val packageName = stringValue(extension, "packageName") ?: ""
            for (sourceElement in sources) {
                val source = sourceElement as? JsonObject ?: continue
                val homeUrl = stringValue(source, "homeUrl") ?: continue
                if (!isHttpUrl(homeUrl)) continue
                sites += ForeignSite(
                    name = stringValue(source, "name") ?: "",
                    lang = stringValue(source, "language") ?: "",
                    baseUrl = homeUrl,
                    contentRating = rating,
                    packageName = packageName
                )
            }
        }
        if (sites.isEmpty()) return null
        val repoName = stringValue(root, "name") ?: "Novel Sourcery"
        return RepoFormat.ForeignRepo(repoName, dedupe(sites))
    }

    /**
     * The tachiyomix BINARY index (v1.0.9): raw `index.pb` bytes
     * in, the same [RepoFormat.ForeignRepo] out — the site list
     * is all Xreader takes from any of these repos. Null when
     * the bytes aren't a readable index or hold no sites.
     */
    fun detectPb(bytes: ByteArray): RepoFormat.ForeignRepo? {
        val index = PbIndexReader.read(bytes) ?: return null
        val sites = index.extensions.flatMap { extension ->
            extension.sources.mapNotNull { source ->
                if (!isHttpUrl(source.homeUrl)) {
                    null
                } else {
                    ForeignSite(
                        name = source.name,
                        lang = source.language,
                        baseUrl = source.homeUrl
                    )
                }
            }
        }
        if (sites.isEmpty()) return null
        val repoName = index.name.ifBlank { "Novel Sourcery" }
        return RepoFormat.ForeignRepo(repoName, dedupe(sites))
    }

    private fun detectLegado(entries: List<JsonObject>): RepoFormat.ForeignRepo? {
        val sites = entries.mapNotNull { entry ->
            val name = stringValue(entry, "bookSourceName") ?: return@mapNotNull null
            val url = stringValue(entry, "bookSourceUrl") ?: return@mapNotNull null
            ForeignSite(name = name, lang = stringValue(entry, "lang") ?: "", baseUrl = url)
        }
        if (sites.isEmpty()) return null
        return RepoFormat.ForeignRepo("Legado", dedupe(sites))
    }

    private fun detectMihon(entries: List<JsonObject>): RepoFormat.ForeignRepo? {
        var isMihon = false
        // v1.0.8.3: package markers count even on entries with no
        // sources array — that combination IS the plugin index.
        var packageMarked = false
        val sites = mutableListOf<ForeignSite>()
        for (entry in entries) {
            val hasPackageMarker = entry.containsKey("pkg") || entry.containsKey("apk")
            if (hasPackageMarker) {
                isMihon = true
                packageMarked = true
            }
            val sources = entry["sources"] as? JsonArray ?: continue
            for (element in sources) {
                val source = element as? JsonObject ?: continue
                // A sources list shaped like Mihon's (name/lang/id/
                // baseUrl) is itself a marker, even without pkg/apk.
                if (!hasPackageMarker &&
                    source.containsKey("baseUrl") &&
                    (source.containsKey("lang") || source.containsKey("id"))
                ) {
                    isMihon = true
                }
                val baseUrl = stringValue(source, "baseUrl") ?: continue
                if (!isHttpUrl(baseUrl)) continue
                sites += ForeignSite(
                    name = stringValue(source, "name") ?: "",
                    lang = stringValue(source, "lang") ?: "",
                    baseUrl = baseUrl
                )
            }
        }
        if (sites.isNotEmpty()) {
            return if (isMihon) {
                RepoFormat.ForeignRepo(
                    "Mihon", dedupe(sites), stubIndex = isStubIndex(entries)
                )
            } else {
                null
            }
        }
        // v1.0.8.3: a PLUGIN index — package entries pointing at
        // one plugin file and listing no sources (verified live:
        // Kototoro's index.min.json is a single pkg/apk entry,
        // "plugin.jar", no sources at all). The repository is
        // real and gets named; there is simply no site list in
        // it to offer, because the sites are code in the file.
        if (packageMarked) {
            return RepoFormat.ForeignRepo(
                ecosystemName = pluginEcosystem(entries),
                sites = emptyList(),
                pluginIndex = true
            )
        }
        if (!isMihon) return null
        return RepoFormat.ForeignRepo("Mihon", dedupe(sites))
    }

    /**
     * A stub index (v1.0.9): EVERY entry is an upgrade nag —
     * "Outdated App", "Update to <app> …" — the shape
     * tachiyomix repos publish as index.min.json now that the
     * real catalogue moved to index.json / index.pb (verified
     * verbatim: Novel Sourcery's two entries). A real extension
     * entry never carries either name.
     */
    private fun isStubIndex(entries: List<JsonObject>): Boolean {
        if (entries.isEmpty()) return false
        return entries.all { entry ->
            val name = stringValue(entry, "name")?.lowercase() ?: return@all false
            name.contains("outdated") || name.startsWith("update to ")
        }
    }

    /** Which app a plugin index belongs to, read from its
     * entries' package / display names: Kototoro and
     * Tachiyomi/Mihon publish this shape; anything else stays
     * generic. */
    private fun pluginEcosystem(entries: List<JsonObject>): String {
        val haystack = entries.joinToString(" ") { entry ->
            listOfNotNull(stringValue(entry, "pkg"), stringValue(entry, "name"))
                .joinToString(" ")
        }.lowercase()
        return when {
            "kototoro" in haystack -> "Kototoro"
            "tachiyomi" in haystack || "mihon" in haystack -> "Mihon"
            else -> "another reader app"
        }
    }

    private fun detectLnReader(entries: List<JsonObject>): RepoFormat.ForeignRepo? {
        val sites = entries.mapNotNull { entry ->
            lnReaderSite(entry)
        }
        if (sites.isEmpty()) return null
        return RepoFormat.ForeignRepo("LNReader", dedupe(sites))
    }

    /**
     * One LNReader entry, or null when the entry doesn't carry the
     * LNReader shape: a site/plugin URL field plus a name or id.
     * Strong signals only — a bare `url` on its own is the generic
     * case below, not LNReader.
     */
    private fun lnReaderSite(entry: JsonObject): ForeignSite? {
        val plugin = entry["plugin"] as? JsonObject
        val source = entry["source"] as? JsonObject
        val name = stringValue(entry, "name", "title")
            ?: plugin?.let { stringValue(it, "name", "title") }
            ?: source?.let { stringValue(it, "name", "title") }
            ?: stringValue(entry, "id")
            ?: plugin?.let { stringValue(it, "id") }
            ?: source?.let { stringValue(it, "id") }
            ?: return null
        // The site's own address is the base URL when present; the
        // plugin file URL is the fallback (it still identifies it).
        val siteUrl = stringValue(entry, "site", "siteUrl")
            ?: plugin?.let { stringValue(it, "site", "siteUrl", "url", "baseUrl") }
            ?: source?.let { stringValue(it, "site", "siteUrl", "url", "baseUrl") }
        val url = when {
            siteUrl != null && isHttpUrl(siteUrl) -> siteUrl
            // `url` counts for LNReader only alongside an id and a
            // plugin-ish companion field — that's the plugins.json
            // shape (id + site + url + lang/version/icon).
            entry.containsKey("id") && entry.containsKey("url") &&
                (entry.containsKey("lang") || entry.containsKey("version") ||
                    entry.containsKey("iconUrl") || entry.containsKey("site")) -> {
                val direct = stringValue(entry, "url")
                if (direct != null && isHttpUrl(direct)) direct else null
            }
            else -> null
        } ?: return null
        // v1.0.9: the plugin file itself, when the entry names
        // one — its [family] filename tag decides whether this
        // site installs ready-made (see FamilyTemplates). Only
        // when a real site address exists separately: an entry
        // whose only URL is the plugin file has no site to
        // install a source for.
        val pluginUrl = if (siteUrl != null) {
            stringValue(entry, "url")
                ?.takeIf { isHttpUrl(it) && it.endsWith(".js") }
        } else {
            null
        }
        return ForeignSite(
            name = name,
            lang = stringValue(entry, "lang") ?: "",
            baseUrl = url,
            pluginUrl = pluginUrl,
            family = pluginUrl?.let { FamilyTemplates.familyOf(it) },
            pluginId = stringValue(entry, "id")
        )
    }

    private fun detectGeneric(entries: List<JsonObject>): RepoFormat.ForeignRepo? {
        val sites = entries.mapNotNull { entry ->
            val url = listOf("baseUrl", "site", "url", "homepage", "homePage", "siteUrl")
                .firstNotNullOfOrNull { key ->
                    stringValue(entry, key)?.takeIf { isHttpUrl(it) }
                }
                ?: return@mapNotNull null
            ForeignSite(
                name = stringValue(entry, "name", "title", "id") ?: "",
                lang = stringValue(entry, "lang") ?: "",
                baseUrl = url
            )
        }
        if (sites.isEmpty()) return null
        return RepoFormat.ForeignRepo("another reader app", dedupe(sites))
    }

    // ── helpers ─────────────────────────────────────────────────────

    private fun looksLikeDeclarativeConfig(obj: JsonObject): Boolean =
        obj.containsKey("details") && obj.containsKey("chapter") &&
            (obj.containsKey("baseUrl") || obj.containsKey("id"))

    /** Entry objects inside an object root: a collection array under
     * a known key, any array-of-objects value, or the object's own
     * object values (dictionary-style indexes). */
    private fun entriesFromObject(root: JsonObject): List<JsonObject> {
        val collectionKeys = listOf(
            "plugins", "sources", "extensions", "items", "list",
            "bookSources", "sites", "repos", "catalogue", "catalog"
        )
        for (key in collectionKeys) {
            val array = root[key] as? JsonArray ?: continue
            val objects = array.mapNotNull { it as? JsonObject }
            if (objects.isNotEmpty()) return objects
        }
        for ((_, value) in root) {
            val array = value as? JsonArray ?: continue
            val objects = array.mapNotNull { it as? JsonObject }
            if (objects.isNotEmpty()) return objects
        }
        return root.values.mapNotNull { it as? JsonObject }
    }

    private fun dedupe(sites: List<ForeignSite>): List<ForeignSite> =
        sites.distinctBy { it.baseUrl.trim().trimEnd('/').lowercase() }

    private fun isHttpUrl(value: String): Boolean =
        value.startsWith("http://") || value.startsWith("https://")

    private fun stringValue(obj: JsonObject, vararg keys: String): String? {
        for (key in keys) {
            val primitive = obj[key] as? JsonPrimitive ?: continue
            val value = primitive.contentOrNull?.trim()?.ifBlank { null } ?: continue
            return value
        }
        return null
    }
}
