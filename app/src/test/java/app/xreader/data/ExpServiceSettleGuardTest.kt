package app.xreader.data

import app.xreader.data.db.AchievementDao
import app.xreader.data.db.AchievementUnlock
import app.xreader.data.db.Book
import app.xreader.data.db.BookDao
import app.xreader.data.db.ChapterDao
import app.xreader.data.db.DailyActivityDao
import app.xreader.data.db.ReadingSession
import app.xreader.data.game.ExpService
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.2.0 (audit D12): settle() is change-driven. A second settle
 * with nothing changed must not re-run the full-library scans
 * (finished-books list, all daily rows, chapter read count,
 * achievement list) — the counting wrappers below record every
 * such call. A genuinely new change (an unprocessed session)
 * must break the guard and pay out through the full pass.
 */
class ExpServiceSettleGuardTest {

    private val ist: ZoneId = ZoneId.of("Asia/Kolkata")
    private val now: ZonedDateTime =
        ZonedDateTime.of(2026, 10, 28, 12, 0, 0, 0, ist)
    private val nowMs = now.toInstant().toEpochMilli()

    private class CountingBookDao(
        private val inner: FakeBookDao
    ) : BookDao by inner {
        var finishedBooksCalls = 0
        override suspend fun finishedBooks(): List<Book> {
            finishedBooksCalls++
            return inner.finishedBooks()
        }
    }

    private class CountingDailyDao(
        private val inner: FakeDailyActivityDao
    ) : DailyActivityDao by inner {
        var allCalls = 0
        override suspend fun all(): List<app.xreader.data.db.DailyActivity> {
            allCalls++
            return inner.all()
        }
    }

    private class CountingChapterDao(
        private val inner: FakeChapterDao
    ) : ChapterDao by inner {
        var readCountCalls = 0
        override suspend fun readCount(): Int {
            readCountCalls++
            return inner.readCount()
        }
    }

    private class CountingAchievementDao(
        private val inner: FakeAchievementDao
    ) : AchievementDao by inner {
        var allCalls = 0
        override suspend fun all(): List<AchievementUnlock> {
            allCalls++
            return inner.all()
        }
    }

    private class Rig {
        val profileDao = FakeProfileDao()
        val sessionDao = FakeReadingSessionDao()
        val dailyFake = FakeDailyActivityDao()
        val dailyDao = CountingDailyDao(dailyFake)
        val bookFake = FakeBookDao()
        val bookDao = CountingBookDao(bookFake)
        val chapterFake = FakeChapterDao()
        val chapterDao = CountingChapterDao(chapterFake)
        val extensionDao = FakeInstalledExtensionDao()
        val expEventDao = FakeExpEventDao()
        val achievementFake = FakeAchievementDao()
        val achievementDao = CountingAchievementDao(achievementFake)

        fun scans(): Int =
            bookDao.finishedBooksCalls + dailyDao.allCalls +
                chapterDao.readCountCalls + achievementDao.allCalls

        fun resetScans() {
            bookDao.finishedBooksCalls = 0
            dailyDao.allCalls = 0
            chapterDao.readCountCalls = 0
            achievementDao.allCalls = 0
        }
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

    private fun session(minutes: Int) = ReadingSession(
        bookId = 1,
        startedAt = now.minusHours(2).toInstant().toEpochMilli(),
        endedAt = now.minusHours(1).toInstant().toEpochMilli(),
        activeMinutes = minutes,
        chaptersFinished = 0,
        wordsRead = minutes * 200L,
        focusBonusApplied = false
    )

    @Test
    fun `a second settle with no changes runs no library scan`() = runTest {
        val rig = Rig()
        rig.sessionDao.insert(session(minutes = 30))
        val svc = service(rig)

        val first = svc.settle()
        assertEquals(120L, first.expGained) // 30 min * 4 EXP
        assertTrue("first settle must run the full pass", rig.scans() > 0)

        rig.resetScans()
        val second = svc.settle()
        assertEquals(0L, second.expGained)
        assertEquals(
            "idle settle must skip finishedBooks/daily.all/readCount/achievements",
            0, rig.scans()
        )
        assertEquals(120L, rig.profileDao.get(1L)?.totalExp)
    }

    @Test
    fun `a new unprocessed session breaks the guard and pays`() = runTest {
        val rig = Rig()
        rig.sessionDao.insert(session(minutes = 30))
        val svc = service(rig)
        svc.settle()

        rig.resetScans()
        rig.sessionDao.insert(session(minutes = 10))
        val third = svc.settle()
        assertEquals(40L, third.expGained) // 10 min * 4 EXP
        assertTrue("changed settle must run the full pass", rig.scans() > 0)
        assertEquals(160L, rig.profileDao.get(1L)?.totalExp)

        // And it settles back into the guarded state.
        rig.resetScans()
        assertEquals(0L, svc.settle().expGained)
        assertEquals(0, rig.scans())
    }

    @Test
    fun `a newly finished book breaks the guard and pays`() = runTest {
        val rig = Rig()
        val svc = service(rig)
        svc.settle() // empty library: full pass, watermark recorded

        rig.resetScans()
        assertEquals(0L, svc.settle().expGained)
        assertEquals(0, rig.scans())

        rig.bookFake.insert(
            Book(title = "Done", addedAt = 1, updatedAt = 1, finished = true)
        )
        val result = svc.settle()
        assertEquals(200L, result.expGained)
        assertTrue(rig.scans() > 0)
    }
}
