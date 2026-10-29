package app.xreader.data

import app.xreader.data.db.AiSummary
import app.xreader.data.db.AiSummaryDao
import app.xreader.data.db.Bookmark
import app.xreader.data.db.BookmarkDao
import app.xreader.data.db.DailyActivity
import app.xreader.data.db.DailyActivityDao
import app.xreader.data.db.Highlight
import app.xreader.data.db.HighlightDao
import app.xreader.data.db.LoreArt
import app.xreader.data.db.LoreArtDao
import app.xreader.data.db.LoreEntity
import app.xreader.data.db.LoreEntityDao
import app.xreader.data.db.LoreRelation
import app.xreader.data.db.LoreRelationDao
import app.xreader.data.db.Profile
import app.xreader.data.db.ProfileDao
import app.xreader.data.db.ReadingProgress
import app.xreader.data.db.ReadingProgressDao
import app.xreader.data.db.SyncState
import app.xreader.data.db.SyncStateDao
import app.xreader.data.db.TerminologyDao
import app.xreader.data.db.TerminologyEntry
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** In-memory fakes for the Phase 5 DAOs (AI + sync), same style as
 * [Fakes.kt]: they implement the Room interfaces with the semantics
 * the services rely on (REPLACE upserts, per-book filtering). */

class FakeLoreEntityDao : LoreEntityDao {
    private val ids = AtomicLong(1)
    val entities = MutableStateFlow<List<LoreEntity>>(emptyList())

    override suspend fun insert(entity: LoreEntity): Long {
        val id = ids.getAndIncrement()
        entities.value = entities.value + entity.copy(id = id)
        return id
    }

    override suspend fun update(entity: LoreEntity) {
        entities.value = entities.value.map { if (it.id == entity.id) entity else it }
    }

    override suspend fun delete(entity: LoreEntity) {
        entities.value = entities.value.filterNot { it.id == entity.id }
    }

    override fun observeForBook(bookId: Long): Flow<List<LoreEntity>> =
        entities.map { list -> list.filter { it.bookId == bookId } }

    override suspend fun forBook(bookId: Long): List<LoreEntity> =
        entities.value.filter { it.bookId == bookId }
}

class FakeLoreRelationDao : LoreRelationDao {
    private val ids = AtomicLong(1)
    val relations = MutableStateFlow<List<LoreRelation>>(emptyList())

    override suspend fun insert(relation: LoreRelation): Long {
        val id = ids.getAndIncrement()
        relations.value = relations.value + relation.copy(id = id)
        return id
    }

    override suspend fun delete(relation: LoreRelation) {
        relations.value = relations.value.filterNot { it.id == relation.id }
    }

    override fun observeForBook(bookId: Long): Flow<List<LoreRelation>> =
        relations.map { list -> list.filter { it.bookId == bookId } }

    override suspend fun forBook(bookId: Long): List<LoreRelation> =
        relations.value.filter { it.bookId == bookId }
}

class FakeLoreArtDao : LoreArtDao {
    private val ids = AtomicLong(1)
    val art = MutableStateFlow<List<LoreArt>>(emptyList())

    override suspend fun insert(entry: LoreArt): Long {
        val id = ids.getAndIncrement()
        art.value = art.value + entry.copy(id = id)
        return id
    }

    override suspend fun delete(entry: LoreArt) {
        art.value = art.value.filterNot { it.id == entry.id }
    }

    override fun observeForBook(bookId: Long): Flow<List<LoreArt>> =
        art.map { list -> list.filter { it.bookId == bookId } }
}

class FakeAiSummaryDao : AiSummaryDao {
    val summaries = MutableStateFlow<List<AiSummary>>(emptyList())

    override suspend fun upsert(summary: AiSummary) {
        val existing = summaries.value.firstOrNull {
            it.bookId == summary.bookId && it.chapterId == summary.chapterId
        }
        summaries.value = if (existing != null) {
            summaries.value.map {
                if (it.id == existing.id) summary.copy(id = existing.id) else it
            }
        } else {
            summaries.value + summary.copy(id = (summaries.value.maxOfOrNull { it.id } ?: 0) + 1)
        }
    }

    override suspend fun get(bookId: Long, chapterId: Long): AiSummary? =
        summaries.value.firstOrNull { it.bookId == bookId && it.chapterId == chapterId }

    override fun observe(bookId: Long, chapterId: Long): Flow<AiSummary?> =
        summaries.map { list ->
            list.firstOrNull { it.bookId == bookId && it.chapterId == chapterId }
        }

    override suspend fun forBook(bookId: Long): List<AiSummary> =
        summaries.value.filter { it.bookId == bookId }
}

class FakeTerminologyDao : TerminologyDao {
    private val ids = AtomicLong(1)
    val entries = MutableStateFlow<List<TerminologyEntry>>(emptyList())

    override suspend fun upsert(entry: TerminologyEntry) {
        val existing = entries.value.firstOrNull {
            it.bookId == entry.bookId && it.sourceTerm == entry.sourceTerm
        }
        if (existing != null) {
            entries.value = entries.value.map {
                if (it.id == existing.id) entry.copy(id = existing.id) else it
            }
        } else {
            entries.value = entries.value + entry.copy(id = ids.getAndIncrement())
        }
    }

    override suspend fun delete(entry: TerminologyEntry) {
        entries.value = entries.value.filterNot { it.id == entry.id }
    }

    override fun observeForBook(bookId: Long): Flow<List<TerminologyEntry>> =
        entries.map { list -> list.filter { it.bookId == bookId } }

    override suspend fun forBook(bookId: Long): List<TerminologyEntry> =
        entries.value.filter { it.bookId == bookId }

    override suspend fun getById(id: Long): TerminologyEntry? =
        entries.value.firstOrNull { it.id == id }

    override suspend fun update(entry: TerminologyEntry) {
        entries.value = entries.value.map { if (it.id == entry.id) entry else it }
    }
}

class FakeReadingProgressDao : ReadingProgressDao {
    val rows = MutableStateFlow<List<ReadingProgress>>(emptyList())

    override suspend fun upsert(progress: ReadingProgress) {
        rows.value = rows.value.filterNot { it.bookId == progress.bookId } + progress
    }

    override suspend fun getForBook(bookId: Long): ReadingProgress? =
        rows.value.firstOrNull { it.bookId == bookId }

    override fun observeForBook(bookId: Long): Flow<ReadingProgress?> =
        rows.map { list -> list.firstOrNull { it.bookId == bookId } }

    override fun observeRecent(): Flow<List<ReadingProgress>> =
        rows.map { list -> list.sortedByDescending { it.updatedAt } }

    override suspend fun allProgress(): List<ReadingProgress> = rows.value
}

class FakeBookmarkDao : BookmarkDao {
    private val ids = AtomicLong(1)
    val bookmarks = MutableStateFlow<List<Bookmark>>(emptyList())

    override suspend fun insert(bookmark: Bookmark): Long {
        val id = ids.getAndIncrement()
        bookmarks.value = bookmarks.value + bookmark.copy(id = id)
        return id
    }

    override suspend fun delete(bookmark: Bookmark) {
        bookmarks.value = bookmarks.value.filterNot { it.id == bookmark.id }
    }

    override fun observeForBook(bookId: Long): Flow<List<Bookmark>> =
        bookmarks.map { list -> list.filter { it.bookId == bookId } }

    override suspend fun forBook(bookId: Long): List<Bookmark> =
        bookmarks.value.filter { it.bookId == bookId }
}

class FakeHighlightDao : HighlightDao {
    private val ids = AtomicLong(1)
    val highlights = MutableStateFlow<List<Highlight>>(emptyList())

    override suspend fun insert(highlight: Highlight): Long {
        val id = ids.getAndIncrement()
        highlights.value = highlights.value + highlight.copy(id = id)
        return id
    }

    override suspend fun update(highlight: Highlight) {
        highlights.value = highlights.value.map { if (it.id == highlight.id) highlight else it }
    }

    override suspend fun delete(highlight: Highlight) {
        highlights.value = highlights.value.filterNot { it.id == highlight.id }
    }

    override fun observeForBook(bookId: Long): Flow<List<Highlight>> =
        highlights.map { list -> list.filter { it.bookId == bookId } }

    override suspend fun forBook(bookId: Long): List<Highlight> =
        highlights.value.filter { it.bookId == bookId }
}

class FakeProfileDao : ProfileDao {
    val row = MutableStateFlow<Profile?>(null)

    override suspend fun upsert(profile: Profile) {
        row.value = profile
    }

    override suspend fun get(id: Long): Profile? = row.value

    override fun observe(id: Long): Flow<Profile?> = row
}

class FakeDailyActivityDao : DailyActivityDao {
    val days = MutableStateFlow<List<DailyActivity>>(emptyList())

    override suspend fun upsert(day: DailyActivity) {
        days.value = days.value.filterNot { it.date == day.date } + day
    }

    override suspend fun get(date: String): DailyActivity? =
        days.value.firstOrNull { it.date == date }

    override fun observeRange(from: String, to: String): Flow<List<DailyActivity>> =
        days.map { list -> list.filter { it.date in from..to } }

    override suspend fun all(): List<DailyActivity> = days.value.sortedBy { it.date }
}

class FakeSyncStateDao : SyncStateDao {
    val rows = MutableStateFlow<List<SyncState>>(emptyList())

    override suspend fun upsert(state: SyncState) {
        val existing = rows.value.firstOrNull {
            it.entityType == state.entityType && it.entityId == state.entityId
        }
        rows.value = if (existing != null) {
            rows.value.map { if (it.id == existing.id) state.copy(id = existing.id) else it }
        } else {
            rows.value + state.copy(id = (rows.value.maxOfOrNull { it.id } ?: 0) + 1)
        }
    }

    override suspend fun dirtyRoots(): List<SyncState> = rows.value.filter { it.dirty }

    override suspend fun get(type: String, entityId: String): SyncState? =
        rows.value.firstOrNull { it.entityType == type && it.entityId == entityId }
}
