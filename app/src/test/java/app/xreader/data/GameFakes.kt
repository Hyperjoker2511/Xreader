package app.xreader.data

import app.xreader.data.db.AchievementDao
import app.xreader.data.db.AchievementUnlock
import app.xreader.data.db.ExpEvent
import app.xreader.data.db.ExpEventDao
import app.xreader.data.db.ReadingSession
import app.xreader.data.db.ReadingSessionDao
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** Phase 6 test doubles for the game DAOs (PLAN §8). */
class FakeReadingSessionDao : ReadingSessionDao {
    private val ids = AtomicLong(1)
    val sessions = MutableStateFlow<List<ReadingSession>>(emptyList())

    override suspend fun insert(session: ReadingSession): Long {
        val id = if (session.id == 0L) ids.getAndIncrement() else session.id
        sessions.value = sessions.value + session.copy(id = id)
        return id
    }

    override suspend fun update(session: ReadingSession) {
        sessions.value = sessions.value.map {
            if (it.id == session.id) session else it
        }
    }

    override fun observeForBook(bookId: Long): Flow<List<ReadingSession>> =
        sessions.map { list -> list.filter { it.bookId == bookId } }

    override suspend fun recent(limit: Int): List<ReadingSession> =
        sessions.value.sortedByDescending { it.startedAt }.take(limit)

    override suspend fun recentForBookSync(bookId: Long): List<ReadingSession> =
        sessions.value.filter { it.bookId == bookId }
            .sortedByDescending { it.startedAt }

    override suspend fun unprocessed(): List<ReadingSession> =
        sessions.value.filter { !it.expProcessed }.sortedBy { it.startedAt }

    override suspend fun totalActiveMinutes(): Int =
        sessions.value.sumOf { it.activeMinutes }

    override suspend fun totalWordsRead(): Long =
        sessions.value.sumOf { it.wordsRead }

    override suspend fun focusSessionCount(): Int =
        sessions.value.count { it.focusBonusApplied }
}

class FakeExpEventDao : ExpEventDao {
    val events = MutableStateFlow<List<ExpEvent>>(emptyList())

    override suspend fun insert(event: ExpEvent): Long {
        if (events.value.any { it.eventKey == event.eventKey }) return -1L
        events.value = events.value + event
        return events.value.size.toLong()
    }

    override suspend fun get(key: String): ExpEvent? =
        events.value.firstOrNull { it.eventKey == key }

    override suspend fun all(): List<ExpEvent> = events.value
}

class FakeAchievementDao : AchievementDao {
    val rows = MutableStateFlow<List<AchievementUnlock>>(emptyList())

    override suspend fun insert(unlock: AchievementUnlock): Long {
        if (rows.value.any { it.id == unlock.id }) return -1L
        rows.value = rows.value + unlock
        return rows.value.size.toLong()
    }

    override suspend fun all(): List<AchievementUnlock> = rows.value

    override fun observeAll(): Flow<List<AchievementUnlock>> = rows
}
