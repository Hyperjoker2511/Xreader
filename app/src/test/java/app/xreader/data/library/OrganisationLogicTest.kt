package app.xreader.data.library

import app.xreader.data.db.Shelf
import app.xreader.data.db.ShelfKind
import app.xreader.data.db.SmartRule
import app.xreader.data.db.VocabState
import app.xreader.data.db.VocabWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** R21 shelves, R22 tags, vocab review, update rhythm, content search. */
class OrganisationLogicTest {

    private val now = 1_700_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    private fun facts(
        id: Long,
        favourite: Boolean = false,
        downloaded: Int = 0,
        unreadDl: Int = 0,
        unseen: Int = 0,
        updatedAt: Long = now - 30 * day,
        status: ReadingStatus = ReadingStatus.NONE,
        tags: Set<String> = emptySet()
    ) = ShelfLogic.BookFacts(id, favourite, downloaded, unreadDl, unseen, updatedAt, status, tags)

    // ── Shelves ──────────────────────────────────────────────────

    @Test
    fun `all is pinned first and can never be hidden`() {
        val chips = ShelfLogic.chips(
            facts = listOf(facts(1)),
            userShelves = emptyList(),
            manualMembers = emptyMap(),
            hiddenBuiltins = setOf(
                ShelfLogic.BUILTIN_FAVOURITES, ShelfLogic.BUILTIN_DOWNLOADED,
                ShelfLogic.BUILTIN_BEHIND_ON
            ),
            nowMillis = now
        )
        assertEquals(listOf("all"), chips.map { it.ref })
        assertEquals(1, chips[0].count)
    }

    @Test
    fun `builtin membership follows the facts`() {
        val rows = listOf(
            facts(1, favourite = true),
            facts(2, downloaded = 12, unreadDl = 3),
            facts(3, unseen = 5)
        )
        assertEquals(setOf(1L), ShelfLogic.builtinMembers(ShelfLogic.BUILTIN_FAVOURITES, rows))
        assertEquals(setOf(2L), ShelfLogic.builtinMembers(ShelfLogic.BUILTIN_DOWNLOADED, rows))
        assertEquals(setOf(3L), ShelfLogic.builtinMembers(ShelfLogic.BUILTIN_BEHIND_ON, rows))
    }

    @Test
    fun `smart shelves fill themselves by rule`() {
        val tagShelf = Shelf(
            id = 7, name = "Cultivation", kind = ShelfKind.SMART,
            rule = SmartRule.TAG, ruleValue = "cultivation",
            createdAt = now, updatedAt = now
        )
        val weekShelf = Shelf(
            id = 8, name = "Fresh", kind = ShelfKind.SMART,
            rule = SmartRule.UPDATED_THIS_WEEK,
            createdAt = now, updatedAt = now
        )
        val rows = listOf(
            facts(1, tags = setOf("cultivation"), updatedAt = now - day),
            facts(2, tags = setOf("romance"), updatedAt = now - 20 * day)
        )
        assertEquals(setOf(1L), ShelfLogic.smartMembers(tagShelf, rows, now))
        assertEquals(setOf(1L), ShelfLogic.smartMembers(weekShelf, rows, now))
        // A manual shelf never pretends to be rule-owned.
        val manual = tagShelf.copy(kind = ShelfKind.MANUAL)
        assertTrue(ShelfLogic.smartMembers(manual, rows, now).isEmpty())
    }

    @Test
    fun `hidden user shelves leave the rail but keep membership`() {
        val hidden = Shelf(
            id = 9, name = "Secret", hidden = true, createdAt = now, updatedAt = now
        )
        val chips = ShelfLogic.chips(
            listOf(facts(1)), listOf(hidden),
            mapOf(9L to setOf(1L)), emptySet(), now
        )
        assertFalse(chips.any { it.ref == "user:9" })
        assertEquals(
            setOf(1L),
            ShelfLogic.membersFor("user:9", listOf(facts(1)), listOf(hidden),
                mapOf(9L to setOf(1L)), now)
        )
    }

    // ── Tags ─────────────────────────────────────────────────────

    @Test
    fun `source labels normalise to one canonical tag`() {
        assertEquals("male-protagonist", TagRegistry.slugFor("Male MC"))
        assertEquals("male-protagonist", TagRegistry.slugFor("Male Lead"))
        assertEquals("male-protagonist", TagRegistry.slugFor("Male Protagonist"))
        assertEquals("overpowered-mc", TagRegistry.slugFor("OP MC"))
        assertEquals("smart-mc", TagRegistry.slugFor("Scheming MC"))
        assertEquals("Male MC", TagRegistry.displayFor("male-protagonist"))
        // Unknown labels keep their own slug — evidence preserved.
        assertEquals("some-new-tag", TagRegistry.slugFor("Some New Tag"))
    }

    @Test
    fun `synopsis extraction infers cautiously`() {
        val tags = TagRegistry.extractFromSynopsis(
            "He was reincarnated. The system appeared with a status window. " +
                "The weakest trash would take his revenge."
        )
        assertTrue("reincarnation" in tags)
        assertTrue("game-elements" in tags)
        assertTrue("revenge" in tags)
        // Word-boundary discipline: "qi" inside other words must not fire.
        val none = TagRegistry.extractFromSynopsis(
            "A quiet tale about acquiring antiques in a small village."
        )
        assertFalse("cultivation" in none)
        assertTrue(TagRegistry.extractFromSynopsis("").isEmpty())
    }

    // ── Vocab ────────────────────────────────────────────────────

    private fun word(state: VocabState, interval: Double) = VocabWord(
        id = 1, word = "ephemeral", state = state, intervalDays = interval,
        dueAt = 0, createdAt = now, updatedAt = now
    )

    @Test
    fun `remembering climbs the ladder and graduates at fourteen days`() {
        var w = word(VocabState.NEW, 0.0)
        w = VocabReview.review(w, remembered = true, nowMillis = now)
        assertEquals(VocabState.LEARNING, w.state)
        assertEquals(1.0, w.intervalDays, 0.001)
        w = VocabReview.review(w.copy(intervalDays = 7.0), remembered = true, nowMillis = now)
        assertEquals(14.0, w.intervalDays, 0.001)
        assertEquals(VocabState.KNOWN, w.state)
    }

    @Test
    fun `forgetting resets to tomorrow without deleting the word`() {
        val w = VocabReview.review(
            word(VocabState.LEARNING, 7.0), remembered = false, nowMillis = now
        )
        assertEquals(1.0, w.intervalDays, 0.001)
        assertEquals(now + day, w.dueAt)
        assertEquals(VocabState.LEARNING, w.state)
    }

    // ── Update rhythm ────────────────────────────────────────────

    @Test
    fun `daily serial projects tomorrow`() {
        val stamps = (0..6).map { now - (6 - it) * day }
        val est = UpdateRhythm.estimate(stamps, now)!!
        assertEquals(day, est.medianIntervalMillis)
        assertFalse(est.overdue)
        assertEquals("Next chapter expected tomorrow", UpdateRhythm.label(est, now))
    }

    @Test
    fun `too little history gives no estimate rather than a guess`() {
        assertNull(UpdateRhythm.estimate(listOf(now, now + day), now))
        assertNull(UpdateRhythm.estimate(emptyList(), now))
    }

    // ── Content search ───────────────────────────────────────────

    @Test
    fun `content search finds downloaded text with snippets and counts`() {
        val chapters = listOf(
            ChapterTextForSearch(1, "Book", 0, "Ch 1", "The sword fell. The sword sang."),
            ChapterTextForSearch(1, "Book", 1, "Ch 2", "No blades here."),
            ChapterTextForSearch(2, "Other", 0, "Ch 1", "A sword appears once.")
        )
        val hits = ContentSearcher.search("sword", chapters)
        assertEquals(2, hits.size)
        assertEquals(2, hits[0].matchCount)
        assertEquals(0, hits[0].chapterIndex)
        assertTrue(hits[0].snippet.contains("sword"))
        // Too-short queries don't pretend to search.
        assertTrue(ContentSearcher.search("s", chapters).isEmpty())
    }
}
