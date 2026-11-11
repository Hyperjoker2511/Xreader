package app.xreader.data.extension.player

import android.app.Application
import androidx.room.Room
import androidx.room.withTransaction
import app.xreader.core.error.AppResult
import app.xreader.data.FakeTransport
import app.xreader.data.db.Book
import app.xreader.data.db.ExtensionKind
import app.xreader.data.db.ExtensionRepo
import app.xreader.data.db.InstalledExtension
import app.xreader.data.db.SourceType
import app.xreader.data.db.TrustState
import app.xreader.data.db.XreaderDatabase
import app.xreader.data.extension.ChapterNumbers
import app.xreader.data.extension.ContentRating
import app.xreader.data.extension.ExtensionService
import app.xreader.data.extension.ExtensionStore
import app.xreader.data.extension.FakeDexLoader
import app.xreader.data.extension.FakeJsRuntime
import app.xreader.data.extension.HostContextFactory
import app.xreader.data.extension.SourceBookIds
import app.xreader.data.extension.SourceBookSync
import app.xreader.data.extension.testSessionStore
import app.xreader.data.files.BookStorage
import app.xreader.domain.content.ChapterPage
import app.xreader.domain.content.ChapterPages
import app.xreader.extensionapi.SourceBook
import app.xreader.extensionapi.SourceChapter
import app.xreader.komikku.loader.ChapterLoader
import app.xreader.komikku.loader.ChapterPagePipeline
import app.xreader.komikku.loader.HttpPageLoader
import app.xreader.komikku.loader.HttpSourceBinding
import app.xreader.komikku.loader.PipelinePageLoader
import app.xreader.komikku.model.ReaderChapter
import app.xreader.komikku.setting.InMemoryKomikkuStore
import app.xreader.komikku.setting.ReaderPreferences
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.online.HttpSource
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
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
 * v1.5.11 gate — END-TO-END PATH tests (not unit pieces) for
 * the manga transplant. Two convicted v1.5.10 failures are
 * pinned here as whole journeys:
 *
 *  (a) THE MEMO ROUND TRIP (the Asura conviction): an
 *      extension mints private per-chapter state
 *      (`SChapter.memo`) at chapter-list time and REQUIRES
 *      it back at page-list time, throwing "Refresh Chapter
 *      List" when it is missing — and no refresh could ever
 *      help while the host dropped the memo at every seam.
 *      The path asserted: fake HttpSource chapter list →
 *      [MihonSourceAdapter] mapping → [SourceChapter.memo]
 *      JSON text → the REAL chapter-store road into a real
 *      Room row (schema v12 `chapters.memo`) → the reader
 *      road's row rebuild → `adapter.getPageList` → the
 *      fake receives an SChapter carrying the SAME memo.
 *
 *  (b) LOADER READY, NO CIRCULAR WAIT (the MangaFire
 *      conviction): on the donor road a page load must end
 *      with `stream != null` AND `status == Ready`, stamped
 *      by the LOADER at stream assignment, and a collector
 *      of `Page.statusFlow` must OBSERVE that Ready — the
 *      pre-v1.5.11 pipeline deferred Ready to a view outcome
 *      that waited on the very status emission the silent
 *      stream assignment never produced (spinner forever).
 *
 * Seam compromises forced by the landed code's shape
 * (documented per the gate brief):
 *  - `MihonSourceAdapter.mapChapters` and
 *    `SourceBookSync.applyPlan` are both private. They are
 *    driven through their production public roads —
 *    `adapter.chapters()` / `detailsAndChapters()` for the
 *    mapping, and `SourceBookSync.refresh()` (the call whose
 *    body ends in `applyPlan`) for the store — against a
 *    Robolectric in-memory [XreaderDatabase] (the v12
 *    schema; the memo column exists only there), wired
 *    exactly as FenrirRecordedFilingTest wires its real-DB
 *    sync stack.
 *  - `retainedChapters` is COMPANION-scoped (keyed by
 *    namespaced source id + chapter url), so two adapter
 *    instances over the same source with the SAME
 *    namespaced id share snapshots in-process and a true
 *    cold-process simulation is impossible in one process.
 *    The cold path is therefore driven through a fresh
 *    adapter instance carrying a DIFFERENT namespaced id
 *    over the same fake source: its snapshot lookup misses
 *    by construction, so the stored row's memo text is the
 *    ONLY carrier that can save the page-list call — which
 *    is exactly the cold-process claim (a new process has
 *    an empty companion map and only the row).
 *  - `PipelinePageLoader.internalLoadPage` is private; the
 *    pipeline leg is driven through the public road
 *    (`getPages()` + `loadPage()` with a byte-providing
 *    pipeline), whose worker queue is the only caller of
 *    `internalLoadPage`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class V1511MangaPathTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ── (a) the memo round trip ─────────────────────────────

    /**
     * An Asura-shaped fake: its chapters carry a non-empty
     * memo (a session token + chapter key), and its
     * `getPageList` ANSWERS LIKE ASURA — a chapter arriving
     * without its own memo token is refused with the exact
     * "Refresh Chapter List" failure the owner hit.
     */
    private class MemoFakeSource : HttpSource() {

        override val id: Long = 777L
        override val name: String = "FakePanels1511"
        override val lang: String = "en"
        override val baseUrl: String = "https://panels.test"
        override val supportsLatest: Boolean = true
        override val client: OkHttpClient = OkHttpClient()

        /** The memo chapter 1's extension state carries. */
        val memoCh1: JsonObject = buildJsonObject {
            put("sessionToken", JsonPrimitive("tok-asura-1511"))
            put("chapterKey", JsonPrimitive("key-ch1"))
        }

        /** Every SChapter handed to getPageList, verbatim. */
        val pageListChapters = CopyOnWriteArrayList<SChapter>()

        private val updateChapters: List<SChapter> = listOf(
            SChapter.create().apply {
                name = "Chapter 2"
                url = "/ch/2"
                chapter_number = 2f
                date_upload = 1_700_000_002_000
                scanlator = "Gengar"
                memo = buildJsonObject {
                    put("sessionToken", JsonPrimitive("tok-ch2"))
                }
            },
            SChapter.create().apply {
                name = "Chapter 1"
                url = "/ch/1"
                chapter_number = 1f
                date_upload = 1_700_000_001_000
                scanlator = "Gengar"
                memo = memoCh1
            }
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
        ): SMangaUpdate {
            val details = if (fetchDetails) {
                SManga.create().apply {
                    url = manga.url
                    title = "Alpha"
                    author = "Some Author"
                    description = "A story."
                    thumbnail_url = "/covers/alpha-hd.jpg"
                    initialized = true
                }
            } else {
                manga
            }
            return SMangaUpdate(
                details,
                if (fetchChapters) updateChapters else emptyList()
            )
        }

        override suspend fun getPageList(
            chapter: SChapter
        ): List<Page> {
            pageListChapters += chapter
            // The Asura conviction, as a guard: chapter 1's
            // pages exist only for a caller that hands the
            // extension its own state back.
            if (chapter.url == "/ch/1") {
                val token =
                    (chapter.memo["sessionToken"] as? JsonPrimitive)
                        ?.content
                if (token != "tok-asura-1511") {
                    throw IllegalStateException("Refresh Chapter List")
                }
            }
            return listOf(
                Page(
                    0, url = chapter.url,
                    imageUrl = "https://cdn.test${chapter.url}/0.jpg"
                )
            )
        }

        override suspend fun getImageUrl(page: Page): String =
            page.imageUrl ?: "https://cdn.test/signed"

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
                .body(
                    "memo-fake-bytes".toByteArray()
                        .toResponseBody("image/png".toMediaType())
                )
                .build()
    }

    private fun adapterFor(
        fake: MemoFakeSource,
        namespaced: String = NAMESPACED
    ) = MihonSourceAdapter(
        packageName = PKG,
        namespacedId = namespaced,
        mihon = fake,
        contentRating = ContentRating.SAFE
    )

    private class RealStack(
        val db: XreaderDatabase,
        val sync: SourceBookSync,
        val bookId: Long
    )

    /** The production-shaped sync stack over a REAL Room
     * database (in-memory, current schema v12): the same
     * wiring FenrirRecordedFilingTest uses, with the player
     * live-source registry pointed at [fake]. */
    private fun realStack(fake: MemoFakeSource): RealStack =
        runBlocking {
            val filesDir = tmp.newFolder()
            val db = Room.inMemoryDatabaseBuilder(
                RuntimeEnvironment.getApplication(),
                XreaderDatabase::class.java
            ).allowMainThreadQueries().build()
            val repoId = db.extensionRepoDao().insert(
                ExtensionRepo(
                    name = "Test Repo",
                    indexUrl = "https://repo.test/index.json"
                )
            )
            val extRowId = db.installedExtensionDao().insert(
                InstalledExtension(
                    repoId = repoId,
                    displayName = "FakePanels1511",
                    packageId = PKG,
                    versionName = "1.0",
                    versionCode = 1,
                    kind = ExtensionKind.KEIYOUSHI,
                    sha256 = "abc",
                    apiLevel = 16,
                    languages = "en",
                    sourceIds = "777",
                    trustState = TrustState.TRUSTED,
                    installedAt = 0,
                    updatedAt = 0
                )
            )
            val store = ExtensionStore(filesDir)
            val transport = FakeTransport { request ->
                if (request.url.contains("/covers/")) {
                    FakeTransport.ok("fake-cover-bytes")
                } else {
                    FakeTransport.ok("")
                }
            }
            val service = ExtensionService(
                db.installedExtensionDao(),
                store,
                HostContextFactory(store, testSessionStore(), transport),
                FakeDexLoader(),
                keiyoushiLiveSources = { mapOf(NAMESPACED to fake) }
            ) { FakeJsRuntime() }
            val sync = SourceBookSync(
                bookDao = db.bookDao(),
                chapterDao = db.chapterDao(),
                sourceMapDao = db.chapterSourceMapDao(),
                extensionDao = db.installedExtensionDao(),
                service = service,
                storage = BookStorage(filesDir),
                transport = transport,
                inTransaction = { block -> db.withTransaction { block() } }
            )
            val bookId = db.bookDao().insert(
                Book(
                    title = "Alpha",
                    sourceType = SourceType.EXTENSION,
                    extensionId = extRowId,
                    remoteId = SourceBookIds.encode(
                        NAMESPACED, "/manga/alpha"
                    ),
                    addedAt = 0,
                    updatedAt = 0,
                    inLibrary = true
                )
            )
            RealStack(db, sync, bookId)
        }

    /** The reader road's row rebuild (KomikkuReaderViewModel
     * .httpSourceBinding / ExtensionChapterResolver shape):
     * the facade chapter is rebuilt from the STORED ROW —
     * real title, title-parsed number, and the stored
     * dateText / scanlator / memo (schema v12). */
    private fun rebuildFromRow(
        row: app.xreader.data.db.Chapter
    ): SourceChapter = SourceChapter(
        id = row.remoteId ?: "",
        name = row.title,
        number = ChapterNumbers.parse(row.title),
        dateText = row.dateText,
        scanlator = row.scanlator,
        memo = row.memo
    )

    @Test
    fun `memo round trip - mapping persists through Room and warm rebuild reaches getPageList`() =
        runBlocking {
            val fake = MemoFakeSource()
            val adapter = adapterFor(fake)
            val book = SourceBook(id = "/manga/alpha", title = "Alpha")

            // Leg 1 — the mapping: the donor memo leaves the
            // adapter as the SourceChapter's memo JSON text.
            // (Pre-v1.5.11 mapChapters dropped it: null here,
            // and every later leg had nothing to carry.)
            val chapters = adapter.chapters(book)
            val ch1 = chapters.single { it.id == "/ch/1" }
            // v1.5.12 (M4): the persisted payload is the
            // extension's memo entries VERBATIM plus the host's
            // chapter-number key — the M4 carrier for the true
            // number (the Room row has no number column). The
            // extension entries must be untouched, and the
            // number key must carry chapter 1's true number.
            val persistedCh1 = Json.parseToJsonElement(ch1.memo!!) as JsonObject
            assertEquals(
                "the extension's own memo entries ride verbatim",
                fake.memoCh1,
                JsonObject(
                    persistedCh1 - MihonSourceAdapter.MEMO_CHAPTER_NUMBER_KEY
                )
            )
            assertEquals(
                "the host number key carries the true number",
                1f,
                MihonSourceAdapter.persistedChapterNumber(ch1.memo)
            )

            // Leg 2 — the store: refresh() runs the fused
            // fetch → reconcile → applyPlan into REAL Room
            // rows; the v12 memo column must hold the text.
            val stack = realStack(fake)
            try {
                val seeded = stack.db.bookDao().getById(stack.bookId)!!
                val refreshed = stack.sync.refresh(seeded)
                assertTrue(
                    "refresh failed: $refreshed",
                    refreshed is AppResult.Ok
                )
                val rows = stack.db.chapterDao().forBook(stack.bookId)
                assertEquals(2, rows.size)
                val row1 = rows.single { it.remoteId == "/ch/1" }
                // v1.5.12 (M4): the row's memo column holds the
                // payload — extension entries verbatim + the
                // host number key (see leg 1).
                val persistedRow1 =
                    Json.parseToJsonElement(row1.memo!!) as JsonObject
                assertEquals(
                    "the chapter row's memo column must hold the" +
                        " extension's memo entries",
                    fake.memoCh1,
                    JsonObject(
                        persistedRow1 -
                            MihonSourceAdapter.MEMO_CHAPTER_NUMBER_KEY
                    )
                )
                assertEquals(
                    1f,
                    MihonSourceAdapter.persistedChapterNumber(row1.memo)
                )

                // Leg 3 — the reader road: rebuild the facade
                // chapter from the stored row and ask for its
                // pages; the fake must receive its own memo
                // back (and its Asura guard must not fire).
                val pages = adapter.getPageList(rebuildFromRow(row1))
                assertEquals(1, pages.size)
                val received = fake.pageListChapters.last()
                assertEquals(
                    "getPageList must receive the SAME memo" +
                        " JsonObject the source minted",
                    fake.memoCh1, received.memo
                )
            } finally {
                stack.db.close()
            }
        }

    @Test
    fun `memo round trip - cold adapter restores the memo from the stored row alone`() =
        runBlocking {
            val fake = MemoFakeSource()
            val stack = realStack(fake)
            try {
                val seeded = stack.db.bookDao().getById(stack.bookId)!!
                val refreshed = stack.sync.refresh(seeded)
                assertTrue(
                    "refresh failed: $refreshed",
                    refreshed is AppResult.Ok
                )
                val row1 = stack.db.chapterDao().forBook(stack.bookId)
                    .single { it.remoteId == "/ch/1" }
                // v1.5.12 (M4): payload = extension entries +
                // host number key (see the warm test's leg 1).
                assertEquals(
                    1f,
                    MihonSourceAdapter.persistedChapterNumber(row1.memo)
                )
                val rebuilt = rebuildFromRow(row1)

                // The COLD path: a fresh adapter instance whose
                // namespaced id differs, so the companion
                // retained-snapshot map (keyed by namespaced
                // id) MISSES by construction — the in-process
                // stand-in for a new process, whose companion
                // map is empty. The stored row's memo text is
                // the only carrier left; schapterOf must parse
                // it back into the donor JsonObject.
                val coldAdapter = adapterFor(fake, COLD_NAMESPACED)
                val pages = coldAdapter.getPageList(rebuilt)
                assertEquals(1, pages.size)
                assertEquals(
                    "the cold road must restore the memo from" +
                        " the row alone",
                    fake.memoCh1, fake.pageListChapters.last().memo
                )

                // Negative control — the guard is real: the
                // same cold adapter handed a chapter rebuilt
                // WITHOUT the row's memo (the pre-v1.5.11
                // (id, name, null) rebuild) is refused exactly
                // as Asura refused the owner.
                try {
                    coldAdapter.getPageList(
                        SourceChapter(id = "/ch/1", name = "Chapter 1")
                    )
                    fail("expected the Asura-style refusal")
                } catch (e: IllegalStateException) {
                    assertEquals("Refresh Chapter List", e.message)
                }
            } finally {
                stack.db.close()
            }
        }

    // ── (b) loader Ready, no circular wait ──────────────────

    /** A donor-shaped fake for the loader roads: two pages,
     * the second carrying NO image address (its URL must be
     * resolved lazily, per page, at load time). */
    private class LoaderFakeSource : HttpSource() {

        override val id: Long = 777L
        override val name: String = "FakePanels1511Loader"
        override val lang: String = "en"
        override val baseUrl: String = "https://panels.test"
        override val supportsLatest: Boolean = true
        override val client: OkHttpClient = OkHttpClient()

        val pageListChapters = CopyOnWriteArrayList<SChapter>()
        val imageUrlCalls = CopyOnWriteArrayList<String>()
        val imageRequests = CopyOnWriteArrayList<String?>()

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
        ): SMangaUpdate = SMangaUpdate(manga, emptyList())

        override suspend fun getPageList(
            chapter: SChapter
        ): List<Page> {
            pageListChapters += chapter
            return listOf(
                Page(
                    0, url = "/read/p1",
                    imageUrl = "https://cdn.test/1.jpg"
                ),
                Page(1, url = "/read/p2", imageUrl = null)
            )
        }

        override suspend fun getImageUrl(page: Page): String {
            imageUrlCalls += page.url
            return "https://cdn.test/signed/" +
                page.url.substringAfterLast('/')
        }

        override suspend fun getImage(page: Page): Response {
            imageRequests += page.imageUrl
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

        companion object {
            val PAGE_BYTES = "fake-page-bytes-1511".toByteArray()
        }
    }

    private fun loaderAdapter(fake: LoaderFakeSource) =
        MihonSourceAdapter(
            packageName = PKG,
            namespacedId = NAMESPACED,
            mihon = fake,
            contentRating = ContentRating.SAFE
        )

    @Test
    fun `http loader - chapter loader binding road stamps Ready with the stream`() =
        runBlocking {
            val fake = LoaderFakeSource()
            val adapter = loaderAdapter(fake)
            val sourceChapter = SourceChapter(
                id = "/ch/1", name = "Chapter 1"
            )
            var ensuredCalls = 0
            // The VM's binding level: ChapterLoader asks the
            // pipeline for an httpSourceBinding; a Keiyoushi
            // chapter gets the transplanted HttpPageLoader and
            // the stored-record pipeline is never consulted
            // for its page list.
            val pipeline = object : ChapterPagePipeline {
                override suspend fun storedPages(
                    bookId: Long, chapterId: Long
                ): ChapterPages? = null

                override suspend fun ensuredPages(
                    bookId: Long, chapterId: Long
                ): ChapterPages? {
                    ensuredCalls++
                    return null
                }

                override fun assetFile(
                    bookId: Long, fileName: String
                ): File = File("/nonexistent")

                override suspend fun httpSourceBinding(
                    bookId: Long, chapterId: Long
                ): HttpSourceBinding =
                    HttpSourceBinding(adapter, sourceChapter)
            }
            val prefs = ReaderPreferences(InMemoryKomikkuStore())
            val chapterLoader = ChapterLoader(
                bookId = 7L,
                isLocalBook = false,
                pipeline = pipeline,
                readerPrefs = prefs,
                scope = CoroutineScope(Dispatchers.Unconfined)
            )
            val chapter = ReaderChapter(
                chapterId = 2L, title = "Chapter 2"
            )
            chapterLoader.loadChapter(chapter)
            try {
                assertTrue(chapter.state is ReaderChapter.State.Loaded)
                assertTrue(
                    "the donor road must select HttpPageLoader," +
                        " got ${chapter.pageLoader}",
                    chapter.pageLoader is HttpPageLoader
                )
                assertEquals(
                    "the stored-record pipeline must not serve" +
                        " a Keiyoushi chapter's page list",
                    0, ensuredCalls
                )
                assertEquals(1, fake.pageListChapters.size)
                assertEquals("/ch/1", fake.pageListChapters.single().url)

                val loader = chapter.pageLoader!!
                val page = chapter.pages!!.first()
                // The statusFlow collector is subscribed BEFORE
                // the load: Ready must be OBSERVED, not merely
                // readable afterwards — the viewer waits on
                // this flow, and the pre-fix road never emitted
                // it (the circular wait).
                val readySeen = async {
                    page.statusFlow.first { it == Page.State.Ready }
                }
                loader.loadPage(page)
                withTimeout(10_000) { readySeen.await() }
                assertEquals(Page.State.Ready, page.status)
                assertNotNull(
                    "Ready must arrive WITH its stream assigned",
                    page.stream
                )
                assertEquals(
                    LoaderFakeSource.PAGE_BYTES.toList(),
                    page.stream!!.invoke().readBytes().toList()
                )
            } finally {
                chapter.pageLoader?.recycle()
            }
        }

    @Test
    fun `http loader - direct loader lazily resolves the image url and stamps Ready`() =
        runBlocking {
            val fake = LoaderFakeSource()
            val adapter = loaderAdapter(fake)
            val chapter = ReaderChapter(
                chapterId = 2L, title = "Chapter 2"
            )
            val loader = HttpPageLoader(
                chapter = chapter,
                source = adapter,
                sourceChapter = SourceChapter(
                    id = "/ch/1", name = "Chapter 1"
                ),
                chapterCache = null,
                readerPreferences = ReaderPreferences(
                    InMemoryKomikkuStore()
                )
            )
            try {
                val pages = loader.getPages()
                assertEquals(2, pages.size)
                pages.forEach { it.chapter = chapter }
                // Page 2 arrived with no image address: the
                // donor resolves it LAZILY at load time through
                // the source's own getImageUrl, never in a
                // chapter-open storm.
                val page = pages[1]
                assertEquals(null, page.imageUrl)
                val readySeen = async {
                    page.statusFlow.first { it == Page.State.Ready }
                }
                loader.loadPage(page)
                withTimeout(10_000) { readySeen.await() }
                assertEquals(
                    listOf("/read/p2"), fake.imageUrlCalls.toList()
                )
                assertEquals(
                    "https://cdn.test/signed/p2", page.imageUrl
                )
                assertEquals(Page.State.Ready, page.status)
                assertNotNull(page.stream)
                assertEquals(
                    LoaderFakeSource.PAGE_BYTES.toList(),
                    page.stream!!.invoke().readBytes().toList()
                )
                assertTrue(
                    fake.imageRequests.contains(
                        "https://cdn.test/signed/p2"
                    )
                )
            } finally {
                loader.recycle()
            }
        }

    @Test
    fun `pipeline loader - byte fetching road stamps Ready without a view outcome`() =
        runBlocking {
            // The non-Keiyoushi road (declarative / JS manga):
            // with providesPageBytes() == true the worker runs,
            // and v1.5.11's internalLoadPage stamps Ready at
            // stream assignment — the test NEVER calls
            // onImageReady, so on the pre-fix M4 deferral this
            // page would sit at LoadPage forever.
            val stored = ChapterPages(
                pages = listOf(
                    ChapterPage(url = "https://cdn.example.test/p1.jpg"),
                    ChapterPage(url = "https://cdn.example.test/p2.jpg")
                )
            )
            val pipeline = object : ChapterPagePipeline {
                override suspend fun storedPages(
                    bookId: Long, chapterId: Long
                ): ChapterPages? = stored

                override suspend fun ensuredPages(
                    bookId: Long, chapterId: Long
                ): ChapterPages? = stored

                override fun assetFile(
                    bookId: Long, fileName: String
                ): File = File("/nonexistent")

                override fun providesPageBytes(): Boolean = true

                override suspend fun fetchImageBytes(
                    url: String, referer: String?
                ): ByteArray = PIPELINE_BYTES
            }
            val loader = PipelinePageLoader(
                7L, 2L, pipeline,
                CoroutineScope(Dispatchers.Unconfined),
                ReaderPreferences(InMemoryKomikkuStore())
            )
            try {
                val pages = loader.getPages()
                assertEquals(2, pages.size)
                val page = pages[0]
                val readySeen = async {
                    page.statusFlow.first { it == Page.State.Ready }
                }
                loader.loadPage(page)
                withTimeout(10_000) { readySeen.await() }
                assertEquals(Page.State.Ready, page.status)
                assertNotNull(
                    "Ready must arrive WITH its stream assigned",
                    page.stream
                )
                assertEquals(
                    PIPELINE_BYTES.toList(),
                    page.stream!!.invoke().readBytes().toList()
                )
            } finally {
                loader.recycle()
            }
        }

    companion object {
        const val PKG = "eu.kanade.tachiyomi.extension.en.fakepanels1511"
        const val NAMESPACED = "keiyoushi:$PKG:777"

        /** A namespaced id the warm path never touches: the
         * cold adapter's retained-snapshot lookups miss by
         * construction (see the class header). */
        const val COLD_NAMESPACED = "keiyoushi:$PKG:778"

        val PIPELINE_BYTES = "pipeline-bytes-1511".toByteArray()
    }
}
