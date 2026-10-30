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
import app.xreader.data.db.QueueState
import app.xreader.data.extension.DiagnosticLog
import app.xreader.data.files.BookStorage
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v1.0.6.2 (F3): a chapter download that fails leaves its
 * reason in the session's diagnostic log, so a fault report
 * can say WHY the chapter never arrived (the draw-check's
 * silent download failures were undiagnosable without it).
 */
class V1062DownloadLogTest {

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

    @Test
    fun `a failed chapter download is written to the diagnostic log`() =
        runTest {
            DiagnosticLog.clear()
            val bookId = bookDao.insert(
                Book(title = "T", addedAt = 1, updatedAt = 1)
            )
            val chapterId = chapterDao.insert(
                Chapter(
                    bookId = bookId, index = 0, title = "Chapter 1",
                    remoteId = "https://src.test/gone", updatedAt = 1
                )
            )
            queueDao.insert(
                DownloadQueueItem(
                    bookId = bookId, chapterId = chapterId,
                    kind = DownloadKind.CHAPTER,
                    enqueuedAt = 1, updatedAt = 1
                )
            )
            val transport = FakeTransport {
                AppResult.Ok(FakeTransport.response(404, "nope"))
            }
            val engine = DownloadEngine(
                queueDao = queueDao,
                chapterDao = chapterDao,
                bookDao = bookDao,
                sourceMapDao = sourceMapDao,
                storage = BookStorage(temp.newFolder("files")),
                transport = transport,
                resolverForItem = {
                    RoomChapterSourceResolver(sourceMapDao) {
                        UrlChapterFetcher(transport)
                    }
                },
                networkGate = AllNetworks(),
                settingsProvider = {
                    DownloadSettings(
                        wifiOnlyPrefetch = true, autoPrefetch = true
                    )
                },
                fileDownloaded = { _, _ -> null },
                delayer = { },
                jitter = { 1.0 },
                clock = { 1_000L }
            )
            engine.runQueue()
            assertEquals(
                QueueState.FAILED, queueDao.items.value.single().state
            )
            val entries = DiagnosticLog.entries()
            assertTrue(
                "expected a download entry in $entries",
                entries.any { it.tag == "download" }
            )
            assertTrue(
                entries.any {
                    it.tag == "download" &&
                        it.detail.contains("chapter $chapterId")
                }
            )
        }
}
