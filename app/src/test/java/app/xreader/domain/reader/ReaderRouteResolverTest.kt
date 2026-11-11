package app.xreader.domain.reader

import app.xreader.core.error.AppError
import app.xreader.core.error.AppResult
import app.xreader.data.FakeBookDao
import app.xreader.data.FakeChapterDao
import app.xreader.data.FakeInstalledExtensionDao
import app.xreader.data.FakeTransport
import app.xreader.data.db.Book
import app.xreader.data.db.Chapter
import app.xreader.data.db.InstalledExtension
import app.xreader.data.db.SourceType
import app.xreader.data.db.TrustState
import app.xreader.data.download.ChapterCandidate
import app.xreader.data.download.FetchOutcome
import app.xreader.data.extension.BookKind152
import app.xreader.data.extension.CleanerRuleStore
import app.xreader.data.extension.ContentRating
import app.xreader.data.extension.DeclarativeConfig
import app.xreader.data.extension.DeclarativeFingerprints
import app.xreader.data.extension.ExtensionChapterFetcher
import app.xreader.data.extension.ExtensionPermissions
import app.xreader.data.extension.ExtensionService
import app.xreader.data.extension.ExtensionStore
import app.xreader.data.extension.FakeDexLoader
import app.xreader.data.extension.FakeJsRuntime
import app.xreader.data.extension.FixtureSite
import app.xreader.data.extension.HostContextFactory
import app.xreader.data.extension.MediaKind
import app.xreader.data.extension.SourceBookIds
import app.xreader.data.extension.missingMangaPageRecordError
import app.xreader.data.extension.testSessionStore
import app.xreader.domain.content.ChapterPage
import app.xreader.domain.content.ChapterPages
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v1.5.6 (§2 / §1.1): the routing-contract harness. The law —
 * novels → novel reader, manga → Komikku reader, ALWAYS, decided
 * from source/book BEFORE any reader opens — is exercised through
 * the resolver itself, in the call shapes the entry points use
 * (resolve by book row, and resolve by book id + chapter index),
 * plus the §1.1 resolver-fallthrough hardening: a manga book whose
 * player page record is missing fails retryably instead of being
 * converted into fake text content.
 */
class ReaderRouteResolverTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val bookDao = FakeBookDao()
    private val chapterDao = FakeChapterDao()

    // ── stacks ──────────────────────────────────────────────────

    /** A declarative-package service serving [configs]. */
    private fun declarativeStack(
        configs: List<DeclarativeConfig>,
        packageId: String = "test.novels"
    ): Pair<ExtensionService, Long> {
        val extDao = FakeInstalledExtensionDao()
        val store = ExtensionStore(tmp.newFolder())
        val factory = HostContextFactory(
            store, testSessionStore(), FakeTransport { FakeTransport.ok("") }
        )
        val service = ExtensionService(extDao, store, factory, FakeDexLoader()) {
            FakeJsRuntime()
        }
        store.writeText(
            store.definitionFile(packageId),
            DeclarativeFingerprints.encode(configs)
        )
        val extId = runBlocking {
            extDao.insert(
                InstalledExtension(
                    repoId = 1,
                    displayName = "Novels Example",
                    packageId = packageId,
                    versionName = "1.0",
                    versionCode = 1,
                    kind = app.xreader.data.db.ExtensionKind.DECLARATIVE,
                    artifactPath = null,
                    sha256 = DeclarativeFingerprints.of(configs),
                    apiLevel = 1,
                    languages = "en",
                    sourceIds = configs.joinToString(",") { it.id },
                    installedAt = 0,
                    updatedAt = 0,
                    permissionsJson = ExtensionPermissions(
                        domains = listOf(FixtureSite.HOST)
                    ).let { permissions ->
                        kotlinx.serialization.json.Json.encodeToString(
                            ExtensionPermissions.serializer(), permissions
                        )
                    }
                )
            )
        }
        return service to extId
    }

    private val pkg = "eu.kanade.tachiyomi.extension.en.fakepanels"
    private val namespaced = "keiyoushi:$pkg:777"

    /** A Keiyoushi-player service whose live registry holds one
     * fake Mihon manga source (id 777). */
    private fun keiyoushiStack(
        source: FakeHttpSource = FakeHttpSource()
    ): Pair<ExtensionService, Long> {
        val extDao = FakeInstalledExtensionDao()
        val store = ExtensionStore(tmp.newFolder())
        val factory = HostContextFactory(
            store, testSessionStore(), FakeTransport { FakeTransport.ok("") }
        )
        val service = ExtensionService(
            extDao, store, factory, FakeDexLoader(),
            keiyoushiLiveSources = { mapOf(namespaced to source) },
            keiyoushiRatingProvider = { ContentRating.MIXED },
            keiyoushiPlayerEnabled = { true }
        ) { FakeJsRuntime() }
        val extId = runBlocking {
            extDao.insert(
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
                    trustState = TrustState.TRUSTED,
                    permissionsJson = "{}",
                    installedAt = 0,
                    updatedAt = 0
                )
            )
        }
        return service to extId
    }

    private suspend fun extensionBook(
        extId: Long,
        sourceId: String,
        kind: String?
    ): Book {
        val id = bookDao.insert(
            Book(
                title = "Routed Book",
                sourceType = SourceType.EXTENSION,
                extensionId = extId,
                remoteId = SourceBookIds.encode(sourceId, "/manga/alpha"),
                kind = kind,
                addedAt = 0,
                updatedAt = 0
            )
        )
        return bookDao.getById(id)!!
    }

    // ── the routing law ─────────────────────────────────────────

    @Test
    fun keiyoushiBookWithNullKindRoutesToKomikkuWithChapterRowId() = runBlocking {
        val (service, extId) = keiyoushiStack()
        // Book.kind = NULL: the player source decides (priority 1).
        val book = extensionBook(extId, sourceId = "777", kind = null)
        val chapterRowId = chapterDao.insert(
            Chapter(
                bookId = book.id, index = 2, title = "Chapter 3",
                remoteId = "/ch/3", updatedAt = 0
            )
        )
        val resolver = ReaderRouteResolver(bookDao, chapterDao, service)
        // The entry-point call shape: book id + chapter index.
        val route = resolver.resolve(book.id, chapterIndex = 2)
        assertEquals(
            ReaderRoute.Komikku(bookId = book.id, chapterRowId = chapterRowId),
            route
        )
    }

    @Test
    fun declarativeNovelBookRoutesToNovelReader() = runBlocking {
        val (service, extId) = declarativeStack(listOf(FixtureSite.config()))
        val book = extensionBook(extId, sourceId = "novels-example", kind = null)
        val resolver = ReaderRouteResolver(bookDao, chapterDao, service)
        assertEquals(
            ReaderRoute.NovelReader(bookId = book.id, chapterIndex = 0),
            resolver.resolve(book, chapterIndex = 0)
        )
    }

    @Test
    fun declarativeTextSourceOutranksStoredMangaKind() = runBlocking {
        // The §2 amendment case: mediaKindOf(handle) = TEXT ranks
        // ABOVE the book's stored kind — a declarative TEXT
        // source's book goes to the novel reader even when its
        // stored kind says MANGA.
        val (service, extId) = declarativeStack(listOf(FixtureSite.config()))
        val book = extensionBook(
            extId, sourceId = "novels-example", kind = BookKind152.MANGA
        )
        val resolver = ReaderRouteResolver(bookDao, chapterDao, service)
        assertEquals(
            ReaderRoute.NovelReader(bookId = book.id, chapterIndex = 1),
            resolver.resolve(book, chapterIndex = 1)
        )
    }

    @Test
    fun declarativeMangaSourceRoutesToKomikku() = runBlocking {
        val mangaConfig = FixtureSite.config().copy(mediaKind = MediaKind.MANGA)
        val (service, extId) = declarativeStack(listOf(mangaConfig))
        val book = extensionBook(extId, sourceId = "novels-example", kind = null)
        val chapterRowId = chapterDao.insert(
            Chapter(
                bookId = book.id, index = 0, title = "Chapter 1",
                remoteId = FixtureSite.CHAPTER_1_URL, updatedAt = 0
            )
        )
        val resolver = ReaderRouteResolver(bookDao, chapterDao, service)
        assertEquals(
            ReaderRoute.Komikku(bookId = book.id, chapterRowId = chapterRowId),
            resolver.resolve(book, chapterIndex = 0)
        )
    }

    @Test
    fun bothKindsSourceStoredKindDecides() = runBlocking {
        // A both-kinds source (a listing spec carries a tier-1
        // kind-field mapping): its declared kind does NOT decide
        // the book — the stored verdict does (priority 2).
        val bothKinds = FixtureSite.config().copy(
            popular = FixtureSite.config().popular!!.copy(
                kindField = "type",
                kindValueMap = BookKind152.FENRIR_TYPE_MAP
            )
        )
        val (service, extId) = declarativeStack(listOf(bothKinds))
        val resolver = ReaderRouteResolver(bookDao, chapterDao, service)

        val mangaBook = extensionBook(
            extId, sourceId = "novels-example", kind = BookKind152.MANGA
        )
        val mangaChapterRowId = chapterDao.insert(
            Chapter(
                bookId = mangaBook.id, index = 0, title = "Chapter 1",
                remoteId = FixtureSite.CHAPTER_1_URL, updatedAt = 0
            )
        )
        assertEquals(
            ReaderRoute.Komikku(
                bookId = mangaBook.id, chapterRowId = mangaChapterRowId
            ),
            resolver.resolve(mangaBook, chapterIndex = 0)
        )

        val novelBook = extensionBook(
            extId, sourceId = "novels-example", kind = BookKind152.TEXT
        )
        assertEquals(
            ReaderRoute.NovelReader(bookId = novelBook.id, chapterIndex = 0),
            resolver.resolve(novelBook, chapterIndex = 0)
        )

        // UNKNOWN + both-kinds: priority 4's novel-reader default.
        val unknownBook = extensionBook(
            extId, sourceId = "novels-example", kind = null
        )
        assertEquals(
            ReaderRoute.NovelReader(bookId = unknownBook.id, chapterIndex = 0),
            resolver.resolve(unknownBook, chapterIndex = 0)
        )
    }

    @Test
    fun localFileBookRoutesToLocalDoc() = runBlocking {
        val (service, _) = declarativeStack(listOf(FixtureSite.config()))
        val id = bookDao.insert(
            Book(
                title = "Local Novel",
                sourceType = SourceType.LOCAL_FILE,
                localFormat = app.xreader.data.db.BookFormat.EPUB,
                filePath = "/storage/emulated/0/Download/local-novel.epub",
                addedAt = 0,
                updatedAt = 0
            )
        )
        val resolver = ReaderRouteResolver(bookDao, chapterDao, service)
        assertEquals(
            ReaderRoute.LocalDoc(bookId = id),
            resolver.resolve(id, chapterIndex = 0)
        )
    }

    @Test
    fun unknownKindWithUnresolvableSourceFallsBackToNovelReader() = runBlocking {
        val (service, _) = declarativeStack(listOf(FixtureSite.config()))
        // The extension row is gone: no handle, no stored kind —
        // priority 4 keeps the book reachable in the novel reader.
        val book = extensionBook(
            extId = 9999L, sourceId = "novels-example", kind = null
        )
        val resolver = ReaderRouteResolver(bookDao, chapterDao, service)
        assertEquals(
            ReaderRoute.NovelReader(bookId = book.id, chapterIndex = 0),
            resolver.resolve(book, chapterIndex = 0)
        )
    }

    @Test
    fun missingBookFallsBackToNovelReader() = runBlocking {
        val (service, _) = declarativeStack(listOf(FixtureSite.config()))
        val resolver = ReaderRouteResolver(bookDao, chapterDao, service)
        assertEquals(
            ReaderRoute.NovelReader(bookId = 424242L, chapterIndex = 3),
            resolver.resolve(424242L, chapterIndex = 3)
        )
    }

    // ── §1.1 hardening: the resolver-fallthrough case ───────────

    @Test
    fun mangaBookWithMissingPageRecordFailsRetryablyNeverConverts() = runBlocking {
        val (service, extId) = keiyoushiStack()
        val handle = (service.sourcesOf(extId) as AppResult.Ok).value.single()
        // The adapter instance is fresh: its in-memory page
        // record is absent (pagesOf → null) — the normal,
        // reachable state §1.1 names. For a MANGA book the guard
        // must produce a retryable failure, never a conversion.
        val error = missingMangaPageRecordError(handle, service, pages = null)
        assertTrue(error is AppError.ProviderDown)
        assertTrue((error as AppError.ProviderDown).retryable)
        // An EMPTY record is the same failure state.
        val emptyError = missingMangaPageRecordError(
            handle, service, pages = ChapterPages(emptyList())
        )
        assertTrue(emptyError is AppError.ProviderDown)
        assertTrue((emptyError as AppError.ProviderDown).retryable)
        // A present record lets the fetch proceed.
        assertNull(
            missingMangaPageRecordError(
                handle, service,
                pages = ChapterPages(
                    listOf(ChapterPage(url = "https://cdn.test/1.jpg"))
                )
            )
        )
    }

    @Test
    fun textSourceWithMissingPageRecordIsNotHardened() = runBlocking {
        val (service, extId) = declarativeStack(listOf(FixtureSite.config()))
        val handle = (service.sourcesOf(extId) as AppResult.Ok).value.single()
        // A prose source has no page record by design; the guard
        // must not touch its HTML-to-content road.
        assertNull(missingMangaPageRecordError(handle, service, pages = null))
    }

    @Test
    fun mangaFetcherStillSucceedsWhenThePageRecordLands() = runBlocking {
        // The guard's other side: a normal player chapter fetch
        // (chapterContent stashes the record, pagesOf finds it)
        // still returns the page record as Success content.
        val (service, extId) = keiyoushiStack()
        val cleanerStore = CleanerRuleStore(ExtensionStore(tmp.newFolder()))
        val fetcher = ExtensionChapterFetcher(
            service = service,
            cleanerStore = cleanerStore,
            extensionId = extId,
            sourceId = namespaced,
            bookId = 7,
            storage = null,
            fetchPageFiles = false
        )
        val outcome = fetcher.fetch(
            ChapterCandidate(
                mapId = 0, label = "Chapter 1", locator = "/ch/1", bookId = 7
            )
        )
        assertTrue(outcome is FetchOutcome.Success)
        val pages = (outcome as FetchOutcome.Success).content.pages
        // v1.5.11 (transplant, Verdict 1): page 2 rides the
        // record under its lazy registration key — its
        // signed address resolves only at byte-fetch time,
        // never at chapter open.
        assertEquals(
            listOf(
                "https://cdn.test/1.jpg",
                "https://panels.test/read/p2#xreaderPage=1"
            ),
            pages!!.urls
        )
    }

    // ── the fake Mihon source (mirrors MihonSourceAdapterTest's) ──

    private class FakeHttpSource(
        override val id: Long = 777L
    ) : HttpSource() {

        override val name: String = "Fake Panels"
        override val lang: String = "en"
        override val baseUrl: String = "https://panels.test"
        override val supportsLatest: Boolean get() = true

        var chaptersNewestFirst: List<SChapter> = listOf(
            SChapter(
                name = "Chapter 1", url = "/ch/1",
                date_upload = 1700000001000, chapter_number = 1f
            )
        )
        var pages: List<Page> = listOf(
            Page(0, url = "/read/p1", imageUrl = "https://cdn.test/1.jpg"),
            Page(1, url = "/read/p2", imageUrl = null)
        )

        override suspend fun getPopularManga(page: Int): MangasPage =
            MangasPage(
                listOf(
                    SManga(
                        url = "/manga/alpha", title = "Alpha",
                        thumbnail_url = "/covers/alpha.jpg"
                    )
                ),
                hasNextPage = false
            )

        override suspend fun getLatestUpdates(page: Int): MangasPage =
            getPopularManga(page)

        override suspend fun getSearchManga(
            page: Int, query: String, filters: FilterList
        ): MangasPage = getPopularManga(page)

        override suspend fun getMangaUpdate(
            manga: SManga,
            chapters: List<SChapter>,
            fetchDetails: Boolean,
            fetchChapters: Boolean
        ): SMangaUpdate = SMangaUpdate(
            manga,
            if (fetchChapters) chaptersNewestFirst else emptyList()
        )

        override suspend fun getPageList(chapter: SChapter): List<Page> = pages

        override suspend fun getImageUrl(page: Page): String =
            "https://cdn.test/signed/" + page.url.substringAfterLast('/')

        override suspend fun getImage(page: Page): Response =
            Response.Builder()
                .request(
                    Request.Builder()
                        .url(page.imageUrl ?: "https://panels.test/")
                        .build()
                )
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("fake-png-bytes".toByteArray().toResponseBody("image/png".toMediaType()))
                .build()

        override fun getFilterList(): FilterList =
            FilterList(object : Filter.TriState("Genre") {})
    }
}
