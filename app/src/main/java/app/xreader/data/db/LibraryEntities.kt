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

@Entity(tableName = "books")
data class Book(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val author: String = "",
    val coverPath: String? = null,
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
    val chapterWalkPageSize: Int = 0
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
    indices = [Index("bookId"), Index(value = ["bookId", "index"], unique = true)]
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
    val dateText: String? = null
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
    ]
)
data class ReadingProgress(
    @PrimaryKey val bookId: Long,
    val chapterId: Long,
    val chapterIndex: Int,
    val charOffset: Int = 0,
    val fraction: Float = 0f,
    val scrollFraction: Float = 0f,
    val updatedAt: Long,
    val deviceId: String = ""
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
    val updatedAt: Long
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
    val updatedAt: Long
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
