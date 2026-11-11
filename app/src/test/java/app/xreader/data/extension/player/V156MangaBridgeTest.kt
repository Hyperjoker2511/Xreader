package app.xreader.data.extension.player

import app.xreader.data.extension.ContentRating
import app.xreader.di.PlayerImageClaim
import app.xreader.extensionapi.SourceChapter
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * v1.5.6 (§5.2 M2 + §5.3 M3) at the adapter seam:
 * - M2: a page whose bridge registration was evicted
 *   re-resolves through THIS adapter (its retained page /
 *   its chapter record) and is served by the source's own
 *   image request — never by a plain headerless fetch.
 * - M3: on a cold process (fresh adapter + fresh bridge),
 *   refreshing a stored chapter re-registers its pages
 *   through the adapter, keyed by the chapter's source
 *   identity, without any stored record being involved.
 *
 * v1.5.11 (transplant, Verdict 1 — M3 re-derived): the fake
 * below is an HttpSource, so its READER road is no longer
 * the M3 bridge refresh at all — ChapterLoader binds the
 * transplanted HttpPageLoader to the live adapter (the
 * donor's `source is HttpSource` arm), whose getPages()
 * re-resolves the page list through the source's own
 * getPageList and serves bytes through its getImage, with
 * no bridge registration created or consulted. The M3 test
 * below asserts THAT road; M2 (the bridge, which the
 * download/pipeline roads still travel) stands as written.
 */
class V156MangaBridgeTest {

    private val pkg = "eu.kanade.tachiyomi.extension.en.fakepanels"
    private val namespaced = "keiyoushi:$pkg:777"
    private val chapter = SourceChapter(id = "/ch/1", name = "Chapter 1")
    private val pageUrl = "https://cdn.test/1.jpg"

    private lateinit var source: FakeHttpSource
    private lateinit var adapter: MihonSourceAdapter

    @Before
    fun setUp() {
        PlayerImageBridge.clearForTest()
        source = FakeHttpSource()
        adapter = MihonSourceAdapter(
            packageName = pkg,
            namespacedId = namespaced,
            mihon = source,
            contentRating = ContentRating.SAFE
        )
    }

    @After
    fun tearDown() = PlayerImageBridge.clearForTest()

    @Test
    fun evictedPageReResolvesThroughTheAdapter() = runBlocking {
        adapter.chapterContent(chapter)
        assertTrue(PlayerImageBridge.knows(pageUrl))
        // Evict the registration by flooding the bridge.
        repeat(PlayerImageBridge.MAX_ENTRIES + 1) { i ->
            PlayerImageBridge.register("https://flood.test/$i.jpg") {
                byteArrayOf(0)
            }
        }
        assertFalse(PlayerImageBridge.knows(pageUrl))
        // The fetcher still claims it (owned), and the fetch
        // goes through the ADAPTER's request path — the
        // source's own image request records the call.
        assertTrue(PlayerImageClaim.claims(pageUrl))
        val before = source.imageRequests.size
        val bytes = PlayerImageBridge.fetchOrReresolve(pageUrl)
        assertEquals(
            FakeHttpSource.PAGE_TEXT,
            bytes?.let { String(it) }
        )
        assertTrue(
            "the source's own image request must serve the page",
            source.imageRequests.size > before &&
                source.imageRequests.last() == pageUrl
        )
    }

    @Test
    fun coldProcessRefreshReRegistersStoredChapterPages() = runBlocking {
        // First process: the chapter is read, registrations
        // live. Then the process dies: bridge cleared, a
        // FRESH adapter over the same source.
        adapter.chapterContent(chapter)
        assertEquals(1, source.pageListCalls)
        PlayerImageBridge.clearForTest()
        val coldAdapter = MihonSourceAdapter(
            packageName = pkg,
            namespacedId = namespaced,
            mihon = source,
            contentRating = ContentRating.SAFE
        )
        assertFalse(PlayerImageBridge.knows(pageUrl))

        // v1.5.11 (re-derived, see the file header): for an
        // HttpSource-shaped source the cold reader open does
        // NOT run the bridge refresh — ChapterLoader's donor
        // arm binds HttpPageLoader to the live adapter, and
        // its getPages() is exactly this call: the adapter's
        // donor getPageList over the chapter's source
        // identity (its remote id). The page list lands
        // through the SOURCE's getPageList, once.
        val pages = coldAdapter.getPageList(chapter)
        assertEquals(
            "the page list is re-resolved through the source's getPageList",
            2, source.pageListCalls
        )
        assertEquals(
            listOf(pageUrl, "https://cdn.test/2.jpg"),
            pages.map { it.imageUrl }
        )
        // The donor road carries the pages on the Page
        // objects themselves: no bridge registration is
        // created (or needed) for them.
        assertFalse(PlayerImageBridge.knows(pageUrl))

        // And the bytes travel the same donor road — the
        // source's own image request, via the adapter's
        // donor getImage on the landed page.
        val bytes = coldAdapter.getImage(pages.first())
        assertEquals(FakeHttpSource.PAGE_TEXT, String(bytes))
        assertEquals(pageUrl, source.imageRequests.last())
    }

    private class FakeHttpSource(
        override val id: Long = 777L
    ) : HttpSource() {

        override val name: String = "Fake Panels"
        override val lang: String = "en"
        override val baseUrl: String = "https://panels.test"
        override val supportsLatest: Boolean = true

        var pageListCalls = 0
        val imageRequests: MutableList<String?> =
            java.util.Collections.synchronizedList(mutableListOf())

        private val pages: List<Page> = listOf(
            Page(0, url = "/read/p1", imageUrl = "https://cdn.test/1.jpg"),
            Page(1, url = "/read/p2", imageUrl = "https://cdn.test/2.jpg")
        )

        override suspend fun getPopularManga(page: Int): MangasPage =
            MangasPage(emptyList(), false)

        override suspend fun getLatestUpdates(page: Int): MangasPage =
            MangasPage(emptyList(), false)

        override suspend fun getSearchManga(
            page: Int, query: String, filters: FilterList
        ): MangasPage = MangasPage(emptyList(), false)

        override suspend fun getMangaUpdate(
            manga: SManga,
            chapters: List<SChapter>,
            fetchDetails: Boolean,
            fetchChapters: Boolean
        ): SMangaUpdate = SMangaUpdate(manga, chapters)

        override suspend fun getPageList(
            chapter: SChapter
        ): List<Page> {
            pageListCalls++
            return pages
        }

        override suspend fun getImageUrl(page: Page): String =
            page.imageUrl ?: "https://cdn.test/unknown.jpg"

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
                .body(PAGE_TEXT.toByteArray().toResponseBody("image/png".toMediaType()))
                .build()
        }

        override fun getFilterList(): FilterList =
            FilterList(object : Filter.TriState("Genre") {})

        companion object {
            const val PAGE_TEXT = "fake-png-bytes"
        }
    }
}
