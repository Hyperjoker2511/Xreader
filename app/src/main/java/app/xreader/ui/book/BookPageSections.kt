package app.xreader.ui.book

import app.xreader.data.db.Chapter
import app.xreader.data.db.DownloadState
import app.xreader.data.extension.SourceBookIds

/**
 * v1.0.5 (B1, plan §B/D26): the book page's Chapters section is
 * ALWAYS present — a header with the count, the list when there is
 * one, and a real recovery section when there isn't. This object
 * is the pure decision behind that section: which state renders,
 * and which recovery doors it may offer. The doors are capability
 * facts, not guesses:
 *  - "Open the source's page" exists only when the book's remote
 *    id actually decodes to an http(s) page address (declarative
 *    sources store the book-page URL; opaque extension ids are
 *    not addresses and never get the door);
 *  - "Go to Repair" exists only for link sources (Repair lives on
 *    the source's Details page and only link sources carry the
 *    editable contract Repair works on);
 *  - "Re-check now" is offered in both failure states (the caller
 *    wires it to the chapter fetch + the source's honest drive).
 * Pure, so the whole table is unit-tested.
 */
enum class ChaptersSectionKind {
    /** Chapters exist — the list renders. */
    LIST,

    /** No chapters yet and the list is being pulled. */
    LOADING,

    /** The chapter fetch failed — the error is shown, with doors. */
    ERROR,

    /** A source book whose list came back empty — doors. */
    EMPTY_SOURCE,

    /** A local book with no chapters — a quiet fact, no doors. */
    EMPTY_LOCAL
}

data class ChaptersSectionState(
    val kind: ChaptersSectionKind,
    val chapterCount: Int,
    /** The book's page on its source's site, when it is a URL. */
    val sourcePageUrl: String?,
    val isLinkSource: Boolean
) {
    /** The recovery-doors card renders for exactly these kinds. */
    val showRecoveryDoors: Boolean
        get() = kind == ChaptersSectionKind.EMPTY_SOURCE ||
            kind == ChaptersSectionKind.ERROR

    val canOpenSourcePage: Boolean
        get() = showRecoveryDoors && sourcePageUrl != null

    val canGoToRepair: Boolean
        get() = showRecoveryDoors && isLinkSource
}

object ChaptersSection {

    /** The book's page address on its source, or null when the
     * stored remote id is not a web address. */
    fun sourcePageUrl(remoteId: String?): String? {
        val (_, bookRemoteId) = SourceBookIds.decode(remoteId) ?: return null
        return bookRemoteId.takeIf {
            it.startsWith("https://") || it.startsWith("http://")
        }
    }

    fun state(
        chapterCount: Int,
        chaptersLoading: Boolean,
        chaptersError: String?,
        isExtensionBook: Boolean,
        remoteId: String?,
        isLinkSource: Boolean
    ): ChaptersSectionState {
        val kind = when {
            chapterCount > 0 -> ChaptersSectionKind.LIST
            chaptersLoading -> ChaptersSectionKind.LOADING
            chaptersError != null -> ChaptersSectionKind.ERROR
            isExtensionBook -> ChaptersSectionKind.EMPTY_SOURCE
            else -> ChaptersSectionKind.EMPTY_LOCAL
        }
        return ChaptersSectionState(
            kind = kind,
            chapterCount = chapterCount,
            sourcePageUrl = sourcePageUrl(remoteId),
            isLinkSource = isLinkSource
        )
    }
}

/**
 * v1.0.5 (B2 + E6): the book page's Download sheet offers the
 * three choices a reader actually makes — the whole book, the
 * next ten, or everything not yet read. This object is the pure
 * range arithmetic behind the sheet AND behind the ViewModel's
 * older download helpers, so the counts the sheet shows are the
 * counts that get queued — one definition, unit-tested.
 *
 * A chapter is downloadable when it isn't already on the device
 * and it has a source locator — the exact predicate the download
 * controller's enqueueBook has always used. Chapters arrive in
 * reading order (the DAO orders by index), and every range keeps
 * that order.
 */
enum class BookDownloadChoice { ALL, NEXT_10, UP_TO_LATEST_UNREAD }

object BookDownloadRanges {

    const val NEXT_COUNT = 10

    fun downloadable(chapters: List<Chapter>): List<Chapter> =
        chapters.filter {
            it.downloadState != DownloadState.READY &&
                !it.remoteId.isNullOrBlank()
        }

    fun range(
        chapters: List<Chapter>,
        choice: BookDownloadChoice
    ): List<Chapter> {
        val missing = downloadable(chapters)
        return when (choice) {
            BookDownloadChoice.ALL -> missing
            BookDownloadChoice.NEXT_10 -> missing.take(NEXT_COUNT)
            // Every not-yet-read chapter is by definition at or
            // before the latest unread one — the unread set IS
            // the range "up to the latest unread".
            BookDownloadChoice.UP_TO_LATEST_UNREAD ->
                missing.filter { !it.read }
        }
    }

    /** The count each sheet row shows — computed by [range]
     * itself, so a row can never promise a different number
     * than the tap will queue. */
    fun counts(chapters: List<Chapter>): Map<BookDownloadChoice, Int> =
        BookDownloadChoice.entries.associateWith { range(chapters, it).size }
}
