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

/** v1.3.0 delta (V120-CHECK 45): Library membership. Existing
 * rows default to members — everything the user already has
 * stays exactly where it was; only rows created by opening a
 * catalogue book from now on start as previews. */
val MIGRATION_6_7: Migration = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE `books` ADD COLUMN `inLibrary` " +
                "INTEGER NOT NULL DEFAULT 1"
        )
    }
}

/**
 * 8 → 9 (v1.5.5, §2 epoch hygiene): books learn the
 * definition epoch their chapter list was last completed
 * under ([app.xreader.data.db.Book.syncedEpoch]). One
 * additive INTEGER column; existing rows read as epoch 0
 * (unstamped), so their next sync re-proves the chapter
 * list under the current definition epoch before the
 * completeness stamp is trusted again. No row is rewritten
 * and no chapter row is ever deleted by this migration.
 */
val MIGRATION_8_9: Migration = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE `books` ADD COLUMN `syncedEpoch` " +
                "INTEGER NOT NULL DEFAULT 0"
        )
    }
}

/**
 * 9 → 10 (v1.5.6, §3): the render-the-file data model. All additive —
 * no row is ever rewritten destructively:
 *  - books gain [Book.documentUri] + [Book.fileFingerprint];
 *  - reading_progress gains locatorJson + anchorText;
 *  - bookmarks/highlights gain locatorJson;
 *  - NEW reading_memory (the §3.7 note; NO foreign key — it must
 *    survive book deletion) and local_outline (Contents for
 *    in-place books) tables.
 *
 * DATA mapping, computed from stored rows only:
 *  - every LOCAL_FILE book's local_outline is filled from its
 *    chapter rows (idx = chapter index, title, targetJson =
 *    {"legacyChapterIndex":index});
 *  - reading_progress of local books gains a legacyChapter locator
 *    whose bookFraction is computed from the chapters' wordCount
 *    prefix sums (prefix + wordCount[chapterIndex] × in-chapter
 *    fraction — scrollFraction, falling back to fraction) over the
 *    total wordCount (zero-guarded), with the chapter's title as
 *    anchorText;
 *  - bookmarks/highlights of local books gain legacyChapter
 *    locators (highlights resolve their chapterIndex through the
 *    chapter row their chapterId names, and carry start/end
 *    offsets too).
 *
 * NOTE (§3.5 PRESERVE fate): PDF boundary reconstruction is NOT
 * run inside this migration (no file wiring exists at this seam);
 * records are preserved with chapter anchors, never dropped, never
 * force-mapped to pages — open-time refinement belongs to the
 * engine workstream. Books with a retained filePath keep it
 * as-is: population (a) re-point is a read-time resolution, no
 * data change.
 */
val MIGRATION_9_10: Migration = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // ── Schema ──────────────────────────────────────────────
        db.execSQL("ALTER TABLE `books` ADD COLUMN `documentUri` TEXT")
        db.execSQL("ALTER TABLE `books` ADD COLUMN `fileFingerprint` TEXT")
        db.execSQL("ALTER TABLE `reading_progress` ADD COLUMN `locatorJson` TEXT")
        db.execSQL("ALTER TABLE `reading_progress` ADD COLUMN `anchorText` TEXT")
        db.execSQL("ALTER TABLE `bookmarks` ADD COLUMN `locatorJson` TEXT")
        db.execSQL("ALTER TABLE `highlights` ADD COLUMN `locatorJson` TEXT")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `reading_memory` (" +
                "`fingerprint` TEXT NOT NULL, " +
                "`displayName` TEXT NOT NULL, " +
                "`sizeBytes` INTEGER NOT NULL, " +
                "`format` TEXT NOT NULL, " +
                "`fraction` REAL NOT NULL DEFAULT 0, " +
                "`locatorJson` TEXT, " +
                "`bookmarksJson` TEXT NOT NULL DEFAULT '[]', " +
                "`highlightsJson` TEXT NOT NULL DEFAULT '[]', " +
                "`updatedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`fingerprint`))"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `local_outline` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`bookId` INTEGER NOT NULL, " +
                "`idx` INTEGER NOT NULL, " +
                "`title` TEXT NOT NULL, " +
                "`targetJson` TEXT NOT NULL, " +
                "FOREIGN KEY(`bookId`) REFERENCES `books`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE )"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_local_outline_bookId` " +
                "ON `local_outline` (`bookId`)"
        )

        // ── Data mapping (stored rows only) ─────────────────────
        data class MigChapter(val id: Long, val index: Int, val title: String, val wordCount: Int)

        val localBookIds = mutableListOf<Long>()
        db.query("SELECT `id` FROM `books` WHERE `sourceType` = 'LOCAL_FILE'").use { cursor ->
            while (cursor.moveToNext()) localBookIds += cursor.getLong(0)
        }
        // bookId → its chapters in index order; chapterId → index.
        val chaptersByBook = HashMap<Long, List<MigChapter>>()
        val chapterIndexById = HashMap<Long, Int>()
        for (bookId in localBookIds) {
            val chapters = mutableListOf<MigChapter>()
            db.query(
                "SELECT `id`, `index`, `title`, `wordCount` FROM `chapters` " +
                    "WHERE `bookId` = ? ORDER BY `index` ASC",
                arrayOf<Any>(bookId)
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val chapter = MigChapter(
                        id = cursor.getLong(0),
                        index = cursor.getInt(1),
                        title = cursor.getString(2) ?: "",
                        wordCount = cursor.getInt(3)
                    )
                    chapters += chapter
                    chapterIndexById[chapter.id] = chapter.index
                }
            }
            chaptersByBook[bookId] = chapters
            for (chapter in chapters) {
                db.execSQL(
                    "INSERT INTO `local_outline` (`bookId`, `idx`, `title`, `targetJson`) " +
                        "VALUES (?, ?, ?, ?)",
                    arrayOf<Any>(
                        bookId, chapter.index, chapter.title,
                        "{\"legacyChapterIndex\":${chapter.index}}"
                    )
                )
            }
        }

        // Progress: wordCount prefix sums → bookFraction.
        for (bookId in localBookIds) {
            val chapters = chaptersByBook[bookId].orEmpty()
            if (chapters.isEmpty()) continue
            val totalWords = chapters.sumOf { it.wordCount }.toLong()
            val prefixByIndex = HashMap<Int, Long>()
            var running = 0L
            for (chapter in chapters) {
                prefixByIndex[chapter.index] = running
                running += chapter.wordCount
            }
            val byIndex = chapters.associateBy { it.index }
            data class ProgRow(val chapterIndex: Int, val fraction: Float, val scrollFraction: Float)
            val rows = mutableListOf<ProgRow>()
            db.query(
                "SELECT `chapterIndex`, `fraction`, `scrollFraction` FROM `reading_progress` " +
                    "WHERE `bookId` = ?",
                arrayOf<Any>(bookId)
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    rows += ProgRow(cursor.getInt(0), cursor.getFloat(1), cursor.getFloat(2))
                }
            }
            for (row in rows) {
                val chapter = byIndex[row.chapterIndex]
                val inChapter = (
                    if (row.scrollFraction > 0f) row.scrollFraction else row.fraction
                    ).coerceIn(0f, 1f)
                val bookFraction = if (totalWords > 0 && chapter != null) {
                    val prefix = prefixByIndex[row.chapterIndex] ?: 0L
                    ((prefix + chapter.wordCount * inChapter.toDouble()) / totalWords)
                        .toFloat().coerceIn(0f, 1f)
                } else {
                    row.fraction.coerceIn(0f, 1f)
                }
                val locator =
                    "{\"kind\":\"legacyChapter\",\"chapterIndex\":${row.chapterIndex}," +
                        "\"inChapterFraction\":$inChapter,\"bookFraction\":$bookFraction}"
                db.execSQL(
                    "UPDATE `reading_progress` SET `locatorJson` = ?, `anchorText` = ? " +
                        "WHERE `bookId` = ?",
                    arrayOf<Any?>(locator, chapter?.title, bookId)
                )
            }
        }

        // Bookmarks: chapterIndex + charOffset are stored columns.
        for (bookId in localBookIds) {
            data class MarkRow(val id: Long, val chapterIndex: Int, val charOffset: Int)
            val rows = mutableListOf<MarkRow>()
            db.query(
                "SELECT `id`, `chapterIndex`, `charOffset` FROM `bookmarks` WHERE `bookId` = ?",
                arrayOf<Any>(bookId)
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    rows += MarkRow(cursor.getLong(0), cursor.getInt(1), cursor.getInt(2))
                }
            }
            for (row in rows) {
                val locator =
                    "{\"kind\":\"legacyChapter\",\"chapterIndex\":${row.chapterIndex}," +
                        "\"charOffset\":${row.charOffset}}"
                db.execSQL(
                    "UPDATE `bookmarks` SET `locatorJson` = ? WHERE `id` = ?",
                    arrayOf<Any>(locator, row.id)
                )
            }
        }

        // Highlights: chapterIndex resolves through the chapter row.
        for (bookId in localBookIds) {
            data class LightRow(val id: Long, val chapterId: Long, val start: Int, val end: Int)
            val rows = mutableListOf<LightRow>()
            db.query(
                "SELECT `id`, `chapterId`, `startOffset`, `endOffset` FROM `highlights` " +
                    "WHERE `bookId` = ?",
                arrayOf<Any>(bookId)
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    rows += LightRow(
                        cursor.getLong(0), cursor.getLong(1),
                        cursor.getInt(2), cursor.getInt(3)
                    )
                }
            }
            for (row in rows) {
                // Unresolvable chapter → preserve the record as-is
                // (never dropped, never force-mapped).
                val chapterIndex = chapterIndexById[row.chapterId] ?: continue
                val locator =
                    "{\"kind\":\"legacyChapter\",\"chapterIndex\":$chapterIndex," +
                        "\"charOffset\":${row.start},\"startOffset\":${row.start}," +
                        "\"endOffset\":${row.end}}"
                db.execSQL(
                    "UPDATE `highlights` SET `locatorJson` = ? WHERE `id` = ?",
                    arrayOf<Any>(locator, row.id)
                )
            }
        }
    }
}

/**
 * 10 → 11 (v1.5.8, E1): chapters learn their scanlator — the
 * translation group the source credits (the donor model's
 * first-class `SChapter.scanlator`, dropped at the host model
 * until now). One nullable TEXT column, defaulted exactly like
 * `dateText` was in MIGRATION_3_4; no row is rewritten and
 * every pre-v1.5.8 row reads null (no source credited one in
 * the stored data — honest absence).
 */
val MIGRATION_10_11: Migration = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `chapters` ADD COLUMN `scanlator` TEXT")
    }
}

/**
 * 11 → 12 (v1.5.11, transplant Verdict 1): chapters learn their
 * memo — the donor model's per-chapter private state
 * (`SChapter.memo`, carried as JSON text), which the donor
 * persists DB-round-trip and Xreader dropped at every seam
 * until now. One nullable TEXT column, defaulted exactly like
 * `scanlator` was in MIGRATION_10_11; no row is rewritten and
 * every pre-v1.5.11 row reads null (no source state was ever
 * stored — honest absence; the next chapter-list refresh
 * re-mints and stores it).
 */
val MIGRATION_11_12: Migration = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `chapters` ADD COLUMN `memo` TEXT")
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

/**
 * 7 → 8 (v1.5.2, §16 total — EXACTLY these artefacts, nothing more):
 * (1) books.kind nullable, (2) books.coverUrl nullable,
 * (3) chapters.bodyOrigin nullable, (4) history_records,
 * (5) pending_operations, (6) update_pass_journal,
 * (7) installed_extensions.needsRepair + reason class.
 * Kind seeding (§7): from STORED evidence only. v7 stores no per-book
 * content verdict on the book row, so every book seeds NULL (UNKNOWN)
 * — the migration NEVER mass-reclassifies from a source default.
 */
val MIGRATION_7_8: Migration = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `books` ADD COLUMN `coverUrl` TEXT")
        db.execSQL("ALTER TABLE `books` ADD COLUMN `kind` TEXT")
        db.execSQL("ALTER TABLE `chapters` ADD COLUMN `bodyOrigin` TEXT")
        db.execSQL(
            "ALTER TABLE `installed_extensions` ADD COLUMN `needsRepair` " +
                "INTEGER NOT NULL DEFAULT 0"
        )
        db.execSQL(
            "ALTER TABLE `installed_extensions` ADD COLUMN `needsRepairReason` TEXT"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `history_records` (" +
                "`bookKey` TEXT NOT NULL, `bookId` INTEGER, `title` TEXT NOT NULL, " +
                "`bookUrl` TEXT NOT NULL, `sourceName` TEXT NOT NULL, " +
                "`coverUrl` TEXT, `author` TEXT NOT NULL, `tags` TEXT NOT NULL, " +
                "`rating` TEXT NOT NULL, `synopsis` TEXT NOT NULL, " +
                "`totalChapters` INTEGER NOT NULL, `recordedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`bookKey`))"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `pending_operations` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`sessionKey` TEXT NOT NULL, `flowKind` TEXT NOT NULL, " +
                "`host` TEXT NOT NULL, `state` TEXT NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, `watermark` TEXT NOT NULL, " +
                "`attemptCount` INTEGER NOT NULL, `lastFailureClass` TEXT, " +
                "`claimantToken` TEXT, `trace` TEXT NOT NULL, " +
                "`category` TEXT NOT NULL)"
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_pending_operations_sessionKey` " +
                "ON `pending_operations` (`sessionKey`)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `update_pass_journal` (" +
                "`versionCode` INTEGER NOT NULL, `providerKey` TEXT NOT NULL, " +
                "`stage` TEXT NOT NULL, `outcome` TEXT NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`versionCode`, `providerKey`))"
        )
    }
}
