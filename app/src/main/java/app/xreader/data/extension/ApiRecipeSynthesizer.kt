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
        listOf("cover", "cover_url", "coverUrl", "image", "thumbnail", "poster")
    private val AUTHOR_FIELDS = listOf("author", "author_name")
    private val CHAPTER_NAME_FIELDS = listOf("title", "name")
    private val CHAPTER_LINK_FIELDS = listOf("url", "slug", "id", "number")
    private val TEXT_FIELDS =
        listOf("content", "body", "text", "html", "content_html", "chapter_content")
    private val ARRAY_FIELDS =
        listOf("data", "items", "results", "novels", "books", "list", "chapters")
    private val SEARCH_PARAMS = listOf("q", "query", "search", "keyword", "title")
    private val TOTAL_FIELDS = listOf("total", "total_count", "count")

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
            val array = arrayOf(pc.payload) ?: return@mapNotNull null
            val first = array.items.firstOrNull() as? JsonObject
                ?: return@mapNotNull null
            val titleField = firstField(first, TITLE_FIELDS)
            val linkField = firstField(first, LINK_FIELDS)
            if (titleField == null || linkField == null) {
                return@mapNotNull null
            }
            val qualifying = array.items.count { item ->
                val record = item as? JsonObject
                    ?: return@count false
                !stringField(record, titleField).isNullOrBlank() &&
                    !stringField(record, linkField).isNullOrBlank()
            }
            if (qualifying < MIN_LIST_ITEMS) return@mapNotNull null
            Listing(pc, array, titleField, linkField)
        }
        if (listings.isEmpty()) return null
        val searchListing = listings.firstOrNull { listing ->
            queryParams(listing.call.url).keys.any { it in SEARCH_PARAMS }
        }
        val catalogueListing = listings.firstOrNull { listing ->
            queryParams(listing.call.url).keys.none { it in SEARCH_PARAMS }
        } ?: searchListing ?: return null
        val listing = catalogueListing
        val firstItem = listing.array.items.firstOrNull() as? JsonObject
            ?: return null
        val slugValue = stringField(firstItem, listing.linkField)
            ?.takeIf { it.isNotBlank() } ?: return null

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
            if (!pathSegments(pc.call.url).contains(slugValue)) {
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
        val textCall = parsed.firstOrNull { pc ->
            if (!pathSegments(pc.call.url).contains(chapterKey)) {
                return@firstOrNull false
            }
            val obj = pc.payload as? JsonObject ?: return@firstOrNull false
            TEXT_FIELDS.any { field ->
                (obj[field] as? JsonPrimitive)?.contentOrNull
                    ?.let { it.length >= 200 } == true
            }
        }
        val textField = textCall?.let { pc ->
            val obj = pc.payload as JsonObject
            TEXT_FIELDS.firstOrNull { field ->
                (obj[field] as? JsonPrimitive)?.contentOrNull
                    ?.let { it.length >= 200 } == true
            }
        }

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
            val content = (obj[textField] as? JsonPrimitive)
                ?.contentOrNull.orEmpty()
            ChapterTextAjax(
                urlTemplate = template,
                jsonContentField = textField,
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

        // ── assemble ───────────────────────────────────────
        fun listingSpec(from: Listing, search: Boolean): ListSpec {
            var url = from.call.url
            val params = queryParams(url)
            if (search) {
                val param = params.keys.first { it in SEARCH_PARAMS }
                url = replaceQueryValue(url, param, "{query}")
            } else if (params.keys.any { it in SEARCH_PARAMS }) {
                val param = params.keys.first { it in SEARCH_PARAMS }
                url = removeQueryParam(url, param)
            }
            if (params.containsKey("page")) {
                url = replaceQueryValue(url, "page", "{page}")
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
                if (params.containsKey("page")) {
                    out = replaceQueryValue(out, "page", "{page}")
                }
                out
            }
        val totalField = (chapters.payloadObject())?.let { obj ->
            TOTAL_FIELDS.firstOrNull { obj[it] is JsonPrimitive }
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
            credentials = binding?.let { CredentialSpec(headerBinding = it) },
            extraDomains = apiHosts
        )
        return Synthesis(config, apiHosts)
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
    private fun arrayOf(payload: JsonElement): ArrayHit? {
        if (payload is JsonArray) {
            return ArrayHit("", payload)
        }
        val obj = payload as? JsonObject ?: return null
        val fields = ARRAY_FIELDS + obj.keys
        for (field in fields.distinct()) {
            val value = obj[field] as? JsonArray ?: continue
            if (value.any { it is JsonObject }) {
                return ArrayHit(field, value)
            }
        }
        return null
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
        val repaired = when {
            text.startsWith("[") -> "$cut]"
            text.startsWith("{") -> cut
            else -> return null
        }
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
