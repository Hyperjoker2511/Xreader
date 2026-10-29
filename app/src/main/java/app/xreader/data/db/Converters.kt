package app.xreader.data.db

import androidx.room.TypeConverter

/** Where a book came from (PLAN §1.3). */
enum class SourceType { LOCAL_FILE, EXTENSION, OPDS }

/** On-disk formats the import pipeline recognises (PLAN §4). */
enum class BookFormat { EPUB, PDF, TXT, MARKDOWN, FB2, HTML, CBZ, MOBI, AZW3, CBR, UNKNOWN }

enum class DownloadState { NONE, QUEUED, DOWNLOADING, READY, FAILED }

enum class TrustState { TRUSTED, CHANGED_REQUIRES_RETRUST }

enum class ExtensionKind { DECLARATIVE, JS, DEX }

enum class DownloadKind { CHAPTER, PREFETCH, EXPORT }

enum class QueueState { QUEUED, RUNNING, PAUSED, DONE, FAILED, CANCELLED }

enum class LoreType { CHARACTER, FACTION, TERM, LOCATION, ITEM }

/**
 * Enum ⇄ String converters. Reads are defensive (PLAN §1.5): an unknown
 * stored value (future version, corrupt row) degrades to a safe default
 * instead of crashing the reader.
 */
class Converters {
    @TypeConverter fun sourceTypeToString(v: SourceType): String = v.name
    @TypeConverter fun stringToSourceType(v: String): SourceType =
        runCatching { SourceType.valueOf(v) }.getOrDefault(SourceType.LOCAL_FILE)

    @TypeConverter fun bookFormatToString(v: BookFormat): String = v.name
    @TypeConverter fun stringToBookFormat(v: String): BookFormat =
        runCatching { BookFormat.valueOf(v) }.getOrDefault(BookFormat.UNKNOWN)

    @TypeConverter fun downloadStateToString(v: DownloadState): String = v.name
    @TypeConverter fun stringToDownloadState(v: String): DownloadState =
        runCatching { DownloadState.valueOf(v) }.getOrDefault(DownloadState.NONE)

    @TypeConverter fun trustStateToString(v: TrustState): String = v.name
    @TypeConverter fun stringToTrustState(v: String): TrustState =
        runCatching { TrustState.valueOf(v) }.getOrDefault(TrustState.CHANGED_REQUIRES_RETRUST)

    @TypeConverter fun extensionKindToString(v: ExtensionKind): String = v.name
    @TypeConverter fun stringToExtensionKind(v: String): ExtensionKind =
        runCatching { ExtensionKind.valueOf(v) }.getOrDefault(ExtensionKind.DECLARATIVE)

    @TypeConverter fun downloadKindToString(v: DownloadKind): String = v.name
    @TypeConverter fun stringToDownloadKind(v: String): DownloadKind =
        runCatching { DownloadKind.valueOf(v) }.getOrDefault(DownloadKind.CHAPTER)

    @TypeConverter fun queueStateToString(v: QueueState): String = v.name
    @TypeConverter fun stringToQueueState(v: String): QueueState =
        runCatching { QueueState.valueOf(v) }.getOrDefault(QueueState.QUEUED)

    @TypeConverter fun loreTypeToString(v: LoreType): String = v.name
    @TypeConverter fun stringToLoreType(v: String): LoreType =
        runCatching { LoreType.valueOf(v) }.getOrDefault(LoreType.TERM)

    // v1.0.2 (DB v3) organisation enums — same defensive reads.
    @TypeConverter fun shelfKindToString(v: ShelfKind): String = v.name
    @TypeConverter fun stringToShelfKind(v: String): ShelfKind =
        runCatching { ShelfKind.valueOf(v) }.getOrDefault(ShelfKind.MANUAL)

    @TypeConverter fun smartRuleToString(v: SmartRule?): String? = v?.name
    @TypeConverter fun stringToSmartRule(v: String?): SmartRule? =
        v?.let { runCatching { SmartRule.valueOf(it) }.getOrNull() }

    @TypeConverter fun tagOriginToString(v: TagOrigin): String = v.name
    @TypeConverter fun stringToTagOrigin(v: String): TagOrigin =
        runCatching { TagOrigin.valueOf(v) }.getOrDefault(TagOrigin.SOURCE)

    @TypeConverter fun vocabStateToString(v: VocabState): String = v.name
    @TypeConverter fun stringToVocabState(v: String): VocabState =
        runCatching { VocabState.valueOf(v) }.getOrDefault(VocabState.NEW)
}
