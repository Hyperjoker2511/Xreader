package app.xreader.data.sync

import app.xreader.core.error.AppError
import app.xreader.core.error.AppResult
import app.xreader.data.ai.LoreMerger
import app.xreader.data.db.Book
import app.xreader.data.db.BookDao
import app.xreader.data.db.Bookmark
import app.xreader.data.db.BookmarkDao
import app.xreader.data.db.ChapterDao
import app.xreader.data.db.DailyActivity
import app.xreader.data.db.DailyActivityDao
import app.xreader.data.db.Highlight
import app.xreader.data.db.HighlightDao
import app.xreader.data.db.InstalledExtensionDao
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
import app.xreader.data.prefs.SyncPrefs
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.flow.first

/**
 * The sync engine (PLAN §7): gather this device's payload, merge it
 * with the other side's (Drive file / WebDAV file / backup archive —
 * all the same shape), apply the merged result locally, and write it
 * back. Merging is [SyncMerger]'s pure function; this class is the
 * Room-facing edge, plus the visible sync log and per-section report.
 *
 * Books are matched across devices by [SyncKeys.bookKey]. A book that
 * exists only on the other device is counted and logged, NOT created
 * as a file-less ghost row: when the user adds the same book here,
 * the next sync matches it and everything (progress, lore, marks)
 * lands. Sync never blocks reading and never touches book files.
 */
class SyncService(
    private val bookDao: BookDao,
    private val chapterDao: ChapterDao,
    private val progressDao: ReadingProgressDao,
    private val bookmarkDao: BookmarkDao,
    private val highlightDao: HighlightDao,
    private val loreEntityDao: LoreEntityDao,
    private val loreRelationDao: LoreRelationDao,
    private val terminologyDao: TerminologyDao,
    private val profileDao: ProfileDao,
    private val dailyActivityDao: DailyActivityDao,
    private val syncStateDao: SyncStateDao,
    private val installedExtensionDao: InstalledExtensionDao,
    private val syncPrefs: SyncPrefs,
    private val tombstones: TombstoneStore,
    private val syncLog: SyncLogStore,
    private val readerPrefsProvider: suspend () -> Map<String, String>,
    private val applyReaderPrefs: suspend (Map<String, String>) -> Unit,
    private val aiModelProvider: suspend () -> String?,
    private val applyAiModel: suspend (String) -> Unit,
    /** v1.0.1: per-book reading status lives in a DataStore sidecar
     * (BookMetaPrefs) — these lambdas bridge it into the payload. */
    private val avatarColourProvider: suspend () -> String = { "" },
    private val applyAvatarColour: suspend (String) -> Unit = {},
    private val bookStatusProvider: suspend (Long) -> String = { "NONE" },
    private val applyBookStatus: suspend (Long, String) -> Unit = { _, _ -> },
    /** Phase 6: the opt-in book-file channel. Null (tests, or a
     * build without it) = metadata-only sync, the Phase 5 shape. */
    private val fileSync: BookFileSync? = null,
    private val fileSyncEnabled: suspend () -> Boolean = { false },
    /** v1.0.2 (R02): organisation bridges — gather receives the
     * bookId→syncKey map so shelf membership can travel by key;
     * apply receives the merged section plus the key→bookId map of
     * books that exist locally. Defaults keep older constructions
     * (and the test suite) organisation-free. */
    private val organisationProvider: suspend (Map<Long, String>) -> OrganisationSection =
        { OrganisationSection() },
    private val applyOrganisation: suspend (OrganisationSection, Map<String, Long>) -> Unit =
        { _, _ -> },
    private val clock: () -> Long = System::currentTimeMillis
) {

    data class SyncReport(
        val backendLabel: String,
        val booksMatched: Int,
        val booksOnlyElsewhere: Int,
        val progressApplied: Int,
        val bookmarksApplied: Int,
        val highlightsApplied: Int,
        val loreApplied: Int,
        val termsApplied: Int,
        val conflicts: List<String>,
        /** Phase 6: book-file channel results (0 when off). */
        val filesUploaded: Int = 0,
        val filesDownloaded: Int = 0
    ) {
        val summary: String
            get() = "Synced with $backendLabel — $progressApplied reading positions, " +
                "$bookmarksApplied bookmarks, $highlightsApplied highlights, " +
                "$loreApplied lore notes" +
                if (booksOnlyElsewhere > 0) {
                    "; $booksOnlyElsewhere book(s) exist only on the other device"
                } else {
                    ""
                } +
                if (filesUploaded > 0 || filesDownloaded > 0) {
                    "; book files: $filesUploaded up, $filesDownloaded down"
                } else {
                    ""
                }
    }

    // -- gather ---------------------------------------------------------------

    suspend fun gather(): SyncPayload {
        val deviceId = syncPrefs.deviceId()
        val packageByExtension = installedExtensionDao.observeAll().first()
            .associate { it.id to it.packageId }
        val books = bookDao.allBooks()
        val keyByBookId = books.associate { book ->
            book.id to SyncKeys.bookKey(book, packageByExtension[book.extensionId ?: -1L])
        }
        val bookRecords = books.map { book ->
            BookRecord(
                syncKey = keyByBookId.getValue(book.id),
                title = book.title,
                author = book.author,
                description = book.description,
                sourceType = book.sourceType.name,
                totalChapters = book.totalChapters,
                totalWords = book.totalWords,
                finished = book.finished,
                userRating = book.userRating,
                readingStatus = bookStatusProvider(book.id),
                updatedAt = book.updatedAt,
                lastOpenedAt = book.lastOpenedAt
            )
        }
        val progress = progressDao.allProgress().mapNotNull { row ->
            val key = keyByBookId[row.bookId] ?: return@mapNotNull null
            ProgressRecord(
                bookKey = key,
                chapterIndex = row.chapterIndex,
                charOffset = row.charOffset,
                fraction = row.fraction,
                scrollFraction = row.scrollFraction,
                updatedAt = row.updatedAt,
                deviceId = row.deviceId.ifBlank { deviceId }
            )
        }
        val bookmarks = mutableListOf<BookmarkRecord>()
        val highlights = mutableListOf<HighlightRecord>()
        val lore = mutableListOf<LoreRecord>()
        val relations = mutableListOf<LoreRelationRecord>()
        val terms = mutableListOf<TermRecord>()
        for (book in books) {
            val key = keyByBookId.getValue(book.id)
            val chapters = chapterDao.forBook(book.id)
            val indexByChapterId = chapters.associate { it.id to it.index }
            bookmarkDao.forBook(book.id).forEach { bookmark ->
                bookmarks += BookmarkRecord(
                    bookKey = key,
                    chapterIndex = bookmark.chapterIndex,
                    charOffset = bookmark.charOffset,
                    label = bookmark.label,
                    createdAt = bookmark.createdAt,
                    updatedAt = bookmark.updatedAt
                )
            }
            highlightDao.forBook(book.id).forEach { highlight ->
                val index = indexByChapterId[highlight.chapterId]
                    ?: return@forEach
                highlights += HighlightRecord(
                    bookKey = key,
                    chapterIndex = index,
                    startOffset = highlight.startOffset,
                    endOffset = highlight.endOffset,
                    quotedText = highlight.quotedText,
                    color = highlight.color,
                    note = highlight.note,
                    createdAt = highlight.createdAt,
                    updatedAt = highlight.updatedAt
                )
            }
            val entities = loreEntityDao.forBook(book.id)
            entities.forEach { entity ->
                lore += LoreRecord(
                    bookKey = key,
                    type = entity.type.name,
                    name = entity.name,
                    aliasesJson = entity.aliasesJson,
                    description = entity.description,
                    firstChapterIndex = entity.firstChapterIndex,
                    userLocked = entity.userLocked,
                    updatedAt = entity.updatedAt
                )
            }
            val nameById = entities.associate { it.id to it.name }
            loreRelationDao.forBook(book.id).forEach { relation ->
                val from = nameById[relation.fromEntityId]
                val to = nameById[relation.toEntityId]
                if (from != null && to != null) {
                    relations += LoreRelationRecord(key, from, to, relation.relationLabel)
                }
            }
            terminologyDao.forBook(book.id).forEach { term ->
                terms += TermRecord(key, term.sourceTerm, term.chosenTranslation, term.locked)
            }
        }
        val profile = profileDao.get()?.let {
            ProfileRecord(
                displayName = it.displayName,
                avatarColour = avatarColourProvider(),
                totalExp = it.totalExp,
                level = it.level,
                dailyGoalMinutes = it.dailyGoalMinutes,
                createdAt = it.createdAt
            )
        }
        val days = dailyActivityDao.all().map {
            DayRecord(it.date, it.minutesRead, it.chaptersFinished, it.expEarned, it.goalMet)
        }
        val organisation = runCatching { organisationProvider(keyByBookId) }
            .getOrDefault(OrganisationSection())
        return SyncPayload(
            deviceId = deviceId,
            exportedAt = clock(),
            books = bookRecords,
            progress = progress,
            bookmarks = bookmarks,
            highlights = highlights,
            lore = lore,
            loreRelations = relations,
            terminology = terms,
            profile = profile,
            dailyActivity = days,
            readerPrefs = readerPrefsProvider(),
            aiModelChoice = aiModelProvider(),
            tombstones = tombstones.all(),
            shelves = organisation.shelves,
            tags = organisation.tags,
            bookTags = organisation.bookTags,
            followedTagSlugs = organisation.followedTagSlugs,
            favouriteBookKeys = organisation.favouriteBookKeys,
            themeSync = organisation.themeSync
        )
    }

    // -- apply ------------------------------------------------------------------

    /** Applies a merged payload to the local database. Returns counts
     * for the report/log. Idempotent: applying twice changes nothing
     * the second time (LWW comparisons are all `>`). */
    /** Same-write-timestamp tie-break for progress: the incoming
     * record applies only when it is strictly further along than
     * what's stored (an identical record is a no-op, so re-syncs
     * stay quiet). */
    private fun strictlyFurther(
        existing: ReadingProgress,
        record: ProgressRecord
    ): Boolean = when {
        record.chapterIndex != existing.chapterIndex ->
            record.chapterIndex > existing.chapterIndex
        record.charOffset != existing.charOffset ->
            record.charOffset > existing.charOffset
        else -> record.fraction > existing.fraction
    }

    suspend fun applyMerged(merged: SyncPayload): SyncReport {
        val packageByExtension = installedExtensionDao.observeAll().first()
            .associate { it.id to it.packageId }
        val localBooks = bookDao.allBooks()
        val byKey = localBooks.associateBy { book ->
            SyncKeys.bookKey(book, packageByExtension[book.extensionId ?: -1L])
        }
        var booksMatched = 0
        var booksOnlyElsewhere = 0
        for (record in merged.books) {
            val local = byKey[record.syncKey]
            if (local == null) {
                booksOnlyElsewhere++
                continue
            }
            booksMatched++
            // v1.0.1: reading status follows the same newest-wins
            // rule through the sidecar bridge.
            if (record.updatedAt > local.updatedAt &&
                record.readingStatus != "NONE"
            ) {
                applyBookStatus(local.id, record.readingStatus)
            }
            if (record.updatedAt > local.updatedAt) {
                bookDao.update(
                    local.copy(
                        title = record.title,
                        author = record.author,
                        description = record.description,
                        totalChapters = record.totalChapters,
                        totalWords = record.totalWords,
                        finished = record.finished,
                        userRating = record.userRating,
                        updatedAt = record.updatedAt,
                        lastOpenedAt = maxOfNullable(local.lastOpenedAt, record.lastOpenedAt)
                    )
                )
            }
        }

        var progressApplied = 0
        for (record in merged.progress) {
            val book = byKey[record.bookKey] ?: continue
            val chapter = chapterDao.getByIndex(book.id, record.chapterIndex) ?: continue
            val existing = progressDao.getForBook(book.id)
            if (existing == null || record.updatedAt > existing.updatedAt ||
                (record.updatedAt == existing.updatedAt &&
                    strictlyFurther(existing, record))
            ) {
                progressDao.upsert(
                    ReadingProgress(
                        bookId = book.id,
                        chapterId = chapter.id,
                        chapterIndex = record.chapterIndex,
                        charOffset = record.charOffset,
                        fraction = record.fraction,
                        scrollFraction = record.scrollFraction,
                        updatedAt = record.updatedAt,
                        deviceId = record.deviceId
                    )
                )
                progressApplied++
            }
        }

        var bookmarksApplied = 0
        for (record in merged.bookmarks) {
            val book = byKey[record.bookKey] ?: continue
            val chapter = chapterDao.getByIndex(book.id, record.chapterIndex) ?: continue
            val existing = bookmarkDao.forBook(book.id).firstOrNull {
                it.chapterIndex == record.chapterIndex &&
                    it.charOffset == record.charOffset
            }
            if (existing == null) {
                bookmarkDao.insert(
                    Bookmark(
                        bookId = book.id,
                        chapterId = chapter.id,
                        chapterIndex = record.chapterIndex,
                        charOffset = record.charOffset,
                        label = record.label,
                        createdAt = record.createdAt,
                        updatedAt = record.updatedAt
                    )
                )
                bookmarksApplied++
            } else if (record.updatedAt > existing.updatedAt) {
                bookmarkDao.delete(existing)
                bookmarkDao.insert(
                    existing.copy(
                        chapterId = chapter.id,
                        label = record.label,
                        updatedAt = record.updatedAt
                    )
                )
                bookmarksApplied++
            }
        }

        var highlightsApplied = 0
        for (record in merged.highlights) {
            val book = byKey[record.bookKey] ?: continue
            val chapter = chapterDao.getByIndex(book.id, record.chapterIndex) ?: continue
            val existing = highlightDao.forBook(book.id).firstOrNull {
                it.chapterId == chapter.id &&
                    it.startOffset == record.startOffset &&
                    it.endOffset == record.endOffset
            }
            if (existing == null) {
                highlightDao.insert(
                    Highlight(
                        bookId = book.id,
                        chapterId = chapter.id,
                        startOffset = record.startOffset,
                        endOffset = record.endOffset,
                        quotedText = record.quotedText,
                        color = record.color,
                        note = record.note,
                        createdAt = record.createdAt,
                        updatedAt = record.updatedAt
                    )
                )
                highlightsApplied++
            } else if (record.updatedAt > existing.updatedAt) {
                highlightDao.update(
                    existing.copy(
                        quotedText = record.quotedText,
                        color = record.color,
                        note = record.note,
                        updatedAt = record.updatedAt
                    )
                )
                highlightsApplied++
            }
        }

        var loreApplied = 0
        for (record in merged.lore) {
            val book = byKey[record.bookKey] ?: continue
            val type = runCatching {
                app.xreader.data.db.LoreType.valueOf(record.type)
            }.getOrDefault(app.xreader.data.db.LoreType.TERM)
            val existing = loreEntityDao.forBook(book.id).firstOrNull {
                it.type == type &&
                    LoreMerger.normalise(it.name) == LoreMerger.normalise(record.name)
            }
            if (existing == null) {
                loreEntityDao.insert(
                    LoreEntity(
                        bookId = book.id,
                        type = type,
                        name = record.name,
                        aliasesJson = record.aliasesJson,
                        description = record.description,
                        firstChapterIndex = record.firstChapterIndex,
                        userLocked = record.userLocked,
                        updatedAt = record.updatedAt
                    )
                )
                loreApplied++
            } else if (record.updatedAt > existing.updatedAt &&
                !(existing.userLocked && !record.userLocked)
            ) {
                loreEntityDao.update(
                    existing.copy(
                        aliasesJson = record.aliasesJson,
                        description = record.description,
                        firstChapterIndex = minOf(
                            existing.firstChapterIndex, record.firstChapterIndex
                        ),
                        userLocked = existing.userLocked || record.userLocked,
                        updatedAt = record.updatedAt
                    )
                )
                loreApplied++
            }
        }
        // Relations resolve after entities exist on both sides.
        for (record in merged.loreRelations) {
            val book = byKey[record.bookKey] ?: continue
            val entities = loreEntityDao.forBook(book.id)
            val byName = entities.associateBy { LoreMerger.normalise(it.name) }
            val from = byName[LoreMerger.normalise(record.fromName)] ?: continue
            val to = byName[LoreMerger.normalise(record.toName)] ?: continue
            val exists = loreRelationDao.forBook(book.id).any {
                it.fromEntityId == from.id && it.toEntityId == to.id &&
                    LoreMerger.normalise(it.relationLabel) ==
                    LoreMerger.normalise(record.label)
            }
            if (!exists) {
                loreRelationDao.insert(
                    LoreRelation(
                        bookId = book.id,
                        fromEntityId = from.id,
                        toEntityId = to.id,
                        relationLabel = record.label
                    )
                )
            }
        }

        var termsApplied = 0
        for (record in merged.terminology) {
            val book = byKey[record.bookKey] ?: continue
            val existing = terminologyDao.forBook(book.id)
                .firstOrNull { it.sourceTerm == record.sourceTerm }
            if (existing == null) {
                terminologyDao.upsert(
                    TerminologyEntry(
                        bookId = book.id,
                        sourceTerm = record.sourceTerm,
                        chosenTranslation = record.chosenTranslation,
                        locked = record.locked
                    )
                )
                termsApplied++
            } else if (!(existing.locked && !record.locked) &&
                (existing.chosenTranslation != record.chosenTranslation ||
                    existing.locked != record.locked)
            ) {
                terminologyDao.upsert(
                    existing.copy(
                        chosenTranslation = record.chosenTranslation,
                        locked = record.locked
                    )
                )
                termsApplied++
            }
        }

        merged.profile?.let { record ->
            if (record.avatarColour.isNotBlank()) {
                applyAvatarColour(record.avatarColour)
            }
            val existing = profileDao.get()
            profileDao.upsert(
                Profile(
                    displayName = record.displayName,
                    avatarPath = existing?.avatarPath,
                    totalExp = record.totalExp,
                    level = record.level,
                    dailyGoalMinutes = record.dailyGoalMinutes,
                    createdAt = if (existing != null && existing.createdAt > 0) {
                        existing.createdAt
                    } else {
                        record.createdAt
                    }
                )
            )
        }
        merged.dailyActivity.forEach { day ->
            dailyActivityDao.upsert(
                DailyActivity(
                    date = day.date,
                    minutesRead = day.minutesRead,
                    chaptersFinished = day.chaptersFinished,
                    expEarned = day.expEarned,
                    goalMet = day.goalMet
                )
            )
        }
        if (merged.readerPrefs.isNotEmpty()) {
            applyReaderPrefs(merged.readerPrefs)
        }
        merged.aiModelChoice?.let { applyAiModel(it) }

        // v1.0.2 (R02): organisation applies through its bridge —
        // shelves/tags/favourites/theme land in their own stores.
        runCatching {
            applyOrganisation(
                OrganisationSection(
                    shelves = merged.shelves,
                    tags = merged.tags,
                    bookTags = merged.bookTags,
                    followedTagSlugs = merged.followedTagSlugs,
                    favouriteBookKeys = merged.favouriteBookKeys,
                    themeSync = merged.themeSync
                ),
                byKey.entries.associate { (key, book) -> key to book.id }
            )
        }

        return SyncReport(
            backendLabel = "",
            booksMatched = booksMatched,
            booksOnlyElsewhere = booksOnlyElsewhere,
            progressApplied = progressApplied,
            bookmarksApplied = bookmarksApplied,
            highlightsApplied = highlightsApplied,
            loreApplied = loreApplied,
            termsApplied = termsApplied,
            conflicts = emptyList()
        )
    }

    // -- flows ------------------------------------------------------------------

    /** One full sync against [backend]: read → merge → apply → write. */
    suspend fun syncNow(backend: SyncBackend): AppResult<SyncReport> {
        val now = clock()
        val local = gather()
        val remoteBytes = when (val read = backend.read(SyncBackend.SYNC_FILE_NAME)) {
            is AppResult.Err -> return AppResult.Err(read.error)
            is AppResult.Ok -> read.value
        }
        val remote = if (remoteBytes == null) {
            null
        } else {
            SyncPayload.decode(remoteBytes.toString(Charsets.UTF_8))
                ?: return AppResult.Err(
                    AppError.BadFile(
                        "the saved sync data couldn't be read, so nothing was changed"
                    )
                )
        }
        val mergeResult = if (remote != null) {
            SyncMerger.merge(local, remote, now)
        } else {
            SyncMerger.Result(local.copy(exportedAt = now), emptyList())
        }
        val applied = applyMerged(mergeResult.payload)
        tombstones.replaceAll(mergeResult.payload.tombstones)
        val writeResult = backend.write(
            SyncBackend.SYNC_FILE_NAME,
            SyncPayload.encode(mergeResult.payload).toByteArray(Charsets.UTF_8)
        )
        val revision = when (writeResult) {
            is AppResult.Err -> return AppResult.Err(writeResult.error)
            is AppResult.Ok -> writeResult.value
        }
        // Phase 6: the opt-in file channel runs AFTER the metadata
        // lands — a file failure is reported in the log/conflicts,
        // never allowed to un-happen a good metadata sync.
        var fileCounts = BookFileSync.FileReport()
        val fileNotes = mutableListOf<String>()
        val fs = fileSync
        if (fs != null && fileSyncEnabled()) {
            when (val pushed = fs.push(backend)) {
                is AppResult.Err -> fileNotes +=
                    "Book files didn't finish uploading: " +
                    app.xreader.core.error.ErrorText.displayText(pushed.error)
                is AppResult.Ok -> fileCounts += pushed.value
            }
            if (fileNotes.isEmpty()) {
                when (val pulled = fs.pull(backend)) {
                    is AppResult.Err -> fileNotes +=
                        "Book files didn't finish downloading: " +
                        app.xreader.core.error.ErrorText.displayText(pulled.error)
                    is AppResult.Ok -> fileCounts += pulled.value
                }
            }
        }
        syncStateDao.upsert(
            SyncState(
                entityType = "sync",
                entityId = backend.id,
                remoteRevision = revision,
                lastSyncedAt = now,
                dirty = false
            )
        )
        val report = applied.copy(
            backendLabel = backend.label,
            conflicts = mergeResult.conflicts + fileNotes,
            filesUploaded = fileCounts.uploaded,
            filesDownloaded = fileCounts.downloaded
        )
        syncPrefs.recordSync(now, report.summary)
        syncLog.append(
            SyncLogEntry(
                at = now,
                backend = backend.label,
                summary = report.summary,
                conflicts = report.conflicts
            )
        )
        return AppResult.Ok(report)
    }

    /** Backup export: the same payload in the archive format. With
     * the file opt-in on, the archive also carries the book files. */
    suspend fun exportBackup(output: OutputStream): AppResult<Unit> =
        try {
            val payload = gather()
            val fs = fileSync
            if (fs != null && fileSyncEnabled()) {
                val (manifest, files) = fs.collectForBackup()
                BackupArchive.writeWithFiles(
                    payload,
                    BookFileSync.encodeManifest(manifest),
                    files,
                    output
                )
            } else {
                BackupArchive.write(payload, output)
            }
            AppResult.Ok(Unit)
        } catch (e: Exception) {
            AppResult.Err(AppError.ExportFailed("the backup couldn't be written (${e.message?.take(80)})"))
        }

    /** Backup restore = a merge with the archive as the other side.
     * Book files inside the archive land on matched books after the
     * metadata merge (they need the merged library to match onto). */
    suspend fun importBackup(input: InputStream): AppResult<SyncReport> {
        val fs = fileSync
        var stagedManifest: BookFileSync.FileManifest? = null
        val stagedFiles = mutableMapOf<String, java.io.File>()
        var stagingDir: java.io.File? = null
        val backup = if (fs != null) {
            val staging = fs.newStagingDir()
            stagingDir = staging
            val sink: (String, InputStream) -> Unit = { name, stream ->
                if (!name.contains("..")) {
                    val target = java.io.File(staging, name)
                    target.parentFile?.mkdirs()
                    target.outputStream().buffered().use { out ->
                        stream.copyTo(out)
                    }
                    stagedFiles[name] = target
                }
            }
            when (val read = BackupArchive.readWithFiles(input, sink)) {
                is AppResult.Err -> return AppResult.Err(read.error)
                is AppResult.Ok -> {
                    stagedManifest = read.value.second?.let {
                        BookFileSync.decodeManifest(it)
                    }
                    read.value.first
                }
            }
        } else {
            when (val read = BackupArchive.read(input)) {
                is AppResult.Err -> return AppResult.Err(read.error)
                is AppResult.Ok -> read.value
            }
        }
        val now = clock()
        val local = gather()
        val mergeResult = SyncMerger.merge(local, backup, now)
        val applied = applyMerged(mergeResult.payload)
        tombstones.replaceAll(mergeResult.payload.tombstones)
        var filesDownloaded = 0
        val manifest = stagedManifest
        if (fs != null && manifest != null) {
            filesDownloaded = fs.applyFromBackup(manifest, stagedFiles).downloaded
        }
        stagingDir?.deleteRecursively()
        val report = applied.copy(
            backendLabel = "backup file",
            conflicts = mergeResult.conflicts,
            filesDownloaded = filesDownloaded
        )
        syncLog.append(
            SyncLogEntry(
                at = now,
                backend = "Backup file",
                summary = report.summary,
                conflicts = report.conflicts
            )
        )
        return AppResult.Ok(report)
    }

    /** Records a user deletion so it travels on the next sync. */
    suspend fun recordDeletion(kind: String, naturalKey: String) {
        tombstones.record(kind, naturalKey)
    }

    /** The sync key for one local book (delete sites need it). */
    suspend fun bookKeyFor(book: Book): String {
        val packageId = book.extensionId?.let { extId ->
            installedExtensionDao.observeAll().first()
                .firstOrNull { it.id == extId }?.packageId
        }
        return SyncKeys.bookKey(book, packageId)
    }

    private fun maxOfNullable(a: Long?, b: Long?): Long? = when {
        a == null -> b
        b == null -> a
        else -> maxOf(a, b)
    }
}
