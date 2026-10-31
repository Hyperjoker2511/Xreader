package app.xreader.data.download

import app.xreader.core.error.AppError
import app.xreader.core.error.AppResult
import app.xreader.data.FakeBookDao
import app.xreader.data.FakeChapterDao
import app.xreader.data.FakeChapterSourceMapDao
import app.xreader.data.FakeDownloadQueueDao
import app.xreader.data.FakeTransport
import app.xreader.data.db.Book
import app.xreader.data.db.Chapter
import app.xreader.data.db.DownloadKind
import app.xreader.data.db.DownloadQueueDao
import app.xreader.data.db.DownloadQueueItem
import app.xreader.data.db.DownloadState
import app.xreader.data.db.QueueState
import app.xreader.data.files.BookStorage
import app.xreader.domain.content.ChapterBlock
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v1.2.0 (audit finding 1 / D5 / D14 / D17): the download
 * optimisation pins.
 *  - progress touches are coalesced (whole percent or ~500 ms,
 *    final always) instead of per raw tick;
 *  - the engine's attempt counter is persisted at round
 *    boundaries and terminal writes only — a 5-attempt,
 *    3-candidate failure costs 3 queue-row updates, not 7 —
 *    while the final row stays exact;
 *  - DONE queue rows are pruned at drain START (rows the current
 *    drain completes survive until the next drain; FAILED rows
 *    are never pruned);
 *  - UrlChapterFetcher keeps an image that is already on disk
 *    instead of re-fetching it.
 */
class DownloadOptimisationTest {

    @get:Rule val temp = TemporaryFolder()

    private val bookDao = FakeBookDao()
    private val chapterDao = FakeChapterDao()
    private val fakeQueueDao = FakeDownloadQueueDao()
    private val queueDao = CountingQueueDao(fakeQueueDao)
    private val sourceMapDao = FakeChapterSourceMapDao()
    private var now = 1_000L

    private class AllNetworks : NetworkGate {
        override fun onUnmeteredNetwork() = true
        override fun hasNetwork() = true
    }

    /** Counts queue-row writes while delegating everything else
     * to the shared fake (same observable semantics). */
    private class CountingQueueDao(
        private val inner: FakeDownloadQueueDao
    ) : DownloadQueueDao by inner {
        var updates = 0
        override suspend fun update(item: DownloadQueueItem) {
            updates++
            inner.update(item)
        }
    }

    private fun engine(
        transport: FakeTransport,
        resolver: (DownloadQueueItem) -> ChapterSourceResolver = { item ->
            RoomChapterSourceResolver(sourceMapDao) { UrlChapterFetcher(transport) }
        }
    ): DownloadEngine = DownloadEngine(
        queueDao = queueDao,
        chapterDao = chapterDao,
        bookDao = bookDao,
        sourceMapDao = sourceMapDao,
        storage = BookStorage(temp.newFolder("files-${System.nanoTime()}")),
        transport = transport,
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

    // -- progress coalescing (pure) --------------------------------

    @Test
    fun `sub-percent progress ticks produce only whole-percent writes`() {
        val coalescer = DownloadProgressCoalescer(clock = { 0L })
        var writes = 0
        var downloaded = 0L
        val total = 1_000_000L
        while (downloaded < 100_000L) {
            downloaded += 5_000L // 0.5% per tick
            if (coalescer.shouldPersist(downloaded, total)) writes++
        }
        // 20 raw ticks cross 11 whole percents (0%..10%).
        assertEquals(11, writes)
    }

    @Test
    fun `the final progress tick always persists`() {
        val coalescer = DownloadProgressCoalescer(clock = { 0L })
        assertTrue(coalescer.shouldPersist(5_000L, 1_000_000L))
        assertTrue(coalescer.shouldPersist(1_000_000L, 1_000_000L))
    }

    @Test
    fun `an unchanged percent persists again after the interval`() {
        var clockNow = 0L
        val coalescer = DownloadProgressCoalescer(clock = { clockNow })
        assertTrue(coalescer.shouldPersist(50_000L, 1_000_000L))
        assertFalse(coalescer.shouldPersist(50_500L, 1_000_000L))
        clockNow = 600L
        assertTrue(coalescer.shouldPersist(50_900L, 1_000_000L))
    }

    // -- attempt-write coalescing (engine) --------------------------

    @Test
    fun `attempt counters are written per round not per attempt`() = runTest {
        val bookId = bookDao.insert(Book(title = "T", addedAt = 1, updatedAt = 1))
        val chapterId = chapterDao.insert(
            Chapter(
                bookId = bookId, index = 0, title = "Chapter 1",
                remoteId = "https://a.test/1", updatedAt = 1
            )
        )
        fakeQueueDao.insert(
            DownloadQueueItem(
                bookId = bookId, chapterId = chapterId,
                kind = DownloadKind.CHAPTER, enqueuedAt = now, updatedAt = now
            )
        )
        // Three transiently-failing candidates: the engine burns
        // all 5 attempts across two rounds before failing.
        val failing = ChapterFetcher {
            FetchOutcome.Failure(AppError.ProviderDown("server"))
        }
        val candidates = (1..3).map { i ->
            ResolvedSource(
                candidate = ChapterCandidate(
                    label = "source $i", locator = "https://host$i.test/ch"
                ),
                fetcher = failing
            )
        }
        val eng = engine(FakeTransport { FakeTransport.ok("unused") }) { _ ->
            object : ChapterSourceResolver {
                override suspend fun candidatesFor(
                    chapter: Chapter
                ): List<ResolvedSource> = candidates
            }
        }
        eng.runQueue()
        val item = fakeQueueDao.items.value.single()
        assertEquals(QueueState.FAILED, item.state)
        // The final row is exact: all 5 attempts are recorded.
        assertEquals(DownloadEngine.MAX_ATTEMPTS, item.attempts)
        assertEquals(
            DownloadState.FAILED, chapterDao.getById(chapterId)!!.downloadState
        )
        // RUNNING (process) + one round-boundary persist + FAILED
        // (markFailed) = 3 writes. Per-attempt persistence would
        // have written 1 + 5 + 1 = 7.
        assertEquals(3, queueDao.updates)
    }

    // -- queue pruning ----------------------------------------------

    @Test
    fun `done rows are pruned at drain start and unfinished rows survive`() =
        runTest {
            val bookId = bookDao.insert(Book(title = "T", addedAt = 1, updatedAt = 1))
            val chapterId = chapterDao.insert(
                Chapter(
                    bookId = bookId, index = 0, title = "Chapter 1",
                    remoteId = "https://src.test/ch1", updatedAt = 1
                )
            )
            val staleDoneId = fakeQueueDao.insert(
                DownloadQueueItem(
                    bookId = bookId, chapterId = null,
                    kind = DownloadKind.CHAPTER, state = QueueState.DONE,
                    enqueuedAt = 1, updatedAt = 1
                )
            )
            val failedId = fakeQueueDao.insert(
                DownloadQueueItem(
                    bookId = bookId, chapterId = null,
                    kind = DownloadKind.CHAPTER, state = QueueState.FAILED,
                    enqueuedAt = 2, updatedAt = 2
                )
            )
            val queuedId = fakeQueueDao.insert(
                DownloadQueueItem(
                    bookId = bookId, chapterId = chapterId,
                    kind = DownloadKind.CHAPTER,
                    enqueuedAt = 3, updatedAt = 3
                )
            )
            val transport = FakeTransport {
                FakeTransport.ok("Plain text chapter body with enough words.")
            }
            engine(transport).runQueue()
            var ids = fakeQueueDao.items.value.map { it.id }
            assertFalse("stale DONE row must be pruned", staleDoneId in ids)
            assertTrue("FAILED row must survive", failedId in ids)
            // The row this drain completed survives THIS drain —
            // the Downloads Finished grouping still reads it.
            assertTrue("just-completed row must survive its drain", queuedId in ids)
            assertEquals(
                QueueState.DONE, fakeQueueDao.getById(queuedId)!!.state
            )
            // The NEXT drain's start prunes it.
            engine(transport).runQueue()
            ids = fakeQueueDao.items.value.map { it.id }
            assertFalse(queuedId in ids)
            assertEquals(listOf(failedId), ids)
        }

    // -- UrlChapterFetcher image mirror ------------------------------

    @Test
    fun `an image already on disk is kept without a fetch`() = runTest {
        val storage = BookStorage(temp.newFolder("img-skip"))
        val bookId = 7L
        File(storage.contentDir(bookId), "img-pic.png")
            .writeBytes(byteArrayOf(1, 2, 3))
        var imageRequests = 0
        val html = "<html><body><p>Text around the picture.</p>" +
            "<img src=\"/pic.png\"/></body></html>"
        val transport = FakeTransport { request ->
            if (request.url == "https://src.test/ch") {
                FakeTransport.ok(html)
            } else {
                imageRequests++
                AppResult.Ok(FakeTransport.response(404, "gone"))
            }
        }
        val fetcher = UrlChapterFetcher(transport, storage = storage)
        val outcome = fetcher.fetch(
            ChapterCandidate(locator = "https://src.test/ch", bookId = bookId)
        )
        assertTrue("fetch failed: $outcome", outcome is FetchOutcome.Success)
        assertEquals(0, imageRequests)
        val content = (outcome as FetchOutcome.Success).content
        assertEquals(
            "pic.png",
            content.blocks.filterIsInstance<ChapterBlock.Image>()
                .single().fileName
        )
    }
}
