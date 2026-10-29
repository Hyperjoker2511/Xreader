package app.xreader.data.extension

import app.xreader.domain.content.ChapterContent
import app.xreader.extensionapi.Source
import app.xreader.extensionapi.SourceBook
import app.xreader.extensionapi.SourceBookPage
import app.xreader.extensionapi.SourceChapter
import app.xreader.extensionapi.SourceChapterContent
import app.xreader.extensionapi.SourceContext
import app.xreader.extensionapi.SourceException
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
        val books = doc.select(spec.results).mapNotNull { el ->
            val title = readSpec(el, spec.title)?.trim().orEmpty()
            val link = readSpec(el, spec.link)?.trim().orEmpty()
            if (title.isEmpty() || link.isEmpty()) return@mapNotNull null
            SourceBook(
                id = absolute(link, url),
                title = title,
                author = spec.author?.let { readSpec(el, it)?.trim()?.ifEmpty { null } },
                coverUrl = spec.cover?.let { readSpec(el, it)?.trim()?.ifEmpty { null } }
                    ?.let { absolute(it, url) }
            )
        }
        val hasNext = spec.nextPage?.let { sel ->
            doc.select(SelectorSpec.parse(sel).css).isNotEmpty()
        } ?: false
        return SourceBookPage(books, hasNext)
    }

    // ── details + chapters ──────────────────────────────────────────

    override fun details(book: SourceBook): SourceBook {
        val doc = fetchDoc(bookPageUrl(book))
        val spec = config.details
        return book.copy(
            title = spec.title?.let { readDocSpec(doc, it) }?.ifBlank { null } ?: book.title,
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
        return doc.select(SelectorSpec.parse(spec.list).css).mapNotNull { el ->
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
        return doc.select(SelectorSpec.parse(spec.list).css).mapNotNull { el ->
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
        return doc.select(SelectorSpec.parse(spec.results).css).mapNotNull { el ->
            val title = readSpec(el, spec.title)?.trim().orEmpty()
            val link = readSpec(el, spec.link)?.trim().orEmpty()
            if (title.isEmpty() || link.isEmpty()) return@mapNotNull null
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
            title = spec.title?.let { readDocSpec(doc, it) }?.ifBlank { null } ?: book.title,
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
        val nameSpec = SelectorSpec.parse(spec.name)
        val linkSpec = SelectorSpec.parse(spec.link)
        val chapters = doc.select(spec.list).mapIndexedNotNull { index, el ->
            val link = readParsedSpec(el, linkSpec)?.trim().orEmpty()
            if (link.isEmpty()) return@mapIndexedNotNull null
            val chapterName = readParsedSpec(el, nameSpec)?.trim()
                ?.ifEmpty { null } ?: "Chapter ${index + 1}"
            SourceChapter(
                id = absolute(link, pageUrl),
                name = chapterName,
                number = ChapterNumbers.parse(chapterName)
            )
        }
        return if (spec.reverse) chapters.asReversed() else chapters
    }

    // ── chapter content ─────────────────────────────────────────────

    override fun chapterContent(chapter: SourceChapter): SourceChapterContent {
        val spec = config.chapter
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
            val contentSpec = SelectorSpec.parse(spec.content)
            val element = doc.selectFirst(contentSpec.css)
                ?: throw SourceException("the chapter text wasn't found on the page")
            val clone = element.clone()
            for (removeSel in spec.remove) {
                runCatching { clone.select(removeSel).remove() }
            }
            fragments += clone.html()
            url = spec.nextPage?.let { nextSel ->
                val parsed = SelectorSpec.parse(nextSel)
                val nextEl = doc.selectFirst(parsed.css) ?: return@let null
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

    private fun readDocSpec(doc: Document, rawSpec: String): String? {
        val spec = SelectorSpec.parse(rawSpec)
        val el = if (spec.css.isBlank()) doc else doc.selectFirst(spec.css) ?: return null
        return readFrom(el, spec)
    }

    private fun readSpec(element: Element, rawSpec: String): String? {
        val spec = SelectorSpec.parse(rawSpec)
        val target = if (spec.css.isBlank()) {
            element
        } else {
            element.selectFirst(spec.css) ?: return null
        }
        return readFrom(target, spec)
    }

    private fun readParsedSpec(element: Element, spec: SelectorSpec): String? {
        val target = if (spec.css.isBlank()) {
            element
        } else {
            element.selectFirst(spec.css) ?: return null
        }
        return readFrom(target, spec)
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
