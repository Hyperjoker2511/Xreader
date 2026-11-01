package app.xreader.data.extension

import app.xreader.core.error.AppError
import app.xreader.core.error.AppResult
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The Keiyoushi manga repository index (manga programme,
 * slice M1; player states added in K3) — the reader for
 * Keiyoushi's published index: names, languages, site
 * addresses, content ratings, and (for the player) the
 * installable package catalogue in. M1 shipped it read-only
 * with native-conversion states; K3's Keiyoushi player
 * superseded the conversion model per the user's decision —
 * a site becomes readable when its Keiyoushi extension is
 * downloaded, verified (signer == the index-published key),
 * consented to and loaded by the player
 * (data/extension/player), and [rows] states that truth:
 * READY means the extension is installed and loaded (or a
 * native conversion exists), never anything in between.
 *
 * Two published shapes are understood, both verified against
 * the live repo (2026-11-01):
 *
 * 1. The full index (index.json, the canonical document at
 *    [INDEX_URL]): the tachiyomix object form —
 *    `{extensionList: {extensions: [...]}}`, each extension
 *    carrying `packageName`, a `contentWarning`
 *    (CONTENT_WARNING_SAFE / _MIXED / _NSFW — the index's own
 *    three ratings, mapping 1:1 onto [ContentRating]) and
 *    `sources[]` with `name` / `language` / `homeUrl`
 *    (+ optional `mirrorUrls`). 1,446 extensions / 2,359
 *    sources at verification time.
 * 2. The classic Mihon array form (older mirrors and
 *    Tachiyomi-era copies): entries with `pkg` / `apk` and a
 *    package-level `nsfw` 0/1, whose `sources[]` carry
 *    `name` / `lang` / `baseUrl` (a source may carry its own
 *    `nsfw`). Mapping: a flagged source is 18+; an unflagged
 *    source inside a flagged package is MIXED (the package
 *    mixes flagged and unflagged material); no flags = SAFE.
 *
 * The repo's index.min.json is NOT used: it is the published
 * STUB (two "Outdated App" nag entries pointing at the
 * project's own site, the shape [RepoFormat] already names).
 *
 * Parsed defensively, like [parseRepoIndex]: unknown fields
 * ignored, bad entries dropped with the rest of the catalogue
 * intact, an unusable document is a plain [AppError].
 */
object KeiyoushiIndex {

    /** The canonical index document — verified live from this
     * machine on 2026-11-01 (HTTP 200; the document names
     * itself "Keiyoushi"). */
    const val INDEX_URL =
        "https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.json"

    /** The display name the index gives itself, and the name
     * the registered repository row carries. */
    const val REPO_NAME = "Keiyoushi"

    /** Plain words for the not-yet-converted state — an entry
     * is never shown as working before its conversion exists. */
    const val NEEDS_CONVERSION_WORDS = "Needs the manga conversion"

    // ── the unified entry ───────────────────────────────────

    /** One manga site in the Keiyoushi catalogue, whatever
     * index shape it came from. */
    data class Entry(
        val name: String,
        val lang: String,
        val baseUrl: String,
        /** Mirror addresses the index lists for the site. */
        val mirrorUrls: List<String> = emptyList(),
        val rating: ContentRating = ContentRating.SAFE,
        /** The Keiyoushi package that carries this source. */
        val packageName: String = ""
    ) {
        /** The site's normalised host — the conversion join
         * key (a native conversion declares the same host). */
        val host: String get() = baseUrl.normaliseHost()
    }

    /** An entry's TRUE state: READY only when a native manga
     * conversion for its site is installed in the app — or,
     * since K3, when its Keiyoushi extension is installed and
     * loaded in the player. The player states below are the
     * row's honest install progress, never a claim of working
     * before the extension actually loads. */
    enum class State(val label: String) {
        READY("Ready"),
        NEEDS_CONVERSION(NEEDS_CONVERSION_WORDS),
        /** K3: the site's Keiyoushi package can be installed. */
        NOT_INSTALLED("Install"),
        DOWNLOADING("Downloading…"),
        VERIFYING("Checking the file…"),
        AWAITING_CONSENT("Needs your OK"),
        LOADING("Starting…"),
        UPDATE_AVAILABLE("Update available"),
        FAILED("Couldn't be installed"),
        INCOMPATIBLE("Can't run in this version")
    }

    /** K3: the action a row offers, if any. */
    enum class RowAction { NONE, INSTALL, REVIEW, UPDATE, REMOVE }

    /**
     * K3: what the Keiyoushi player says about one PACKAGE
     * (rows are per source; a package's state stamps every
     * source it carries). [reason] is the plain-words cause
     * for FAILED / INCOMPATIBLE.
     */
    data class PlayerPackageState(
        val kind: Kind,
        val reason: String? = null
    ) {
        enum class Kind {
            NOT_INSTALLED, DOWNLOADING, VERIFYING, AWAITING_CONSENT,
            LOADING, READY, UPDATE_AVAILABLE, FAILED, INCOMPATIBLE
        }
    }

    /** One display row of the Keiyoushi catalogue: the entry,
     * its conversion state and its rating badge (the
     * repository screen's contract — slice M3 renders these). */
    data class Row(
        val entry: Entry,
        val state: State,
        val badge: String?,
        /** K3: the failure/incompatibility reason in plain
         * words, when the state carries one. */
        val detail: String? = null,
        /** K3: the action this row offers (Install / Review /
         * Update / Remove), if any. */
        val action: RowAction = RowAction.NONE
    )

    // ── rating mapping ──────────────────────────────────────

    /** The tachiyomix `contentWarning` → our rating. Unknown
     * or missing values are SAFE (the index's own default for
     * unmarked extensions is a general catalogue). */
    fun ratingForContentWarning(raw: String?): ContentRating =
        when (raw?.trim()?.uppercase()) {
            "CONTENT_WARNING_NSFW", "NSFW" -> ContentRating.ADULT
            "CONTENT_WARNING_MIXED", "MIXED" -> ContentRating.MIXED
            else -> ContentRating.SAFE
        }

    /**
     * The classic Mihon `nsfw` flags → our rating. A source
     * flagged itself is 18+; a source NOT flagged inside a
     * FLAGGED package has mixed signals (the package is marked
     * adult while this source is not) and maps to MIXED; no
     * flags anywhere = SAFE.
     */
    fun ratingForNsfw(
        packageNsfw: Boolean,
        sourceNsfw: Boolean?
    ): ContentRating = when {
        sourceNsfw == true -> ContentRating.ADULT
        packageNsfw && sourceNsfw == false -> ContentRating.MIXED
        packageNsfw && sourceNsfw == null -> ContentRating.ADULT
        else -> ContentRating.SAFE
    }

    // ── conversion state ────────────────────────────────────

    /**
     * The normalised hosts that HAVE a native conversion:
     * every installed MANGA declarative config's own host plus
     * its declared extra domains (an API host a conversion
     * reads through). Text configs never convert a manga
     * entry — a novel source for the same site is not a manga
     * conversion.
     */
    fun convertedHosts(configs: List<DeclarativeConfig>): Set<String> {
        val hosts = mutableSetOf<String>()
        for (config in configs) {
            if (!config.mediaKind.isManga) continue
            hosts += config.baseUrl.normaliseHost()
            hosts += config.extraDomains.map { it.normaliseHost() }
        }
        return hosts.filter { it.isNotEmpty() }.toSet()
    }

    fun stateOf(entry: Entry, convertedHosts: Set<String>): State =
        if (entry.host.isNotEmpty() && entry.host in convertedHosts) {
            State.READY
        } else {
            State.NEEDS_CONVERSION
        }

    /**
     * The catalogue rows under the reader's content
     * preference: entries the preference hides are ABSENT
     * (never listed as disabled or greyed), each row carries
     * its conversion state and its rating badge, and the
     * order is alphabetical by name (language as tiebreak) —
     * the deterministic order the repository screen renders.
     */
    fun rows(
        entries: List<Entry>,
        convertedHosts: Set<String>,
        visibility: ContentVisibility
    ): List<Row> = entries
        .filter { ContentAccess.allows(it.rating, visibility) }
        .map { entry ->
            Row(
                entry = entry,
                state = stateOf(entry, convertedHosts),
                badge = entry.rating.badge
            )
        }
        .sortedWith(
            compareBy<Row>({ it.entry.name.lowercase() }, { it.entry.lang })
        )

    /**
     * K3: the player-aware rows. A package the player has
     * installed and LOADED makes its sources READY (the
     * conversion question no longer applies); a package in
     * any other player state shows that state honestly with
     * its action; a package the player hasn't touched falls
     * back to the conversion rule, except that a source
     * whose package the catalogue names offers Install
     * instead of the conversion dead-end. Same preference
     * filtering and ordering as [rows].
     */
    fun rows(
        entries: List<Entry>,
        convertedHosts: Set<String>,
        visibility: ContentVisibility,
        playerStates: Map<String, PlayerPackageState>
    ): List<Row> = entries
        .filter { ContentAccess.allows(it.rating, visibility) }
        .map { entry ->
            val player = entry.packageName
                .takeIf { it.isNotBlank() }
                ?.let { playerStates[it] }
            val converted = stateOf(entry, convertedHosts) == State.READY
            when (player?.kind) {
                PlayerPackageState.Kind.READY -> Row(
                    entry, State.READY, entry.rating.badge,
                    action = RowAction.REMOVE
                )
                PlayerPackageState.Kind.UPDATE_AVAILABLE -> Row(
                    entry, State.UPDATE_AVAILABLE, entry.rating.badge,
                    action = RowAction.UPDATE
                )
                PlayerPackageState.Kind.DOWNLOADING -> Row(
                    entry, State.DOWNLOADING, entry.rating.badge
                )
                PlayerPackageState.Kind.VERIFYING -> Row(
                    entry, State.VERIFYING, entry.rating.badge
                )
                PlayerPackageState.Kind.AWAITING_CONSENT -> Row(
                    entry, State.AWAITING_CONSENT, entry.rating.badge,
                    action = RowAction.REVIEW
                )
                PlayerPackageState.Kind.LOADING -> Row(
                    entry, State.LOADING, entry.rating.badge
                )
                PlayerPackageState.Kind.FAILED -> Row(
                    entry, State.FAILED, entry.rating.badge,
                    detail = player.reason,
                    action = RowAction.INSTALL
                )
                PlayerPackageState.Kind.INCOMPATIBLE -> Row(
                    entry, State.INCOMPATIBLE, entry.rating.badge,
                    detail = player.reason
                )
                PlayerPackageState.Kind.NOT_INSTALLED, null ->
                    if (converted) {
                        Row(entry, State.READY, entry.rating.badge)
                    } else if (entry.packageName.isNotBlank()) {
                        Row(
                            entry, State.NOT_INSTALLED,
                            entry.rating.badge,
                            action = RowAction.INSTALL
                        )
                    } else {
                        Row(
                            entry, State.NEEDS_CONVERSION,
                            entry.rating.badge
                        )
                    }
            }
        }
        .sortedWith(
            compareBy<Row>({ it.entry.name.lowercase() }, { it.entry.lang })
        )

    // ── package catalogue (K3: installable form) ────────────

    /** One source inside a [Package], as the index lists it. */
    data class SourceStub(
        val id: String,
        val name: String,
        val lang: String,
        val homeUrl: String
    )

    /**
     * One installable Keiyoushi extension package — the
     * player's unit of download/verify/load (K3). The index
     * lists sources per package; the APK is per package.
     */
    data class Package(
        val name: String,
        val packageName: String,
        val versionName: String,
        val versionCode: Long,
        /** The extension-library version string ("1.6"). */
        val extensionLib: String,
        /** Absolute APK download URL. */
        val apkUrl: String,
        val rating: ContentRating = ContentRating.SAFE,
        val sources: List<SourceStub> = emptyList()
    )

    /** The whole catalogue in installable form, with the
     * signing key the index publishes for its APKs. */
    data class Catalog(
        val name: String,
        val signingKey: String?,
        val packages: List<Package>
    )

    /**
     * The tachiyomi version-code convention, verified against
     * the live index (1.6.71 → 106071, 1.6.5 → 106005):
     * major × 100000 + minor × 1000 + patch. Non-numeric
     * parts count as 0.
     */
    fun versionCodeOf(versionName: String): Long {
        val parts = versionName.trim().split('.')
            .map { it.takeWhile { c -> c.isDigit() }.toLongOrNull() ?: 0L }
        val major = parts.getOrElse(0) { 0L }
        val minor = parts.getOrElse(1) { 0L }
        val patch = parts.getOrElse(2) { 0L }
        return major * 100_000 + minor * 1_000 + patch
    }

    /**
     * Parse an index body into the installable [Catalog]
     * (either published shape). A document with no usable
     * packages is a plain ParseFailed, exactly like [parse].
     */
    fun parseCatalog(body: String): AppResult<Catalog> {
        val catalog = parseCatalogTachiyomix(body)
            ?: parseCatalogClassic(body)
        if (catalog == null || catalog.packages.isEmpty()) {
            return AppResult.Err(
                AppError.ParseFailed(
                    "Keiyoushi list",
                    "the list wasn't in the expected format"
                )
            )
        }
        return AppResult.Ok(catalog)
    }

    private fun parseCatalogTachiyomix(body: String): Catalog? {
        val root = runCatching {
            json.decodeFromString(TachiyomixIndex.serializer(), body)
        }.getOrNull() ?: return null
        val extensions = root.extensionList?.extensions.orEmpty()
        if (extensions.isEmpty()) return null
        val packages = mutableListOf<Package>()
        for (extension in extensions) {
            val packageName = extension.packageName?.trim().orEmpty()
            val apkUrl = extension.resources?.apkUrl?.trim().orEmpty()
            if (packageName.isBlank() || !apkUrl.startsWith("http")) continue
            val versionName = extension.versionName?.trim().orEmpty()
            val versionCode = extension.versionCode
                ?.trim()?.toLongOrNull()
                ?: versionCodeOf(versionName)
            packages += Package(
                name = extension.name?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: extension.sources.firstOrNull()?.name?.trim()
                    ?: packageName,
                packageName = packageName,
                versionName = versionName,
                versionCode = versionCode,
                extensionLib = extension.extensionLib?.trim().orEmpty(),
                apkUrl = apkUrl,
                rating = ratingForContentWarning(extension.contentWarning),
                sources = extension.sources.mapNotNull { source ->
                    val url = source.homeUrl?.trim().orEmpty()
                    val name = source.name?.trim().orEmpty()
                    if (!url.startsWith("http") || name.isBlank()) {
                        null
                    } else {
                        SourceStub(
                            id = source.id?.trim().orEmpty(),
                            name = name,
                            lang = source.language?.trim().orEmpty(),
                            homeUrl = url
                        )
                    }
                }
            )
        }
        if (packages.isEmpty()) return null
        return Catalog(
            name = root.name?.trim()?.takeIf { it.isNotBlank() }
                ?: REPO_NAME,
            signingKey = root.signingKey?.trim()
                ?.lowercase()?.takeIf { it.isNotBlank() },
            packages = packages
        )
    }

    private fun parseCatalogClassic(body: String): Catalog? {
        val packages = runCatching {
            json.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(
                    MihonPackage.serializer()
                ),
                body
            )
        }.getOrNull() ?: return null
        if (packages.isEmpty()) return null
        // Classic entries name the APK FILE; it lives beside
        // the index document that listed it.
        val apkBase = INDEX_URL.substringBeforeLast('/') + "/"
        val out = mutableListOf<Package>()
        for (pkg in packages) {
            val packageName = pkg.pkg?.trim().orEmpty()
            val apkFile = pkg.apk?.trim().orEmpty()
            if (packageName.isBlank() || apkFile.isBlank()) continue
            if (pkg.name?.trim()?.startsWith("Outdated", ignoreCase = true) == true ||
                pkg.name?.trim()?.startsWith("Update to", ignoreCase = true) == true
            ) {
                continue
            }
            val versionName = pkg.version?.trim().orEmpty()
            val packageNsfw = (pkg.nsfw ?: 0) != 0
            out += Package(
                name = pkg.name?.trim()
                    ?.takeIf { it.isNotBlank() } ?: packageName,
                packageName = packageName,
                versionName = versionName,
                versionCode = versionCodeOf(versionName),
                extensionLib = "",
                apkUrl = if (apkFile.startsWith("http")) {
                    apkFile
                } else {
                    apkBase + apkFile.removePrefix("./")
                },
                rating = if (packageNsfw) {
                    ContentRating.ADULT
                } else {
                    ContentRating.SAFE
                },
                sources = pkg.sources.mapNotNull { source ->
                    val url = source.baseUrl?.trim().orEmpty()
                    val name = source.name?.trim().orEmpty()
                    if (!url.startsWith("http") || name.isBlank()) {
                        null
                    } else {
                        SourceStub(
                            id = source.id?.trim().orEmpty(),
                            name = name,
                            lang = source.lang?.trim().orEmpty(),
                            homeUrl = url
                        )
                    }
                }
            )
        }
        // The classic shape publishes no signing key.
        return Catalog(REPO_NAME, null, out).takeIf { out.isNotEmpty() }
    }

    // ── parsing ─────────────────────────────────────────────

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Parse an index body (either published shape) into the
     * unified entries. A document in neither shape — or with
     * no usable entries at all — is a plain ParseFailed,
     * never a crash and never an empty "success".
     */
    fun parse(body: String): AppResult<List<Entry>> {
        val entries = parseTachiyomix(body) ?: parseClassicMihon(body)
        if (entries.isNullOrEmpty()) {
            return AppResult.Err(
                AppError.ParseFailed(
                    "Keiyoushi list",
                    "the list wasn't in the expected format"
                )
            )
        }
        return AppResult.Ok(entries)
    }

    /** Shape 1 (verified live): the tachiyomix object form. */
    private fun parseTachiyomix(body: String): List<Entry>? {
        val root = runCatching {
            json.decodeFromString(TachiyomixIndex.serializer(), body)
        }.getOrNull() ?: return null
        val extensions = root.extensionList?.extensions.orEmpty()
        if (extensions.isEmpty()) return null
        val entries = mutableListOf<Entry>()
        for (extension in extensions) {
            val rating = ratingForContentWarning(extension.contentWarning)
            for (source in extension.sources) {
                val url = source.homeUrl?.trim().orEmpty()
                if (!url.startsWith("http")) continue
                val name = source.name?.trim().orEmpty()
                if (name.isBlank()) continue
                entries += Entry(
                    name = name,
                    lang = source.language?.trim().orEmpty(),
                    baseUrl = url,
                    mirrorUrls = source.mirrorUrls
                        .map { it.trim() }
                        .filter { it.startsWith("http") },
                    rating = rating,
                    packageName = extension.packageName?.trim().orEmpty()
                )
            }
        }
        return dedupe(entries).takeIf { it.isNotEmpty() }
    }

    /** Shape 2: the classic Mihon array form. */
    private fun parseClassicMihon(body: String): List<Entry>? {
        val packages = runCatching {
            json.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(
                    MihonPackage.serializer()
                ),
                body
            )
        }.getOrNull() ?: return null
        if (packages.isEmpty()) return null
        val entries = mutableListOf<Entry>()
        for (pkg in packages) {
            // The stub index ("Outdated App" nags) is not a
            // catalogue: an array of nothing but nags has no
            // usable entries and falls to the caller's error.
            if (pkg.name?.trim()?.startsWith("Outdated", ignoreCase = true) == true ||
                pkg.name?.trim()?.startsWith("Update to", ignoreCase = true) == true
            ) {
                continue
            }
            val packageNsfw = (pkg.nsfw ?: 0) != 0
            for (source in pkg.sources) {
                val url = source.baseUrl?.trim().orEmpty()
                if (!url.startsWith("http")) continue
                val name = source.name?.trim().orEmpty()
                if (name.isBlank()) continue
                entries += Entry(
                    name = name,
                    lang = source.lang?.trim().orEmpty(),
                    baseUrl = url,
                    rating = ratingForNsfw(
                        packageNsfw,
                        source.nsfw?.let { it != 0 }
                    ),
                    packageName = pkg.pkg?.trim().orEmpty()
                )
            }
        }
        return dedupe(entries).takeIf { it.isNotEmpty() }
    }

    /** One entry per (name, language, host): the same source
     * listed by two packages (a per-language package and an
     * "all" package) is one catalogue row; the FIRST listing
     * wins, index order being the publisher's own order. */
    private fun dedupe(entries: List<Entry>): List<Entry> =
        entries.distinctBy {
            "${it.name.lowercase()}|${it.lang.lowercase()}|${it.host}"
        }

    // ── wire shapes (private DTOs) ──────────────────────────

    @Serializable
    private data class TachiyomixIndex(
        val name: String? = null,
        /** K3: the signing key the index publishes for its
         * APKs — the player's trust anchor. */
        val signingKey: String? = null,
        val extensionList: TachiyomixExtensionList? = null
    )

    @Serializable
    private data class TachiyomixExtensionList(
        val extensions: List<TachiyomixExtension> = emptyList()
    )

    @Serializable
    private data class TachiyomixExtension(
        val name: String? = null,
        val packageName: String? = null,
        val contentWarning: String? = null,
        /** K3: installable form — download addresses, the
         * library version and the package version. */
        val resources: TachiyomixResources? = null,
        val extensionLib: String? = null,
        val versionCode: String? = null,
        val versionName: String? = null,
        val sources: List<TachiyomixSource> = emptyList()
    )

    @Serializable
    private data class TachiyomixResources(
        val apkUrl: String? = null,
        val iconUrl: String? = null
    )

    @Serializable
    private data class TachiyomixSource(
        /** K3: the source's Mihon id, as a string (the index
         * serialises the Long unquoted-or-quoted depending on
         * the generator; lenient parsing accepts both). */
        val id: String? = null,
        val name: String? = null,
        val language: String? = null,
        val homeUrl: String? = null,
        val mirrorUrls: List<String> = emptyList()
    )

    @Serializable
    private data class MihonPackage(
        val name: String? = null,
        val pkg: String? = null,
        val nsfw: Int? = null,
        /** K3: the APK file name + version (classic shape). */
        val apk: String? = null,
        val version: String? = null,
        val sources: List<MihonSource> = emptyList()
    )

    @Serializable
    private data class MihonSource(
        val id: String? = null,
        val name: String? = null,
        val lang: String? = null,
        val baseUrl: String? = null,
        val nsfw: Int? = null
    )
}

/** Whether [body] is a Keiyoushi index document at all (either
 * shape, with at least one usable entry) — the repo-add walk's
 * recognition hook, kept beside the reader it belongs to. */
fun isKeiyoushiIndex(body: String): Boolean =
    KeiyoushiIndex.parse(body) is AppResult.Ok
