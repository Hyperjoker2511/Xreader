package app.xreader.data.extension

import app.xreader.extensionapi.SourceChapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** R10 verdicts, R11 ranking/dedupe/switch, R24 import planning. */
class SourceProgrammeTest {

    private fun core(ok: Boolean) = SourceCapability.entries
        .filter { it in ContractVerdict.READING_CORE }
        .map { CapabilityResult(it, ok) }

    @Test
    fun `full core pass is working`() {
        assertEquals(
            SourceHealthState.WORKING,
            ContractVerdict.judge(core(true), checkedAtMillis = 1L)
        )
    }

    @Test
    fun `optional failure is working with limits, never blocking`() {
        val results = core(true) + CapabilityResult(SourceCapability.BOOKSHELF, false)
        val state = ContractVerdict.judge(results, 1L)
        assertEquals(SourceHealthState.WORKING_WITH_LIMITS, state)
    }

    @Test
    fun `any core failure is needs setup`() {
        val results = core(true).map {
            if (it.capability == SourceCapability.TEXT) it.copy(ok = false) else it
        }
        assertEquals(SourceHealthState.NEEDS_SETUP, ContractVerdict.judge(results, 1L))
        assertEquals(
            SourceHealthState.UNKNOWN,
            ContractVerdict.judge(emptyList(), 1L)
        )
    }

    @Test
    fun `verdict summary names the broken core part in plain words`() {
        val results = core(true).map {
            if (it.capability == SourceCapability.CHAPTERS) it.copy(ok = false) else it
        }
        val verdict = ContractVerdict(
            state = ContractVerdict.judge(results, 1L), results = results
        )
        assertTrue(verdict.summaryLine().contains("chapter lists"))
    }

    // ── Ranking / dedupe ─────────────────────────────────────────

    private fun candidate(
        id: Long, downloaded: Int = 0, total: Int = 100, latest: Long = 0,
        health: SourceHealthState = SourceHealthState.WORKING,
        load: Long? = null
    ) = SourceCandidate(id, "S$id", "r$id", health, downloaded, total, latest, load)

    @Test
    fun `default ranking puts downloads first and broken sources last`() {
        val ranked = BestSourceRanker.rank(
            listOf(
                candidate(1, downloaded = 0, latest = 900),
                candidate(2, downloaded = 40, latest = 100),
                candidate(3, downloaded = 80, health = SourceHealthState.BROKEN)
            )
        )
        // Downloads lead even against a newer rival; health breaks
        // the tie before recency in the default chain.
        assertEquals(2L, ranked[0].extensionId)
        assertEquals(3L, ranked.last().extensionId)
    }

    @Test
    fun `fastest priority honours measured load`() {
        val ranked = BestSourceRanker.rank(
            listOf(candidate(1, load = 900), candidate(2, load = 120)),
            BestSourcePriority.FASTEST
        )
        assertEquals(2L, ranked[0].extensionId)
    }

    @Test
    fun `work grouper merges spelling variants of one work`() {
        data class Hit(val t: String, val a: String, val src: String)
        val hits = listOf(
            Hit("Sword God's Return", "Lin", "A"),
            Hit("Sword God's Return [WN]", "", "B"),
            Hit("Sword God's Return", "Someone Else", "C"),
            Hit("Different Book", "Lin", "A")
        )
        val groups = WorkGrouper.group(hits, { it.t }, { it.a })
        // A+B merge (blank author is missing evidence); C does NOT
        // merge — a different named author is contrary evidence.
        assertEquals(3, groups.size)
        assertEquals(2, groups.first { it.title.startsWith("Sword") }.copies.size)
    }

    // ── Switch preview ───────────────────────────────────────────

    private fun chapters(vararg names: String): List<SourceChapter> =
        names.mapIndexed { i, name ->
            SourceChapter(id = "c$i", name = name, number = (i + 1).toFloat())
        }

    @Test
    fun `switch preview maps progress exactly when the chapter matches`() {
        val old = chapters("One", "Two", "Three", "Four")
        val new = chapters("One", "Two", "Three", "Four", "Five")
        val preview = SourceSwitchPlanner.preview(old, new, progressChapterIndex = 2, 0)
        assertTrue(preview.canSwitch)
        assertEquals(4, preview.matchedChapters)
        assertEquals(2, preview.progressChapterIndex)
        assertFalse(preview.progressApproximate)
    }

    @Test
    fun `switch preview falls back to the nearest earlier chapter`() {
        val old = chapters("One", "Two", "Three", "Four")
        // New source renumbered: chapter "Three" is missing.
        val new = listOf(
            SourceChapter(id = "n1", name = "One", number = 1.0f),
            SourceChapter(id = "n2", name = "Two", number = 2.0f),
            SourceChapter(id = "n4", name = "Four", number = 4.0f)
        )
        val preview = SourceSwitchPlanner.preview(old, new, progressChapterIndex = 2, 0)
        assertEquals(1, preview.progressChapterIndex) // lands on "Two"
        assertTrue(preview.progressApproximate)
    }

    // ── Account import ───────────────────────────────────────────

    @Test
    fun `import plan creates new, keeps present, never moves progress backwards`() {
        val account = listOf(
            AccountBook("k1", "New Book"),
            AccountBook(
                "k2", "Old Book", progressChapterKey = "ch9",
                categories = setOf(AccountCategory.BOOKSHELF, AccountCategory.PROGRESS)
            ),
            AccountBook(
                "k3", "Fav Book",
                categories = setOf(AccountCategory.FAVOURITES)
            )
        )
        val existing = listOf(
            ExistingBookState(5, "k2", "Old Book", progressChapterIndex = 12),
            ExistingBookState(6, null, "Fav Book")
        )
        val plan = AccountImportPlanner.plan(
            accountBooks = account,
            existing = existing,
            selectedCategories = AccountCategory.entries.toSet(),
            chapterIndexForKey = { _, key -> if (key == "ch9") 8 else null },
            nearestEarlier = { _, _ -> null }
        )
        assertEquals(3, plan.found)
        assertEquals(1, plan.newBooks)
        // k2's import position (8) is BEHIND local (12) → present, no update.
        val k2 = plan.decisions.first { it.account.remoteKey == "k2" }
        assertEquals(ImportAction.ALREADY_PRESENT, k2.action)
        // k3 matched by title; favourites category sets the flag.
        val k3 = plan.decisions.first { it.account.remoteKey == "k3" }
        assertTrue(k3.setFavourite)
    }

    @Test
    fun `unselected categories are skipped in the preview`() {
        val account = listOf(
            AccountBook("k1", "Book", categories = setOf(AccountCategory.HISTORY))
        )
        val plan = AccountImportPlanner.plan(
            account, emptyList(), setOf(AccountCategory.BOOKSHELF),
            { _, _ -> null }, { _, _ -> null }
        )
        assertEquals(0, plan.found)
        assertEquals(ImportAction.SKIP, plan.decisions.single().action)
    }
}
