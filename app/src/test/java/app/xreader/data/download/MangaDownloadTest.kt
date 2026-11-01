package app.xreader.data.download

import app.xreader.core.error.AppResult
import app.xreader.data.FakeBookDao
import app.xreader.data.FakeChapterDao
import app.xreader.data.FakeChapterSourceMapDao
import app.xreader.data.FakeDownloadQueueDao
import app.xreader.data.FakeTransport
import app.xreader.data.db.Book
import app.xreader.data.db.Chapter
import app.xreader.data.db.DownloadKind
import app.xreader.data.db.DownloadQueueItem
import app.xreader.data.db.DownloadState
import app.xreader.data.db.QueueState
import app.xreader.data.files.BookStorage
import app.xreader.domain.content.ChapterContent
import app.xreader.domain.content.ChapterPage
import app.xreader.domain.content.ChapterPages
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Manga programme (M3): manga chapters in the download
 * engine — the same queue, the same states, honest outcomes.
 * A chapter whose page record is fully filled downloads like
 * any chapter; a partly-filled record makes the chapter
 * PARTIAL (never READY — a failed page never marks a chapter
 * downloaded) with the plain count on the failed queue item;
 * and a stored read-online record (URLs, no files) is never
 * "promoted" to downloaded without the fetch landing the
 * files.
 */
class MangaDownloadTest {

    @get:Rule val temp = TemporaryFolder()

    private val bookDao = FakeBookDao()
    private val chapterDao = FakeChapterDao()
    private val queueDao = FakeDownloadQueueDao()
    private val sourceMapDao = FakeChapterSourceMapDao()
    private var now = 1_000L

    private class AllNetworks : NetworkGate {
        override fun onUnmeteredNetwork() = true
        override fun hasNetwork() = true
    }

    private lateinit var storage: BookStorage

    private fun engine(
        resolver: (DownloadQueueItem) -> ChapterSourceResolver
    ): DownloadEngine {
        storage = BookStorage(temp.newFolder("files-${System.nanoTime()}"))
        return DownloadEngine(
            queueDao = queueDao,
            chapterDao = chapterDao,
            bookDao = bookDao,
            sourceMapDao = sourceMapDao,
            storage = storage,
            transport = FakeTransport { FakeTransport.ok("") },
            resolverForItem = resolver,
            networkGate = AllNetworks(),
            settingsProvider = {
                DownloadSettings(wifiOnlyPrefetch = true, autoPrefetch = true)
            },
            fileDownloaded = { _, _ -> null },
            delayer = { },
            jitter = { 1.0 },
            clock = { now }
        )
    }

    /** A resolver whose single candidate serves [content]
     * (a fresh copy per fetch) and counts its fetches. */
    private fun resolverServing(
        fetches: AtomicInteger,
        content: () -> ChapterContent
    ): (DownloadQueueItem) -> ChapterSourceResolver = {
        object : ChapterSourceResolver {
            override suspend fun candidatesFor(
                chapter: Chapter
            ): List<ResolvedSource> = listOf(
                ResolvedSource(
                    ChapterCandidate(
                        locator = chapter.remoteId ?: "",
                        bookId = chapter.bookId
                    )
                ) {
                    fetches.incrementAndGet()
                    FetchOutcome.Success(content())
                }
            )
        }
    }

    private fun pagesContent(filled: Int, total: Int): ChapterContent =
        ChapterContent(
            pages = ChapterPages(
                (1..total).map { i ->
                    ChapterPage(
                        url = "https://manga.example/pages/p$i.jpg",
                        localFile = if (i <= filled) "img-p$i.jpg" else null
                    )
                }
            )
        )

    private suspend fun bookWithChapter(): Pair<Long, Long> {
        val bookId = bookDao.insert(Book(title = "Panel Saga", addedAt = 1, updatedAt = 1))
        val chapterId = chapterDao.insert(
            Chapter(
                bookId = bookId, index = 0, title = "Chapter 1",
                remoteId = "https://manga.example/book/panel-saga/chapter-1",
                updatedAt = 1
            )
        )
        return bookId to chapterId
    }

    private suspend fun enqueue(bookId: Long, chapterId: Long) {
        queueDao.insert(
            DownloadQueueItem(
                bookId = bookId, chapterId = chapterId,
                kind = DownloadKind.CHAPTER,
                enqueuedAt = now, updatedAt = now
            )
        )
    }

    @Test
    fun `all pages filled downloads like any chapter`() = runTest {
        val (bookId, chapterId) = bookWithChapter()
        enqueue(bookId, chapterId)
        val fetches = AtomicInteger(0)
        engine(resolverServing(fetches) { pagesContent(2, 2) }).runQueue()

        assertEquals(QueueState.DONE, queueDao.items.value.single().state)
        assertEquals(
            DownloadState.READY, chapterDao.getById(chapterId)!!.downloadState
        )
        val stored = storage.readChapterContent(bookId, chapterId)
        assertTrue(stored is AppResult.Ok)
        val pages = (stored as AppResult.Ok).value.pages!!
        assertEquals(2, pages.downloadedCount)
    }

    @Test
    fun `a partly filled record is partial, never downloaded`() = runTest {
        val (bookId, chapterId) = bookWithChapter()
        enqueue(bookId, chapterId)
        val fetches = AtomicInteger(0)
        engine(resolverServing(fetches) { pagesContent(1, 2) }).runQueue()

        val item = queueDao.items.value.single()
        assertEquals(QueueState.FAILED, item.state)
        assertTrue(
            "the queue item must say the plain count, was: " +
                item.lastErrorDetail,
            item.lastErrorDetail?.contains(
                "only 1 of 2 pages downloaded"
            ) == true
        )
        val chapter = chapterDao.getById(chapterId)!!
        assertEquals(DownloadState.PARTIAL, chapter.downloadState)
        // The record is stored (the landed page is kept for
        // the retry) and names the page still missing.
        assertTrue(chapter.contentPath != null)
        val stored = storage.readChapterContent(bookId, chapterId)
        assertTrue(stored is AppResult.Ok)
        val pages = (stored as AppResult.Ok).value.pages!!
        assertEquals(1, pages.downloadedCount)
        assertEquals(2, pages.size)
    }

    @Test
    fun `a retry after partial completes the chapter`() = runTest {
        val (bookId, chapterId) = bookWithChapter()
        enqueue(bookId, chapterId)
        val fetches = AtomicInteger(0)
        // One engine, one storage; the fetch's fill level is
        // the variable (first pass lands one page, the retry
        // lands both — the resolver-level test proves the
        // retry only ASKS for the missing page).
        var fill = 1
        val eng = engine(resolverServing(fetches) { pagesContent(fill, 2) })
        eng.runQueue()
        assertEquals(
            DownloadState.PARTIAL,
            chapterDao.getById(chapterId)!!.downloadState
        )
        // The Downloads retry: the failed item goes back to
        // queued with its attempts reset (the controller's
        // resume shape).
        val failed = queueDao.items.value.single()
        assertEquals(QueueState.FAILED, failed.state)
        queueDao.update(
            failed.copy(
                state = QueueState.QUEUED, attempts = 0,
                lastErrorDetail = null
            )
        )
        fill = 2
        eng.runQueue()
        assertEquals(QueueState.DONE, queueDao.items.value.single().state)
        assertEquals(
            DownloadState.READY, chapterDao.getById(chapterId)!!.downloadState
        )
    }

    @Test
    fun `a read-online record is not promoted - the files must land`() =
        runTest {
            val (bookId, chapterId) = bookWithChapter()
            val fetches = AtomicInteger(0)
            val eng = engine(resolverServing(fetches) { pagesContent(2, 2) })
            // Seed the read-online state: the chapter record
            // exists (its pages are URLs, no files) and the
            // chapter row points at it, state NONE — exactly
            // what reading a manga chapter online leaves.
            val written = storage.writeChapterContent(
                bookId, chapterId, pagesContent(0, 2)
            )
            assertTrue(written is AppResult.Ok)
            chapterDao.setContent(
                chapterId, (written as AppResult.Ok).value, 0,
                DownloadState.NONE
            )
            enqueue(bookId, chapterId)
            eng.runQueue()
            // Promotion would have skipped the fetch entirely;
            // the engine must have fetched (and only then
            // marked the chapter downloaded).
            assertTrue("the chapter must be fetched, not promoted", fetches.get() > 0)
            assertEquals(
                DownloadState.READY,
                chapterDao.getById(chapterId)!!.downloadState
            )
        }

    @Test
    fun `a fully downloaded record still promotes without a fetch`() =
        runTest {
            val (bookId, chapterId) = bookWithChapter()
            val fetches = AtomicInteger(0)
            // The content never matters: a correct engine
            // never calls the fetcher for a complete record.
            val eng = engine(resolverServing(fetches) { pagesContent(2, 2) })
            // A chapter already fully on disk (record complete,
            // row not yet marked): the long-standing promotion
            // rule completes it where it stands.
            val written = storage.writeChapterContent(
                bookId, chapterId, pagesContent(2, 2)
            )
            assertTrue(written is AppResult.Ok)
            chapterDao.setContent(
                chapterId, (written as AppResult.Ok).value, 0,
                DownloadState.NONE
            )
            enqueue(bookId, chapterId)
            eng.runQueue()
            assertEquals(0, fetches.get())
            assertEquals(QueueState.DONE, queueDao.items.value.single().state)
            assertEquals(
                DownloadState.READY,
                chapterDao.getById(chapterId)!!.downloadState
            )
        }
}
