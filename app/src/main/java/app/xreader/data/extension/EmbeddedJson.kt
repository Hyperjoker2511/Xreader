package app.xreader.data.extension

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.jsoup.nodes.Document

/**
 * v1.2.3: reading the data a page carries INSIDE itself.
 *
 * A growing family of novel sites (Next.js apps — novelbuddy.me
 * is the proven case) server-renders pages whose real catalogue,
 * search results and chapter lists are not DOM rows at all: they
 * ride in the page's embedded JSON — the `__NEXT_DATA__` script
 * block, or the React Server Components stream
 * (`self.__next_f.push(...)`) — while the visible DOM is a
 * landing page, a skeleton, or a short window of the data. A
 * selector-only engine reads those pages as empty, which is how
 * a source could be saved with NO catalogue in v1.2.2
 * (build-logs/V122-CRASH-INVESTIGATION.md).
 *
 * This object is the deterministic half of the answer:
 * extraction of the embedded payloads from a fetched document,
 * dot-path resolution over them, and SHAPE DISCOVERY — finding
 * the array of book records / chapter records inside a payload
 * by the fields the records carry, so inference can store a
 * path the engine then executes verbatim (no guessing at read
 * time). Discovery is scoring-based and bounded; anything it
 * cannot name confidently, it does not name at all.
 */
object EmbeddedJson {

    private val json = Json { ignoreUnknownKeys = true }

    // ── extraction ──────────────────────────────────────────

    /** The document's `__NEXT_DATA__` object, or null when the
     * page carries none (or it doesn't parse). */
    fun nextData(doc: Document): JsonObject? {
        val text = runCatching {
            doc.selectFirst("script#__NEXT_DATA__")?.data()
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            json.parseToJsonElement(text) as? JsonObject
        }.getOrNull()
    }

    /**
     * Every embedded-JSON root the document carries, in the
     * order the engine should prefer them: the `__NEXT_DATA__`
     * object first, then each parseable React flight row from
     * the RSC stream.
     */
    fun roots(doc: Document): List<JsonElement> {
        val out = mutableListOf<JsonElement>()
        nextData(doc)?.let { out += it }
        out += rscRoots(doc)
        return out
    }

    /**
     * The React Server Components stream, reassembled: every
     * `self.__next_f.push([n,"…"])` string payload is unescaped
     * and concatenated (the stream is chunked mid-line by
     * design), then each `id:payload` row that parses as JSON
     * becomes a root. Rows that don't parse (element trees with
     * `$` references parse fine; plain-text rows don't) are
     * skipped — model data is what this engine reads.
     */
    fun rscRoots(doc: Document): List<JsonElement> {
        val stream = StringBuilder()
        for (script in doc.select("script")) {
            val text = runCatching { script.data() }.getOrNull()
                ?: continue
            if (!text.contains("__next_f")) continue
            var index = 0
            while (true) {
                val push = text.indexOf("push(", index)
                if (push < 0) break
                val quote = text.indexOf('"', push)
                if (quote < 0) break
                val (value, next) = readJsString(text, quote)
                if (value == null) break
                stream.append(value)
                index = next
            }
        }
        if (stream.isEmpty()) return emptyList()
        val roots = mutableListOf<JsonElement>()
        for (line in stream.lines()) {
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val payload = line.substring(colon + 1)
            if (payload.isEmpty()) continue
            if (payload[0] != '{' && payload[0] != '[') continue
            runCatching { json.parseToJsonElement(payload) }
                .getOrNull()?.let { roots += it }
        }
        return roots
    }

    /** One double-quoted JS string literal starting at [quote]:
     * its unescaped value and the index just past its closing
     * quote, or null when unterminated. */
    private fun readJsString(
        text: String,
        quote: Int
    ): Pair<String?, Int> {
        val out = StringBuilder()
        var i = quote + 1
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\\' && i + 1 < text.length -> {
                    when (val esc = text[i + 1]) {
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'u' -> {
                            val hex = text.substring(
                                i + 2, minOf(i + 6, text.length)
                            )
                            val code = hex.toIntOrNull(16)
                            if (code == null) {
                                out.append('u')
                                i += 1
                            } else {
                                out.append(code.toChar())
                                i += 4
                            }
                        }
                        else -> out.append(esc)
                    }
                    i += 2
                }
                c == '"' -> return out.toString() to i + 1
                else -> {
                    out.append(c)
                    i += 1
                }
            }
        }
        return null to text.length
    }

    // ── paths ───────────────────────────────────────────────

    /** Resolve a dot path (`props.pageProps.items`) over a
     * parsed payload; numeric segments index into arrays.
     * Null when any hop is absent or the wrong shape. */
    fun resolve(root: JsonElement, path: String): JsonElement? {
        if (path.isBlank()) return root
        var current: JsonElement = root
        for (segment in path.split('.')) {
            current = when (current) {
                is JsonObject -> current[segment] ?: return null
                is JsonArray -> {
                    val index = segment.toIntOrNull() ?: return null
                    current.getOrNull(index) ?: return null
                }
                else -> return null
            }
        }
        return current
    }

    /** The string at a dot path inside one record, or null
     * when absent, not a primitive, or blank. Numbers and
     * booleans read as their literal text. */
    fun stringAt(record: JsonObject, fieldPath: String): String? {
        val element = resolve(record, fieldPath) as? JsonPrimitive
            ?: return null
        return element.contentOrNull?.ifBlank { null }
    }

    // ── discovery ───────────────────────────────────────────

    /** A discovered array of book records inside a payload:
     * where it lives, and which record fields carry the title,
     * the book link and (when present) the cover. */
    data class BookArray(
        val rootIndex: Int,
        val path: String,
        val titleField: String,
        val linkField: String,
        val coverField: String?,
        val count: Int
    )

    /** A discovered array of chapter records, with the total
     * the book's own data states when it states one (a
     * "2,217 chapters" display field or a numeric total
     * beside the array). */
    data class ChapterArray(
        val rootIndex: Int,
        val path: String,
        val nameField: String,
        val linkField: String,
        val count: Int,
        val statedTotal: Int?
    )

    private val TITLE_FIELDS = listOf("name", "title")
    private val LINK_FIELDS = listOf("url", "href", "link", "path", "permalink")
    private val COVER_FIELDS = listOf(
        "cover", "coverUrl", "image", "imageUrl", "poster", "thumbnail"
    )
    private val NUMBER_FIELDS = listOf(
        "number", "chapterNumber", "chapter_number", "no", "index"
    )

    private data class Found(
        val rootIndex: Int,
        val path: String,
        val array: JsonArray,
        val parent: JsonObject?,
        val depth: Int,
        val score: Int
    )

    /**
     * Find the payload's book-record array: an array of objects
     * whose records carry a title field and a book link field.
     * Chapter-link arrays are actively scored DOWN (their links
     * are chapter-shaped) and cover-carrying records score UP,
     * so a catalogue array beats the chapter windows nested
     * inside its own records. [minItems] is the smallest array
     * that counts (a catalogue needs a cluster; a search page
     * may honestly return one book).
     */
    fun discoverBookArray(doc: Document, minItems: Int): BookArray? {
        val roots = roots(doc)
        var best: Pair<Found, BookArray>? = null
        roots.forEachIndexed { rootIndex, root ->
            walk(root, "", null, 0) { path, element, parent, depth ->
                val array = element as? JsonArray ?: return@walk
                if (array.size < minItems) return@walk
                val records = array.mapNotNull { it as? JsonObject }
                if (records.size < minItems) return@walk
                val titleField = fieldOf(records, TITLE_FIELDS) { true }
                    ?: return@walk
                val linkField = fieldOf(records, LINK_FIELDS) { value ->
                    value.startsWith("/") || value.startsWith("http")
                } ?: return@walk
                val chapterish = records.count { record ->
                    val link = stringAt(record, linkField) ?: ""
                    link.contains("/chapter") || link.contains("chapter-")
                }
                // A listing whose links are mostly chapters is
                // a chapter list wearing a catalogue's shape.
                if (chapterish * 2 > records.size) return@walk
                val coverField = fieldOf(records, COVER_FIELDS) { value ->
                    value.startsWith("/") || value.startsWith("http")
                }
                val score = records.size +
                    (if (coverField != null) 1000 else 0) -
                    depth * 10
                val found = Found(rootIndex, path, array, parent, depth, score)
                val candidate = BookArray(
                    rootIndex, path, titleField, linkField, coverField,
                    records.size
                )
                if (best == null || score > best!!.first.score) {
                    best = found to candidate
                }
            }
        }
        return best?.second
    }

    /**
     * Find the payload's chapter-record array: records whose
     * links are chapter-shaped, or which carry a chapter
     * number field beside a name and a link. The LONGEST such
     * array wins (a book page can carry several small chapter
     * windows — latest-3 rails — beside the real list).
     */
    fun discoverChapterArray(doc: Document): ChapterArray? {
        val roots = roots(doc)
        var best: Pair<Found, ChapterArray>? = null
        roots.forEachIndexed { rootIndex, root ->
            walk(root, "", null, 0) { path, element, parent, depth ->
                val array = element as? JsonArray ?: return@walk
                if (array.size < 3) return@walk
                val records = array.mapNotNull { it as? JsonObject }
                if (records.size < 3) return@walk
                val nameField = fieldOf(records, TITLE_FIELDS) { true }
                    ?: return@walk
                val linkField = fieldOf(records, LINK_FIELDS) { value ->
                    value.startsWith("/") || value.startsWith("http")
                } ?: return@walk
                val chapterish = records.count { record ->
                    val link = stringAt(record, linkField) ?: ""
                    link.contains("/chapter") || link.contains("chapter-")
                }
                val numbered = records.count { record ->
                    NUMBER_FIELDS.any { field ->
                        (record[field] as? JsonPrimitive)?.let {
                            it.contentOrNull?.toFloatOrNull() != null
                        } == true
                    }
                }
                if (chapterish * 2 <= records.size &&
                    numbered * 2 <= records.size
                ) {
                    return@walk
                }
                val score = records.size - depth * 10
                val found = Found(rootIndex, path, array, parent, depth, score)
                val candidate = ChapterArray(
                    rootIndex, path, nameField, linkField,
                    records.size, statedTotalNear(parent, array)
                )
                if (best == null || score > best!!.first.score) {
                    best = found to candidate
                }
            }
        }
        return best?.second
    }

    /** The chapter total a payload states beside a chapter
     * array: the array's parent object (the book record) is
     * where sites print it — a "2,217 chapters" display string
     * or a numeric total field. */
    private fun statedTotalNear(
        parent: JsonObject?,
        array: JsonArray
    ): Int? {
        if (parent == null) return null
        for ((key, value) in parent) {
            if (value === array) continue
            val primitive = value as? JsonPrimitive ?: continue
            val text = primitive.contentOrNull ?: continue
            if (key.contains("chapter", ignoreCase = true) ||
                text.contains("chapter", ignoreCase = true)
            ) {
                val number = Regex("([\\d,]+)")
                    .find(text)?.groupValues?.get(1)
                    ?.replace(",", "")?.toIntOrNull()
                if (number != null && number > array.size) return number
            }
        }
        return null
    }

    /** The first field from [candidates] that at least half
     * the records carry as a non-blank string passing
     * [accept]. */
    private fun fieldOf(
        records: List<JsonObject>,
        candidates: List<String>,
        accept: (String) -> Boolean
    ): String? {
        for (field in candidates) {
            val hits = records.count { record ->
                val value = (record[field] as? JsonPrimitive)
                    ?.contentOrNull
                !value.isNullOrBlank() && accept(value)
            }
            if (hits * 2 >= records.size) return field
        }
        return null
    }

    /** Bounded depth-first walk over a payload: every element
     * is visited with its dot path, its parent object (when
     * the element sits directly inside one) and its depth.
     * The bounds keep a hostile or pathological payload from
     * turning discovery into a hang — the same discipline the
     * wire readers already keep. */
    private fun walk(
        element: JsonElement,
        path: String,
        parent: JsonObject?,
        depth: Int,
        visit: (String, JsonElement, JsonObject?, Int) -> Unit
    ) {
        if (depth > MAX_DEPTH) return
        visit(path, element, parent, depth)
        when (element) {
            is JsonObject -> for ((key, value) in element) {
                walk(
                    value,
                    if (path.isEmpty()) key else "$path.$key",
                    element,
                    depth + 1,
                    visit
                )
            }
            is JsonArray -> element.forEachIndexed { index, value ->
                walk(
                    value,
                    if (path.isEmpty()) index.toString()
                    else "$path.$index",
                    parent,
                    depth + 1,
                    visit
                )
            }
            else -> Unit
        }
    }

    private const val MAX_DEPTH = 9
}
