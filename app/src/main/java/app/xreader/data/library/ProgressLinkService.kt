package app.xreader.data.library

import app.xreader.data.db.Book
import app.xreader.data.db.BookDao
import app.xreader.data.db.ChapterDao
import app.xreader.data.db.ReadingProgress
import app.xreader.data.db.ReadingProgressDao
import app.xreader.data.db.SourceType
import app.xreader.data.prefs.ProgressLinkPrefs
import kotlinx.coroutines.flow.first

/**
 * Progress-linking service (Cycle 1, F3): the DAO-backed
 * half of [ProgressLinker]. All decisions come from the pure
 * core; this class only gathers the library, asks, and — for
 * an [Outcome.Applied] — writes through the EXISTING resume
 * path: the book's own `reading_progress` row (the one the
 * book page's Continue pill, the Library shelf and History
 * all read) plus the chapters' read flags. No new tables,
 * no migration: links are derived from the library by the
 * matcher on every call, and the only persisted fact is the
 * user's refusals ([ProgressLinkPrefs]).
 */
class ProgressLinkService(
    private val bookDao: BookDao,
    private val chapterDao: ChapterDao,
    private val progressDao: ReadingProgressDao,
    private val prefs: ProgressLinkPrefs,
    private val inTransaction: suspend (suspend () -> Unit) -> Unit =
        { block -> block() }
) {

    sealed interface Outcome {
        /** Nothing to change: no linked copies, no sibling
         * ahead, or this book isn't a library book. */
        data object NoChange : Outcome

        /** A sibling's position was carried into this book.
         * [sourceBook] is the copy it came from (for the
         * "carried over from …" line). */
        data class Applied(val plan: ResumePlan, val sourceBook: Book) : Outcome

        /** The furthest sibling sits beyond this copy's
         * loaded chapter list, and the list is a partial
         * (progressive) one: grow it toward [targetNumber]
         * first, then reconcile again. Landing on the last
         * loaded chapter instead would strand the reader far
         * behind the sibling for no reason. */
        data class NeedsGrowth(val targetNumber: Float) : Outcome
    }

    /** The library books whose progress this book shares:
     * same-story copies in the Library, refusals removed. */
    suspend fun linkedCopies(bookId: Long): List<Book> {
        val book = bookDao.getById(bookId) ?: return emptyList()
        if (!book.inLibrary || book.mergedIntoId != null) return emptyList()
        val library = libraryBooks()
        val refused = prefs.refusedPairs.first()
        return ProgressLinker.siblingsOf(book, library, refused)
    }

    /**
     * Compute this book's effective resume position from its
     * link group and apply it if a sibling is ahead. Safe to
     * call on every book-page / reader open: it converges
     * (once applied, this book's own position equals the
     * group's furthest, so the next call is a no-op).
     */
    suspend fun reconcile(bookId: Long): Outcome {
        val book = bookDao.getById(bookId) ?: return Outcome.NoChange
        if (!book.inLibrary || book.mergedIntoId != null) {
            return Outcome.NoChange
        }
        val library = libraryBooks()
        val refused = prefs.refusedPairs.first()
        val siblings = ProgressLinker.siblingsOf(book, library, refused)
        if (siblings.isEmpty()) return Outcome.NoChange

        val ownChapters = chapterDao.forBook(bookId)
        if (ownChapters.isEmpty()) return Outcome.NoChange
        val ownPosition = ProgressLinker.positionOf(
            ownChapters, progressDao.getForBook(bookId)
        )

        // Every sibling's position, with the timestamp the
        // position was earned at (the progress row's own —
        // the carried row keeps the position's true age, so
        // merely OPENING a copy never makes it look freshly
        // read in History).
        data class SiblingPosition(
            val book: Book,
            val position: LinkPosition,
            val updatedAt: Long
        )

        val siblingPositions = ArrayList<SiblingPosition>()
        for (sibling in siblings) {
            val chapters = chapterDao.forBook(sibling.id)
            if (chapters.isEmpty()) continue
            val progress = progressDao.getForBook(sibling.id)
            val position = ProgressLinker.positionOf(chapters, progress)
                ?: continue
            siblingPositions += SiblingPosition(
                book = sibling,
                position = position,
                updatedAt = progress?.updatedAt
                    ?: sibling.lastOpenedAt
                    ?: System.currentTimeMillis()
            )
        }
        if (siblingPositions.isEmpty()) return Outcome.NoChange

        val winner = siblingPositions.maxWithOrNull(
            compareBy<SiblingPosition> { it.position.scalar }
                .thenBy { it.book.lastOpenedAt ?: 0L }
        ) ?: return Outcome.NoChange
        if (ownPosition != null &&
            winner.position.scalar <= ownPosition.scalar + ProgressLinker.EPS
        ) {
            return Outcome.NoChange
        }

        // A progressive (partial) chapter list that doesn't
        // reach the sibling's number yet: grow first, plan
        // after. A COMPLETE list that falls short is the
        // site's real shape — the planner's nearest match +
        // notice handles it.
        if (book.sourceType == SourceType.EXTENSION &&
            !book.chapterListComplete
        ) {
            val numbers = ProgressLinker.effectiveNumbers(ownChapters)
            val maxLoaded = numbers.values.maxOrNull() ?: 0f
            if (winner.position.number > maxLoaded + ProgressLinker.EPS) {
                return Outcome.NeedsGrowth(winner.position.number)
            }
        }

        // The service picks the winner (its timestamp and
        // identity feed the outcome), so the planner is
        // asked to map exactly that position — the two can
        // never disagree about whose progress landed.
        val plan = ProgressLinker.plan(
            targetChapters = ownChapters,
            ownPosition = ownPosition,
            siblingPositions = mapOf(winner.book.id to winner.position)
        ) ?: return Outcome.NoChange

        inTransaction {
            for (chapterId in plan.markReadIds) {
                chapterDao.setRead(chapterId, true)
            }
            progressDao.upsert(
                ReadingProgress(
                    bookId = bookId,
                    chapterId = plan.targetChapter.id,
                    chapterIndex = plan.targetChapter.index,
                    charOffset = 0,
                    fraction = plan.fraction,
                    scrollFraction = plan.scrollFraction,
                    updatedAt = winner.updatedAt
                )
            )
        }
        return Outcome.Applied(plan, winner.book)
    }

    /** "Not the same book": record the refusal. Sharing
     * between the two entries stops immediately — both
     * directions, from the very next reconcile or listing —
     * because refusals are consulted at use time, and the
     * matcher can never re-link a refused pair. */
    suspend fun unlink(bookId: Long, otherBookId: Long) {
        prefs.addRefusal(bookId, otherBookId)
    }

    /** The linkable library: members only, never merged-away
     * rows (they're hidden behind their survivor). */
    private suspend fun libraryBooks(): List<Book> =
        bookDao.allBooks().filter { it.inLibrary && it.mergedIntoId == null }
}
