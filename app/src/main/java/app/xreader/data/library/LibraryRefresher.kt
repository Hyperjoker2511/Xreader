package app.xreader.data.library

import app.xreader.core.error.AppResult
import app.xreader.data.db.BookDao
import app.xreader.data.db.SourceType
import app.xreader.data.download.DownloadController
import app.xreader.data.extension.SourceBookSync
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withPermit

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
        val autoDownload = runCatching {
            libraryPrefs.state.first().autoDownloadTracked
        }.getOrDefault(false)
        // v1.2.0 (audit finding 6 / D3, refresher side): the walk
        // is no longer strictly one-book-at-a-time across the
        // WHOLE library. Books are grouped by their source
        // (extensionId): groups run in parallel under a small
        // bounded semaphore (3), books INSIDE one source stay
        // serial. Why this shape is safe to overlap:
        //  - per-book state is disjoint: each refresh touches its
        //    own book/chapter rows (Room serialises writers) and
        //    its own BookMeta entry (DataStore edits serialise);
        //    the refresher's own counters are per-group locals,
        //    summed at the end — no shared mutable state here;
        //  - a source's session/cookies are per-source state, so
        //    two books of the SAME source could interleave
        //    session use (and rendered sources share one WebView
        //    fetcher) — keeping one source serial removes that
        //    whole class of overlap, while different sources use
        //    disjoint sessions (the WebView fetcher additionally
        //    mutex-serialises renders internally);
        //  - SourceBookSync's one shared lock (the progressive
        //    walk mutex) is only on the walk path refresh()
        //    doesn't take, and its content caches are
        //    synchronized.
        // The unbounded retry loop the audit flagged lives inside
        // SourceBookSync, not here, and is another change's scope.
        val groups = books.groupBy { it.extensionId }
        val gate = kotlinx.coroutines.sync.Semaphore(MAX_PARALLEL_SOURCES)
        val outcomes = kotlinx.coroutines.coroutineScope {
            groups.values.map { group ->
                async {
                    gate.withPermit {
                        var groupOutcome = Outcome()
                        for (book in group) {
                            groupOutcome += refreshOne(book, autoDownload)
                        }
                        groupOutcome.copy(booksChecked = group.size)
                    }
                }
            }.awaitAll()
        }
        return outcomes.fold(Outcome()) { acc, one -> acc + one }
    }

    /** One book's refresh + badge bookkeeping (the pre-v1.2.0
     * loop body, unchanged), returning its slice of the totals.
     * [booksChecked] is filled in by the caller, not here. */
    private suspend fun refreshOne(
        book: app.xreader.data.db.Book,
        autoDownload: Boolean
    ): Outcome {
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
                val meta = bookMetaPrefs.get(book.id)
                bookMetaPrefs.set(book.id, meta.copy(newChapterCount = newCount))
                if (meta.tracked && autoDownload) {
                    runCatching { downloadController.enqueuePrefetch(book.id) }
                }
                return Outcome(booksWithNew = 1, totalNew = newCount)
            }
            return Outcome()
        }
        return Outcome(failures = 1)
    }

    companion object {
        /** Sources refreshed in parallel (audit finding 6's
         * "small bounded concurrency (2–3)"). */
        const val MAX_PARALLEL_SOURCES = 3
    }
}

/** Slice-wise totals addition for the parallel refresher groups. */
private operator fun LibraryRefresher.Outcome.plus(
    other: LibraryRefresher.Outcome
): LibraryRefresher.Outcome = LibraryRefresher.Outcome(
    booksChecked = booksChecked + other.booksChecked,
    booksWithNew = booksWithNew + other.booksWithNew,
    totalNew = totalNew + other.totalNew,
    failures = failures + other.failures
)
