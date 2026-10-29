package app.xreader.data.reader

import app.xreader.data.db.DailyActivity
import app.xreader.data.db.DailyActivityDao
import app.xreader.data.db.ReadingSession
import app.xreader.data.db.ReadingSessionDao
import app.xreader.domain.reader.SessionTracker
import app.xreader.domain.reader.WpmEstimator
import java.time.LocalDate
import java.time.ZoneId

/**
 * Bridges the pure [SessionTracker] to Room (PLAN §6): a finished
 * tracker snapshot becomes one `ReadingSession` row plus today's
 * `DailyActivity` accumulation (Phase 6's EXP engine consumes both),
 * and sessions across the library feed the shared [WpmEstimator] so
 * "≈time left" reflects how this user actually reads.
 */
class SessionRecorder(
    private val sessionDao: ReadingSessionDao,
    private val dailyDao: DailyActivityDao,
    private val zone: ZoneId = ZoneId.systemDefault()
) {
    val wpmEstimator = WpmEstimator()

    /** Seeds the estimator from recent stored sessions (app start). */
    suspend fun warmUp() {
        runCatching {
            sessionDao.recent(RECENT_SESSIONS)
                .filter { it.activeMinutes > 0 && it.wordsRead > 0 }
                .reversed()
                .forEach { session ->
                    wpmEstimator.addSample(
                        words = session.wordsRead.toInt(),
                        activeSeconds = session.activeMinutes * 60.0
                    )
                }
        }
    }

    suspend fun record(bookId: Long, snapshot: SessionTracker.Snapshot) {
        if (snapshot.activeMinutes <= 0 && snapshot.wordsRead <= 0L &&
            snapshot.chaptersFinished <= 0
        ) {
            return // an opened-and-closed reader writes nothing
        }
        sessionDao.insert(
            ReadingSession(
                bookId = bookId,
                startedAt = snapshot.startedAt,
                endedAt = snapshot.endedAt,
                activeMinutes = snapshot.activeMinutes,
                chaptersFinished = snapshot.chaptersFinished,
                wordsRead = snapshot.wordsRead,
                focusBonusApplied = false
            )
        )
        if (snapshot.activeMinutes > 0 && snapshot.wordsRead > 0) {
            wpmEstimator.addSample(
                words = snapshot.wordsRead.toInt(),
                activeSeconds = snapshot.activeMinutes * 60.0
            )
        }
        val date = LocalDate.ofInstant(
            java.time.Instant.ofEpochMilli(snapshot.endedAt), zone
        ).toString()
        val existing = dailyDao.get(date)
        dailyDao.upsert(
            DailyActivity(
                date = date,
                minutesRead = (existing?.minutesRead ?: 0) + snapshot.activeMinutes,
                chaptersFinished = (existing?.chaptersFinished ?: 0) +
                    snapshot.chaptersFinished,
                expEarned = existing?.expEarned ?: 0L,
                goalMet = existing?.goalMet ?: false
            )
        )
    }

    /** A completed focus session flags its bonus on the latest session
     * row for [bookId] (Phase 6 multiplies EXP from this flag). */
    suspend fun markFocusBonus(bookId: Long) {
        runCatching {
            val latest = sessionDao.recentForBookSync(bookId).firstOrNull() ?: return
            sessionDao.update(latest.copy(focusBonusApplied = true))
        }
    }

    companion object {
        const val RECENT_SESSIONS = 24
    }
}
