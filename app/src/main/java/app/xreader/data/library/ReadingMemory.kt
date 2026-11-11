package app.xreader.data.library

import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * v1.5.6 (§3.7): the reading-memory codec — the shapes the memory
 * NOTE stores (bookmarks/highlights as locator JSON strings) and
 * the fingerprint arithmetic every seam shares. The fingerprint
 * is "displayName|sizeBytes|sha256": a same-name different-content
 * file hashes differently and must NOT match.
 */
@Serializable
data class MemoryBookmark(
    val label: String = "",
    val locatorJson: String? = null,
    val chapterIndex: Int = 0,
    val charOffset: Int = 0
)

@Serializable
data class MemoryHighlight(
    val locatorJson: String? = null,
    val chapterIndex: Int = 0,
    val startOffset: Int = 0,
    val endOffset: Int = 0,
    val quotedText: String = "",
    val color: String = "amber",
    val note: String? = null
)

object ReadingMemoryCodec {

    private val json = Json { ignoreUnknownKeys = true }

    fun fingerprintOf(displayName: String, sizeBytes: Long, sha256: String): String =
        "$displayName|$sizeBytes|$sha256"

    /** The display-name segment of a fingerprint ("" when malformed). */
    fun displayNameOf(fingerprint: String): String = fingerprint.substringBefore('|')

    /** The size segment of a fingerprint (null when malformed). */
    fun sizeBytesOf(fingerprint: String): Long? =
        fingerprint.split('|').getOrNull(1)?.toLongOrNull()

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input -> update(digest, input) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun sha256Hex(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        update(digest, input)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun update(digest: MessageDigest, input: InputStream) {
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }

    fun encodeBookmarks(bookmarks: List<MemoryBookmark>): String =
        json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(MemoryBookmark.serializer()),
            bookmarks
        )

    fun decodeBookmarks(text: String): List<MemoryBookmark> =
        runCatching {
            json.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(MemoryBookmark.serializer()),
                text
            )
        }.getOrDefault(emptyList())

    fun encodeHighlights(highlights: List<MemoryHighlight>): String =
        json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(MemoryHighlight.serializer()),
            highlights
        )

    fun decodeHighlights(text: String): List<MemoryHighlight> =
        runCatching {
            json.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(MemoryHighlight.serializer()),
                text
            )
        }.getOrDefault(emptyList())

    /** Parses a locator JSON object, or null when it isn't one. */
    fun locatorObject(locatorJson: String?): JsonObject? {
        if (locatorJson.isNullOrBlank()) return null
        return runCatching { Json.parseToJsonElement(locatorJson).jsonObject }.getOrNull()
    }

    /** The position index a locator names: chapterIndex for the
     * legacy/EPUB forms, section for EPUB in-place, page for PDF. */
    fun locatorIndex(locatorJson: String?): Int? {
        val obj = locatorObject(locatorJson) ?: return null
        for (key in listOf("chapterIndex", "section", "page")) {
            obj[key]?.jsonPrimitive?.intOrNull?.let { return it }
        }
        return null
    }

    /** The bookFraction a locator carries, when it carries one. */
    fun locatorBookFraction(locatorJson: String?): Float? =
        locatorObject(locatorJson)?.get("bookFraction")?.jsonPrimitive?.floatOrNull

    /** A legacyChapter locator for a chapter-keyed record that has
     * none of its own yet (delete-time capture, §3.7). */
    fun legacyLocator(chapterIndex: Int, charOffset: Int): String =
        "{\"kind\":\"legacyChapter\",\"chapterIndex\":$chapterIndex,\"charOffset\":$charOffset}"

    fun legacyHighlightLocator(chapterIndex: Int, startOffset: Int, endOffset: Int): String =
        "{\"kind\":\"legacyChapter\",\"chapterIndex\":$chapterIndex," +
            "\"charOffset\":$startOffset,\"startOffset\":$startOffset,\"endOffset\":$endOffset}"
}
