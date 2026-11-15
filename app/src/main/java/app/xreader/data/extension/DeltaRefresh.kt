package app.xreader.data.extension

import app.xreader.data.db.Chapter

/**
 * Patch 9 (big-book refresh diagnosis): the named exits of
 * the head-delta refresh ([SourceBookSync.tryHeadDeltaRefresh])
 * and the classification of its seed.
 *
 * The delta path used to abandon itself SILENTLY — every
 * guard returned the same anonymous null, so when a big
 * book's refresh fell back to the full pagination walk
 * (the owner's 1,668-chapter Novelight book was the standing
 * report), nothing anywhere said WHICH proof had failed.
 * Now every abandonment records its [DeltaBailReason] to
 * [DiagnosticLog] with the facts that decided it, and the
 * seed is classified by [DeltaSeedClassifier]. The guards
 * themselves are unchanged: a bail still means "the delta
 * can't prove it — take the full path", never an error.
 */

/** One entry per distinct cause that abandons the
 * head-delta path for the full walk. The names are a
 * diagnostic contract: they land verbatim in the session's
 * Copy-details paste, and a unit test pins this exact set
 * so a rename can't silently rewrite the evidence. */
enum class DeltaBailReason {
    /** No stored chapters yet — a first sync has nothing
     * to delta against. */
    NO_STORED_CHAPTERS,

    /** The book row carries no extension id. Unreachable
     * from the refresh call site (it errors earlier on the
     * same fact); named so no return stays anonymous. */
    NO_EXTENSION_ID,

    /** The store was synced under an older definition
     * epoch; the full path must re-earn the stamp. Fires
     * at most once per definitions update for a book whose
     * full refresh lands proven-complete. */
    EPOCH_STALE,

    /** The head-snapshot fetch itself failed (the guarded
     * source call came back Err — network, session, site). */
    SNAPSHOT_FETCH_FAILED,

    /** The fetch succeeded but the source exposed no
     * usable seed (no chapter rows on the book page, or a
     * conformance near-total loss inside the snapshot). */
    NO_USABLE_SEED,

    /** The stored list has no head row to compare.
     * Structurally unreachable (the list is proven
     * non-empty above it); named for completeness. */
    NO_STORED_HEAD,

    /** The newest stored chapter is absent from the seed —
     * the head moved, or the seed isn't the head. The
     * recorded detail carries the [DeltaSeedClass]. */
    HEAD_NOT_IN_SEED,

    /** Under the per-book lock, the fresh re-read found
     * the stored list emptied by a concurrent change. */
    STORED_EMPTIED_ON_RECHECK,

    /** The fresh re-read has no head row. Twin of
     * [NO_STORED_HEAD] on the locked re-read; likewise
     * structurally unreachable. */
    NO_STORED_HEAD_ON_RECHECK,

    /** A concurrent refresh changed the head between the
     * first read and the locked re-read; this attempt
     * re-routes to the full path instead of replaying a
     * stale plan (v1.5.39 item 1). The recorded detail
     * carries the [DeltaSeedClass] of the fresh list. */
    HEAD_MOVED_ON_RECHECK,

    /** Unchanged verdict refused: the page states an exact
     * total that disagrees with the stored count (the
     * truncated-tail disease is this shape — only a full
     * walk can rebuild the list). */
    STATED_TOTAL_MISMATCH,

    /** Unchanged verdict refused: the stored count exceeds
     * the pagination capacity the page states (declared
     * pages × page size) — the store claims more chapters
     * than the site's own pagination can hold. */
    STATED_CAPACITY_EXCEEDED,

    /** Grown verdict refused: the page's stated total is
     * not stored + the seed's inserts — the growth reaches
     * beyond the seed window (or the total counts rows the
     * seed can't show), so the delta cannot certify it. */
    UNACCOUNTED_GROWTH
}

/** How the delta seed (the book page's own chapter rows —
 * for a paginated source, its newest window) sits against
 * the stored list, judged by canonical identity. */
enum class DeltaSeedClass {
    /** The newest stored chapter rides the seed: the seed
     * really is the list's head. */
    SEED_OK,

    /** The seed overlaps the stored list's OLDEST end but
     * not its head — the page handed over the tail, not
     * the head (a reversed-window road). */
    SEED_WRONG_END,

    /** The seed overlaps the stored list somewhere in the
     * middle but reaches neither end — the head moved
     * sideways (renumbering, removals) or the window sits
     * mid-list. */
    SEED_SIDEWAYS,

    /** The seed shares no canonical identity with the
     * stored list at all — a different list entirely
     * (wrong road, wholesale re-identification). */
    SEED_NO_OVERLAP
}

/** The classifier's verdict: the class plus the size of
 * the seed∩store overlap (distinct canonical identities)
 * that decided it — the two facts a diagnosis reads. */
data class DeltaSeedAssessment(
    val seedClass: DeltaSeedClass,
    val overlap: Int
)

/** Pure seed classification, by canonical chapter identity
 * (the same [ChapterIdentity.canonicalKey] the delta check
 * itself compares with, so URL-form differences between
 * the page's rows and the stored rows — www, case,
 * trailing slash, escapes — never fake a miss). */
object DeltaSeedClassifier {

    fun assess(
        stored: List<Chapter>,
        seedIds: Set<String>
    ): DeltaSeedAssessment {
        if (stored.isEmpty()) {
            return DeltaSeedAssessment(DeltaSeedClass.SEED_NO_OVERLAP, 0)
        }
        val storedIds = stored.mapTo(HashSet()) {
            ChapterIdentity.canonicalKey(it.remoteId ?: "")
        }
        val overlap = storedIds.count { it in seedIds }
        val newestId = stored.maxByOrNull { it.index }?.let {
            ChapterIdentity.canonicalKey(it.remoteId ?: "")
        }
        // SEED_OK takes precedence over every other class:
        // a seed wide enough to hold the whole list contains
        // both ends, and the head's presence is the verdict
        // the delta acts on.
        if (newestId != null && newestId in seedIds) {
            return DeltaSeedAssessment(DeltaSeedClass.SEED_OK, overlap)
        }
        if (overlap == 0) {
            return DeltaSeedAssessment(DeltaSeedClass.SEED_NO_OVERLAP, 0)
        }
        val oldestId = stored.minByOrNull { it.index }?.let {
            ChapterIdentity.canonicalKey(it.remoteId ?: "")
        }
        return if (oldestId != null && oldestId in seedIds) {
            DeltaSeedAssessment(DeltaSeedClass.SEED_WRONG_END, overlap)
        } else {
            DeltaSeedAssessment(DeltaSeedClass.SEED_SIDEWAYS, overlap)
        }
    }
}
