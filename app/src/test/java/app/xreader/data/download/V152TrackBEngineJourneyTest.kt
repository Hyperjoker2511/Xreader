package app.xreader.data.download

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
import app.xreader.data.net.HttpTransport
import app.xreader.domain.content.ChapterContent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v1.5.2 Track B (round-15) — §14.4 / §12.4 journeys over the REAL
 * DownloadEngine (fake DAOs/transport, the DownloadEngineTest
 * harness shape):
 *
 *  - a freshly fetched login-wall STUB is refused as a body on the
 *    queue road (round 1 found it stored WITH its count): the item
 *    fails with the gate's reason, nothing is stored, no count.
 *  - a STORED stub is never promoted to a download by a tap: the
 *    engine re-fetches, and only a real body lands READY.
 *  - prefetch expansion stamps the chapter ROWS AUTOMATIC at
 *    enqueue (durable), not only in the process-local item set —
 *    proven by stamping surviving a fetch failure, where no
 *    completion-time stamp could ever run.
 */
class V152TrackBEngineJourneyTest {

    @get:Rule val temp = TemporaryFolder()

    private val bookDao = FakeBookDao()
    private val chapterDao = FakeChapterDao()
    private val queueDao = FakeDownloadQueueDao()
    private val sourceMapDao = FakeChapterSourceMapDao()
    private var now = 5_000L

    private class AllNetworks : NetworkGate {
        override fun onUnmeteredNetwork() = true
        override fun hasNetwork() = true
    }

    private lateinit var storage: BookStorage

    private fun engine(transport: HttpTransport): DownloadEngine {
        storage = BookStorage(temp.newFolder("files-${System.nanoTime()}"))
        return DownloadEngine(
            queueDao = queueDao,
            chapterDao = chapterDao,
            bookDao = bookDao,
            sourceMapDao = sourceMapDao,
            storage = storage,
            transport = transport,
            resolverForItem = { RoomChapterSourceResolver(sourceMapDao) { UrlChapterFetcher(transport) } },
            networkGate = AllNetworks(),
            settingsProvider = { DownloadSettings(wifiOnlyPrefetch = false, autoPrefetch = true) },
            fileDownloaded = { _, _ -> null },
            delayer = { },
            jitter = { 1.0 },
            clock = { now }
        )
    }

    private val stubHtml =
        "<html><body><p>Please sign in to read this chapter. " +
            "Sign in to continue reading this story, members only.</p></body></html>"
    private val realHtml =
        "<html><body><p>Real chapter prose arrives at last, and it " +
            "keeps going with a proper story beat for the reader.</p></body></html>"

    private suspend fun bookWithChapter(url: String): Pair<Long, Long> {
        val bookId = bookDao.insert(Book(title = "T", addedAt = 1, updatedAt = 1))
        val chapterId = chapterDao.insert(
            Chapter(bookId = bookId, index = 0, title = "Chapter 1", remoteId = url, updatedAt = 1)
        )
        return bookId to chapterId
    }

    private suspend fun enqueueChapterItem(bookId: Long, chapterId: Long): Long =
        queueDao.insert(
            DownloadQueueItem(
                bookId = bookId, chapterId = chapterId, kind = DownloadKind.CHAPTER,
                enqueuedAt = now, updatedAt = now
            )
        )

    @Test
    fun `a fetched stub is refused - never stored, never counted, never READY`() = runTest {
        val (bookId, chapterId) = bookWithChapter("https://src.test/stub")
        enqueueChapterItem(bookId, chapterId)
        val transport = FakeTransport { FakeTransport.ok(stubHtml) }
        engine(transport).runQueue()
        val item = queueDao.items.value.single()
        assertEquals(QueueState.FAILED, item.state)
        assertTrue(
            "gate reason expected, was: ${item.lastErrorDetail}",
            item.lastErrorDetail?.contains("sign in") == true
        )
        val chapter = chapterDao.getById(chapterId)!!
        assertEquals(DownloadState.FAILED, chapter.downloadState)
        assertNull(chapter.contentPath)
        assertEquals(0, chapter.wordCount)
        assertFalse(BodyOrigin152.isDownloaded(chapter))
        assertEquals(0, chapterDao.downloadedCount(bookId))
    }

    @Test
    fun `a stored stub is not promoted - the tap re-fetches and only a real body lands`() = runTest {
        val (bookId, chapterId) = bookWithChapter("https://src.test/walled")
        val eng = engine(FakeTransport { FakeTransport.ok(realHtml) })
        // A stub body stored by an older build, carrying its count.
        val stubContent = ChapterContent(
            blocks = listOf(
                app.xreader.domain.content.ChapterBlock.Paragraph.plain(
                    "Please sign in to read this chapter. Sign in to continue, members only."
                )
            )
        )
        val saved = storage.writeChapterContent(bookId, chapterId, stubContent)
        assertTrue(saved is app.xreader.core.error.AppResult.Ok)
        val path = (saved as app.xreader.core.error.AppResult.Ok).value
        chapterDao.update(
            chapterDao.getById(chapterId)!!.copy(
                contentPath = path, wordCount = 64,
                downloadState = DownloadState.NONE, bodyOrigin = BodyOrigin152.AUTOMATIC
            )
        )
        enqueueChapterItem(bookId, chapterId)
        eng.runQueue()
        val item = queueDao.items.value.single()
        assertEquals(QueueState.DONE, item.state)
        val chapter = chapterDao.getById(chapterId)!!
        assertEquals(DownloadState.READY, chapter.downloadState)
        // What landed is the REAL body, not the promoted stub.
        val stored = storage.readChapterContent(bookId, chapterId)
        assertTrue(stored is app.xreader.core.error.AppResult.Ok)
        assertTrue(
            (stored as app.xreader.core.error.AppResult.Ok).value.plainText()
                .contains("Real chapter prose")
        )
    }

    @Test
    fun `prefetch expansion stamps chapter rows AUTOMATIC at enqueue - even when the fetch then fails`() =
        runTest {
            val bookId = bookDao.insert(Book(title = "P", addedAt = 1, updatedAt = 1))
            val chapterIds = (0 until 3).map { i ->
                chapterDao.insert(
                    Chapter(
                        bookId = bookId, index = i, title = "Chapter ${i + 1}",
                        remoteId = "https://src.test/p$i", updatedAt = 1
                    )
                )
            }
            queueDao.insert(
                DownloadQueueItem(
                    bookId = bookId, chapterId = null, kind = DownloadKind.PREFETCH,
                    enqueuedAt = now, updatedAt = now
                )
            )
            // Every chapter fetch fails: no completion-time stamp
            // can run, so an AUTOMATIC row stamp can only have
            // come from the enqueue-time stamp.
            val transport = FakeTransport {
                app.xreader.core.error.AppResult.Ok(FakeTransport.response(404, "gone"))
            }
            engine(transport).runQueue()
            for (id in chapterIds) {
                assertEquals(
                    BodyOrigin152.AUTOMATIC,
                    chapterDao.getById(id)!!.bodyOrigin
                )
                assertFalse(BodyOrigin152.isDownloaded(chapterDao.getById(id)!!))
            }
            assertNotNull(queueDao.items.value.first { it.kind == DownloadKind.PREFETCH })
        }
}
