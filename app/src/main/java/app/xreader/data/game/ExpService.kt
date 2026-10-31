package app.xreader.data.game

import app.xreader.data.db.AchievementDao
import app.xreader.data.db.AchievementUnlock
import app.xreader.data.db.BookDao
import app.xreader.data.db.ChapterDao
import app.xreader.data.db.DailyActivityDao
import app.xreader.data.db.ExpEvent
import app.xreader.data.db.ExpEventDao
import app.xreader.data.db.InstalledExtensionDao
import app.xreader.data.db.Profile
import app.xreader.data.db.ProfileDao
import app.xreader.data.db.ReadingSessionDao
import app.xreader.domain.game.Achievements
import app.xreader.domain.game.ExpAwards
import app.xreader.domain.game.ExpCurve
import app.xreader.domain.game.GameStats
import app.xreader.domain.game.Streaks
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.first

/**
 * The EXP engine's Room-facing edge (PLAN §8). Everything the
 * reader earns flows through [settle], which is safe to call any
 * number of times, from anywhere, because every award is guarded:
 *
 *  - sessions pay out once — the row's `expProcessed` flag flips in
 *    the same pass that pays;
 *  - one-time awards (finished book, met daily goal) pay once —
 *    the `exp_events` ledger's INSERT OR IGNORE returns -1 when the
 *    event was already paid, and only a real insert adds EXP;
 *  - achievement unlocks insert-or-ignore against derived
 *    conditions, so a sync or restore re-derives the same set.
 *
 * EXP lands in two places that must agree: the profile's lifetime
 * `totalExp` (level derives from it — single source) and the day
 * row's `expEarned` (the day the reading happened, in the device's
 * local zone — the same bucket SessionRecorder wrote).
 */
class ExpService(
    private val profileDao: ProfileDao,
    private val sessionDao: ReadingSessionDao,
    private val dailyDao: DailyActivityDao,
    private val bookDao: BookDao,
    private val chapterDao: ChapterDao,
    private val extensionDao: InstalledExtensionDao,
    private val expEventDao: ExpEventDao,
    private val achievementDao: AchievementDao,
    private val loreChatUsedProvider: suspend () -> Boolean,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val clock: () -> Long = System::currentTimeMillis
) {

    /** What one settle pass did — the UI celebrates [newAchievements]
     * and [goalJustMet] and otherwise stays quiet. */
    data class SettleResult(
        val expGained: Long = 0,
        val newAchievements: List<String> = emptyList(),
        val goalJustMet: Boolean = false,
        val levelBefore: Int = 1,
        val levelAfter: Int = 1
    )

    suspend fun ensureProfile(): Profile {
        profileDao.get()?.let { return it }
        val created = Profile(createdAt = clock())
        profileDao.upsert(created)
        return created
    }

    /**
     * v1.2.0 (audit D12): the last full settle's change watermark.
     * In-memory only — a process restart simply pays one full
     * settle, which is always safe (every award is ledger- /
     * flag-guarded). The fields are the cheap COUNTs the guard
     * below compares against; [settle] refreshes it at the end of
     * every full pass from the stats it already computed.
     */
    private data class SettleWatermark(
        val finishedBooks: Int,
        val totalBooks: Int,
        val sourcesInstalled: Int,
        val profileTotalExp: Long,
        val profileLevel: Int
    )

    @Volatile private var lastSettleWatermark: SettleWatermark? = null

    suspend fun settle(): SettleResult {
        var profile = ensureProfile()
        val levelBefore = profile.level

        // 1) Sessions: pay each unprocessed session exactly once.
        val unprocessed = sessionDao.unprocessed()

        // v1.2.0 (audit D12): change-driven guard. The full pass
        // below re-scans finished books, ALL daily rows and eight
        // aggregates — on every app start, reader exit and Profile
        // open, even when nothing changed. When (a) no session is
        // waiting to be paid, (b) the finished/total book counts
        // and the installed-source count match the watermark, and
        // (c) the profile's banked EXP and level are exactly what
        // the last full pass left (a sync that raised totalExp
        // without touching level breaks this and falls through to
        // the level-repair in step 4), this settle is a no-op and
        // returns without the scans. Daily-goal changes are
        // covered transitively: minutes only reach a day row via
        // SessionRecorder, which always leaves an unprocessed
        // session row behind it, and settle's own addToDay runs
        // inside the full pass. Payout correctness never depends
        // on the guard: the ledger and expProcessed flags still
        // guard every award on the full path. The COUNT queries
        // the guard itself pays are single-row aggregates, two of
        // them index-backed after DB v6 (books.finished).
        val watermark = lastSettleWatermark
        if (unprocessed.isEmpty() && watermark != null &&
            profile.totalExp == watermark.profileTotalExp &&
            profile.level == watermark.profileLevel &&
            profile.level == ExpCurve.levelFor(profile.totalExp) &&
            bookDao.finishedCount() == watermark.finishedBooks &&
            bookDao.count() == watermark.totalBooks &&
            extensionDao.countAll() == watermark.sourcesInstalled
        ) {
            return SettleResult(
                levelBefore = levelBefore,
                levelAfter = profile.level
            )
        }
        var gained = 0L
        var goalJustMet = false

        for (session in unprocessed) {
            val endedAt = session.endedAt ?: session.startedAt
            val wallMinutes = ((endedAt - session.startedAt) / 60_000L)
                .toInt().coerceAtLeast(session.activeMinutes)
            val exp = ExpAwards.sessionExp(
                activeMinutes = session.activeMinutes,
                wallMinutes = wallMinutes,
                chaptersFinished = session.chaptersFinished,
                focusBonus = session.focusBonusApplied
            )
            sessionDao.update(session.copy(expProcessed = true))
            if (exp > 0) {
                gained += exp
                addToDay(dayOf(endedAt), exp)
            }
        }

        // 2) Finished books: one bonus each, ledger-guarded.
        for (book in bookDao.finishedBooks()) {
            val inserted = expEventDao.insert(
                ExpEvent(
                    eventKey = "book:${book.id}",
                    exp = ExpAwards.EXP_BOOK_FINISHED,
                    createdAt = clock()
                )
            )
            if (inserted >= 0) gained += ExpAwards.EXP_BOOK_FINISHED
        }

        // 3) Daily goals: a day whose minutes reached the goal pays
        // once and flips goalMet (the Profile ring + heatmap read it).
        val goal = profile.dailyGoalMinutes.coerceAtLeast(1)
        for (day in dailyDao.all()) {
            if (day.goalMet || day.minutesRead < goal) continue
            val inserted = expEventDao.insert(
                ExpEvent(
                    eventKey = "goal:${day.date}",
                    exp = ExpAwards.EXP_DAILY_GOAL,
                    createdAt = clock()
                )
            )
            dailyDao.upsert(day.copy(goalMet = true, expEarned = day.expEarned +
                if (inserted >= 0) ExpAwards.EXP_DAILY_GOAL else 0L))
            if (inserted >= 0) {
                gained += ExpAwards.EXP_DAILY_GOAL
                if (day.date == LocalDate.ofInstant(java.time.Instant.ofEpochMilli(clock()), zone).toString()) goalJustMet = true
            }
        }

        // 4) Bank the EXP and re-derive the level from the total.
        if (gained > 0) {
            val newTotal = profile.totalExp + gained
            profile = profile.copy(
                totalExp = newTotal,
                level = ExpCurve.levelFor(newTotal)
            )
            profileDao.upsert(profile)
        } else if (profile.level != ExpCurve.levelFor(profile.totalExp)) {
            // A sync may have raised totalExp without touching level.
            profile = profile.copy(level = ExpCurve.levelFor(profile.totalExp))
            profileDao.upsert(profile)
        }

        // 5) Achievements: derive from records, record the new ones.
        val stats = buildStats(profile)
        val earned = Achievements.unlockedIds(stats)
        val known = achievementDao.all().map { it.id }.toSet()
        val fresh = earned - known
        for (id in fresh) {
            achievementDao.insert(AchievementUnlock(id = id, unlockedAt = clock()))
        }

        // v1.2.0 (D12): this full pass leaves the library in the
        // settled state its stats describe — record the watermark
        // the next settle's guard compares against (no extra
        // queries: buildStats already produced every number).
        lastSettleWatermark = SettleWatermark(
            finishedBooks = stats.booksFinished,
            totalBooks = stats.booksTotal,
            sourcesInstalled = stats.sourcesInstalled,
            profileTotalExp = profile.totalExp,
            profileLevel = profile.level
        )

        return SettleResult(
            expGained = gained,
            newAchievements = Achievements.ALL
                .filter { it.id in fresh }
                .map { it.id },
            goalJustMet = goalJustMet,
            levelBefore = levelBefore,
            levelAfter = profile.level
        )
    }

    /** The full stats snapshot (Profile screen + achievements). */
    suspend fun stats(): GameStats = buildStats(ensureProfile())

    private suspend fun buildStats(profile: Profile): GameStats {
        val days = dailyDao.all()
        val activeDates = days
            .filter { it.minutesRead > 0 }
            .mapNotNull { runCatching { LocalDate.parse(it.date) }.getOrNull() }
            .toSet()
        return GameStats(
            booksTotal = bookDao.count(),
            booksFinished = bookDao.finishedCount(),
            chaptersFinished = chapterDao.readCount(),
            totalActiveMinutes = sessionDao.totalActiveMinutes(),
            totalWordsRead = sessionDao.totalWordsRead(),
            currentStreak = Streaks.currentStreak(activeDates, LocalDate.ofInstant(java.time.Instant.ofEpochMilli(clock()), zone)),
            bestStreak = Streaks.bestStreak(activeDates),
            focusSessions = sessionDao.focusSessionCount(),
            level = profile.level,
            sourcesInstalled = extensionDao.countAll(),
            loreChatUsed = loreChatUsedProvider(),
            goalDaysMet = days.count { it.goalMet }
        )
    }

    private fun dayOf(epochMillis: Long): String =
        LocalDate.ofInstant(Instant.ofEpochMilli(epochMillis), zone).toString()

    private suspend fun addToDay(dateIso: String, exp: Long) {
        val existing = dailyDao.get(dateIso)
        if (existing == null) {
            // A session from a device-clock edge can predate any
            // activity row; the day still deserves its EXP record.
            dailyDao.upsert(
                app.xreader.data.db.DailyActivity(
                    date = dateIso,
                    expEarned = exp
                )
            )
        } else {
            dailyDao.upsert(existing.copy(expEarned = existing.expEarned + exp))
        }
    }

    companion object {
        /** The Flow-based convenience the container wires: the
         * lore-chat flag comes from [app.xreader.data.prefs.GamePrefs]. */
        fun loreChatProvider(prefs: app.xreader.data.prefs.GamePrefs): suspend () -> Boolean =
            { prefs.loreChatUsed.first() }
    }
}
