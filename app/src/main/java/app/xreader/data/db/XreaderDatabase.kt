package app.xreader.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * Xreader's database (PLAN §1.3). v1 poured every table the six
 * phases planned; v2 (Phase 6) adds the EXP engine's bookkeeping —
 * an `expProcessed` flag on sessions plus the one-time-award ledger
 * and the achievement-unlock log — via [MIGRATION_1_2]. Chapter
 * text, secrets, and book files NEVER live here (§1.3, §1.4) — this
 * database is indexes, state, and memory only.
 */
@Database(
    entities = [
        Book::class,
        Chapter::class,
        ChapterSourceMap::class,
        ReadingProgress::class,
        Bookmark::class,
        Highlight::class,
        OpdsServer::class,
        ExtensionRepo::class,
        InstalledExtension::class,
        DownloadQueueItem::class,
        LoreEntity::class,
        LoreRelation::class,
        LoreArt::class,
        AiSummary::class,
        TerminologyEntry::class,
        Profile::class,
        ReadingSession::class,
        DailyActivity::class,
        SyncState::class,
        ExpEvent::class,
        AchievementUnlock::class,
        Shelf::class,
        ShelfBook::class,
        Tag::class,
        BookTag::class,
        TagFollow::class,
        VocabWord::class
    ],
    version = 5,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class XreaderDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun chapterDao(): ChapterDao
    abstract fun chapterSourceMapDao(): ChapterSourceMapDao
    abstract fun readingProgressDao(): ReadingProgressDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun highlightDao(): HighlightDao
    abstract fun opdsServerDao(): OpdsServerDao
    abstract fun extensionRepoDao(): ExtensionRepoDao
    abstract fun installedExtensionDao(): InstalledExtensionDao
    abstract fun downloadQueueDao(): DownloadQueueDao
    abstract fun loreEntityDao(): LoreEntityDao
    abstract fun loreRelationDao(): LoreRelationDao
    abstract fun loreArtDao(): LoreArtDao
    abstract fun aiSummaryDao(): AiSummaryDao
    abstract fun terminologyDao(): TerminologyDao
    abstract fun profileDao(): ProfileDao
    abstract fun readingSessionDao(): ReadingSessionDao
    abstract fun dailyActivityDao(): DailyActivityDao
    abstract fun syncStateDao(): SyncStateDao
    abstract fun expEventDao(): ExpEventDao
    abstract fun achievementDao(): AchievementDao
    abstract fun shelfDao(): ShelfDao
    abstract fun tagDao(): TagDao
    abstract fun vocabDao(): VocabDao

    companion object {
        const val NAME = "xreader.db"

        /** The table names the current schema must contain — the
         * schema smoke test pins this list against the generated
         * schema (PLAN §3 gate; v2 added the Phase 6 game tables). */
        val EXPECTED_TABLES = setOf(
            "books", "chapters", "chapter_source_map", "reading_progress",
            "bookmarks", "highlights", "opds_servers", "extension_repos",
            "installed_extensions", "download_queue", "lore_entities",
            "lore_relations", "lore_art", "ai_summaries", "terminology",
            "profile", "reading_sessions", "daily_activity", "sync_state",
            "exp_events", "achievements",
            // v3 (v1.0.2): library organisation — shelves, tags, vocab.
            "shelves", "shelf_books", "tags", "book_tags", "tag_follows",
            "vocab_words"
        )
    }
}
