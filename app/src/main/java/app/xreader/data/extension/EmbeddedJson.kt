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
     * v1.5.3 (finding #2): the Inertia.js payload — Laravel apps
     * (brightnovels.com is the proven case) server-render their
     * whole page model as JSON inside the `data-page` attribute
     * of the page's `div#app` element, while the DOM carries no
     * catalogue anchors at all. The attribute value is the page
     * object ({component, props, url, version}), entity-decoded
     * by the HTML parser and, on some stacks, URL-encoded on top
     * of that. Returns the parsed object, or null when the page
     * carries none (or it doesn't parse) — never throws.
     *
     * v1.5.4 (item 12): the extraction is a four-stage ladder,
     * first success wins, each stage's output must parse as a
     * JSON object or the ladder falls through — mirroring the
     * maintained BrightNovels extension's own extraction
     * order:
     *  1. the `div#app[data-page]` attribute value, raw;
     *  2. the attribute value URL-decoded;
     *  3. every `data-page` value a regex finds in the
     *     document's HTML (the attribute may ride on another
     *     carrier, or survive only as a stashed copy the
     *     parsed-DOM attribute read can't reach);
     *  4. each regex value retried through JSON-string
     *     unescape, for stacks that embed the payload as an
     *     escaped string (\" and \/ sequences).
     */
    fun inertiaData(doc: Document): JsonObject? {
        val raw = runCatching {
            doc.selectFirst("div#app[data-page]")?.attr("data-page")
        }.getOrNull()?.takeIf { it.isNotBlank() }
        if (raw != null) {
            // Stage 1: the attribute value as parsed.
            runCatching { json.parseToJsonElement(raw) as? JsonObject }
                .getOrNull()?.let { return it }
            // Stage 2: URL-decoded.
            val decoded = runCatching {
                java.net.URLDecoder.decode(raw, Charsets.UTF_8.name())
            }.getOrNull()
            if (decoded != null) {
                runCatching {
                    json.parseToJsonElement(decoded) as? JsonObject
                }.getOrNull()?.let { return it }
            }
        }
        // Stages 3–4: the raw-body regex road.
        val candidates = dataPageCandidates(doc)
        for (candidate in candidates) {
            runCatching {
                json.parseToJsonElement(candidate) as? JsonObject
            }.getOrNull()?.let { return it }
        }
        for (candidate in candidates) {
            val unescaped = jsonStringUnescape(candidate) ?: continue
            runCatching {
                json.parseToJsonElement(unescaped) as? JsonObject
            }.getOrNull()?.let { return it }
        }
        return null
    }

    private val DATA_PAGE_DOUBLE_QUOTED = Regex(
        "data-page\\s*=\\s*\"([^\"]*)\"",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    private val DATA_PAGE_SINGLE_QUOTED = Regex(
        "data-page\\s*=\\s*'([^']*)'",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    /**
     * Every `data-page` attribute value the document's HTML
     * carries, in the maintained extension's candidate order —
     * all double-quoted matches in document order, then all
     * single-quoted ones — blanks dropped, duplicates
     * collapsed. The scan text is the document's serialised
     * HTML: the closest thing to the raw body the engine
     * still holds once a page has been parsed (carriers the
     * DOM preserves verbatim — comments, raw-text elements,
     * template contents — are exactly the ones the attribute
     * read can't reach).
     */
    private fun dataPageCandidates(doc: Document): List<String> {
        val html = runCatching { doc.outerHtml() }.getOrNull()
            ?: return emptyList()
        val found = LinkedHashSet<String>()
        for (match in DATA_PAGE_DOUBLE_QUOTED.findAll(html)) {
            match.groupValues[1].takeIf { it.isNotBlank() }
                ?.let { found += it }
        }
        for (match in DATA_PAGE_SINGLE_QUOTED.findAll(html)) {
            match.groupValues[1].takeIf { it.isNotBlank() }
                ?.let { found += it }
        }
        return found.toList()
    }

    /**
     * The JSON-string unescape of [value]: the value read as
     * the CONTENTS of a JSON string literal, so `\"` becomes
     * `"` and `\/` becomes `/` — the escapes the donor's
     * decoder handles. Null when the value can't be a string
     * body at all (an unescaped quote or control character
     * ends the attempt; the ladder then gives up cleanly).
     */
    private fun jsonStringUnescape(value: String): String? =
        runCatching {
            (json.parseToJsonElement("\"$value\"") as? JsonPrimitive)
                ?.contentOrNull
        }.getOrNull()

    /**
     * Every embedded-JSON root the document carries, in the
     * order the engine should prefer them: the `__NEXT_DATA__`
     * object first, then the Inertia `data-page` object, then
     * each parseable React flight row from the RSC stream.
     */
    fun roots(doc: Document): List<JsonElement> {
        val out = mutableListOf<JsonElement>()
        nextData(doc)?.let { out += it }
        inertiaData(doc)?.let { out += it }
        out += rscRoots(doc)
        return out
    }

    /**
     * v1.3.0 (E1): when the document's body IS a JSON payload —
     * the page was a bare API response (jsoup parks the payload
     * text in the body, verbatim inside a `<pre>` or as plain
     * body text) — that payload parsed as a root, else null.
     * This is what lets the very same dot-path reads serve
     * direct JSON endpoints (an API catalogue, an API chapter
     * list) and embedded page payloads with one code path.
     */
    fun bodyJson(doc: Document): JsonElement? {
        val text = runCatching {
            doc.selectFirst("body > pre")?.wholeText()
                ?: doc.body()?.wholeText()
        }.getOrNull()?.trim()?.takeIf {
            it.startsWith("{") || it.startsWith("[")
        } ?: return null
        return runCatching { json.parseToJsonElement(text) }.getOrNull()
    }

    /**
     * Every JSON root a spec may read, in preference order: a
     * bare-JSON body FIRST (a page that is pure JSON is an API
     * answer — its payload is the data, not a container for
     * it), then the embedded roots ([roots]).
     *
     * v1.5.4 (finding #3 close-out, item 11): each root is
     * followed by its wrapped-envelope forms. A payload may
     * wrap the page object one level down — `{"page": {…}}`
     * or `{"data": {…}}`, the two wrappings the maintained
     * BrightNovels extension reads props through
     * (`root.page.props`, `root.data.props`) — and a declared
     * path written against the bare page object
     * (`props.seriesList.data`) must still resolve. So the
     * wrapper object AND its props object join the root set
     * immediately after their bare root: the declared path
     * resolves against the wrapper unchanged, the props
     * object gives the envelope scan its descent, and every
     * read that consumes this root set (listings, chapter
     * rows, chapter content, the shape-miss check) gains the
     * tolerance at family level — every embedded-JSON
     * source, never one source by name. Deterministic
     * precedence: bare root first, then `page`, then `data`
     * — the order the extension itself tries them. Discovery
     * ([roots]) is untouched: inference still names paths
     * against the bare roots it observed.
     */
    fun rootsForRead(doc: Document): List<JsonElement> {
        val body = bodyJson(doc)
        val base =
            if (body != null) listOf(body) + roots(doc) else roots(doc)
        val out = mutableListOf<JsonElement>()
        for (root in base) {
            out += root
            val obj = root as? JsonObject ?: continue
            for (wrapperKey in listOf("page", "data")) {
                val wrapper = obj[wrapperKey] as? JsonObject
                    ?: continue
                out += wrapper
                (wrapper["props"] as? JsonObject)?.let { out += it }
            }
        }
        return out
    }

    /**
     * v1.5.4 (item 14): the key NAMES a document's embedded
     * payloads carry — every root's top-level keys and every
     * props object's keys (the bare root's own `props`, and
     * the `page`/`data` wrappers'), in first-seen order,
     * each set capped so a payload can't flood a log line.
     * NAMES ONLY, by construction: this string is built from
     * key sets alone, so no value, URL or body text can ever
     * enter it. It exists for the shape-miss log record (see
     * DeclarativeSource.listingShapeMissed) — the dividend
     * that turns the NEXT payload drift from a guess into a
     * fact: the key the definition should have named is
     * right there in the owner's own problem report.
     */
    fun keyNamesSummary(doc: Document): String {
        val top = LinkedHashSet<String>()
        val props = LinkedHashSet<String>()
        val rawRoots = listOfNotNull(bodyJson(doc)) + roots(doc)
        for (root in rawRoots) {
            val obj = root as? JsonObject ?: continue
            top += obj.keys
            val holders = listOfNotNull(
                obj,
                obj["page"] as? JsonObject,
                obj["data"] as? JsonObject
            )
            for (holder in holders) {
                (holder["props"] as? JsonObject)?.let {
                    props += it.keys
                }
            }
        }
        fun render(label: String, names: LinkedHashSet<String>): String =
            if (names.isEmpty()) {
                "$label: (none)"
            } else {
                "$label: " + names.take(12).joinToString(", ") +
                    if (names.size > 12) " …" else ""
            }
        return render("top", top) + " · " + render("props", props)
    }

    /**
     * v1.5.5 (§2): the [keyNamesSummary] discipline for a
     * payload already parsed as JSON — the chapters endpoint's
     * answers never become a Document, but a zero-row first
     * page owes diagnostics the same names-only key summary:
     * the root object's top-level keys plus any `props`
     * object's keys (the root's own, or a `page`/`data`
     * wrapper's), capped exactly as above. NAMES ONLY, by
     * construction.
     */
    fun keyNamesSummary(root: JsonElement): String {
        val top = LinkedHashSet<String>()
        val props = LinkedHashSet<String>()
        val obj = root as? JsonObject
        if (obj != null) {
            top += obj.keys
            val holders = listOfNotNull(
                obj,
                obj["page"] as? JsonObject,
                obj["data"] as? JsonObject
            )
            for (holder in holders) {
                (holder["props"] as? JsonObject)?.let {
                    props += it.keys
                }
            }
        }
        fun render(label: String, names: LinkedHashSet<String>): String =
            if (names.isEmpty()) {
                "$label: (none)"
            } else {
                "$label: " + names.take(12).joinToString(", ") +
                    if (names.size > 12) " …" else ""
            }
        val rootKind = when (root) {
            is JsonObject -> "object"
            is kotlinx.serialization.json.JsonArray -> "array"
            else -> "scalar"
        }
        return render("top", top) + " · " + render("props", props) +
            " · root: $rootKind"
    }

    // ── v1.3.0 (E3): API discovery seed ─────────────────────
    //
    // Four of the "hard" sites of the v1.2.3 round (novelbuddy,
    // chikari, noveli, lncrawler) all turned out to run plain
    // same-origin JSON APIs the probe never looked for — the
    // probe judged their HTML shells and stopped. These hints
    // are the seed of the API-discovery road: cheap, pure, and
    // reported on the probe's stage lines whenever a probe
    // fails, so a failing site's report now SAYS whether an
    // API road exists (the lesson of novelarchive, whose whole
    // site is /api/novels/<hash> + /chapters/<n> and whose
    // probe had no API step at all).

    /** Same-origin `/api/…` endpoint paths a page references —
     * in its script sources, its links, or its inline script
     * text (fetch/axios calls live there). Query strings are
     * dropped; duplicates collapse; order is first-seen. */
    fun apiEndpointHints(doc: Document, pageUrl: String): List<String> {
        val found = LinkedHashSet<String>()
        fun offer(raw: String?) {
            val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return
            val path = value.substringBefore('?').substringBefore('#')
            if (path.contains("/api/") && path.length <= 200) {
                found += path
            }
        }
        for (element in doc.select("script[src], link[href], a[href]")) {
            offer(
                element.absUrl("src").ifBlank { null }
                    ?: element.absUrl("href").ifBlank { null }
                    ?: element.attr("src").ifBlank { element.attr("href") }
            )
        }
        val inlineApi = Regex("""["'(/][\w./{}-]*?/api/[\w./{}-]*""")
        for (script in doc.select("script:not([src])")) {
            for (match in inlineApi.findAll(script.data())) {
                offer(match.value.trim('"', '\'', '(', ')'))
                if (found.size >= 12) break
            }
            if (found.size >= 12) break
        }
        return found.take(12)
    }

    /** One listing-shaped array found in a page's payloads:
     * the root it lives in, its dot path within that root
     * ("" = the root IS the array), and its record count. */
    data class EmbeddedListing(
        val root: JsonElement,
        val path: String,
        val size: Int
    )

    /**
     * The largest book-like record array in the page's
     * embedded payloads (records carrying a title-ish and a
     * link/id-ish field), or null when the payloads hold no
     * listing-shaped array — the "this page's data is JSON"
     * signal for the discovery seed.
     */
    fun embeddedListing(doc: Document): EmbeddedListing? {
        var best: EmbeddedListing? = null
        for (root in roots(doc)) {
            scanForListing(root, root, "", 0) { candidate ->
                if (best == null || candidate.size > best!!.size) {
                    best = candidate
                }
            }
        }
        return best
    }

    private fun scanForListing(
        root: JsonElement,
        element: JsonElement,
        path: String,
        depth: Int,
        offer: (EmbeddedListing) -> Unit
    ) {
        if (depth > 5) return
        when (element) {
            is JsonArray -> {
                val records = element.filterIsInstance<JsonObject>()
                if (records.size >= 3 &&
                    records.count { record ->
                        record.keys.any {
                            it.equals("title", true) ||
                                it.equals("name", true)
                        } && record.keys.any {
                            it.equals("url", true) ||
                                it.equals("slug", true) ||
                                it.equals("id", true) ||
                                it.equals("link", true) ||
                                it.equals("href", true)
                        }
                    } * 2 > records.size
                ) {
                    offer(EmbeddedListing(root, path, records.size))
                }
            }
            is JsonObject -> element.entries.forEach { (key, value) ->
                scanForListing(
                    root, value,
                    if (path.isEmpty()) key else "$path.$key",
                    depth + 1, offer
                )
            }
            else -> Unit
        }
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

    /**
     * v1.5.4 (item 13): the segment alias table — the ONLY
     * aliases [resolve] may substitute, tried in order when
     * the declared segment is ABSENT on the object being
     * read. Both entries are the BrightNovels wire's own
     * drift shapes, read off the maintained extension (its
     * listing paginator is `seriesList` OR `series`), and
     * they apply at family level: any embedded-JSON source
     * whose payload drifts the same way reads on. No other
     * aliases exist — a payload shape not named here is a
     * shape-miss, not a guessing game.
     *
     * The cover entry is deliberately NOT in this map: it is
     * parent-guarded in [aliasesFor], because `url` is far
     * too common a field name to alias anywhere but under a
     * `cover` parent.
     */
    private val SEGMENT_ALIASES: Map<String, List<String>> = mapOf(
        "seriesList" to listOf("series")
    )

    /** The aliases for an absent [segment], where [parent]
     * is the segment that produced the object being read
     * (null at the path's first hop). `url` aliases to
     * `thumbnail_url` ONLY under a `cover` parent — the
     * extension's cover read (`cover.url` else
     * `cover.thumbnail_url`); every other parent gets none. */
    private fun aliasesFor(
        segment: String,
        parent: String?
    ): List<String> {
        if (segment == "url") {
            return if (parent == "cover") {
                listOf("thumbnail_url")
            } else {
                emptyList()
            }
        }
        return SEGMENT_ALIASES[segment] ?: emptyList()
    }

    /** Resolve a dot path (`props.pageProps.items`) over a
     * parsed payload; numeric segments index into arrays.
     * Null when any hop is absent or the wrong shape.
     *
     * v1.5.4 (item 13): when a segment is ABSENT on the
     * current object (a present key — even a null one —
     * always wins, exactly as before), the resolver tries
     * the segment's declared aliases ([aliasesFor]) in
     * order before giving up; the walk then continues from
     * the aliased node with the remaining segments. No
     * backtracking: an alias that exists but whose subtree
     * misses ends the resolution, like any other miss. */
    fun resolve(root: JsonElement, path: String): JsonElement? {
        if (path.isBlank()) return root
        var current: JsonElement = root
        var parent: String? = null
        for (segment in path.split('.')) {
            current = when (current) {
                is JsonObject -> {
                    val direct = current[segment]
                    if (direct != null) {
                        direct
                    } else {
                        var aliased: JsonElement? = null
                        for (alias in aliasesFor(segment, parent)) {
                            val candidate = current[alias]
                            if (candidate != null) {
                                aliased = candidate
                                break
                            }
                        }
                        aliased ?: return null
                    }
                }
                is JsonArray -> {
                    val index = segment.toIntOrNull() ?: return null
                    current.getOrNull(index) ?: return null
                }
                else -> return null
            }
            parent = segment
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

    /** The envelope keys list-shaped payloads travel under,
     * in the order the scan prefers them. */
    val ENVELOPE_KEYS = listOf(
        "items", "data", "results", "chapters", "list", "rows",
        "novels", "books"
    )

    /**
     * v1.5.0 (steer A2): the polymorphic envelope unwrapper —
     * the ONE place that finds the record array inside a JSON
     * payload whose exact envelope wasn't (or no longer is)
     * the declared one. Accepts a bare array, an object
     * holding an array under any [ENVELOPE_KEYS] field, or a
     * wrapper object (`data: { items: … }`) one level deep.
     * Deterministic key order; returns the first non-empty
     * array found, else null. Callers use it ONLY when the
     * declared path missed — a declared path that resolves
     * always wins, so a working recipe never changes shape
     * under this fallback.
     */
    fun scanForArray(root: JsonElement): JsonArray? {
        if (root is JsonArray) {
            return root.takeIf { it.isNotEmpty() }
        }
        val obj = root as? JsonObject ?: return null
        for (key in ENVELOPE_KEYS) {
            val value = obj[key] ?: continue
            if (value is JsonArray && value.isNotEmpty()) return value
            if (value is JsonObject) {
                for (innerKey in ENVELOPE_KEYS) {
                    val inner = value[innerKey]
                    if (inner is JsonArray && inner.isNotEmpty()) {
                        return inner
                    }
                }
            }
        }
        return null
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
                // v1.2.4: a listing whose links mostly point
                // into a people collection (/users/<id>-<name>)
                // is a member rail wearing a catalogue's shape —
                // the payload twin of the DOM people rail a Live
                // catalogue swallowed on device (novelbuddy).
                val peopleish = records.count { record ->
                    val link = stringAt(record, linkField) ?: ""
                    BookShape.isPeopleCollectionHref(link)
                }
                if (peopleish * 2 > records.size) return@walk
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
