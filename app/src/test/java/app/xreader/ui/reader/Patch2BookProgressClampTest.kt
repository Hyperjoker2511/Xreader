package app.xreader.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v1.7 Patch 2 (item 1e): the stored-fraction monotonicity
 * clamp both novel write sites route through
 * (ReaderViewModel.saveProgressNow and onCleared's final
 * upsert — one function, so the rule is pinned once, here).
 *
 * The clamp's job: under the char-based %, estimate refinement
 * can tick a recomputed fraction DOWN for the same place; at
 * an at-or-ahead position the stored row must not move
 * backwards. A genuinely earlier position may still write
 * lower — the reader going back is a real move, not noise.
 */
class Patch2BookProgressClampTest {

    @Test
    fun noStoredRowWritesTheComputedFraction() {
        assertEquals(
            0.42f,
            BookProgressClamp.clamp(
                computed = 0.42f, newChapterIndex = 3, newCharOffset = 100,
                storedChapterIndex = null, storedCharOffset = null,
                storedFraction = null
            ),
            0.0001f
        )
    }

    @Test
    fun refinementDecreaseAtAnAtOrAheadPositionWritesTheStoredValue() {
        // Same chapter, same offset; refinement recomputed the
        // fraction from 0.52 down to 0.50. The stored 0.52 wins.
        assertEquals(
            0.52f,
            BookProgressClamp.clamp(
                computed = 0.50f, newChapterIndex = 3, newCharOffset = 1200,
                storedChapterIndex = 3, storedCharOffset = 1200,
                storedFraction = 0.52f
            ),
            0.0001f
        )
        // Same chapter, FURTHER offset, recomputed lower: still
        // the stored value — position is ahead, fraction is not
        // allowed backwards.
        assertEquals(
            0.52f,
            BookProgressClamp.clamp(
                computed = 0.50f, newChapterIndex = 3, newCharOffset = 2400,
                storedChapterIndex = 3, storedCharOffset = 1200,
                storedFraction = 0.52f
            ),
            0.0001f
        )
        // A later chapter entirely (its own start recomputes
        // below the stored book fraction): the stored value wins.
        assertEquals(
            0.52f,
            BookProgressClamp.clamp(
                computed = 0.31f, newChapterIndex = 4, newCharOffset = 0,
                storedChapterIndex = 3, storedCharOffset = 1200,
                storedFraction = 0.52f
            ),
            0.0001f
        )
    }

    @Test
    fun genuineIncreaseAtAnAtOrAheadPositionWritesTheComputedValue() {
        assertEquals(
            0.60f,
            BookProgressClamp.clamp(
                computed = 0.60f, newChapterIndex = 3, newCharOffset = 2400,
                storedChapterIndex = 3, storedCharOffset = 1200,
                storedFraction = 0.52f
            ),
            0.0001f
        )
    }

    @Test
    fun genuinelyEarlierPositionMayStillWriteLower() {
        // Same chapter, the reader scrolled back: the lower
        // computed fraction is the truth and is written.
        assertEquals(
            0.30f,
            BookProgressClamp.clamp(
                computed = 0.30f, newChapterIndex = 3, newCharOffset = 400,
                storedChapterIndex = 3, storedCharOffset = 1200,
                storedFraction = 0.52f
            ),
            0.0001f
        )
        // An earlier chapter entirely: same rule.
        assertEquals(
            0.10f,
            BookProgressClamp.clamp(
                computed = 0.10f, newChapterIndex = 1, newCharOffset = 0,
                storedChapterIndex = 3, storedCharOffset = 1200,
                storedFraction = 0.52f
            ),
            0.0001f
        )
    }

    @Test
    fun resultIsCoercedToTheFractionRange() {
        // A stored row from another writer (sync) can carry an
        // out-of-range fraction; the clamp's output stays a
        // fraction.
        assertEquals(
            1f,
            BookProgressClamp.clamp(
                computed = 0.90f, newChapterIndex = 3, newCharOffset = 1200,
                storedChapterIndex = 3, storedCharOffset = 1200,
                storedFraction = 1.2f
            ),
            0.0001f
        )
    }
}
