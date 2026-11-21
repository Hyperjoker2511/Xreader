package app.xreader.data.prepare

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * v1.7 MR-2 (P1/P3, amendment A7): one converted page's
 * anchor — where the page's text begins inside its core
 * chapter, and how much of the chapter it holds. Offsets
 * are [app.xreader.domain.reader.ChapterText] char
 * offsets (the reader's universal coordinate system), so
 * a page place and a core place are two spellings of one
 * position.
 */
data class PdfPageAnchor(
    val pageIndex: Int,
    val chapterIndex: Int,
    val startCharOffsetInChapter: Int,
    val charLength: Int
)

/**
 * The conversion's per-page anchor table (A7): one entry
 * per page of the book, in page order, plus the book's
 * total char count. The pages of a chapter partition it
 * exactly — each page's [PdfPageAnchor.charLength] runs
 * to the next page's start (or the chapter's end), so an
 * empty page anchors with length 0 and place stays
 * truthful through it.
 *
 * Persisted as a small JSON in the book's `local-pdf`
 * directory, beside the strip (`anchors.json`, temp +
 * rename like the strip store): disposable with the
 * strip, invalid with it — a fingerprint change kills
 * the prepare record, and the re-conversion rewrites
 * this table before the new record lands.
 */
class PdfAnchorTable(
    val pages: List<PdfPageAnchor>,
    val totalCharCount: Int
) {
    /** The book's page count (the table's own span). */
    val pageCount: Int get() = pages.size

    private val chapterLengths: List<Int> = run {
        val lengths = sortedMapOf<Int, Int>()
        for (page in pages) {
            val end = page.startCharOffsetInChapter + page.charLength
            val chapter = page.chapterIndex
            if (end > (lengths[chapter] ?: 0)) lengths[chapter] = end
        }
        val maxChapter = pages.maxOfOrNull { it.chapterIndex } ?: -1
        (0..maxChapter).map { lengths[it] ?: 0 }
    }

    /** Chapter [chapterIndex]'s length in ChapterText
     * chars (0 for an index the table does not hold). */
    fun chapterLength(chapterIndex: Int): Int =
        chapterLengths.getOrElse(chapterIndex) { 0 }

    /** The book-level char offset of an in-chapter place:
     * every earlier chapter's length plus [charOffset]. */
    fun bookCharOffset(chapterIndex: Int, charOffset: Int): Int {
        var total = 0
        for (chapter in 0 until chapterIndex.coerceAtMost(chapterLengths.size)) {
            total += chapterLengths[chapter]
        }
        return total + charOffset
    }

    /** The anchor for [page] (clamped into the table), or
     * null when the table is empty. */
    fun anchorForPage(page: Int): PdfPageAnchor? {
        if (pages.isEmpty()) return null
        return pages[page.coerceIn(0, pages.lastIndex)]
    }

    /** [chapterIndex]'s anchors, in page order. */
    fun anchorsForChapter(chapterIndex: Int): List<PdfPageAnchor> =
        pages.filter { it.chapterIndex == chapterIndex }

    companion object {
        const val ANCHORS_FILE = "anchors.json"

        /** The persisted table from the book's local-pdf
         * [dir], or null when absent or malformed (the
         * book then reads as unconverted until the local
         * open flow re-converts it). */
        fun load(dir: File): PdfAnchorTable? = runCatching {
            val file = File(dir, ANCHORS_FILE)
            if (!file.isFile) return null
            val root = JSONObject(file.readText())
            val total = root.optInt("totalChars", -1)
            if (total < 0) return null
            val array = root.getJSONArray("pages")
            val pages = ArrayList<PdfPageAnchor>(array.length())
            for (i in 0 until array.length()) {
                val entry = array.getJSONArray(i)
                if (entry.length() < 4) return null
                pages += PdfPageAnchor(
                    pageIndex = entry.getInt(0),
                    chapterIndex = entry.getInt(1),
                    startCharOffsetInChapter = entry.getInt(2),
                    charLength = entry.getInt(3)
                )
            }
            if (pages.isEmpty()) return null
            if (pages.withIndex().any { (i, page) -> page.pageIndex != i }) {
                return null
            }
            PdfAnchorTable(pages, total)
        }.getOrNull()

        /** Persists [table] into [dir] (temp + rename, so
         * a killed write never leaves a torn table). */
        fun write(dir: File, table: PdfAnchorTable) {
            val array = JSONArray()
            for (page in table.pages) {
                array.put(
                    JSONArray()
                        .put(page.pageIndex)
                        .put(page.chapterIndex)
                        .put(page.startCharOffsetInChapter)
                        .put(page.charLength)
                )
            }
            val root = JSONObject()
                .put("totalChars", table.totalCharCount)
                .put("pages", array)
            dir.mkdirs()
            val target = File(dir, ANCHORS_FILE)
            val temp = File(dir, "$ANCHORS_FILE.tmp")
            temp.writeText(root.toString())
            if (!temp.renameTo(target)) {
                target.writeText(root.toString())
                temp.delete()
            }
        }
    }
}

/**
 * v1.7 MR-2 (P3): the place bridge between a converted
 * PDF's two faces — pure arithmetic over the anchor
 * table. One ReadingProgress row stays the single
 * source of truth; a face hop converts the saved place
 * through this bridge BEFORE the other surface opens,
 * and each surface keeps writing its own form while
 * reading.
 *
 * Round-trip rule (Risk 3): page → core → page returns
 * the same page, except a place at a page's very last
 * char (fraction 1.0) which lands on the next page's
 * first char — within the one page the proof allows.
 * Empty pages carry length 0: a place inside one
 * resolves to its start, and a core offset at an empty
 * page's start names the NEXT page that holds text at
 * that offset (the last page-start ≤ the offset wins,
 * and the empty page shares its start with it).
 */
class PdfPlaceBridge(private val table: PdfAnchorTable) {

    data class CorePlace(val chapterIndex: Int, val charOffset: Int)

    data class PagePlace(val page: Int, val inPageFraction: Float)

    /** The core place a page-face place names: the
     * chapter containing [page], at the page's start
     * offset plus [inPageFraction] of the page's char
     * length in the chapter. */
    fun pagePlaceToCore(page: Int, inPageFraction: Float): CorePlace {
        val anchor = table.anchorForPage(page)
            ?: return CorePlace(0, 0)
        val fraction = inPageFraction.coerceIn(0f, 1f)
        val offset = anchor.startCharOffsetInChapter +
            (fraction * anchor.charLength).toInt()
        return CorePlace(
            chapterIndex = anchor.chapterIndex,
            charOffset = offset.coerceIn(
                anchor.startCharOffsetInChapter,
                anchor.startCharOffsetInChapter + anchor.charLength
            )
        )
    }

    /** The page-face place a core place names: the last
     * page-start at or before [charOffset] in the
     * chapter; the fraction is the remaining chars over
     * that page's char length (0 when the page holds no
     * text). */
    fun corePlaceToPage(chapterIndex: Int, charOffset: Int): PagePlace {
        val anchors = table.anchorsForChapter(chapterIndex)
        if (anchors.isEmpty()) {
            // A chapter the table does not hold (a stale
            // row against a re-conversion): the book's
            // first page is the honest fallback.
            return PagePlace(0, 0f)
        }
        val offset = charOffset.coerceAtLeast(0)
        val anchor = anchors.lastOrNull {
            it.startCharOffsetInChapter <= offset
        } ?: anchors.first()
        val fraction = if (anchor.charLength > 0) {
            ((offset - anchor.startCharOffsetInChapter).toFloat() /
                anchor.charLength).coerceIn(0f, 1f)
        } else {
            0f
        }
        return PagePlace(anchor.pageIndex, fraction)
    }
}
