package app.xreader.data.extension

import app.xreader.domain.content.ChapterContent
import app.xreader.extensionapi.Source
import app.xreader.extensionapi.SourceBook
import app.xreader.extensionapi.SourceBookPage
import app.xreader.extensionapi.SourceChapter
import app.xreader.extensionapi.SourceChapterContent
import app.xreader.extensionapi.SourceContext
import app.xreader.extensionapi.SourceException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

/**
 * The declarative engine (PLAN §5): interprets a [DeclarativeConfig] —
 * pure selector data, no code — as a [Source]. It fetches through the
 * context's host HTTP (so the whitelist/session/rate-limit apply) and
 * parses with jsoup directly (it IS host code; the API's parsed-
 * document facade exists for sandboxed kinds).
 *
 * Chapter HTML is converted to the app's [ChapterContent] model via
 * [HtmlToContent] and stashed in [lastChapterContent] /
 * [contentOf] — the extension API returns HTML strings, but the host's
 * own fetch path wants the normalised model; the service uses the
 * model when this source produced it.
 */
class DeclarativeSource(
    /** The rules this source runs on (exposed so the host can apply
     * the source's own cleaner rules in the fetch path). */
    val config: DeclarativeConfig,
    private val context: SourceContext
) : Source {

    override val id: String get() = config.id
    override val name: String get() = config.name
    override val lang: String get() = config.lang
    override val baseUrl: String get() = config.baseUrl

    /** Normalised content of the most recent chapterContent() call,
     * keyed by chapter id — the host's fetch path reads this. */
    private val contentCache = LinkedHashMap<String, ChapterContent>()

    fun contentOf(chapterId: String): ChapterContent? = contentCache[chapterId]

    // ── listings ────────────────────────────────────────────────────

    override fun search(query: String, page: Int): SourceBookPage {
        val spec = config.search
            ?: throw SourceException("“$name” doesn't support search")
        return fetchListing(spec, query, page)
    }

    override fun popular(page: Int): SourceBookPage {
        val spec = config.popular ?: config.latest ?: config.search
            ?: throw SourceException("“$name” has no catalogue page")
        return fetchListing(spec, "", page)
    }

    override fun latest(page: Int): SourceBookPage {
        val spec = config.latest ?: config.popular ?: config.search
            ?: throw SourceException("“$name” has no catalogue page")
        return fetchListing(spec, "", page)
    }

    /** v1.0.2 (R24): the signed-in account's bookshelf page. Runs
     * through the same session-aware HTTP context as every other
     * call, so the user's assisted-login session applies. */
    fun bookshelf(page: Int): SourceBookPage {
        val spec = config.bookshelf
            ?: throw SourceException("“$name” doesn't know its account bookshelf")
        return fetchListing(spec, "", page)
    }

    private fun fetchListing(spec: ListSpec, query: String, page: Int): SourceBookPage {
        val url = resolveUrl(
            spec.url
                .replace("{query}", URLEncoder.encode(query, "UTF-8"))
                .replace("{page}", page.toString())
                .replace("{page0}", (page - 1).coerceAtLeast(0).toString())
        )
        val doc = if (spec.method.equals("POST", ignoreCase = true) && spec.body != null) {
            val body = spec.body
                .replace("{query}", URLEncoder.encode(query, "UTF-8"))
                .replace("{page}", page.toString())
            val response = context.http.post(url, body)
            if (response.status !in 200..299) {
                throw app.xreader.extensionapi.SourceException(
                    "the site answered HTTP ${response.status}"
                )
            }
            Jsoup.parse(response.body, response.finalUrl.ifBlank { url })
        } else {
            fetchDoc(url)
        }
        val books = selectChained(doc, spec.results).mapNotNull { el ->
            val rawTitle = readSpec(el, spec.title)?.trim().orEmpty()
            val link = readSpec(el, spec.link)?.trim().orEmpty()
            if (rawTitle.isEmpty() || link.isEmpty()) return@mapNotNull null
            // v1.0.5 (A7): listing titles get the shared hygiene —
            // trailing date stamps stripped, and a date-only title
            // (the extractor read the date element) falls back to
            // the book's URL slug instead of naming the book after
            // a calendar day (D25).
            val cleaned = TitleCleaner.clean(rawTitle)
            val title = when {
                cleaned.isBlank() -> rawTitle
                TitleCleaner.isDateOnly(cleaned) ->
                    TitleCleaner.slugTitle(absolute(link, url)) ?: return@mapNotNull null
                else -> cleaned
            }
            SourceBook(
                id = absolute(link, url),
                title = title,
                author = spec.author?.let { readSpec(el, it)?.trim()?.ifEmpty { null } },
                coverUrl = spec.cover?.let { readSpec(el, it)?.trim()?.ifEmpty { null } }
                    ?.let { absolute(it, url) }
            )
        }
        val hasNext = spec.nextPage?.let { sel ->
            selectChained(doc, sel).isNotEmpty()
        } ?: false
        return SourceBookPage(books, hasNext)
    }

    // ── details + chapters ──────────────────────────────────────────

    override fun details(book: SourceBook): SourceBook {
        val doc = fetchDoc(bookPageUrl(book))
        val spec = config.details
        return book.copy(
            title = readTitle(doc, spec.title) ?: book.title,
            author = spec.author?.let { readDocSpec(doc, it) }?.ifBlank { null } ?: book.author,
            description = spec.description?.let { readDocSpec(doc, it) }?.ifBlank { null }
                ?: book.description,
            coverUrl = spec.cover?.let { readDocSpec(doc, it) }?.ifBlank { null }
                ?.let { absolute(it, bookPageUrl(book)) } ?: book.coverUrl
        )
    }

    override fun chapters(book: SourceBook): List<SourceChapter> {
        val doc = fetchDoc(bookPageUrl(book))
        return parseChapters(doc, bookPageUrl(book))
    }

    // ── v1.0.1 enrichment ─────────────────────────────────────────

    override fun reviews(book: SourceBook): List<app.xreader.extensionapi.SourceReview>? {
        val spec = config.reviews ?: return null
        val doc = fetchDoc(bookPageUrl(book))
        return selectChained(doc, spec.list).mapNotNull { el ->
            val text = readSpec(el, spec.text)?.trim().orEmpty()
            if (text.isEmpty()) return@mapNotNull null
            app.xreader.extensionapi.SourceReview(
                author = readSpec(el, spec.author)?.trim()?.ifEmpty { "Reader" } ?: "Reader",
                rating = spec.rating?.let { readSpec(el, it) }
                    ?.replace(Regex("[^0-9.]"), "")?.toFloatOrNull(),
                text = text,
                dateText = spec.date?.let { readSpec(el, it)?.trim()?.ifEmpty { null } }
            )
        }
    }

    override fun comments(book: SourceBook): List<app.xreader.extensionapi.SourceComment>? {
        val spec = config.comments ?: return null
        val doc = fetchDoc(bookPageUrl(book))
        return selectChained(doc, spec.list).mapNotNull { el ->
            val text = readSpec(el, spec.text)?.trim().orEmpty()
            if (text.isEmpty()) return@mapNotNull null
            app.xreader.extensionapi.SourceComment(
                author = readSpec(el, spec.author)?.trim()?.ifEmpty { "Reader" } ?: "Reader",
                text = text,
                dateText = spec.date?.let { readSpec(el, it)?.trim()?.ifEmpty { null } }
            )
        }
    }

    override fun related(book: SourceBook): List<SourceBook>? {
        val spec = config.related ?: return null
        val url = bookPageUrl(book)
        val doc = fetchDoc(url)
        return selectChained(doc, spec.results).mapNotNull { el ->
            val rawTitle = readSpec(el, spec.title)?.trim().orEmpty()
            val link = readSpec(el, spec.link)?.trim().orEmpty()
            if (rawTitle.isEmpty() || link.isEmpty()) return@mapNotNull null
            // v1.0.5 (A7): same title hygiene as listings.
            val cleaned = TitleCleaner.clean(rawTitle)
            val title = when {
                cleaned.isBlank() -> rawTitle
                TitleCleaner.isDateOnly(cleaned) ->
                    TitleCleaner.slugTitle(absolute(link, url)) ?: return@mapNotNull null
                else -> cleaned
            }
            SourceBook(
                id = absolute(link, url),
                title = title,
                author = spec.author?.let { readSpec(el, it)?.trim()?.ifEmpty { null } },
                coverUrl = spec.cover?.let { readSpec(el, it)?.trim()?.ifEmpty { null } }
                    ?.let { absolute(it, url) }
            )
        }
    }

    /** Details + chapters from ONE fetch — book pages carry both. */
    fun detailsAndChapters(book: SourceBook): Pair<SourceBook, List<SourceChapter>> {
        val url = bookPageUrl(book)
        val doc = fetchDoc(url)
        val spec = config.details
        val filled = book.copy(
            title = readTitle(doc, spec.title) ?: book.title,
            author = spec.author?.let { readDocSpec(doc, it) }?.ifBlank { null } ?: book.author,
            description = spec.description?.let { readDocSpec(doc, it) }?.ifBlank { null }
                ?: book.description,
            coverUrl = spec.cover?.let { readDocSpec(doc, it) }?.ifBlank { null }
                ?.let { absolute(it, url) } ?: book.coverUrl
        )
        return filled to parseChapters(doc, url)
    }

    private fun parseChapters(doc: Document, pageUrl: String): List<SourceChapter> {
        val spec = config.details.chapters
        val chapters = selectChained(doc, spec.list).mapIndexedNotNull { index, el ->
            val link = readSpec(el, spec.link)?.trim().orEmpty()
            if (link.isEmpty()) return@mapIndexedNotNull null
            val chapterName = readSpec(el, spec.name)?.trim()
                ?.ifEmpty { null } ?: "Chapter ${index + 1}"
            SourceChapter(
                id = absolute(link, pageUrl),
                name = chapterName,
                number = ChapterNumbers.parse(chapterName)
            )
        }
        // v1.0.5 (E3): sites that show a "latest chapters" block
        // AND the full list on the same page (Novelight: 10 + 51
        // rows under one selector) would otherwise hand back the
        // overlap twice. One entry per chapter URL, first
        // occurrence wins, order preserved — the COMPLETE list.
        val unique = chapters.distinctBy { it.id }
        return if (spec.reverse) unique.asReversed() else unique
    }

    /**
     * v1.0.5 (A3): the ids of this book's chapters whose list
     * ROW is lock-marked (paid/premium) — one extra book-page
     * fetch, used by the test-drives so their text sampling can
     * skip locked chapters instead of condemning the source.
     */
    fun lockedChapterIds(book: SourceBook): Set<String> {
        val url = bookPageUrl(book)
        val doc = fetchDoc(url)
        val spec = config.details.chapters
        val locked = mutableSetOf<String>()
        for (el in selectChained(doc, spec.list)) {
            if (!ChapterLocks.rowIsLocked(el)) continue
            val link = readSpec(el, spec.link)?.trim().orEmpty()
            if (link.isNotEmpty()) locked += absolute(link, url)
        }
        return locked
    }

    /**
     * v1.0.5 (A7): a details title read with the shared hygiene —
     * trailing date stamps stripped; a date-only read is rejected
     * (null) so the caller keeps the title the book already had.
     */
    private fun readTitle(doc: Document, rawSpec: String?): String? {
        val raw = rawSpec?.let { readDocSpec(doc, it) }?.trim()?.ifBlank { null }
            ?: return null
        val cleaned = TitleCleaner.clean(raw)
        if (cleaned.isBlank() || TitleCleaner.isDateOnly(cleaned)) return null
        return cleaned
    }

    // ── chapter content ─────────────────────────────────────────────

    override fun chapterContent(chapter: SourceChapter): SourceChapterContent {
        val spec = config.chapter
        // v1.0.5 Stage 1.5: AJAX text mode — when the config
        // declares it AND this chapter's URL yields a numeric id,
        // the text comes from the JSON endpoint instead of the
        // (shell) chapter page. When either doesn't hold, the
        // static path below runs exactly as it always has.
        val ajax = spec.ajax
        if (ajax != null) {
            val endpoint = ajax.endpointFor(chapter.id)
            if (endpoint != null) return ajaxChapterContent(chapter, ajax, endpoint)
        }
        var url: String? = chapter.id
        val fragments = mutableListOf<String>()
        var title: String? = null
        var pages = 0
        while (url != null && pages < MAX_CHAPTER_PAGES) {
            pages++
            val doc = fetchDoc(url)
            if (title == null) {
                title = spec.title?.let { readDocSpec(doc, it) }?.ifBlank { null }
            }
            val element = firstChainedMatch(doc, spec.content)?.first
                ?: throw SourceException("the chapter text wasn't found on the page")
            val clone = element.clone()
            for (removeSel in spec.remove) {
                for (candidate in SelectorChains.split(removeSel)) {
                    runCatching { clone.select(candidate).remove() }
                }
            }
            fragments += clone.html()
            url = spec.nextPage?.let { nextSel ->
                val (nextEl, parsed) = firstChainedMatch(doc, nextSel)
                    ?: return@let null
                val href = if (parsed.attr != null) {
                    readAttr(nextEl, parsed.attr)
                } else {
                    nextEl.attr("href").ifBlank { nextEl.selectFirst("a")?.attr("href") }
                }
                href?.takeIf { it.isNotBlank() }?.let { absolute(it, url!!) }
            }
        }
        val combined = fragments.joinToString("\n")
        // Convert once, on the concatenated fragment, so multi-page
        // chapters read as one continuous text.
        val doc = Jsoup.parseBodyFragment(combined, chapter.id)
        contentCache[chapter.id] = HtmlToContent.fromDocument(doc, chapter.id)
        return SourceChapterContent(html = combined, title = title)
    }

    /**
     * The AJAX text mode's read (v1.0.5 Stage 1.5): fetch the
     * declared endpoint through the source's own HTTP context —
     * the declared headers ride along, and the whitelist, login
     * session and rate limit apply exactly as for any other fetch
     * this source makes — then take the configured JSON field as
     * the chapter's HTML and run the chapter spec's ordinary
     * extraction/cleanup over it (the [ChapterSpec.content]
     * selector applies inside the payload when it matches; the
     * payload IS the chapter container when it doesn't, which is
     * the verified shape: `{"content": "<div class=…>prose</div>"}`).
     *
     * An endpoint that answers with no readable text — field
     * missing, field blank, or HTML whose text is empty — is how
     * these sites mark a paid/locked chapter, so it surfaces as
     * the typed empty-text failure the test-drives already
     * understand (they sample another chapter, or report the
     * paid/locked state), never as a silent empty chapter.
     */
    private fun ajaxChapterContent(
        chapter: SourceChapter,
        ajax: ChapterTextAjax,
        endpoint: String
    ): SourceChapterContent {
        val spec = config.chapter
        val response = context.http.get(endpoint, ajax.headers)
        if (response.status !in 200..299) {
            throw SourceException("the site answered HTTP ${response.status}")
        }
        val root = runCatching {
            ajaxJson.parseToJsonElement(response.body)
        }.getOrNull() as? JsonObject
        val payload = (root?.get(ajax.jsonContentField) as? JsonPrimitive)
            ?.content?.takeIf { it.isNotBlank() }
            ?: throw SourceException(AJAX_TEXT_EMPTY_MESSAGE)
        val fragment = Jsoup.parseBodyFragment(payload, chapter.id)
        val title = spec.title?.let { readDocSpec(fragment, it) }?.ifBlank { null }
        val element = firstChainedMatch(fragment, spec.content)?.first
            ?: fragment.body()
        val clone = element.clone()
        for (removeSel in spec.remove) {
            for (candidate in SelectorChains.split(removeSel)) {
                runCatching { clone.select(candidate).remove() }
            }
        }
        if (clone.text().isBlank()) throw SourceException(AJAX_TEXT_EMPTY_MESSAGE)
        val html = clone.html()
        val doc = Jsoup.parseBodyFragment(html, chapter.id)
        contentCache[chapter.id] = HtmlToContent.fromDocument(doc, chapter.id)
        return SourceChapterContent(html = html, title = title)
    }

    // ── helpers ─────────────────────────────────────────────────────

    private fun bookPageUrl(book: SourceBook): String =
        if (book.id.startsWith("http://") || book.id.startsWith("https://")) {
            book.id
        } else {
            resolveUrl(book.id)
        }

    private fun fetchDoc(url: String): Document {
        val response = context.http.get(url)
        if (response.status !in 200..299) {
            throw SourceException("the site answered HTTP ${response.status}")
        }
        return Jsoup.parse(response.body, response.finalUrl.ifBlank { url })
    }

    private fun resolveUrl(raw: String): String =
        if (raw.startsWith("http://") || raw.startsWith("https://")) raw
        else baseUrl.trimEnd('/') + "/" + raw.trimStart('/')

    private fun absolute(raw: String, pageUrl: String): String =
        runCatching { URI(pageUrl).resolve(raw).toString() }.getOrDefault(raw)

    /**
     * v1.0.4 (D14): every selector read goes through the fallback
     * chain — candidates are tried in order and the first that
     * produces a non-blank value wins. A one-candidate chain (every
     * config written before chains existed) returns exactly what the
     * old single-selector read returned, blank included.
     */
    private fun readDocSpec(doc: Document, rawSpec: String): String? {
        var firstValue: String? = null
        var seen = false
        for (candidate in SelectorChains.split(rawSpec)) {
            val spec = SelectorSpec.parse(candidate)
            val el = if (spec.css.isBlank()) {
                doc
            } else {
                doc.selectFirst(spec.css) ?: continue
            }
            val value = readFrom(el, spec)
            if (!seen) {
                firstValue = value
                seen = true
            }
            if (!value.isNullOrBlank()) return value
        }
        return firstValue
    }

    private fun readSpec(element: Element, rawSpec: String): String? {
        var firstValue: String? = null
        var seen = false
        for (candidate in SelectorChains.split(rawSpec)) {
            val spec = SelectorSpec.parse(candidate)
            val target = if (spec.css.isBlank()) {
                element
            } else {
                element.selectFirst(spec.css) ?: continue
            }
            val value = readFrom(target, spec)
            if (!seen) {
                firstValue = value
                seen = true
            }
            if (!value.isNullOrBlank()) return value
        }
        return firstValue
    }

    /** First candidate of a (possibly chained) list selector that
     * matches anything in [doc] wins; no match anywhere = empty. */
    private fun selectChained(doc: Document, rawSpec: String): List<Element> {
        for (candidate in SelectorChains.split(rawSpec)) {
            val css = SelectorSpec.parse(candidate).css
            if (css.isBlank()) continue
            val found = runCatching { doc.select(css) }.getOrNull()
            if (!found.isNullOrEmpty()) return found
        }
        return emptyList()
    }

    /** The first element any candidate of a (possibly chained)
     * selector matches, with the candidate that matched — chapter
     * content and next-page resolution need the parsed spec too. */
    private fun firstChainedMatch(
        doc: Document,
        rawSpec: String
    ): Pair<Element, SelectorSpec>? {
        for (candidate in SelectorChains.split(rawSpec)) {
            val spec = SelectorSpec.parse(candidate)
            if (spec.css.isBlank()) continue
            val el = runCatching { doc.selectFirst(spec.css) }.getOrNull()
                ?: continue
            return el to spec
        }
        return null
    }

    private fun readFrom(element: Element, spec: SelectorSpec): String? {
        if (spec.attr == null) return element.text()
        if (spec.attr == "text") return element.text()
        if (spec.attr == "html") return element.html()
        return readAttr(element, spec.attr)
    }

    private fun readAttr(element: Element, attr: String): String? {
        val value = when (attr.lowercase()) {
            "href", "src" -> element.absUrl(attr)
            else -> element.attr(attr)
        }
        return value.ifBlank { element.attr(attr).ifBlank { null } }
    }

    companion object {
        /** Multi-page chapter chaining cap (§5: no runaway pagination). */
        const val MAX_CHAPTER_PAGES = 10

        /** v1.0.5 Stage 1.5: the AJAX endpoint answered but carried
         * no readable text — the paid/locked signal for this mode,
         * worded so the test-drives' sampling reports it plainly. */
        const val AJAX_TEXT_EMPTY_MESSAGE =
            "the chapter's text came back empty — the chapter is " +
                "paid or locked, or the site changed how it serves " +
                "chapter text"

        /** The AJAX mode's JSON reader: the tree's existing
         * kotlinx.serialization (no new dependency). */
        private val ajaxJson = Json { ignoreUnknownKeys = true; isLenient = true }
    }
}

/** Chapter-number extraction shared by the engine, the failover
 * matcher and link inference: "Chapter 10.5", "Ch. 3", "第12章",
 * a bare leading "12." — null when there's no number to find. */
object ChapterNumbers {
    private val patterns = listOf(
        Regex("(?i)chapter\\s*(\\d+(?:\\.\\d+)?)"),
        Regex("(?i)\\bch\\.?\\s*(\\d+(?:\\.\\d+)?)"),
        Regex("(?i)episode\\s*(\\d+(?:\\.\\d+)?)"),
        Regex("第\\s*(\\d+)\\s*[章话話节節回]"),
        Regex("^\\s*(\\d+(?:\\.\\d+)?)\\s*[.:\\-]")
    )

    fun parse(name: String): Float? {
        for (pattern in patterns) {
            pattern.find(name)?.let { return it.groupValues[1].toFloatOrNull() }
        }
        return null
    }
}
