package app.xreader.data.extension.player

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * v1.5.17 (F1 — heal-on-open for the convicted manga memo
 * drop, V1513-LANE-A A-1): some Keiyoushi extensions mint
 * private per-chapter state (`SChapter.memo`, e.g. Asura's
 * `mangaSlug`) ONLY at chapter-list fetch time and throw
 * `Exception("Refresh Chapter List")` from `getPageList`
 * before any network call when it is absent. Chapter rows
 * stored before schema v12 (the memo column's birth, a bare
 * `ADD COLUMN` in MIGRATION_11_12) carry `memo = NULL`
 * forever unless a chapter-list refresh re-mints the state
 * through the sync apply path — and neither reader road
 * refreshed on open, so every legacy shelf book failed
 * deterministically at page open while its lists worked.
 * (Device-proven: the owner refreshed one affected book's
 * list manually and its chapters opened and loaded.)
 *
 * This object is the ONE-SHOT guard both reader roads share
 * (KomikkuReaderViewModel's HttpSource binding road and
 * ExtensionChapterFetcher's pipeline road): when a chapter's
 * EFFECTIVE memo — the memo [MihonSourceAdapter] would hand
 * the extension after its retained-snapshot restore and its
 * cold row rebuild, see [MihonSourceAdapter
 * .effectiveMemoMissing] — is missing/empty, the road runs
 * ONE chapter-list refresh for the book (the caller's
 * `SourceBookSync.refresh`, whose apply path persists the
 * healed memo into BOTH the adapter's retained snapshot and
 * the chapter rows) and then proceeds to `getPageList`
 * regardless of the outcome: a healed memo saves the open, a
 * still-missing memo lets the extension's real exception
 * surface (its words now reach the error Details — F1 item
 * 3), never a retry loop.
 *
 * Two bounds hold the heal:
 *  - PER (source, book) PER PROCESS: the attempt is claimed
 *    BEFORE the refresh runs, so a source that never mints
 *    memos at all (the MangaFire shape — its `getPageList`
 *    needs only the chapter url) pays at most one wasted
 *    refresh per book per process and opens normally every
 *    time after; a memo-minting source heals on the first
 *    attempt and its predicate reads false from then on. A
 *    new process may try once more — by then the persisted
 *    row usually carries the memo and the predicate stops
 *    the attempt before it starts.
 *  - SINGLE-FLIGHT per book: opens of the same book
 *    serialise on the key's [Mutex], so concurrent chapter
 *    opens never stampede refreshes — a waiter enters after
 *    the in-flight refresh lands, sees the claim spent, and
 *    proceeds straight to its page fetch with the healed
 *    snapshot/rows already in place.
 */
object MangaMemoHeal {

    /** (source namespaced id | book key) pairs whose one
     * heal attempt this process is spent. The book key is the
     * stored Book's Room id as text — the same stable key the
     * sync layer keys its single-flight registry by. */
    private val attemptedKeys = ConcurrentHashMap.newKeySet<String>()

    /** One flight lock per attempt key — see the doc above. */
    private val flights = ConcurrentHashMap<String, Mutex>()

    /** The attempt key for one book on one source. */
    fun key(sourceId: String, bookKey: String): String =
        "$sourceId|$bookKey"

    /** True when [key]'s one heal attempt is already spent. */
    fun attempted(key: String): Boolean = key in attemptedKeys

    /**
     * Run [refreshChapterList] iff [memoMissing] and no heal
     * was attempted for [key] yet in this process. The
     * refresh is BEST EFFORT: its failure (or the sync's
     * honest Err) never fails the open it rides on — the
     * caller proceeds to `getPageList` either way and the
     * extension's own verdict decides the open. Caller
     * cancellation is NOT swallowed. Returns true when the
     * refresh ran (the caller may want to re-read the healed
     * chapter row before building its chapter reference).
     */
    suspend fun healOnceIfMemoMissing(
        key: String,
        memoMissing: Boolean,
        refreshChapterList: suspend () -> Unit
    ): Boolean {
        if (!memoMissing) return false
        val flight = flights.computeIfAbsent(key) { Mutex() }
        return flight.withLock {
            if (!attemptedKeys.add(key)) return@withLock false
            try {
                refreshChapterList()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Best effort — see the doc above: the open
                // proceeds and the real exception, if any,
                // surfaces at getPageList.
            }
            true
        }
    }

    /** Test seam: the attempt set is process-wide by design,
     * so gate suites reset it between cases. */
    fun clearAttemptsForTest() {
        attemptedKeys.clear()
    }
}
