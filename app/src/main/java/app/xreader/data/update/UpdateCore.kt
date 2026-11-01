package app.xreader.data.update

import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * In-app updater — the pure core (v1.3.5, Cycle 1): GitHub
 * Releases parsing, version comparison, the once-a-day gate and
 * SHA-256. No Android, no network — every rule here is pinned by
 * unit tests, and the Android/HTTP shell around it stays thin.
 *
 * RELEASE CONVENTION (what the release process must publish for
 * this to work — see CYCLE1-PLAN "In-app updates"):
 *  - a GitHub Release whose tag is the version (e.g. `v1.3.5`);
 *  - the signed APK as a release asset (name ends `.apk`);
 *  - its SHA-256, either as a checksum ASSET (`SHA256SUMS`,
 *    `SHA256SUMS.txt`, or any `*.sha256` file, in `sha256sum`
 *    format) or as a line in the release body naming the hash
 *    (a line containing "SHA-256" and the 64-hex digest is the
 *    documented shape; the extractor also accepts a bare digest
 *    line naming the APK file);
 *  - a short plain-words summary for the update card: a
 *    `## What's new` section in the release body whose bullet
 *    lines are written for a normal person, NOT a changelog.
 *    The card shows at most [ReleaseParsing.MAX_SUMMARY_LINES]
 *    of those bullets and never anything else from the body;
 *    the full body is one "Read more" away, inside the app.
 */

/** One downloadable file attached to a GitHub release. */
data class ReleaseAsset(
    val name: String,
    val downloadUrl: String,
    val sizeBytes: Long
)

/** A parsed GitHub "latest release", reduced to what the
 * updater needs. [version] is the tag with any leading `v`
 * stripped — the same shape as BuildConfig.VERSION_NAME. */
data class ReleaseInfo(
    val tagName: String,
    val version: String,
    val title: String,
    val body: String,
    val htmlUrl: String,
    val apk: ReleaseAsset,
    val checksumAsset: ReleaseAsset?
)

object ReleaseParsing {

    /** The card's summary is a few lines, never a wall. */
    const val MAX_SUMMARY_LINES = 4

    private val json = Json { ignoreUnknownKeys = true }

    /** Parses a `GET /repos/{owner}/{repo}/releases/latest`
     * response. Null when the payload is unusable: not JSON, a
     * draft, or no `.apk` asset attached. */
    fun parseLatestRelease(payload: String): ReleaseInfo? {
        val root = runCatching { json.parseToJsonElement(payload).jsonObject }
            .getOrNull() ?: return null
        if (root.bool("draft")) return null
        val tag = root.string("tag_name")?.trim().orEmpty()
        if (tag.isEmpty()) return null
        val assets = root["assets"]?.let { runCatching { it.jsonArray }.getOrNull() }
            .orEmpty()
            .mapNotNull { element ->
                val obj = runCatching { element.jsonObject }.getOrNull()
                    ?: return@mapNotNull null
                val name = obj.string("name") ?: return@mapNotNull null
                val url = obj.string("browser_download_url")
                    ?: return@mapNotNull null
                ReleaseAsset(
                    name = name,
                    downloadUrl = url,
                    sizeBytes = obj.long("size") ?: 0L
                )
            }
        val apk = pickApk(assets) ?: return null
        return ReleaseInfo(
            tagName = tag,
            version = VersionNames.normalise(tag),
            title = root.string("name")?.trim().orEmpty(),
            body = root.string("body").orEmpty(),
            htmlUrl = root.string("html_url").orEmpty(),
            apk = apk,
            checksumAsset = pickChecksumAsset(assets)
        )
    }

    /** The release's APK asset: any `.apk`; when several are
     * attached the one naming the app wins, else the largest
     * (a stray sample APK must never shadow the real one). */
    private fun pickApk(assets: List<ReleaseAsset>): ReleaseAsset? {
        val apks = assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
        if (apks.isEmpty()) return null
        return apks.firstOrNull { it.name.contains("xreader", ignoreCase = true) }
            ?: apks.maxByOrNull { it.sizeBytes }
            ?: apks.first()
    }

    /** The checksum asset, when the release carries one:
     * `SHA256SUMS`-style names or any `*.sha256` file. */
    private fun pickChecksumAsset(assets: List<ReleaseAsset>): ReleaseAsset? =
        assets.firstOrNull { asset ->
            val name = asset.name.lowercase()
            name == "sha256sums" || name == "sha256sums.txt" ||
                name == "checksums.txt" || name.endsWith(".sha256") ||
                name.endsWith(".sha256sum")
        }

    private val hexDigest = Regex("""\b[0-9a-fA-F]{64}\b""")

    /**
     * Finds the APK's SHA-256. The checksum ASSET's text wins
     * when given (its line naming the APK file, else its only
     * digest); otherwise the release BODY is searched — a line
     * that mentions SHA, or names the APK file, or (last resort)
     * the body's first standalone digest. Null when neither
     * source publishes one: the updater fails closed rather
     * than installing an unverifiable file.
     */
    fun extractChecksum(
        checksumAssetText: String?,
        body: String?,
        apkName: String
    ): String? {
        if (!checksumAssetText.isNullOrBlank()) {
            val lines = checksumAssetText.lines()
            lines.firstOrNull { it.contains(apkName, ignoreCase = true) }
                ?.let { line -> hexDigest.find(line)?.value?.let { return it.lowercase() } }
            val digests = hexDigest.findAll(checksumAssetText).map { it.value }.toList()
            if (digests.size == 1) return digests.first().lowercase()
        }
        if (!body.isNullOrBlank()) {
            val lines = body.lines()
            val shaLine = lines.firstOrNull {
                it.contains("sha", ignoreCase = true) && hexDigest.containsMatchIn(it)
            }
            if (shaLine != null) {
                return hexDigest.find(shaLine)?.value?.lowercase()
            }
            lines.firstOrNull { it.contains(apkName, ignoreCase = true) }
                ?.let { line -> hexDigest.find(line)?.value?.let { return it.lowercase() } }
            return hexDigest.find(body)?.value?.lowercase()
        }
        return null
    }

    /**
     * The card's short summary: the bullet lines under the
     * body's `## What's new` heading (see the convention in
     * this file's header), markdown-stripped, at most
     * [MAX_SUMMARY_LINES]. Empty when the release carries no
     * such section — the card then shows its generic line.
     */
    fun shortSummary(body: String): List<String> {
        val lines = body.lines()
        val start = lines.indexOfFirst { line ->
            val text = line.trim().trimStart('#').trim()
            text.equals("what's new", ignoreCase = true) ||
                text.equals("what’s new", ignoreCase = true)
        }
        if (start < 0) return emptyList()
        val summary = mutableListOf<String>()
        for (line in lines.drop(start + 1)) {
            val trimmed = line.trim()
            if (trimmed.startsWith("#")) break
            val bullet = when {
                trimmed.startsWith("- ") -> trimmed.drop(2)
                trimmed.startsWith("* ") -> trimmed.drop(2)
                trimmed.startsWith("• ") -> trimmed.drop(2)
                else -> null
            }
            if (bullet != null) {
                val clean = stripMarkdown(bullet).trim()
                if (clean.isNotEmpty()) summary += clean
                if (summary.size >= MAX_SUMMARY_LINES) break
            } else if (trimmed.isNotEmpty() && summary.isNotEmpty()) {
                break
            }
        }
        return summary
    }

    /** Just enough markdown removal for one-line summaries:
     * bold/italic markers, code ticks and [text](url) links. */
    private fun stripMarkdown(text: String): String =
        text.replace(Regex("""\[([^\]]+)\]\([^)]*\)"""), "$1")
            .replace("**", "")
            .replace("__", "")
            .replace("`", "")
            .trim()

    private fun JsonObject.string(key: String): String? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content

    private fun JsonObject.long(key: String): Long? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull()

    private fun JsonObject.bool(key: String): Boolean =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content
            ?.toBooleanStrictOrNull() ?: false
}

/** Tolerant version-name comparison: `v1.3.5` vs `1.3.4`,
 * missing segments read as 0, a segment's leading digits are
 * its number (`5-beta` → 5), and when the numbers tie, the
 * build carrying a pre-release suffix is the older one. */
object VersionNames {

    fun normalise(version: String): String =
        version.trim().removePrefix("v").removePrefix("V").trim()

    /** -1 / 0 / 1 as [a] is older than / equal to / newer
     * than [b]. */
    fun compare(a: String, b: String): Int {
        val left = segments(normalise(a))
        val right = segments(normalise(b))
        val count = maxOf(left.size, right.size)
        for (i in 0 until count) {
            val x = left.getOrElse(i) { 0 }
            val y = right.getOrElse(i) { 0 }
            if (x != y) return if (x < y) -1 else 1
        }
        val aSuffix = hasSuffix(a)
        val bSuffix = hasSuffix(b)
        return when {
            aSuffix == bSuffix -> 0
            aSuffix -> -1
            else -> 1
        }
    }

    fun isNewer(candidate: String, installed: String): Boolean =
        compare(candidate, installed) > 0

    private fun segments(version: String): List<Int> =
        version.split('.').map { segment ->
            segment.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
        }

    private fun hasSuffix(version: String): Boolean {
        val clean = normalise(version)
        return clean.contains('-') ||
            clean.split('.').any { segment ->
                segment.any { it.isLetter() }
            }
    }
}

/**
 * The once-a-day rules (user, 2026-11-01): the automatic
 * check runs at most once per calendar day, and the floating
 * card is SHOWN at most once per calendar day — ignored or
 * dismissed, it stays away until tomorrow. Pure functions of
 * an injected clock so the rules are testable.
 */
object UpdateGate {

    /** The local calendar day (ISO, e.g. `2026-11-01`) that
     * contains [epochMs] in [zone]. */
    fun dayOf(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate().toString()

    /** An automatic check is due when auto-check is on and
     * the last check was not today. */
    fun dueForAutoCheck(
        autoCheckEnabled: Boolean,
        lastCheckDay: String?,
        nowMs: Long,
        zone: ZoneId = ZoneId.systemDefault()
    ): Boolean = autoCheckEnabled && lastCheckDay != dayOf(nowMs, zone)

    /** The card may appear when an update is available and
     * it has not already been shown today. */
    fun mayShowCard(
        updateAvailable: Boolean,
        cardShownDay: String?,
        nowMs: Long,
        zone: ZoneId = ZoneId.systemDefault()
    ): Boolean = updateAvailable && cardShownDay != dayOf(nowMs, zone)
}

/** SHA-256 over bytes / files / streams, lowercase hex. */
object Sha256 {

    fun ofBytes(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).toHex()
    }

    fun ofFile(file: File): String = file.inputStream().use { ofStream(it) }

    fun ofStream(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        return digest.digest().toHex()
    }

    /** Case-insensitive comparison against a published hex
     * digest; a malformed expectation never matches. */
    fun matches(file: File, expectedHex: String): Boolean {
        val expected = expectedHex.trim().lowercase()
        if (expected.length != 64 || expected.any { it !in '0'..'9' && it !in 'a'..'f' }) {
            return false
        }
        return ofFile(file) == expected
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { byte -> "%02x".format(byte.toInt() and 0xFF) }
}
