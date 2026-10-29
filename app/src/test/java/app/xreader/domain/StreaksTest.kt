package app.xreader.domain

import app.xreader.data.FakeDailyActivityDao
import app.xreader.data.FakeReadingSessionDao
import app.xreader.data.reader.SessionRecorder
import app.xreader.domain.game.Streaks
import app.xreader.domain.reader.SessionTracker
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 6 gate (PLAN §8): streak day boundaries — including the
 * user's own timezone handling. Asia/Kolkata is UTC+5:30, so a
 * session just after local midnight belongs to the NEW local day
 * even though it is still the previous day in UTC.
 */
class StreaksTest {

    private val today: LocalDate = LocalDate.of(2026, 10, 28)

    private fun days(vararg offsets: Long): Set<LocalDate> =
        offsets.map { today.minusDays(it) }.toSet()

    @Test
    fun `current streak counts back from today`() {
        assertEquals(3, Streaks.currentStreak(days(0, 1, 2, 4), today))
    }

    @Test
    fun `streak survives when today has no reading yet`() {
        // Yesterday-grace: reading yesterday keeps the streak alive
        // until the day ends.
        assertEquals(2, Streaks.currentStreak(days(1, 2, 4), today))
    }

    @Test
    fun `streak dies after a full missed day`() {
        assertEquals(0, Streaks.currentStreak(days(2, 3), today))
        assertEquals(0, Streaks.currentStreak(emptySet(), today))
    }

    @Test
    fun `best streak finds the longest historical run`() {
        val all = days(0, 1) + (10L..15L).map { today.minusDays(it) } + days(30)
        assertEquals(6, Streaks.bestStreak(all))
    }

    @Test
    fun `heatmap is 26 monday-first weeks ending with today`() {
        val heat = Streaks.heatmap(emptyMap(), emptySet(), today, goalMinutes = 20)
        assertEquals(26, heat.size)
        assertTrue(heat.all { it.size == 7 })
        // Today sits in the last column; the grid starts on a Monday.
        assertTrue(heat.last().any { it.date == today })
        assertEquals(
            java.time.DayOfWeek.MONDAY,
            heat.first().first().date.dayOfWeek
        )
    }

    @Test
    fun `heatmap levels scale with minutes and the goal flag`() {
        val minutes = mapOf(
            today to 20,
            today.minusDays(1) to 10,  // half the goal → 2
            today.minusDays(2) to 3    // some reading → 1
        )
        val flat = Streaks.heatmap(
            minutes, goalMetDates = setOf(today), today, goalMinutes = 20
        ).flatten()
        fun levelOf(date: LocalDate) = flat.first { it.date == date }.level
        assertEquals(3, levelOf(today))               // goal met
        assertEquals(2, levelOf(today.minusDays(1)))  // >= half goal
        assertEquals(1, levelOf(today.minusDays(2)))  // any reading
        assertEquals(0, levelOf(today.minusDays(3)))  // nothing
    }

    @Test
    fun `a session ending just after local midnight lands on the new day`() =
        runTest {
            // 00:55 IST on Oct 28 is still Oct 27 in UTC. The
            // DailyActivity row must carry the IST date — that is
            // the day the streak engine sees.
            val ist = ZoneId.of("Asia/Kolkata")
            val daily = FakeDailyActivityDao()
            val recorder = SessionRecorder(FakeReadingSessionDao(), daily, ist)
            val start = ZonedDateTime.of(2026, 10, 28, 0, 30, 0, 0, ist)
            val end = start.plusMinutes(25)
            recorder.record(
                bookId = 1,
                snapshot = SessionTracker.Snapshot(
                    startedAt = start.toInstant().toEpochMilli(),
                    endedAt = end.toInstant().toEpochMilli(),
                    activeMinutes = 25,
                    wordsRead = 5_000,
                    chaptersFinished = 1
                )
            )
            assertNotNull(daily.get("2026-10-28"))
            assertNull(daily.get("2026-10-27"))
        }

    @Test
    fun `a session crossing midnight is bucketed by its end`() = runTest {
        val ist = ZoneId.of("Asia/Kolkata")
        val daily = FakeDailyActivityDao()
        val recorder = SessionRecorder(FakeReadingSessionDao(), daily, ist)
        val start = ZonedDateTime.of(2026, 10, 27, 23, 50, 0, 0, ist)
        val end = start.plusMinutes(30) // 00:20 on Oct 28 IST
        recorder.record(
            bookId = 1,
            snapshot = SessionTracker.Snapshot(
                startedAt = start.toInstant().toEpochMilli(),
                endedAt = end.toInstant().toEpochMilli(),
                activeMinutes = 30,
                wordsRead = 6_000,
                chaptersFinished = 0
            )
        )
        assertNotNull(daily.get("2026-10-28"))
        assertNull(daily.get("2026-10-27"))
    }
}
