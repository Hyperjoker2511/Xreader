package app.xreader.ui.book

import app.xreader.data.db.Chapter

/**
 * v1.5.7 (Finding 5, PRIMARY bug — owner evidence): a book's
 * chapter list LOST ITS BEGINNING. Chapter 1 was present in
 * the previous build; after repeatedly growing the list with
 * the load-more affordance (to the full 1,200), chapter 1 was
 * gone. The owner confirmed the scope in his own words: "for
 * whole novels, not only Novelight. Sources." — so the
 * invariant lives at the SHARED chapter machinery every novel
 * source flows through, not in any per-source patch.
 *
 * THE INVARIANT: a book's chapter list is always the complete,
 * ordered set. Loading more APPENDS to it; it never drops,
 * replaces, or windows out earlier chapters.
 *
 * This object is the book page's pure half of that invariant:
 *  - [headMissing] decides, from the stored rows alone,
 *    whether the list's beginning is gone (its earliest
 *    chapter number is past chapter 1, on a list with enough
 *    numbered rows to judge);
 *  - [healDecision] decides what a re-fetch may do about it:
 *    a fetched complete list RESTORES the stored one
 *    atomically (the sync applies it as one reconciler plan
 *    in one transaction); a failed or empty fetch KEEPS the
 *    stored list exactly as it is — a heal must never become
 *    the thing that wipes the list.
 *
 * The fetch + store half is the shared seam itself
 * (SourceBookSync.refresh — the one full re-fetch every
 * family shares: declarative, player/lane, JS/Dex alike; see
 * the scope-F report for the seam map and for the destructive
 * truncated-refresh write diagnosed inside data/extension,
 * which is the extension workstream's to fix).
 *
 * Pure, so the decision table is JVM-tested
 * (ChapterListIntegrityTest).
 */
object ChapterListIntegrity {

    /** The earliest chapter number the list carries, judged
     * by each chapter's own identity number (see
     * [ChapterJump.identityNumber] for why bare URL tails
     * don't count). Null when no chapter carries a number. */
    fun earliestNumber(chapters: List<Chapter>): Float? =
        chapters.mapNotNull { ChapterJump.identityNumber(it) }
            .minOrNull()

    /** How many chapters carry an identity number at all. */
    fun numberedCount(chapters: List<Chapter>): Int =
        chapters.count { ChapterJump.identityNumber(it) != null }

    /**
     * True when the stored list's beginning is gone: its
     * earliest numbered chapter sits past chapter 1. The bar
     * mirrors the engine's own first-chapter proof
     * (DeclarativeSource's earliest > 1 rule), plus a quorum:
     * at least three numbered rows must be present before the
     * list is judged — a one- or two-row list, or a list whose
     * chapters carry no numbers, is never condemned on a
     * guess (books that legitimately open on a prologue —
     * chapter 0 — or on chapter 1 itself pass, because their
     * earliest number is ≤ 1).
     */
    fun headMissing(chapters: List<Chapter>): Boolean {
        if (numberedCount(chapters) < 3) return false
        val earliest = earliestNumber(chapters) ?: return false
        return earliest > 1.0f
    }

    /** What a heal attempt may do with its fetch outcome. */
    sealed interface HealDecision {
        /** The stored list's head is intact — nothing to heal. */
        data object NotNeeded : HealDecision

        /** Replace the stored list with [fetched] as one
         * atomic reconciler plan: after it lands, the stored
         * list EQUALS the source's full ordered list. */
        data class Restore(val fetched: List<Chapter>) : HealDecision

        /** Keep the stored list untouched. [reason] is the
         * plain-words why, for logs/messages. */
        data class KeepStored(val reason: String) : HealDecision
    }

    /**
     * The heal decision for a book whose stored list may have
     * lost its head. [fetched] is the source's full chapter
     * list as Chapter rows would be stored (null = the fetch
     * failed). Rules, in order:
     *  1. head intact → NotNeeded (a heal never rewrites a
     *     healthy list);
     *  2. fetch failed or came back empty → KeepStored (the
     *     existing list is never wiped by a heal);
     *  3. the fetched list's head is no better than the
     *     stored one (its earliest number is not earlier) →
     *     KeepStored (swapping one headless list for another
     *     would only churn row identities);
     *  4. otherwise → Restore with the fetched list.
     */
    fun healDecision(
        stored: List<Chapter>,
        fetched: List<Chapter>?
    ): HealDecision {
        if (!headMissing(stored)) return HealDecision.NotNeeded
        if (fetched == null) {
            return HealDecision.KeepStored("the re-fetch failed")
        }
        if (fetched.isEmpty()) {
            return HealDecision.KeepStored("the re-fetch came back empty")
        }
        val storedEarliest = earliestNumber(stored)
        val fetchedEarliest = earliestNumber(fetched)
        if (fetchedEarliest != null && storedEarliest != null &&
            fetchedEarliest >= storedEarliest
        ) {
            return HealDecision.KeepStored(
                "the re-fetched list starts no earlier"
            )
        }
        return HealDecision.Restore(fetched)
    }

    /**
     * The completeness shape the invariant demands of a stored
     * list, checkable without a source: after sorting by the
     * stored index, the indices are exactly 0..size-1 with no
     * gaps and no duplicates — the list is ONE ordered set,
     * never a window. (Chapter numbers need not be contiguous
     * — sites skip and special-number chapters — so the check
     * keys on position, the thing the store itself guarantees.)
     */
    fun hasContiguousIndices(chapters: List<Chapter>): Boolean {
        val indices = chapters.map { it.index }.sorted()
        return indices == indices.indices.toList()
    }
}
