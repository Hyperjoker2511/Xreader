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
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
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

    /** One cached chapter: its normalised content AND the
     * parsed document it was converted from (v1.2.0, C2 — the
     * host's image pass reads image URLs from the document
     * instead of re-parsing the returned HTML). */
    private class CachedChapter(
        val content: ChapterContent,
        val document: Document
    )

    /** Normalised content of recent chapterContent() calls,
     * keyed by chapter id — the host's fetch path reads this.
     * v1.2.0 (audit #4): BOUNDED access-ordered LRU — the old
     * plain map held every chapter read for the process
     * lifetime (~100 chapters ≈ tens of MB that could never be
     * reclaimed, on a 2 GB tablet the app elsewhere caps at
     * 96 MB). A miss costs nothing but a re-read: callers
     * already treat null as "convert it yourself". */
    private val contentCache =
        object : LinkedHashMap<String, CachedChapter>(16, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, CachedChapter>
            ): Boolean = size > MAX_CONTENT_CACHE_ENTRIES
        }

    fun contentOf(chapterId: String): ChapterContent? =
        synchronized(contentCache) { contentCache[chapterId]?.content }

    /** v1.2.0 (C2): the parsed document of a cached chapter, or
     * null when the chapter isn't cached (evicted / never read
     * through this instance) — callers fall back to parsing the
     * returned HTML themselves, exactly as before. */
    fun documentOf(chapterId: String): Document? =
        synchronized(contentCache) { contentCache[chapterId]?.document }

    private fun cacheChapter(chapterId: String, document: Document) {
        val cached = CachedChapter(
            content = HtmlToContent.fromDocument(document, chapterId),
            document = document
        )
        synchronized(contentCache) { contentCache[chapterId] = cached }
    }

    // ── listings ────────────────────────────────────────────────────

    override fun search(query: String, page: Int): SourceBookPage {
        val spec = config.search
            ?: throw SourceException("“$name” doesn't support search")
        return fetchListing(spec, query, page)
    }

    override fun popular(page: Int): SourceBookPage =
        popular(page, prefetched = null)

    /** v1.2.1 (Defect 4): the popular read, handed a document
     * the CALLER just fetched for the listing URL (a Live
     * Source's derivation fetch) — the A2 threading pattern
     * applied to listings: on a rendered site the page is not
     * RENDERED twice for one catalogue open. The document is
     * used only when its own URL is the listing's URL;
     * anything else is ignored and the page is fetched
     * normally. */
    fun popular(page: Int, prefetched: Document?): SourceBookPage {
        val spec = config.popular ?: config.latest ?: config.search
            ?: throw SourceException("“$name” has no catalogue page")
        return fetchListing(spec, "", page, prefetched)
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

    private fun fetchListing(
        spec: ListSpec,
        query: String,
        page: Int,
        prefetched: Document? = null
    ): SourceBookPage {
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
        } else if (prefetched != null && documentUrl(prefetched) == url) {
            // v1.2.1 (Defect 4): the caller's just-fetched
            // document for this exact listing URL — no second
            // fetch (see popular(page, prefetched)).
            prefetched
        } else {
            fetchDoc(url)
        }
        val listing = readListingRows(doc, spec, url)
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
        if (listing.books.isEmpty() && spec.jsonPath == null &&
            LinkInference.isJsShell(doc)
        ) {
            throw SourceException(
                "the site's page never finished loading — try again"
            )
        }
        return listing
    }

    /**
     * v1.2.3: the row read of one listing document — the half
     * of [fetchListing] that turns a fetched page into books.
     * Split out so the probe can VERIFY a derived spec against
     * the very document it was derived from (no second fetch,
     * no render repeated), and so DOM specs and embedded-JSON
     * specs share one hygiene path (title cleaning, the
     * Book-Shape link pattern, cover resolution).
     */
    fun readListingRows(
        doc: Document,
        spec: ListSpec,
        pageUrl: String
    ): SourceBookPage {
        if (spec.jsonPath != null) {
            return readJsonListingRows(doc, spec, pageUrl)
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
            if (!linkShapeAllows(spec, absolute(link, pageUrl))) {
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
                    TitleCleaner.slugTitle(absolute(link, pageUrl))
                        ?: return@mapNotNull null
                else -> cleaned
            }
            SourceBook(
                id = absolute(link, pageUrl),
                title = title,
                author = spec.author?.let { readSpec(el, it)?.trim()?.ifEmpty { null } },
                coverUrl = spec.cover?.let { readSpec(el, it)?.trim()?.ifEmpty { null } }
                    ?.let { absolute(it, pageUrl) }
            )
        }
        val hasNext = spec.nextPage?.let { sel ->
            selectChained(doc, sel).isNotEmpty()
        } ?: false
        return SourceBookPage(books, hasNext)
    }

    /**
     * v1.2.3: the embedded-JSON row read — the records of the
     * array at [ListSpec.jsonPath] in the page's embedded
     * payload become the listing's books, through the same
     * title hygiene and Book-Shape pattern as DOM rows. A page
     * whose payload is absent or whose array is misshapen
     * reads as an EMPTY listing here; the fetch-level checks
     * (and the health drive) decide what an empty read means —
     * the read itself never invents rows from a DOM that was
     * never the data.
     */
    private fun readJsonListingRows(
        doc: Document,
        spec: ListSpec,
        pageUrl: String
    ): SourceBookPage {
        val path = spec.jsonPath ?: return SourceBookPage(emptyList(), false)
        var array: kotlinx.serialization.json.JsonArray? = null
        var parent: kotlinx.serialization.json.JsonObject? = null
        for (root in EmbeddedJson.rootsForRead(doc)) {
            val found = EmbeddedJson.resolve(root, path)
                as? kotlinx.serialization.json.JsonArray
            if (found != null) {
                array = found
                val parentPath = path.substringBeforeLast('.', "")
                parent = if (parentPath.isEmpty()) {
                    root as? kotlinx.serialization.json.JsonObject
                } else {
                    EmbeddedJson.resolve(root, parentPath)
                        as? kotlinx.serialization.json.JsonObject
                }
                break
            }
        }
        val records = array ?: return SourceBookPage(emptyList(), false)
        val books = records.mapNotNull { element ->
            val record = element as? kotlinx.serialization.json.JsonObject
                ?: return@mapNotNull null
            val rawTitle = EmbeddedJson.stringAt(record, spec.title)
                ?.trim().orEmpty()
            val rawLink = EmbeddedJson.stringAt(record, spec.link)
                ?.trim().orEmpty()
            if (rawTitle.isEmpty() || rawLink.isEmpty()) {
                return@mapNotNull null
            }
            val link = spec.jsonLinkTemplate
                ?.replace("{value}", rawLink) ?: rawLink
            if (!linkShapeAllows(spec, absolute(link, pageUrl))) {
                return@mapNotNull null
            }
            val cleaned = TitleCleaner.clean(rawTitle)
            val title = when {
                cleaned.isBlank() -> rawTitle
                TitleCleaner.isDateOnly(cleaned) ->
                    TitleCleaner.slugTitle(absolute(link, pageUrl))
                        ?: return@mapNotNull null
                else -> cleaned
            }
            SourceBook(
                id = absolute(link, pageUrl),
                title = title,
                author = spec.author?.let {
                    EmbeddedJson.stringAt(record, it)?.trim()?.ifEmpty { null }
                },
                coverUrl = spec.cover?.let {
                    EmbeddedJson.stringAt(record, it)?.trim()?.ifEmpty { null }
                }?.let { raw ->
                    // v1.3.2: a cover template expands a bare
                    // storage path into its serving URL first
                    // (fenrirealm's "storage/…" covers).
                    val expanded = spec.jsonCoverTemplate
                        ?.replace("{value}", raw) ?: raw
                    absolute(expanded, pageUrl)
                }
            )
        }
        // Paging evidence, when the payload states it: the
        // object holding the array often carries a pagination
        // block beside it (novelbuddy: total_pages / has_next).
        val pagination = parent?.get("pagination")
            as? kotlinx.serialization.json.JsonObject
        val hasNext = pagination?.let { block ->
            (block["has_next"] as? kotlinx.serialization.json.JsonPrimitive)
                ?.contentOrNull?.toBoolean()
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
    fun detailsAndChapters(book: SourceBook): Pair<SourceBook, List<SourceChapter>> =
        detailsAndChapters(book, prefetched = null)

    /** v1.2.0 (A2): the same one-fetch read, handed a document
     * the CALLER just fetched for this exact book page (a Live
     * Source's derivation fetch) so the page isn't fetched —
     * on a rendered site, RENDERED — twice for one open. The
     * prefetched document is used ONLY when its own URL is the
     * book page's URL; anything else is ignored and the page is
     * fetched normally. No other behaviour changes. */
    fun detailsAndChapters(
        book: SourceBook,
        prefetched: Document?
    ): Pair<SourceBook, List<SourceChapter>> {
        val url = bookPageUrl(book)
        val doc = prefetched?.takeIf { documentUrl(it) == url }
            ?: fetchDoc(url)
        return readDetails(book, doc, url) to parseAllChapters(doc, url)
    }

    /** A document's own URL (its parse-time base), for the
     * prefetched-document identity checks. */
    private fun documentUrl(doc: Document): String =
        doc.location().ifBlank { doc.baseUri() }

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
        // v1.3.0 delta (V120-CHECK 49): a single-shot endpoint
        // (its URL never varies by page — NovelBuddy's whole
        // chapter list in one answer) is progressive too: the
        // payload may arrive whole, but only the first slice
        // is persisted on the open path; the rest lands on
        // the first demand past the slice.
        if (pagination.isSingleShot()) return true
        return !spec.reverse || pagination.totalPagesSelector != null
    }

    /** The first slice a single-shot list opens with. The
     * spec (item 49): a book opens after its first chunk —
     * never gated on ingesting the whole list. */
    private fun singleShotSlice(minCount: Int): Int =
        maxOf(minCount, config.details.chapters.pagination?.pageSize?.takeIf { it > 0 } ?: 500)

    /** The first chunk of a book: details + at least
     * [minCount] chapters (one site page is the natural
     * unit — slicing a fetched page's rows would not save
     * a single request). */
    fun chaptersFirst(book: SourceBook, minCount: Int): ChapterChunk {
        val url = bookPageUrl(book)
        val doc = fetchDoc(url)
        val filled = readDetails(book, doc, url)
        if (config.details.chapters.pagination?.isSingleShot() == true) {
            // One fetch carries the whole list; persist only
            // the first slice now (item 49) and let the first
            // chaptersMore complete it from a re-fetch.
            val all = parseAllChapters(doc, url)
            val slice = singleShotSlice(minCount)
            val complete = all.size <= slice
            return ChapterChunk(
                filled,
                if (complete) all else all.take(slice),
                ChapterWalk(
                    nextPage = if (complete) 0 else 2,
                    totalPages = 1,
                    pageSize = all.size
                ),
                complete
            )
        }
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
        if (config.details.chapters.pagination?.isSingleShot() == true &&
            walk.pageSize > 0
        ) {
            // The completing re-fetch: the endpoint returns
            // the whole list again; the caller's reconcile
            // keeps the stored prefix and appends the rest.
            val all = parseAllChapters(fetchDoc(url), url)
            return ChapterChunk(
                null, all,
                ChapterWalk(nextPage = 0, totalPages = 1, pageSize = all.size),
                complete = true
            )
        }
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
        /** v1.3.2 (scope A4): the v1.3.1 seed-and-start-at-2
         * behaviour, armed as the fallback when this walk's
         * page-1 fetch fails on a DOM/fragment road. */
        private var seedFallbackArmed = false
        /** v1.3.0 (E1): the chapter total a JSON endpoint
         * states; 0 = not stated. Ends the walk exactly. */
        private var knownTotal = 0
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
            } else if (p.jsonArrayPath != null) {
                // v1.3.0 delta (V120-CHECK 46): a JSON-RECORDS
                // endpoint (chikari's limit/offset API and its
                // kin) owns page 1 — the "book page" is an API
                // document carrying none of the list's rows.
                // Seeding from it and starting at page 2
                // silently DROPPED the first page: chikari
                // lost chapters 1–500 (of 1,923), so position
                // 1 wore chapter 501's text under a "Chapter 1"
                // label. No seeding; the walk starts at page 1
                // like the full walk does.
                nextPage = 1
            } else {
                // v1.3.2 (items 71/80, scope A4): DOM/fragment
                // pagination starts at PAGE 1 too. v1.3.1
                // seeded the book page's slice and fetched
                // from page 2, assuming the slice WAS page 1 —
                // on the NovelFire family the book page's slice
                // is a later page, so the list opened at
                // chapter 101 and page 1 was never fetched.
                // The walk now fetches page 1 itself; when it
                // turns out to BE the book slice, its rows
                // dedupe in step() and the walk walks on. If
                // the page-1 fetch FAILS, step() falls back to
                // the seeded slice and continues from page 2 —
                // the pre-v1.3.2 behaviour — rather than
                // failing a list that used to work.
                seedFallbackArmed = bookPageRows.isNotEmpty()
                nextPage = 1
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

        fun readingRows(): List<SourceChapter> =
            finishChapterRows(rows, pageUrl)

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
            val fetchedPage = fetchListPage(p, id, nextPage)
            if (fetchedPage == null) {
                // v1.3.2 (scope A4): a failed PAGE-1 fetch on a
                // DOM/fragment road with a book-page slice in
                // hand degrades to the pre-v1.3.2 behaviour —
                // seed the slice, walk from page 2 — instead of
                // failing a list that used to work. Any other
                // failed page ends the chunk here, resumable.
                if (seedFallbackArmed && nextPage == 1) {
                    seedFallbackArmed = false
                    rows += bookPageRows
                    seen += bookPageRows.map { it.id }
                    nextPage = 2
                    return true
                }
                failed = true
                return false
            }
            fetchedPage.total?.let { if (it > 0) knownTotal = it }
            val fragmentRows = fetchedPage.rows
            fetchedPages++
            if (fragmentRows.isEmpty()) {
                // v1.3.2: an empty page 1 with a slice in hand
                // is the same degradation — the endpoint's
                // first page is not where this site keeps its
                // rows; seed and walk on from page 2.
                if (seedFallbackArmed && nextPage == 1) {
                    seedFallbackArmed = false
                    rows += bookPageRows
                    seen += bookPageRows.map { it.id }
                    nextPage = 2
                    return true
                }
                complete = true
                return false
            }
            seedFallbackArmed = false
            val fresh = fragmentRows.filter { it.id !in seen }
            if (fresh.isEmpty()) {
                complete = true
                return false
            }
            rows += fresh
            seen += fresh.map { it.id }
            // v1.3.0 (E1): an endpoint that states its total
            // ends the walk the moment the rows reach it —
            // exact, no short-page guessing.
            if (knownTotal > 0 && seen.size >= knownTotal) {
                nextPage = 0
                complete = true
                return true
            }
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
            val fetchedPage = fetchListPage(p, id, nextPage)
            if (fetchedPage == null) {
                failed = true
                return false
            }
            fetchedPage.total?.let { if (it > 0) knownTotal = it }
            val fragmentRows = fetchedPage.rows
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
        ): PageFetch? {
            val slug = ChapterListPagination.bookSlugOf(pageUrl)
            val url = resolveUrl(p.endpointFor(id, page, slug, pageUrl))
            return runCatching {
                val response = if (
                    p.method.equals("POST", ignoreCase = true) &&
                    p.body != null
                ) {
                    context.http.post(
                        url, p.bodyFor(id, page, slug, pageUrl) ?: "",
                        headers = p.headers
                    )
                } else {
                    context.http.get(url, p.headers)
                }
                if (response.status !in 200..299) return@runCatching null
                if (p.jsonArrayPath != null) {
                    val root = runCatching {
                        ajaxJson.parseToJsonElement(response.body)
                    }.getOrNull() ?: return@runCatching null
                    val rows = jsonEndpointRows(root, pageUrl, id)
                    val total = p.jsonTotalPath?.let { path ->
                        (EmbeddedJson.resolve(root, path)
                            as? kotlinx.serialization.json.JsonPrimitive)
                            ?.contentOrNull?.toIntOrNull()
                    }
                    PageFetch(rows, total)
                } else {
                    val html = paginationFragmentHtml(response.body, p)
                        ?: return@runCatching null
                    val fragment = Jsoup.parseBodyFragment(html, pageUrl)
                    PageFetch(
                        rowsFromElements(selectFragmentRows(fragment), pageUrl),
                        null
                    )
                }
            }.getOrElse { error ->
                if (error is kotlinx.coroutines.CancellationException) {
                    throw error
                }
                null
            }
        }
    }

    /** One endpoint page of a chapter-list walk: its rows, and
     * the response's stated chapter total when the endpoint
     * declares one (v1.3.0, E1 — API chapter lists). */
    private data class PageFetch(
        val rows: List<SourceChapter>,
        val total: Int?
    )

    /**
     * v1.3.0 (E1): chapter rows from a JSON endpoint response —
     * the chapter array at the pagination spec's
     * [ChapterListPagination.jsonArrayPath], read with the
     * chapter spec's JSON record fields. A record's link may be
     * a full URL, a site path, or a bare id/slug the spec's
     * link template expands into the chapter URL. Same
     * admission rule as every other row source: the chapter
     * must live on the site's own host.
     */
    private fun jsonEndpointRows(
        root: kotlinx.serialization.json.JsonElement,
        pageUrl: String,
        bookId: String
    ): List<SourceChapter> {
        val spec = config.details.chapters
        val pagination = spec.pagination ?: return emptyList()
        val path = pagination.jsonArrayPath ?: return emptyList()
        val array = EmbeddedJson.resolve(root, path)
            as? kotlinx.serialization.json.JsonArray
            ?: return emptyList()
        return array.mapIndexedNotNull { index, element ->
            val record = element as? kotlinx.serialization.json.JsonObject
                ?: return@mapIndexedNotNull null
            val rawLink = EmbeddedJson.stringAt(record, spec.jsonLinkField)
                ?.trim().orEmpty()
            if (rawLink.isEmpty()) return@mapIndexedNotNull null
            val link = spec.jsonLinkTemplate?.let { template ->
                template
                    .replace("{value}", rawLink)
                    .replace(
                        "{bookSlug}",
                        ChapterListPagination.bookSlugOf(pageUrl)
                    )
                    .replace("{bookId}", bookId)
            } ?: rawLink
            val absoluteLink = absolute(link, pageUrl)
            if (!LinkInference.isSameSiteHost(pageUrl, absoluteLink)) {
                return@mapIndexedNotNull null
            }
            val rawName = jsonRecordName(record, spec, index)
            // v1.3.2 (item 78): JSON roads de-glue too — name
            // and date are separate fields on every road.
            val (name, gluedDate) = cleanChapterName(rawName)
            SourceChapter(
                id = absoluteLink,
                name = name,
                number = ChapterNumbers.parse(name)
                    ?: recordNumber(record, spec),
                dateText = spec.jsonDateField?.let { field ->
                    EmbeddedJson.stringAt(record, field)
                        ?.trim()?.ifEmpty { null }
                } ?: gluedDate
            )
        }
    }

    /**
     * A JSON chapter record's display name: its name field,
     * prefixed with its own stated number when the name is a
     * bare title (chikari's records: number 1 + title
     * "Nightmare Begins" — the site shows "Chapter 1:
     * Nightmare Begins"). A name that already carries its
     * number (novelbuddy's "Chapter 3229 …") stands as-is;
     * a record with neither falls back to its position.
     */
    private fun jsonRecordName(
        record: kotlinx.serialization.json.JsonObject,
        spec: ChapterListSpec,
        index: Int
    ): String {
        val raw = EmbeddedJson.stringAt(record, spec.jsonNameField)
            ?.trim()?.ifEmpty { null }
        val number = recordNumber(record, spec)
        if (raw == null) {
            return recordNumberName(record, spec) ?: "Chapter ${index + 1}"
        }
        if (number != null && ChapterNumbers.parse(raw) == null) {
            val label = recordNumberName(record, spec) ?: return raw
            return "$label: $raw"
        }
        return raw
    }

    /** A JSON chapter record's own number, when it carries one
     * in a recognised number field — API lists state it even
     * when the row's name is a bare title (chikari). */
    private fun recordNumber(
        record: kotlinx.serialization.json.JsonObject,
        spec: ChapterListSpec
    ): Float? {
        for (field in listOf("number", "chapterNumber", "chapter_number")) {
            val value = (EmbeddedJson.resolve(record, field)
                as? kotlinx.serialization.json.JsonPrimitive)
                ?.contentOrNull?.toFloatOrNull()
            if (value != null) return value
        }
        return null
    }

    /** "Chapter n" from a record's number field, for records
     * whose name field holds a bare chapter title — the list
     * shows the number AND the title, as the sites do. */
    private fun recordNumberName(
        record: kotlinx.serialization.json.JsonObject,
        spec: ChapterListSpec
    ): String? {
        val number = recordNumber(record, spec) ?: return null
        val asInt = number.toInt()
        return if (number == asInt.toFloat()) "Chapter $asInt" else "Chapter $number"
    }

    /** The COMPLETE chapter list (v1.0.6.2, F1): the book page's
     * own rows, then — when the chapter spec declares a
     * [ChapterListPagination] and the page yields its book id —
     * the pagination walk appends every remaining page in site
     * order. Dedupe + reading-order finishing happen ONCE, over
     * the merged set, exactly as the single-page parse did. */
    private fun parseAllChapters(doc: Document, pageUrl: String): List<SourceChapter> {
        val spec = config.details.chapters
        // v1.3.2 (item 85): when the spec declares a JSON
        // chapters ENDPOINT (pagination.jsonArrayPath), that
        // endpoint is the road — the book page's DOM rows do
        // NOT seed the list. NovelBin's book pages carry a
        // loose `a` chapter selector (the chapter anchors
        // share the page with the site's nav and genre menu);
        // seeding from it put ~49 menu rows ahead of the API's
        // real list. The DOM rows return only as the fallback
        // when the endpoint answers nothing at all.
        val endpointIsTheRoad = spec.pagination?.jsonArrayPath != null
        val domRows = parseChapterRows(doc, pageUrl)
        val rows = if (endpointIsTheRoad) {
            mutableListOf()
        } else {
            domRows.toMutableList()
        }
        // v1.2.3: the embedded-JSON chapter rows merge in —
        // the payload's list runs longer than the rendered
        // window on Next.js book pages (novelbuddy: latest 50
        // in JSON vs ~21 anchors). One entry per chapter URL
        // is enforced downstream (finishChapterRows), DOM rows
        // first so their richer data (dates) wins a duplicate.
        if (spec.jsonPath != null) {
            rows += jsonChapterRows(doc, pageUrl)
        }
        // v1.3.0 (E1): a DIRECT JSON endpoint page — the book
        // page's whole payload is an API answer whose chapter
        // array the spec names; when it yields rows they ARE
        // the list (the DOM of such a page is a shell of the
        // payload, never a second source of rows).
        val endpointPath = spec.jsonEndpointPath
        var seededFromEndpoint = false
        if (endpointPath != null) {
            val endpointRows = directJsonChapterRows(doc, pageUrl, endpointPath)
            if (endpointRows.isNotEmpty()) {
                rows.clear()
                rows += endpointRows
                seededFromEndpoint = true
            }
        }
        val pagination = spec.pagination
        if (pagination != null) {
            walkChapterPages(
                doc, pageUrl, pagination, rows,
                seededFromEndpoint = seededFromEndpoint
            )
        }
        // v1.3.2 (item 85): the endpoint road answered
        // nothing — only then do the book page's DOM rows
        // stand as the list (the pre-v1.3.2 degradation).
        if (endpointIsTheRoad && rows.isEmpty()) {
            rows += domRows
        }
        // v1.0.8.1: a pattern-completed list is already in
        // reading order — it must NOT pass through the reverse
        // finishing the parsed-rows path uses.
        val patterned = patternCompletedRows(doc, pageUrl, rows)
        if (patterned != null) return ChapterIdentity.merge(patterned)
        return finishChapterRows(rows, pageUrl)
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
        if (spec.urlPattern == null) {
            return null
        }
        // WS3 (class D): the total comes from the stated-total
        // text pattern when the spec declares one (the chikari
        // lane), or from the learner's numeric total (a strict-
        // pattern window completed from the page's own links).
        val total = spec.statedTotalIn(doc.body()?.text().orEmpty())
            ?: spec.totalCount?.takeIf { it > 0 }
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

    /**
     * v1.3.0 (E1): chapter rows from a DIRECT JSON payload —
     * the array at [ChapterListSpec.jsonEndpointPath] in the
     * page's own JSON (bare body or embedded), read with the
     * spec's JSON record fields and link template.
     */
    private fun directJsonChapterRows(
        doc: Document,
        pageUrl: String,
        path: String
    ): List<SourceChapter> {
        val spec = config.details.chapters
        for (root in EmbeddedJson.rootsForRead(doc)) {
            val array = EmbeddedJson.resolve(root, path)
                as? kotlinx.serialization.json.JsonArray ?: continue
            val bookId = spec.pagination
                ?.bookIdIn(bookIdHaystack(doc)) ?: ""
            return array.mapIndexedNotNull { index, element ->
                // v1.3.0: a NAME-LIST endpoint (novelarchive: the
                // book payload's chapter_names) — the array's
                // items are bare strings, each the chapter's
                // name; its link is built from the row's 1-based
                // position via the template's {index}.
                if (element is kotlinx.serialization.json.JsonPrimitive) {
                    val stringName = element.contentOrNull
                        ?.trim().orEmpty()
                    val template = spec.jsonLinkTemplate
                    if (stringName.isEmpty() || template == null) {
                        return@mapIndexedNotNull null
                    }
                    val link = template
                        .replace("{index}", (index + 1).toString())
                        .replace("{value}", stringName)
                        .replace(
                            "{bookSlug}",
                            ChapterListPagination.bookSlugOf(pageUrl)
                        )
                        .replace("{bookId}", bookId)
                    val absoluteLink = absolute(link, pageUrl)
                    if (!LinkInference.isSameSiteHost(
                            pageUrl, absoluteLink
                        )
                    ) {
                        return@mapIndexedNotNull null
                    }
                    return@mapIndexedNotNull SourceChapter(
                        id = absoluteLink,
                        name = stringName,
                        number = (index + 1).toFloat()
                    )
                }
                val record = element as? kotlinx.serialization.json.JsonObject
                    ?: return@mapIndexedNotNull null
                val rawLink = EmbeddedJson.stringAt(record, spec.jsonLinkField)
                    ?.trim().orEmpty()
                if (rawLink.isEmpty()) return@mapIndexedNotNull null
                val link = spec.jsonLinkTemplate?.let { template ->
                    template
                        .replace("{value}", rawLink)
                        .replace("{index}", (index + 1).toString())
                        .replace(
                            "{bookSlug}",
                            ChapterListPagination.bookSlugOf(pageUrl)
                        )
                        .replace("{bookId}", bookId)
                } ?: rawLink
                val absoluteLink = absolute(link, pageUrl)
                if (!LinkInference.isSameSiteHost(pageUrl, absoluteLink)) {
                    return@mapIndexedNotNull null
                }
                val rawName = jsonRecordName(record, spec, index)
                // v1.3.2 (item 78): de-glue on this road too.
                val (name, gluedDate) = cleanChapterName(rawName)
                SourceChapter(
                    id = absoluteLink,
                    name = name,
                    number = ChapterNumbers.parse(name)
                        ?: recordNumber(record, spec),
                    dateText = spec.jsonDateField?.let { field ->
                        EmbeddedJson.stringAt(record, field)
                            ?.trim()?.ifEmpty { null }
                    } ?: gluedDate
                )
            }
        }
        return emptyList()
    }

    private fun parseChapterRows(doc: Document, pageUrl: String): List<SourceChapter> =
        rowsFromElements(selectChained(doc, config.details.chapters.list), pageUrl)

    /**
     * v1.2.3: chapter rows from the book page's embedded JSON
     * (see [ChapterListSpec.jsonPath]). Same admission rules
     * as DOM rows: the link must exist and stay on the site's
     * own host (WS3 class G — a foreign row inside the data
     * is an ad, not a chapter).
     */
    private fun jsonChapterRows(
        doc: Document,
        pageUrl: String
    ): List<SourceChapter> {
        val spec = config.details.chapters
        val path = spec.jsonPath ?: return emptyList()
        var array: kotlinx.serialization.json.JsonArray? = null
        for (root in EmbeddedJson.rootsForRead(doc)) {
            val found = EmbeddedJson.resolve(root, path)
                as? kotlinx.serialization.json.JsonArray
            if (found != null) {
                array = found
                break
            }
        }
        val records = array ?: return emptyList()
        return records.mapIndexedNotNull { index, element ->
            val record = element as? kotlinx.serialization.json.JsonObject
                ?: return@mapIndexedNotNull null
            val link = EmbeddedJson.stringAt(record, spec.jsonLinkField)
                ?.trim().orEmpty()
            if (link.isEmpty()) return@mapIndexedNotNull null
            if (!LinkInference.isSameSiteHost(pageUrl, absolute(link, pageUrl))) {
                return@mapIndexedNotNull null
            }
            val rawName = EmbeddedJson.stringAt(record, spec.jsonNameField)
                ?.trim()?.ifEmpty { null } ?: "Chapter ${index + 1}"
            // v1.3.2 (item 78): de-glue on this road too.
            val (name, gluedDate) = cleanChapterName(rawName)
            SourceChapter(
                id = absolute(link, pageUrl),
                name = name,
                number = ChapterNumbers.parse(name),
                dateText = gluedDate
            )
        }
    }

    private fun rowsFromElements(
        elements: List<Element>,
        pageUrl: String
    ): List<SourceChapter> {
        val spec = config.details.chapters
        return elements.mapIndexedNotNull { index, el ->
            val link = readSpec(el, spec.link)?.trim().orEmpty()
            if (link.isEmpty()) return@mapIndexedNotNull null
            // WS3 (class G): chapter rows admit only the site's
            // own host — a foreign-host ad row inside the list
            // markup must never become a chapter of this book
            // (it became "chapter 1" on one batch site and the
            // whitelist then killed the whole probe).
            if (!LinkInference.isSameSiteHost(pageUrl, absolute(link, pageUrl))) {
                return@mapIndexedNotNull null
            }
            val rawName = readSpec(el, spec.name)?.trim()
                ?.ifEmpty { null } ?: "Chapter ${index + 1}"
            // v1.3.2 (items 71/78): name and date are separate
            // fields — a row whose text glues them together
            // ("Chapter 156: Totally Awful 3 days ago", the
            // NovelFire family's "101Chapter 101 – Unexpected
            // Clue5 years ago") is split here, at the builder
            // every DOM road shares.
            val (chapterName, gluedDate) = cleanChapterName(rawName)
            SourceChapter(
                id = absolute(link, pageUrl),
                name = chapterName,
                number = ChapterNumbers.parse(chapterName),
                // v1.0.6.3: the row's release date when the spec
                // declares one (novelight's ".date@text"); rides
                // the pagination walk too — it shares this builder.
                dateText = spec.date?.let {
                    readSpec(el, it)?.trim()?.ifEmpty { null }
                } ?: gluedDate
            )
        }
    }

    /**
     * v1.3.2 (items 71/78): the shared chapter-name hygiene
     * for every row builder — split a glued trailing date
     * into its own field ([ChapterDateText.splitGluedDate])
     * and drop a duplicated leading number ("101Chapter 101
     * – …" — the site's row index glued onto the chapter's
     * own label). Returns (clean name, split-off date text).
     */
    private fun cleanChapterName(raw: String): Pair<String, String?> {
        val (split, date) =
            app.xreader.domain.reader.ChapterDateText.splitGluedDate(raw)
        val deduped = DUPLICATE_NUMBER_PREFIX.find(split)?.let { match ->
            if (match.groupValues[1] == match.groupValues[2]) {
                // Drop only the glued prefix number, keeping
                // the chapter's own label: "101Chapter 101 – X"
                // → "Chapter 101 – X".
                val rest = split.substring(
                    match.groups[2]?.range?.last?.plus(1) ?: match.range.last + 1
                )
                "Chapter ${match.groupValues[2]}$rest"
            } else {
                split
            }
        } ?: split
        return deduped to date
    }

    /** The chapter-shape gate (item 50): can this row be a
     * chapter at all? Call-to-action labels ("Start Reading"
     * and kin) carry no chapter number; tag/genre/category
     * links point at book collections. Either shape in a
     * chapter list is page furniture, not a chapter. */
    private fun isChapterShaped(row: SourceChapter): Boolean {
        if (row.number == null &&
            row.name.trim().lowercase() in CHAPTER_CTA_LABELS
        ) {
            return false
        }
        val path = runCatching { java.net.URI(row.id).path }
            .getOrNull() ?: return true
        val segments = path.split('/').filter { it.isNotBlank() }
        return segments.none { it.lowercase() in LISTING_PATH_SEGMENTS }
    }

    private fun finishChapterRows(
        rows: List<SourceChapter>,
        pageUrl: String
    ): List<SourceChapter> {
        val spec = config.details.chapters
        // v1.3.0 delta (V120-CHECK 50): the chapter-shape gate.
        // A book page's furniture — the "Start Reading" call to
        // action, the book's own tag chips (Magic, Action,
        // Transmigration…) — matches loose chapter selectors
        // on DOM roads and renders as chapter rows (with
        // download buttons) ahead of the real list. Two
        // road-independent shapes can never be chapters: a
        // call-to-action label carrying no chapter number, and
        // a link into a listing path (tag / genre / category
        // collections list BOOKS, never chapters). API roads
        // never produce either shape, so the gate is free for
        // them; DOM roads stop leaking furniture here, at the
        // one choke point every declarative list passes.
        // v1.3.2 (item 85): the shape-consistency gate runs
        // over the CTA/tag survivors — a row with no chapter
        // number anywhere survives only when its URL wears
        // the book's own chapter-URL shape (nav and genre
        // menu anchors never do).
        val shaped = ChapterShape.filterConsistent(
            rows.filter { isChapterShaped(it) }, pageUrl
        )
        // v1.3.2 (items 64/80/82, scope B/R3): the fragments a
        // list arrives in — book-page slice, embedded JSON,
        // pagination pages, an API answer — MERGE by canonical
        // chapter identity (the richest record wins a
        // duplicate: the dated API row beats the dateless DOM
        // row for the same chapter) and, when the list's
        // numbers are unique and near-complete, ORDER BY
        // NUMBER: a newest-first fragment stitched onto an
        // ascending one (WoopRead, LibRead) settles into
        // reading order here instead of reaching the store
        // stitched. Lists whose numbers can't carry the order
        // fall through to the site-order finishing below,
        // unchanged.
        return ChapterIdentity.settle(shaped) { merged ->
            settleSiteOrder(merged, spec)
        }
    }

    /** The pre-v1.3.2 finishing, kept as the fallback for
     * lists whose numbers cannot carry their order: the
     * config's reverse flag, then the reading-order
     * invariant flip. */
    private fun settleSiteOrder(
        unique: List<SourceChapter>,
        spec: ChapterListSpec
    ): List<SourceChapter> {
        val ordered = if (spec.reverse) unique.asReversed() else unique
        // v1.1.2: the reading-order invariant. Everything
        // downstream — the stored Chapter.index the reconciler
        // assigns from list position, saved progress, the
        // Library hero, the Continue pill — treats position as
        // reading order. A list that is still strongly
        // descending in its chapter numbers after the config's
        // own reverse handling is a list whose order was
        // misread (a derived config whose newest-first
        // detection missed, a lane whose flag is wrong): flip
        // it here, at the one choke point every declarative
        // list passes through, so no stored book can be upside
        // down. ChapterOrder's bar is deliberately high (see
        // its doc) — an ordinary list is never touched.
        return if (ChapterOrder.isStronglyDescending(ordered)) {
            ordered.asReversed()
        } else {
            ordered
        }
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
        rows: MutableList<SourceChapter>,
        seededFromEndpoint: Boolean = false
    ) {
        val bookId = pagination.bookIdIn(bookIdHaystack(bookDoc)) ?: return
        val seen = rows.mapTo(HashSet()) { it.id }
        var firstPageSize = -1
        var fetched = 0
        // v1.3.0 (E1): page 1 of a slug/id-addressed endpoint is
        // NOT the book page — API chapter lists start at page 1
        // (chikari's paged list, novelping's JSON chapters).
        // The book page's rows are already in [rows]/[seen], so
        // a page-1 fetch simply dedupes against them. EXCEPT
        // when the book page IS the endpoint's first page —
        // [seededFromEndpoint]: the spec's jsonEndpointPath
        // seeded page 1's payload as the book rows — where
        // the walk starts at page 2 as it always did.
        //
        // v1.3.2 (items 71/80, scope A4): where the walk
        // starts depends on what the endpoint IS:
        //  - a JSON API road (jsonArrayPath): the book page
        //    is not a page of the endpoint at all — start at
        //    page 1 (the E1 rule above).
        //  - a {bookUrl} TOC pager (the NovelFire family):
        //    the book page embeds only a WINDOW of the table
        //    of contents — on device it was the pager's page 2
        //    (the list opened at chapter 101 and page 1 was
        //    never fetched) — start at page 1; when page 1
        //    does equal the book slice, its rows dedupe
        //    against the seeds and the walk walks on (the
        //    loop's first-page tolerance below).
        //  - a {bookId} AJAX continuation (Novelight, the F1
        //    road): the book page IS page 1 and the endpoint
        //    serves the REST — its page 1 may not even exist —
        //    start at page 2, as the road always did.
        var page = when {
            seededFromEndpoint -> 2
            pagination.jsonArrayPath != null -> 1
            pagination.urlTemplate.contains("{bookUrl}") -> 1
            else -> 2
        }
        val startPage = page
        // v1.3.0 (E1): the chapter total a JSON endpoint states.
        var knownTotal = 0
        val slug = ChapterListPagination.bookSlugOf(pageUrl)
        // v1.3.0 delta: a single-shot endpoint (NovelBuddy)
        // answers EVERY page with the same payload — fetching
        // page 2 only to watch it dedupe to nothing doubles a
        // ~800 KB download on slow networks. When the next
        // page's request is byte-identical to the previous
        // one, the list is already whole: stop before fetching.
        var prevRequest: Pair<String, String?>? = null
        while (fetched < MAX_LIST_PAGES) {
            val url = resolveUrl(
                pagination.endpointFor(bookId, page, slug, pageUrl)
            )
            val requestKey = url to pagination.bodyFor(
                bookId, page, slug, pageUrl
            )
            if (requestKey == prevRequest) break
            prevRequest = requestKey
            val fetchedPage = runCatching {
                val response = if (
                    pagination.method.equals("POST", ignoreCase = true) &&
                    pagination.body != null
                ) {
                    context.http.post(
                        url,
                        pagination.bodyFor(bookId, page, slug, pageUrl)
                            ?: "",
                        headers = pagination.headers
                    )
                } else {
                    context.http.get(url, pagination.headers)
                }
                if (response.status !in 200..299) return@runCatching null
                if (pagination.jsonArrayPath != null) {
                    val root = runCatching {
                        ajaxJson.parseToJsonElement(response.body)
                    }.getOrNull() ?: return@runCatching null
                    val total = pagination.jsonTotalPath?.let { path ->
                        (EmbeddedJson.resolve(root, path)
                            as? kotlinx.serialization.json.JsonPrimitive)
                            ?.contentOrNull?.toIntOrNull()
                    }
                    PageFetch(jsonEndpointRows(root, pageUrl, bookId), total)
                } else {
                    val html = paginationFragmentHtml(response.body, pagination)
                        ?: return@runCatching null
                    val fragment = Jsoup.parseBodyFragment(html, pageUrl)
                    PageFetch(
                        rowsFromElements(selectFragmentRows(fragment), pageUrl),
                        null
                    )
                }
            }.getOrElse { error ->
                // Cancellation is the caller's business, never
                // a "page failed" signal.
                if (error is kotlinx.coroutines.CancellationException) {
                    throw error
                }
                null
            } ?: return
            fetchedPage.total?.let { if (it > 0) knownTotal = it }
            val fragmentRows = fetchedPage.rows
            fetched++
            if (fragmentRows.isEmpty()) return
            val fresh = fragmentRows.filter { it.id !in seen }
            if (fresh.isEmpty()) {
                // A page that adds nothing: when the walk
                // STARTED at page 1 of a JSON endpoint, its
                // first page can be the seeds served again
                // (they were parsed from the same payload) —
                // keep walking. Any other repeat — a DOM walk
                // whose next page duplicates the book slice,
                // or a later page cycling — means the list is
                // over: stop.
                if (fetched > 1 || startPage != 1) return
            } else {
                rows += fresh
                seen += fresh.map { it.id }
                if (knownTotal > 0 && seen.size >= knownTotal) return
                if (firstPageSize < 0) {
                    firstPageSize = fragmentRows.size
                } else if (fragmentRows.size < firstPageSize) {
                    return
                }
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
        // v1.3.2 (item 76): the page's own synopsis body vs
        // the configured read. A META description is an SEO
        // snippet by construction — boilerplate lead, cut
        // short — so when the spec reads a meta tag (or the
        // text carries the boilerplate / a truncation cut)
        // and the page carries a LONGER synopsis body, the
        // body wins. A body-selector spec reads the body
        // directly and keeps the old heal-only-on-truncation
        // behaviour. Boilerplate is stripped either way.
        val cleaned =
            app.xreader.domain.reader.SynopsisText.stripBoilerplate(extracted)
        val block = doc.select(
            "[class*=synopsis], [class*=description], [class*=summary]"
        ).map {
            app.xreader.domain.reader.SynopsisText
                .stripBoilerplate(it.text().trim())
        }
            .filter { it.length >= 40 }
            .maxByOrNull { it.length }
        val snippetShaped =
            LinkInference.isTruncatedSnippet(cleaned) ||
                app.xreader.domain.reader.SynopsisText
                    .hasBoilerplatePrefix(extracted) ||
                app.xreader.domain.reader.SynopsisText
                    .isMetaSpec(spec.description)
        return if (
            block != null && block.length > cleaned.length &&
            (snippetShaped || block.length >= cleaned.length * 5 / 4)
        ) {
            block
        } else {
            cleaned
        }
    }

    private fun readTitle(doc: Document, rawSpec: String?): String? {
        val raw = rawSpec?.let { readDocSpec(doc, it) }?.trim()?.ifBlank { null }
            ?: return null
        val cleaned = TitleCleaner.clean(raw)
        if (cleaned.isBlank() || TitleCleaner.isDateOnly(cleaned)) return null
        return cleaned
    }

    // ── chapter content ─────────────────────────────────────────────

    override fun chapterContent(chapter: SourceChapter): SourceChapterContent =
        chapterContent(chapter, prefetchedFirstPage = null)

    /** v1.2.0 (A2): the chapter read, handed the document the
     * CALLER just fetched for this chapter's FIRST page (a Live
     * Source's chapter-shape derivation fetch) so the page
     * isn't fetched — on a rendered site, RENDERED — twice for
     * one open. Only the first page can come from the caller:
     * the nextPage chain fetches normally. The document is
     * used only when its own URL matches the page it stands in
     * for. The AJAX text mode never fetches the chapter page,
     * so it ignores the hint. No other behaviour changes.
     *
     * v1.2.0 (C1) note: the nextPage walk below stays
     * SEQUENTIAL by design here — a page's next URL is only
     * known after that page parses, the per-page work between
     * fetches is a clone/strip of the content element (the
     * expensive conversion runs ONCE, over the combined
     * fragment, after the walk), and this read is synchronous
     * host code whose fetch seam (HostHttp) is itself a
     * blocking runBlocking per call, so an overlapped fetch
     * would need a second thread rendezvousing with the
     * single-WebView render mutex and the shared rate limiter
     * for a gain measured in the clone/strip time only. */
    fun chapterContent(
        chapter: SourceChapter,
        prefetchedFirstPage: Document?
    ): SourceChapterContent {
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
            val pageUrl: String = url!!
            val doc = if (pages == 1) {
                prefetchedFirstPage?.takeIf { documentUrl(it) == pageUrl }
                    ?: fetchDoc(pageUrl)
            } else {
                fetchDoc(pageUrl)
            }
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
                val target = href?.takeIf { it.isNotBlank() }
                    ?.let { absolute(it, url!!) } ?: return@let null
                // WS3 (class F): the chaining guard — a stored
                // next-page rule that actually points at the
                // NEXT CHAPTER stops the chain at this chapter's
                // end instead of gluing the next chapter's text
                // onto this one. Same-chapter pagination (same
                // path / sub-path) chains exactly as before.
                if (LinkInference.isNextChapterHref(url!!, target, nextEl.text())) {
                    null
                } else {
                    target
                }
            }
        }
        val combined = fragments.joinToString("\n")
        // Convert once, on the concatenated fragment, so multi-page
        // chapters read as one continuous text.
        val doc = Jsoup.parseBodyFragment(combined, chapter.id)
        cacheChapter(chapter.id, doc)
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
        // v1.3.0 (E1): reader APIs that take the chapter
        // address in a POST body (WTR-LAB) ride the same seam —
        // headers, session, whitelist, rate limit unchanged.
        val response = if (ajax.method.equals("POST", ignoreCase = true)) {
            context.http.post(
                resolveUrl(endpoint),
                ajax.bodyFor(chapter.id) ?: "",
                headers = ajax.headers
            )
        } else {
            context.http.get(resolveUrl(endpoint), ajax.headers)
        }
        if (response.status !in 200..299) {
            throw SourceException("the site answered HTTP ${response.status}")
        }
        val rootElement = runCatching {
            ajaxJson.parseToJsonElement(response.body)
        }.getOrNull()
        val root = rootElement as? JsonObject
        // v1.3.0 (E1): an endpoint that MARKS the chapter
        // locked in its own JSON (chikari: "locked": true)
        // says so in as many words — the typed locked failure,
        // never an empty chapter.
        val lockField = ajax.jsonLockField
        if (lockField != null &&
            (root?.get(lockField) as? JsonPrimitive)?.booleanOrNull == true
        ) {
            throw SourceException(AJAX_TEXT_EMPTY_MESSAGE)
        }
        // v1.3.0: the content value may sit at a dot path
        // (noveli's chapter.paragraphs) and may be an ARRAY —
        // of strings, or of paragraph objects whose text field
        // carries the prose, with anti-scraping watermark items
        // (recognisable by their ids) skipped.
        val contentNode = if (rootElement == null) {
            null
        } else if (ajax.jsonContentPath != null) {
            EmbeddedJson.resolve(rootElement, ajax.jsonContentPath!!)
        } else {
            root?.get(ajax.jsonContentField)
        }
        val skipId = ajax.jsonSkipIdPattern?.takeIf { it.isNotBlank() }
            ?.let { runCatching { Regex(it) }.getOrNull() }
        val rawPayload = when (contentNode) {
            is JsonPrimitive -> contentNode.content
                .takeIf { it.isNotBlank() }
            is kotlinx.serialization.json.JsonArray ->
                contentNode.mapNotNull { item ->
                    when (item) {
                        is JsonPrimitive -> item.contentOrNull
                        is JsonObject -> {
                            val itemId = (item["id"] as? JsonPrimitive)
                                ?.contentOrNull
                            if (skipId != null && itemId != null &&
                                skipId.containsMatchIn(itemId)
                            ) {
                                null
                            } else {
                                (item[ajax.jsonItemTextField]
                                    as? JsonPrimitive)?.contentOrNull
                            }
                        }
                        else -> null
                    }
                }.filter { it.isNotBlank() }
                    .joinToString("\n")
                    .takeIf { it.isNotBlank() }
            else -> null
        } ?: throw SourceException(AJAX_TEXT_EMPTY_MESSAGE)
        // v1.3.0 (E1): a plain-text field (chikari's `body`:
        // newline-separated prose, a small set of inline tags)
        // becomes paragraph HTML — each non-empty line a <p> —
        // instead of being fed to the content selector as if
        // it were markup.
        val payload = if (ajax.plainTextField) {
            rawPayload.replace(Regex("<[^>]+>"), "")
                .lines().map { it.trim() }
                .filter { it.isNotEmpty() }
                .joinToString("\n") { line ->
                    val escaped = line
                        .replace("&", "&amp;")
                        .replace("<", "&lt;")
                        .replace(">", "&gt;")
                    "<p>$escaped</p>"
                }
                .ifBlank { throw SourceException(AJAX_TEXT_EMPTY_MESSAGE) }
        } else {
            rawPayload
        }
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
        cacheChapter(chapter.id, doc)
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
        /** v1.3.2 (item 71): a row label that repeats its own
         * chapter number as a glued prefix — "101Chapter 101
         * – …" (the site's row index concatenated onto the
         * chapter's label by a whole-row @text read). */
        private val DUPLICATE_NUMBER_PREFIX =
            Regex("^(\\d+)\\s*Chapter\\s+(\\d+)\\b")

        /** v1.2.0 (audit #4): the content/document cache cap —
         * enough for a reading session's working set (current +
         * neighbours + recent history), small enough that the
         * cache is tens of chapters' DOMs at most, never the
         * whole book. */
        const val MAX_CONTENT_CACHE_ENTRIES = 24

        /** Multi-page chapter chaining cap (§5: no runaway pagination). */
        const val MAX_CHAPTER_PAGES = 10

        /** v1.0.6.2 (F1): chapter-LIST pagination walk cap — at
         * novelight's 50 rows a page this still covers 2,000
         * chapters past the book page's own slice. */
        const val MAX_LIST_PAGES = 100

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

        /** v1.3.0 delta (item 50): call-to-action labels that
         * book pages render inside chapter-list regions. A row
         * with one of these names and NO chapter number is a
         * button, never a chapter. */
        private val CHAPTER_CTA_LABELS = setOf(
            "start reading", "read now", "continue reading",
            "begin reading", "read from start", "start reading now"
        )

        /** v1.3.0 delta (item 50): URL path segments that name
         * book COLLECTIONS (tag/genre/category listings). A
         * chapter link never routes through one. */
        private val LISTING_PATH_SEGMENTS = setOf(
            "tag", "tags", "genre", "genres", "category", "categories"
        )
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
        Regex("^\\s*(\\d+(?:\\.\\d+)?)\\s*[.:\\-]"),
        // v1.3.2: the number-first house style — "760 chapter"
        // (Novelight). The word must follow the number, so a
        // rating-led title ("5.0 The Star-Painting Genius")
        // never parses.
        Regex("(?i)^\\s*(\\d+(?:\\.\\d+)?)\\s+chapter\\b")
    )

    fun parse(name: String): Float? {
        for (pattern in patterns) {
            pattern.find(name)?.let { return it.groupValues[1].toFloatOrNull() }
        }
        return null
    }

    // ── v1.3.2 (items 78/79/80): slug-shaped names ─────────────
    // Chapter URLs and some sites' row text name chapters in
    // slug form — "chapter-156-totally-awful", "ch-12",
    // "episode-3-part-1". The word patterns above all demand
    // whitespace (or a word boundary the hyphen defeats), so a
    // slug-named list parsed to NO numbers at all: the
    // strongly-descending detector went blind, a newest-first
    // list was stored as-is, and the reader — labelling by
    // position — opened chapter 158 under a "Chapter 1" label.
    private val slugPatterns = listOf(
        Regex("(?i)(?:^|[-_])chapter[-_](\\d+(?:\\.\\d+)?)(?:[-_]|$)"),
        Regex("(?i)(?:^|[-_])ch[-_](\\d+(?:\\.\\d+)?)(?:[-_]|$)"),
        Regex("(?i)(?:^|[-_])episode[-_](\\d+(?:\\.\\d+)?)(?:[-_]|$)")
    )

    /** The number inside a slug-shaped name or URL tail
     * ("chapter-156-totally-awful" → 156). Null when the text
     * is not slug-shaped chapter naming. */
    fun parseSlug(text: String): Float? {
        for (pattern in slugPatterns) {
            pattern.find(text)?.let { return it.groupValues[1].toFloatOrNull() }
        }
        return null
    }

    /** The number a chapter URL itself carries: its last path
     * segment read as a name, then as a slug, then as a bare
     * number ("…/chapter-214", "…/214", "…/chapter/214").
     * Null when the URL carries no chapter number. */
    fun parseFromUrl(url: String): Float? {
        val path = url.substringBefore('?').substringBefore('#')
        val segments = path.split('/').filter { it.isNotBlank() }
        val tail = segments.lastOrNull() ?: return null
        return parse(tail) ?: parseSlug(tail) ?: tail.toFloatOrNull()
            ?: segments.getOrNull(segments.lastIndex - 1)
                ?.takeIf { prev ->
                    prev.equals("chapter", ignoreCase = true) ||
                        prev.equals("ch", ignoreCase = true)
                }?.let { tail.toFloatOrNull() }
    }

    /** Any number a chapter row carries, name first, then
     * slug-shaped name, then its URL — the identity number
     * the merge, the ordering and the reader all key on. */
    fun parseAny(name: String, url: String): Float? =
        parse(name) ?: parseSlug(name) ?: parseFromUrl(url)

    /** The number for ORDERING a list: the name's own number,
     * or a slug-shaped number in the name or URL — but never a
     * bare numeric URL tail. A bare tail is as often a
     * database id as a chapter number (Novelight's
     * …/chapter/346374 is chapter 760), and one list mixing
     * name numbers with id numbers sorts into nonsense:
     * v1.3.2's first gate run caught exactly that — the
     * Novelight list "sorted" by id interleaved its fragments.
     * A list whose numbers live only in bare tails keeps its
     * site order instead (the fallback finishing). */
    fun parseForOrder(name: String, url: String): Float? {
        parse(name)?.let { return it }
        parseSlug(name)?.let { return it }
        val path = url.substringBefore('?').substringBefore('#')
        val segments = path.split('/').filter { it.isNotBlank() }
        val tail = segments.lastOrNull() ?: return null
        parse(tail)?.let { return it }
        parseSlug(tail)?.let { return it }
        val prev = segments.getOrNull(segments.lastIndex - 1)
        if (prev != null &&
            (prev.equals("chapter", ignoreCase = true) ||
                prev.equals("ch", ignoreCase = true))
        ) {
            return tail.toFloatOrNull()
        }
        return null
    }
}

/**
 * v1.1.2: the reading-order trend test. A finished chapter
 * list is STRONGLY DESCENDING when its chapter numbers —
 * read from the chapter names, or from the chapter URLs'
 * tails when a name carries none — fall overall: the first
 * number exceeds the last AND at least four fifths of the
 * adjacent steps go down. The bar is set so real reading
 * orders can never trip it: ascending lists have almost no
 * descending steps; per-volume numbering restarts produce
 * one descending step per volume; specials (10.5) sit
 * between their neighbours. Only a list that was captured
 * newest-first and never flipped looks like this — and such
 * a list stored as-is turns the whole book upside down
 * (chapter 1 at the last index, progress reading ~97% for a
 * book just opened). Too few numbered rows to judge (under
 * three, or under a quarter of the list) is not a verdict.
 */
object ChapterOrder {

    fun isStronglyDescending(rows: List<SourceChapter>): Boolean {
        val numbers = rows.mapNotNull { row ->
            row.number ?: hrefTailNumber(row.id)
        }
        if (numbers.size < 3) return false
        if (numbers.size < (rows.size + 3) / 4) return false
        if (numbers.first() <= numbers.last()) return false
        var descending = 0
        var steps = 0
        for (i in 0 until numbers.lastIndex) {
            steps++
            if (numbers[i] > numbers[i + 1]) descending++
        }
        return steps > 0 && descending * 5 >= steps * 4
    }

    /** The chapter number a chapter URL's tail carries, when
     * it carries one ("…/series/x/214", "…/chapter-214") —
     * the shape chapter links take on sites whose row text
     * shows only a date. v1.3.2: slug tails parse too
     * ("chapter-214-a-title" → 214) via [ChapterNumbers]. */
    private fun hrefTailNumber(id: String): Float? =
        ChapterNumbers.parseFromUrl(id)
}
