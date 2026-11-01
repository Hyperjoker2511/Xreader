package app.xreader.data.extension.player

import app.xreader.core.error.AppError
import app.xreader.core.error.AppResult
import app.xreader.data.extension.ContentRating
import app.xreader.data.extension.MediaKind
import app.xreader.domain.content.ChapterPage
import app.xreader.domain.content.ChapterPages
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import java.io.IOException
import java.net.URI
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import app.xreader.extensionapi.Source as FacadeSource
import app.xreader.extensionapi.SourceBook
import app.xreader.extensionapi.SourceBookPage
import app.xreader.extensionapi.SourceChapter
import app.xreader.extensionapi.SourceChapterContent
import app.xreader.extensionapi.SourceException
import eu.kanade.tachiyomi.source.Source as MihonSource

/**
 * K4 (Keiyoushi player): one live Mihon source presented as a
 * facade [FacadeSource], so the whole existing pipeline —
 * catalogue, search, book sync, chapter fetch, downloads —
 * consumes a Keiyoushi source exactly like a declarative or
 * JS source, through [app.xreader.data.extension.ExtensionService]'s
 * guarded call path. The plan's adapter (§K4), with the donor
 * shapes kept: the Mihon model objects are mapped field by
 * field, never re-parsed.
 *
 * Identity: the facade [id] is the namespaced id
 * "keiyoushi:<packageName>:<mihonId>" (the same key the player
 * manager's live registry uses), so Mihon Long ids can never
 * collide with another package's or with a native source's.
 * A book's remote id is the source's own [SManga.url]; a
 * chapter's remote id is its [SChapter.url].
 *
 * Every Mihon entry-point call runs inside
 * [PlayerNamespaceElement] — K2/K3's bracketing rule, carried
 * across suspension and thread hops by a coroutine context
 * element (a Mihon call's segments resume on OkHttp threads;
 * the namespace must be in force on whichever thread a
 * segment runs on, because the injected Application reads it
 * per call). The facade is blocking by contract, so each call
 * bridges through `runBlocking`, the host's established shape
 * for its suspend seams.
 *
 * Errors: anything a source throws is converted HERE to a
 * facade [SourceException] with plain words (HTTP codes
 * named, linkage/script-engine gaps worded by
 * [PlayerFailureWords], a source's own readable message kept)
 * — a stack trace or a raw linkage error never crosses into
 * the guarded path, whose own catch only handles Exceptions.
 */
class MihonSourceAdapter(
    /** The Keiyoushi package this source came from — the
     * player namespace every call is bracketed with. */
    val packageName: String,
    /** "keiyoushi:<packageName>:<mihonId>". */
    val namespacedId: String,
    /** The live Mihon source, from the player's registry. */
    val mihon: MihonSource,
    /** The extension's index contentWarning, mapped by
     * [app.xreader.data.extension.KeiyoushiIndex] — stamped
     * per source so the ONE content preference governs player
     * sources exactly like native ones. */
    val contentRating: ContentRating
) : FacadeSource {

    /** The source's own Mihon id (the registry's suffix). */
    val mihonId: Long get() = mihon.id

    /** Player sources are manga by construction. */
    val mediaKind: MediaKind get() = MediaKind.MANGA

    override val id: String = namespacedId

    override val name: String get() = mihon.name

    override val lang: String
        get() = (mihon as? CatalogueSource)?.lang ?: "multi"

    override val baseUrl: String
        get() = (mihon as? HttpSource)?.baseUrl ?: ""

    // ── listings ────────────────────────────────────────────

    override fun search(query: String, page: Int): SourceBookPage =
        callMihon {
            // K5: the catalogue's filter sheet applies its
            // FilterList onto the adapter (see [applyFilters]);
            // until it does, search runs on the source's
            // default (empty) filter set, exactly as before.
            mapMangasPage(
                mihon.getSearchManga(page, query, appliedFilterList ?: FilterList())
            )
        }

    override fun popular(page: Int): SourceBookPage =
        callMihon { mapMangasPage(mihon.getPopularManga(page)) }

    override fun latest(page: Int): SourceBookPage =
        callMihon {
            mapMangasPage(
                if (mihon.supportsLatest) {
                    mihon.getLatestUpdates(page)
                } else {
                    // The facade's own convention (a native
                    // source with no latest list falls back to
                    // its popular list).
                    mihon.getPopularManga(page)
                }
            )
        }

    // ── book + chapters ─────────────────────────────────────

    override fun details(book: SourceBook): SourceBook = callMihon {
        val manga = mihon.getMangaUpdate(
            manga = smangaOf(book),
            chapters = emptyList(),
            fetchDetails = true,
            fetchChapters = false
        ).manga
        SourceBook(
            id = manga.url.takeIf { it.isNotBlank() } ?: book.id,
            title = manga.title.takeIf { it.isNotBlank() } ?: book.title,
            author = manga.author?.takeIf { it.isNotBlank() }
                ?: book.author,
            coverUrl = manga.thumbnail_url
                ?.takeIf { it.isNotBlank() }
                ?.let { absolutise(it) }
                ?: book.coverUrl,
            description = manga.description?.takeIf { it.isNotBlank() }
                ?: book.description
        )
    }

    override fun chapters(book: SourceBook): List<SourceChapter> =
        callMihon {
            val update = mihon.getMangaUpdate(
                manga = smangaOf(book),
                chapters = emptyList(),
                fetchDetails = false,
                fetchChapters = true
            )
            readingOrder(update.chapters).map { chapter ->
                SourceChapter(
                    id = chapter.url,
                    name = chapter.name,
                    // TRUE chapter number, from the model —
                    // never the row's position (the app's
                    // chapter-identity rule). Mihon's -1 (or
                    // any negative) means "unknown" and maps
                    // to null, not to a fabricated number.
                    number = chapter.chapter_number
                        .takeIf { it >= 0f },
                    // Epoch millis as text: the chapter-date
                    // renderer parses all-digit values as
                    // instants, so the relative wording stays
                    // honest ("3 days ago", never a guess).
                    dateText = chapter.date_upload
                        .takeIf { it > 0 }?.toString()
                )
            }
        }

    /**
     * Reading order from a Mihon chapter list. Sources return
     * chapters newest-first; when EVERY chapter carries a
     * true number the number is the order (stable sort —
     * equal numbers keep the source's order), otherwise the
     * list is reversed into oldest-first. Position is never
     * turned into a number either way; the numbers ride
     * separately on each mapped chapter.
     */
    private fun readingOrder(chapters: List<SChapter>): List<SChapter> =
        if (chapters.isNotEmpty() &&
            chapters.all { it.chapter_number >= 0f }
        ) {
            chapters.sortedBy { it.chapter_number }
        } else {
            chapters.reversed()
        }

    // ── chapter pages ───────────────────────────────────────

    override fun chapterContent(
        chapter: SourceChapter
    ): SourceChapterContent = callMihon {
        val pages = mihon.getPageList(schapterOf(chapter))
        if (pages.isEmpty()) {
            throw SourceException("the chapter pages weren't found")
        }
        val resolved = resolvePages(pages)
        synchronized(pagesCache) { pagesCache[chapter.id] = resolved }
        SourceChapterContent(
            // The honest string form of an image chapter for
            // any consumer that only speaks the facade (the
            // declarative engine's manga shape, mirrored):
            // the pages as an <img> fragment, in order. The
            // fetch path reads the record itself via [pagesOf].
            html = resolved.pages.joinToString("\n") { page ->
                "<img src=\"" +
                    page.url.replace("&", "&amp;")
                        .replace("\"", "&quot;") +
                    "\"/>"
            },
            title = chapter.name
        )
    }

    /**
     * Turn Mihon [Page]s into the [ChapterPages] record the
     * image reader and the page downloader already consume.
     * A page whose image address the source left open is
     * resolved through the source's own image-url path
     * ([HttpSource.getImageUrl] — its imageUrlRequest /
     * imageUrlParse pair, which is where a source like Asura
     * Scans puts its URL logic); a page that already carries
     * its address keeps it (no pointless request). Either way
     * the final address is written back onto the [Page] and
     * the Page is retained, so a later download can fetch the
     * bytes through the source's own image request (see
     * [pageBytes]) instead of a bare, header-less GET.
     */
    private suspend fun resolvePages(pages: List<Page>): ChapterPages {
        val http = mihon as? HttpSource
        val out = ArrayList<ChapterPage>(pages.size)
        for (page in pages) {
            val raw = page.imageUrl?.takeIf { it.isNotBlank() }
                ?: if (http != null) {
                    http.getImageUrl(page)
                } else {
                    throw SourceException(
                        "a page's image address wasn't given"
                    )
                }
            val absolute = absolutise(raw)
            page.imageUrl = absolute
            out += ChapterPage(url = absolute)
            synchronized(retainedPages) { retainedPages[absolute] = page }
        }
        // K6: stamp the record with the Referer the online
        // reader should present for these pages. Downloads
        // fetch through the source's own image request (its
        // headers ride along), but the reader's Coil path can
        // only carry what the record holds — and the value
        // Mihon sources put in their imageRequest Referer is
        // their own base URL, so that is the honest stamp.
        // A source with no base URL (a plain Source, not an
        // HttpSource) leaves it null, exactly as before.
        val referer = baseUrl.takeIf { it.isNotBlank() }?.let {
            if (it.endsWith("/")) it else "$it/"
        }
        return ChapterPages(out, referer = referer)
    }

    /** The page record of a chapter read through this
     * adapter, or null when the chapter isn't cached (evicted
     * / never read) — the fetch path treats null as "no page
     * record", exactly like the declarative engine's. */
    fun pagesOf(chapterId: String): ChapterPages? =
        synchronized(pagesCache) { pagesCache[chapterId] }

    /**
     * The download road for one page's bytes: the fetch runs
     * through the source's OWN image request
     * ([HttpSource.getImage] — its imageRequest with its
     * headers, executed on its client, whose cookie jar is
     * the player's session bridge: the same merged per-host
     * session resolution the source's own traffic gets).
     * Returns null when [url] is not a page this adapter
     * resolved (or the source has no HTTP image path) — the
     * caller then uses its default byte path, as for any
     * other source kind.
     */
    suspend fun pageBytes(url: String): AppResult<ByteArray>? {
        val page = synchronized(retainedPages) { retainedPages[url] }
            ?: return null
        val http = mihon as? HttpSource ?: return null
        return try {
            val bytes = bracketed {
                http.getImage(page).use { response ->
                    response.body?.bytes()
                        ?: throw SourceException(
                            "the page image came back empty"
                        )
                }
            }
            if (bytes.isEmpty()) {
                AppResult.Err(
                    AppError.ExtensionFailed(
                        name, "the page image came back empty"
                    )
                )
            } else {
                AppResult.Ok(bytes)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            val failure = asSourceFailure(t)
            AppResult.Err(
                AppError.ExtensionFailed(
                    name,
                    failure.message ?: "the page image couldn't be fetched"
                )
            )
        }
    }

    // ── enrichment + K5 doors ───────────────────────────────

    override fun related(book: SourceBook): List<SourceBook>? {
        val catalogue = mihon as? CatalogueSource ?: return null
        if (!catalogue.supportsRelatedMangas) return null
        return callMihon {
            try {
                catalogue.fetchRelatedMangaList(smangaOf(book))
                    .map { it.toFacadeBook() }
            } catch (e: UnsupportedOperationException) {
                // The contract's own "not offered" answer —
                // the facade's null, not a failure.
                null
            }
        }
    }

    /**
     * The source's own filter list — K5's filter sheet reads
     * it from here and edits the returned objects in place
     * (the donor model: a FilterList's filters ARE the state).
     * Nothing is applied to searches until the sheet hands
     * the list back through [applyFilters].
     */
    fun filterList(): FilterList = callMihon { mihon.getFilterList() }

    /** The filter set the sheet last applied — the exact
     * list instance the next search passes to the source.
     * Held on the adapter (one adapter per catalogue visit,
     * resolved once by the catalogue's ViewModel), so filter
     * state lives and dies with the visit: reopening the
     * source starts from the source's defaults again. */
    @Volatile
    private var appliedFilterList: FilterList? = null

    /** K5: the filter sheet's Apply — searches from now on
     * run with [filters] until another list is applied. */
    fun applyFilters(filters: FilterList) {
        appliedFilterList = filters
    }

    /** The currently applied filter list, or null when the
     * sheet has never applied one this visit. */
    fun appliedFilters(): FilterList? = appliedFilterList

    // ── mapping helpers ─────────────────────────────────────

    private fun mapMangasPage(page: MangasPage): SourceBookPage =
        SourceBookPage(
            books = page.mangas.map { it.toFacadeBook() },
            hasNextPage = page.hasNextPage
        )

    private fun SManga.toFacadeBook(): SourceBook = SourceBook(
        id = url,
        title = title,
        author = author?.takeIf { it.isNotBlank() },
        coverUrl = thumbnail_url?.takeIf { it.isNotBlank() }
            ?.let { absolutise(it) },
        description = description?.takeIf { it.isNotBlank() }
    )

    /** Rebuild the Mihon manga a call needs from the facade
     * book: the url is the identity every Mihon entry point
     * keys on; the display fields ride along so a source
     * that echoes them back loses nothing. */
    private fun smangaOf(book: SourceBook): SManga =
        SManga.create().apply {
            url = book.id
            title = book.title
            author = book.author
            description = book.description
            thumbnail_url = book.coverUrl
            initialized = true
        }

    private fun schapterOf(chapter: SourceChapter): SChapter =
        SChapter.create().apply {
            url = chapter.id
            name = chapter.name
            chapter_number = chapter.number ?: -1f
        }

    /** Absolutise a possibly-relative address against the
     * source's base url (Mihon models mix absolute urls,
     * root-relative paths and protocol-relative hosts). */
    private fun absolutise(url: String): String {
        if (url.startsWith("http://") || url.startsWith("https://")) {
            return url
        }
        val base = baseUrl
        if (base.isBlank()) return url
        return runCatching { URI(base).resolve(url).toString() }
            .getOrElse {
                base.trimEnd('/') + "/" + url.trimStart('/')
            }
    }

    // ── the call bridge ─────────────────────────────────────

    /**
     * Run one Mihon entry-point call: bracketed with this
     * package's namespace (the element re-applies it after
     * every suspension/thread hop), bridged to the facade's
     * blocking shape, with every failure converted to plain
     * words by [asSourceFailure].
     */
    private fun <T> callMihon(block: suspend () -> T): T {
        return try {
            runBlocking { bracketed(block) }
        } catch (e: CancellationException) {
            // Inside the bridge's own runBlocking a
            // cancellation can only be the source's own
            // timeout machinery — a plain failure, worded
            // like one below.
            throw asSourceFailure(e)
        } catch (t: Throwable) {
            throw asSourceFailure(t)
        }
    }

    /** The namespace-bracketed suspend segment (also the
     * shape [pageBytes] uses directly — it is already in a
     * coroutine when the service calls it). */
    private suspend fun <T> bracketed(block: suspend () -> T): T =
        withContext(PlayerNamespaceElement(packageName)) { block() }

    /**
     * Plain words for anything a source can throw, in the
     * app's error shapes: HTTP answers named by code, the
     * player's known gaps (missing class, script engine,
     * OkHttp version) worded by [PlayerFailureWords], a lost
     * site as a lost site, and a source's own readable
     * message kept as-is. Nothing raw crosses.
     */
    private fun asSourceFailure(t: Throwable): SourceException =
        when {
            t is SourceException -> t
            t is HttpException ->
                SourceException("the site answered HTTP ${t.code}")
            t is CancellationException ->
                SourceException("the source took too long to answer")
            isPlayerGap(t) ->
                SourceException(PlayerFailureWords.describe(t).reason)
            t is IOException ->
                SourceException("the site couldn't be reached")
            else -> SourceException(
                t.message?.takeIf { it.isNotBlank() }?.take(300)
                    ?: "the source failed (${t.javaClass.simpleName})"
            )
        }

    /** The gaps [PlayerFailureWords] has wording for: a class
     * the app doesn't carry (anywhere in the chain), or the
     * script-engine stub's named refusal. */
    private fun isPlayerGap(t: Throwable): Boolean =
        generateSequence(t) { it.cause }.any { e ->
            PlayerFailureWords.missingClassOf(e) != null ||
                (e is UnsupportedOperationException &&
                    e.message?.contains("JavaScriptEngine") == true)
        }

    // ── retained state ──────────────────────────────────────

    /** Page records of recent chapterContent() calls, keyed
     * by chapter id — bounded access-ordered LRU, the
     * declarative engine's shape (a miss costs a re-read;
     * callers already treat null that way). */
    private val pagesCache =
        object : LinkedHashMap<String, ChapterPages>(16, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, ChapterPages>
            ): Boolean = size > MAX_CACHED_CHAPTERS
        }

    /** Resolved Mihon [Page]s by final image url — the
     * download road's way back to the source's image request.
     * Bounded like [pagesCache]; an evicted page simply falls
     * back to the caller's default byte path. */
    private val retainedPages =
        object : LinkedHashMap<String, Page>(16, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, Page>
            ): Boolean = size > MAX_RETAINED_PAGES
        }

    private companion object {
        const val MAX_CACHED_CHAPTERS = 24
        const val MAX_RETAINED_PAGES = 2048
    }
}

/**
 * K4: installs the player's per-extension namespace on
 * whichever thread a coroutine segment runs on. Mihon calls
 * suspend and resume across OkHttp dispatcher threads; the
 * injected Application resolves its namespace from the
 * CURRENT thread on every call ([PlayerContextState]), so a
 * plain `withNamespace` around the outer call is not enough —
 * the element re-applies the namespace after every hop and
 * restores the thread's previous value on suspension.
 */
internal class PlayerNamespaceElement(
    private val namespace: String
) : ThreadContextElement<String?> {

    companion object Key : CoroutineContext.Key<PlayerNamespaceElement>

    override val key: CoroutineContext.Key<PlayerNamespaceElement>
        get() = Key

    override fun updateThreadContext(
        context: CoroutineContext
    ): String? = PlayerContextState.swapNamespace(namespace)

    override fun restoreThreadContext(
        context: CoroutineContext,
        oldState: String?
    ) {
        PlayerContextState.swapNamespace(oldState)
    }
}
