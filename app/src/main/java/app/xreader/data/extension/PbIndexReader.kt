package app.xreader.data.extension

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream

/**
 * A minimal reader for the tachiyomix repository index
 * (v1.0.9) — the protobuf format Mihon's current extension
 * repos and Novel Sourcery publish as `index.pb`. The schema
 * was verified against the publisher tooling
 * (mihonapp/tachiyomix `index.proto`, mirrored in the
 * keiyoushi / novelsourcery extensions-source repos):
 *
 * ```
 * message Index {
 *   string name = 1;  string badgeLabel = 2;  string signingKey = 3;
 *   Contact contact = 4;
 *   oneof extensions {
 *     ExtensionList extensionList = 101;
 *     string extensionListUrl = 102;
 *   }
 * }
 * message ExtensionList { repeated Extension extensions = 1; }
 * message Extension {
 *   string name = 1;  string packageName = 2;  Resources resources = 3;
 *   string extensionLib = 4;  int64 versionCode = 5;
 *   string versionName = 6;  ContentWarning contentWarning = 7;
 *   repeated Source sources = 8;
 * }
 * message Source {
 *   int64 id = 1;  string name = 2;  string language = 3;
 *   string homeUrl = 4;  repeated string mirrorUrls = 5;
 *   optional string message = 7;
 * }
 * ```
 *
 * Proto3 wire format needs no library for this: every field
 * here is a varint or a length-delimited string/message, and
 * unknown fields are skipped by wire type. Malformed input
 * yields null — this reader never throws.
 *
 * Two wire realities the reader absorbs itself (site-batch
 * Phase A, verified against the real Keiyoushi capture):
 *
 * - GZIP: the published `index.pb` arrives gzip-wrapped.
 *   OkHttp's default client decompresses a `Content-Encoding:
 *   gzip` response transparently, but a repo serving the file
 *   AS gzip data (no such header), or any non-OkHttp path,
 *   hands this reader the raw gzip bytes — so the reader
 *   sniffs the gzip magic and unwraps, exactly as the
 *   `.tachibk` reader does. "May be gzip-wrapped" is a
 *   property of the format, not of one HTTP client.
 * - FIELD 101 SHAPES: field 101 is a singular message field,
 *   so proto3 merge semantics apply — several 101 payloads
 *   are read as ONE merged ExtensionList, not just the
 *   first; and a payload that is itself a bare Extension
 *   (a publisher writing extensions as repeated 101 entries,
 *   no list wrapper) is accepted as a one-extension list.
 */
data class PbSource(
    val id: Long,
    val name: String,
    val language: String,
    val homeUrl: String,
    val mirrorUrls: List<String> = emptyList()
)

data class PbExtension(
    val name: String,
    val packageName: String,
    val versionCode: Long,
    val versionName: String,
    val contentWarning: Int,
    val sources: List<PbSource>
)

data class PbIndex(
    val name: String,
    val signingKey: String?,
    val extensions: List<PbExtension>,
    /** The oneof alternative to an inline list: the index is a
     * pointer at another index file. Null when the list is
     * inline (the common case). */
    val extensionListUrl: String?
)

object PbIndexReader {

    fun read(bytes: ByteArray): PbIndex? {
        val plain = unwrapGzip(bytes) ?: return null
        val index = parseMessage(plain) ?: return null
        // Every field-101 payload contributes its extensions
        // (proto3 merge semantics for a singular message
        // field); a payload may be an ExtensionList wrapper
        // or, in the unwrapped shape, a bare Extension.
        val extensions = index.bytes(101)
            .flatMap { extensionsFromPayload(it) }
        return PbIndex(
            name = index.string(1) ?: "",
            signingKey = index.string(3),
            extensions = extensions,
            extensionListUrl = index.string(102)
        )
    }

    /** One field-101 payload → its extensions. A genuine
     * ExtensionList parses to extensions that carry package
     * names; anything else is tried as a bare Extension
     * message before falling back to whatever the list read
     * produced (possibly empty). */
    private fun extensionsFromPayload(payload: ByteArray): List<PbExtension> {
        val list = parseMessage(payload) ?: return emptyList()
        val fromList = list.bytes(1).mapNotNull { parseExtension(it) }
        if (fromList.isNotEmpty() &&
            fromList.all { it.packageName.isNotBlank() }
        ) {
            return fromList
        }
        val direct = parseExtension(payload)
        if (direct != null && direct.name.isNotBlank() &&
            direct.packageName.isNotBlank()
        ) {
            return listOf(direct)
        }
        return fromList
    }

    /** The bytes as a plain protobuf message: gzip-wrapped
     * input (magic `1f 8b`) is decompressed, anything else is
     * passed through untouched. Null on a corrupt or absurdly
     * large gzip stream — the reader never throws. */
    private fun unwrapGzip(bytes: ByteArray): ByteArray? {
        if (bytes.size < 2 ||
            bytes[0] != 0x1f.toByte() || bytes[1] != 0x8b.toByte()
        ) {
            return bytes
        }
        return try {
            GZIPInputStream(bytes.inputStream()).use { gzip ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = gzip.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    if (out.size() > MAX_DECOMPRESSED_BYTES) return null
                }
                out.toByteArray()
            }
        } catch (e: Exception) {
            null
        }
    }

    /** A decompressed index is a catalogue, not a payload:
     * 64 MB is far beyond any published repo index (the real
     * Keiyoushi one is 0.7 MB) and bounds a gzip bomb. */
    private const val MAX_DECOMPRESSED_BYTES = 64 * 1024 * 1024

    private fun parseExtension(bytes: ByteArray): PbExtension? {
        val ext = parseMessage(bytes) ?: return null
        return PbExtension(
            name = ext.string(1) ?: "",
            packageName = ext.string(2) ?: "",
            versionCode = ext.varint(5) ?: 0L,
            versionName = ext.string(6) ?: "",
            contentWarning = (ext.varint(7) ?: 0L).toInt(),
            sources = ext.bytes(8).mapNotNull { parseSource(it) }
        )
    }

    private fun parseSource(bytes: ByteArray): PbSource? {
        val source = parseMessage(bytes) ?: return null
        val homeUrl = source.string(4) ?: return null
        return PbSource(
            id = source.varint(1) ?: 0L,
            name = source.string(2) ?: "",
            language = source.string(3) ?: "",
            homeUrl = homeUrl,
            mirrorUrls = source.bytes(5).mapNotNull {
                it.toString(Charsets.UTF_8).ifBlank { null }
            }
        )
    }

    // ── the wire walker ─────────────────────────────────────

    /** One parsed message: field number → its values, varints
     * as Long and everything length-delimited as raw bytes. */
    private class Fields(
        private val varints: Map<Int, List<Long>>,
        private val payloads: Map<Int, List<ByteArray>>
    ) {
        fun varint(field: Int): Long? = varints[field]?.firstOrNull()
        fun bytes(field: Int): List<ByteArray> = payloads[field].orEmpty()
        fun string(field: Int): String? =
            bytes(field).firstOrNull()?.toString(Charsets.UTF_8)
                ?.ifBlank { null }
    }

    private fun parseMessage(bytes: ByteArray): Fields? {
        val varints = mutableMapOf<Int, MutableList<Long>>()
        val payloads = mutableMapOf<Int, MutableList<ByteArray>>()
        var pos = 0
        while (pos < bytes.size) {
            val (tag, afterTag) = readVarint(bytes, pos) ?: return null
            pos = afterTag
            val field = (tag ushr 3).toInt()
            if (field <= 0) return null
            when ((tag and 0x7L).toInt()) {
                0 -> {
                    val (value, after) = readVarint(bytes, pos) ?: return null
                    pos = after
                    varints.getOrPut(field) { mutableListOf() } += value
                }
                2 -> {
                    val (length, after) = readVarint(bytes, pos) ?: return null
                    pos = after
                    if (length < 0 || length > bytes.size - pos) return null
                    val slice = bytes.copyOfRange(pos, pos + length.toInt())
                    pos += length.toInt()
                    payloads.getOrPut(field) { mutableListOf() } += slice
                }
                1 -> {
                    if (bytes.size - pos < 8) return null
                    pos += 8
                }
                5 -> {
                    if (bytes.size - pos < 4) return null
                    pos += 4
                }
                else -> return null
            }
        }
        return Fields(varints, payloads)
    }

    /** A base-128 varint at [start]; the value and the offset
     * just past it, or null when it runs off the end or never
     * terminates within the 10 bytes a 64-bit varint may take. */
    private fun readVarint(bytes: ByteArray, start: Int): Pair<Long, Int>? {
        var result = 0L
        var shift = 0
        var pos = start
        while (pos < bytes.size && shift < 70) {
            val b = bytes[pos].toInt() and 0xFF
            pos++
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result to pos
            shift += 7
        }
        return null
    }
}
