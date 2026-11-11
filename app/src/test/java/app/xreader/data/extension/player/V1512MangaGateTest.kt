package app.xreader.data.extension.player

import android.app.Application
import android.net.Uri
import app.xreader.data.extension.ContentRating
import app.xreader.data.extension.testSessionStore
import app.xreader.data.extension.testVault
import app.xreader.domain.content.ChapterPages
import app.xreader.extensionapi.SourceBook
import app.xreader.extensionapi.SourceChapter
import app.xreader.komikku.loader.ChapterLoader
import app.xreader.komikku.loader.ChapterPagePipeline
import app.xreader.komikku.loader.HttpPageLoader
import app.xreader.komikku.loader.HttpSourceBinding
import app.xreader.komikku.model.InsertPage
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
 * v1.5.12 gate — FRONT 2 (manga) path proofs for the items
 * the v1.5.11 forensics left as code facts / open questions:
 *
 *  (a) PAGES-BEYOND-1 POPULATION (Lane 1's open sub-question,
 *      made mandatory by the brief): a six-page chapter's
 *      pages 2..6 JOIN the viewer's page list through the
 *      ChapterLoader binding road, and every one of them
 *      reaches Ready with a stream when loaded — the device
 *      failure ("page 1 renders, nothing beyond it moves")
 *      must not have a loader-side half hiding behind the
 *      convicted gesture layer.
 *  (b) PAGE.URI PRESERVATION (M3, Lane 6 AT-RISK 1):
 *      HttpPageLoader.getPages re-creates each donor Page as
 *      a ReaderPage — the donor Page's uri must survive the
 *      re-creation (pre-v1.5.12 it was dropped to null).
 *  (c) COLD-ROAD CHAPTER NUMBER (M4, Lane 6 AT-RISK 2): an
 *      unnumbered-title chapter ("Prologue") round-trips its
 *      extension-minted chapter_number (7) through the
 *      PERSISTED memo payload — the cold adapter's
 *      getPageList receives 7, not -1 — with the extension's
 *      own memo entries restored verbatim (host key stripped).
 *  (d) ONE BRACKET PER PAGE (M5, Lane 6 AT-RISK 3): a page
 *      whose image address resolves lazily (getImageUrl +
 *      getImage) enters PlayerWebViewSync.around EXACTLY ONCE
 *      at the outer level — the per-call brackets inside the
 *      adapter detect the loader's page-load bracket and
 *      no-op (pre-v1.5.12: two outer brackets per page).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class V1512MangaGateTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ── fakes ───────────────────────────────────────────────

    /** Six pages; odd indexes carry no image address (lazy
     * resolution at load); page index 2 carries a donor uri. */
    private class MultiPageFakeSource : HttpSource() {

        override val id: Long = 777L
        override val name: String = "FakePanels1512"
        override val lang: String = "en"
        override val baseUrl: String = "https://panels.test"
        override val supportsLatest: Boolean = true
        override val client: OkHttpClient = OkHttpClient()

        val pageListChapters = CopyOnWriteArrayList<SChapter>()
        val imageUrlCalls = CopyOnWriteArrayList<String>()

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

        override suspend fun getPageList(chapter: SChapter): List<Page> {
            pageListChapters += chapter
            return (0 until 6).map { i ->
                Page(
                    i,
                    url = "/read/p${i + 1}",
                    imageUrl = if (i % 2 == 0) {
                        "https://cdn.test/${i + 1}.jpg"
                    } else {
                        null
                    },
                    uri = if (i == 2) PAGE_URI else null
                )
            }
        }

        override suspend fun getImageUrl(page: Page): String {
            imageUrlCalls += page.url
            return "https://cdn.test/signed/" +
                page.url.substringAfterLast('/')
        }

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
                .body(PAGE_BYTES.toResponseBody("image/png".toMediaType()))
                .build()

        companion object {
            val PAGE_BYTES = "fake-page-bytes-1512".toByteArray()
            val PAGE_URI: Uri = Uri.parse("content://fake.pages/p3")
        }
    }

    /** One unnumbered-title chapter whose extension-minted
     * number is 7 and whose memo carries a private key;
     * getPageList refuses (Asura-shaped) unless BOTH come
     * back intact. */
    private class PrologueFakeSource : HttpSource() {

        override val id: Long = 777L
        override val name: String = "FakePrologue1512"
        override val lang: String = "en"
        override val baseUrl: String = "https://panels.test"
        override val supportsLatest: Boolean = true
        override val client: OkHttpClient = OkHttpClient()

        val memoPrologue: JsonObject = buildJsonObject {
            put("chapterKey", JsonPrimitive("prologue-key"))
        }

        val pageListChapters = CopyOnWriteArrayList<SChapter>()

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
        ): SMangaUpdate = SMangaUpdate(
            manga,
            if (fetchChapters) {
                listOf(
                    SChapter.create().apply {
                        name = "Prologue"
                        url = "/ch/prologue"
                        chapter_number = 7f
                        date_upload = 1_700_000_007_000
                        memo = memoPrologue
                    }
                )
            } else {
                emptyList()
            }
        )

        override suspend fun getPageList(chapter: SChapter): List<Page> {
            pageListChapters += chapter
            if (chapter.chapter_number != 7f ||
                chapter.memo != memoPrologue
            ) {
                throw IllegalStateException("Refresh Chapter List")
            }
            return listOf(
                Page(0, url = "/read/pro1", imageUrl = "https://cdn.test/pro1.jpg")
            )
        }
    }

    private fun adapterFor(
        fake: HttpSource,
        namespaced: String = NAMESPACED
    ) = MihonSourceAdapter(
        packageName = PKG,
        namespacedId = namespaced,
        mihon = fake,
        contentRating = ContentRating.SAFE
    )

    // ── (a) population ──────────────────────────────────────

    @Test
    fun `population - pages beyond the first join the viewer list and reach Ready`() =
        runBlocking {
            val fake = MultiPageFakeSource()
            val adapter = adapterFor(fake)
            val sourceChapter = SourceChapter(id = "/ch/1", name = "Chapter 1")
            val pipeline = object : ChapterPagePipeline {
                override suspend fun storedPages(
                    bookId: Long, chapterId: Long
                ): ChapterPages? = null

                override suspend fun ensuredPages(
                    bookId: Long, chapterId: Long
                ): ChapterPages? = null

                override fun assetFile(
                    bookId: Long, fileName: String
                ): File = File("/nonexistent")

                override suspend fun httpSourceBinding(
                    bookId: Long, chapterId: Long
                ): HttpSourceBinding = HttpSourceBinding(adapter, sourceChapter)
            }
            val chapterLoader = ChapterLoader(
                bookId = 7L,
                isLocalBook = false,
                pipeline = pipeline,
                readerPrefs = ReaderPreferences(InMemoryKomikkuStore()),
                scope = CoroutineScope(Dispatchers.Unconfined)
            )
            val chapter = ReaderChapter(chapterId = 2L, title = "Chapter 1")
            chapterLoader.loadChapter(chapter)
            try {
                assertTrue(chapter.state is ReaderChapter.State.Loaded)
                val pages = chapter.pages!!
                assertEquals(
                    "all six pages must JOIN the viewer's page list",
                    6, pages.size
                )
                assertEquals((0 until 6).toList(), pages.map { it.index })
                val loader = chapter.pageLoader!!
                // Page 1 is the control; pages 2..6 are the
                // claim — each must reach Ready WITH a stream.
                for (page in pages) {
                    val readySeen = async {
                        page.statusFlow.first { it == Page.State.Ready }
                    }
                    loader.loadPage(page)
                    withTimeout(10_000) { readySeen.await() }
                    assertEquals(Page.State.Ready, page.status)
                    assertNotNull(
                        "page ${page.index} Ready must carry its stream",
                        page.stream
                    )
                    assertEquals(
                        MultiPageFakeSource.PAGE_BYTES.toList(),
                        page.stream!!.invoke().readBytes().toList()
                    )
                }
                // The lazy pages (odd indexes) resolved through
                // the source's own getImageUrl, once each.
                assertEquals(
                    listOf("/read/p2", "/read/p4", "/read/p6"),
                    fake.imageUrlCalls.toList().sorted()
                )
            } finally {
                chapter.pageLoader?.recycle()
            }
        }

    // ── (b) Page.uri preservation ───────────────────────────

    @Test
    fun `uri - the donor page uri survives the loader re-creation`() =
        runBlocking {
            val fake = MultiPageFakeSource()
            val adapter = adapterFor(fake)
            val chapter = ReaderChapter(chapterId = 2L, title = "Chapter 1")
            val loader = HttpPageLoader(
                chapter = chapter,
                source = adapter,
                sourceChapter = SourceChapter(id = "/ch/1", name = "Chapter 1"),
                chapterCache = null,
                readerPreferences = ReaderPreferences(InMemoryKomikkuStore())
            )
            try {
                val pages = loader.getPages()
                pages.forEach { it.chapter = chapter }
                assertEquals(6, pages.size)
                assertEquals(
                    "the donor Page's uri must ride the ReaderPage" +
                        " re-creation (M3)",
                    MultiPageFakeSource.PAGE_URI, pages[2].uri
                )
                assertEquals(
                    "pages minted without a uri keep null",
                    null, pages[0].uri
                )
                assertEquals(
                    "a split page (InsertPage) inherits the uri too",
                    MultiPageFakeSource.PAGE_URI, InsertPage(pages[2]).uri
                )
            } finally {
                loader.recycle()
            }
        }

    // ── (c) cold-road chapter number ────────────────────────

    @Test
    fun `cold number - unnumbered title round trips its true number via the persisted memo`() =
        runBlocking {
            val fake = PrologueFakeSource()
            val adapter = adapterFor(fake)
            val book = SourceBook(id = "/manga/alpha", title = "Alpha")

            // The mapping: the facade chapter carries the true
            // number AND a memo payload that persists it.
            val mapped = adapter.chapters(book)
                .single { it.id == "/ch/prologue" }
            assertEquals(7f, mapped.number)
            assertEquals(
                "the persisted memo payload must carry the true number",
                7f, MihonSourceAdapter.persistedChapterNumber(mapped.memo)
            )

            // The cold road: a fresh adapter (snapshot miss by
            // construction) + a chapter rebuilt the way the
            // reader roads rebuild it — title-parsed number
            // (null for "Prologue") and the persisted memo.
            val coldAdapter = adapterFor(fake, COLD_NAMESPACED)
            val pages = coldAdapter.getPageList(
                SourceChapter(
                    id = "/ch/prologue",
                    name = "Prologue",
                    number = null,
                    memo = mapped.memo
                )
            )
            assertEquals(1, pages.size)
            val received = fake.pageListChapters.last()
            assertEquals(
                "getPageList must receive the TRUE number, not -1",
                7f, received.chapter_number
            )
            assertEquals(
                "the extension's own memo entries return verbatim" +
                    " (the host number key is stripped)",
                fake.memoPrologue, received.memo
            )

            // Negative control: no persisted payload, no
            // number — the fake refuses exactly as Asura did,
            // proving the payload above was the carrier.
            try {
                coldAdapter.getPageList(
                    SourceChapter(
                        id = "/ch/prologue", name = "Prologue", number = null
                    )
                )
                fail("expected the Asura-style refusal")
            } catch (e: IllegalStateException) {
                assertEquals("Refresh Chapter List", e.message)
            }
        }

    // ── (d) one bracket per page ────────────────────────────

    @Test
    fun `one bracket - a lazy page load enters the session bracket exactly once`() =
        runBlocking {
            // Production-shaped WebView cookie sync, installed
            // the way AppContainer installs it.
            val vault = testVault()
            PlayerWebViewSync.install(
                RuntimeEnvironment.getApplication(),
                testSessionStore(), vault
            )
            val fake = MultiPageFakeSource()
            val adapter = adapterFor(fake)
            val chapter = ReaderChapter(chapterId = 2L, title = "Chapter 1")
            val loader = HttpPageLoader(
                chapter = chapter,
                source = adapter,
                sourceChapter = SourceChapter(id = "/ch/1", name = "Chapter 1"),
                chapterCache = null,
                readerPreferences = ReaderPreferences(InMemoryKomikkuStore())
            )
            try {
                val pages = loader.getPages()
                pages.forEach { it.chapter = chapter }
                // Count from here: the chapter-level getPageList
                // bracket is not the claim — the PAGE load is.
                PlayerWebViewSync.outerBracketEntries.set(0)
                // Page index 1 has NO image address: its load
                // runs the getImageUrl + getImage pair.
                val page = pages[1]
                val readySeen = async {
                    page.statusFlow.first { it == Page.State.Ready }
                }
                loader.loadPage(page)
                withTimeout(10_000) { readySeen.await() }
                assertEquals(Page.State.Ready, page.status)
                assertEquals(listOf("/read/p2"), fake.imageUrlCalls.toList())
                assertEquals(
                    "the getImageUrl+getImage pair for one page must" +
                        " enter the session bracket exactly once (M5)",
                    1, PlayerWebViewSync.outerBracketEntries.get()
                )
            } finally {
                loader.recycle()
            }
        }

    companion object {
        const val PKG = "eu.kanade.tachiyomi.extension.en.fakepanels1511"
        const val NAMESPACED = "keiyoushi:$PKG:777"
        const val COLD_NAMESPACED = "keiyoushi:$PKG:778"
    }
}
