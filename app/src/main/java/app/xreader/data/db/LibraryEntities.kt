package app.xreader.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Library & content tables (PLAN §1.3). Chapter *text* never lives in
 * Room — only the on-disk path of the normalised content file — so a
 * 1,500-chapter book stays a small database.
 */

@Entity(
    tableName = "books",
    indices = [
        // v1.2.0 (DB v6, audit D8): the hot lookups were full scans.
        // (extensionId, remoteId) is the catalogue-tap identity
        // (BookDao.findByExtensionRemote); (mergedIntoId,
        // lastOpenedAt) backs the shelf filter + recent sort
        // (observeShelf); title backs findByTitleAndChapters;
        // finished backs finishedBooks/finishedCount (ExpService).
        Index(value = ["extensionId", "remoteId"]),
        Index(value = ["mergedIntoId", "lastOpenedAt"]),
        Index("title"),
        Index("finished")
    ]
)
data class Book(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val author: String = "",
    val coverPath: String? = null,
    /** v1.5.2 (§14.1): the CANONICAL cover field — the source-provided
     * cover URL, stored on the row at catalogue/book fetch. [coverPath]
     * is only its cached local rendering; every surface falls back to
     * this URL when no file exists. Null = the source provided none. */
    val coverUrl: String? = null,
    /** v1.5.2 (§7): per-book kind evidence — "TEXT" / "MANGA", or NULL
     * = UNKNOWN (no evidence yet). Never stamped from a source default;
     * see BookKindResolver152. */
    val kind: String? = null,
    val description: String = "",
    val sourceType: SourceType = SourceType.LOCAL_FILE,
    /** InstalledExtension.id when sourceType == EXTENSION. */
    val extensionId: Long? = null,
    /** The source's own id for this book (extension/OPDS), if any. */
    val remoteId: String? = null,
    val opdsServerId: Long? = null,
    val localFormat: BookFormat = BookFormat.UNKNOWN,
    val filePath: String? = null,
    val totalChapters: Int = 0,
    val totalWords: Long = 0,
    val addedAt: Long,
    val updatedAt: Long,
    val lastOpenedAt: Long? = null,
    val finished: Boolean = false,
    val userRating: Int? = null,
    /** Reversible duplicate merge (v1.0.2, sweep addendum): when
     * set, this entry is merged INTO the named book — it leaves the
     * Library lists and its chapters join the survivor's canonical
     * list. Setting it back to null (Unmerge) restores the entry
     * exactly; nothing is ever deleted by a merge. */
    val mergedIntoId: Long? = null,
    /** v1.0.8 (progressive chapter loading): false while a
     * source book's chapter list is still a partial prefix that
     * grows as the user reads. Every book synced before v1.0.8
     * holds its complete list, hence the true default. */
    val chapterListComplete: Boolean = true,
    /** v1.0.8: the progressive walk's next SITE page to fetch
     * (ascending for normal lists, descending for reversed
     * ones); 0 = no walk in progress. */
    val chapterWalkNextPage: Int = 0,
    /** v1.0.8: the site's own chapter-list page count when the
     * book page declared one (reversed walks start there). */
    val chapterWalkTotalPages: Int = 0,
    /** v1.0.8: the row count of a full chapter-list endpoint
     * page, learned on the walk's first fetch — a resumed
     * ascending walk needs it to recognise its last page. */
    val chapterWalkPageSize: Int = 0,
    /** v1.3.0 delta (V120-CHECK 45/52): Library membership.
     * Opening a catalogue book creates its row as a PREVIEW
     * (false): the row backs the book screen, the reader and
     * downloads, but it is NOT in the Library — the shelf
     * (observeShelf) shows members only. Membership begins at
     * an explicit act: Add to library, the first chapter
     * actually read, or a download. Rows that existed before
     * the v7 migration default to members (true) — nothing
     * the user already has ever disappears. */
    val inLibrary: Boolean = true,
    /** v1.5.5 (§2 epoch hygiene): the source-definition epoch
     * this book's chapter list was last COMPLETED under (see
     * BundledDefaults.BUNDLED_DEFINITIONS_EPOCH). 0 = never
     * stamped under the epoch rule (every pre-v1.5.5 row, and
     * every book whose source carries no definition epoch —
     * learned/link/player sources are always-current and never
     * consult this field). When it differs from the source
     * definition's current epoch, SourceBookSync treats
     * [chapterListComplete] as false and discards the saved
     * walk cursor before the next sync; stored chapter rows
     * are never deleted by the rule. Added by MIGRATION_8_9
     * as a plain INTEGER column defaulting to 0. Declared
     * LAST so no existing positional construction shifts. */
    val syncedEpoch: Int = 0,
    /** v1.5.6 (§3.4(d)): for NEW in-place imports, the document URI
     * of the file where it lives on the device (lasting read
     * permission taken at pick time). The app holds NO copy of
     * such a book — it reads the file there. Null for retained-copy
     * books (population (a), OPDS, legacy) and remote books.
     * Declared LAST so no existing positional construction shifts.
     * Added by MIGRATION_9_10 as a plain nullable TEXT column. */
    val documentUri: String? = null,
    /** v1.5.6 (§3.7): the file fingerprint
     * "displayName|sizeBytes|sha256" that keys the reading-memory
     * record — re-adding the identical file restores place and
     * bookmarks. Null when no hash was captured (old legacy rows).
     * Added by MIGRATION_9_10 as a plain nullable TEXT column. */
    val fileFingerprint: String? = null
)

@Entity(
    tableName = "chapters",
    foreignKeys = [
        ForeignKey(
            entity = Book::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("bookId"),
        Index(value = ["bookId", "index"], unique = true),
        // v1.2.0 (DB v6, audit D15): getDownloadedChapters filters
        // downloadState = 'READY' across ALL books — neither
        // existing index leads with downloadState.
        Index(value = ["downloadState", "bookId", "index"])
    ]
)
data class Chapter(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    /** Zero-based position in the book. (Column named via field; "index" is
     * quoted by Room's generated SQL.) */
    val index: Int,
    val title: String,
    val remoteId: String? = null,
    /** Normalised content file under content/<bookId>/ — null until downloaded/parsed. */
    val contentPath: String? = null,
    val wordCount: Int = 0,
    val downloadState: DownloadState = DownloadState.NONE,
    val read: Boolean = false,
    val updatedAt: Long,
    /** v1.0.6.3: the release date the source printed for this
     * chapter (e.g. "09.09.2026"), null when the source prints
     * none. Last field + defaulted: every existing construction
     * (imports, OPDS, tests) stays untouched. Added by
     * MIGRATION_3_4 as a plain TEXT column. */
    val dateText: String? = null,
    /** v1.5.2 (§12.4): body provenance — "USER" (explicit download) /
     * "AUTOMATIC" (reading cache / prefetch) / null (pre-v1.5.2 row,
     * re-classified by the §8 pass heuristic). "Downloaded" means
     * USER + READY, everywhere (§12.9). */
    val bodyOrigin: String? = null,
    /** v1.5.8 (E1): the scanlator the source credits for this
     * chapter (extension-api [app.xreader.extensionapi.SourceChapter]
     * .scanlator, normalised at the adapter seam). Null when the
     * source credits none — declarative/JS sources leave it null,
     * honest absence. Declared LAST + defaulted like [dateText]:
     * every existing construction stays untouched. Added by
     * MIGRATION_10_11 as a plain nullable TEXT column. */
    val scanlator: String? = null,
    /** v1.5.11 (transplant, Verdict 1): the source's private
     * per-chapter state (the donor SChapter.memo JsonObject,
     * carried as its JSON text — extension-api
     * [app.xreader.extensionapi.SourceChapter].memo). The
     * donor persists memo DB-round-trip; until v1.5.11 Xreader
     * dropped it at every seam, so an extension that requires
     * it at page-list time (Asura) failed forever. Null when
     * the source minted none — declarative/JS sources leave
     * it null, honest absence. Declared LAST + defaulted like
     * [scanlator]: every existing construction stays untouched.
     * Added by MIGRATION_11_12 as a plain nullable TEXT column. */
    val memo: String? = null
)

/** Multi-source failover mapping (Phase 3): this chapter also exists on
 * another source, matched by title/number with a confidence score. */
@Entity(
    tableName = "chapter_source_map",
    foreignKeys = [
        ForeignKey(
            entity = Chapter::class,
            parentColumns = ["id"],
            childColumns = ["chapterId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("chapterId")]
)
data class ChapterSourceMap(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val chapterId: Long,
    val altExtensionId: Long,
    val altRemoteId: String,
    val matchConfidence: Float,
    val verified: Boolean = false
)

/** One row per book: where the reader left off. The locator (chapter +
 * char offset + fraction) is stable across font-size changes (§1.3). */
@Entity(
    tableName = "reading_progress",
    foreignKeys = [
        ForeignKey(
            entity = Book::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    // v1.2.0 (DB v6, audit D15): observeRecent sorts the whole
    // table by updatedAt on every progress write.
    indices = [Index("updatedAt")]
)
data class ReadingProgress(
    @PrimaryKey val bookId: Long,
    val chapterId: Long,
    val chapterIndex: Int,
    val charOffset: Int = 0,
    val fraction: Float = 0f,
    val scrollFraction: Float = 0f,
    val updatedAt: Long,
    val deviceId: String = "",
    /** v1.5.6 (§3.2): the document locator for LOCAL books —
     * engine locator JSON (EPUB section / PDF page form, or the
     * migration's legacyChapter form). Null for remote books and
     * for chapter-keyed rows that predate schema 10. Added by
     * MIGRATION_9_10 as a plain nullable TEXT column. */
    val locatorJson: String? = null,
    /** v1.5.6 (§3.2/§3.5): a short text anchor that re-finds the
     * place after a re-layout (the chapter/section title at
     * migration time). Added by MIGRATION_9_10. */
    val anchorText: String? = null
)

@Entity(
    tableName = "bookmarks",
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
data class Bookmark(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val chapterId: Long,
    val chapterIndex: Int,
    val charOffset: Int = 0,
    val label: String = "",
    val createdAt: Long,
    val updatedAt: Long,
    /** v1.5.6 (§3.2): document locator JSON for local books
     * (see [ReadingProgress.locatorJson]). Added by
     * MIGRATION_9_10 as a plain nullable TEXT column. */
    val locatorJson: String? = null
)

@Entity(
    tableName = "highlights",
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
data class Highlight(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val chapterId: Long,
    val startOffset: Int,
    val endOffset: Int,
    val quotedText: String,
    /** Colour token name (e.g. "amber"); the UI maps it to a palette colour. */
    val color: String = "amber",
    val note: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    /** v1.5.6 (§3.2): document locator JSON for local books
     * (see [ReadingProgress.locatorJson]). Added by
     * MIGRATION_9_10 as a plain nullable TEXT column. */
    val locatorJson: String? = null
)

/** v1.5.6 (§3.7): the reading-memory NOTE that survives a local
 * book's deletion — the file's fingerprint, the reading position
 * and bookmarks/highlights as locators. A few KB, NO book content.
 * NO foreign key on purpose: the record must survive the book
 * row's deletion so re-adding the identical file can resume. */
@Entity(tableName = "reading_memory")
data class ReadingMemory(
    @PrimaryKey val fingerprint: String,
    val displayName: String,
    val sizeBytes: Long,
    /** Stored format name ([BookFormat].name, e.g. "EPUB"). */
    val format: String,
    @androidx.room.ColumnInfo(defaultValue = "0")
    val fraction: Float = 0f,
    val locatorJson: String? = null,
    @androidx.room.ColumnInfo(defaultValue = "'[]'")
    val bookmarksJson: String = "[]",
    @androidx.room.ColumnInfo(defaultValue = "'[]'")
    val highlightsJson: String = "[]",
    val updatedAt: Long
)

/** v1.5.6 (§3.1 row 7 / §3.4(d)): the Contents of an in-place
 * local book, captured at registration from the probe's own
 * structure — EPUB target {"section":i}, PDF target {"page":p}.
 * In-place books have NO chapter rows; this table is their
 * outline. Migrated legacy books carry
 * {"legacyChapterIndex":i} targets (MIGRATION_9_10). */
@Entity(
    tableName = "local_outline",
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
data class LocalOutline(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val idx: Int,
    val title: String,
    val targetJson: String
)

/** One outline entry as the importer/deletion seams pass it
 * around before a book id exists: a title plus its target JSON
 * (see [LocalOutline]). */
data class OutlineTarget(
    val title: String,
    val targetJson: String
)

/** A self-hosted library server (Komga / Kavita / Calibre-Web, §4).
 * The password itself NEVER lives here — [passwordRef] is the KeyVault
 * entry name that holds it. */
@Entity(tableName = "opds_servers")
data class OpdsServer(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val baseUrl: String,
    val username: String? = null,
    val passwordRef: String? = null
)
