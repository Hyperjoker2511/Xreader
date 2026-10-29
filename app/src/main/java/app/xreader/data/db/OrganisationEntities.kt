package app.xreader.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Library organisation tables (v1.0.2, DB v3 — R21 shelves, R22
 * tags, sweep "Words I'm learning"). Built-in shelves (All,
 * Favourites, Downloaded, Behind on) are NOT rows — they are
 * computed by ShelfLogic from book/meta state, so they can never be
 * deleted or desynchronised. Only USER shelves live here.
 *
 * Chapter text, covers, and palettes still never live in Room
 * (§1.3): these tables are names, memberships, and learning state.
 */

enum class ShelfKind { MANUAL, SMART }

/** The rule a SMART shelf fills itself by (R21). */
enum class SmartRule { TAG, UPDATED_THIS_WEEK, UNREAD_DOWNLOADS, DOWNLOADED, READING_STATUS }

@Entity(tableName = "shelves")
data class Shelf(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val kind: ShelfKind = ShelfKind.MANUAL,
    /** Set only for SMART shelves: the rule that owns membership. */
    val rule: SmartRule? = null,
    /** Rule parameter: tag slug, or a ReadingStatus name. */
    val ruleValue: String = "",
    val sortOrder: Int = 0,
    val hidden: Boolean = false,
    /** Per-shelf sort memory (R21): a LibrarySort name. */
    val sort: String = "RECENTLY_READ",
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(
    tableName = "shelf_books",
    primaryKeys = ["shelfId", "bookId"],
    foreignKeys = [
        ForeignKey(
            entity = Shelf::class,
            parentColumns = ["id"],
            childColumns = ["shelfId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = Book::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("bookId")]
)
data class ShelfBook(
    val shelfId: Long,
    val bookId: Long,
    val addedAt: Long
)

/** A canonical tag (R22). Source labels are aliases/evidence; the
 * registry's display name is what the user sees. */
@Entity(tableName = "tags")
data class Tag(
    @PrimaryKey val slug: String,
    val displayName: String,
    /** Seed family (Genre, MC type, Theme…) — display grouping only. */
    val family: String = "",
    val createdAt: Long
)

enum class TagOrigin { SOURCE, FILE, INFERRED, USER }

@Entity(
    tableName = "book_tags",
    primaryKeys = ["bookId", "slug"],
    indices = [Index("slug")]
)
data class BookTag(
    val bookId: Long,
    val slug: String,
    val origin: TagOrigin = TagOrigin.SOURCE,
    /** The user removed this tag from this book (R22 corrections):
     * the row stays as a tombstone so a source refresh cannot
     * silently re-add what the user rejected. */
    val suppressed: Boolean = false
)

/** A followed tag (R22). "Favourite" is for books, "Pinned" for
 * sources/repos, "Followed" for tags — the words never mix. */
@Entity(tableName = "tag_follows")
data class TagFollow(
    @PrimaryKey val slug: String,
    val followedAt: Long
)

enum class VocabState { NEW, LEARNING, KNOWN }

/** One saved word (sweep addendum: KOReader-style vocabulary).
 * The sentence it was found in is kept — a word without its
 * sentence is a flashcard nobody remembers. */
@Entity(tableName = "vocab_words")
data class VocabWord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val word: String,
    val definition: String = "",
    val sentence: String = "",
    val bookId: Long? = null,
    val chapterIndex: Int = -1,
    val state: VocabState = VocabState.NEW,
    /** Spaced-review interval in days (VocabReview owns the ladder). */
    val intervalDays: Double = 0.0,
    val dueAt: Long = 0,
    val createdAt: Long,
    val updatedAt: Long
)
