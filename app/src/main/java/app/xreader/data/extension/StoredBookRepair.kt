package app.xreader.data.extension

import app.xreader.core.error.AppResult
import app.xreader.data.db.Book
import app.xreader.data.db.BookDao
import app.xreader.data.db.ChapterDao
import app.xreader.data.db.SourceType
import app.xreader.data.files.BookStorage
import app.xreader.data.prefs.SourcePrefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * v1.3.3 (V132-CHECK 11/14, scope S6): STORED-BOOK REPAIR.
 *
 * v1.3.2's canonical merge healed every FRESH sync — but a
 * book already sitting in the Library kept its old rows
 * forever, because opening it never touches the network
 * (the v1.0.8 rule). His Shadow Slave stayed at the
 * v1.3.1-polluted 3,253 rows against the source's own
 * 3,229, its phantom Chapter 1 row kept failing to open,
 * and its missing cover never healed — nothing in the app
 * ever re-checked a stored book against its source.
 *
 * The repair is a full canonical re-sync (the same
 * [SourceBookSync.refresh] a manual Refresh runs): the
 * fetched list replaces the stored rows through the
 * identity reconciler, read/download state rides across by
 * identity, and the details half restores a missing cover
 * from the source's current record. It runs:
 *  - once per app version over the whole Library (the
 *    upgrade sweep — a polluted store's counts agree with
 *    THEMSELVES, so only a version-gated full pass catches
 *    it), and
 *  - whenever a stored book is opened showing a symptom
 *    the policy below names (count disagreement with the
 *    book's own stated total, a missing cover, a cover
 *    whose file is gone).
 * All best-effort and background: a failed repair changes
 * nothing and the next open retries.
 */
object StoredRepairPolicy {

    /** The symptoms a repair heals, as a pure decision over
     * the stored row and its actual chapter count. */
    fun needsRepair(book: Book, storedChapterCount: Int): Boolean {
        if (book.extensionId == null) return false
        if (book.sourceType != SourceType.EXTENSION) return false
        // The stored rows disagree with the total the book
        // itself records — a partial or polluted store.
        if (storedChapterCount != book.totalChapters) return true
        // No cover on file — the details half of a re-sync
        // restores the real cover (item 14); the letter tile
        // is a fallback, never the steady state.
        if (book.coverPath == null) return true
        return false
    }
}

class StoredBookRepair(
    private val bookDao: BookDao,
    private val chapterDao: ChapterDao,
    private val sourceBookSync: SourceBookSync,
    private val sourcePrefs: SourcePrefs,
    private val storage: BookStorage,
    private val hasNetwork: () -> Boolean = { true }
) {

    private val inFlight: MutableSet<Long> =
        Collections.newSetFromMap(ConcurrentHashMap())

    /** Repair one book now: the full canonical re-sync.
     * True when the re-sync landed. Never throws for a bad
     * source — one book's failure is its own. */
    suspend fun repairBook(book: Book): Boolean {
        if (!inFlight.add(book.id)) return false
        return try {
            val fresh = bookDao.getById(book.id) ?: return false
            if (fresh.extensionId == null) return false
            sourceBookSync.refresh(fresh) is AppResult.Ok
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            false
        } finally {
            inFlight.remove(book.id)
        }
    }

    /** The open-book trigger: repair only when a symptom
     * shows (the pure policy, plus a cover whose file has
     * gone from disk). */
    suspend fun repairIfNeeded(book: Book): Boolean {
        val storedCount = chapterDao.forBook(book.id).size
        val coverFileGone = book.coverPath?.let { path ->
            !runCatching { storage.resolve(path).exists() }
                .getOrDefault(true)
        } ?: false
        if (!StoredRepairPolicy.needsRepair(book, storedCount) &&
            !coverFileGone
        ) {
            return false
        }
        return repairBook(book)
    }

    /**
     * The upgrade sweep: once per app version, re-sync every
     * Library source book. Returns false (flag unburned, a
     * later launch retries) when the sweep already ran for
     * this version or there is no network — a cancelled run
     * likewise leaves the flag unburned.
     */
    suspend fun runUpgradeSweep(currentVersionCode: Int): Boolean {
        if (sourcePrefs.storedRepairDoneForCode.first() >=
            currentVersionCode
        ) {
            return false
        }
        if (!hasNetwork()) return false
        val books = bookDao.observeAllBooks().first()
            .filter {
                it.inLibrary && it.extensionId != null &&
                    it.mergedIntoId == null &&
                    it.sourceType == SourceType.EXTENSION
            }
        for (book in books) {
            repairBook(book)
        }
        sourcePrefs.setStoredRepairDoneForCode(currentVersionCode)
        return true
    }
}
