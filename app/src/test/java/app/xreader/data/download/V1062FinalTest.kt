package app.xreader.data.download

import app.xreader.data.FakeBookDao
import app.xreader.data.FakeChapterDao
import app.xreader.data.FakeDownloadQueueDao
import app.xreader.data.db.DownloadQueueItem
import app.xreader.data.db.DownloadKind
import app.xreader.data.db.QueueState
import app.xreader.data.extension.ProblemDetails
import app.xreader.data.extension.SourceHealthRecord
import app.xreader.data.extension.SourceHealthState
import app.xreader.data.files.BookStorage
import app.xreader.ui.downloads.formatBytes
import app.xreader.ui.theme.AppColourThemes
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v1.0.6.2 tail-end pins: D2 (Pause all / Resume all + the
 * storage line), SO1 (plain health words), M1 (Copy app
 * details), F8b (the three-theme picker).
 */
class V1062FinalTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun item(state: QueueState, chapterId: Long) = DownloadQueueItem(
        bookId = 1L,
        chapterId = chapterId,
        kind = DownloadKind.CHAPTER,
        state = state,
        enqueuedAt = 0L,
        updatedAt = 0L
    )

    @Test fun pauseAllAndResumeAllMoveTheWholeQueue() = runBlocking {
        val queueDao = FakeDownloadQueueDao()
        val controller = DownloadController(
            queueDao = queueDao,
            chapterDao = FakeChapterDao(),
            bookDao = FakeBookDao(),
            storage = BookStorage(tmp.newFolder()),
            enqueuer = WorkEnqueuer {},
            clock = { 5L }
        )
        val running = queueDao.insert(item(QueueState.RUNNING, 11L))
        val queued = queueDao.insert(item(QueueState.QUEUED, 12L))
        val failed = queueDao.insert(item(QueueState.FAILED, 13L))

        controller.pauseAll()
        var byId = queueDao.items.value.associateBy { it.id }
        assertEquals(QueueState.PAUSED, byId[running]?.state)
        assertEquals(QueueState.PAUSED, byId[queued]?.state)
        // A failed item is not "paused" — it waits for a resume.
        assertEquals(QueueState.FAILED, byId[failed]?.state)

        controller.resumeAll()
        byId = queueDao.items.value.associateBy { it.id }
        assertEquals(QueueState.QUEUED, byId[running]?.state)
        assertEquals(QueueState.QUEUED, byId[queued]?.state)
        assertEquals(QueueState.QUEUED, byId[failed]?.state)
    }

    @Test fun storageLineSizesArePlain() {
        assertEquals("0 B", formatBytes(0L))
        assertEquals("812 B", formatBytes(812L))
        assertEquals("45 KB", formatBytes(45_200L))
        assertEquals("12.3 MB", formatBytes(12_300_000L))
        assertEquals("1.2 GB", formatBytes(1_200_000_000L))
    }

    @Test fun healthWordsMatchTheDetailsCardVerdicts() {
        assertEquals(
            "Working",
            SourceHealthRecord(SourceHealthState.WORKING).shortStateWord()
        )
        assertEquals(
            "Working with limits",
            SourceHealthRecord(SourceHealthState.WORKING_WITH_LIMITS).shortStateWord()
        )
        assertEquals(
            "Needs setup",
            SourceHealthRecord(SourceHealthState.NEEDS_SETUP).shortStateWord()
        )
        assertEquals(
            "Not working",
            SourceHealthRecord(SourceHealthState.BROKEN).shortStateWord()
        )
        assertEquals(
            "Not checked yet",
            SourceHealthRecord(SourceHealthState.UNKNOWN).shortStateWord()
        )
    }

    @Test fun appDetailsPasteCarriesVersionSourcesAndProblems() {
        val text = ProblemDetails.formatAppDetails(
            appVersion = "1.0.6.2 (15)",
            sourceLines = listOf("Novelight — Working", "Chikari — Needs setup")
        )
        assertTrue(text.contains("Xreader app details"))
        assertTrue(text.contains("App version: 1.0.6.2 (15)"))
        assertTrue(text.contains("- Novelight — Working"))
        assertTrue(text.contains("- Chikari — Needs setup"))
        val empty = ProblemDetails.formatAppDetails("1.0.6.2 (15)", emptyList())
        assertTrue(empty.contains("- none added yet"))
        assertFalse(empty.contains("Recent problems"))
    }

    @Test fun theColourThemePickerIsExactlyThreeVioletFamilyThemes() {
        assertEquals(
            listOf("Electric violet", "Deep indigo", "Magenta violet"),
            AppColourThemes.map { it.name }
        )
    }
}
