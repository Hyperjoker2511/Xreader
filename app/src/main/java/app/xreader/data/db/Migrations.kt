package app.xreader.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Manual migrations (the build.gradle schema-export comment made
 * this the rule from day one: every schema change ships a tested
 * manual migration, never a destructive fallback).
 *
 * 1 → 2 (Phase 6, gamification): sessions learn whether the EXP
 * engine has paid them out; two small tables appear — the one-time
 * award ledger and the achievement-unlock log.
 */
val MIGRATION_1_2: Migration = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE `reading_sessions` ADD COLUMN `expProcessed` " +
                "INTEGER NOT NULL DEFAULT 0"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `exp_events` (" +
                "`eventKey` TEXT NOT NULL, " +
                "`exp` INTEGER NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`eventKey`))"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `achievements` (" +
                "`id` TEXT NOT NULL, " +
                "`unlockedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`))"
        )
    }
}

/**
 * 2 → 3 (v1.0.2): library organisation. Books gain the reversible
 * merge pointer; six small tables appear — user shelves and their
 * memberships, the canonical tag registry, per-book tag rows (with
 * user-correction tombstones), tag follows, and saved vocabulary.
 * No existing table loses or rewrites a row; every statement is
 * additive. Runs rehearsed on the v2 schema shape by the gate's
 * migration fixtures (Stage 3).
 */
/**
 * 3 → 4 (v1.0.6.3): chapters learn the release date their source
 * printed (novelight's DD.MM.YYYY row dates) so the book page can
 * show "3 days ago" and a latest-chapter freshness line. One
 * nullable TEXT column; no row is rewritten.
 */
val MIGRATION_3_4: Migration = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `chapters` ADD COLUMN `dateText` TEXT")
    }
}

/**
 * 4 → 5 (v1.0.8): books carry their progressive chapter-list
 * state — whether the stored list is complete, and the walk
 * cursor (next site page + the site's page count) that lets a
 * partially loaded list resume exactly where it stopped.
 * Existing books were fully synced by construction, so the
 * completeness flag defaults to 1 (true) and the cursors to 0.
 */
val MIGRATION_4_5: Migration = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE `books` ADD COLUMN `chapterListComplete` " +
                "INTEGER NOT NULL DEFAULT 1"
        )
        db.execSQL(
            "ALTER TABLE `books` ADD COLUMN `chapterWalkNextPage` " +
                "INTEGER NOT NULL DEFAULT 0"
        )
        db.execSQL(
            "ALTER TABLE `books` ADD COLUMN `chapterWalkTotalPages` " +
                "INTEGER NOT NULL DEFAULT 0"
        )
        db.execSQL(
            "ALTER TABLE `books` ADD COLUMN `chapterWalkPageSize` " +
                "INTEGER NOT NULL DEFAULT 0"
        )
    }
}

/**
 * 5 → 6 (v1.2.0, audit D8 + D15): the index bundle. No table is
 * created, dropped or rewritten — only indices are added, matching
 * the @Entity indices declarations exactly (Room validates the
 * post-migration schema against them):
 *  - books: (extensionId, remoteId) for the catalogue-tap lookup,
 *    (mergedIntoId, lastOpenedAt) for the shelf filter/sort,
 *    (title) for migration matching, (finished) for the EXP
 *    engine's finished-book scans;
 *  - chapters: (downloadState, bookId, index) for the
 *    cross-library downloaded-chapters search;
 *  - reading_progress: (updatedAt) for observeRecent's sort.
 * (The remaining D15 candidates — reading_sessions, download_queue,
 * vocab_words — live in entity files outside this change's scope;
 * their indices are a follow-up, not silently half-done here:
 * an index created by SQL but absent from the entity declaration
 * would fail Room's schema validation.)
 */
val MIGRATION_5_6: Migration = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_books_extensionId_remoteId` " +
                "ON `books` (`extensionId`, `remoteId`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_books_mergedIntoId_lastOpenedAt` " +
                "ON `books` (`mergedIntoId`, `lastOpenedAt`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_books_title` " +
                "ON `books` (`title`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_books_finished` " +
                "ON `books` (`finished`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_chapters_downloadState_bookId_index` " +
                "ON `chapters` (`downloadState`, `bookId`, `index`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_reading_progress_updatedAt` " +
                "ON `reading_progress` (`updatedAt`)"
        )
    }
}

val MIGRATION_2_3: Migration = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `books` ADD COLUMN `mergedIntoId` INTEGER")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `shelves` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`name` TEXT NOT NULL, " +
                "`kind` TEXT NOT NULL, " +
                "`rule` TEXT, " +
                "`ruleValue` TEXT NOT NULL, " +
                "`sortOrder` INTEGER NOT NULL, " +
                "`hidden` INTEGER NOT NULL, " +
                "`sort` TEXT NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `shelf_books` (" +
                "`shelfId` INTEGER NOT NULL, " +
                "`bookId` INTEGER NOT NULL, " +
                "`addedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`shelfId`, `bookId`), " +
                "FOREIGN KEY(`shelfId`) REFERENCES `shelves`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE , " +
                "FOREIGN KEY(`bookId`) REFERENCES `books`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE )"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_shelf_books_bookId` " +
                "ON `shelf_books` (`bookId`)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `tags` (" +
                "`slug` TEXT NOT NULL, " +
                "`displayName` TEXT NOT NULL, " +
                "`family` TEXT NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`slug`))"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `book_tags` (" +
                "`bookId` INTEGER NOT NULL, " +
                "`slug` TEXT NOT NULL, " +
                "`origin` TEXT NOT NULL, " +
                "`suppressed` INTEGER NOT NULL, " +
                "PRIMARY KEY(`bookId`, `slug`))"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_book_tags_slug` " +
                "ON `book_tags` (`slug`)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `tag_follows` (" +
                "`slug` TEXT NOT NULL, " +
                "`followedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`slug`))"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `vocab_words` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`word` TEXT NOT NULL, " +
                "`definition` TEXT NOT NULL, " +
                "`sentence` TEXT NOT NULL, " +
                "`bookId` INTEGER, " +
                "`chapterIndex` INTEGER NOT NULL, " +
                "`state` TEXT NOT NULL, " +
                "`intervalDays` REAL NOT NULL, " +
                "`dueAt` INTEGER NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL)"
        )
    }
}
