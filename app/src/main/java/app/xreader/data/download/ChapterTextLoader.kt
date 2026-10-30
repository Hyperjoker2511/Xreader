package app.xreader.data.download

import app.xreader.core.error.AppError
import app.xreader.core.error.AppResult
import app.xreader.core.error.ErrorText
import app.xreader.data.db.Chapter
import app.xreader.data.db.ChapterDao
import app.xreader.data.db.ChapterSourceMapDao
import app.xreader.data.db.DownloadState
import app.xreader.data.extension.DiagnosticLog
import app.xreader.data.files.BookStorage
import app.xreader.domain.content.ChapterContent
import kotlinx.coroutines.CancellationException

/**
 * v1.0.6.2 (F9): THE chapter-text resolution — one path for the
 * reader and every AI feature, so a feature can never fail to see
 * a chapter the reader can display.
 *
 * Resolution order (the reader's own): the stored copy when the
 * chapter is on the phone; otherwise the live path — fetch the
 * chapter DIRECTLY through the same chapter-source machinery the
 * download engine drains with (the container's chapter-source
 * resolver: failover candidates in order, locks and login states
 * honoured identically), then persist the result as the chapter's
 * downloaded copy — write, READY state, verified-source mark and
 * the chapter-downloaded hook, in the engine's own order. That
 * persisted copy is the silent save F3 promises: the next read,
 * by any feature, is local.
 *
 * v1.0.6.2 (Gemini fix round, G1): the live path used to enqueue
 * through the download queue and poll the database for up to
 * three minutes. A paused queue (Downloads → Pause all) or a
 * busy one meant the reader waited out the whole poll and timed
 * out even though the source would have answered immediately.
 * The queue is for actual downloads only now; a live read never
 * rides it and never depends on its pause state. One attempt per
 * candidate, in order, no backoff — the reader offers Retry.
 */
class ChapterTextLoader(
    private val storage: BookStorage,
    private val resolver: ChapterSourceResolver,
    private val chapterDao: ChapterDao,
    private val sourceMapDao: ChapterSourceMapDao? = null,
    /** The engine's chapter-downloaded hook (the AI summary/lore
     * pipeline), fired after a live fetch persists — best effort,
     * exactly as the engine fires it. */
    private val onChapterDownloaded: suspend (bookId: Long, chapterId: Long) -> Unit =
        { _, _ -> }
) {

    sealed interface Outcome {
        /** The chapter's stored copy exists; [chapter] is the
         * refreshed row pointing at it. */
        data class Stored(val chapter: Chapter) : Outcome

        /** The live fetch failed; [detail] is the fetch's own
         * plain reason (e.g. it names a paid or locked chapter). */
        data class Failed(val detail: String) : Outcome

        /** Kept for API compatibility with the queue-polling era —
         * callers switch exhaustively over this type. The direct
         * fetch always resolves to Stored or Failed, so this is
         * no longer produced. */
        data object TimedOut : Outcome
    }

    /** Ensures the chapter's text is stored on this device. */
    suspend fun ensureStored(bookId: Long, chapter: Chapter): Outcome {
        // Refresh the row first (one cheap read): a copy may have
        // landed since the caller read it, and a stale row must
        // not trigger a second fetch of a stored chapter.
        val current = chapterDao.getById(chapter.id) ?: chapter
        if (current.contentPath != null) return Outcome.Stored(current)
        chapterDao.setDownloadState(current.id, DownloadState.DOWNLOADING)
        // The engine's own no-source wording (its processChapterItem
        // empty-candidates case): a chapter nothing can serve
        // fails with exactly this reason.
        var lastError: AppError =
            AppError.BadFile("no source is recorded for this chapter")
        for (source in resolver.candidatesFor(current)) {
            when (val fetched = source.fetcher.fetch(source.candidate)) {
                is FetchOutcome.Success -> {
                    val saved = storage.writeChapterContent(
                        bookId, current.id, fetched.content
                    )
                    if (saved is AppResult.Err) {
                        // A storage failure won't be fixed by
                        // another source — fail, as the engine does.
                        lastError = saved.error
                        break
                    }
                    val relativePath = (saved as AppResult.Ok).value
                    chapterDao.setContent(
                        current.id, relativePath, fetched.content.wordCount,
                        DownloadState.READY
                    )
                    try {
                        onChapterDownloaded(bookId, current.id)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (ignored: Exception) {
                        // Best effort — the engine's hook discipline.
                    }
                    if (source.candidate.mapId > 0) {
                        runCatching {
                            sourceMapDao?.markVerified(source.candidate.mapId)
                        }
                    }
                    val fresh = chapterDao.getById(current.id) ?: current
                    return Outcome.Stored(fresh)
                }
                is FetchOutcome.Failure -> lastError = fetched.error
            }
        }
        // The engine's failure bookkeeping, mirrored: the chapter
        // carries FAILED and the reason joins the session's
        // problem history for the Copy-details flow.
        chapterDao.setDownloadState(current.id, DownloadState.FAILED)
        DiagnosticLog.record(
            "download", ErrorText.displayText(lastError),
            "chapter ${current.id} · book $bookId"
        )
        return Outcome.Failed(ErrorText.displayText(lastError))
    }

    /**
     * The chapter's content for a feature: the stored copy,
     * fetched live first when it isn't on the phone yet. Null
     * when the text honestly isn't available (a failed fetch, an
     * unreadable stored file).
     */
    suspend fun loadContent(bookId: Long, chapter: Chapter): ChapterContent? {
        val stored = when (val outcome = ensureStored(bookId, chapter)) {
            is Outcome.Stored -> outcome.chapter
            else -> return null
        }
        return storage.readChapterContent(bookId, stored.id).getOrNull()
    }

    /** The stored copy only — never fetches. */
    suspend fun storedContent(bookId: Long, chapterId: Long): ChapterContent? =
        storage.readChapterContent(bookId, chapterId).getOrNull()
}
