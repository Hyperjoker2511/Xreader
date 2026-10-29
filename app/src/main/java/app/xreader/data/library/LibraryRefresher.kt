package app.xreader.data.library

import app.xreader.core.error.AppResult
import app.xreader.data.db.BookDao
import app.xreader.data.db.SourceType
import app.xreader.data.download.DownloadController
import app.xreader.data.extension.SourceBookSync
import kotlinx.coroutines.flow.first

/**
 * The whole-library refresh (v1.0.2, R20): every eligible online
 * book's canonical chapter list is re-fetched, newly found chapters
 * are counted into each book's badge (BookMeta.newChapterCount —
 * the `+N` the user sees), non-user-locked metadata refreshes
 * through the sync path's provenance rules, and tracked books with
 * Wi-Fi auto-download enabled queue their new chapters.
 *
 * It NEVER downloads chapter bodies on its own — the only downloads
 * are the pre-existing tracked + Wi-Fi rule, exactly as the plan's
 * R20 fixes it. Local files are untouched (their content is never
 * uploaded or rewritten).
 *
 * Shared by the Library's button, its pull gesture, and the
 * WorkManager worker, so all three behave identically. Failures are
 * counted per source and left as source-health state (R10/R13) —
 * the refresher reports numbers, never a prose summary, and the
 * pull gesture shows nothing at all (Q2 addendum: silent).
 */
class LibraryRefresher(
    private val bookDao: BookDao,
    private val bookMetaPrefs: BookMetaPrefs,
    private val sourceBookSync: SourceBookSync,
    private val libraryPrefs: LibraryPrefs,
    private val downloadController: DownloadController
) {
    data class Outcome(
        val booksChecked: Int = 0,
        val booksWithNew: Int = 0,
        val totalNew: Int = 0,
        val failures: Int = 0
    )

    suspend fun refreshAll(): Outcome {
        val books = bookDao.allBooks()
            .filter {
                it.sourceType == SourceType.EXTENSION && it.mergedIntoId == null
            }
        var booksWithNew = 0
        var totalNew = 0
        var failures = 0
        val autoDownload = runCatching {
            libraryPrefs.state.first().autoDownloadTracked
        }.getOrDefault(false)
        for (book in books) {
            val before = bookMetaPrefs.get(book.id)
            val seen = if (before.lastSeenChapterCount > 0) {
                before.lastSeenChapterCount
            } else {
                book.totalChapters
            }
            if (before.lastSeenChapterCount == 0) {
                bookMetaPrefs.set(book.id, before.copy(lastSeenChapterCount = seen))
            }
            val result = runCatching { sourceBookSync.refresh(book) }
                .getOrElse { null }
            if (result is AppResult.Ok) {
                val fresh = bookDao.getById(book.id)
                val total = fresh?.totalChapters ?: book.totalChapters
                val newCount = BookMeta.newChapters(
                    before.copy(lastSeenChapterCount = seen), total
                )
                if (newCount > 0) {
                    booksWithNew++
                    totalNew += newCount
                    val meta = bookMetaPrefs.get(book.id)
                    bookMetaPrefs.set(book.id, meta.copy(newChapterCount = newCount))
                    if (meta.tracked && autoDownload) {
                        runCatching { downloadController.enqueuePrefetch(book.id) }
                    }
                }
            } else {
                failures++
            }
        }
        return Outcome(
            booksChecked = books.size,
            booksWithNew = booksWithNew,
            totalNew = totalNew,
            failures = failures
        )
    }
}
