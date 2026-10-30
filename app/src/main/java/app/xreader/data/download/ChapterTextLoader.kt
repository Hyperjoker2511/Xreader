package app.xreader.data.download

import app.xreader.data.db.Chapter
import app.xreader.data.db.ChapterDao
import app.xreader.data.db.DownloadQueueDao
import app.xreader.data.db.QueueState
import app.xreader.data.files.BookStorage
import app.xreader.domain.content.ChapterContent
import kotlinx.coroutines.delay

/**
 * v1.0.6.2 (F9): THE chapter-text resolution — one path for the
 * reader and every AI feature, so a feature can never fail to see
 * a chapter the reader can display.
 *
 * Resolution order (the reader's own): the stored copy when the
 * chapter is on the phone; otherwise the live path — enqueue
 * through the same download queue downloads use and wait for the
 * stored copy to land (same queue, same chapter identity, same
 * file). Nothing here invents a second store or a second fetch.
 */
class ChapterTextLoader(
    private val storage: BookStorage,
    private val downloadController: DownloadController,
    private val chapterDao: ChapterDao,
    private val queueDao: DownloadQueueDao
) {

    sealed interface Outcome {
        /** The chapter's stored copy exists; [chapter] is the
         * refreshed row pointing at it. */
        data class Stored(val chapter: Chapter) : Outcome

        /** The live fetch failed; [detail] is the queue's own
         * plain reason (e.g. it names a paid or locked chapter). */
        data class Failed(val detail: String) : Outcome

        /** The fetch is still travelling after the full wait. */
        data object TimedOut : Outcome
    }

    /** Ensures the chapter's text is stored on this device. */
    suspend fun ensureStored(bookId: Long, chapter: Chapter): Outcome {
        if (chapter.contentPath != null) return Outcome.Stored(chapter)
        val itemId = downloadController.enqueueChapter(bookId, chapter.id)
        repeat(POLLS) {
            delay(POLL_MS)
            val fresh = chapterDao.getById(chapter.id)
            if (fresh != null && fresh.contentPath != null) {
                return Outcome.Stored(fresh)
            }
            val item = queueDao.getById(itemId)
            if (item?.state == QueueState.FAILED) {
                return Outcome.Failed(item.lastErrorDetail.orEmpty())
            }
        }
        return Outcome.TimedOut
    }

    /**
     * The chapter's content for a feature: the stored copy,
     * fetched live first when it isn't on the phone yet. Null
     * when the text honestly isn't available (a failed or
     * still-travelling fetch, an unreadable stored file).
     */
    suspend fun loadContent(bookId: Long, chapter: Chapter): ChapterContent? {
        val stored = when (val outcome = ensureStored(bookId, chapter)) {
            is Outcome.Stored -> outcome.chapter
            else -> return null
        }
        return storage.readChapterContent(bookId, stored.id).getOrNull()
    }

    /** The stored copy only — never fetches. */
    fun storedContent(bookId: Long, chapterId: Long): ChapterContent? =
        storage.readChapterContent(bookId, chapterId).getOrNull()

    companion object {
        /** The reader's own wait: 90 polls × 2 s. */
        const val POLLS = 90
        const val POLL_MS = 2_000L
    }
}
