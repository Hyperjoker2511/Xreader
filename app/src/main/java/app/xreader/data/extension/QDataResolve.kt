package app.xreader.data.extension

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * v1.5.35 (Item 5, Xnovel): the Qwik City `q-data.json`
 * resolver shim — the one native addition the XNOVEL source
 * spec (build-logs/XNOVEL-SOURCE-SPEC.md, Step 2/3) prescribes.
 *
 * xnovel.me serves every route's loader data as a Qwik City
 * `q-data.json` document: `{"_entry": "…", "_objs": [ … ]}` —
 * a flat object graph in which each `_objs` entry is a literal
 * and every string nested inside a container is a base-36
 * INDEX into `_objs` (a trailing `!` marks a shared ref).
 * No dot-path read can address that graph, so this shim runs
 * as a fetch pre-step: resolve the graph, classify the
 * records by their key sets (the spec's identification rule —
 * book = `N_title` + `info_cover_name`, chapter = `ch_title`
 * + `ChPath`, paging = `total` + `pages` + `size`), and
 * re-emit ONE plain envelope the engine's existing field
 * mappings read unchanged:
 *
 * ```
 * { "books": [ …book records, page order… ],
 *   "book": { …the details-grade book record, plus a
 *             "description" alias of info_descText… } | null,
 *   "chapters": [ …chapter records, ch_index ascending… ],
 *   "chapter": { …the chapter record carrying content… } | null,
 *   "content": "…the chapter's content, hoisted…" | null,
 *   "paging": { …the chapter-list paging… } | null,
 *   "pagination": { "has_next": true|false } }
 * ```
 *
 * The transform is SHAPE-GUARDED: a body that does not parse
 * as a JSON object carrying an `_objs` array is returned
 * byte-identical, so every other source's fetches pass
 * through untouched. Any failure inside the transform also
 * returns the original body — a shim failure must degrade to
 * the honest shape-miss, never to a crash or invented data.
 *
 * Proven live 2026-11-07 from the build VM against the real
 * payloads (popular / search / the 1,293-chapter proof book):
 * records resolve to real titles, authors, covers and paging,
 * and the walk order matches the entry tree's own order.
 */
object QDataResolve {

    private val json = Json { ignoreUnknownKeys = true }

    private const val MAX_RESOLVE_DEPTH = 40
    private const val MAX_WALK_DEPTH = 12

    /** The chapter window size the site's own loaders use
     * (`init: 45, size: 45`) — how the envelope tells the
     * chapter-list paging apart from a listing paging. */
    private const val CHAPTER_PAGE_SIZE = 45

    /**
     * [body] rewritten as the plain envelope when it is a
     * q-data document; [body] itself otherwise (and on any
     * failure). Called by the engine's document fetch and
     * its chapter-text ajax fetch — the two roads a q-data
     * payload can arrive on.
     */
    fun transformBody(body: String): String {
        val trimmed = body.removePrefix("\uFEFF").trimStart()
        if (!trimmed.startsWith("{")) return body
        val root = runCatching { json.parseToJsonElement(body) }
            .getOrNull() as? JsonObject ?: return body
        val objs = root["_objs"] as? JsonArray ?: return body
        if (objs.isEmpty()) return body
        return runCatching { buildEnvelope(root, objs) }
            .getOrNull() ?: body
    }

    private fun buildEnvelope(
        root: JsonObject,
        objs: JsonArray
    ): String {
        val resolver = Resolver(objs)
        val entryIndex = (root["_entry"] as? JsonPrimitive)
            ?.contentOrNull?.let { refTarget(it, objs.size) }
        // Encounter order: the entry tree first (its arrays
        // are the page's own order), then every remaining
        // entry — records the entry doesn't reach still
        // classify (rails, stubs, the first-chapter record).
        val forest = ArrayList<JsonElement>(objs.size + 1)
        if (entryIndex != null) forest += resolver.entry(entryIndex)
        for (index in objs.indices) forest += resolver.entry(index)

        val booksById = LinkedHashMap<String, JsonObject>()
        val chaptersById = LinkedHashMap<String, JsonObject>()
        val pagings = ArrayList<JsonObject>()
        var contentChapter: JsonObject? = null
        for (element in forest) {
            walk(element, 0) { dict ->
                val keys = dict.keys
                if ("N_title" in keys && "info_cover_name" in keys) {
                    val id = (dict["N_id"] as? JsonPrimitive)
                        ?.contentOrNull ?: ""
                    val previous = booksById[id]
                    if (previous == null || dict.size > previous.size) {
                        booksById[id] = dict
                    }
                }
                if ("ch_title" in keys && "ChPath" in keys) {
                    val id = (dict["ch_id"] as? JsonPrimitive)
                        ?.contentOrNull
                        ?: (dict["ChPath"] as? JsonPrimitive)
                            ?.contentOrNull
                        ?: ""
                    if (id !in chaptersById) chaptersById[id] = dict
                    if (contentChapter == null) {
                        val content = (dict["content"] as? JsonPrimitive)
                            ?.contentOrNull
                        if (!content.isNullOrBlank()) {
                            contentChapter = dict
                        }
                    }
                }
                if ("total" in keys && "pages" in keys && "size" in keys &&
                    dict !in pagings
                ) {
                    pagings += dict
                }
            }
        }

        val sortedChapters = chaptersById.values.sortedBy { chapter ->
            (chapter["ch_index"] as? JsonPrimitive)
                ?.contentOrNull?.toIntOrNull() ?: Int.MAX_VALUE
        }
        // v1.5.36 (F2): the graph also classifies one
        // "latest chapter" stub per rail/recommended book —
        // same host, same record shape, another book's
        // chapter, passing every admission rule downstream.
        // The envelope's chapter list is THIS page's book's
        // list: the primary book is the first book record
        // the entry tree classified (booksById's
        // first-inserted entry — the page's own book on
        // every payload family sampled), and the site's own
        // path grammar (/{bookId}-{slug}/…) names its
        // chapters. Keep only records under that prefix.
        // When no primary book can be identified, the
        // unfiltered emission stands, exactly as before —
        // the fallback is stated here, not silent.
        val primaryBookId = booksById.keys.firstOrNull()
        val chapters = if (primaryBookId.isNullOrEmpty()) {
            sortedChapters
        } else {
            val ownPrefix = "/$primaryBookId-"
            sortedChapters.filter { chapter ->
                (chapter["ChPath"] as? JsonPrimitive)
                    ?.contentOrNull?.startsWith(ownPrefix) == true
            }
        }
        // The details-grade book: the record carrying the
        // longest synopsis (listing stubs and related rails
        // carry shorter or none).
        val book = booksById.values.maxByOrNull { candidate ->
            (candidate["info_descText"] as? JsonPrimitive)
                ?.contentOrNull?.length ?: 0
        }
        val chapterPaging = pagings.firstOrNull { paging ->
            (paging["size"] as? JsonPrimitive)?.contentOrNull
                ?.toIntOrNull() == CHAPTER_PAGE_SIZE
        }
        val listingPaging = pagings.firstOrNull { it !== chapterPaging }
            ?: pagings.firstOrNull()
        val hasNext = when {
            // A book page's own paging is its chapter list's;
            // its book rails have no next page.
            chapterPaging != null && listingPaging === chapterPaging &&
                chapters.isNotEmpty() -> false
            listingPaging != null ->
                ((listingPaging["next"] as? JsonPrimitive)
                    ?.contentOrNull?.toIntOrNull() ?: 0) > 0
            else -> false
        }

        val envelope = buildJsonObject {
            put("books", JsonArray(booksById.values.toList()))
            if (book != null) {
                // The engine's details ladder reads a plain
                // "description" key; the site's name for the
                // same fact is info_descText — the alias is
                // the adapter's one renaming.
                val description = (book["info_descText"]
                    as? JsonPrimitive)?.contentOrNull
                val withAlias = if (!description.isNullOrBlank() &&
                    "description" !in book
                ) {
                    JsonObject(book + ("description" to
                        JsonPrimitive(description)))
                } else {
                    book
                }
                put("book", withAlias)
            }
            put("chapters", JsonArray(chapters))
            contentChapter?.let { chapter ->
                put("chapter", chapter)
                put(
                    "content",
                    (chapter["content"] as? JsonPrimitive)
                        ?: JsonPrimitive("")
                )
            }
            (chapterPaging ?: listingPaging)?.let { put("paging", it) }
            put(
                "pagination",
                buildJsonObject { put("has_next", JsonPrimitive(hasNext)) }
            )
        }
        return envelope.toString()
    }

    /** Bounded walk over one resolved element, visiting every
     * object it contains (the element itself included). */
    private fun walk(
        element: JsonElement,
        depth: Int,
        visit: (JsonObject) -> Unit
    ) {
        if (depth > MAX_WALK_DEPTH) return
        when (element) {
            is JsonObject -> {
                visit(element)
                for (value in element.values) walk(value, depth + 1, visit)
            }
            is JsonArray -> for (value in element) {
                walk(value, depth + 1, visit)
            }
            else -> Unit
        }
    }

    /**
     * The index a nested string points at, or null when the
     * string is a literal: a ref is non-empty, all base-36
     * digits (after stripping one trailing `!`), parses in
     * base 36, and lands inside the graph. The bounds check
     * is the safety net — a literal slug like "adult" parses
     * base-36 to an index far past the graph and stays a
     * literal.
     */
    private fun refTarget(raw: String, size: Int): Int? {
        val text = if (raw.endsWith("!")) raw.dropLast(1) else raw
        if (text.isEmpty()) return null
        for (c in text) {
            if (c !in '0'..'9' && c !in 'a'..'z') return null
        }
        val index = text.toIntOrNull(36) ?: return null
        return index.takeIf { it in 0 until size }
    }

    /** Memoised recursive resolution of one `_objs` graph:
     * each entry resolves once; a ref cycle hands back the
     * raw entry instead of recursing forever. */
    private class Resolver(private val objs: JsonArray) {
        private val memo = HashMap<Int, JsonElement>()
        private val active = HashSet<Int>()

        fun entry(index: Int): JsonElement {
            memo[index]?.let { return it }
            if (!active.add(index)) return objs[index]
            val resolved = value(objs[index], 0, top = true)
            active.remove(index)
            memo[index] = resolved
            return resolved
        }

        private fun value(
            element: JsonElement,
            depth: Int,
            top: Boolean
        ): JsonElement {
            if (depth > MAX_RESOLVE_DEPTH) return element
            return when (element) {
                is JsonObject -> JsonObject(
                    element.mapValues { (_, child) ->
                        value(child, depth + 1, top = false)
                    }
                )
                is JsonArray -> JsonArray(
                    element.map { child ->
                        value(child, depth + 1, top = false)
                    }
                )
                is JsonPrimitive -> {
                    if (!top && element.isString) {
                        refTarget(element.content, objs.size)
                            ?.let { entry(it) } ?: element
                    } else {
                        element
                    }
                }
                else -> element
            }
        }
    }
}
