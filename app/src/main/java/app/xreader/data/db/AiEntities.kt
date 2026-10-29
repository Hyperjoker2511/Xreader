package app.xreader.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** AI / lore tables (PLAN §1.3) — the LoreKeeper's memory. */

@Entity(
    tableName = "lore_entities",
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
data class LoreEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val type: LoreType,
    val name: String,
    /** JSON array of alternate names/spellings. */
    val aliasesJson: String = "[]",
    val description: String = "",
    val firstChapterIndex: Int = 0,
    /** User edits win over re-extraction (§5). */
    val userLocked: Boolean = false,
    val updatedAt: Long
)

@Entity(
    tableName = "lore_relations",
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
data class LoreRelation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val fromEntityId: Long,
    val toEntityId: Long,
    val relationLabel: String
)

@Entity(
    tableName = "lore_art",
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
data class LoreArt(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val entityId: Long? = null,
    val chapterId: Long? = null,
    val passageQuote: String,
    val prompt: String,
    /** WebP under lore-art/<bookId>/. */
    val imagePath: String,
    val createdAt: Long
)

/** Cached AI summaries; [contentHash] invalidates when the chapter changes.
 * Arc recaps are summaries-of-summaries stored the same way with a range
 * marker chapterId (Phase 5 convention). */
@Entity(
    tableName = "ai_summaries",
    foreignKeys = [
        ForeignKey(
            entity = Book::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["bookId", "chapterId"], unique = true)]
)
data class AiSummary(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val chapterId: Long,
    val contentHash: String,
    /** JSON array of bullet strings. */
    val bulletsJson: String,
    val modelUsed: String,
    val createdAt: Long
)

/** Per-book glossary for RAW translation (§5) — the ReTranslate
 * Knowledge-Base lesson applied to reading. */
@Entity(
    tableName = "terminology",
    foreignKeys = [
        ForeignKey(
            entity = Book::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["bookId", "sourceTerm"], unique = true)]
)
data class TerminologyEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val sourceTerm: String,
    val chosenTranslation: String,
    val locked: Boolean = false
)
