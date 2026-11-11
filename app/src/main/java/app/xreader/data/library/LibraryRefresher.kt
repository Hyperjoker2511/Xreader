package app.xreader.data.library

import app.xreader.core.error.AppResult
import app.xreader.data.db.BookDao
import app.xreader.data.db.SourceType
import app.xreader.data.download.DownloadController
import app.xreader.data.extension.SourceBookIds
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
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
    // v1.5.1 (item 2, Library wedge exhibit): the per-book
    // refresh as a seam — production wires SourceBookSync, and
    // every call through it is time-bounded below.
    private val refreshBook: suspend (app.xreader.data.db.Book) -> AppResult<app.xreader.data.db.Book>,
    private val libraryPrefs: LibraryPrefs,
    private val downloadController: DownloadController,
    private val perBookTimeoutMs: Long = REFRESH_PER_BOOK_TIMEOUT_MS
) {
    data class Outcome(
        val booksChecked: Int = 0,
        val booksWithNew: Int = 0,
        val totalNew: Int = 0,
        val failures: Int = 0,
        /** v1.5.3 (finding #9, strand D): books whose refresh
         * hit the per-book bound and were SKIPPED — counted
         * separately from ordinary failures so the walk's
         * summary (and the worker's log) can say how many books
         * were passed over, not merely failed. */
        val skipped: Int = 0
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
        // v1.5.10 (H2a): hero-first ordering. Groups that
        // contain a hero book START first (the async list's
        // order is the semaphore's admission order), and
        // WITHIN each group hero books refresh before
        // non-heroes. Cross-group parallelism means this is
        // start-priority + per-group order, not a global
        // sequence — the honest bound, stated in the report.
        // No new scheduler: same groups, same semaphore.
        val groups = heroOrderedGroups(books)
        val gate = kotlinx.coroutines.sync.Semaphore(MAX_PARALLEL_SOURCES)
        // v1.5.34 (item 1a): the walk gets a HARD DEADLINE.
        // Before, refreshAll had per-book bounds but no overall
        // one: a library of slow sources could keep the job —
        // and the Library's refreshing state — alive without
        // limit. Each group now publishes its outcome into its
        // slot as it finishes; when the deadline passes the
        // walk returns the PARTIAL outcome (finished groups'
        // tallies, unfinished books counted as skipped) instead
        // of running on. Unfinished books are simply picked up
        // by the next refresh.
        val groupResults =
            java.util.concurrent.atomic.AtomicReferenceArray<Outcome>(
                groups.size
            )
        val finished = kotlinx.coroutines.withTimeoutOrNull(
            REFRESH_ALL_DEADLINE_MS
        ) {
            kotlinx.coroutines.coroutineScope {
                groups.mapIndexed { index, group ->
                    async {
                        gate.withPermit {
                            var groupOutcome = Outcome()
                            for (book in group) {
                                groupOutcome += refreshOne(
                                    book, autoDownload
                                )
                            }
                            groupOutcome.copy(
                                booksChecked = group.size
                            ).also { groupResults.set(index, it) }
                        }
                    }
                }.awaitAll()
            }
            true
        } ?: false
        val partial = (0 until groups.size).fold(Outcome()) { acc, i ->
            val one = groupResults.get(i)
            if (one != null) acc + one else acc
        }
        if (finished) return partial
        val unchecked = books.size - partial.booksChecked
        android.util.Log.w(
            "LibraryRefresher",
            "refreshAll hit its deadline: checked=" +
                "${partial.booksChecked} of ${books.size}; " +
                "$unchecked books deferred to the next refresh"
        )
        return partial.copy(skipped = partial.skipped + unchecked)
    }

    /**
     * v1.5.5 (§1): the background per-source cap. Every
     * background/library refresh of a book holds a permit
     * from its SOURCE's semaphore (2) for the refresh's
     * whole duration, so background work never runs more
     * than two books of the same source at once — across
     * DIFFERENT manga of that source (same-manga overlap
     * is the adapter registry's job, §1). This seam is the
     * fan-out layer: [refreshAll] groups by extension and
     * already walks one group serially, so within a single
     * refreshAll the cap is a guard that changes nothing —
     * its real work is coordinating OVERLAPPING refreshAll
     * runs (the Library button, the pull gesture and the
     * WorkManager worker can run concurrently against the
     * same source) and any future fan-out widening. The
     * map is companion-held so separate refresher instances
     * share the caps.
     *
     * Foreground reads are NEVER gated by this: the book
     * page's own refresh (SourceBookSync.refresh from UI
     * paths) does not pass through here, and chapter reads
     * (chapterContent / getPageList) are not refreshes at
     * all — a user reading never queues behind a sync.
     */
    private suspend fun refreshOne(
        book: app.xreader.data.db.Book,
        autoDownload: Boolean
    ): Outcome {
        val sourceKey = SourceBookIds.decode(book.remoteId)?.first
            ?: "extension:${book.extensionId}"
        val cap = BACKGROUND_SOURCE_CAPS.computeIfAbsent(sourceKey) {
            Semaphore(MAX_PARALLEL_BOOKS_PER_SOURCE)
        }
        return cap.withPermit { refreshOneCapped(book, autoDownload) }
    }

    /** One book's refresh + badge bookkeeping (the pre-v1.2.0
     * loop body, unchanged), returning its slice of the totals.
     * [booksChecked] is filled in by the caller, not here. */
    private suspend fun refreshOneCapped(
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
        // v1.5.1 (item 2, Library wedge exhibit 2026-11-02
        // 17:47): a per-book refresh used to run UNBOUNDED —
        // one book whose source never answered (an extension
        // call that never returned) held its whole source
        // group, refreshAll's awaitAll, and the Library's
        // update state hostage forever: the cover spinner
        // never terminated and no surface ever got its answer
        // back. Every per-book refresh is now hard-bounded; a
        // timeout is an ordinary per-book failure, the group
        // moves on to the next book, and the sweep always
        // terminates with an honest outcome.
        // v1.5.3 (finding #9, strand D): the bound tightens to
        // REFRESH_PER_BOOK_TIMEOUT_MS (aligned with the fetch
        // stack's STANDARD budget), and a timeout is now counted
        // as SKIPPED, distinct from an ordinary failure: the
        // block only marks itself completed when the refresh
        // actually returned, and a CancellationException is
        // rethrown rather than swallowed, so a cut-off refresh
        // can never masquerade as a completed one.
        var completed = false
        val result = kotlinx.coroutines.withTimeoutOrNull(perBookTimeoutMs) {
            val r = runCatching { refreshBook(book) }.getOrElse { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                null
            }
            completed = true
            r
        }
        if (!completed) return Outcome(skipped = 1)
        if (result is AppResult.Ok) {
            val fresh = bookDao.getById(book.id)
            val total = fresh?.totalChapters ?: book.totalChapters
            if (!book.inLibrary) {
                // v1.5.4 (item 19, finding #2): the badge
                // arithmetic below is for LIBRARY books only.
                // This walk also visits preview/history rows
                // (every book ever opened has a row), and a
                // non-library row baselined on its stored —
                // possibly partial, progressive-chunk — total
                // booked the whole remainder of a fat serial as
                // "new chapters" in one pass. A refreshed
                // non-library row instead leaves with its
                // baseline AT the post-refresh total and no
                // stored new count, so no phantom "new" exists
                // now or can surface if the book joins the
                // library later. (BookMeta.newChapters itself
                // is unchanged and still refuses a true first
                // sight; the phantom entered through the
                // stored-total baseline above, which a library
                // book keeps — its remainder genuinely is
                // unseen — and a non-library row no longer
                // converts into a badge.)
                val meta = bookMetaPrefs.get(book.id)
                bookMetaPrefs.set(
                    book.id,
                    meta.copy(
                        lastSeenChapterCount = total,
                        newChapterCount = 0
                    )
                )
                return Outcome()
            }
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

        /** v1.5.5 (§1): background refreshes of DIFFERENT
         * books of the SAME source allowed at once. */
        const val MAX_PARALLEL_BOOKS_PER_SOURCE = 2

        /** v1.5.5 (§1): the per-source background caps, by
         * source id (extension-row fallback) — see
         * [refreshOne]. */
        private val BACKGROUND_SOURCE_CAPS =
            ConcurrentHashMap<String, Semaphore>()

        /** v1.5.3 (finding #9, strand D): one book's refresh
         * may take at most this long before it counts as that
         * book SKIPPED and the sweep moves on (see refreshOne).
         * Aligned with the fetch stack's STANDARD budget
         * (75 s); a hang is not a slow book, and a slow book
         * is not a hang. */
        const val REFRESH_PER_BOOK_TIMEOUT_MS = 75_000L

        /** v1.5.34 (item 1a): the whole walk's hard deadline —
         * comfortably above one book's bound, far below the
         * unbounded job the owner saw re-appearing as phantom
         * refreshes. */
        const val REFRESH_ALL_DEADLINE_MS = 240_000L
    }
}

/**
 * v1.5.10 (H2a): the hero set, pinned to the BOOK ROW's own
 * fields (the refresher's only inputs): a book is a hero
 * when it carries recorded reading activity —
 * [app.xreader.data.db.Book.lastOpenedAt] is set (a book is
 * only ever opened by reading it; the Continue/Library-hero
 * surfaces key on the same fact) — and it is NOT completed
 * ([app.xreader.data.db.Book.finished] is the row's
 * completion flag). Most-recently-read first = lastOpenedAt
 * descending. Pure, so the warmth gate pins the table.
 */
internal fun isHeroBook(book: app.xreader.data.db.Book): Boolean =
    book.lastOpenedAt != null && !book.finished

/**
 * v1.5.10 (H2a): group [books] by extensionId (the
 * refresher's existing grouping), order hero groups first,
 * and order heroes first within each group (recent first;
 * non-heroes keep their incoming relative order). Group
 * order among hero groups — and among non-hero groups —
 * keeps the incoming first-appearance order, so the change
 * is purely additive priority, never a reshuffle.
 */
internal fun heroOrderedGroups(
    books: List<app.xreader.data.db.Book>
): List<List<app.xreader.data.db.Book>> {
    val groups = books.groupBy { it.extensionId }.values
    val orderedWithin = groups.map { group ->
        val heroes = group.filter { isHeroBook(it) }
            .sortedByDescending { it.lastOpenedAt ?: Long.MIN_VALUE }
        val rest = group.filter { !isHeroBook(it) }
        heroes + rest
    }
    val heroGroups = orderedWithin.filter { group ->
        group.any { isHeroBook(it) }
    }
    val otherGroups = orderedWithin.filter { group ->
        group.none { isHeroBook(it) }
    }
    return heroGroups + otherGroups
}

/** Slice-wise totals addition for the parallel refresher groups. */
private operator fun LibraryRefresher.Outcome.plus(
    other: LibraryRefresher.Outcome
): LibraryRefresher.Outcome = LibraryRefresher.Outcome(
    booksChecked = booksChecked + other.booksChecked,
    booksWithNew = booksWithNew + other.booksWithNew,
    totalNew = totalNew + other.totalNew,
    failures = failures + other.failures,
    skipped = skipped + other.skipped
)
