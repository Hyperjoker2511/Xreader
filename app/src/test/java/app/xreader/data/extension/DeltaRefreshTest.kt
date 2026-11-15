package app.xreader.data.extension

import app.xreader.data.db.Chapter
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Patch 9 (big-book refresh diagnosis): the delta seed
 * classifier and the bail-reason contract.
 *
 * The fixtures are shaped like the standing report — a
 * 1,668-chapter stored list (the owner's Novelight book)
 * against the newest-50 window a paginated book page
 * serves as the delta seed. The classifier is the unit
 * that decides which [DeltaSeedClass] a bail records;
 * the reason names themselves are pinned verbatim because
 * they land in Copy details as the diagnosis evidence —
 * a rename must break loudly here, not silently rewrite
 * a fault report's vocabulary.
 */
class DeltaRefreshTest {

    private fun storedBook(
        count: Int,
        base: String = "https://novelight.net/book/ex-rank"
    ): List<Chapter> = (0 until count).map { i ->
        Chapter(
            bookId = 7L,
            index = i,
            title = "Chapter ${i + 1}",
            remoteId = "$base/chapter-${i + 1}",
            updatedAt = 0L
        )
    }

    /** Seed ids the way production builds them: the seed
     * rows' ids through canonical identity. */
    private fun seedIdsOf(
        stored: List<Chapter>,
        indexes: IntRange
    ): Set<String> = indexes.mapTo(HashSet()) {
        ChapterIdentity.canonicalKey(stored[it].remoteId ?: "")
    }

    @Test
    fun `newest window seed is SEED_OK`() {
        val stored = storedBook(1668)
        // The book page's newest 50 rows: indexes 1618..1667.
        val assessment = DeltaSeedClassifier.assess(
            stored, seedIdsOf(stored, 1618..1667)
        )
        assertEquals(DeltaSeedClass.SEED_OK, assessment.seedClass)
        assertEquals(50, assessment.overlap)
    }

    @Test
    fun `oldest window seed is SEED_WRONG_END`() {
        val stored = storedBook(1668)
        val assessment = DeltaSeedClassifier.assess(
            stored, seedIdsOf(stored, 0..49)
        )
        assertEquals(DeltaSeedClass.SEED_WRONG_END, assessment.seedClass)
        assertEquals(50, assessment.overlap)
    }

    @Test
    fun `middle window seed is SEED_SIDEWAYS`() {
        val stored = storedBook(1668)
        val assessment = DeltaSeedClassifier.assess(
            stored, seedIdsOf(stored, 799..848)
        )
        assertEquals(DeltaSeedClass.SEED_SIDEWAYS, assessment.seedClass)
        assertEquals(50, assessment.overlap)
    }

    @Test
    fun `shifted head window is SEED_SIDEWAYS with partial overlap`() {
        val stored = storedBook(1668)
        // The site's newest stored chapter fell out of the
        // window (removed / renumbered) and two chapters the
        // store has never seen rode in: the seed now covers
        // stored indexes 1619..1666 plus two foreign ids.
        val seed = seedIdsOf(stored, 1619..1666) + setOf(
            ChapterIdentity.canonicalKey(
                "https://novelight.net/book/ex-rank/chapter-1669"
            ),
            ChapterIdentity.canonicalKey(
                "https://novelight.net/book/ex-rank/chapter-1670"
            )
        )
        val assessment = DeltaSeedClassifier.assess(stored, seed)
        assertEquals(DeltaSeedClass.SEED_SIDEWAYS, assessment.seedClass)
        assertEquals(48, assessment.overlap)
    }

    @Test
    fun `foreign seed is SEED_NO_OVERLAP`() {
        val stored = storedBook(1668)
        val other = storedBook(50, "https://novelight.net/book/other-book")
        val assessment = DeltaSeedClassifier.assess(
            stored, seedIdsOf(other, 0..49)
        )
        assertEquals(DeltaSeedClass.SEED_NO_OVERLAP, assessment.seedClass)
        assertEquals(0, assessment.overlap)
    }

    @Test
    fun `whole list seed is SEED_OK even though it holds the oldest too`() {
        // A small book fits on one page: the seed contains
        // BOTH ends. The head's presence is the verdict the
        // delta acts on, so SEED_OK outranks SEED_WRONG_END.
        val stored = storedBook(30)
        val assessment = DeltaSeedClassifier.assess(
            stored, seedIdsOf(stored, 0..29)
        )
        assertEquals(DeltaSeedClass.SEED_OK, assessment.seedClass)
        assertEquals(30, assessment.overlap)
    }

    @Test
    fun `seed holding the head plus the tail is still SEED_OK`() {
        val stored = storedBook(1668)
        val seed = seedIdsOf(stored, 0..49) + seedIdsOf(stored, 1667..1667)
        val assessment = DeltaSeedClassifier.assess(stored, seed)
        assertEquals(DeltaSeedClass.SEED_OK, assessment.seedClass)
        assertEquals(51, assessment.overlap)
    }

    @Test
    fun `url form differences never fake a head miss`() {
        // The stored row's URL and the page's current href
        // for the same chapter in another dress (www, case,
        // trailing slash): canonical identity folds them, so
        // the head is present and the class is SEED_OK.
        val stored = storedBook(1668)
        val seed = setOf(
            ChapterIdentity.canonicalKey(
                "https://www.Novelight.net/book/ex-rank/chapter-1668/"
            )
        )
        val assessment = DeltaSeedClassifier.assess(stored, seed)
        assertEquals(DeltaSeedClass.SEED_OK, assessment.seedClass)
        assertEquals(1, assessment.overlap)
    }

    @Test
    fun `empty store classifies as no overlap`() {
        val assessment = DeltaSeedClassifier.assess(
            emptyList(), setOf("https://novelight.net/book/x/chapter-1")
        )
        assertEquals(DeltaSeedClass.SEED_NO_OVERLAP, assessment.seedClass)
        assertEquals(0, assessment.overlap)
    }

    @Test
    fun `bail reason names are the pinned diagnostic contract`() {
        assertEquals(
            listOf(
                "NO_STORED_CHAPTERS",
                "NO_EXTENSION_ID",
                "EPOCH_STALE",
                "SNAPSHOT_FETCH_FAILED",
                "NO_USABLE_SEED",
                "NO_STORED_HEAD",
                "HEAD_NOT_IN_SEED",
                "STORED_EMPTIED_ON_RECHECK",
                "NO_STORED_HEAD_ON_RECHECK",
                "HEAD_MOVED_ON_RECHECK",
                "STATED_TOTAL_MISMATCH",
                "STATED_CAPACITY_EXCEEDED",
                "UNACCOUNTED_GROWTH"
            ),
            DeltaBailReason.entries.map { it.name }
        )
        assertEquals(
            listOf(
                "SEED_OK", "SEED_WRONG_END", "SEED_SIDEWAYS", "SEED_NO_OVERLAP"
            ),
            DeltaSeedClass.entries.map { it.name }
        )
    }
}
