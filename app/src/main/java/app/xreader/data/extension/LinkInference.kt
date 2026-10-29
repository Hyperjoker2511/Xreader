package app.xreader.data.extension

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI

/**
 * "Add source from link" inference (PLAN Amendment 7): given a book
 * page and a chapter page as parsed documents, guess the declarative
 * selectors a source for the site would use. The heuristics, in the
 * order they're applied:
 *
 * BOOK PAGE
 * - Login wall: a password field anywhere (or a /login URL with a
 *   form) → the assisted-login flow must run first.
 * - Chapter list: anchors that look like chapters (a chapter number
 *   in the link text — "Chapter 12", "Ch. 3", "第12章" — or a
 *   chapter-shaped URL), clustered by a shared parent/wrapper. The
 *   biggest cluster of ≥3 wins.
 * - Title: og:title → first <h1> → the <title> tag (site suffixes
 *   stripped). Cover: og:image → the largest image near the title.
 * - Newest-first lists (numbers descending) set the reverse flag.
 *
 * CHAPTER PAGE
 * - Content: the article/main/div/section whose text is longest after
 *   subtracting link text and penalising nav/menu/footer/comment
 *   class names — the readable block, not the page chrome.
 * - Next page: an anchor whose text reads "next", "›", "»",
 *   "下一章", "下一页" or rel="next".
 *
 * LISTING PAGES (search results / home catalogue)
 * - Anchor clusters whose links share the book URL's path prefix, or
 *   that wrap cover images.
 *
 * Everything here is pure document analysis — fetching and the
 * mandatory test-drive live in [LinkProber] — so fixtures pin the
 * behaviour exactly.
 */
object LinkInference {

    data class BookPageAnalysis(
        val title: String,
        val titleSpec: String,
        val authorSpec: String?,
        val descriptionSpec: String?,
        val coverUrl: String?,
        val chapterListSelector: String,
        val chapterNameSpec: String,
        val chapterLinkSpec: String,
        val chapterCount: Int,
        val firstChapterUrl: String,
        val newestFirst: Boolean,
        /** v1.0.5 (A6): the spec that REPRODUCES [coverUrl] from
         * the page (an <img> spec, or the og:image meta spec) —
         * null exactly when no cover was found, so a found cover
         * never degrades into a blank cover rule. */
        val coverSpec: String? = null,
        /** v1.0.5 (A7): the title came from the URL slug because
         * every on-page candidate was a date stamp — there is no
         * honest title spec, and callers must not store one. */
        val titleFromSlug: Boolean = false
    )

    data class ChapterPageAnalysis(
        val contentSelector: String,
        val textLength: Int,
        val preview: String,
        val nextPageSelector: String?,
        val titleSpec: String?
    )

    data class ListingAnalysis(
        val resultsSelector: String,
        val titleSpec: String,
        val linkSpec: String,
        val coverSpec: String?,
        val count: Int
    )

    // ── login ───────────────────────────────────────────────────────

    fun needsLogin(doc: Document, pageUrl: String): Boolean {
        if (doc.select("input[type=password]").isNotEmpty()) return true
        val path = runCatching { URI(pageUrl).path }.getOrNull()?.lowercase() ?: ""
        val loginPath = path.contains("/login") || path.contains("/signin") ||
            path.contains("/sign-in") || path.contains("account/login")
        return loginPath && doc.select("form").isNotEmpty()
    }

    /**
     * JavaScript-shell detection (v1.0.5, A9 — D16): some sites
     * serve an empty app shell and fill it with JavaScript — no
     * static reader (templates, inference, heuristics, or the AI
     * detector, all reading the same fetched HTML) can read them,
     * and the honest answer is to say so at add-time instead of
     * installing broken rules. A page counts as a shell only with
     * POSITIVE app evidence — an empty JS mount point, a
     * "enable JavaScript" noscript, or module scripts — combined
     * with a near-empty body; a plain sparse page (a blog post)
     * is NOT a shell and keeps the ordinary failure path.
     */
    fun isJsShell(doc: Document): Boolean {
        val body = doc.body() ?: return false
        if (body.text().trim().length >= 120) return false
        if (body.select("a[href]").size >= 5) return false
        val mount = doc.select("div, main, section").any { el ->
            el.id().lowercase() in JS_MOUNT_IDS &&
                el.children().isEmpty() && el.text().isBlank()
        }
        if (mount) return true
        val noscript = doc.select("noscript").any {
            it.text().lowercase().contains("javascript")
        }
        if (noscript) return true
        return doc.select("script[type=module]").size >= 2
    }

    // ── book page ───────────────────────────────────────────────────

    fun analyseBookPage(doc: Document, pageUrl: String): BookPageAnalysis? {
        val cluster = findAnchorCluster(doc, chapterAnchors(doc)) ?: return null
        val foundTitle = findTitle(doc)
        // v1.0.5 (A7): every on-page title candidate was a date
        // stamp — fall back to the URL slug rather than naming
        // the book after a calendar day. A slug title has no
        // honest spec; titleFromSlug tells callers so.
        val title: Pair<String, String>
        val fromSlug: Boolean
        if (foundTitle != null) {
            title = foundTitle
            fromSlug = false
        } else {
            val slug = TitleCleaner.slugTitle(pageUrl) ?: return null
            title = slug to ""
            fromSlug = true
        }
        val cover = findCover(doc, pageUrl, title.first)
        val numbers = cluster.items.mapNotNull { item ->
            ChapterNumbers.parse(item.text())
        }
        val newestFirst = numbers.size >= 2 && numbers.first() > numbers.last()
        val firstItem = if (newestFirst) cluster.items.last() else cluster.items.first()
        val firstAnchor = anchorOf(firstItem)
        val firstUrl = firstAnchor?.attr("abs:href")?.ifBlank { null }
            ?: firstAnchor?.let { absolute(it.attr("href"), pageUrl) }
            ?: return null
        val itemIsAnchor = cluster.itemsAreAnchors
        return BookPageAnalysis(
            title = title.first,
            titleSpec = title.second,
            authorSpec = findAuthorSpec(doc),
            descriptionSpec = findDescriptionSpec(doc),
            coverUrl = cover?.url,
            coverSpec = cover?.spec,
            titleFromSlug = fromSlug,
            chapterListSelector = SelectorSynthesizer.forList(cluster.items.first()),
            chapterNameSpec = if (itemIsAnchor) "@text" else "a@text",
            chapterLinkSpec = if (itemIsAnchor) "@href" else "a@href",
            chapterCount = cluster.items.size,
            firstChapterUrl = firstUrl,
            newestFirst = newestFirst
        )
    }

    // ── chapter page ────────────────────────────────────────────────

    fun analyseChapterPage(doc: Document, pageUrl: String): ChapterPageAnalysis? {
        val candidates = doc.select("article, main, div, section, td")
            .filter { element ->
                val text = element.text()
                text.length >= MIN_CONTENT_CHARS && !isChrome(element)
            }
        val best = candidates.maxByOrNull { element ->
            val textLength = element.text().length
            val linkTextLength = element.select("a").sumOf { it.text().length }
            textLength - (linkTextLength * 2)
        } ?: return null
        val text = best.text().trim()
        if (text.length < MIN_READABLE_CHARS) return null
        val nextAnchor = doc.select("a").firstOrNull { anchor ->
            val rel = anchor.attr("rel")
            rel.equals("next", ignoreCase = true) ||
                NEXT_TEXT.matches(anchor.text().trim())
        }
        val heading = doc.selectFirst("h1")
        return ChapterPageAnalysis(
            contentSelector = SelectorSynthesizer.forElement(best),
            textLength = text.length,
            preview = text.take(PREVIEW_CHARS),
            nextPageSelector = nextAnchor?.let { SelectorSynthesizer.forElement(it) },
            titleSpec = heading?.let { SelectorSynthesizer.forElement(it) }
        )
    }

    // ── listing page ────────────────────────────────────────────────

    fun analyseListingPage(
        doc: Document,
        pageUrl: String,
        bookUrlHint: String?
    ): ListingAnalysis? {
        // Book links live under a shared collection segment
        // ("/novel/…", "/book/…") — different books share only that
        // first segment, so it's the segment that must match the hint.
        val hintSegment = bookUrlHint?.let { pathPrefix(it, 1) }
        val anchors = doc.select("a[href]").filter { anchor ->
            val href = anchor.attr("abs:href").ifBlank { absolute(anchor.attr("href"), pageUrl) }
            if (href.isBlank()) return@filter false
            val wrapsImage = anchor.select("img").isNotEmpty()
            val sharesSegment = hintSegment != null && hintSegment.isNotEmpty() &&
                pathPrefix(href, 1) == hintSegment &&
                href != bookUrlHint
            wrapsImage || sharesSegment
        }
        val cluster = findAnchorCluster(doc, anchors) ?: return null
        val itemHasImage = cluster.items.any { it.select("img").isNotEmpty() } ||
            (cluster.itemsAreAnchors && cluster.items.any {
                it.select("img").isNotEmpty()
            })
        return ListingAnalysis(
            resultsSelector = SelectorSynthesizer.forList(cluster.items.first()),
            titleSpec = if (cluster.itemsAreAnchors) "@text" else "a@text",
            linkSpec = if (cluster.itemsAreAnchors) "@href" else "a@href",
            coverSpec = when {
                cluster.itemsAreAnchors && itemHasImage -> "img@src"
                !cluster.itemsAreAnchors && itemHasImage -> "img@src"
                else -> null
            },
            count = cluster.items.size
        )
    }

    /**
     * Search-results analysis (v1.0.5, A5): a real search often
     * returns ONE or TWO books — below [analyseListingPage]'s
     * cluster minimum — and the old all-or-nothing judgement then
     * preferred any convention whose unfiltered catalogue DID
     * cluster (the `?s=` failure, D21). This variant falls back
     * to a single matching item and derives the repeating specs
     * from it, so a small honest result set still counts.
     */
    fun analyseSearchResultsPage(
        doc: Document,
        pageUrl: String,
        bookUrlHint: String?
    ): ListingAnalysis? {
        analyseListingPage(doc, pageUrl, bookUrlHint)?.let { return it }
        val hintSegment = bookUrlHint?.let { pathPrefix(it, 1) }
        val anchor = doc.select("a[href]").firstOrNull { candidate ->
            val href = candidate.attr("abs:href")
                .ifBlank { absolute(candidate.attr("href"), pageUrl) }
            if (href.isBlank() || href == bookUrlHint) return@firstOrNull false
            val wrapsImage = candidate.select("img").isNotEmpty()
            val sharesSegment = hintSegment != null && hintSegment.isNotEmpty() &&
                pathPrefix(href, 1) == hintSegment
            wrapsImage || sharesSegment
        } ?: return null
        val hasImage = anchor.select("img").isNotEmpty()
        return ListingAnalysis(
            resultsSelector = SelectorSynthesizer.forList(anchor),
            titleSpec = "@text",
            linkSpec = "@href",
            coverSpec = if (hasImage) "img@src" else null,
            count = 1
        )
    }

    /** A "table of contents"-style link to the book page, when the
     * pasted URL was a chapter page. */
    fun findBookPageLink(doc: Document, pageUrl: String): String? {
        val anchor = doc.select("a[href]").firstOrNull { a ->
            TOC_TEXT.matches(a.text().trim()) ||
                a.attr("href").lowercase().let {
                    it.contains("/novel/") && !it.contains("chapter")
                }
        } ?: return null
        val href = anchor.attr("abs:href").ifBlank { absolute(anchor.attr("href"), pageUrl) }
        return href.ifBlank { null }
    }

    /** One search form found on a page (v1.0.1): where it submits,
     * how (GET/POST), and the query field's name. */
    data class SearchFormCandidate(
        val actionUrl: String,
        val method: String,
        val inputName: String
    ) {
        /** GET template in the {query} convention [ListSpec] uses. */
        fun toTemplate(): String {
            val separator = if (actionUrl.contains('?')) "&" else "?"
            return "$actionUrl$separator$inputName={query}"
        }
    }

    /**
     * Search form detection (v1.0.1 — widened): every plausible
     * search form on the page, best first. v1.0.0 required the word
     * "search" in the action AND a named input from a fixed list,
     * which missed JS-submitted forms (no action → submits to the
     * page itself), path-only actions, and forms whose only tell is
     * a search-typed input or a search placeholder. A form counts
     * when ANY strong signal is present.
     */
    fun findSearchForms(doc: Document, pageUrl: String): List<SearchFormCandidate> {
        val found = mutableListOf<SearchFormCandidate>()
        for (form in doc.select("form")) {
            val rawAction = form.attr("abs:action")
                .ifBlank { absolute(form.attr("action"), pageUrl) }
                .ifBlank { pageUrl }
            val inputs = form.select(
                "input[type=search], input[type=text], input:not([type])"
            ).filter { it.attr("name").isNotBlank() }
            if (inputs.isEmpty()) continue
            val input = inputs.firstOrNull {
                it.attr("name").lowercase() in SEARCH_PARAMS ||
                    it.attr("type").equals("search", ignoreCase = true)
            } ?: inputs.first()
            val actionLower = rawAction.lowercase()
            val signals = listOf(
                actionLower.contains("search"),
                input.attr("name").lowercase() in SEARCH_PARAMS,
                input.attr("type").equals("search", ignoreCase = true),
                input.attr("placeholder").lowercase().contains("search"),
                form.attr("class").lowercase().contains("search"),
                form.attr("id").lowercase().contains("search"),
                form.attr("role").equals("search", ignoreCase = true)
            )
            if (signals.none { it }) continue
            val method = if (form.attr("method").equals("post", ignoreCase = true)) {
                "POST"
            } else {
                "GET"
            }
            found += SearchFormCandidate(rawAction, method, input.attr("name"))
        }
        // GET forms with a search-y action first — the common case.
        return found.sortedByDescending {
            (if (it.method == "GET") 2 else 1) +
                (if (it.actionUrl.lowercase().contains("search")) 1 else 0)
        }
    }

    /** Search form detection: returns a URL template with {query}. */
    fun findSearchTemplate(doc: Document, pageUrl: String): String? =
        findSearchForms(doc, pageUrl)
            .firstOrNull { it.method == "GET" }?.toTemplate()

    // ── Madara fast-path (v1.0.1, QuickNovel lesson) ──────────────
    // A large share of novel sites run the Madara WordPress theme
    // ("wp-manga") with near-identical markup. When the fingerprints
    // match, the known-good template below is tried FIRST — the full
    // mandatory test-drive still decides whether it ships.

    fun isMadaraPage(doc: Document): Boolean {
        val bodyClass = doc.body()?.attr("class")?.lowercase().orEmpty()
        if (bodyClass.contains("madara")) return true
        if (doc.selectFirst("div.wp-manga, .wp-manga, [class*=wp-manga]") != null) return true
        if (doc.selectFirst("meta[name=generator]")?.attr("content")
                ?.lowercase()?.contains("madara") == true
        ) return true
        return doc.selectFirst(".c-blog-post .item-thumb, .manga-title-badges") != null
    }

    /** Madara's stock search: WordPress search scoped to manga. */
    fun madaraSearchTemplate(baseUrl: String): String =
        baseUrl.trimEnd('/') + "/?s={query}&post_type=wp-manga"

    /** Madara's stock search-results listing selectors. */
    fun madaraSearchSpec(baseUrl: String): ListSpec = ListSpec(
        url = madaraSearchTemplate(baseUrl),
        results = ".c-tabs-item .c-tabs-item__content",
        title = ".post-title a@text",
        link = ".post-title a@href",
        cover = ".item-thumb img@src"
    )

    /**
     * Reviews-section detection (v1.0.1 enrichment): a container
     * whose class/id says "review" holding at least two similar
     * items with real text. Returns selectors for the declarative
     * engine, or null — a found-but-empty section is impossible to
     * distinguish from decoration here, so absence is honest.
     */
    fun detectReviews(doc: Document): ReviewsSpec? {
        val containers = doc.select(
            "[class*=review], [id*=review], [class*=Review], [id*=Review]"
        )
        for (container in containers) {
            if (container.tagName() == "form" || container.tagName() == "a") continue
            val children = container.children().filter { it.text().trim().length >= 20 }
            if (children.size < 2) continue
            val item = children.first()
            val itemSelector = SelectorSynthesizer.forList(item)
            val authorEl = item.select("a, [class*=author], [class*=name], [class*=user]")
                .firstOrNull()
            val ratingEl = item.select(
                "[class*=star], [class*=rating], [class*=score]"
            ).firstOrNull()
            return ReviewsSpec(
                list = itemSelector,
                author = authorEl?.let { SelectorSynthesizer.forElement(it) } ?: "@text",
                text = "@text",
                rating = ratingEl?.let { SelectorSynthesizer.forElement(it) },
                aggregate = doc.selectFirst(
                    "[itemprop=ratingValue], [class*=average-rating], [class*=rating-value]"
                )?.let { SelectorSynthesizer.forElement(it) }
            )
        }
        return null
    }

    fun siteName(doc: Document): String? =
        doc.selectFirst("meta[property=og:site_name]")?.attr("content")
            ?.ifBlank { null }

    // ── internals ───────────────────────────────────────────────────

    private data class Cluster(
        val items: List<Element>,
        val itemsAreAnchors: Boolean
    )

    private fun chapterAnchors(doc: Document): List<Element> =
        doc.select("a[href]").filter { isChapterLink(it) }

    private fun isChapterLink(anchor: Element): Boolean {
        val text = anchor.text().trim()
        if (text.length > 90) return false
        if (ChapterNumbers.parse(text) != null) return true
        val href = anchor.attr("href").lowercase()
        return CHAPTER_HREF.containsMatchIn(href)
    }

    /**
     * Cluster anchors by shape: sharing one parent (items = anchors),
     * or wrapped one-per-item in same-tag siblings (items = wrappers).
     * The biggest cluster of ≥ [MIN_CLUSTER] wins.
     */
    private fun findAnchorCluster(doc: Document, anchors: List<Element>): Cluster? {
        if (anchors.size < MIN_CLUSTER) return null
        // Case 1: anchors are direct siblings.
        val byParent = anchors.groupBy { it.parent() }
            .filterKeys { it != null }
        val direct = byParent.maxByOrNull { it.value.size }
        if (direct != null && direct.value.size >= MIN_CLUSTER) {
            return Cluster(direct.value, itemsAreAnchors = true)
        }
        // Case 2: each anchor wrapped in a same-tag item element.
        val wrappers = anchors.mapNotNull { anchor ->
            val parent = anchor.parent() ?: return@mapNotNull null
            if (parent.tagName() == "body") return@mapNotNull null
            parent
        }
        if (wrappers.size >= MIN_CLUSTER) {
            val byGrandparent = wrappers.groupBy { it.parent() to it.tagName() }
                .filterKeys { it.first != null }
            val best = byGrandparent.maxByOrNull { it.value.size }
            if (best != null && best.value.size >= MIN_CLUSTER) {
                return Cluster(best.value.distinct(), itemsAreAnchors = false)
            }
        }
        return null
    }

    /**
     * Title candidates in order (og:title → h1 → <title>), each
     * cleaned by [TitleCleaner]. v1.0.5 (A7): a candidate that is
     * ONLY a date stamp is rejected outright — the extractor
     * grabbed the date element, not the title (D25) — and the
     * next candidate gets its chance.
     */
    private fun findTitle(doc: Document): Pair<String, String>? {
        doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?.trim()?.ifBlank { null }?.let { raw ->
                val cleaned = TitleCleaner.clean(raw)
                if (cleaned.isNotBlank() && !TitleCleaner.isDateOnly(cleaned)) {
                    return cleaned to "meta[property=og:title]@content"
                }
            }
        doc.selectFirst("h1")?.let { h1 ->
            val text = h1.text().trim()
            val cleaned = TitleCleaner.clean(text)
            if (text.isNotBlank() && cleaned.isNotBlank() &&
                !TitleCleaner.isDateOnly(cleaned)
            ) {
                return cleaned to SelectorSynthesizer.forElement(h1)
            }
        }
        val titleTag = doc.title().trim()
        if (titleTag.isNotBlank()) {
            val cleaned = TitleCleaner.clean(titleTag)
            if (cleaned.isNotBlank() && !TitleCleaner.isDateOnly(cleaned)) {
                return cleaned to "title@text"
            }
        }
        return null
    }

    /** A found cover: its absolute URL plus the spec that
     * reproduces it from the page (A6). */
    private data class CoverHit(val url: String, val spec: String)

    /**
     * The cover chain (v1.0.5, A6 — D19/D21): (1) an <img> whose
     * alt names THIS book (sites like Novelight/NovelFull put the
     * book's own poster among other books' posters — the alt is
     * the tell); (2) an <img> with cover/poster/thumb hints near
     * the title, never inside carousel chrome (A8); (3) og:image;
     * (4) the first image in article/main, chrome excluded. A hit
     * always carries the spec that reproduces it, so a found
     * cover can never degrade into a blank cover rule.
     */
    private fun findCover(doc: Document, pageUrl: String, title: String): CoverHit? {
        val titleLower = title.trim().lowercase()
        if (titleLower.length >= 2) {
            val byAlt = doc.select("img").firstOrNull { img ->
                val alt = img.attr("alt").trim().lowercase()
                val src = img.attr("src").lowercase()
                alt.isNotBlank() &&
                    (alt == titleLower || alt.startsWith(titleLower)) &&
                    !src.contains("logo") && !src.contains("banner")
            }
            if (byAlt != null) {
                imageUrl(byAlt, pageUrl)?.let {
                    return CoverHit(it, SelectorSynthesizer.forElement(byAlt) + "@src")
                }
            }
        }
        val hinted = doc.select("img").firstOrNull { img ->
            val cls = (img.className() + " " + img.id()).lowercase()
            val hint = cls.contains("cover") || cls.contains("poster") ||
                cls.contains("thumb")
            hint && !isInChromeContainer(img)
        }
        if (hinted != null) {
            imageUrl(hinted, pageUrl)?.let {
                return CoverHit(it, SelectorSynthesizer.forElement(hinted) + "@src")
            }
        }
        doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?.ifBlank { null }?.let {
                return CoverHit(absolute(it, pageUrl), "meta[property=og:image]@content")
            }
        val image = doc.select("article img, main img")
            .firstOrNull { !isInChromeContainer(it) }
        if (image != null) {
            imageUrl(image, pageUrl)?.let {
                return CoverHit(it, SelectorSynthesizer.forElement(image) + "@src")
            }
        }
        return null
    }

    private fun imageUrl(img: Element, pageUrl: String): String? =
        img.attr("abs:src").ifBlank { null }
            ?: img.attr("src").ifBlank { null }?.let { absolute(it, pageUrl) }

    private fun findAuthorSpec(doc: Document): String? {
        doc.selectFirst("meta[name=author]")?.attr("content")
            ?.ifBlank { null }?.let { return "meta[name=author]@content" }
        val el = doc.select("[class*=author], [id*=author]")
            .firstOrNull {
                it.text().trim().length in 2..60 && it.children().size <= 2 &&
                    !isInChromeContainer(it)
            }
            ?: return null
        return SelectorSynthesizer.forElement(el)
    }

    private fun findDescriptionSpec(doc: Document): String? {
        if (doc.selectFirst("meta[name=description]")?.attr("content")?.isNotBlank() == true) {
            return "meta[name=description]@content"
        }
        if (doc.selectFirst("meta[property=og:description]")?.attr("content")
                ?.isNotBlank() == true
        ) {
            return "meta[property=og:description]@content"
        }
        val el = doc.select("[class*=synopsis], [class*=description], [class*=summary]")
            .firstOrNull { it.text().trim().length >= 40 && !isInChromeContainer(it) }
            ?: return null
        return SelectorSynthesizer.forElement(el)
    }

    /**
     * Field sanity (v1.0.5, A8 — D21): detail fields must never
     * be read out of page CHROME — carousels and sliders hold
     * OTHER books' covers, authors and blurbs (the stored
     * novelight author rule pointed into a swiper). True when
     * [element] or any ancestor is a swiper/slider/carousel
     * container.
     */
    private fun isInChromeContainer(element: Element): Boolean {
        var current: Element? = element
        while (current != null) {
            val marker = (current.className() + " " + current.id()).lowercase()
            if (CHROME_CONTAINERS.any { marker.contains(it) }) return true
            current = current.parent()
        }
        return false
    }

    private fun isChrome(element: Element): Boolean {
        val marker = (element.className() + " " + element.id()).lowercase()
        return CHROME_MARKERS.any { marker.contains(it) }
    }

    private fun anchorOf(item: Element): Element? =
        if (item.tagName() == "a") item else item.selectFirst("a[href]")

    private fun pathPrefix(url: String, segments: Int): String = runCatching {
        val path = URI(url).path ?: return ""
        path.split('/').filter { it.isNotBlank() }.take(segments).joinToString("/")
    }.getOrDefault("")

    private fun absolute(raw: String, base: String): String =
        runCatching { URI(base).resolve(raw).toString() }.getOrDefault(raw)

    const val MIN_CLUSTER = 3
    const val MIN_CONTENT_CHARS = 300
    const val MIN_READABLE_CHARS = 200
    const val PREVIEW_CHARS = 220

    private val CHAPTER_HREF = Regex(
        "(?i)(chapter|chap|ch)[-_/.]?\\d+|/\\d{2,6}\\.html?$|/\\d{2,6}/?$"
    )
    private val NEXT_TEXT = Regex(
        "(?i)^(next( chapter| page)?|›|»|>>|下一章|下一页|다음)\\s*(chapter|page|›|»)?\\s*$"
    )
    private val TOC_TEXT = Regex(
        "(?i)(table of contents|novel info|book info|details|목차|目录|目錄|章节列表)"
    )
    private val SEARCH_PARAMS = setOf("q", "query", "keyword", "keywords", "s", "search", "name")
    private val CHROME_MARKERS = listOf(
        "nav", "menu", "header", "footer", "sidebar", "comment",
        "pagination", "breadcrumb", "share", "related", "cookie"
    )
    /** Carousel/slider containers whose contents are page chrome,
     * never the book's own fields (A8). */
    private val CHROME_CONTAINERS = listOf(
        "swiper", "slider", "carousel", "owl-carousel", "slick-"
    )
    /** Empty mount points of the common JS app frameworks (A9). */
    private val JS_MOUNT_IDS = setOf("root", "app", "__next", "__nuxt", "app-root")
}
