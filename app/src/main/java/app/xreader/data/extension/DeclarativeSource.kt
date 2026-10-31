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
            // v1.1.0 (design §6.0/§6.1, Shape B): the shared
            // Book-Shape Rule — when the spec declares a link
            // pattern, an item whose link path doesn't end at
            // the book slug is a chapter or a comment target,
            // never a book, however book-like its card looks
            // (comment cards carry avatar images, so neither
            // title nor cover can separate them).
            if (!linkShapeAllows(spec, absolute(link, url))) {
                return@mapNotNull null
            }
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
        // v1.0.9.1: an empty listing read from a page that is
        // still a JavaScript shell is not an empty catalogue —
        // the page never built itself (the browser view was
        // unavailable, switched off, or gave up upstream), and
        // returning an empty page would present "nothing here
        // yet" for a source that simply never loaded. Fail
        // loudly so the caller offers a retry instead. A built
        // page with no results (a search with no hits, a list
        // beyond its end) is not a shell and still returns its
        // honest empty page.
        if (books.isEmpty() && LinkInference.isJsShell(doc)) {
            throw SourceException(
                "the site's page never finished loading — try again"
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
            description = readDescription(doc, spec) ?: book.description,
            coverUrl = spec.cover?.let { readDocSpec(doc, it) }?.ifBlank { null }
                ?.let { absolute(it, bookPageUrl(book)) } ?: book.coverUrl
        )
    }

    override fun chapters(book: SourceBook): List<SourceChapter> {
        val doc = fetchDoc(bookPageUrl(book))
        return parseAllChapters(doc, bookPageUrl(book))
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
            // v1.1.0: the Book-Shape Rule binds related() too —
            // it is the fetchListing loop's twin (design §6.1).
            if (!linkShapeAllows(spec, absolute(link, url))) {
                return@mapNotNull null
            }
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

    /** Details + chapters from ONE book-page fetch — book pages
     * carry both (plus the F1 pagination walk when declared). */
    fun detailsAndChapters(book: SourceBook): Pair<SourceBook, List<SourceChapter>> {
        val url = bookPageUrl(book)
        val doc = fetchDoc(url)
        return readDetails(book, doc, url) to parseAllChapters(doc, url)
    }

    private fun readDetails(
        book: SourceBook,
        doc: Document,
        url: String
    ): SourceBook {
        val spec = config.details
        return book.copy(
            title = readTitle(doc, spec.title) ?: book.title,
            author = spec.author?.let { readDocSpec(doc, it) }?.ifBlank { null } ?: book.author,
            description = readDescription(doc, spec) ?: book.description,
            coverUrl = spec.cover?.let { readDocSpec(doc, it) }?.ifBlank { null }
                ?.let { absolute(it, url) } ?: book.coverUrl
        )
    }

    // ── v1.0.8: progressive chapter loading ─────────────────────

    /** The resumable position of a progressive chapter-list
     * walk, persisted on the Book row between calls.
     * [nextPage] is the next SITE page to fetch — ascending
     * for normal lists, descending for reversed ones; 0 = no
     * walk in progress. [totalPages] is the site's own page
     * count when the book page declares one. [pageSize] is
     * the row count of a full endpoint page, learned from the
     * walk's first fetched page: the ascending short-page =
     * last-page rule needs it across resumes, or a resumed
     * walk could never recognise its final page. */
    data class ChapterWalk(
        val nextPage: Int = 0,
        val totalPages: Int = 0,
        val pageSize: Int = 0
    )

    /** One slice of a progressive load: the book's details
     * (first call only), the cumulative READING-order chapter
     * prefix known so far, the walk's new position, and
     * whether the list is now complete. A chunk that ends on
     * a FAILED page reports complete = false with the cursor
     * still on that page, so a later call retries it — a
     * failure is never mistaken for the end of the book. */
    data class ChapterChunk(
        val details: SourceBook?,
        val chapters: List<SourceChapter>,
        val walk: ChapterWalk,
        val complete: Boolean
    )

    /** True when this source's chapter list can grow a chunk
     * at a time: it paginates, and — for reversed lists —
     * the book page declares its page count, because the
     * walk must start from the LAST page to hand back
     * reading chapter 1 first. Everything else keeps the
     * complete-on-open walk. */
    fun supportsProgressiveChapters(): Boolean {
        val spec = config.details.chapters
        val pagination = spec.pagination ?: return false
        return !spec.reverse || pagination.totalPagesSelector != null
    }

    /** The first chunk of a book: details + at least
     * [minCount] chapters (one site page is the natural
     * unit — slicing a fetched page's rows would not save
     * a single request). */
    fun chaptersFirst(book: SourceBook, minCount: Int): ChapterChunk {
        val url = bookPageUrl(book)
        val doc = fetchDoc(url)
        val filled = readDetails(book, doc, url)
        val session = WalkSession(doc, url)
        session.startFresh()
        session.growTo(minCount)
        return ChapterChunk(
            filled, session.readingRows(), session.walk(), session.complete
        )
    }

    /** Grow a previously started walk to at least [minTotal]
     * chapters. [haveReading] is the stored reading-order
     * prefix; [walk] is the persisted cursor. */
    fun chaptersMore(
        book: SourceBook,
        haveReading: List<SourceChapter>,
        minTotal: Int,
        walk: ChapterWalk
    ): ChapterChunk {
        val url = bookPageUrl(book)
        val doc = fetchDoc(url)
        val session = WalkSession(doc, url)
        session.resume(haveReading, walk)
        session.growTo(minTotal)
        return ChapterChunk(
            null, session.readingRows(), session.walk(), session.complete
        )
    }

    /** One resumable pass over a paginated chapter list.
     * Accumulates rows in SITE order; [readingRows] applies
     * the shared dedupe + reverse finishing, so the caller
     * always receives a reading-order PREFIX — for reversed
     * lists each newly fetched (older) page PREPENDS in site
     * order, which extends the reading prefix at its end and
     * never moves a stored chapter's position. */
    private inner class WalkSession(
        private val bookDoc: Document,
        private val pageUrl: String
    ) {
        private val pagination: ChapterListPagination? =
            config.details.chapters.pagination
        private val reversed: Boolean = config.details.chapters.reverse
        private val bookPageRows: List<SourceChapter> =
            parseChapterRows(bookDoc, pageUrl)
        private val rows = mutableListOf<SourceChapter>()
        private val seen = HashSet<String>()
        private var bookId: String? = null
        private var nextPage = 0
        private var totalPages = 0
        private var firstPageSize = -1
        private var fetchedPages = 0
        private var failed = false
        var complete = false
            private set

        fun startFresh() {
            val p = pagination
            if (p == null) {
                completeWithBookPageRows()
                return
            }
            bookId = p.bookIdIn(bookIdHaystack(bookDoc))
            if (bookId == null) {
                // The pre-F1 behaviour: no id, no walk — the
                // book page's rows are the whole list.
                completeWithBookPageRows()
                return
            }
            if (reversed) {
                totalPages = p.totalPagesIn(bookDoc) ?: 0
                if (totalPages <= 0) {
                    // The selector stopped answering (the site
                    // changed shape): degrade to today's full
                    // walk rather than a list that can never
                    // finish loading.
                    legacyFullWalk(p)
                    return
                }
                nextPage = totalPages
            } else {
                rows += bookPageRows
                seen += bookPageRows.map { it.id }
                nextPage = 2
            }
        }

        fun resume(haveReading: List<SourceChapter>, walk: ChapterWalk) {
            val p = pagination
            if (p == null) {
                completeWithBookPageRows()
                return
            }
            bookId = p.bookIdIn(bookIdHaystack(bookDoc))
            if (bookId == null) {
                // Can't walk right now; hand back what is
                // stored and stay incomplete so a later call
                // can retry. The stored reading prefix flipped
                // (reversed) or as-is (normal) is exactly the
                // accumulated site-order rows.
                val siteRows =
                    if (reversed) haveReading.asReversed() else haveReading
                rows += siteRows
                seen += rows.map { it.id }
                nextPage = walk.nextPage
                totalPages = walk.totalPages
                if (walk.pageSize > 0) firstPageSize = walk.pageSize
                failed = true
                return
            }
            val siteRows =
                if (reversed) haveReading.asReversed() else haveReading
            rows += siteRows
            seen += rows.map { it.id }
            nextPage = walk.nextPage
            totalPages = walk.totalPages
            if (walk.pageSize > 0) firstPageSize = walk.pageSize
            if (nextPage <= 0) complete = true
        }

        fun growTo(minCount: Int) {
            var guard = 0
            while (!complete && !failed &&
                readingRows().size < minCount && guard < MAX_LIST_PAGES
            ) {
                if (!step()) break
                guard++
            }
        }

        fun readingRows(): List<SourceChapter> = finishChapterRows(rows)

        fun walk(): ChapterWalk = ChapterWalk(
            nextPage = if (complete) 0 else nextPage,
            totalPages = totalPages,
            pageSize = if (firstPageSize > 0) firstPageSize else 0
        )

        private fun completeWithBookPageRows() {
            rows += bookPageRows
            seen += bookPageRows.map { it.id }
            nextPage = 0
            complete = true
        }

        private fun legacyFullWalk(p: ChapterListPagination) {
            val merged = bookPageRows.toMutableList()
            walkChapterPages(bookDoc, pageUrl, p, merged)
            rows += merged
            seen += rows.map { it.id }
            nextPage = 0
            complete = true
        }

        /** Fetch exactly one more page in the walk's
         * direction. False = no progress was possible (the
         * walk ended, or the page failed). */
        private fun step(): Boolean {
            if (complete || failed) return false
            val p = pagination ?: run {
                complete = true
                return false
            }
            val id = bookId ?: run {
                complete = true
                return false
            }
            if (reversed) return stepDescending(p, id)
            // Ascending: the F1 walk's stop conditions, one
            // page at a time.
            if (fetchedPages >= MAX_LIST_PAGES) {
                complete = true
                return false
            }
            val fragmentRows = fetchListPage(p, id, nextPage)
            if (fragmentRows == null) {
                failed = true
                return false
            }
            fetchedPages++
            if (fragmentRows.isEmpty()) {
                complete = true
                return false
            }
            val fresh = fragmentRows.filter { it.id !in seen }
            if (fresh.isEmpty()) {
                complete = true
                return false
            }
            rows += fresh
            seen += fresh.map { it.id }
            if (firstPageSize < 0) {
                firstPageSize = fragmentRows.size
            } else if (fragmentRows.size < firstPageSize) {
                nextPage = 0
                complete = true
                return true
            }
            nextPage++
            return true
        }

        private fun stepDescending(
            p: ChapterListPagination,
            id: String
        ): Boolean {
            if (nextPage <= 0) {
                complete = true
                return false
            }
            if (nextPage == 1) {
                // Site page 1 is the book page itself — its
                // rows are the newest slice, prepended last.
                prependFresh(bookPageRows)
                nextPage = 0
                complete = true
                return true
            }
            val fragmentRows = fetchListPage(p, id, nextPage)
            if (fragmentRows == null) {
                failed = true
                return false
            }
            if (fragmentRows.isEmpty()) {
                // The declared total overshot the real list
                // (the site shrank): settle with the book
                // page's rows instead of spinning incomplete
                // forever; a manual Refresh re-derives truth.
                prependFresh(bookPageRows)
                nextPage = 0
                complete = true
                return true
            }
            prependFresh(fragmentRows.filter { it.id !in seen })
            nextPage--
            return true
        }

        private fun prependFresh(fresh: List<SourceChapter>) {
            if (fresh.isEmpty()) return
            rows.addAll(0, fresh)
            seen += fresh.map { it.id }
        }

        /** One pagination-endpoint page, or null when the
         * fetch failed (HTTP error, unparseable body) — the
         * resumable walk's failure signal, distinct from an
         * empty page's end-of-list. */
        private fun fetchListPage(
            p: ChapterListPagination,
            id: String,
            page: Int
        ): List<SourceChapter>? {
            val url = resolveUrl(p.endpointFor(id, page))
            return runCatching {
                val response = context.http.get(url, p.headers)
                if (response.status !in 200..299) return@runCatching null
                val html = paginationFragmentHtml(response.body, p)
                    ?: return@runCatching null
                val fragment = Jsoup.parseBodyFragment(html, pageUrl)
                rowsFromElements(selectFragmentRows(fragment), pageUrl)
            }.getOrElse { error ->
                if (error is kotlinx.coroutines.CancellationException) {
                    throw error
                }
                null
            }
        }
    }

    /** The COMPLETE chapter list (v1.0.6.2, F1): the book page's
     * own rows, then — when the chapter spec declares a
     * [ChapterListPagination] and the page yields its book id —
     * the pagination walk appends every remaining page in site
     * order. Dedupe + reading-order finishing happen ONCE, over
     * the merged set, exactly as the single-page parse did. */
    private fun parseAllChapters(doc: Document, pageUrl: String): List<SourceChapter> {
        val spec = config.details.chapters
        val rows = parseChapterRows(doc, pageUrl).toMutableList()
        val pagination = spec.pagination
        if (pagination != null) {
            walkChapterPages(doc, pageUrl, pagination, rows)
        }
        // v1.0.8.1: a pattern-completed list is already in
        // reading order — it must NOT pass through the reverse
        // finishing the parsed-rows path uses.
        val patterned = patternCompletedRows(doc, pageUrl, rows)
        if (patterned != null) return patterned.distinctBy { it.id }
        return finishChapterRows(rows)
    }

    /**
     * v1.0.8.1: pattern completion — when the chapter spec
     * declares a URL pattern + a stated-total pattern and the
     * book page states MORE chapters than it renders, the full
     * list is generated 1..total in reading order. The rendered
     * rows do NOT lend their names: on the verified site their
     * text carries list chrome (relative dates, button labels),
     * so generated "Chapter n" names keep the list uniform —
     * the parsed rows remain the fallback for a page whose
     * stated total can't be read. Rendered rows under the
     * book's own URL that the pattern doesn't know (a prologue,
     * a special) keep their place at the end rather than being
     * lost; foreign rows (another book's links) never enter.
     * Returns null when the pattern doesn't apply or adds
     * nothing — the parsed rows then stand as the list,
     * exactly as before.
     */
    private fun patternCompletedRows(
        doc: Document,
        pageUrl: String,
        parsedRows: List<SourceChapter>
    ): List<SourceChapter>? {
        val spec = config.details.chapters
        if (spec.urlPattern == null || spec.totalCountPattern == null) {
            return null
        }
        val total = spec.statedTotalIn(doc.body()?.text().orEmpty())
            ?: return null
        if (total <= parsedRows.size || total > MAX_PATTERN_CHAPTERS) {
            return null
        }
        val generated = (1..total).map { n ->
            val url = spec.patternUrl(pageUrl, n) ?: return null
            SourceChapter(
                id = absolute(url, pageUrl),
                name = "Chapter $n",
                number = n.toFloat()
            )
        }
        val generatedIds = generated.mapTo(HashSet()) { it.id }
        val bookPrefix = pageUrl.trimEnd('/') + "/"
        val extras = parsedRows.filter {
            it.id !in generatedIds && it.id.startsWith(bookPrefix)
        }
        return generated + extras
    }

    private fun parseChapterRows(doc: Document, pageUrl: String): List<SourceChapter> =
        rowsFromElements(selectChained(doc, config.details.chapters.list), pageUrl)

    private fun rowsFromElements(
        elements: List<Element>,
        pageUrl: String
    ): List<SourceChapter> {
        val spec = config.details.chapters
        return elements.mapIndexedNotNull { index, el ->
            val link = readSpec(el, spec.link)?.trim().orEmpty()
            if (link.isEmpty()) return@mapIndexedNotNull null
            val chapterName = readSpec(el, spec.name)?.trim()
                ?.ifEmpty { null } ?: "Chapter ${index + 1}"
            SourceChapter(
                id = absolute(link, pageUrl),
                name = chapterName,
                number = ChapterNumbers.parse(chapterName),
                // v1.0.6.3: the row's release date when the spec
                // declares one (novelight's ".date@text"); rides
                // the pagination walk too — it shares this builder.
                dateText = spec.date?.let { readSpec(el, it)?.trim()?.ifEmpty { null } }
            )
        }
    }

    private fun finishChapterRows(rows: List<SourceChapter>): List<SourceChapter> {
        val spec = config.details.chapters
        // v1.0.5 (E3): sites that show a "latest chapters" block
        // AND the full list on the same page (Novelight: 10 + 51
        // rows under one selector) would otherwise hand back the
        // overlap twice. One entry per chapter URL, first
        // occurrence wins, order preserved — the COMPLETE list.
        val unique = rows.distinctBy { it.id }
        return if (spec.reverse) unique.asReversed() else unique
    }

    /**
     * The F1 pagination walk: fetch pages 2, 3, … of the declared
     * endpoint through the source's own HTTP context (declared
     * headers ride along; whitelist/session/rate limit apply),
     * appending each page's NEW rows to [rows] in site order.
     * Stops on: a page that fails to load or parse (the rows
     * gathered so far are kept — a broken page 14 must not lose
     * chapters 1–700), an empty page, a page whose rows were all
     * already seen, a page shorter than the first paginated page
     * (a short page is the last), or the hard page cap.
     */
    private fun walkChapterPages(
        bookDoc: Document,
        pageUrl: String,
        pagination: ChapterListPagination,
        rows: MutableList<SourceChapter>
    ) {
        val bookId = pagination.bookIdIn(bookIdHaystack(bookDoc)) ?: return
        val seen = rows.mapTo(HashSet()) { it.id }
        var firstPageSize = -1
        var page = 2
        var fetched = 0
        while (fetched < MAX_LIST_PAGES) {
            val url = resolveUrl(pagination.endpointFor(bookId, page))
            val fragmentRows = runCatching {
                val response = context.http.get(url, pagination.headers)
                if (response.status !in 200..299) return@runCatching null
                val html = paginationFragmentHtml(response.body, pagination)
                    ?: return@runCatching null
                val fragment = Jsoup.parseBodyFragment(html, pageUrl)
                rowsFromElements(selectFragmentRows(fragment), pageUrl)
            }.getOrElse { error ->
                // Cancellation is the caller's business, never
                // a "page failed" signal.
                if (error is kotlinx.coroutines.CancellationException) {
                    throw error
                }
                null
            } ?: return
            fetched++
            if (fragmentRows.isEmpty()) return
            val fresh = fragmentRows.filter { it.id !in seen }
            if (fresh.isEmpty()) return
            rows += fresh
            seen += fresh.map { it.id }
            if (firstPageSize < 0) {
                firstPageSize = fragmentRows.size
            } else if (fragmentRows.size < firstPageSize) {
                return
            }
            page++
        }
    }

    /** The text the book-id pattern runs over: script bodies
     * first (ids like novelight's `BOOK_ID` live in a script),
     * then the whole serialised page as the fallback. */
    private fun bookIdHaystack(doc: Document): String = buildString {
        for (script in doc.select("script")) {
            append(script.data())
            append('\n')
        }
        append(doc.outerHtml())
    }

    /** A pagination response's rows HTML: the body itself, or the
     * configured JSON field's value when the endpoint answers
     * JSON. Null when a JSON answer carries no usable field —
     * the walk treats that as the end of the list. */
    private fun paginationFragmentHtml(
        body: String,
        pagination: ChapterListPagination
    ): String? {
        val field = pagination.jsonHtmlField ?: return body
        val root = runCatching { ajaxJson.parseToJsonElement(body) }
            .getOrNull() as? JsonObject ?: return null
        return (root[field] as? JsonPrimitive)?.content
            ?.takeIf { it.isNotBlank() }
    }

    /** Row elements of a pagination fragment. Fragments carry the
     * rows WITHOUT the page chrome the book-page selector expects
     * (the `.chapters` container, say), so when the list selector
     * matches nothing, each candidate's row-only tail — the last
     * compound after any combinator — is tried before giving up
     * on the page. */
    private fun selectFragmentRows(fragment: Document): List<Element> {
        val spec = config.details.chapters
        val direct = selectChained(fragment, spec.list)
        if (direct.isNotEmpty()) return direct
        for (candidate in SelectorChains.split(spec.list)) {
            val css = SelectorSpec.parse(candidate).css
            if (css.isBlank()) continue
            val tail = rowOnlySelector(css)
            if (tail == css) continue
            val found = runCatching { fragment.select(tail) }.getOrNull()
            if (!found.isNullOrEmpty()) return found
        }
        return emptyList()
    }

    /** The last compound of a selector — everything after the
     * final descendant/child/sibling combinator. */
    private fun rowOnlySelector(css: String): String =
        css.split(Regex("\\s*[>+~]\\s*|\\s+"))
            .filter { it.isNotBlank() }
            .lastOrNull() ?: css

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
    /** v1.0.7 draw-check fix: the spec's description, healed
     * when it reads as a truncated SEO snippet. Sources saved
     * before the inference fix can carry a meta-tag spec whose
     * text the site itself sliced ("…was a n..."); when the
     * extracted text ends in an ellipsis cut, the page's own
     * synopsis block (the longest synopsis/description/summary
     * block, when longer) is the real text. */
    private fun readDescription(doc: Document, spec: DetailsSpec): String? {
        val extracted = spec.description
            ?.let { readDocSpec(doc, it) }?.ifBlank { null }
            ?: return null
        if (!LinkInference.isTruncatedSnippet(extracted)) {
            return extracted
        }
        val block = doc.select(
            "[class*=synopsis], [class*=description], [class*=summary]"
        ).map { it.text().trim() }
            .filter { it.length >= 40 }
            .maxByOrNull { it.length }
        return block?.takeIf { it.length > extracted.length }
            ?: extracted
    }

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

    /** v1.1.0: the Book-Shape Rule gate shared by listings
     * and related() — no declared pattern, no filter. */
    private fun linkShapeAllows(spec: ListSpec, absoluteLink: String): Boolean {
        val pattern = spec.linkPattern?.takeIf { it.isNotBlank() } ?: return true
        return BookShape.matchesPattern(absoluteLink, pattern)
    }

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
            val value = readFrom(textTarget(doc, spec, el), spec)
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
            val value = readFrom(textTarget(element, spec, target), spec)
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

    /**
     * v1.0.8.1: the element a TEXT read should actually read.
     * Catalogue cards whose first matching anchor is a textless
     * cover link (a common real-world shape — chikari.moe's
     * cards, and the cover-first cards behind the v1.0.8 F5
     * failure) made specs like `a@text` read the cover link's
     * empty text, so every listing item read title-less and was
     * dropped. When the first match's text is blank, rescan the
     * SAME selector within the same container for the first
     * match that carries text. Only text reads are affected:
     * attribute reads (@href/@src/…) keep the first match
     * exactly, chains and their first-value fallback are
     * untouched, and a blank-css self-read never rescans.
     */
    private fun textTarget(
        container: Element,
        spec: SelectorSpec,
        first: Element
    ): Element {
        if (spec.css.isBlank()) return first
        if (spec.attr != null && spec.attr != "text") return first
        if (first.text().isNotBlank()) return first
        return runCatching {
            container.select(spec.css).firstOrNull {
                it.text().isNotBlank()
            }
        }.getOrNull() ?: first
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

        /** v1.0.6.2 (F1): chapter-LIST pagination walk cap — at
         * novelight's 50 rows a page this still covers 2,000
         * chapters past the book page's own slice. */
        const val MAX_LIST_PAGES = 40

        /** v1.0.8.1: pattern-completion sanity cap — a stated
         * total above this is a misread page, not a book. */
        const val MAX_PATTERN_CHAPTERS = 50_000

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
