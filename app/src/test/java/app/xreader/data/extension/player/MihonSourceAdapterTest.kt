package app.xreader.data.extension.player

import app.xreader.core.error.AppResult
import app.xreader.data.FakeInstalledExtensionDao
import app.xreader.data.FakeTransport
import app.xreader.data.db.InstalledExtension
import app.xreader.data.db.TrustState
import app.xreader.data.download.ChapterCandidate
import app.xreader.data.download.FetchOutcome
import app.xreader.data.extension.CleanerRuleStore
import app.xreader.data.extension.ContentRating
import app.xreader.data.extension.ExtensionChapterFetcher
import app.xreader.data.extension.ExtensionService
import app.xreader.data.extension.ExtensionStore
import app.xreader.data.extension.FakeDexLoader
import app.xreader.data.extension.FakeJsRuntime
import app.xreader.data.extension.HostContextFactory
import app.xreader.data.extension.MediaKind
import app.xreader.data.extension.testSessionStore
import app.xreader.data.files.BookStorage
import app.xreader.extensionapi.SourceBook
import app.xreader.extensionapi.SourceChapter
import app.xreader.extensionapi.SourceException
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.online.HttpSource
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * K4: the Mihon→pipeline adapter, proven against an
 * HttpSource-shaped fake compiled against the real
 * :mihon-api (the plan's JVM half — classloading itself is
 * K3/K6's instrumented and device proof). The fake records
 * what the adapter asked it for, so the mappings are asserted
 * at the seam, not by their shadows: catalogue/search
 * mapping, TRUE chapter numbers, page resolution through the
 * source's own image-url path, page bytes through its image
 * request, rating/kind stamping, the disabled-row fan-out
 * rule, and plain-words errors.
 */
class MihonSourceAdapterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val pkg = "eu.kanade.tachiyomi.extension.en.fakepanels"
    private val namespaced = "keiyoushi:$pkg:777"

    private lateinit var source: FakeHttpSource
    private lateinit var adapter: MihonSourceAdapter

    @Before
    fun setUp() {
        source = FakeHttpSource()
        adapter = MihonSourceAdapter(
            packageName = pkg,
            namespacedId = namespaced,
            mihon = source,
            contentRating = ContentRating.MIXED
        )
    }

    // ── identity + stamps ───────────────────────────────────

    @Test
    fun identityIsNamespacedAndStamped() {
        assertEquals(namespaced, adapter.id)
        assertEquals("Fake Panels", adapter.name)
        assertEquals("en", adapter.lang)
        assertEquals("https://panels.test", adapter.baseUrl)
        assertEquals(777L, adapter.mihonId)
        assertEquals(MediaKind.MANGA, adapter.mediaKind)
        assertEquals(ContentRating.MIXED, adapter.contentRating)
    }

    // ── catalogue / search ──────────────────────────────────

    @Test
    fun popularMapsBooksWithAbsolutisedCovers() {
        val page = adapter.popular(1)
        assertEquals(1, page.books.size)
        val book = page.books.single()
        assertEquals("/manga/alpha", book.id)
        assertEquals("Alpha", book.title)
        assertEquals("https://panels.test/covers/alpha.jpg", book.coverUrl)
        assertTrue(page.hasNextPage)
    }

    @Test
    fun callsRunUnderThePackageNamespaceAcrossSuspensions() {
        adapter.popular(1)
        // The fake delays before recording: the namespace
        // element must re-apply after the suspension hop.
        assertEquals(listOf(pkg), source.namespaces)
    }

    @Test
    fun searchPassesTheQueryAndTheDefaultFilterSet() {
        val page = adapter.search("alp", 1)
        assertEquals(1, page.books.size)
        val (query, filters) = source.searchQueries.single()
        assertEquals("alp", query)
        assertTrue(filters.list.isEmpty())
    }

    @Test
    fun appliedFiltersReachTheNextSearchVerbatim() {
        // K5: the sheet edits the source's own filter objects
        // and applies the list; the next search must hand the
        // source THAT list (same instance, edited states) —
        // this is the whole apply contract.
        val filters = adapter.filterList()
        val genre = filters.list.single() as Filter.TriState
        genre.state = Filter.TriState.STATE_INCLUDE
        adapter.applyFilters(filters)
        adapter.search("alp", 1)
        val (query, received) = source.searchQueries.single()
        assertEquals("alp", query)
        assertSame(filters, received)
        assertEquals(
            Filter.TriState.STATE_INCLUDE,
            (received.list.single() as Filter.TriState).state
        )
        assertSame(filters, adapter.appliedFilters())
    }

    @Test
    fun searchBeforeAnyApplyStillUsesTheDefaultSet() {
        adapter.search("alp", 1)
        val (_, received) = source.searchQueries.single()
        assertTrue(received.list.isEmpty())
        assertNull(adapter.appliedFilters())
    }

    @Test
    fun latestFallsBackToPopularWhenTheSourceHasNone() {
        source.latestSupported = false
        adapter.latest(1)
        assertEquals(1, source.popularCalls)
        assertEquals(0, source.latestCalls)
    }

    @Test
    fun filterListPassesThroughForK5() {
        val filters = adapter.filterList()
        assertEquals(1, filters.list.size)
        assertEquals("Genre", filters.list.single().name)
    }

    // ── details / chapters ──────────────────────────────────

    @Test
    fun detailsFillFieldsAndKeepTheBookIdentity() {
        val book = adapter.details(
            SourceBook(id = "/manga/alpha", title = "Alpha")
        )
        assertEquals("/manga/alpha", book.id)
        assertEquals("Some Author", book.author)
        assertEquals("A story.", book.description)
        assertEquals(
            "https://panels.test/covers/alpha-hd.jpg", book.coverUrl
        )
        assertEquals(listOf(true to false), source.updateCalls)
    }

    @Test
    fun chaptersCarryTrueNumbersInReadingOrderWithDates() {
        val chapters = adapter.chapters(
            SourceBook(id = "/manga/alpha", title = "Alpha")
        )
        assertEquals(listOf(false to true), source.updateCalls)
        // The source answered newest-first (3, 2, 1); the
        // facade contract is reading order, by TRUE number.
        assertEquals(
            listOf("/ch/1", "/ch/2", "/ch/3"), chapters.map { it.id }
        )
        assertEquals(
            listOf(1f, 2f, 3f), chapters.map { it.number }
        )
        assertEquals(
            listOf("1700000001000", "1700000002000", "1700000003000"),
            chapters.map { it.dateText }
        )
    }

    @Test
    fun unnumberedChaptersReverseToReadingOrderWithNoInventedNumbers() {
        source.chaptersNewestFirst = listOf(
            SChapter(
                name = "Chapter C", url = "/ch/c",
                date_upload = 1700000003000
            ),
            SChapter(
                name = "Chapter B", url = "/ch/b",
                date_upload = 1700000002000
            ),
            SChapter(
                name = "Chapter A", url = "/ch/a",
                date_upload = 1700000001000
            )
        )
        val chapters = adapter.chapters(
            SourceBook(id = "/manga/alpha", title = "Alpha")
        )
        assertEquals(
            listOf("/ch/a", "/ch/b", "/ch/c"), chapters.map { it.id }
        )
        assertTrue(chapters.all { it.number == null })
    }

    // ── chapter pages ───────────────────────────────────────

    @Test
    fun chapterContentResolvesPagesThroughTheSourcesImageUrlPath() {
        val content = adapter.chapterContent(
            SourceChapter(id = "/ch/1", name = "Chapter 1")
        )
        // Page 1 carried its address; page 2's was resolved
        // through the fake's getImageUrl (its signed-CDN
        // transform) — the recorded call proves the path ran.
        assertEquals(listOf("/read/p2"), source.imageUrlCalls)
        val pages = adapter.pagesOf("/ch/1")
        assertNotNull(pages)
        assertEquals(
            listOf(
                "https://cdn.test/1.jpg",
                "https://cdn.test/signed/p2"
            ),
            pages!!.urls
        )
        assertTrue(content.html.contains("https://cdn.test/1.jpg"))
        assertTrue(content.html.contains("https://cdn.test/signed/p2"))
    }

    @Test
    fun chapterPagesCarryTheSourcesBaseUrlAsReferer() {
        // K6: the online reader needs the source's Referer
        // for CDNs that demand it (downloads already ride
        // the source's own image request). The record keeps
        // it through the download write-back and the storage
        // JSON round-trip.
        adapter.chapterContent(
            SourceChapter(id = "/ch/1", name = "Chapter 1")
        )
        val pages = adapter.pagesOf("/ch/1")!!
        assertEquals("https://panels.test/", pages.referer)
        val downloaded = pages.copy(
            pages = pages.pages.map { it.copy(localFile = "p.jpg") }
        )
        assertEquals(
            "https://panels.test/", downloaded.referer
        )
        val json = kotlinx.serialization.json.Json {
            encodeDefaults = false
        }
        val restored = json.decodeFromString(
            app.xreader.domain.content.ChapterPages.serializer(),
            json.encodeToString(
                app.xreader.domain.content.ChapterPages.serializer(),
                pages
            )
        )
        assertEquals(pages, restored)
    }

    @Test
    fun emptyPageListFailsInPlainWords() {
        source.pages = emptyList()
        try {
            adapter.chapterContent(SourceChapter(id = "/ch/1", name = "C1"))
            fail("expected a SourceException")
        } catch (e: SourceException) {
            assertEquals("the chapter pages weren't found", e.message)
        }
    }

    // ── page bytes (the download road) ──────────────────────

    @Test
    fun pageBytesRunThroughTheSourcesImageRequest() = runBlocking {
        adapter.chapterContent(SourceChapter(id = "/ch/1", name = "C1"))
        val result = adapter.pageBytes("https://cdn.test/signed/p2")
        assertTrue(result is AppResult.Ok)
        assertEquals(
            FakeHttpSource.PAGE_BYTES.toList(),
            (result as AppResult.Ok).value.toList()
        )
        // The bytes were fetched by executing the source's
        // image request for the RESOLVED page — its imageUrl
        // is the signed address, under the package namespace.
        assertEquals(
            listOf("https://cdn.test/signed/p2"), source.imageRequests
        )
        assertTrue(source.namespaces.all { it == pkg })
        // A URL the adapter never resolved is not its business.
        assertNull(adapter.pageBytes("https://elsewhere.test/x.jpg"))
    }

    // ── error honesty ───────────────────────────────────────

    @Test
    fun httpAnswersAreNamedByCode() {
        source.failure = HttpException(403)
        try {
            adapter.popular(1)
            fail("expected a SourceException")
        } catch (e: SourceException) {
            assertEquals("the site answered HTTP 403", e.message)
        }
    }

    @Test
    fun missingOkHttpClassGetsThePlayerWording() {
        source.failure = NoClassDefFoundError("okhttp3/CompressionInterceptor")
        try {
            adapter.popular(1)
            fail("expected a SourceException")
        } catch (e: SourceException) {
            assertTrue(
                e.message!!.contains("newer network library")
            )
        }
    }

    @Test
    fun theSourcesOwnReadableMessageIsKept() {
        source.failure = RuntimeException("Chapter list moved")
        try {
            adapter.popular(1)
            fail("expected a SourceException")
        } catch (e: SourceException) {
            assertEquals("Chapter list moved", e.message)
        }
    }

    // ── the service seam ────────────────────────────────────

    private data class ServiceStack(
        val service: ExtensionService,
        val dao: FakeInstalledExtensionDao
    )

    private fun serviceStack(
        live: Map<String, eu.kanade.tachiyomi.source.Source>,
        rating: ContentRating? = ContentRating.MIXED,
        enabled: Boolean = true,
        playerEnabled: Boolean = true
    ): ServiceStack {
        val dao = FakeInstalledExtensionDao()
        val store = ExtensionStore(tmp.newFolder())
        val factory = HostContextFactory(
            store, testSessionStore(),
            FakeTransport { FakeTransport.ok("") }
        )
        val service = ExtensionService(
            dao, store, factory, FakeDexLoader(),
            keiyoushiLiveSources = { live },
            keiyoushiRatingProvider = { rating },
            keiyoushiPlayerEnabled = { playerEnabled }
        ) { FakeJsRuntime() }
        runBlocking {
            dao.insert(
                InstalledExtension(
                    repoId = 1,
                    displayName = "Fake Panels",
                    packageId = pkg,
                    versionName = "1.6.5",
                    versionCode = 106005,
                    kind = app.xreader.data.db.ExtensionKind.KEIYOUSHI,
                    artifactPath = null,
                    sha256 = "abc",
                    apiLevel = 16,
                    languages = "en",
                    sourceIds = "777",
                    enabled = enabled,
                    trustState = TrustState.TRUSTED,
                    permissionsJson = "{}",
                    installedAt = 0,
                    updatedAt = 0
                )
            )
        }
        return ServiceStack(service, dao)
    }

    @Test
    fun keiyoushiRowYieldsStampedAdapterHandles() = runBlocking {
        val stack = serviceStack(mapOf(namespaced to source))
        val rowId = stack.dao.extensions.value.single().id
        val handles = (stack.service.sourcesOf(rowId) as AppResult.Ok).value
        assertEquals(1, handles.size)
        val handle = handles.single()
        assertEquals(namespaced, handle.source.id)
        assertEquals(
            MediaKind.MANGA, stack.service.mediaKindOf(handle)
        )
        assertEquals(
            ContentRating.MIXED, stack.service.contentRatingOf(handle)
        )
        // Both id shapes resolve: the namespaced facade id,
        // and the bare Mihon id the row itself declares.
        assertTrue(
            stack.service.source(rowId, namespaced) is AppResult.Ok
        )
        assertTrue(stack.service.source(rowId, "777") is AppResult.Ok)
        assertEquals(
            listOf(namespaced),
            stack.service.allSources().map { it.source.id }
        )
    }

    @Test
    fun unknownRatingFailsClosedToAdult() = runBlocking {
        val stack = serviceStack(
            mapOf(namespaced to source), rating = null
        )
        val rowId = stack.dao.extensions.value.single().id
        val handle = (stack.service.sourcesOf(rowId) as AppResult.Ok)
            .value.single()
        assertEquals(
            ContentRating.ADULT, stack.service.contentRatingOf(handle)
        )
    }

    @Test
    fun disabledRowJoinsNoFanOut() = runBlocking {
        val stack = serviceStack(
            mapOf(namespaced to source), enabled = false
        )
        // The sources-off rule: off only because the user
        // turned it off — and then it is off everywhere.
        assertTrue(stack.service.allSources().isEmpty())
        val rowId = stack.dao.extensions.value.single().id
        assertTrue(stack.service.sourcesOf(rowId) is AppResult.Err)
    }

    @Test
    fun unloadedPackageSimplyDoesNotAppear() = runBlocking {
        val stack = serviceStack(emptyMap())
        val rowId = stack.dao.extensions.value.single().id
        val loaded = stack.service.sourcesOf(rowId)
        assertTrue(loaded is AppResult.Ok)
        assertTrue((loaded as AppResult.Ok).value.isEmpty())
        assertTrue(stack.service.allSources().isEmpty())
    }

    @Test
    fun playerTurnedOffRefusesPlayerRowsInPlainWords() = runBlocking {
        // K6 kill switch: with the master switch off the
        // player contributes nothing to fan-out, and opening
        // a player book names the switch — NOT "switched
        // off" (that phrase belongs to the per-source card
        // and would loop its re-enable button).
        val stack = serviceStack(
            mapOf(namespaced to source), playerEnabled = false
        )
        assertTrue(stack.service.allSources().isEmpty())
        val rowId = stack.dao.extensions.value.single().id
        val result = stack.service.sourcesOf(rowId)
        assertTrue(result is AppResult.Err)
        val detail = (result as AppResult.Err).error.detail.orEmpty()
        assertTrue(detail.contains("turned off"))
        assertTrue(detail.contains("Customise"))
        assertTrue(!detail.contains("switched off"))
    }

    // ── the chapter fetcher seam ────────────────────────────

    private fun fetcherStack(
        fetchPageFiles: Boolean,
        storage: BookStorage?
    ): Pair<ExtensionChapterFetcher, ServiceStack> {
        val stack = serviceStack(mapOf(namespaced to source))
        val rowId = stack.dao.extensions.value.single().id
        val store = ExtensionStore(tmp.newFolder())
        val fetcher = ExtensionChapterFetcher(
            service = stack.service,
            cleanerStore = CleanerRuleStore(store),
            extensionId = rowId,
            sourceId = namespaced,
            bookId = 7,
            storage = storage,
            fetchPageFiles = fetchPageFiles
        )
        return fetcher to stack
    }

    @Test
    fun fetcherReturnsThePageRecordForAnAdapterChapter() = runBlocking {
        val (fetcher, _) = fetcherStack(
            fetchPageFiles = false, storage = null
        )
        val outcome = fetcher.fetch(
            ChapterCandidate(
                mapId = 0, label = "Chapter 1",
                locator = "/ch/1", bookId = 7
            )
        )
        assertTrue(outcome is FetchOutcome.Success)
        val pages = (outcome as FetchOutcome.Success).content.pages
        assertNotNull(pages)
        assertEquals(
            listOf(
                "https://cdn.test/1.jpg",
                "https://cdn.test/signed/p2"
            ),
            pages!!.urls
        )
    }

    @Test
    fun downloadRoadFillsPageFilesThroughTheImageRequest() = runBlocking {
        val storage = BookStorage(tmp.newFolder())
        val (fetcher, _) = fetcherStack(
            fetchPageFiles = true, storage = storage
        )
        val outcome = fetcher.fetch(
            ChapterCandidate(
                mapId = 0, label = "Chapter 1",
                locator = "/ch/1", bookId = 7
            )
        )
        assertTrue(outcome is FetchOutcome.Success)
        val pages = (outcome as FetchOutcome.Success).content.pages!!
        // Every page filled, every file on disk — and the
        // fetches ran through the source's image request.
        assertEquals(2, pages.downloadedCount)
        for (page in pages.pages) {
            val name = page.localFile
            assertNotNull(name)
            assertTrue(File(storage.contentDir(7), name!!).isFile)
        }
        assertEquals(
            setOf(
                "https://cdn.test/1.jpg",
                "https://cdn.test/signed/p2"
            ),
            source.imageRequests.toSet()
        )
    }

    // ── the fake ────────────────────────────────────────────

    /**
     * An HttpSource-shaped fake: canned catalogue/details/
     * chapters/pages, a signed-CDN image-url transform for
     * pages that arrive without an address, and an image
     * request that answers canned bytes while recording what
     * it was asked for (and under which namespace).
     */
    private class FakeHttpSource(
        override val id: Long = 777L
    ) : HttpSource() {

        override val name: String = "Fake Panels"
        override val lang: String = "en"
        override val baseUrl: String = "https://panels.test"

        var latestSupported: Boolean = true
        override val supportsLatest: Boolean get() = latestSupported

        var popularMangas: List<SManga> = listOf(
            SManga(
                url = "/manga/alpha",
                title = "Alpha",
                thumbnail_url = "/covers/alpha.jpg"
            )
        )
        var chaptersNewestFirst: List<SChapter> = listOf(
            SChapter(
                name = "Chapter 3", url = "/ch/3",
                date_upload = 1700000003000, chapter_number = 3f
            ),
            SChapter(
                name = "Chapter 2", url = "/ch/2",
                date_upload = 1700000002000, chapter_number = 2f
            ),
            SChapter(
                name = "Chapter 1", url = "/ch/1",
                date_upload = 1700000001000, chapter_number = 1f
            )
        )
        var pages: List<Page> = listOf(
            Page(0, url = "/read/p1", imageUrl = "https://cdn.test/1.jpg"),
            Page(1, url = "/read/p2", imageUrl = null)
        )
        var failure: Throwable? = null

        var popularCalls = 0
        var latestCalls = 0
        val searchQueries = mutableListOf<Pair<String, FilterList>>()
        val updateCalls = mutableListOf<Pair<Boolean, Boolean>>()
        val imageUrlCalls = mutableListOf<String>()
        // Written from the download pass's parallel fetches.
        val imageRequests: MutableList<String?> =
            java.util.Collections.synchronizedList(mutableListOf())
        val namespaces: MutableList<String> =
            java.util.Collections.synchronizedList(mutableListOf())

        private fun gate() {
            failure?.let { throw it }
        }

        override suspend fun getPopularManga(page: Int): MangasPage {
            gate()
            popularCalls++
            // A real suspension: the namespace assertion in
            // the tests only means something if the call
            // resumes on a thread the caller didn't bracket.
            delay(1)
            namespaces += PlayerContextState.currentNamespace()
            return MangasPage(popularMangas, hasNextPage = true)
        }

        override suspend fun getLatestUpdates(page: Int): MangasPage {
            gate()
            latestCalls++
            return MangasPage(popularMangas, hasNextPage = false)
        }

        override suspend fun getSearchManga(
            page: Int, query: String, filters: FilterList
        ): MangasPage {
            gate()
            searchQueries += query to filters
            return MangasPage(
                popularMangas.filter {
                    it.title.contains(query, ignoreCase = true)
                },
                hasNextPage = false
            )
        }

        override suspend fun getMangaUpdate(
            manga: SManga,
            chapters: List<SChapter>,
            fetchDetails: Boolean,
            fetchChapters: Boolean
        ): SMangaUpdate {
            gate()
            updateCalls += fetchDetails to fetchChapters
            val details = if (fetchDetails) {
                SManga(
                    url = manga.url,
                    title = manga.title,
                    author = "Some Author",
                    description = "A story.",
                    thumbnail_url = "/covers/alpha-hd.jpg",
                    initialized = true
                )
            } else {
                manga
            }
            return SMangaUpdate(
                details,
                if (fetchChapters) chaptersNewestFirst else emptyList()
            )
        }

        override suspend fun getPageList(
            chapter: SChapter
        ): List<Page> {
            gate()
            return pages
        }

        override suspend fun getImageUrl(page: Page): String {
            imageUrlCalls += page.url
            return "https://cdn.test/signed/" +
                page.url.substringAfterLast('/')
        }

        override suspend fun getImage(page: Page): Response {
            imageRequests += page.imageUrl
            namespaces += PlayerContextState.currentNamespace()
            return Response.Builder()
                .request(
                    Request.Builder()
                        .url(page.imageUrl ?: "https://panels.test/")
                        .build()
                )
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(
                    PAGE_BYTES.toResponseBody(
                        "image/png".toMediaType()
                    )
                )
                .build()
        }

        override fun getFilterList(): FilterList =
            // K5: a tri-state (not a header) so the adapter
            // test can edit its state and watch the edited
            // list reach the next search.
            FilterList(object : Filter.TriState("Genre") {})

        companion object {
            val PAGE_BYTES = "fake-png-bytes".toByteArray()
        }
    }
}
