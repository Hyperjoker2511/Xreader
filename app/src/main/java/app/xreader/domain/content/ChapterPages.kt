package app.xreader.domain.content

import kotlinx.serialization.Serializable

/**
 * One manga chapter's pages (manga programme, slice M1): the
 * ordered page list a MANGA source's chapter content carries
 * inside its [ChapterContent] record (the `pages` field), so
 * an image chapter persists through exactly the storage every
 * chapter already uses — `content/<bookId>/<chapterId>.json`,
 * written by the same writeChapterContent path, with the
 * chapter row's contentPath pointing at it.
 *
 * Each page starts life as a remote [ChapterPage.url] (the
 * extraction result). Downloads (slice M3) fill
 * [ChapterPage.localFile] per page as the bytes land; the
 * reader (slice M2) prefers the local file when present and
 * the remote URL otherwise. Text chapters never carry this
 * record (their `pages` stays null, and with encodeDefaults
 * off their stored files are byte-identical to before).
 *
 * M3 page-file naming law: a downloaded page's file is named
 * `img-p<tag>-<asset name>`, where the tag is the first four
 * bytes of the SHA-256 of the chapter's locator, hex-encoded —
 * chapter-scoped because every chapter of a series names its
 * pages alike (001.jpg, 002.jpg…) while all chapters share
 * the one `content/<bookId>/` directory. A page whose file
 * already exists is never re-fetched, so a retry lands only
 * the pages still missing.
 */
@Serializable
data class ChapterPage(
    /** The page image's absolute remote URL. */
    val url: String,
    /** The downloaded copy's file name inside the book's
     * content directory (the `img-<name>` layout chapter
     * images already use), or null while the page has only
     * been read online. Filled by the download pass (M3);
     * the URL above stays the page's identity either way. */
    val localFile: String? = null
)

/** A chapter's ordered pages, in reading order. */
@Serializable
data class ChapterPages(
    val pages: List<ChapterPage> = emptyList(),
    /** K6: the Referer the page images expect, when the
     * source's own image requests carry one — the Keiyoushi
     * adapter stamps its source's base URL here (the value
     * Mihon sources overwhelmingly put in their imageRequest
     * Referer), and the image reader sends it on its Coil
     * requests for these pages. Downloads never needed it:
     * they fetch through the source's own image request,
     * headers and all. Null for every other manga kind (their
     * stored records stay byte-identical — encodeDefaults is
     * off and a null never serialises). */
    val referer: String? = null
) {
    val size: Int get() = pages.size

    val isEmpty: Boolean get() = pages.isEmpty()

    /** The page URLs in reading order (the reader's online
     * source of truth). */
    val urls: List<String> get() = pages.map { it.url }

    /** A copy with one page's downloaded file recorded —
     * the M3 download pass's write-back shape. An out-of-range
     * index returns this record unchanged. */
    fun withLocalFile(index: Int, fileName: String): ChapterPages {
        if (index !in pages.indices) return this
        return ChapterPages(
            pages.mapIndexed { i, page ->
                if (i == index) page.copy(localFile = fileName) else page
            },
            referer = referer
        )
    }

    /** How many pages already have a downloaded copy. */
    val downloadedCount: Int get() = pages.count { it.localFile != null }
}
