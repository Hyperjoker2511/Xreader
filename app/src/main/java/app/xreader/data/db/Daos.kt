package app.xreader.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Phase 1 DAOs: the storage verbs every later phase builds on. Richer
 * queries (shelf ordering, failover lookups, heatmap ranges) arrive with
 * the phases that need them — the schema is what this phase pours.
 */

@Dao
interface BookDao {
    @Insert suspend fun insert(book: Book): Long
    @Update suspend fun update(book: Book)
    @Delete suspend fun delete(book: Book)
    @Query("SELECT * FROM books WHERE id = :id") suspend fun getById(id: Long): Book?
    /** Phase 3: an extension book is identified by (extension, remote id). */
    @Query("SELECT * FROM books WHERE extensionId = :extensionId AND remoteId = :remoteId LIMIT 1")
    suspend fun findByExtensionRemote(extensionId: Long, remoteId: String): Book?
    @Query("SELECT * FROM books WHERE id = :id") fun observeById(id: Long): Flow<Book?>
    @Query(
        "SELECT * FROM books WHERE mergedIntoId IS NULL " +
            "ORDER BY COALESCE(lastOpenedAt, addedAt) DESC"
    )
    fun observeShelf(): Flow<List<Book>>
    @Query("SELECT COUNT(*) FROM books") suspend fun count(): Int
    /** v1.0.2: every book INCLUDING merged-away copies (the book
     * page's merge section needs to see what it absorbed). */
    @Query("SELECT * FROM books ORDER BY id")
    fun observeAllBooks(): Flow<List<Book>>
    /** Phase 5: sync gathers the whole library's metadata. */
    @Query("SELECT * FROM books ORDER BY id") suspend fun allBooks(): List<Book>
    /** Phase 5: sync matches a local book to its sync key. */
    @Query("SELECT * FROM books WHERE title = :title AND totalChapters = :chapters LIMIT 1")
    suspend fun findByTitleAndChapters(title: String, chapters: Int): Book?
    /** Phase 6: profile stats + the finished-book EXP event source. */
    @Query("SELECT COUNT(*) FROM books WHERE finished = 1") suspend fun finishedCount(): Int
    @Query("SELECT * FROM books WHERE finished = 1") suspend fun finishedBooks(): List<Book>
}

/** Per-book download tallies (v1.0.2, R19/R23): one grouped query
 * feeds every card's downloaded/total + unread-downloaded numbers
 * instead of a query per card. */
data class BookDownloadCount(
    val bookId: Long,
    val downloaded: Int,
    val readDownloaded: Int
)

@Dao
interface ChapterDao {
    @Insert suspend fun insert(chapter: Chapter): Long
    @Insert suspend fun insertAll(chapters: List<Chapter>)
    @Update suspend fun update(chapter: Chapter)
    @Delete suspend fun delete(chapter: Chapter)
    @Query("SELECT * FROM chapters WHERE id = :id") suspend fun getById(id: Long): Chapter?
    @Query("SELECT * FROM chapters WHERE bookId = :bookId ORDER BY `index` ASC")
    fun observeForBook(bookId: Long): Flow<List<Chapter>>
    @Query("SELECT * FROM chapters WHERE bookId = :bookId ORDER BY `index` ASC")
    suspend fun forBook(bookId: Long): List<Chapter>
    @Query("UPDATE chapters SET downloadState = :state WHERE id = :id")
    suspend fun setDownloadState(id: Long, state: DownloadState)
    @Query("UPDATE chapters SET contentPath = :path, wordCount = :words, downloadState = :state WHERE id = :id")
    suspend fun setContent(id: Long, path: String, words: Int, state: DownloadState)
    @Query("SELECT COUNT(*) FROM chapters WHERE bookId = :bookId AND downloadState = 'READY'")
    suspend fun readyCount(bookId: Long): Int

    /** v1.0.2 (sweep): bounded cross-library list of downloaded
     * chapters for search-inside-downloaded-books. */
    @Query(
        "SELECT * FROM chapters WHERE downloadState = 'READY' " +
            "ORDER BY bookId, `index` LIMIT :limit"
    )
    suspend fun getDownloadedChapters(limit: Int): List<Chapter>
    /** v1.0.2 (R19): all books' download tallies in one flow. */
    @Query(
        "SELECT bookId, " +
            "SUM(CASE WHEN downloadState = 'READY' THEN 1 ELSE 0 END) AS downloaded, " +
            "SUM(CASE WHEN downloadState = 'READY' AND read = 1 THEN 1 ELSE 0 END) " +
            "AS readDownloaded FROM chapters GROUP BY bookId"
    )
    fun observeDownloadCounts(): Flow<List<BookDownloadCount>>
    /** Phase 4: the reader marks chapters read as they're finished. */
    @Query("UPDATE chapters SET read = :read WHERE id = :id")
    suspend fun setRead(id: Long, read: Boolean)
    /** Phase 5: sync/AI address chapters by their position in the book. */
    @Query("SELECT * FROM chapters WHERE bookId = :bookId AND `index` = :index LIMIT 1")
    suspend fun getByIndex(bookId: Long, index: Int): Chapter?
    /** Phase 6: chapters actually finished, across the library
     * (the achievements' chapter counts read this, so migrated
     * read-state counts too). */
    @Query("SELECT COUNT(*) FROM chapters WHERE read = 1") suspend fun readCount(): Int
}

@Dao
interface ChapterSourceMapDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(map: ChapterSourceMap): Long
    @Delete suspend fun delete(map: ChapterSourceMap)
    @Query("SELECT * FROM chapter_source_map WHERE chapterId = :chapterId")
    suspend fun forChapter(chapterId: Long): List<ChapterSourceMap>
    @Query("SELECT * FROM chapter_source_map WHERE chapterId = :chapterId ORDER BY matchConfidence DESC")
    suspend fun alternatesFor(chapterId: Long): List<ChapterSourceMap>
    @Query("UPDATE chapter_source_map SET verified = 1 WHERE id = :id")
    suspend fun markVerified(id: Long)
}

@Dao
interface ReadingProgressDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(progress: ReadingProgress)
    @Query("SELECT * FROM reading_progress WHERE bookId = :bookId")
    suspend fun getForBook(bookId: Long): ReadingProgress?
    @Query("SELECT * FROM reading_progress WHERE bookId = :bookId")
    fun observeForBook(bookId: Long): Flow<ReadingProgress?>
    @Query("SELECT * FROM reading_progress ORDER BY updatedAt DESC")
    fun observeRecent(): Flow<List<ReadingProgress>>
    /** Phase 5: sync gathers every book's progress. */
    @Query("SELECT * FROM reading_progress") suspend fun allProgress(): List<ReadingProgress>
}

@Dao
interface BookmarkDao {
    @Insert suspend fun insert(bookmark: Bookmark): Long
    @Delete suspend fun delete(bookmark: Bookmark)
    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId ORDER BY chapterIndex, charOffset")
    fun observeForBook(bookId: Long): Flow<List<Bookmark>>
    /** Phase 5: sync gather/apply. */
    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId ORDER BY chapterIndex, charOffset")
    suspend fun forBook(bookId: Long): List<Bookmark>
}

@Dao
interface HighlightDao {
    @Insert suspend fun insert(highlight: Highlight): Long
    @Update suspend fun update(highlight: Highlight)
    @Delete suspend fun delete(highlight: Highlight)
    @Query("SELECT * FROM highlights WHERE bookId = :bookId ORDER BY chapterId, startOffset")
    fun observeForBook(bookId: Long): Flow<List<Highlight>>
    /** Phase 5: sync gather/apply. */
    @Query("SELECT * FROM highlights WHERE bookId = :bookId ORDER BY chapterId, startOffset")
    suspend fun forBook(bookId: Long): List<Highlight>
}

@Dao
interface OpdsServerDao {
    @Insert suspend fun insert(server: OpdsServer): Long
    @Update suspend fun update(server: OpdsServer)
    @Delete suspend fun delete(server: OpdsServer)
    @Query("SELECT * FROM opds_servers ORDER BY name") fun observeAll(): Flow<List<OpdsServer>>
    @Query("SELECT * FROM opds_servers WHERE id = :id") suspend fun getById(id: Long): OpdsServer?
    @Query("SELECT * FROM opds_servers WHERE id = :id") fun observeById(id: Long): Flow<OpdsServer?>
}

@Dao
interface ExtensionRepoDao {
    @Insert suspend fun insert(repo: ExtensionRepo): Long
    @Update suspend fun update(repo: ExtensionRepo)
    @Delete suspend fun delete(repo: ExtensionRepo)
    @Query("SELECT * FROM extension_repos WHERE id = :id")
    suspend fun getById(id: Long): ExtensionRepo?
    @Query("SELECT * FROM extension_repos ORDER BY name") fun observeAll(): Flow<List<ExtensionRepo>>
}

@Dao
interface InstalledExtensionDao {
    @Insert suspend fun insert(extension: InstalledExtension): Long
    @Update suspend fun update(extension: InstalledExtension)
    @Delete suspend fun delete(extension: InstalledExtension)
    @Query("SELECT * FROM installed_extensions WHERE packageId = :packageId")
    suspend fun getByPackageId(packageId: String): InstalledExtension?
    @Query("SELECT * FROM installed_extensions WHERE id = :id")
    suspend fun getById(id: Long): InstalledExtension?
    @Query("SELECT * FROM installed_extensions WHERE id = :id")
    fun observeById(id: Long): Flow<InstalledExtension?>
    @Query("SELECT * FROM installed_extensions ORDER BY displayName")
    fun observeAll(): Flow<List<InstalledExtension>>
    /** Phase 6: profile stats (the first-source achievement). */
    @Query("SELECT COUNT(*) FROM installed_extensions") suspend fun countAll(): Int
    @Query("SELECT * FROM installed_extensions ORDER BY displayName")
    suspend fun allNow(): List<InstalledExtension>
}

@Dao
interface DownloadQueueDao {
    @Insert suspend fun insert(item: DownloadQueueItem): Long
    @Update suspend fun update(item: DownloadQueueItem)
    @Delete suspend fun delete(item: DownloadQueueItem)
    @Query("SELECT * FROM download_queue ORDER BY enqueuedAt DESC")
    fun observeAll(): Flow<List<DownloadQueueItem>>
    @Query("SELECT * FROM download_queue WHERE state IN ('QUEUED','RUNNING') ORDER BY enqueuedAt")
    suspend fun active(): List<DownloadQueueItem>
    @Query("SELECT * FROM download_queue WHERE id = :id") suspend fun getById(id: Long): DownloadQueueItem?
    @Query("SELECT * FROM download_queue WHERE state = 'QUEUED' ORDER BY enqueuedAt LIMIT :limit")
    suspend fun nextQueued(limit: Int): List<DownloadQueueItem>
    @Query("SELECT * FROM download_queue WHERE bookId = :bookId ORDER BY enqueuedAt")
    fun observeForBook(bookId: Long): Flow<List<DownloadQueueItem>>
    @Query("SELECT * FROM download_queue WHERE bookId = :bookId AND state IN ('QUEUED','RUNNING','PAUSED')")
    suspend fun activeForBook(bookId: Long): List<DownloadQueueItem>
    @Query("UPDATE download_queue SET state = 'QUEUED', lastErrorDetail = NULL WHERE state = 'RUNNING'")
    suspend fun resetRunningToQueued(): Int
}

@Dao
interface LoreEntityDao {
    @Insert suspend fun insert(entity: LoreEntity): Long
    @Update suspend fun update(entity: LoreEntity)
    @Delete suspend fun delete(entity: LoreEntity)
    @Query("SELECT * FROM lore_entities WHERE bookId = :bookId ORDER BY type, name")
    fun observeForBook(bookId: Long): Flow<List<LoreEntity>>
    /** Phase 5: extraction merge + chat context + sync. */
    @Query("SELECT * FROM lore_entities WHERE bookId = :bookId ORDER BY type, name")
    suspend fun forBook(bookId: Long): List<LoreEntity>
}

@Dao
interface LoreRelationDao {
    @Insert suspend fun insert(relation: LoreRelation): Long
    @Delete suspend fun delete(relation: LoreRelation)
    @Query("SELECT * FROM lore_relations WHERE bookId = :bookId")
    fun observeForBook(bookId: Long): Flow<List<LoreRelation>>
    /** Phase 5: extraction merge + sync. */
    @Query("SELECT * FROM lore_relations WHERE bookId = :bookId")
    suspend fun forBook(bookId: Long): List<LoreRelation>
}

@Dao
interface LoreArtDao {
    @Insert suspend fun insert(art: LoreArt): Long
    @Delete suspend fun delete(art: LoreArt)
    @Query("SELECT * FROM lore_art WHERE bookId = :bookId ORDER BY createdAt DESC")
    fun observeForBook(bookId: Long): Flow<List<LoreArt>>
}

@Dao
interface AiSummaryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(summary: AiSummary)
    @Query("SELECT * FROM ai_summaries WHERE bookId = :bookId AND chapterId = :chapterId")
    suspend fun get(bookId: Long, chapterId: Long): AiSummary?
    /** Phase 5: the reader's chapter sheet shows the cached summary. */
    @Query("SELECT * FROM ai_summaries WHERE bookId = :bookId AND chapterId = :chapterId")
    fun observe(bookId: Long, chapterId: Long): Flow<AiSummary?>
    /** Phase 5: sync gather. */
    @Query("SELECT * FROM ai_summaries WHERE bookId = :bookId")
    suspend fun forBook(bookId: Long): List<AiSummary>
}

@Dao
interface TerminologyDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(entry: TerminologyEntry)
    @Delete suspend fun delete(entry: TerminologyEntry)
    @Query("SELECT * FROM terminology WHERE bookId = :bookId ORDER BY sourceTerm")
    fun observeForBook(bookId: Long): Flow<List<TerminologyEntry>>
    /** Phase 5: translation prompt + sync. */
    @Query("SELECT * FROM terminology WHERE bookId = :bookId ORDER BY sourceTerm")
    suspend fun forBook(bookId: Long): List<TerminologyEntry>
    @Query("SELECT * FROM terminology WHERE id = :id") suspend fun getById(id: Long): TerminologyEntry?
    @Update suspend fun update(entry: TerminologyEntry)
}

@Dao
interface ProfileDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(profile: Profile)
    @Query("SELECT * FROM profile WHERE id = :id") suspend fun get(id: Long = Profile.SINGLETON_ID): Profile?
    @Query("SELECT * FROM profile WHERE id = :id") fun observe(id: Long = Profile.SINGLETON_ID): Flow<Profile?>
}

@Dao
interface ReadingSessionDao {
    @Insert suspend fun insert(session: ReadingSession): Long
    @Update suspend fun update(session: ReadingSession)
    @Query("SELECT * FROM reading_sessions WHERE bookId = :bookId ORDER BY startedAt DESC")
    fun observeForBook(bookId: Long): Flow<List<ReadingSession>>
    /** Phase 4: WPM warm-up + focus-bonus flagging. */
    @Query("SELECT * FROM reading_sessions ORDER BY startedAt DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<ReadingSession>
    @Query("SELECT * FROM reading_sessions WHERE bookId = :bookId ORDER BY startedAt DESC")
    suspend fun recentForBookSync(bookId: Long): List<ReadingSession>
    /** Phase 6: sessions the EXP engine hasn't paid out yet. */
    @Query("SELECT * FROM reading_sessions WHERE expProcessed = 0 ORDER BY startedAt ASC")
    suspend fun unprocessed(): List<ReadingSession>
    /** Phase 6: lifetime totals for the Profile stats + achievements. */
    @Query("SELECT COALESCE(SUM(activeMinutes), 0) FROM reading_sessions")
    suspend fun totalActiveMinutes(): Int
    @Query("SELECT COALESCE(SUM(wordsRead), 0) FROM reading_sessions")
    suspend fun totalWordsRead(): Long
    @Query("SELECT COUNT(*) FROM reading_sessions WHERE focusBonusApplied = 1")
    suspend fun focusSessionCount(): Int
}

@Dao
interface DailyActivityDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(day: DailyActivity)
    @Query("SELECT * FROM daily_activity WHERE date = :date") suspend fun get(date: String): DailyActivity?
    @Query("SELECT * FROM daily_activity WHERE date BETWEEN :from AND :to ORDER BY date")
    fun observeRange(from: String, to: String): Flow<List<DailyActivity>>
    /** Phase 5: gamification state joins the sync payload. */
    @Query("SELECT * FROM daily_activity ORDER BY date") suspend fun all(): List<DailyActivity>
}

@Dao
interface SyncStateDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(state: SyncState)
    @Query("SELECT * FROM sync_state WHERE dirty = 1") suspend fun dirtyRoots(): List<SyncState>
    @Query("SELECT * FROM sync_state WHERE entityType = :type AND entityId = :entityId")
    suspend fun get(type: String, entityId: String): SyncState?
}

@Dao
interface ExpEventDao {
    /** IGNORE is the idempotency: returns -1 when the event was
     * already paid, so callers award EXP only on a real insert. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(event: ExpEvent): Long
    @Query("SELECT * FROM exp_events WHERE eventKey = :key")
    suspend fun get(key: String): ExpEvent?
    @Query("SELECT * FROM exp_events ORDER BY createdAt")
    suspend fun all(): List<ExpEvent>
}

@Dao
interface AchievementDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(unlock: AchievementUnlock): Long
    @Query("SELECT * FROM achievements ORDER BY unlockedAt")
    suspend fun all(): List<AchievementUnlock>
    @Query("SELECT * FROM achievements ORDER BY unlockedAt")
    fun observeAll(): Flow<List<AchievementUnlock>>
}

@Dao
interface ShelfDao {
    @Insert suspend fun insert(shelf: Shelf): Long
    @Update suspend fun update(shelf: Shelf)
    @Delete suspend fun delete(shelf: Shelf)
    @Query("SELECT * FROM shelves ORDER BY sortOrder, name")
    fun observeAll(): Flow<List<Shelf>>
    @Query("SELECT * FROM shelves ORDER BY sortOrder, name")
    suspend fun all(): List<Shelf>
    @Query("SELECT * FROM shelves WHERE id = :id") suspend fun getById(id: Long): Shelf?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addBook(entry: ShelfBook)
    @Query("DELETE FROM shelf_books WHERE shelfId = :shelfId AND bookId = :bookId")
    suspend fun removeBook(shelfId: Long, bookId: Long)
    @Query("SELECT * FROM shelf_books")
    fun observeMemberships(): Flow<List<ShelfBook>>
    @Query("SELECT bookId FROM shelf_books WHERE shelfId = :shelfId")
    suspend fun bookIdsOn(shelfId: Long): List<Long>
}

@Dao
interface TagDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertTag(tag: Tag)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertTags(tags: List<Tag>)
    @Query("SELECT * FROM tags ORDER BY displayName") fun observeTags(): Flow<List<Tag>>
    @Query("SELECT * FROM tags ORDER BY displayName") suspend fun allTags(): List<Tag>
    @Query("SELECT * FROM tags WHERE slug = :slug") suspend fun tag(slug: String): Tag?

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertBookTag(row: BookTag)
    @Query("SELECT * FROM book_tags WHERE bookId = :bookId")
    fun observeForBook(bookId: Long): Flow<List<BookTag>>
    @Query("SELECT * FROM book_tags WHERE bookId = :bookId")
    suspend fun forBook(bookId: Long): List<BookTag>
    @Query("SELECT * FROM book_tags WHERE slug = :slug AND suppressed = 0")
    suspend fun booksWithTag(slug: String): List<BookTag>
    @Query("SELECT * FROM book_tags WHERE suppressed = 0")
    fun observeAllBookTags(): Flow<List<BookTag>>
    @Query("DELETE FROM book_tags WHERE bookId = :bookId AND slug = :slug")
    suspend fun removeBookTag(bookId: Long, slug: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun follow(follow: TagFollow)
    @Query("DELETE FROM tag_follows WHERE slug = :slug") suspend fun unfollow(slug: String)
    @Query("SELECT * FROM tag_follows") fun observeFollows(): Flow<List<TagFollow>>
    @Query("SELECT * FROM tag_follows") suspend fun allFollows(): List<TagFollow>
}

@Dao
interface VocabDao {
    @Insert suspend fun insert(word: VocabWord): Long
    @Update suspend fun update(word: VocabWord)
    @Delete suspend fun delete(word: VocabWord)
    @Query("SELECT * FROM vocab_words ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<VocabWord>>
    @Query("SELECT * FROM vocab_words ORDER BY createdAt DESC")
    suspend fun all(): List<VocabWord>
    @Query("SELECT * FROM vocab_words WHERE lower(word) = lower(:word) LIMIT 1")
    suspend fun findByWord(word: String): VocabWord?
    @Query("SELECT * FROM vocab_words WHERE dueAt <= :nowMillis AND state != 'KNOWN' ORDER BY dueAt")
    suspend fun due(nowMillis: Long): List<VocabWord>
    @Query("SELECT COUNT(*) FROM vocab_words") suspend fun count(): Int
}
