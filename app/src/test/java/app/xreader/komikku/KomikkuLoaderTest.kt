package app.xreader.komikku

import app.xreader.domain.content.ChapterPage
import app.xreader.domain.content.ChapterPages
import app.xreader.komikku.loader.ChapterLoader
import app.xreader.komikku.loader.ChapterPagePipeline
import app.xreader.komikku.loader.DownloadPageLoader
import app.xreader.komikku.loader.KomikkuPageAdapter
import app.xreader.komikku.loader.LoaderKind
import app.xreader.komikku.loader.PipelinePageLoader
import app.xreader.komikku.loader.isFullyDownloaded
import app.xreader.komikku.loader.selectLoaderKind
import app.xreader.komikku.loader.toReaderPages
import app.xreader.komikku.model.ReaderChapter
import app.xreader.komikku.presentation.displayPages
import app.xreader.komikku.setting.InMemoryKomikkuStore
import app.xreader.komikku.setting.ReaderPreferences
import eu.kanade.tachiyomi.source.model.Page
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FIX-18 proofs: the PageLoader contract at the seam —
 * loader selection (donor ChapterLoader's `when`), the
 * chapter invariant (every emitted page has its chapter set),
 * page state on the source-model Page base, and the
 * catalogue→book→chapters→pages journey over recorded-shape
 * fixtures (the shapes the real pipeline stores).
 */
class KomikkuLoaderTest {

    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val prefs = ReaderPreferences(InMemoryKomikkuStore())

    /** A recorded-shape pipeline: chapter 2 is remote-only,
     * chapter 3 is fully downloaded, chapter 1 is a Keiyoushi
     * chapter (Referer stamped by the player adapter). */
    private class FakePipeline(
        private val stored: Map<Long, ChapterPages>,
        private val assetDir: File
    ) : ChapterPagePipeline {
        override suspend fun storedPages(
            bookId: Long,
            chapterId: Long
        ): ChapterPages? = stored[chapterId]

        override suspend fun ensuredPages(
            bookId: Long,
            chapterId: Long
        ): ChapterPages? = stored[chapterId]

        override fun assetFile(bookId: Long, fileName: String): File =
            File(assetDir, "img-$fileName")
    }

    private fun remotePages(count: Int, referer: String? = null): ChapterPages =
        ChapterPages(
            pages = (1..count).map {
                ChapterPage(url = "https://cdn.example.test/p$it.jpg")
            },
            referer = referer
        )

    private fun downloadedPages(count: Int): ChapterPages =
        ChapterPages(
            pages = (1..count).map {
                ChapterPage(
                    url = "https://cdn.example.test/p$it.jpg",
                    localFile = "p$it.jpg"
                )
            }
        )

    private fun chapterLoader(
        pipeline: ChapterPagePipeline,
        isLocalBook: Boolean = false
    ) = ChapterLoader(
        bookId = 7L,
        isLocalBook = isLocalBook,
        pipeline = pipeline,
        readerPrefs = prefs,
        scope = scope
    )

    @Test
    fun loaderSelectionMatchesDonorWhen() {
        assertEquals(LoaderKind.DOWNLOAD, selectLoaderKind(isDownloaded = true, isLocalBook = false))
        assertEquals(LoaderKind.DOWNLOAD, selectLoaderKind(isDownloaded = false, isLocalBook = true))
        assertEquals(LoaderKind.PIPELINE, selectLoaderKind(isDownloaded = false, isLocalBook = false))
    }

    @Test
    fun fullyDownloadedNeedsEveryPageLocal() {
        assertTrue(downloadedPages(3).isFullyDownloaded())
        val mixed = ChapterPages(
            pages = listOf(
                ChapterPage(url = "https://x.test/1", localFile = "1.jpg"),
                ChapterPage(url = "https://x.test/2")
            )
        )
        assertEquals(false, mixed.isFullyDownloaded())
        assertEquals(false, ChapterPages().isFullyDownloaded())
    }

    @Test
    fun pipelineChapterLoadsWithChapterInvariantOnEveryPage() = runBlocking {
        val pipeline = FakePipeline(
            stored = mapOf(2L to remotePages(3)),
            assetDir = File("/nonexistent")
        )
        val chapter = ReaderChapter(chapterId = 2L, title = "Chapter 2")
        chapterLoader(pipeline).loadChapter(chapter)

        assertTrue(chapter.state is ReaderChapter.State.Loaded)
        assertTrue(chapter.pageLoader is PipelinePageLoader)
        assertEquals(false, chapter.pageLoader!!.isLocal)
        val pages = chapter.pages!!
        assertEquals(3, pages.size)
        // THE INVARIANT (round-12 check 1): every page the viewer
        // will see has its lateinit chapter set.
        pages.forEach { page ->
            assertSame(chapter, page.chapter)
            assertEquals(
                "https://cdn.example.test/p${page.index + 1}.jpg",
                page.loadKey
            )
        }
    }

    @Test
    fun downloadedChapterSelectsDownloadLoaderAndInvariantHolds() = runBlocking {
        val dir = createTempDir("komikku-dl")
        try {
            // The downloaded page files exist on disk.
            for (i in 1..2) File(dir, "img-p$i.jpg").writeBytes(byteArrayOf(1))
            val pipeline = FakePipeline(
                stored = mapOf(3L to downloadedPages(2)),
                assetDir = dir
            )
            val chapter = ReaderChapter(chapterId = 3L, title = "Chapter 3")
            chapterLoader(pipeline).loadChapter(chapter)

            assertTrue(chapter.pageLoader is DownloadPageLoader)
            assertEquals(true, chapter.pageLoader!!.isLocal)
            val pages = chapter.pages!!
            pages.forEach { page ->
                assertSame(chapter, page.chapter)
                // Local copy wins over the remote URL.
                assertEquals("p${page.index + 1}.jpg", page.loadKey)
            }
            // Page state rides the source-model Page base (check 3).
            val loader = chapter.pageLoader!!
            loader.loadPage(pages[0])
            assertEquals(Page.State.Ready, pages[0].status)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun downloadLoaderReportsMissingFileAsErrorState() = runBlocking {
        val pipeline = FakePipeline(
            stored = mapOf(3L to downloadedPages(1)),
            assetDir = File("/nonexistent-dir")
        )
        val loader = DownloadPageLoader(7L, 3L, pipeline)
        val pages = loader.getPages()
        loader.loadPage(pages[0])
        assertTrue(pages[0].status is Page.State.Error)
    }

    @Test
    fun pipelineLoadPageMarksReadyThroughBaseState() = runBlocking {
        // v1.5.6 (§5.4, M4): this test used to assert Ready
        // straight from loadPage — that WAS the defect (Ready
        // stamped from a non-blank load key before anything
        // loaded). Now loadPage only arms the page; Ready
        // arrives with the actual image-load outcome.
        val pipeline = FakePipeline(
            stored = mapOf(2L to remotePages(1)),
            assetDir = File("/nonexistent")
        )
        val loader = PipelinePageLoader(7L, 2L, pipeline, scope)
        val pages = loader.getPages()
        loader.loadPage(pages[0])
        assertEquals(Page.State.LoadPage, pages[0].status)
        loader.onImageReady(pages[0])
        assertEquals(Page.State.Ready, pages[0].status)
    }

    // ── v1.5.6 §5.1 (M1): cancellation is not an error ─────

    @Test
    fun cancellationMidFlightDoesNotStampError() = runBlocking {
        val pipeline = object : ChapterPagePipeline {
            override suspend fun storedPages(
                bookId: Long,
                chapterId: Long
            ): ChapterPages? = null

            override suspend fun ensuredPages(
                bookId: Long,
                chapterId: Long
            ): ChapterPages? {
                delay(Long.MAX_VALUE)
                return null
            }

            override fun assetFile(bookId: Long, fileName: String): File =
                File("/nonexistent")
        }
        val chapter = ReaderChapter(chapterId = 5L, title = "Slow")
        val deferred = async { chapterLoader(pipeline).loadChapter(chapter) }
        delay(50)
        deferred.cancel()
        var propagated = false
        try {
            deferred.await()
        } catch (e: CancellationException) {
            propagated = true
        }
        assertTrue("cancellation must propagate", propagated)
        assertFalse(
            "a cancelled load must not wear an Error state",
            chapter.state is ReaderChapter.State.Error
        )
    }

    @Test
    fun genuineFailureStillStampsError() = runBlocking {
        val pipeline = object : ChapterPagePipeline {
            override suspend fun storedPages(
                bookId: Long,
                chapterId: Long
            ): ChapterPages? = null

            override suspend fun ensuredPages(
                bookId: Long,
                chapterId: Long
            ): ChapterPages? = throw IOException("site down")

            override fun assetFile(bookId: Long, fileName: String): File =
                File("/nonexistent")
        }
        val chapter = ReaderChapter(chapterId = 5L, title = "Broken")
        var threw = false
        try {
            chapterLoader(pipeline).loadChapter(chapter)
        } catch (e: IOException) {
            threw = true
        }
        assertTrue(threw)
        assertTrue(chapter.state is ReaderChapter.State.Error)
    }

    // ── v1.5.6 §5.3 (M3): stored-first serving refreshes ──

    @Test
    fun storedChapterRefreshesRegistrationsBeforeServing() = runBlocking {
        var refreshCalls = 0
        var ensuredCalls = 0
        val refreshedFor = mutableListOf<Pair<Long, Long>>()
        val pipeline = object : ChapterPagePipeline {
            override suspend fun storedPages(
                bookId: Long,
                chapterId: Long
            ): ChapterPages? = remotePages(2)

            override suspend fun ensuredPages(
                bookId: Long,
                chapterId: Long
            ): ChapterPages? {
                ensuredCalls++
                return remotePages(2)
            }

            override fun assetFile(bookId: Long, fileName: String): File =
                File("/nonexistent")

            override suspend fun refreshRegistrations(
                bookId: Long,
                chapterId: Long
            ) {
                refreshCalls++
                refreshedFor += bookId to chapterId
            }
        }
        val chapter = ReaderChapter(chapterId = 2L, title = "C2")
        chapterLoader(pipeline).loadChapter(chapter)
        assertTrue(chapter.state is ReaderChapter.State.Loaded)
        assertTrue(
            "stored-first serving must refresh registrations",
            refreshCalls >= 1
        )
        assertTrue(refreshedFor.all { it == (7L to 2L) })
        assertEquals(
            "stored pages are served, never re-fetched",
            0, ensuredCalls
        )
    }

    // ── v1.5.6 §5.4 (M4): Ready is the image outcome ──────

    @Test
    fun failingImageOutcomeStampsErrorAndRetryReArms() = runBlocking {
        val pipeline = FakePipeline(
            stored = mapOf(2L to remotePages(1)),
            assetDir = File("/nonexistent")
        )
        val loader = PipelinePageLoader(7L, 2L, pipeline, scope)
        val pages = loader.getPages()
        val page = pages[0]
        loader.loadPage(page)
        assertFalse(page.status is Page.State.Ready)
        loader.onImageError(page, IOException("image host gone"))
        assertTrue(
            "a failed image must be an Error, not a fake Ready",
            page.status is Page.State.Error
        )
        // The retry seam exists and re-arms the page.
        loader.retryPage(page)
        assertEquals(Page.State.LoadPage, page.status)
    }

    @Test
    fun pipelinePageWithNothingToLoadIsAnErrorAtOnce() = runBlocking {
        val pipeline = FakePipeline(
            stored = emptyMap(),
            assetDir = File("/nonexistent")
        )
        val loader = PipelinePageLoader(7L, 2L, pipeline, scope)
        val page = app.xreader.komikku.model.ReaderPage(0, url = "")
        loader.loadPage(page)
        assertTrue(page.status is Page.State.Error)
    }

    @Test
    fun requestedPageRuleMatchesDonor() = runBlocking {
        val pipeline = FakePipeline(
            stored = mapOf(2L to remotePages(5)),
            assetDir = File("/nonexistent")
        )
        // Unread chapter: the requested page is honoured.
        val unread = ReaderChapter(chapterId = 2L, title = "C2", read = false)
        chapterLoader(pipeline).loadChapter(unread, page = 3)
        assertEquals(3, unread.requestedPage)
        // Read chapter, no requested page, preserve off: stays 0.
        val read = ReaderChapter(chapterId = 2L, title = "C2", read = true, lastPageRead = 4)
        chapterLoader(pipeline).loadChapter(read)
        assertEquals(0, read.requestedPage)
    }

    @Test
    fun emptyPageListIsAnErrorState() = runBlocking {
        val pipeline = FakePipeline(
            stored = mapOf(9L to ChapterPages()),
            assetDir = File("/nonexistent")
        )
        val chapter = ReaderChapter(chapterId = 9L, title = "Empty")
        var threw = false
        try {
            chapterLoader(pipeline).loadChapter(chapter)
        } catch (e: Exception) {
            threw = true
        }
        assertTrue(threw)
        assertTrue(chapter.state is ReaderChapter.State.Error)
    }

    @Test
    fun recycleOnUnrefClearsLoaderAndState() = runBlocking {
        val pipeline = FakePipeline(
            stored = mapOf(2L to remotePages(2)),
            assetDir = File("/nonexistent")
        )
        val chapter = ReaderChapter(chapterId = 2L, title = "C2")
        chapterLoader(pipeline).loadChapter(chapter)
        val loader = chapter.pageLoader!!
        chapter.ref()
        chapter.unref()
        assertTrue(loader.isRecycled)
        assertEquals(null, chapter.pageLoader)
        assertTrue(chapter.state is ReaderChapter.State.Wait)
    }

    @Test
    fun seamMappingCarriesRefererAndOrder() {
        val pages = remotePages(3, referer = "https://source.example.test/")
        val readerPages = pages.toReaderPages()
        assertEquals(listOf(0, 1, 2), readerPages.map { it.index })
        readerPages.forEach {
            assertEquals("https://source.example.test/", it.referer)
        }
    }

    /** The journey: catalogue-shaped chapter list → book window →
     * chapters → pages render list, over the recorded shapes. */
    @Test
    fun journeyFromChapterListToRenderablePages() = runBlocking {
        val chapters = listOf(1L to "Ch 1", 2L to "Ch 2", 3L to "Ch 3")
        val window = KomikkuPageAdapter.viewerChapters(chapters, currentChapterId = 2L)
        assertNotNull(window)
        assertEquals(1L, window!!.prevChapter?.chapterId)
        assertEquals(2L, window.currChapter.chapterId)
        assertEquals(3L, window.nextChapter?.chapterId)

        val pipeline = FakePipeline(
            stored = mapOf(
                2L to remotePages(4, referer = "https://source.example.test/")
            ),
            assetDir = File("/nonexistent")
        )
        chapterLoader(pipeline).loadChapter(window.currChapter)
        val renderable = window.currChapter.pages!!.map { it.loadKey }
        assertEquals(
            listOf(
                "https://cdn.example.test/p1.jpg",
                "https://cdn.example.test/p2.jpg",
                "https://cdn.example.test/p3.jpg",
                "https://cdn.example.test/p4.jpg"
            ),
            renderable
        )
    }

    @Test
    fun dualPageSplitHalvesWidePagesInOrder() {
        val wide = app.xreader.komikku.model.ReaderPage(0, "https://x.test/w")
            .apply { fullPage = true }
        val normal = app.xreader.komikku.model.ReaderPage(1, "https://x.test/n")
        val split = displayPages(listOf(wide, normal), dualPageSplit = true, dualPageInvert = false)
        assertEquals(3, split.size)
        assertEquals(listOf(0, 1, -1), split.map { it.half })
        val inverted = displayPages(listOf(wide, normal), dualPageSplit = true, dualPageInvert = true)
        assertEquals(listOf(1, 0, -1), inverted.map { it.half })
        val off = displayPages(listOf(wide, normal), dualPageSplit = false, dualPageInvert = false)
        assertEquals(2, off.size)
    }
}
