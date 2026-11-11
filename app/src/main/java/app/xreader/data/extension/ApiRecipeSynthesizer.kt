package app.xreader.data.extension

import java.net.URI
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * v1.5.0 (A2/A3): turn the traffic a site's own pages fired
 * while Xreader's browser view rendered them ([HarvestLog])
 * into an API-first [DeclarativeConfig] — the learner's API
 * road. Page scraping stays the fallback: this synthesizer
 * only proposes, the probe's ordinary test-drive disposes
 * (all five legs over the API road before anything saves).
 *
 * The shapes recognised are the ones real API-driven novel
 * sites actually use — the same shapes the hand-built
 * Chikari / NovelArchive / Noveli lanes encode:
 *
 *  - a JSON listing call (bare array, or an object wrapping
 *    one) of book records with a title and a slug/id field
 *    → the catalogue + search specs;
 *  - a JSON book-record call whose URL carries the book's
 *    slug → book ids ARE that record's URL (details ride
 *    the catalogue row, exactly like the proven lanes);
 *  - a JSON chapter-list call under the book's slug →
 *    offset/page pagination over its array;
 *  - a JSON chapter-read call carrying a chapter key →
 *    the AJAX text mode, its template rebuilt from the
 *    chapter URL's own segments;
 *  - a request header on an API host whose value equals a
 *    page cookie's value → the cookie→header credential
 *    binding (BOTI's `userToken` shape: the site replays
 *    its own JS-set session cookie as a header to its API
 *    host; the binding is declared, never the value).
 *
 * Anything the traffic doesn't prove, the synthesizer
 * doesn't invent: no recognised chapter-list call, no
 * recipe. Pure and deterministic — the gate feeds it
 * synthetic traffic in the BOTI and Chikari shapes.
 */
object ApiRecipeSynthesizer {

    private val json = Json { ignoreUnknownKeys = true }

    private val TITLE_FIELDS = listOf("title", "name")
    private val LINK_FIELDS = listOf("slug", "id", "url", "href")
    private val COVER_FIELDS =
        listOf(
            "cover", "cover_url", "coverUrl", "coverImgUrl",
            "image", "thumbnail", "poster"
        )
    private val AUTHOR_FIELDS =
        listOf("author", "author_name", "authorPseudonym")
    private val CHAPTER_NAME_FIELDS = listOf("title", "name")
    private val CHAPTER_LINK_FIELDS = listOf("url", "slug", "id", "number")
    private val TEXT_FIELDS =
        listOf("content", "body", "text", "html", "content_html", "chapter_content")
    private val ARRAY_FIELDS =
        listOf("data", "items", "results", "novels", "books", "list", "chapters")
    private val SEARCH_PARAMS = listOf("q", "query", "search", "keyword", "title")

    /** v1.5.2 fix (FIX-1c): page params in the wild — the
     * story-wave family pages with `pageNumber`, others with
     * `page_no` / `p`; the value is what gets templated. */
    private val PAGE_PARAMS = listOf("page", "pagenumber", "page_no", "p")

    /** The query key of [params] that carries the search term,
     * matched case-insensitively (story-wave: `keyWord`). */
    private fun searchParamKey(params: Map<String, String>): String? =
        params.keys.firstOrNull { it.lowercase() in SEARCH_PARAMS }

    /** The query key of [params] that carries the page number. */
    private fun pageParamKey(params: Map<String, String>): String? =
        params.keys.firstOrNull { it.lowercase() in PAGE_PARAMS }
    /** Stated-total field names, in preference order: row
     * totals before page totals (the walk stops when the
     * gathered rows reach the total, so a page count would
     * over-walk). Matched case-insensitively (story-wave:
     * `totalCount`). */
    private val TOTAL_FIELDS = listOf(
        "total", "totalCount", "total_count", "count",
        "totalPages", "total_pages"
    )

    /** v1.5.2 (§10 item 1): headers a browser/transport owns —
     * never replay-header material, however constant they are
     * across a harvest (see the replay pass in [synthesize]). */
    private val REPLAY_EXCLUDED = setOf(
        "host", "connection", "content-length", "content-type",
        "user-agent", "accept", "accept-encoding",
        "accept-language", "origin", "referer", "cookie",
        "authorization"
    )

    /** v1.5.3 (round-16 amendment A): temporal header NAMES —
     * a value under one of these names names a moment, not a
     * constant, however identical it looks across the calls
     * of a fast harvest burst. Replaying it stale earns 403s
     * on strict APIs, so a temporal header NEVER promotes to
     * a static replay header. Suffix forms (`*-timestamp`,
     * `*-time`) are caught in [isTemporalReplayHeader]. */
    private val REPLAY_TEMPORAL_NAMES = setOf(
        "date", "timestamp", "x-timestamp",
        "x-client-timestamp", "x-request-time",
        "x-request-timestamp", "x-time"
    )

    /** v1.5.3 (round-16 amendment A): timestamp-SHAPED values
     * — epoch seconds / millis (optionally fractional),
     * ISO-8601 dates and datetimes, RFC-1123 dates. A header
     * carrying one of these is temporal whatever its name. */
    private val EPOCH_VALUE = Regex("^\\d{9,16}(\\.\\d+)?$")
    private val ISO_VALUE = Regex(
        "^\\d{4}-\\d{2}-\\d{2}" +
            "([Tt ]\\d{2}:\\d{2}(:\\d{2}(\\.\\d+)?)?" +
            "([Zz]|[+-]\\d{2}:?\\d{2})?)?$"
    )
    private val RFC1123_VALUE = Regex(
        "^[A-Za-z]{3}, \\d{2} [A-Za-z]{3} \\d{4} " +
            "\\d{2}:\\d{2}:\\d{2}.*$"
    )

    /** True when [name] is a known temporal header name or
     * [value] is timestamp-shaped — either makes the header
     * a moment, never replay material. */
    private fun isTemporalReplayHeader(
        name: String,
        value: String
    ): Boolean {
        val lower = name.lowercase()
        if (lower in REPLAY_TEMPORAL_NAMES ||
            lower.endsWith("-timestamp") ||
            lower.endsWith("-time")
        ) {
            return true
        }
        val v = value.trim()
        return EPOCH_VALUE.matches(v) ||
            ISO_VALUE.matches(v) ||
            RFC1123_VALUE.matches(v)
    }

    /** Steer A1: the book-list bar (see the listing pass). */
    private const val MIN_LIST_ITEMS = 3

    /** A synthesised recipe plus the extra hosts it needs
     * (the API hosts — the installer turns them into the
     * source's declared domains). */
    data class Synthesis(
        val config: DeclarativeConfig,
        val apiHosts: List<String>
    )

    fun synthesize(
        calls: List<HarvestedCall>,
        webBaseUrl: String,
        siteName: String,
        bookUrlHint: String?
    ): Synthesis? {
        val parsed = calls.mapNotNull { call ->
            if (!call.isJson || call.status !in 200..299) return@mapNotNull null
            val payload = parseLenient(call.bodySample)
                ?: return@mapNotNull null
            ParsedCall(call, payload)
        }
        if (parsed.isEmpty()) return null

        // ── the listing calls ──────────────────────────────
        // v1.5.0 (steer A1): the book-list bar — a candidate
        // array counts as a book list only when at least
        // MIN_LIST_ITEMS of its records carry BOTH a
        // title-like AND a slug/URL-like field. One- or
        // two-item JSON (analytics beacons, telemetry,
        // config blobs) can wear a title-shaped field; a
        // page of real book records is what a catalogue or
        // search answer looks like.
        val listings = parsed.mapNotNull { pc ->
            // v1.5.2 (§10 item 1): the first candidate array
            // that clears the book-list bar wins — a payload
            // can lead with a non-book array (a homepage's
            // module list) ahead of the book shelf inside it.
            arraysOf(pc.payload).firstNotNullOfOrNull { array ->
                val first = array.items.firstOrNull() as? JsonObject
                    ?: return@firstNotNullOfOrNull null
                val titleField = firstField(first, TITLE_FIELDS)
                val linkField = firstField(first, LINK_FIELDS)
                if (titleField == null || linkField == null) {
                    return@firstNotNullOfOrNull null
                }
                val qualifying = array.items.count { item ->
                    val record = item as? JsonObject
                        ?: return@count false
                    !stringField(record, titleField)
                        .isNullOrBlank() &&
                        !stringField(record, linkField)
                            .isNullOrBlank()
                }
                if (qualifying < MIN_LIST_ITEMS) {
                    return@firstNotNullOfOrNull null
                }
                Listing(pc, array, titleField, linkField)
            }
        }
        if (listings.isEmpty()) return null
        val searchListing = listings.firstOrNull { listing ->
            searchParamKey(queryParams(listing.call.url)) != null
        }
        // v1.5.2 fix (FIX-1c): a CHAPTER-LIST call qualifies as a
        // "listing" too (its records carry title + id) — and not
        // being a search call, it used to WIN the catalogue slot,
        // after which every "book slug" was a chapter id and the
        // whole synthesis died. A listing is chapter-shaped when
        // its path names chapters, or when every record shares
        // one parent-book field value (a catalogue's records
        // belong to many books; a chapter list's to exactly one).
        fun looksLikeChapterList(listing: Listing): Boolean {
            if (pathSegments(listing.call.url).any {
                    it.contains("chapter", ignoreCase = true)
                }
            ) {
                return true
            }
            val records = listing.array.items
                .mapNotNull { it as? JsonObject }
            if (records.size < 2) return false
            val parentFields = records.first().keys.filter { key ->
                key.equals("bookId", true) ||
                    key.equals("book_id", true) ||
                    key.equals("parentId", true) ||
                    key.equals("novelId", true) ||
                    key.equals("seriesId", true)
            }
            return parentFields.any { field ->
                val first = stringField(records.first(), field)
                first != null && records.all {
                    stringField(it, field) == first
                }
            }
        }
        val catalogueListing = listings.firstOrNull { listing ->
            searchParamKey(queryParams(listing.call.url)) == null &&
                !looksLikeChapterList(listing)
        } ?: searchListing?.takeIf { !looksLikeChapterList(it) }
            ?: listings.firstOrNull { !looksLikeChapterList(it) }
            ?: return null
        val listing = catalogueListing
        // v1.5.2 fix (FIX-1c): the legs below key on ONE book's
        // slug — but the listing's first record may be a book the
        // recorded traffic never opened (no chapter-list call
        // carries its id), which used to kill the whole synthesis
        // even though the road was fully recorded for another
        // book. The assembly now runs per candidate slug (the
        // listing's first few records) and the first book whose
        // legs ALL assemble wins.
        // v1.5.2 (§10 item 1): candidate books come from the
        // catalogue first, then the search listing — the book
        // whose legs were fully recorded may have surfaced
        // through search rather than a homepage shelf
        // (story-wave: the opened book is a search hit, absent
        // from every module shelf).
        val slugCandidates = (
            listing.array.items.mapNotNull { item ->
                (item as? JsonObject)?.let { record ->
                    stringField(record, listing.linkField)
                }
            }.filter { it.isNotBlank() }.take(5) + (
                searchListing?.takeIf { it !== listing }?.let { sl ->
                    sl.array.items.mapNotNull { item ->
                        (item as? JsonObject)?.let { record ->
                            stringField(record, sl.linkField)
                        }
                    }
                } ?: emptyList()
                ).filter { it.isNotBlank() }.take(5)
            )
            .distinct()
        if (slugCandidates.isEmpty()) return null

        fun assemble(slugValue: String): Synthesis? {
        // ── the book-record call ───────────────────────────
        val bookCall = parsed.firstOrNull { pc ->
            pc.payload is JsonObject &&
                pathSegments(pc.call.url).contains(slugValue)
        }
        // Book ids are the record call's URL when one was
        // seen; otherwise the probed book page's own URL
        // pattern (its slug segment templated).
        val bookLinkTemplate = when {
            bookCall != null ->
                replaceSegment(bookCall.call.url, slugValue, "{value}")
            bookUrlHint != null &&
                pathSegments(bookUrlHint).contains(slugValue) ->
                replaceSegment(bookUrlHint, slugValue, "{value}")
            else -> null
        } ?: return null

        // ── the chapter-list call ──────────────────────────
        val chapters = parsed.mapNotNull { pc ->
            if (pc.call.url == bookCall?.call?.url) return@mapNotNull null
            // v1.5.2 fix (FIX-1c): the book key may ride the
            // QUERY (story-wave: chapters/page?bookId=<id>),
            // not only a path segment.
            if (!pathSegments(pc.call.url).contains(slugValue) &&
                slugValue !in queryParams(pc.call.url).values
            ) {
                return@mapNotNull null
            }
            val array = arrayOf(pc.payload) ?: return@mapNotNull null
            val first = array.items.firstOrNull() as? JsonObject
                ?: return@mapNotNull null
            val nameField = firstField(first, CHAPTER_NAME_FIELDS)
                ?: return@mapNotNull null
            val linkField = firstField(first, CHAPTER_LINK_FIELDS)
                ?: return@mapNotNull null
            ChapterList(pc, array, nameField, linkField)
        }.firstOrNull() ?: return null
        val firstChapter = chapters.array.items.firstOrNull() as? JsonObject
            ?: return null
        val chapterKey = stringField(firstChapter, chapters.linkField)
            ?.takeIf { it.isNotBlank() } ?: return null

        // ── the chapter-read call ──────────────────────────
        // v1.5.2 fix (FIX-1c): the prose field may sit at the
        // payload root OR inside the standard envelope object
        // (`data`) — the story-wave family wraps every answer.
        // textFieldAt returns (field, dotPath-or-null): a null
        // path means the field is at the root.
        fun textFieldAt(obj: JsonObject): Pair<String, String?>? {
            for (field in TEXT_FIELDS) {
                val len = (obj[field] as? JsonPrimitive)
                    ?.contentOrNull?.length ?: 0
                if (len >= 200) return field to null
            }
            val envelope = obj["data"] as? JsonObject ?: return null
            for (field in TEXT_FIELDS) {
                val len = (envelope[field] as? JsonPrimitive)
                    ?.contentOrNull?.length ?: 0
                if (len >= 200) return field to "data.$field"
            }
            return null
        }
        val textCall = parsed.firstOrNull { pc ->
            if (!pathSegments(pc.call.url).contains(chapterKey)) {
                return@firstOrNull false
            }
            val obj = pc.payload as? JsonObject ?: return@firstOrNull false
            textFieldAt(obj) != null
        }
        val textFieldAndPath = textCall?.let { pc ->
            textFieldAt(pc.payload as JsonObject)
        }
        val textField = textFieldAndPath?.first
        val textPath = textFieldAndPath?.second

        // Chapter ids ride the read call's own URL pattern
        // (API host included — chapter rows are same-site
        // checked against the book record's URL, so a
        // cross-host API keeps every leg on the API host);
        // without a read call they ride the chapter-list
        // call's pattern plus the key.
        val chapterLinkTemplate = if (textCall != null) {
            val segs = pathSegments(textCall.call.url)
            val keyIndex = segs.indexOf(chapterKey)
            val prefix = segs.take(keyIndex + 1).joinToString("/")
            (originOf(textCall.call.url) + "/" + prefix)
                .replace(slugValue, "{bookSlug}")
                .replace(chapterKey, "{value}")
        } else {
            chapters.call.url.substringBefore("?").trimEnd('/') + "/{value}"
                .replace(slugValue, "{bookSlug}")
        }

        val ajax = if (textCall != null && textField != null) {
            val urlSegs = pathSegments(textCall.call.url)
            // {seg-N} counts back from the end of the
            // CHAPTER ID (the chapter-list link template's
            // URL), whose path ends AT the chapter key — the
            // read call may carry trailing action segments
            // (…/read) past it. A value's position from that
            // end is (keyIndex + 1 - its own index): the key
            // itself is seg-1, the segment before it seg-2,
            // and so on. (Counting from the read URL's own
            // end overcounts by the trailing segments and
            // produced templates that resolved to the wrong
            // segments — caught by the V150 drive test.)
            val keyIndex = urlSegs.indexOf(chapterKey)
            val fromEnd = { value: String ->
                keyIndex + 1 - urlSegs.indexOf(value)
            }
            val template = textCall.call.url.substringBefore("?")
                .replace(slugValue, "{seg-${fromEnd(slugValue)}}")
                .replace(chapterKey, "{seg-${fromEnd(chapterKey)}}")
            val obj = textCall.payload as JsonObject
            val content = if (textPath != null) {
                (EmbeddedJson.resolve(obj, textPath) as? JsonPrimitive)
                    ?.contentOrNull.orEmpty()
            } else {
                (obj[textField] as? JsonPrimitive)
                    ?.contentOrNull.orEmpty()
            }
            ChapterTextAjax(
                urlTemplate = template,
                jsonContentField = textField,
                jsonContentPath = textPath,
                plainTextField = !content.contains('<'),
                jsonLockField = (obj["locked"] as? JsonPrimitive)
                    ?.let { if (!it.isString) "locked" else null }
            )
        } else {
            null
        }

        // ── the credential binding (BOTI's shape) ─────────
        val webHost = CookieParsing.hostOf(webBaseUrl)
        val apiHosts = parsed.mapNotNull { it.call.host }
            .filter { it != webHost }
            .distinct()
        val cookies = calls.flatMap { it.pageCookies.entries }
            .associate { it.key to it.value }
        var binding: HeaderBinding? = null
        for (pc in parsed) {
            val host = pc.call.host ?: continue
            if (host == webHost) continue
            for ((header, value) in pc.call.requestHeaders) {
                val cookie = cookies.entries.firstOrNull { it.value == value }
                if (cookie != null) {
                    binding = HeaderBinding(
                        cookieName = cookie.key,
                        headerName = header,
                        hosts = listOf(host)
                    )
                    break
                }
            }
            if (binding != null) break
        }

        // ── the replay headers (BOTI's interceptor shape) ──
        // v1.5.2 (§10 item 1): headers the site's own client
        // puts on EVERY harvested call to its API hosts, with
        // one constant value, are part of the road — the API
        // answers a different road without them (story-wave's
        // interceptor sends lang + site-domain on every call).
        // Browser-owned and per-request headers never qualify;
        // a header whose value equals a cookie's belongs to
        // the binding above, not here; Authorization is never
        // persisted from a harvest (the binding owns it when
        // it is cookie-derived, and a static credential must
        // never be baked into a saved source).
        val apiCalls = parsed.filter { pc ->
            val h = pc.call.host
            h != null && h != webHost
        }
        // v1.5.3 (round-16 amendment B): the scheme the API
        // road was harvested over, when every API call agrees
        // on one — pinned into each promoted binding so the
        // header is never replayed onto another scheme of the
        // same host. A mixed-scheme harvest pins nothing
        // (host-only, as before).
        val harvestScheme = apiCalls
            .mapNotNull { pc ->
                runCatching { URI(pc.call.url).scheme?.lowercase() }
                    .getOrNull()
                    ?.takeIf { it.isNotEmpty() }
            }
            .distinct()
            .singleOrNull()
        val replayHeaders: List<StaticHeaderBinding> =
            if (apiCalls.isEmpty()) {
                emptyList()
            } else {
                apiCalls.first().call.requestHeaders.entries
                    .mapNotNull { (name, value) ->
                        val lower = name.lowercase()
                        when {
                            value.isBlank() -> null
                            lower in REPLAY_EXCLUDED -> null
                            lower.startsWith("sec-") -> null
                            // v1.5.3 (round-16 amendment A):
                            // a temporal header is a moment,
                            // not a constant — identical values
                            // across a fast burst are exactly
                            // how a stale timestamp gets baked
                            // in, so it never promotes.
                            isTemporalReplayHeader(name, value) ->
                                null
                            cookies.values.any { it == value } ->
                                null
                            apiCalls.all { pc ->
                                pc.call.requestHeaders.entries
                                    .any { e ->
                                        e.key.equals(
                                            name, ignoreCase = true
                                        ) && e.value == value
                                    }
                            } -> StaticHeaderBinding(
                                headerName = name,
                                value = value,
                                hosts = apiHosts,
                                scheme = harvestScheme
                            )
                            else -> null
                        }
                    }
            }

        // ── assemble ───────────────────────────────────────
        fun listingSpec(from: Listing, search: Boolean): ListSpec {
            var url = from.call.url
            val params = queryParams(url)
            val searchKey = searchParamKey(params)
            if (search) {
                url = replaceQueryValue(url, searchKey!!, "{query}")
            } else if (searchKey != null) {
                url = removeQueryParam(url, searchKey)
            }
            pageParamKey(params)?.let { pageKey ->
                url = replaceQueryValue(url, pageKey, "{page}")
            }
            return ListSpec(
                url = url,
                results = "",
                title = from.titleField,
                link = from.linkField,
                cover = firstField(
                    from.array.items.first() as JsonObject, COVER_FIELDS
                ),
                author = firstField(
                    from.array.items.first() as JsonObject, AUTHOR_FIELDS
                ),
                jsonPath = from.array.path,
                jsonLinkTemplate = bookLinkTemplate
            )
        }

        val chaptersTemplate = chapters.call.url
            .replace(slugValue, "{bookSlug}")
            .let { templated ->
                val params = queryParams(templated)
                var out = templated
                if (params.containsKey("offset")) {
                    out = replaceQueryValue(out, "offset", "{offset}")
                }
                pageParamKey(params)?.let { pageKey ->
                    out = replaceQueryValue(out, pageKey, "{page}")
                }
                out
            }
        // v1.5.2 fix (FIX-1c): the stated total usually rides
        // INSIDE the envelope (data.totalCount), not at the
        // payload root — looking only at the root left the
        // chapter walk with no stopping total, so a big book's
        // drive paged until the site or the clock gave up.
        // Row totals win over page totals; the emitted path is
        // the reader's own dot path.
        val totalField = (chapters.payloadObject())?.let { obj ->
            val envelope = obj["data"] as? JsonObject
            var found: String? = null
            for (name in TOTAL_FIELDS) {
                val rootKey = obj.keys.firstOrNull {
                    it.equals(name, ignoreCase = true) &&
                        obj[it] is JsonPrimitive
                }
                if (rootKey != null) {
                    found = rootKey
                    break
                }
                val envKey = envelope?.keys?.firstOrNull {
                    it.equals(name, ignoreCase = true) &&
                        envelope[it] is JsonPrimitive
                }
                if (envKey != null) {
                    found = "data.$envKey"
                    break
                }
            }
            found
        }
        val config = DeclarativeConfig(
            id = "",
            name = siteName,
            baseUrl = webBaseUrl,
            // A dedicated search spec only when a
            // search-shaped call was actually seen (its
            // parameter templated to {query}); a site whose
            // traffic showed no search call gets none — the
            // drive records search as not set up rather than
            // the recipe inventing an endpoint.
            search = searchListing?.let { listingSpec(it, search = true) },
            popular = listingSpec(listing, search = false),
            details = DetailsSpec(
                chapters = ChapterListSpec(
                    list = "a",
                    name = "@text",
                    link = "@href",
                    reverse = false,
                    pagination = ChapterListPagination(
                        urlTemplate = chaptersTemplate,
                        bookIdPattern = "",
                        jsonArrayPath = chapters.array.path.ifEmpty { null },
                        jsonTotalPath = totalField,
                        pageSize = chapters.array.items.size
                            .takeIf { it > 0 } ?: 50
                    ),
                    jsonNameField = chapters.nameField,
                    jsonLinkField = chapters.linkField,
                    jsonLinkTemplate = chapterLinkTemplate
                )
            ),
            chapter = ChapterSpec(
                content = "body",
                ajax = ajax
            ),
            credentials = when {
                binding == null && replayHeaders.isEmpty() -> null
                else -> CredentialSpec(
                    headerBinding = binding,
                    staticHeaders = replayHeaders
                )
            },
            extraDomains = apiHosts
        )
        return Synthesis(config, apiHosts)
        }

        for (slug in slugCandidates) {
            assemble(slug)?.let { return it }
        }
        return null
    }

    // ── shapes ─────────────────────────────────────────────

    private class ParsedCall(val call: HarvestedCall, val payload: JsonElement) {
        fun payloadObject(): JsonObject? = payload as? JsonObject
    }

    private data class ArrayHit(val path: String, val items: List<JsonElement>)

    private class Listing(
        val pc: ParsedCall,
        val array: ArrayHit,
        val titleField: String,
        val linkField: String
    ) {
        val call: HarvestedCall get() = pc.call
    }

    private class ChapterList(
        val pc: ParsedCall,
        val array: ArrayHit,
        val nameField: String,
        val linkField: String
    ) {
        val call: HarvestedCall get() = pc.call
        fun payloadObject(): JsonObject? = pc.payloadObject()
    }

    /** The array a payload carries: itself (path "") or the
     * first array-of-objects field (path = the field name). */
    private fun arrayOf(payload: JsonElement): ArrayHit? =
        arraysOf(payload).firstOrNull()

    /** Every candidate record array in [payload], outermost
     * first. The listing pass takes the first candidate that
     * clears the book-list bar — a shallow hit is not always
     * the record array (a module homepage leads with its
     * module array, which is not a book list). */
    private fun arraysOf(payload: JsonElement): List<ArrayHit> {
        if (payload is JsonArray) {
            return listOf(ArrayHit("", payload))
        }
        val obj = payload as? JsonObject ?: return emptyList()
        val hits = mutableListOf<ArrayHit>()
        val seen = mutableSetOf<String>()
        fun add(path: String, value: JsonArray) {
            if (value.any { it is JsonObject } && seen.add(path)) {
                hits += ArrayHit(path, value)
            }
        }
        val fields = ARRAY_FIELDS + obj.keys
        for (field in fields.distinct()) {
            val value = obj[field] as? JsonArray ?: continue
            add(field, value)
        }
        // v1.5.2 fix (FIX-1c): descend ONE envelope level — the
        // story-wave family (and many others) wrap the record
        // array one object deeper: {data: {list: [...]}}. The
        // read side already resolves dotted paths, so the hit
        // carries the dotted path ("data.list").
        for (field in fields.distinct()) {
            val inner = obj[field] as? JsonObject ?: continue
            for (innerField in (ARRAY_FIELDS + inner.keys).distinct()) {
                val value = inner[innerField] as? JsonArray ?: continue
                add("$field.$innerField", value)
            }
        }
        // v1.5.2 (§10 item 1): descend into an envelope ARRAY —
        // module-shaped homepages (story-wave's
        // orgCustomConfigs) carry the catalogue as a list
        // inside each module object:
        // {data: [{data: {list: [...]}}]}. The hit carries the
        // indexed dotted path ("data.2.data.list"); the read
        // side resolves numeric segments as array indices.
        for (field in fields.distinct()) {
            val modules = obj[field] as? JsonArray ?: continue
            for ((index, module) in modules.withIndex()) {
                val modObj = module as? JsonObject ?: continue
                for (innerField in (ARRAY_FIELDS + modObj.keys)
                    .distinct()) {
                    (modObj[innerField] as? JsonArray)?.let {
                        add("$field.$index.$innerField", it)
                    }
                    val inner = modObj[innerField] as? JsonObject
                        ?: continue
                    for (leafField in (ARRAY_FIELDS + inner.keys)
                        .distinct()) {
                        val value = inner[leafField] as? JsonArray
                            ?: continue
                        add(
                            "$field.$index.$innerField.$leafField",
                            value
                        )
                    }
                }
            }
        }
        return hits
    }

    // ── JSON helpers ───────────────────────────────────────

    /** Parse a possibly-truncated body sample: samples are
     * capped mid-stream, so an unclosed payload is repaired
     * by cutting at the last complete object and closing
     * the brackets its first character opened. */
    private fun parseLenient(sample: String): JsonElement? {
        val text = sample.trim()
        if (text.isEmpty()) return null
        runCatching { return json.parseToJsonElement(text) }
        val lastObject = text.lastIndexOf('}')
        if (lastObject <= 0) return null
        val cut = text.substring(0, lastObject + 1)
        // v1.5.2 fix (FIX-1c): close EVERY bracket the cut
        // leaves open — the old repair only closed a bare
        // top-level array, so an enveloped payload
        // ({data:{list:[…}) never repaired at all.
        val repaired = HarvestLog.closeOpenBrackets(cut) ?: return null
        return runCatching { json.parseToJsonElement(repaired) }.getOrNull()
    }

    private fun firstField(obj: JsonObject, fields: List<String>): String? =
        fields.firstOrNull { field ->
            (obj[field] as? JsonPrimitive)?.contentOrNull != null
        }

    private fun stringField(obj: JsonObject, field: String): String? =
        (obj[field] as? JsonPrimitive)?.contentOrNull

    // ── URL helpers ────────────────────────────────────────

    private fun pathSegments(url: String): List<String> =
        runCatching { URI(url).path }
            .getOrNull()
            ?.split('/')
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

    private fun originOf(url: String): String =
        runCatching {
            val uri = URI(url)
            "${uri.scheme}://${uri.host}" +
                if (uri.port > 0) ":${uri.port}" else ""
        }.getOrDefault("")

    private fun queryParams(url: String): Map<String, String> {
        val query = runCatching { URI(url).rawQuery }.getOrNull()
            ?: return emptyMap()
        return query.split('&').mapNotNull { pair ->
            val eq = pair.indexOf('=')
            if (eq <= 0) null
            else pair.substring(0, eq) to pair.substring(eq + 1)
        }.toMap()
    }

    private fun replaceSegment(url: String, segment: String, with: String): String {
        val base = url.substringBefore("?")
        val query = url.substringAfter("?", "")
        val parts = base.split('/').toMutableList()
        val index = parts.indexOf(segment)
        if (index < 0) return url
        parts[index] = with
        return parts.joinToString("/") +
            if (query.isNotEmpty()) "?$query" else ""
    }

    private fun replaceQueryValue(url: String, param: String, value: String): String {
        val base = url.substringBefore("?")
        val query = url.substringAfter("?", "")
        if (query.isEmpty()) return url
        val rebuilt = query.split('&').joinToString("&") { pair ->
            val eq = pair.indexOf('=')
            if (eq > 0 && pair.substring(0, eq) == param) {
                "$param=$value"
            } else {
                pair
            }
        }
        return "$base?$rebuilt"
    }

    private fun removeQueryParam(url: String, param: String): String {
        val base = url.substringBefore("?")
        val query = url.substringAfter("?", "")
        if (query.isEmpty()) return url
        val kept = query.split('&').filter { pair ->
            pair.substringBefore('=') != param
        }
        return if (kept.isEmpty()) base else base + "?" + kept.joinToString("&")
    }
}
