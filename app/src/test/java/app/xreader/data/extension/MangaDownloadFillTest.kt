package app.xreader.data.extension

import app.xreader.core.error.AppResult
import app.xreader.data.FakeBookDao
import app.xreader.data.FakeChapterDao
import app.xreader.data.FakeChapterSourceMapDao
import app.xreader.data.FakeInstalledExtensionDao
import app.xreader.data.FakeTransport
import app.xreader.data.db.Book
import app.xreader.data.db.Chapter
import app.xreader.data.db.InstalledExtension
import app.xreader.data.db.SourceType
import app.xreader.data.download.FetchOutcome
import app.xreader.data.download.RoomChapterSourceResolver
import app.xreader.data.download.UrlChapterFetcher
import app.xreader.data.files.BookStorage
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Manga programme (M3): the download pass's page fill — the
 * extension resolver built for the download engine lands
 * every page image next to the book's files and records the
 * names in the chapter's page record; a page that fails keeps
 * its slot empty (never marked downloaded); a second pass
 * fetches ONLY the pages still missing. The reader's resolver
 * (no fill flag) never downloads — reading stays reading.
 */
class MangaDownloadFillTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val bookDao = FakeBookDao()
    private val chapterDao = FakeChapterDao()
    private val mapDao = FakeChapterSourceMapDao()
    private val extDao = FakeInstalledExtensionDao()

    private lateinit var storage: BookStorage
    private var bookId: Long = 0
    private var chapterId: Long = 0

    /** The fixture site plus image bytes: every /pages/ URL
     * serves its "image"; [failingPage] (a URL substring)
     * answers 404 instead. */
    private fun transport(failingPage: String? = null): FakeTransport {
        val pages = MangaFixture.pages()
        return FakeTransport { request ->
            when {
                failingPage != null && request.url.contains(failingPage) ->
                    AppResult.Ok(FakeTransport.response(404, "gone"))
                request.url.contains("/pages/") ->
                    FakeTransport.ok("IMG-BYTES ${request.url}")
                else -> {
                    val body = pages[request.url]
                    if (body == null) {
                        AppResult.Ok(FakeTransport.response(404, "not found"))
                    } else {
                        FakeTransport.ok(body)
                    }
                }
            }
        }
    }

    private suspend fun setUp(transport: FakeTransport) {
        val filesDir = tmp.newFolder()
        val store = ExtensionStore(filesDir)
        val factory = HostContextFactory(store, testSessionStore(), transport)
        val service = ExtensionService(extDao, store, factory, FakeDexLoader()) {
            FakeJsRuntime()
        }
        store.writeText(
            store.definitionFile("test.manga"),
            DeclarativeFingerprints.encode(listOf(MangaFixture.config()))
        )
        val extId = extDao.insert(
            InstalledExtension(
                repoId = 1,
                displayName = "Manga Example",
                packageId = "test.manga",
                versionName = "1.0",
                versionCode = 1,
                kind = app.xreader.data.db.ExtensionKind.DECLARATIVE,
                artifactPath = null,
                sha256 = DeclarativeFingerprints.of(
                    listOf(MangaFixture.config())
                ),
                apiLevel = 1,
                languages = "en",
                sourceIds = "manga-example",
                installedAt = 0,
                updatedAt = 0,
                permissionsJson = kotlinx.serialization.json.Json
                    .encodeToString(
                        ExtensionPermissions.serializer(),
                        ExtensionPermissions(
                            domains = listOf(
                                MangaFixture.HOST, "cdn.manga.example"
                            )
                        )
                    )
            )
        )
        bookId = bookDao.insert(
            Book(
                title = "Panel Saga",
                sourceType = SourceType.EXTENSION,
                extensionId = extId,
                remoteId = SourceBookIds.encode(
                    "manga-example", MangaFixture.BOOK_URL
                ),
                addedAt = 0, updatedAt = 0
            )
        )
        chapterId = chapterDao.insert(
            Chapter(
                bookId = bookId, index = 0, title = "Chapter 1",
                remoteId = MangaFixture.CHAPTER_1_URL, updatedAt = 0
            )
        )
        storage = BookStorage(tmp.newFolder())
        downloadResolver = ExtensionChapterResolver(
            bookDao, mapDao, service, CleanerRuleStore(store),
            fallback = RoomChapterSourceResolver(mapDao) {
                UrlChapterFetcher(transport)
            },
            storage = storage,
            fetchPageFiles = true
        )
        readResolver = ExtensionChapterResolver(
            bookDao, mapDao, service, CleanerRuleStore(store),
            fallback = RoomChapterSourceResolver(mapDao) {
                UrlChapterFetcher(transport)
            },
            storage = storage
        )
    }

    private lateinit var downloadResolver: ExtensionChapterResolver
    private lateinit var readResolver: ExtensionChapterResolver

    private suspend fun fetchFirst(
        resolver: ExtensionChapterResolver
    ): FetchOutcome {
        val chapter = chapterDao.getById(chapterId)!!
        val candidates = resolver.candidatesFor(chapter)
        assertEquals(1, candidates.size)
        return candidates.first().fetcher.fetch(candidates.first().candidate)
    }

    @Test
    fun `a download fetch lands every page file and records the names`() =
        runBlocking {
            val transport = transport()
            setUp(transport)
            val outcome = fetchFirst(downloadResolver)
            assertTrue(outcome is FetchOutcome.Success)
            val pages = (outcome as FetchOutcome.Success).content.pages
            assertNotNull(pages)
            assertEquals(4, pages!!.size)
            assertEquals(4, pages.downloadedCount)
            // Every recorded name points at a real file with
            // the served bytes, in the book's content dir,
            // under the M3 chapter-scoped naming law.
            val dir = storage.contentDir(bookId)
            for (page in pages.pages) {
                val name = page.localFile
                assertNotNull("every page must name its file", name)
                assertTrue(name!!.startsWith("img-p"))
                val file = File(dir, name)
                assertTrue("the page file must exist", file.isFile)
                assertEquals("IMG-BYTES ${page.url}", file.readText())
            }
            // The four page images — and only they — were
            // fetched as images (the rest are the chapter's
            // two HTML pages).
            val imageRequests = transport.requests
                .map { it.url }.filter { it.contains("/pages/") }
            assertEquals(4, imageRequests.size)
        }

    @Test
    fun `one failing page stays unfilled and the retry fetches only it`() =
        runBlocking {
            val failing = "c1-3.jpg"
            val transport = transport(failingPage = failing)
            setUp(transport)
            val first = fetchFirst(downloadResolver)
            assertTrue(first is FetchOutcome.Success)
            val pages = (first as FetchOutcome.Success).content.pages!!
            assertEquals(4, pages.size)
            assertEquals(3, pages.downloadedCount)
            val missing = pages.pages.single { it.localFile == null }
            assertTrue(missing.url.contains(failing))

            // Retry (a fresh fetch through the same resolver):
            // the chapter HTML is re-read, but of the images
            // only the missing page is requested again.
            transport.requests.clear()
            val second = fetchFirst(downloadResolver)
            assertTrue(second is FetchOutcome.Success)
            val imageRequests = transport.requests
                .map { it.url }.filter { it.contains("/pages/") }
            assertEquals(
                listOf(missing.url), imageRequests
            )
            // The record is still partial (the page still 404s)
            // and the three landed files were not rewritten —
            // their names are unchanged.
            val pages2 = (second as FetchOutcome.Success).content.pages!!
            assertEquals(3, pages2.downloadedCount)
            assertEquals(
                pages.pages.map { it.localFile },
                pages2.pages.map { it.localFile }
            )
        }

    @Test
    fun `the read road fills nothing`() = runBlocking {
        val transport = transport()
        setUp(transport)
        val outcome = fetchFirst(readResolver)
        assertTrue(outcome is FetchOutcome.Success)
        val pages = (outcome as FetchOutcome.Success).content.pages!!
        assertEquals(4, pages.size)
        assertTrue(pages.pages.all { it.localFile == null })
        assertNull(
            transport.requests.map { it.url }
                .firstOrNull { it.contains("/pages/") }
        )
    }
}
