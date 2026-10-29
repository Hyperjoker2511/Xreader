package app.xreader.data.sync

import app.xreader.data.db.Book
import app.xreader.data.db.SourceType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The sync payload (PLAN §7): everything that travels between the
 * user's devices — library METADATA (never book files by default),
 * reading progress, bookmarks, highlights, lore, terminology,
 * profile/gamification state, and reader/AI preferences. Secrets are
 * structurally absent: this file is built only from Room rows and
 * preference owners that never see the KeyVault (§9.5) — the Gemini
 * key, OPDS passwords, session cookies, and WebDAV password cannot
 * appear here, which the backup test asserts by construction.
 *
 * Records identify books by a stable cross-device [BookRecord.syncKey]
 * (Room ids are per-device and meaningless elsewhere), and chapters
 * by their index inside the book.
 */
@Serializable
data class SyncPayload(
    val format: Int = FORMAT,
    val deviceId: String = "",
    val exportedAt: Long = 0,
    val books: List<BookRecord> = emptyList(),
    val progress: List<ProgressRecord> = emptyList(),
    val bookmarks: List<BookmarkRecord> = emptyList(),
    val highlights: List<HighlightRecord> = emptyList(),
    val lore: List<LoreRecord> = emptyList(),
    val loreRelations: List<LoreRelationRecord> = emptyList(),
    val terminology: List<TermRecord> = emptyList(),
    val profile: ProfileRecord? = null,
    val dailyActivity: List<DayRecord> = emptyList(),
    /** ReaderConfig as its DataStore JSON-ish map, opaque here. */
    val readerPrefs: Map<String, String> = emptyMap(),
    val aiModelChoice: String? = null,
    val tombstones: List<Tombstone> = emptyList(),
    // ── v1.0.2 (R02): library organisation ──────────────────────
    // All defaulted, so a v1.0.1 payload (or a backup written by
    // it) decodes with plain defaults and an old build ignores the
    // new fields (ignoreUnknownKeys). Membership travels by book
    // syncKey, never by device-local Room id. Credentials, cookies,
    // sessions, cover palettes and search history remain excluded
    // by construction, exactly as before.
    val shelves: List<ShelfRecord> = emptyList(),
    val tags: List<TagRecord> = emptyList(),
    val bookTags: List<BookTagRecord> = emptyList(),
    val followedTagSlugs: List<String> = emptyList(),
    val favouriteBookKeys: List<String> = emptyList(),
    /** Custom-theme PARAMETERS (seed/end/version), never derived
     * colours — the receiving device regenerates its own scheme. */
    val themeSync: ThemeSyncRecord? = null
) {
    companion object {
        const val FORMAT = 1

        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun encode(payload: SyncPayload): String = json.encodeToString(serializer(), payload)

        fun decode(raw: String): SyncPayload? =
            runCatching { json.decodeFromString(serializer(), raw) }.getOrNull()
                ?.takeIf { it.format == FORMAT }
    }
}

@Serializable
data class BookRecord(
    val syncKey: String,
    val title: String,
    val author: String = "",
    val description: String = "",
    val sourceType: String = "LOCAL_FILE",
    val totalChapters: Int = 0,
    val totalWords: Long = 0,
    val finished: Boolean = false,
    val userRating: Int? = null,
    val updatedAt: Long = 0,
    val lastOpenedAt: Long? = null,
    /** v1.0.1: the reader's own status for this book (BookMeta). */
    val readingStatus: String = "NONE"
)

@Serializable
data class ProgressRecord(
    val bookKey: String,
    val chapterIndex: Int,
    val charOffset: Int = 0,
    val fraction: Float = 0f,
    val scrollFraction: Float = 0f,
    val updatedAt: Long = 0,
    val deviceId: String = ""
)

@Serializable
data class BookmarkRecord(
    val bookKey: String,
    val chapterIndex: Int,
    val charOffset: Int = 0,
    val label: String = "",
    val createdAt: Long = 0,
    val updatedAt: Long = 0
) {
    val naturalKey: String get() = "$bookKey|bm|$chapterIndex|$charOffset"
}

@Serializable
data class HighlightRecord(
    val bookKey: String,
    val chapterIndex: Int,
    val startOffset: Int,
    val endOffset: Int,
    val quotedText: String = "",
    val color: String = "amber",
    val note: String? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0
) {
    val naturalKey: String get() = "$bookKey|hl|$chapterIndex|$startOffset|$endOffset"
}

@Serializable
data class LoreRecord(
    val bookKey: String,
    val type: String,
    val name: String,
    val aliasesJson: String = "[]",
    val description: String = "",
    val firstChapterIndex: Int = 0,
    val userLocked: Boolean = false,
    val updatedAt: Long = 0
) {
    val naturalKey: String
        get() = "$bookKey|lore|${type}|${name.trim().lowercase()}"
}

@Serializable
data class LoreRelationRecord(
    val bookKey: String,
    val fromName: String,
    val toName: String,
    val label: String
) {
    val naturalKey: String
        get() = "$bookKey|rel|${fromName.trim().lowercase()}|${toName.trim().lowercase()}|" +
            label.trim().lowercase()
}

@Serializable
data class TermRecord(
    val bookKey: String,
    val sourceTerm: String,
    val chosenTranslation: String,
    val locked: Boolean = false
) {
    val naturalKey: String get() = "$bookKey|term|${sourceTerm.trim().lowercase()}"
}

@Serializable
data class ProfileRecord(
    val displayName: String = "",
    /** v1.0.1: the Me-screen avatar disc colour choice. */
    val avatarColour: String = "",
    val totalExp: Long = 0,
    val level: Int = 1,
    val dailyGoalMinutes: Int = 20,
    val createdAt: Long = 0
)

@Serializable
data class DayRecord(
    val date: String,
    val minutesRead: Int = 0,
    val chaptersFinished: Int = 0,
    val expEarned: Long = 0,
    val goalMet: Boolean = false
)

/** A user shelf (v1.0.2, R21/R02). Built-in shelves are computed
 * on each device and never travel. A shelf deleted on one device
 * travels as a "shelf" tombstone keyed by name. */
@Serializable
data class ShelfRecord(
    val name: String,
    val kind: String = "MANUAL",
    val rule: String? = null,
    val ruleValue: String = "",
    val sortOrder: Int = 0,
    val hidden: Boolean = false,
    val sort: String = "RECENTLY_READ",
    val updatedAt: Long = 0,
    val memberBookKeys: List<String> = emptyList()
)

@Serializable
data class TagRecord(
    val slug: String,
    val displayName: String,
    val family: String = ""
)

@Serializable
data class BookTagRecord(
    val bookKey: String,
    val slug: String,
    val origin: String = "SOURCE",
    /** A user correction (tag removed from this book) — in a merge,
     * suppressed wins, so a correction is never resurrected. */
    val suppressed: Boolean = false
) {
    val naturalKey: String get() = "$bookKey|tag|$slug"
}

/** Make-your-own theme parameters + the two appearance choices
 * that define the look (v1.0.2, R02/R04). */
@Serializable
data class ThemeSyncRecord(
    /** AppearanceValidation.encodeCustomSchemes rows. */
    val customSchemesEncoded: String = "",
    val colourScheme: String = "",
    val trueBlack: Boolean = false,
    val updatedAt: Long = 0
)

/** A deletion that must travel: [kind] is "bookmark" / "highlight" /
 * "lore" / "term" / "shelf"; [key] is the record's natural key. */
@Serializable
data class Tombstone(
    val kind: String,
    val key: String,
    val deletedAt: Long,
    val deviceId: String = ""
)

/** The organisation slice of a payload, ferried between
 * SyncService and its storage bridges (v1.0.2, R02). */
data class OrganisationSection(
    val shelves: List<ShelfRecord> = emptyList(),
    val tags: List<TagRecord> = emptyList(),
    val bookTags: List<BookTagRecord> = emptyList(),
    val followedTagSlugs: List<String> = emptyList(),
    val favouriteBookKeys: List<String> = emptyList(),
    val themeSync: ThemeSyncRecord? = null
)

/** Stable cross-device book identity (see [SyncPayload] doc). */
object SyncKeys {

    fun bookKey(book: Book, extensionPackageId: String?): String = when {
        book.sourceType == SourceType.EXTENSION &&
            extensionPackageId != null && !book.remoteId.isNullOrBlank() ->
            "ext:$extensionPackageId:${book.remoteId}"
        book.sourceType == SourceType.OPDS && !book.remoteId.isNullOrBlank() ->
            "opds:${book.remoteId}"
        else ->
            "local:${normalise(book.title)}|${normalise(book.author)}|${book.totalChapters}"
    }

    fun normalise(value: String): String =
        value.trim().lowercase().replace(Regex("\\s+"), " ")
}
