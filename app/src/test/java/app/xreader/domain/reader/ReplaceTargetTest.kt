package app.xreader.domain.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Patch 11: the Replace dialog's mapping — a re-selected
 * word finds the pair responsible for it (by replacement
 * first, then by original), a fresh selection opens a new
 * pair, and case follows each pair's own rule, exactly as
 * TermReplacer matches. */
class ReplaceTargetTest {

    private val ash = TermPair("Ash", "Satoshi")
    private val lin = TermPair("Lin Dong", "Lin Feng")

    @Test
    fun `a selected replacement opens its own pair`() {
        val target = resolveReplaceTarget("Satoshi", listOf(ash, lin))
        assertEquals(ReplaceTarget.Existing(0, ash), target)
    }

    @Test
    fun `a selected original opens its own pair`() {
        val target = resolveReplaceTarget("Ash", listOf(ash, lin))
        assertEquals(ReplaceTarget.Existing(0, ash), target)
    }

    @Test
    fun `the replacement side wins over another pair's original`() {
        // "Ash" displays as "Satoshi" (pair 1); pair 2's
        // original is also "Satoshi" but it never produced
        // the displayed word — the engine does not cascade.
        // Re-selecting the displayed "Satoshi" must open
        // pair 1, the pair responsible for it.
        val red = TermPair("Satoshi", "Red")
        val target = resolveReplaceTarget("Satoshi", listOf(ash, red))
        assertEquals(ReplaceTarget.Existing(0, ash), target)
    }

    @Test
    fun `a fresh selection is a new pair with the selection as its from side`() {
        val target = resolveReplaceTarget("Mei", listOf(ash, lin))
        assertEquals(ReplaceTarget.New("Mei"), target)
    }

    @Test
    fun `an empty book list always yields a new pair`() {
        val target = resolveReplaceTarget("Ash", emptyList())
        assertEquals(ReplaceTarget.New("Ash"), target)
    }

    @Test
    fun `the selection is trimmed before it is compared or prefilled`() {
        assertEquals(
            ReplaceTarget.Existing(0, ash),
            resolveReplaceTarget("  Satoshi ", listOf(ash))
        )
        assertEquals(
            ReplaceTarget.New("Mei"),
            resolveReplaceTarget(" Mei  ", listOf(ash))
        )
    }

    @Test
    fun `a case sensitive pair does not claim a differently cased selection`() {
        // matchCase = true (the default): the replacer would
        // not have replaced "ASH", so the dialog must not
        // pretend this selection belongs to the pair.
        assertTrue(
            resolveReplaceTarget("ASH", listOf(ash)) is ReplaceTarget.New
        )
        assertTrue(
            resolveReplaceTarget("satoshi", listOf(ash)) is ReplaceTarget.New
        )
    }

    @Test
    fun `a case insensitive pair claims its texts in any case`() {
        val loose = TermPair("lin dong", "Lin Feng", matchCase = false)
        assertEquals(
            ReplaceTarget.Existing(0, loose),
            resolveReplaceTarget("LIN DONG", listOf(loose))
        )
        assertEquals(
            ReplaceTarget.Existing(0, loose),
            resolveReplaceTarget("lin feng", listOf(loose))
        )
    }

    @Test
    fun `a blank selection resolves to an empty new pair`() {
        assertEquals(
            ReplaceTarget.New(""),
            resolveReplaceTarget("   ", listOf(ash))
        )
    }
}
