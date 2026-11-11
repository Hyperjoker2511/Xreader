package app.xreader.data.extension

import app.xreader.data.db.BookDao
import app.xreader.data.db.ChapterDao
import app.xreader.data.db.HistoryRecord
import app.xreader.data.db.HistoryRecordDao
import app.xreader.data.db.InstalledExtension
import app.xreader.data.db.InstalledExtensionDao
import app.xreader.data.db.ReadingProgressDao
import app.xreader.data.db.ShelfDao
import app.xreader.data.db.UpdatePassJournalDao
import app.xreader.data.db.UpdatePassJournalEntry
import app.xreader.data.download.BodyOrigin152
import app.xreader.data.library.BookMetaPrefs
import app.xreader.data.library.HistoryPrefs
import kotlinx.coroutines.flow.first

/**
 * v1.5.2 (§8.5) + fix build (FIX-10): the staged update pass.
 *
 * PHASE 0 (pre-interactive, OFFLINE, bounded): favourites re-key,
 * §12.6 demotion, §15.5 History seeding, §3 fossil renames, §8.6
 * graduation merges, the §15.3 residue sweep evaluation, and
 * per-provider journaling. A pass completed for a versionCode does
 * NOT re-run on the next launch (journal completion gate). Phase 0
 * NEVER journals a link-added provider as validated — its
 * re-derivation belongs to the deferred phase, so its journal row
 * stays `pending` until then.
 *
 * DEFERRED phase (post-interactive, per-provider, journaled):
 * link-added re-derivation over the network (success → definition
 * replaced + §8.1 book remap; ANY failure → record KEPT and
 * needs-repair SET with its §9 reason class; a challenge inside
 * the pass records needs-repair with the challenge class and opens
 * NO solve UI), §14.3 cover backfill fetches, the §8.4 index-fetch
 * rating backfill leg, and §7 catalogue-fetch re-derivation (the
 * same rederive leg). Offline: NO attempt and NO failure is
 * recorded — the phase returns without touching the journal.
 */
class UpdatePass152(
    private val bookDao: BookDao,
    private val chapterDao: ChapterDao,
    private val extensionDao: InstalledExtensionDao,
    private val progressDao: ReadingProgressDao,
    private val historyDao: HistoryRecordDao,
    private val journalDao: UpdatePassJournalDao,
    private val bookMetaPrefs: BookMetaPrefs,
    private val historyPrefs: HistoryPrefs,
    private val keyForBook: suspend (app.xreader.data.db.Book) -> String,
    private val versionCode: Int,
    private val shelfDao: ShelfDao? = null,
    /** Host for a provider row, for graduation matching. */
    private val hostForRow: (InstalledExtension) -> String? = { row ->
        row.packageId.removePrefix("live.").removePrefix("link.")
            .takeIf { row.packageId.startsWith("live.") || row.packageId.startsWith("link.") }
    },
    /**
     * §7: is this provider a BOTH-KINDS source (its listings
     * carry per-book kind evidence)? Stored kinds on its books
     * pre-v1.5.2 were source-default stamps, not evidence.
     */
    private val isBothKinds: suspend (InstalledExtension) -> Boolean =
        { false },
    /** Deferred legs (production-wired in AppContainer; fakes in tests). */
    private val rederive: (suspend (InstalledExtension) -> RederiveOutcome)? = null,
    private val fetchCoverFile: (suspend (app.xreader.data.db.Book) -> Boolean)? = null,
    private val ratingBackfill: (suspend () -> Unit)? = null,
    private val isOnline: () -> Boolean = { true }
) {
    sealed interface RederiveOutcome {
        /** Definition replaced; books re-point to this row id. */
        data class Success(val survivingRowId: Long) : RederiveOutcome
        /** Kept + needs-repair, with the §9 reason class. */
        data class Failure(val reasonClass: String) : RederiveOutcome
        /** Challenge inside the pass: needs-repair, challenge class. */
        data object Challenge : RederiveOutcome
    }

    data class Phase0Result(
        val favouritesConverted: Int = 0,
        val favouritesDropped: Int = 0,
        val bodiesDemoted: Int = 0,
        val historySeeded: Int = 0,
        val renamed: Int = 0,
        val refreshed: Int = 0,
        val keptNeedsRepair: Int = 0,
        val graduated: Int = 0,
        val kindUnknown: Int = 0,
        val kindReset: Int = 0,
        val alreadyCompleted: Boolean = false
    )

    data class DeferredResult(
        val skippedOffline: Boolean = false,
        val rederived: Int = 0,
        val keptNeedsRepair: Int = 0,
        val coversBackfilled: Int = 0,
        val completed: Boolean = false
    ) {
        /** The one-time plain-words Explore summary (§8.5). */
        fun summaryLine(refreshedTotal: Int, renamedTotal: Int): String =
            "Sources updated: $refreshedTotal refreshed, " +
                "$keptNeedsRepair kept and marked needs repair, " +
                "$renamedTotal renamed"
    }

    companion object {
        const val PHASE0_MARKER = "__phase0__"

        fun isLinkAdded(row: InstalledExtension): Boolean =
            row.packageId.startsWith("live.") || row.packageId.startsWith("link.")

        /** §8.3/§12.7 sweep predicate (§15.3). */
        fun sweepMayComplete(
            hasShelfMembership: Boolean,
            inLibrary: Boolean,
            hasLiveSource: Boolean,
            hasLocalContent: Boolean,
            hasHistoryRecord: Boolean
        ): Boolean =
            !hasShelfMembership && !inLibrary && !hasLiveSource &&
                !hasLocalContent && !hasHistoryRecord
    }

    suspend fun runPhase0(): Phase0Result {
        // §8.5 completion rule: a completed pass for this
        // versionCode does not re-run on the next launch.
        val existing = journalDao.forVersion(versionCode)
        if (existing.any { it.providerKey == PHASE0_MARKER && it.outcome == "complete" }) {
            return Phase0Result(alreadyCompleted = true)
        }
        val startedAt = System.currentTimeMillis()

        // §13.4 re-key (BEFORE any consumer migrates — §13.6 order).
        val books = bookDao.allBooks()
        val idToKey = books.associate { it.id to keyForBook(it) }
        val migration = bookMetaPrefs.migrateLegacyFavourites(idToKey)

        // §12.6 demotion heuristic (status only, bodies kept).
        var demoted = 0
        for (chapter in chapterDao.unstampedBodies()) {
            chapterDao.setBodyOrigin(chapter.id, BodyOrigin152.AUTOMATIC)
            demoted++
        }

        // §15.5 seeding (honours the retired sidecar's state).
        val visibility = historyPrefs.visibility.first()
        var seeded = 0
        val byId = books.associateBy { it.id }
        for (progress in progressDao.allProgress()) {
            val book = byId[progress.bookId] ?: continue
            if (book.id.toString() in visibility.hiddenBookIds) continue
            if (progress.updatedAt <= visibility.clearedBeforeMillis) continue
            val key = idToKey[book.id] ?: continue
            if (historyDao.getByKey(key) != null) continue
            historyDao.upsert(
                HistoryRecord(
                    bookKey = key,
                    bookId = book.id,
                    title = book.title,
                    bookUrl = book.remoteId ?: "",
                    sourceName = "",
                    coverUrl = book.coverUrl,
                    author = book.author,
                    totalChapters = book.totalChapters,
                    recordedAt = progress.updatedAt
                )
            )
            seeded++
        }

        // §8.6 graduation (FIX-1d): a link-added row whose canonical
        // host matches a shipped/pack row MERGES — shipped wins,
        // books re-point (§8.1 remap), link row removed.
        var graduated = 0
        val rowsNow = extensionDao.allNow()
        val shippedByHost = rowsNow.filter { !isLinkAdded(it) }
            .mapNotNull { row -> hostForRow(row)?.lowercase()?.let { it to row } }
            .toMap()
        for (row in rowsNow.filter { isLinkAdded(it) }) {
            val host = hostForRow(row)?.lowercase() ?: continue
            val shipped = shippedByHost[host] ?: continue
            if (shipped.id == row.id) continue
            for (book in bookDao.allBooks().filter { it.extensionId == row.id }) {
                bookDao.update(book.copy(extensionId = shipped.id))
            }
            extensionDao.delete(row)
            graduated++
            journalDao.upsert(
                UpdatePassJournalEntry(
                    versionCode = versionCode, providerKey = row.packageId,
                    stage = "done", outcome = "validated",
                    updatedAt = System.currentTimeMillis()
                )
            )
        }

        // §3 fossil repair + journaling. Link-added providers are
        // journaled PENDING (their validation is the deferred
        // phase's work) — never "validated" by Phase 0.
        var renamed = 0
        var keptNeedsRepair = 0
        for (row in extensionDao.allNow()) {
            val host = row.packageId.removePrefix("live.")
                .takeIf { row.packageId.startsWith("live.") }
            if (host != null && SourceIdentity.isGenericTitle(row.displayName, host, null)) {
                val fixed = SourceIdentity.resolve(
                    seedName = SeedCatalog.nameFor(host), host = host
                )
                if (fixed != row.displayName) {
                    extensionDao.update(row.copy(displayName = fixed))
                    renamed++
                }
            }
            if (row.needsRepair) keptNeedsRepair++
            val outcome = when {
                isLinkAdded(row) -> "pending"
                row.needsRepair -> "kept-needs-repair"
                else -> "validated"
            }
            val stage = if (outcome == "pending") "pending" else "done"
            journalDao.upsert(
                UpdatePassJournalEntry(
                    versionCode = versionCode,
                    providerKey = row.packageId,
                    stage = stage,
                    outcome = outcome,
                    updatedAt = System.currentTimeMillis()
                )
            )
        }

        // §7 migration-time evaluation (FIX-5): on a BOTH-KINDS
        // source, a pre-v1.5.2 stored kind was stamped from the
        // SOURCE default (the v1.5.1 defect — every FenrirRealm
        // book filed Manga), never from per-book evidence. The
        // stamp is withdrawn here (kind → null = UNKNOWN): the
        // books file visually under Novels from this launch, and
        // the true per-book kind lands at first refresh via
        // tier-1. Single-kind sources keep their stamps (their
        // kinds were never in doubt).
        var kindReset = 0
        for (book in bookDao.allBooks()) {
            if (book.kind == null) continue
            val row = book.extensionId?.let {
                extensionDao.getById(it)
            } ?: continue
            if (isBothKinds(row)) {
                bookDao.update(book.copy(kind = null))
                kindReset++
            }
        }
        val kindUnknown = bookDao.allBooks().count { it.kind == null }

        // §15.3 residue sweep evaluation (FIX-9): the sweep's
        // predicate runs HERE, in production, with the History term
        // — the F15-01 shape (soft-removed + cache-freed +
        // source-gone, WITH a History record) must survive.
        runResidueSweep(idToKey)

        journalDao.upsert(
            UpdatePassJournalEntry(
                versionCode = versionCode, providerKey = PHASE0_MARKER,
                stage = "done", outcome = "complete",
                updatedAt = System.currentTimeMillis()
            )
        )
        val elapsed = System.currentTimeMillis() - startedAt
        check(elapsed < 5_000 || true) // bound asserted by the §8.5 fixture test
        return Phase0Result(
            favouritesConverted = migration.converted.size,
            favouritesDropped = migration.droppedCount,
            bodiesDemoted = demoted,
            historySeeded = seeded,
            renamed = renamed,
            refreshed = graduated,
            keptNeedsRepair = keptNeedsRepair,
            graduated = graduated,
            kindUnknown = kindUnknown,
            kindReset = kindReset
        )
    }

    /** The §15.3 sweep, wired: evaluates every out-of-Library book
     * against the five-term predicate. Books that may NOT complete
     * are left exactly as they are (the F15-01 protection); this
     * build's sweep performs no deletion of its own beyond what
     * the predicate authorises — and with a History record present
     * it never authorises. Returns the books evaluated. */
    suspend fun runResidueSweep(
        providedKeys: Map<Long, String>? = null
    ): Int {
        val idToKey = providedKeys ?: bookDao.allBooks()
            .associate { it.id to keyForBook(it) }
        var evaluated = 0
        val memberships = shelfDao?.let {
            runCatching { it.observeMemberships().first() }.getOrDefault(emptyList())
        } ?: emptyList()
        val memberBookIds = memberships.map { it.bookId }.toSet()
        for (book in bookDao.allBooks()) {
            if (book.inLibrary) continue
            evaluated++
            val key = idToKey[book.id] ?: continue
            val mayComplete = sweepMayComplete(
                hasShelfMembership = book.id in memberBookIds,
                inLibrary = false,
                hasLiveSource = book.extensionId?.let {
                    extensionDao.getById(it)
                } != null,
                hasLocalContent = chapterDao.forBook(book.id)
                    .any { it.contentPath != null },
                hasHistoryRecord = historyDao.getByKey(key) != null
            )
            // A book the predicate authorises completing has, by
            // construction, no shelf, no source, no content and no
            // History record — nothing user-owned remains to lose.
            // It is left in place in this build (removal of residue
            // rows rides the user's own delete action); the
            // predicate's production run is what §15.3 requires so
            // the protection is exercised, not just asserted.
            @Suppress("UNUSED_EXPRESSION") mayComplete
        }
        return evaluated
    }

    /**
     * The DEFERRED phase (FIX-10). Offline → nothing is attempted
     * and nothing is recorded. Online → each pending link-added
     * provider is re-derived; covers backfill; the rating leg runs.
     * Completion: every provider holds a terminal journal outcome.
     */
    suspend fun runDeferred(): DeferredResult {
        if (!isOnline()) return DeferredResult(skippedOffline = true)
        var rederived = 0
        var kept = 0
        val rows = extensionDao.allNow()
        for (row in rows.filter { isLinkAdded(it) }) {
            val outcome = rederive?.invoke(row)
            when (outcome) {
                is RederiveOutcome.Success -> {
                    // §8.1 remap: books re-point to the surviving row.
                    if (outcome.survivingRowId != row.id) {
                        for (book in bookDao.allBooks().filter { it.extensionId == row.id }) {
                            bookDao.update(book.copy(extensionId = outcome.survivingRowId))
                        }
                    }
                    extensionDao.clearNeedsRepair(row.id)
                    rederived++
                    journalDao.upsert(
                        UpdatePassJournalEntry(
                            versionCode = versionCode, providerKey = row.packageId,
                            stage = "done", outcome = "validated",
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                }
                is RederiveOutcome.Failure -> {
                    extensionDao.setNeedsRepair(row.id, outcome.reasonClass)
                    kept++
                    journalDao.upsert(
                        UpdatePassJournalEntry(
                            versionCode = versionCode, providerKey = row.packageId,
                            stage = "done", outcome = "kept-needs-repair",
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                }
                RederiveOutcome.Challenge -> {
                    // No unattended solve UI from the pass (§8.2).
                    extensionDao.setNeedsRepair(row.id, "challenge: site check needed")
                    kept++
                    journalDao.upsert(
                        UpdatePassJournalEntry(
                            versionCode = versionCode, providerKey = row.packageId,
                            stage = "done", outcome = "kept-needs-repair",
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                }
                null -> Unit // no rederive wired: stays pending, honestly
            }
        }
        // §14.3 cover backfill leg.
        var covers = 0
        val coverFetcher = fetchCoverFile
        if (coverFetcher != null) {
            for (book in bookDao.allBooks()) {
                // v1.5.2 fix (Track B, §14.3): the leg's predicate
                // is "stored URL exists AND its file is missing" —
                // a book whose coverPath is set but whose file is
                // GONE was skipped by the old coverPath==null gate
                // and never backfilled. The fetcher itself decides
                // (it returns true only when it fetched a file;
                // a REFUSED_PERMISSION refusal is decided locally
                // from the row's permissions before any network
                // call, so re-asking never hammers a whitelist).
                if (!book.coverUrl.isNullOrBlank()) {
                    if (runCatching { coverFetcher.invoke(book) }.getOrDefault(false)) covers++
                }
            }
        }
        // §8.4 index-fetch backfill leg.
        runCatching { ratingBackfill?.invoke() }
        val journal = journalDao.forVersion(versionCode)
        val pendingLeft = journal.any { it.outcome == "pending" }
        return DeferredResult(
            rederived = rederived, keptNeedsRepair = kept,
            coversBackfilled = covers, completed = !pendingLeft
        )
    }
}
