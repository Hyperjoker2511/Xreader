package app.xreader.data.ai

import app.xreader.core.error.AppResult
import app.xreader.data.db.BookDao
import app.xreader.data.db.ChapterDao
import app.xreader.data.files.BookStorage
import app.xreader.data.prefs.AiFeature
import app.xreader.data.prefs.AiSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * LoreKeeper's incremental triggers (PLAN §7: extraction happens as
 * chapters are read and downloaded). Both entry points are
 * fire-and-forget and best-effort by design: they do nothing without
 * a configured AI engine, nothing when the feature is off, and
 * nothing for a chapter whose current text was already extracted
 * (the keeper's marker check) — so the triggers can fire liberally
 * without ever re-burning quota or disturbing reading.
 */
class AiChapterHooks(
    private val scope: CoroutineScope,
    private val loreKeeper: LoreKeeper,
    private val bookDao: BookDao,
    private val chapterDao: ChapterDao,
    private val storage: BookStorage,
    private val client: AiClient,
    private val settingsProvider: suspend () -> AiSettings
) {

    /** A chapter finished downloading (DownloadEngine hook). */
    fun onChapterDownloaded(bookId: Long, chapterId: Long) = enqueue(bookId, chapterId)

    /** A chapter was finished in the reader (ReaderViewModel hook). */
    fun onChapterRead(bookId: Long, chapterId: Long) = enqueue(bookId, chapterId)

    private fun enqueue(bookId: Long, chapterId: Long) {
        scope.launch {
            runCatching {
                if (!settingsProvider().enabled(AiFeature.LORE_KEEPER)) return@launch
                if (!client.hasKey()) return@launch
                val book = bookDao.getById(bookId) ?: return@launch
                val chapter = chapterDao.getById(chapterId) ?: return@launch
                if (chapter.contentPath == null) return@launch
                val content = storage.readChapterContent(bookId, chapterId)
                    .getOrNull() ?: return@launch
                val text = content.plainText()
                if (text.length < MIN_TEXT_CHARS) return@launch
                if (loreKeeper.wasExtracted(bookId, chapter, text)) return@launch
                loreKeeper.extractForChapter(book, chapter, text)
            }
        }
    }

    companion object {
        /** Chapters shorter than this rarely name anyone worth keeping. */
        const val MIN_TEXT_CHARS = 400
    }
}
