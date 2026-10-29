package app.xreader.domain

import app.xreader.domain.game.Achievements
import app.xreader.domain.game.GameStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 6 gate (PLAN §8): achievement unlock conditions. */
class AchievementsTest {

    private fun stats(
        books: Int = 0,
        finished: Int = 0,
        chapters: Int = 0,
        minutes: Int = 0,
        bestStreak: Int = 0,
        focus: Int = 0,
        sources: Int = 0,
        lore: Boolean = false,
        level: Int = 1
    ) = GameStats(
        booksTotal = books,
        booksFinished = finished,
        chaptersFinished = chapters,
        totalActiveMinutes = minutes,
        bestStreak = bestStreak,
        focusSessions = focus,
        sourcesInstalled = sources,
        loreChatUsed = lore,
        level = level
    )

    private fun unlockedIds(stats: GameStats): Set<String> =
        Achievements.unlockedIds(stats)

    @Test
    fun `an empty reader unlocks nothing`() {
        assertTrue(unlockedIds(stats()).isEmpty())
    }

    @Test
    fun `first book and library of ten`() {
        assertTrue("first-book" in unlockedIds(stats(books = 1)))
        assertFalse("library-10" in unlockedIds(stats(books = 9)))
        assertTrue("library-10" in unlockedIds(stats(books = 10)))
    }

    @Test
    fun `chapter milestones at 10 100 and 1000`() {
        assertEquals(
            setOf("chapters-10"),
            unlockedIds(stats(chapters = 10))
                .filter { it.startsWith("chapters") }.toSet()
        )
        assertTrue("chapters-100" in unlockedIds(stats(chapters = 100)))
        assertTrue("chapters-1000" in unlockedIds(stats(chapters = 1000)))
        assertFalse("chapters-1000" in unlockedIds(stats(chapters = 999)))
    }

    @Test
    fun `streak achievements use the best streak`() {
        assertTrue("streak-7" in unlockedIds(stats(bestStreak = 7)))
        assertFalse("streak-30" in unlockedIds(stats(bestStreak = 29)))
        assertTrue("streak-30" in unlockedIds(stats(bestStreak = 30)))
    }

    @Test
    fun `focus achievements count completed uninterrupted sessions`() {
        assertTrue("focus-first" in unlockedIds(stats(focus = 1)))
        assertFalse("focus-10" in unlockedIds(stats(focus = 9)))
        assertTrue("focus-10" in unlockedIds(stats(focus = 10)))
    }

    @Test
    fun `level milestones`() {
        assertTrue("level-5" in unlockedIds(stats(level = 5)))
        assertTrue("level-10" in unlockedIds(stats(level = 12)))
        assertFalse("level-25" in unlockedIds(stats(level = 12)))
        assertTrue("level-50" in unlockedIds(stats(level = 50)))
    }

    @Test
    fun `firsts - source lore chat finish and the marathon`() {
        assertTrue("first-source" in unlockedIds(stats(sources = 1)))
        assertTrue("first-lore-chat" in unlockedIds(stats(lore = true)))
        assertTrue("first-finish" in unlockedIds(stats(finished = 1)))
        assertFalse("marathon-600" in unlockedIds(stats(minutes = 599)))
        assertTrue("marathon-600" in unlockedIds(stats(minutes = 600)))
    }

    @Test
    fun `every definition carries copy and a positive target`() {
        for (def in Achievements.ALL) {
            assertTrue(def.title.isNotBlank())
            assertTrue(def.description.isNotBlank())
            assertTrue(def.target > 0)
        }
        assertEquals(
            Achievements.ALL.size,
            Achievements.ALL.map { it.id }.toSet().size
        )
        // evaluate() agrees with unlockedIds() for the same stats.
        val s = stats(books = 3, chapters = 120, level = 7)
        val evaluated = Achievements.evaluate(s)
        assertEquals(unlockedIds(s), evaluated.filterValues { it }.keys)
    }
}
