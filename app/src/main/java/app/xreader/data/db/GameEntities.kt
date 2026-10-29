package app.xreader.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Gamification + sync tables (PLAN §1.3). */

/** Single-row profile (id is always [SINGLETON_ID]). */
@Entity(tableName = "profile")
data class Profile(
    @PrimaryKey val id: Long = SINGLETON_ID,
    val displayName: String = "",
    val avatarPath: String? = null,
    val totalExp: Long = 0,
    val level: Int = 1,
    val dailyGoalMinutes: Int = 20,
    val createdAt: Long
) {
    companion object {
        const val SINGLETON_ID = 1L
    }
}

@Entity(
    tableName = "reading_sessions",
    foreignKeys = [
        ForeignKey(
            entity = Book::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("bookId")]
)
data class ReadingSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val startedAt: Long,
    val endedAt: Long? = null,
    val activeMinutes: Int = 0,
    val chaptersFinished: Int = 0,
    val wordsRead: Long = 0,
    val focusBonusApplied: Boolean = false,
    /** Phase 6: set once the EXP engine has paid this session out.
     * The flag is the idempotency key — a session can be *seen* by
     * the engine many times but is only ever *paid* once. */
    val expProcessed: Boolean = false
)

/** One row per local calendar day — the streak heatmap reads this (§8). */
@Entity(tableName = "daily_activity")
data class DailyActivity(
    /** Local date, ISO `yyyy-MM-dd`. */
    @PrimaryKey val date: String,
    val minutesRead: Int = 0,
    val chaptersFinished: Int = 0,
    val expEarned: Long = 0,
    val goalMet: Boolean = false
)

/** One row per syncable root (Phase 5 fills it): what changed, when it
 * last synced, and the remote revision it is based on. */
@Entity(
    tableName = "sync_state",
    indices = [Index(value = ["entityType", "entityId"], unique = true)]
)
data class SyncState(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val entityType: String,
    val entityId: String,
    val remoteRevision: String? = null,
    val lastSyncedAt: Long? = null,
    val dirty: Boolean = true
)

/**
 * Phase 6: one-time EXP awards that aren't sessions — a finished
 * book, a met daily goal. [eventKey] is the idempotency key
 * (`book:<id>`, `goal:<date>`): INSERT OR IGNORE means the award
 * pass can re-run freely and never pays twice.
 */
@Entity(tableName = "exp_events")
data class ExpEvent(
    @PrimaryKey val eventKey: String,
    val exp: Long,
    val createdAt: Long
)

/** Phase 6: when each achievement was first unlocked. Unlock
 * *conditions* are derived from records (never stored); this table
 * only remembers the moment, so "new!" badges and the Profile
 * screen's dates survive restarts. */
@Entity(tableName = "achievements")
data class AchievementUnlock(
    @PrimaryKey val id: String,
    val unlockedAt: Long
)
