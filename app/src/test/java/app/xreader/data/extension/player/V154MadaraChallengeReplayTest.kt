package app.xreader.data.extension.player

import android.app.Application
import app.xreader.core.error.AppResult
import app.xreader.data.FakeBookDao
import app.xreader.data.FakeChapterDao
import app.xreader.data.FakeChapterSourceMapDao
import app.xreader.data.FakeInstalledExtensionDao
import app.xreader.data.FakeTransport
import app.xreader.data.db.Book
import app.xreader.data.db.InstalledExtension
import app.xreader.data.db.SourceType
import app.xreader.data.db.TrustState
import app.xreader.data.extension.ContentRating
import app.xreader.data.extension.ExtensionPermissions
import app.xreader.data.extension.ExtensionPermissions.Companion.encode
import app.xreader.data.extension.ExtensionService
import app.xreader.data.extension.ExtensionStore
import app.xreader.data.extension.FakeDexLoader
import app.xreader.data.extension.FakeJsRuntime
import app.xreader.data.extension.HostContextFactory
import app.xreader.data.extension.SessionStore
import app.xreader.data.extension.SourceBookIds
import app.xreader.data.extension.SourceBookSync
import app.xreader.data.extension.testSessionStore
import app.xreader.data.extension.testVault
import app.xreader.data.files.BookStorage
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.online.HttpSource
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import okhttp3.FormBody
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * v1.5.4 WS-1 item 8c — the challenged Madara book-page load,
 * replayed end to end (sync → adapter → the source's own
 * OkHttp client → fake transport) from fixtures:
 *
 *  - the details page + chapter-endpoint fragment in the
 *    donor-documented Madara shape (fixtures name their
 *    provenance; the manga Madara base is not in this
 *    checkout — the novel variant documents the wire);
 *  - the chapter POST (wp-admin/admin-ajax.php,
 *    action=manga_get_chapters) first answered by a
 *    Cloudflare page.
 *
 * Leg A (auto-solve class): the challenge page carries the
 * donor detection markers → the interceptor auto-solves per
 * item 6 (solver invoked once, the ORIGINAL POST replayed
 * once, byte-identical), the chapter list lands, and
 * details+cover landed independently (item 5).
 *
 * Leg B (wall class): the POST is answered by the REAL
 * captured Cloudflare wall (build-logs/evidence/
 * brightnovel-series-CF-WALL-egress-capture.html — a genuine
 * capture, which on inspection carries NONE of the donor
 * markers: it is the block-wall class). The load fails FAST
 * into the honest state — solver never invoked, no replay,
 * no wait. This leg pins item 6b against the real artefact.
 *
 * The LIVE Madara site remains a device-proof leg; this proof
 * pins the host's behaviour against the documented wire.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class V154MadaraChallengeReplayTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val pkg = "eu.kanade.tachiyomi.extension.en.madarafake"
    private val namespaced = "keiyoushi:$pkg:888"
    private val base = "https://madara.test"

    private lateinit var bookDao: FakeBookDao
    private lateinit var chapterDao: FakeChapterDao
    private lateinit var sync: SourceBookSync
    private var rowId: Long = 0

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/v154/$name")) {
            "missing fixture $name"
        }.readBytes().toString(Charsets.UTF_8)

    /** The terminal "transport": serves the fixtures, counts
     * and records the chapter POSTs. [firstPostBody] answers
     * the first chapter POST (the challenge/wall); later POSTs
     * get the chapter fragment. */
    private class FakeMadaraTransport(
        private val mangaPage: String,
        private val chaptersFragment: String,
        private val firstPostBody: String
    ) : Interceptor {
        val postBodies = CopyOnWriteArrayList<String>()
        var postCount = 0

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val url = request.url.toString()
            fun respond(code: Int, body: String, server: String?): Response =
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message("m")
                    .apply { if (server != null) header("Server", server) }
                    .body(body.toResponseBody("text/html".toMediaType()))
                    .build()
            return when {
                url.endsWith("/wp-admin/admin-ajax.php") -> {
                    postCount++
                    val buffer = Buffer()
                    request.body?.writeTo(buffer)
                    postBodies += buffer.readUtf8()
                    if (postCount == 1) {
                        respond(403, firstPostBody, "cloudflare")
                    } else {
                        respond(200, chaptersFragment, "cloudflare")
                    }
                }
                url.contains("/manga/") ->
                    respond(200, mangaPage, "cloudflare")
                else -> respond(404, "<html>nope</html>", null)
            }
        }
    }

    /** A Madara-shaped fake source: details = GET the manga
     * page and parse it; chapters = the donor-documented
     * admin-ajax POST, parsed from the returned fragment.
     * All traffic rides [client] — the client the test fitted
     * with the REAL CloudflareInterceptor. */
    private class MadaraFakeSource(
        override val id: Long = 888L,
        override val client: OkHttpClient
    ) : HttpSource() {
        override val name: String = "Madara Fake"
        override val lang: String = "en"
        override val baseUrl: String = "https://madara.test"
        override val supportsLatest: Boolean = true

        override suspend fun getPopularManga(page: Int): MangasPage =
            MangasPage(emptyList(), hasNextPage = false)

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
        ): SMangaUpdate {
            var out = manga
            if (fetchDetails) {
                val request = Request.Builder()
                    .url("$baseUrl${manga.url}")
                    .build()
                val html = client.newCall(request).execute()
                    .use { it.body!!.string() }
                val doc = Jsoup.parse(html, baseUrl)
                out = SManga(
                    url = manga.url,
                    title = doc.select("h1").first()?.text() ?: manga.title,
                    author = doc.select(".author-content a").first()?.text(),
                    description = doc.select(".summary__content").first()?.text(),
                    thumbnail_url = doc.select(".summary_image img")
                        .first()?.attr("src"),
                    initialized = true
                )
            }
            var list = emptyList<SChapter>()
            if (fetchChapters) {
                val body = FormBody.Builder()
                    .add("action", "manga_get_chapters")
                    .add("manga", "42")
                    .build()
                val request = Request.Builder()
                    .url("$baseUrl/wp-admin/admin-ajax.php")
                    .post(body)
                    .build()
                val html = client.newCall(request).execute()
                    .use { it.body!!.string() }
                val doc = Jsoup.parse(html, baseUrl)
                list = doc.select("li.wp-manga-chapter a").map { a ->
                    val text = a.text()
                    SChapter(
                        name = text,
                        url = a.attr("href").removePrefix(baseUrl),
                        chapter_number = text.removePrefix("Chapter ")
                            .trim().toFloatOrNull() ?: -1f
                    )
                }
            }
            return SMangaUpdate(out, list)
        }

        override suspend fun getPageList(chapter: SChapter): List<Page> =
            emptyList()
    }

    private class Stack(
        val transport: FakeMadaraTransport,
        val solverRequests: CopyOnWriteArrayList<Request>
    )

    private fun buildStack(firstPostBody: String): Stack {
        val transport = FakeMadaraTransport(
            mangaPage = fixture("madara-manga-page.html"),
            chaptersFragment = fixture("madara-chapters-fragment.html"),
            firstPostBody = firstPostBody
        )
        val solverRequests = CopyOnWriteArrayList<Request>()
        val vault = testVault()
        val store = SessionStore(vault)
        val jar = SessionStoreCookieJar(store, vault)
        val bridge = PlayerChallengeBridge(
            sessionSavedEvents = MutableSharedFlow(extraBufferCapacity = 4),
            hasClearance = { false }
        )
        val client = OkHttpClient.Builder()
            .addInterceptor(
                CloudflareInterceptor(jar, bridge) { request ->
                    solverRequests += request
                    true
                }
            )
            .addInterceptor(transport)
            .build()
        val source = MadaraFakeSource(client = client)

        val extDao = FakeInstalledExtensionDao()
        val extStore = ExtensionStore(tmp.newFolder())
        val factory = HostContextFactory(
            extStore, testSessionStore(), FakeTransport { FakeTransport.ok("") }
        )
        val service = ExtensionService(
            extDao, extStore, factory, FakeDexLoader(),
            keiyoushiLiveSources = { mapOf(namespaced to source) },
            keiyoushiRatingProvider = { ContentRating.MIXED },
            keiyoushiPlayerEnabled = { true },
            sessionStore = testSessionStore()
        ) { FakeJsRuntime() }
        bookDao = FakeBookDao()
        chapterDao = FakeChapterDao()
        sync = SourceBookSync(
            bookDao = bookDao,
            chapterDao = chapterDao,
            sourceMapDao = FakeChapterSourceMapDao(),
            extensionDao = extDao,
            service = service,
            storage = BookStorage(tmp.newFolder()),
            transport = FakeTransport { request ->
                if (request.url.contains("/covers/")) {
                    FakeTransport.ok("fake-cover-bytes")
                } else {
                    FakeTransport.ok("")
                }
            },
            nowMs = { 1_000L }
        )
        // PlayerWebViewSync installed as production installs
        // it, so the interceptor's post-solve harvest runs its
        // real path.
        PlayerWebViewSync.install(
            RuntimeEnvironment.getApplication(), store, vault
        )
        runBlocking {
            rowId = extDao.insert(
                InstalledExtension(
                    repoId = 1,
                    displayName = "Madara Fake",
                    packageId = pkg,
                    versionName = "1.6.5",
                    versionCode = 106005,
                    kind = app.xreader.data.db.ExtensionKind.KEIYOUSHI,
                    artifactPath = null,
                    sha256 = "abc",
                    apiLevel = 16,
                    languages = "en",
                    sourceIds = "888",
                    enabled = true,
                    trustState = TrustState.TRUSTED,
                    permissionsJson = ExtensionPermissions(
                        domains = listOf("madara.test"),
                        canUseNetwork = true,
                        dexOptIn = true
                    ).encode(),
                    installedAt = 0,
                    updatedAt = 0
                )
            )
        }
        return Stack(transport, solverRequests)
    }

    private suspend fun bookRow(): Book {
        val id = bookDao.insert(
            Book(
                title = "Alpha Chronicles",
                author = "",
                description = "",
                sourceType = SourceType.EXTENSION,
                extensionId = rowId,
                remoteId = SourceBookIds.encode(namespaced, "/manga/alpha/"),
                addedAt = 0,
                updatedAt = 0,
                inLibrary = true
            )
        )
        return bookDao.getById(id)!!
    }

    @Test
    fun legA_challengedPostIsAutoSolvedAndReplayedOnce() {
        val stack = buildStack(fixture("cloudflare-challenge-page.html"))
        val book = runBlocking { bookRow() }
        val result = runBlocking { sync.refresh(book) }

        assertTrue("refresh must succeed, was $result", result is AppResult.Ok)
        // The chapter list landed through the replay.
        val chapters = runBlocking { chapterDao.forBook(book.id) }
        assertEquals(3, chapters.size)
        assertEquals(
            listOf("Chapter 1", "Chapter 2", "Chapter 3"),
            chapters.sortedBy { it.index }.map { it.title }
        )
        // Item 6 discipline: solver invoked exactly once, on
        // the ORIGINAL POST; the POST was replayed exactly
        // once, byte-identical (method-agnostic replay).
        assertEquals(1, stack.solverRequests.size)
        val solvedRequest = stack.solverRequests.single()
        assertEquals("POST", solvedRequest.method)
        assertTrue(
            solvedRequest.url.toString().endsWith("/wp-admin/admin-ajax.php")
        )
        assertEquals(2, stack.transport.postCount)
        assertEquals(2, stack.transport.postBodies.size)
        assertEquals(
            stack.transport.postBodies[0], stack.transport.postBodies[1]
        )
        assertTrue(
            stack.transport.postBodies[0].contains("action=manga_get_chapters")
        )
        // Item 5: details + cover landed independently of the
        // challenged chapters leg.
        val stored = runBlocking { bookDao.getById(book.id) }!!
        assertEquals("Madara Author", stored.author)
        assertNotNull(stored.coverPath)
    }

    @Test
    fun legB_realWallCaptureFailsFastWithNoSolveAndNoReplay() {
        val stack = buildStack(fixture("cloudflare-wall-capture.html"))
        val book = runBlocking { bookRow() }
        val pool = Executors.newSingleThreadExecutor()
        val started = System.nanoTime()
        val result = try {
            pool.submit<AppResult<Book>> {
                runBlocking { sync.refresh(book) }
            }.get(20, TimeUnit.SECONDS)
        } catch (e: Exception) {
            fail("wall-class load must fail FAST, not wait (${e.javaClass.simpleName})")
            throw e
        } finally {
            pool.shutdownNow()
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue(result is AppResult.Err)
        assertTrue("fail-fast means seconds, took ${elapsedMs}ms", elapsedMs < 15_000)
        // No unattended solve is attempted on the wall class,
        // and nothing is replayed.
        assertEquals(0, stack.solverRequests.size)
        assertEquals(1, stack.transport.postCount)
        // v1.5.10 (I6b): the pre-v1.5.5 split call landed the
        // details half independently, and this leg asserted
        // that. Since v1.5.5 (§1) the book page is ONE fused
        // getMangaUpdate (MihonSourceAdapter.detailsAndChapters:
        // "If the fused call throws, both halves surface that
        // same failure — the donor's atomic semantics"), so a
        // wall that kills the call lands NEITHER half: the
        // stored row keeps its pre-refresh shape, honestly.
        // (Apply-independence survives only once a result has
        // landed — leg A above is its proof.)
        val stored = runBlocking { bookDao.getById(book.id) }!!
        assertEquals("", stored.author)
        assertTrue(
            runBlocking { chapterDao.forBook(book.id) }.isEmpty()
        )
    }
}
