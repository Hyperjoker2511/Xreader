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
import java.util.concurrent.ConcurrentHashMap

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
         */
        const val SHAPE_VERSION = "shape.v2"
    }

    /** Per-book ephemeral configs derived this process. */
    private val bookConfigs = ConcurrentHashMap<String, DeclarativeConfig>()

    /** Chapter id → the ephemeral config of the book it was
     * listed from, so a chapter read uses its own book's shape. */
    private val chapterConfigs = ConcurrentHashMap<String, DeclarativeConfig>()

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
        val ephemeral = DeclarativeConfig(
            id = spec.id,
            name = spec.name,
            lang = spec.lang,
            baseUrl = spec.baseUrl,
            popular = ListSpec(
                url = pageUrl,
                results = analysis.resultsSelector,
                title = analysis.titleSpec,
                link = analysis.linkSpec,
                cover = analysis.coverSpec
            ),
            details = DetailsSpec(
                title = "h1",
                chapters = ChapterListSpec(list = "a", name = "@text", link = "@href")
            ),
            chapter = ChapterSpec(content = "article")
        )
        // The delegate would refetch the listing URL; that is
        // acceptable (one extra request) and keeps every read
        // honest against the live page.
        return DeclarativeSource(ephemeral, context).popular(1)
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
        val delegate = bookDelegate(book)
        val result = delegate.detailsAndChapters(book)
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
    private fun bookDelegate(book: SourceBook): DeclarativeSource {
        bookConfigs[book.id]?.let { return DeclarativeSource(it, context) }
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
                return DeclarativeSource(config, context)
            }
        }
        val config = deriveBookConfig(book, doc)
        context.store.put(
            cacheKey,
            liveJson.encodeToString(DeclarativeConfig.serializer(), config)
        )
        context.store.put("$cacheKey.fp", fingerprint)
        bookConfigs[book.id] = config
        return DeclarativeSource(config, context)
    }

    private fun deriveBookConfig(book: SourceBook, bookDoc: Document): DeclarativeConfig {
        val analysis = LinkInference.analyseBookPage(bookDoc, book.id)
            ?: throw SourceException(
                "that page didn't read as a book page on “$name”"
            )
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
        val config = chapterConfigs[chapter.id]
            ?: bookConfigs.values.firstOrNull()
            ?: deriveChapterOnlyConfig(chapter)
        return DeclarativeSource(config, context).chapterContent(chapter)
    }

    private fun deriveChapterOnlyConfig(chapter: SourceChapter): DeclarativeConfig {
        val cacheKey = LiveShapeCache.chapterKey(host)
        val doc = fetchDoc(chapter.id)
        val fingerprint = skeletonFingerprint(doc)
        val cached = context.store.get(cacheKey)
        if (cached != null && context.store.get("$cacheKey.fp") == fingerprint) {
            runCatching {
                liveJson.decodeFromString(ChapterSpec.serializer(), cached)
            }.getOrNull()?.let { chapterSpec ->
                return chapterOnlyConfig(chapterSpec)
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
        return chapterOnlyConfig(chapterSpec)
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
