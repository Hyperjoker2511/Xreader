package app.xreader.data.extension

import app.xreader.extensionapi.Source
import app.xreader.extensionapi.SourceBook
import app.xreader.extensionapi.SourceBookPage
import app.xreader.extensionapi.SourceChapter
import app.xreader.extensionapi.SourceChapterContent
import app.xreader.extensionapi.SourceContext
import app.xreader.extensionapi.SourceException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI

/**
 * What a Live Source install stores (v1.0.9) — deliberately
 * almost nothing: the site's address, where the user entered
 * it, and its display identity. No rules, no selectors: those
 * are derived live on every use (see [LiveSource]), which is
 * the whole point — a site that redesigns has nothing saved
 * to break.
 */
@Serializable
data class LiveSourceSpec(
    val id: String,
    val name: String,
    val baseUrl: String,
    /** The link the user pasted (a home page or a book page). */
    val startUrl: String,
    val iconUrl: String? = null,
    val lang: String = "en"
) {
    fun encode(): String = liveJson.encodeToString(serializer(), this)

    companion object {
        fun decode(text: String): LiveSourceSpec? =
            runCatching { liveJson.decodeFromString(serializer(), text) }
                .getOrNull()
    }
}

private val liveJson = Json { ignoreUnknownKeys = true; isLenient = true }

/**
 * v1.1.0: the derived-shape cache keys, in one place so the
 * version bump (design §6.2) and Repair's eviction (§6.3)
 * can never drift apart from the reads that use them.
 */
object LiveShapeCache {

    fun searchKey(host: String): String =
        "${LiveSource.SHAPE_VERSION}.search.$host"

    fun chapterKey(host: String): String =
        "${LiveSource.SHAPE_VERSION}.chapter.$host"

    fun bookKey(bookId: String): String =
        "${LiveSource.SHAPE_VERSION}.book." +
            Hashes.sha256Hex(bookId).take(16)

    /** Forget the host-keyed shapes (search template +
     * chapter shape, values and fingerprints). */
    fun evictHostShapes(
        store: app.xreader.extensionapi.HostStore,
        host: String
    ) {
        for (key in listOf(searchKey(host), chapterKey(host))) {
            store.remove(key)
            store.remove("$key.fp")
        }
    }
}

/**
 * A Live Source (v1.0.9, the user's install-a-site idea): the
 * site is loaded for real through the host's escalating fetch
 * stack (the hidden browser engine covers JavaScript-shell
 * sites), and the native catalogue / book / reader views are
 * derived LIVE from the pages as they arrive, with
 * [LinkInference] — the same analysers the add-from-link probe
 * uses, run at use time instead of being frozen into a saved
 * configuration at add time.
 *
 * Derived shapes are cached in the extension's private store
 * keyed by a fingerprint of the page's own structure; a
 * fingerprint mismatch or a failed read discards the entry
 * and derives again. The cache is disposable by design — it
 * is never shown, never edited, and never the reason a read
 * fails twice.
 */
class LiveSource(
    /** The install record this source runs on (the host reads
     * the icon URL from it for home-screen shortcuts). */
    val spec: LiveSourceSpec,
    private val context: SourceContext
) : Source {

    override val id: String get() = spec.id
    override val name: String get() = spec.name
    override val lang: String get() = spec.lang
    override val baseUrl: String get() = spec.baseUrl

    private val host: String =
        runCatching { URI(spec.baseUrl).host ?: spec.baseUrl }
            .getOrDefault(spec.baseUrl)

    companion object {
        /**
         * v1.1.0 (design §6.2): the derived-shape cache KEY
         * VERSION. Every derived shape lives under this prefix;
         * bumping it orphans every shape derived by older logic
         * (v1's admitted comment/chapter-shaped junk among
         * them), so an installed Live Source self-heals on its
         * next open — no reinstall, no migration. The orphaned
         * v1 entries are never read again.
         *
         * v1.1.1: bumped to shape.v3 — listings themselves are
         * re-derived on every call and were never cached, but
         * per-book shapes derived in v1.1.0 from junk catalogue
         * rows a user may have tapped (a chat card opened as a
         * "book") must never be served again either.
         */
        const val SHAPE_VERSION = "shape.v3"

        /** v1.2.0 (audit #4): the in-memory derived-config caps
         * — a handful of books are a session's working set, and
         * chapter configs are one small shape per chapter id. */
        const val MAX_BOOK_CONFIGS = 8
        const val MAX_CHAPTER_CONFIGS = 128
    }

    /** An access-ordered, size-capped, synchronised config
     * cache: eldest (least recently used) entries evict past
     * [maxEntries]. */
    private fun boundedConfigCache(
        maxEntries: Int
    ): MutableMap<String, DeclarativeConfig> =
        java.util.Collections.synchronizedMap(
            object : LinkedHashMap<String, DeclarativeConfig>(
                16, 0.75f, true
            ) {
                override fun removeEldestEntry(
                    eldest: MutableMap.MutableEntry<String, DeclarativeConfig>
                ): Boolean = size > maxEntries
            }
        )

    /** Per-book ephemeral configs derived this process.
     * v1.2.0 (audit #4): BOUNDED access-ordered LRU — the old
     * ConcurrentHashMaps grew for the process lifetime. An
     * eviction is never a correctness event: a missing book
     * config simply re-derives from the book page, and a
     * missing chapter config re-derives from the chapter page
     * (or falls back to any surviving book config first). */
    private val bookConfigs: MutableMap<String, DeclarativeConfig> =
        boundedConfigCache(MAX_BOOK_CONFIGS)

    /** Chapter id → the ephemeral config of the book it was
     * listed from, so a chapter read uses its own book's shape. */
    private val chapterConfigs: MutableMap<String, DeclarativeConfig> =
        boundedConfigCache(MAX_CHAPTER_CONFIGS)

    // ── listings ────────────────────────────────────────────

    override fun popular(page: Int): SourceBookPage {
        if (page > 1) return SourceBookPage(emptyList(), hasNextPage = false)
        val doc = fetchDoc(spec.startUrl)
        val analysis = LinkInference.analyseListingPage(
            doc, spec.startUrl, null, booksOnly = true
        )
        if (analysis != null) {
            return listingFrom(doc, spec.startUrl, analysis)
        }
        // The pasted link was a book page, not a catalogue: the
        // catalogue is that one book (it still opens, reads and
        // shelves exactly like any other).
        val bookAnalysis = LinkInference.analyseBookPage(doc, spec.startUrl)
            ?.takeIf { analysis ->
                bookCoherence(doc, spec.startUrl, analysis) == null
            }
            ?: throw SourceException(
                "“$name” didn't show a book list or a book page Xreader can read"
            )
        return SourceBookPage(
            listOf(
                SourceBook(
                    id = spec.startUrl,
                    title = bookAnalysis.title,
                    coverUrl = bookAnalysis.coverUrl
                )
            ),
            hasNextPage = false
        )
    }

    override fun latest(page: Int): SourceBookPage = popular(page)

    override fun search(query: String, page: Int): SourceBookPage {
        if (page > 1) return SourceBookPage(emptyList(), hasNextPage = false)
        val cacheKey = LiveShapeCache.searchKey(host)
        val cachedTemplate = context.store.get(cacheKey)
        val attempts = LinkedHashSet<String>()
        if (cachedTemplate != null) {
            attempts += cachedTemplate.replace(
                "{query}", SearchCandidates.encodeQuery(query)
            )
        }
        attempts += SearchCandidates.conventionUrls(spec.baseUrl, query)
        for (url in attempts) {
            val doc = runCatching { fetchDoc(url) }.getOrNull() ?: continue
            val analysis = LinkInference.analyseSearchResultsPage(
                doc, url, null, booksOnly = true
            )
                ?: continue
            if (analysis.count <= 0) continue
            context.store.put(
                cacheKey,
                url.replace(SearchCandidates.encodeQuery(query), "{query}")
            )
            return listingFrom(doc, url, analysis)
        }
        throw SourceException("“$name” search didn't return a readable results page")
    }

    /** Rows read straight from an already-fetched listing doc
     * through an ephemeral one-page config. */
    private fun listingFrom(
        doc: Document,
        pageUrl: String,
        analysis: LinkInference.ListingAnalysis
    ): SourceBookPage {
        // The fetched document's own URL (post-redirect) is
        // the listing's address — it is also the identity the
        // prefetched-document check in the delegate compares
        // against, so the two can never drift apart.
        val effectiveUrl = doc.location().ifBlank { pageUrl }
        val ephemeral = DeclarativeConfig(
            id = spec.id,
            name = spec.name,
            lang = spec.lang,
            baseUrl = spec.baseUrl,
            popular = ListSpec(
                url = effectiveUrl,
                results = analysis.resultsSelector,
                title = analysis.titleSpec,
                link = analysis.linkSpec,
                cover = analysis.coverSpec,
                // v1.1.1: belt and braces — the rule the
                // inference applied is written into the spec,
                // so the fetch re-checks every item's href
                // shape at parse time too.
                linkPattern = analysis.linkPattern
            ),
            details = DetailsSpec(
                title = "h1",
                chapters = ChapterListSpec(list = "a", name = "@text", link = "@href")
            ),
            chapter = ChapterSpec(content = "article")
        )
        // v1.2.1 (Defect 4): the document that was JUST
        // fetched (and, on an app-shell site, expensively
        // RENDERED) to derive this analysis feeds the read —
        // the listing URL is not fetched a second time for
        // one catalogue open. The delegate still re-reads
        // the live page on every LATER open; only the
        // derivation fetch and the read stop doubling up.
        return DeclarativeSource(ephemeral, context).popular(1, doc)
    }

    // ── details + chapters ──────────────────────────────────

    override fun details(book: SourceBook): SourceBook =
        bookDelegate(book).details(book)

    override fun chapters(book: SourceBook): List<SourceChapter> {
        val delegate = bookDelegate(book)
        val chapters = delegate.chapters(book)
        val config = bookConfigs[book.id]
        if (config != null) {
            for (chapter in chapters) chapterConfigs[chapter.id] = config
        }
        return chapters
    }

    fun detailsAndChapters(book: SourceBook): Pair<SourceBook, List<SourceChapter>> {
        // v1.2.0 (A2): when the delegate derivation JUST fetched
        // the book page, that same document feeds the delegate's
        // read — the book page is fetched (rendered) ONCE for a
        // cold open instead of twice. A cache-hit delegate
        // fetched nothing, so it passes null and the delegate
        // fetches as before.
        val (delegate, prefetched) = bookDelegateWithPrefetch(book)
        val result = delegate.detailsAndChapters(book, prefetched)
        val config = bookConfigs[book.id]
        if (config != null) {
            for (chapter in result.second) chapterConfigs[chapter.id] = config
        }
        return result
    }

    /**
     * The ephemeral declarative twin for one book: derived
     * from the book page itself (title / chapter-list specs
     * from [LinkInference.analyseBookPage], the chapter-text
     * spec from [LinkInference.analyseChapterPage] on the
     * first chapter). A cached shape is used only while the
     * book page's structural fingerprint still matches; any
     * failure evicts and re-derives once.
     */
    private fun bookDelegate(book: SourceBook): DeclarativeSource =
        bookDelegateWithPrefetch(book).first

    /** [bookDelegate] plus the book-page document it fetched
     * along the way (null when the in-memory config cache hit
     * and nothing was fetched) — v1.2.0 (A2) threading for
     * [detailsAndChapters]. */
    private fun bookDelegateWithPrefetch(
        book: SourceBook
    ): Pair<DeclarativeSource, Document?> {
        bookConfigs[book.id]?.let {
            return DeclarativeSource(it, context) to null
        }
        val cacheKey = LiveShapeCache.bookKey(book.id)
        val doc = fetchDoc(book.id)
        val fingerprint = skeletonFingerprint(doc)
        val cached = context.store.get(cacheKey)
        if (cached != null &&
            context.store.get("$cacheKey.fp") == fingerprint
        ) {
            runCatching {
                liveJson.decodeFromString(DeclarativeConfig.serializer(), cached)
            }.getOrNull()?.let { config ->
                bookConfigs[book.id] = config
                return DeclarativeSource(config, context) to doc
            }
        }
        val config = deriveBookConfig(book, doc)
        context.store.put(
            cacheKey,
            liveJson.encodeToString(DeclarativeConfig.serializer(), config)
        )
        context.store.put("$cacheKey.fp", fingerprint)
        bookConfigs[book.id] = config
        return DeclarativeSource(config, context) to doc
    }

    /**
     * v1.1.1: the book-entry coherence gate — the details
     * half of the catalogue fix. Any url reaching book
     * derivation must PROVE it is a book page. For a
     * NON-book-shaped url (a chat link, a comment target,
     * any address a catalogue or a paste can carry) the
     * proof is path extension: the majority of its chapter
     * cluster's links must extend the page's own path — a
     * book's chapters live below the book. A chat page fails
     * this by construction (its links point at comments and
     * books elsewhere, never below `/chat/`), so it can
     * never be dressed up as a book — with the site name or
     * a chat heading for a title and junk for chapters — at
     * any entry point.
     *
     * v1.2.1 (Defect 2): a book-shaped url is no longer
     * trusted outright — the sibling-book check below runs
     * for every url shape (see the check's own comment).
     *
     * Returns a failure reason, or null when coherent.
     */
    private fun bookCoherence(
        doc: Document,
        pageUrl: String,
        analysis: LinkInference.BookPageAnalysis
    ): String? {
        val items = runCatching { doc.select(analysis.chapterListSelector) }
            .getOrDefault(emptyList())
        if (items.isEmpty()) return "no chapter list could be checked"
        // v1.2.1 (Defect 2): the sibling-book check binds EVERY
        // url shape, book-shaped ones included. The v1.1.1 gate
        // trusted a book-shaped address outright — but on
        // app-shell sites the book address IS book-shaped
        // (/mobile/book/<id>) while the derived "chapter list"
        // was a recommendation rail of other books under the
        // same collection. When most of the list's rows are
        // sibling book links, the list is a list of books, not
        // chapters, whatever the page's own address looks like:
        // fail honestly instead of storing books as chapters.
        // (Inference already refuses such rows as chapters;
        // this gate is the second belt for any analysis that
        // reaches derivation by another road.)
        var anchors = 0
        var siblings = 0
        for (item in items) {
            val anchor = if (item.tagName() == "a") item
                else item.selectFirst("a[href]") ?: continue
            anchors++
            if (LinkInference.isSiblingBookLink(anchor, pageUrl)) {
                siblings++
            }
        }
        if (anchors > 0 && siblings * 2 >= anchors) {
            return "its chapter list is a list of other books"
        }
        if (BookShape.isBookShapedHref(pageUrl)) return null
        val bookPath = BookShape.normalisedPath(pageUrl)
        if (bookPath == "/") return "the page address is not a book address"
        var inside = 0
        for (item in items) {
            val anchor = if (item.tagName() == "a") item
                else item.selectFirst("a[href]") ?: continue
            val href = anchor.attr("abs:href").ifBlank { anchor.attr("href") }
            if (BookShape.normalisedPath(href).startsWith("$bookPath/")) {
                inside++
            }
        }
        return if (inside > items.size / 2) {
            null
        } else {
            "its chapter links do not belong to the page"
        }
    }

    private fun deriveBookConfig(book: SourceBook, bookDoc: Document): DeclarativeConfig {
        val analysis = LinkInference.analyseBookPage(bookDoc, book.id)
            ?: throw SourceException(
                "that page didn't read as a book page on “$name”"
            )
        bookCoherence(bookDoc, book.id, analysis)?.let { reason ->
            throw SourceException(
                "that page didn't read as a book page on “$name” ($reason)"
            )
        }
        val chapterDoc = fetchDoc(analysis.firstChapterUrl)
        val chapterAnalysis = LinkInference.analyseChapterPage(chapterDoc, analysis.firstChapterUrl)
            ?: throw SourceException(
                "the chapters on “$name” didn't show readable story text"
            )
        return DeclarativeConfig(
            id = spec.id,
            name = spec.name,
            lang = spec.lang,
            baseUrl = spec.baseUrl,
            details = DetailsSpec(
                title = if (analysis.titleFromSlug) null else analysis.titleSpec,
                author = analysis.authorSpec,
                description = analysis.descriptionSpec,
                cover = analysis.coverSpec,
                chapters = ChapterListSpec(
                    list = analysis.chapterListSelector,
                    name = analysis.chapterNameSpec,
                    link = analysis.chapterLinkSpec,
                    reverse = analysis.newestFirst
                )
            ),
            chapter = ChapterSpec(
                content = chapterAnalysis.contentSelector,
                title = chapterAnalysis.titleSpec,
                nextPage = chapterAnalysis.nextPageSelector
            )
        )
    }

    // ── chapter text ────────────────────────────────────────

    override fun chapterContent(chapter: SourceChapter): SourceChapterContent {
        // The owning book's own shape when the chapter came
        // through a listing; otherwise a host-level chapter
        // shape derived from THIS chapter's own page (chapter
        // pages share one template per site).
        val cachedConfig = chapterConfigs[chapter.id]
            ?: synchronized(bookConfigs) { bookConfigs.values.firstOrNull() }
        if (cachedConfig != null) {
            // No fetch happened to derive this config, so there
            // is no document to thread — the delegate fetches
            // the chapter page itself, exactly as before.
            return DeclarativeSource(cachedConfig, context).chapterContent(chapter)
        }
        // v1.2.0 (A2): the chapter-only derivation FETCHES this
        // chapter's page to learn its shape; that same document
        // is the content read's first page, so a cold chapter
        // open fetches (renders) the page ONCE, not twice.
        val (config, prefetched) = deriveChapterOnlyConfig(chapter)
        return DeclarativeSource(config, context)
            .chapterContent(chapter, prefetched)
    }

    /** The derived chapter-only config PLUS the chapter page
     * fetched to derive it (every path through here fetched
     * it — the fingerprint is computed from that fetch). */
    private fun deriveChapterOnlyConfig(
        chapter: SourceChapter
    ): Pair<DeclarativeConfig, Document> {
        val cacheKey = LiveShapeCache.chapterKey(host)
        val doc = fetchDoc(chapter.id)
        val fingerprint = skeletonFingerprint(doc)
        val cached = context.store.get(cacheKey)
        if (cached != null && context.store.get("$cacheKey.fp") == fingerprint) {
            runCatching {
                liveJson.decodeFromString(ChapterSpec.serializer(), cached)
            }.getOrNull()?.let { chapterSpec ->
                return chapterOnlyConfig(chapterSpec) to doc
            }
        }
        val analysis = LinkInference.analyseChapterPage(doc, chapter.id)
            ?: throw SourceException(
                "this chapter page didn't show readable story text on “$name”"
            )
        val chapterSpec = ChapterSpec(
            content = analysis.contentSelector,
            title = analysis.titleSpec,
            nextPage = analysis.nextPageSelector
        )
        context.store.put(
            cacheKey,
            liveJson.encodeToString(ChapterSpec.serializer(), chapterSpec)
        )
        context.store.put("$cacheKey.fp", fingerprint)
        return chapterOnlyConfig(chapterSpec) to doc
    }

    private fun chapterOnlyConfig(chapterSpec: ChapterSpec): DeclarativeConfig =
        DeclarativeConfig(
            id = spec.id,
            name = spec.name,
            lang = spec.lang,
            baseUrl = spec.baseUrl,
            details = DetailsSpec(
                title = "h1",
                chapters = ChapterListSpec(list = "a", name = "@text", link = "@href")
            ),
            chapter = chapterSpec
        )

    // ── fetching + fingerprints ─────────────────────────────

    private fun fetchDoc(url: String): Document {
        val response = context.http.get(url)
        if (response.status !in 200..299) {
            throw SourceException("the site answered HTTP ${response.status}")
        }
        return Jsoup.parse(response.body, response.finalUrl.ifBlank { url })
    }

    /** v1.1.0 (§6.3 step 1): forget this source's host-keyed
     * derived shapes (search template + chapter shape), so
     * the next read derives again under the current rules.
     * Per-book shapes need no eviction — they are only ever
     * served while the book page's fingerprint still matches,
     * and their keys are versioned (see [SHAPE_VERSION]). */
    fun evictDerivedShapes() {
        LiveShapeCache.evictHostShapes(context.store, host)
    }

    /** A cheap structural fingerprint of a page: the tag +
     * class skeleton of its first elements. When a site
     * redesigns, the fingerprint changes and every cached
     * shape for it is derived again — automatically. */
    private fun skeletonFingerprint(doc: Document): String {
        val skeleton = StringBuilder()
        for (element in doc.select("*").take(300)) {
            skeleton.append(element.tagName())
            val classes = element.className()
            if (classes.isNotBlank()) {
                skeleton.append('.').append(classes.replace(' ', '.'))
            }
            skeleton.append('|')
        }
        return Hashes.sha256Hex(skeleton.toString()).take(16)
    }
}
