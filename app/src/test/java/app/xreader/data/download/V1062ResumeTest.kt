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
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v1.0.6.2 (D3): downloads RESUME — a run interrupted mid-book
 * continues from the first missing chapter, never restarts
 * the book. Three pins: an interrupted run resumes at the
 * right chapter (and never refetches the finished one), the
 * engine completes an item for an already-downloaded chapter
 * without touching the network, and Download-all is
 * idempotent over a partially downloaded book.
 */
class V1062ResumeTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val bookDao = FakeBookDao()
    private val chapterDao = FakeChapterDao()
    private val queueDao = FakeDownloadQueueDao()
    private val sourceMapDao = FakeChapterSourceMapDao()

    private class AllNetworks : NetworkGate {
        override fun onUnmeteredNetwork() = true
        override fun hasNetwork() = true
    }

    private lateinit var storage: BookStorage

    private fun engine(transport: FakeTransport): DownloadEngine {
        storage = BookStorage(temp.newFolder("files-${System.nanoTime()}"))
        return DownloadEngine(
            queueDao = queueDao,
            chapterDao = chapterDao,
            bookDao = bookDao,
            sourceMapDao = sourceMapDao,
            storage = storage,
            transport = transport,
            resolverForItem = {
                RoomChapterSourceResolver(sourceMapDao) {
                    UrlChapterFetcher(transport)
                }
            },
            networkGate = AllNetworks(),
            settingsProvider = {
                DownloadSettings(wifiOnlyPrefetch = true, autoPrefetch = true)
            },
            fileDownloaded = { _, _ -> null },
            delayer = { },
            jitter = { 1.0 },
            clock = { 1_000L }
        )
    }

    private suspend fun bookWithChapters(
        urls: List<String>
    ): Pair<Long, List<Long>> {
        val bookId = bookDao.insert(
            Book(title = "T", addedAt = 1, updatedAt = 1)
        )
        val chapterIds = urls.mapIndexed { index, url ->
            chapterDao.insert(
                Chapter(
                    bookId = bookId, index = index,
                    title = "Chapter ${index + 1}",
                    remoteId = url, updatedAt = 1
                )
            )
        }
        return bookId to chapterIds
    }

    private suspend fun enqueue(bookId: Long, chapterId: Long): Long =
        queueDao.insert(
            DownloadQueueItem(
                bookId = bookId, chapterId = chapterId,
                kind = DownloadKind.CHAPTER, enqueuedAt = 1, updatedAt = 1
            )
        )

    @Test
    fun `an interrupted run resumes at the first missing chapter`() = runTest {
        val (bookId, chapters) = bookWithChapters(
            listOf("https://src.test/ch1", "https://src.test/ch2")
        )
        enqueue(bookId, chapters[0])
        val failedItem = enqueue(bookId, chapters[1])
        var ch2Healthy = false
        val transport = FakeTransport { request ->
            when {
                request.url.endsWith("ch1") ->
                    FakeTransport.ok("First chapter body, complete and readable.")
                ch2Healthy ->
                    FakeTransport.ok("Second chapter body, complete and readable.")
                else -> AppResult.Ok(FakeTransport.response(404, "gone"))
            }
        }
        // Run 1: chapter 1 lands, chapter 2 fails permanently —
        // the run is "interrupted" with the book half done.
        engine(transport).runQueue()
        assertEquals(
            DownloadState.READY,
            chapterDao.getById(chapters[0])!!.downloadState
        )
        assertEquals(
            QueueState.FAILED, queueDao.getById(failedItem)!!.state
        )
        // Restart: the failed item is resumed (the controller's
        // resume writes exactly this) and a fresh engine drains.
        val item = queueDao.getById(failedItem)!!
        queueDao.update(
            item.copy(state = QueueState.QUEUED, attempts = 0, lastErrorDetail = null)
        )
        ch2Healthy = true
        engine(transport).runQueue()
        assertEquals(
            DownloadState.READY,
            chapterDao.getById(chapters[1])!!.downloadState
        )
        assertEquals(QueueState.DONE, queueDao.getById(failedItem)!!.state)
        // The finished chapter was never fetched a second time.
        assertEquals(
            1, transport.requests.count { it.url.endsWith("ch1") }
        )
        assertEquals(
            2, transport.requests.count { it.url.endsWith("ch2") }
        )
    }

    @Test
    fun `an item for a downloaded chapter completes without the network`() =
        runTest {
            val (bookId, chapters) = bookWithChapters(
                listOf("https://src.test/done")
            )
            chapterDao.setContent(
                chapters[0], "content/$bookId/ch-${chapters[0]}.json",
                words = 120, state = DownloadState.READY
            )
            val itemId = enqueue(bookId, chapters[0])
            val transport = FakeTransport {
                AppResult.Ok(FakeTransport.response(500, "must not be called"))
            }
            engine(transport).runQueue()
            assertEquals(QueueState.DONE, queueDao.getById(itemId)!!.state)
            assertTrue(transport.requests.isEmpty())
            assertEquals(
                DownloadState.READY,
                chapterDao.getById(chapters[0])!!.downloadState
            )
        }

    @Test
    fun `download all is idempotent over a partially downloaded book`() =
        runTest {
            val (bookId, chapters) = bookWithChapters(
                listOf(
                    "https://src.test/a", "https://src.test/b",
                    "https://src.test/c"
                )
            )
            val controller = DownloadController(
                queueDao, chapterDao, bookDao,
                BookStorage(temp.newFolder("ctrl")),
                WorkEnqueuer { }
            ) { 1_000L }
            controller.enqueueBook(bookId)
            assertEquals(3, queueDao.items.value.size)
            // A second tap while everything is queued adds nothing.
            controller.enqueueBook(bookId)
            assertEquals(3, queueDao.items.value.size)
            // Chapter 1 finishes; its item completes. Download-all
            // again enqueues only the two missing chapters.
            chapterDao.setContent(
                chapters[0], "content/$bookId/ch-${chapters[0]}.json",
                words = 90, state = DownloadState.READY
            )
            val firstItem = queueDao.items.value
                .first { it.chapterId == chapters[0] }
            queueDao.update(firstItem.copy(state = QueueState.DONE))
            controller.enqueueBook(bookId)
            val activeForBook = queueDao.items.value
                .filter { it.bookId == bookId && it.state == QueueState.QUEUED }
            assertEquals(
                setOf(chapters[1], chapters[2]),
                activeForBook.map { it.chapterId }.toSet()
            )
        }
}
