package app.xreader.data

import app.xreader.data.db.Book
import app.xreader.data.db.DailyActivity
import app.xreader.data.db.ReadingSession
import app.xreader.data.game.ExpService
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 6 gate (PLAN §8): EXP awarding is idempotent. Every award
 * path — sessions, finished books, daily goals — pays exactly
 * once no matter how often settle() runs (app open, reader close,
 * profile open all call it).
 */
class ExpServiceTest {

    private val ist: ZoneId = ZoneId.of("Asia/Kolkata")
    private val now: ZonedDateTime =
        ZonedDateTime.of(2026, 10, 28, 12, 0, 0, 0, ist)
    private val nowMs = now.toInstant().toEpochMilli()

    private class Rig {
        val profileDao = FakeProfileDao()
        val sessionDao = FakeReadingSessionDao()
        val dailyDao = FakeDailyActivityDao()
        val bookDao = FakeBookDao()
        val chapterDao = FakeChapterDao()
        val extensionDao = FakeInstalledExtensionDao()
        val expEventDao = FakeExpEventDao()
        val achievementDao = FakeAchievementDao()
    }

    private fun service(rig: Rig): ExpService = ExpService(
        profileDao = rig.profileDao,
        sessionDao = rig.sessionDao,
        dailyDao = rig.dailyDao,
        bookDao = rig.bookDao,
        chapterDao = rig.chapterDao,
        extensionDao = rig.extensionDao,
        expEventDao = rig.expEventDao,
        achievementDao = rig.achievementDao,
        loreChatUsedProvider = { false },
        zone = ist,
        clock = { nowMs }
    )

    private fun session(
        minutes: Int,
        chapters: Int = 0,
        focus: Boolean = false,
        start: ZonedDateTime = now.minusHours(2)
    ) = ReadingSession(
        bookId = 1,
        startedAt = start.toInstant().toEpochMilli(),
        endedAt = start.plusMinutes(minutes.toLong()).toInstant().toEpochMilli(),
        activeMinutes = minutes,
        chaptersFinished = chapters,
        wordsRead = minutes * 200L,
        focusBonusApplied = focus
    )

    @Test
    fun `a session pays once and only once`() = runTest {
        val rig = Rig()
        rig.sessionDao.insert(session(minutes = 30, chapters = 2))
        val svc = service(rig)

        val first = svc.settle()
        assertEquals(170L, first.expGained) // 30*4 + 2*25
        assertEquals(170L, rig.profileDao.get(1L)?.totalExp)

        val second = svc.settle()
        assertEquals(0L, second.expGained)
        assertEquals(170L, rig.profileDao.get(1L)?.totalExp)

        // The day row carries the EXP on the day the session ended.
        val day = rig.dailyDao.get("2026-10-28")
        assertEquals(170L, day?.expEarned)
    }

    @Test
    fun `an uninterrupted focus session earns the 1_5 multiplier`() = runTest {
        val rig = Rig()
        rig.sessionDao.insert(session(minutes = 30, chapters = 0, focus = true))
        val result = service(rig).settle()
        assertEquals(180L, result.expGained) // 120 * 1.5
        assertTrue(result.newAchievements.contains("focus-first"))
    }

    @Test
    fun `a finished book pays its bonus once`() = runTest {
        val rig = Rig()
        rig.bookDao.insert(
            Book(title = "Done", addedAt = 1, updatedAt = 1, finished = true)
        )
        val svc = service(rig)
        val first = svc.settle()
        assertEquals(200L, first.expGained)
        assertTrue(first.newAchievements.contains("first-finish"))
        assertTrue(first.newAchievements.contains("first-book"))
        assertEquals(0L, svc.settle().expGained)
        // Unlock rows are recorded once, not per settle.
        val ids = rig.achievementDao.all().map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(svc.settle().newAchievements.isEmpty())
    }

    @Test
    fun `meeting today's goal pays once and reports the celebration`() = runTest {
        val rig = Rig()
        // Default daily goal is 20 minutes; today already has 25.
        rig.dailyDao.upsert(DailyActivity(date = "2026-10-28", minutesRead = 25))
        val svc = service(rig)
        val first = svc.settle()
        assertEquals(50L, first.expGained)
        assertTrue(first.goalJustMet)
        assertTrue(rig.dailyDao.get("2026-10-28")!!.goalMet)
        val second = svc.settle()
        assertEquals(0L, second.expGained)
        assertEquals(false, second.goalJustMet)
    }

    @Test
    fun `a past goal day pays quietly without the celebration flag`() = runTest {
        val rig = Rig()
        rig.dailyDao.upsert(DailyActivity(date = "2026-10-20", minutesRead = 30))
        val result = service(rig).settle()
        assertEquals(50L, result.expGained)
        assertEquals(false, result.goalJustMet)
    }

    @Test
    fun `levels derive from the banked total`() = runTest {
        val rig = Rig()
        repeat(3) { i ->
            rig.bookDao.insert(
                Book(
                    title = "Book $i", addedAt = 1, updatedAt = 1,
                    finished = true
                )
            )
        }
        val result = service(rig).settle()
        assertEquals(600L, result.expGained)      // 3 x 200
        assertEquals(1, result.levelBefore)
        assertEquals(2, result.levelAfter)        // 500 <= 600 < 2241
        assertEquals(2, rig.profileDao.get(1L)?.level)
    }

    @Test
    fun `stats snapshot reflects the records`() = runTest {
        val rig = Rig()
        rig.sessionDao.insert(session(minutes = 45, chapters = 3))
        // Chapter read state lives on Chapter rows (stats read them).
        repeat(3) { i ->
            rig.chapterDao.insert(
                app.xreader.data.db.Chapter(
                    bookId = 1, index = i, title = "Ch $i",
                    read = true, updatedAt = 1
                )
            )
        }
        rig.dailyDao.upsert(DailyActivity(date = "2026-10-28", minutesRead = 45))
        rig.dailyDao.upsert(DailyActivity(date = "2026-10-27", minutesRead = 10))
        val svc = service(rig)
        svc.settle()
        val stats = svc.stats()
        assertEquals(45, stats.totalActiveMinutes)
        assertEquals(3, stats.chaptersFinished)
        assertEquals(2, stats.currentStreak) // today + yesterday, IST
        assertEquals(2, stats.bestStreak)
    }
}
