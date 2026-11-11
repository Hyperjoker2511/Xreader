package app.xreader.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * v1.5.2 (Room v8) new tables — exactly the §16 total, nothing more:
 * history_records (§15.5), pending_operations (§1),
 * update_pass_journal (§8.5).
 */

/** §15.5: ONE History record PER BOOK (the QuickNovel copy), keyed by
 * the book's natural key (SyncKeys.bookKey shape), written at read
 * START and updated in place. No chapter identity is stored. */
@Entity(tableName = "history_records")
data class HistoryRecord(
    @PrimaryKey val bookKey: String,
    val bookId: Long? = null,
    val title: String,
    val bookUrl: String = "",
    val sourceName: String = "",
    val coverUrl: String? = null,
    val author: String = "",
    val tags: String = "",
    val rating: String = "",
    val synopsis: String = "",
    val totalChapters: Int = 0,
    val recordedAt: Long
)

/** §1: a durable pending operation keyed by vault session key. The
 * record NEVER holds session values — only a content watermark of the
 * vault record observed at creation. */
@Entity(
    tableName = "pending_operations",
    indices = [Index(value = ["sessionKey"], unique = true)]
)
data class PendingOperation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionKey: String,
    /** LINK_ADD / SOURCE_LOGIN */
    val flowKind: String,
    val host: String = "",
    /** WAITING / READY / RUNNING / SUCCEEDED / FAILED / CANCELLED / EXPIRED */
    val state: String = "WAITING",
    val createdAt: Long,
    val watermark: String = "none",
    val attemptCount: Int = 0,
    val lastFailureClass: String? = null,
    val claimantToken: String? = null,
    /** Redacted trace: "FROM>TO@millis:CATEGORY" entries joined by ";". */
    val trace: String = "",
    val category: String = "NONE"
)

/** §8.5: one journal row per (app versionCode, provider identity). */
@Entity(
    tableName = "update_pass_journal",
    primaryKeys = ["versionCode", "providerKey"]
)
data class UpdatePassJournalEntry(
    val versionCode: Int,
    val providerKey: String,
    /** prepare / validate / commit / cleanup / record / done */
    val stage: String = "prepare",
    /** validated / kept-needs-repair / proven-obsolete / pending */
    val outcome: String = "pending",
    val updatedAt: Long = 0
)
