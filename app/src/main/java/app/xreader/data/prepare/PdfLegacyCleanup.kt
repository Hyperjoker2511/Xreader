package app.xreader.data.prepare

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.xreader.data.db.Book
import app.xreader.data.db.BookmarkDao
import app.xreader.data.db.ChapterDao
import app.xreader.data.db.ReadingProgressDao
import app.xreader.data.engine.LocatorJson
import app.xreader.data.extension.DiagnosticLog
import app.xreader.data.files.BookStorage
import java.io.File
import kotlinx.coroutines.flow.first
import org.json.JSONObject

/**
 * v1.7 OE-1 (R31): the one-time cleanup for MR-2-era
 * converted PDFs, run from the core reader's PDF open
 * path (and only there) the first time each affected
 * book opens in the one reader.
 *
 * MR-2 converted a PDF's strip into core Chapter rows +
 * content files, a per-page anchor table (`anchors.json`
 * beside the strip) and a prepare record + face pref in
 * the settings store. OE-1 deletes that machinery whole
 * (PdfConversionService, PdfPlaceBridge, PdfPrepareAccess,
 * the record/face prefs), so this cleanup deliberately
 * depends on NONE of it: it carries its own minimal
 * `anchors.json` reader, its own copies of the record
 * string form and the DataStore key formats, and its own
 * copy of the bridge's page arithmetic.
 *
 * For every PDF with a prepare record or an anchor
 * table:
 *  1. A core-form ReadingProgress row (locatorJson ==
 *     null — the reflow face's shape) is translated to
 *     page form via the anchor table (page + in-page
 *     fraction → [LocatorJson.pdfLocator]).
 *  2. Core-form bookmarks on converted chapter ids are
 *     translated to page-anchored bookmarks (page only;
 *     char offsets drop — Moon+'s granularity, R19).
 *  3. The converted Chapter rows and their content
 *     files are deleted, and `anchors.json` with them.
 *  4. The prepare record / face prefs are cleared (the
 *     keys' formats are read here, from this file's own
 *     copies, before those accessors' code is deleted).
 *
 * OQ2 fallback (no anchor table — a record whose
 * conversion's table is missing or corrupt): place is
 * mapped by stored percent → nearest page (progress by
 * its book fraction; bookmarks by their chapter's share
 * of the converted chapter count). There is no exact
 * mapping without the table; percent is the honest best.
 *
 * The cleanup is idempotent (a second run finds no
 * record and no table, and does nothing) and
 * failure-isolated: any failure is logged and swallowed,
 * and the book still opens in Pages mode — the reader
 * never reads chapter rows for a PDF after OE-1.
 */
object PdfLegacyCleanup {

    /** One converted page's anchor (the retired
     * PdfPageAnchor's persisted form). */
    private data class Anchor(
        val pageIndex: Int,
        val chapterIndex: Int,
        val startCharOffsetInChapter: Int,
        val charLength: Int
    )

    suspend fun runIfNeeded(
        book: Book,
        pageCount: Int,
        chapterDao: ChapterDao,
        readingProgressDao: ReadingProgressDao,
        bookmarkDao: BookmarkDao,
        storage: BookStorage,
        stripDir: File,
        settingsStore: DataStore<Preferences>
    ) {
        runCatching {
            run(book, pageCount, chapterDao, readingProgressDao,
                bookmarkDao, storage, stripDir, settingsStore)
        }.onFailure { error ->
            DiagnosticLog.record(
                "pdf-legacy-cleanup",
                "book ${book.id} cleanup failed: " +
                    error.javaClass.simpleName
            )
        }
    }

    private suspend fun run(
        book: Book,
        pageCount: Int,
        chapterDao: ChapterDao,
        readingProgressDao: ReadingProgressDao,
        bookmarkDao: BookmarkDao,
        storage: BookStorage,
        stripDir: File,
        settingsStore: DataStore<Preferences>
    ) {
        val prefsSnapshot = settingsStore.data.first()
        val recordRaw = prefsSnapshot[prepareKey(book.id)]
        val anchorsFile = File(stripDir, "anchors.json")
        val table = loadAnchors(anchorsFile)
        if (recordRaw == null && table == null) {
            // Nothing MR-2-shaped stands for this book:
            // the common case, and every second run.
            return
        }
        val rows = chapterDao.forBook(book.id)
        // The rows the conversion landed: with a table,
        // the rows whose index the table's chapters name;
        // without one, the rows carrying content (the
        // landed-content test's shape — provisional
        // import rows carry none and are left alone).
        val convertedRows = if (table != null) {
            val chapters = table.map { it.chapterIndex }.toSet()
            rows.filter { it.index in chapters }
        } else {
            rows.filter { it.contentPath != null }
        }
        val convertedIds = convertedRows.map { it.id }.toSet()

        translateProgress(book, pageCount, table, readingProgressDao)
        translateBookmarks(
            book, pageCount, table, rows.size, convertedIds, bookmarkDao
        )

        // Rows + their content files (and any translated
        // copies beside them), then the table itself.
        for (row in convertedRows) {
            row.contentPath?.let { path ->
                runCatching { storage.resolve(path).delete() }
            }
            runCatching {
                File(storage.contentDir(book.id), "tr-${row.id}.json")
                    .delete()
            }
            chapterDao.delete(row)
        }
        if (convertedRows.isNotEmpty()) {
            storage.invalidateBookFilesSize(book.id)
        }
        runCatching { anchorsFile.delete() }
        runCatching { File(stripDir, "anchors.json.tmp").delete() }

        // The record + face prefs, cleared through this
        // file's own copies of the key formats.
        settingsStore.edit { prefs ->
            prefs.remove(prepareKey(book.id))
            prefs.remove(faceKey(book.id))
        }
    }

    /** Step 1: a core-form progress row becomes page
     * form. Page-form rows (a pdfLocator) and rows of any
     * other shape are already the reader's own and stay. */
    private suspend fun translateProgress(
        book: Book,
        pageCount: Int,
        table: List<Anchor>?,
        readingProgressDao: ReadingProgressDao
    ) {
        val saved = readingProgressDao.getForBook(book.id) ?: return
        if (saved.locatorJson != null) return
        if (pageCount <= 0) return
        val (page, inPageFraction) = if (table != null) {
            corePlaceToPage(table, saved.chapterIndex, saved.charOffset)
        } else {
            (saved.fraction.coerceIn(0f, 1f) * pageCount)
                .toInt()
                .coerceIn(0, pageCount - 1) to 0f
        }
        readingProgressDao.upsert(
            saved.copy(
                chapterId = 0,
                chapterIndex = page,
                charOffset = 0,
                fraction = ((page + inPageFraction) / pageCount)
                    .coerceIn(0f, 1f),
                scrollFraction = inPageFraction,
                updatedAt = System.currentTimeMillis(),
                locatorJson = LocatorJson.pdfLocator(page, inPageFraction)
            )
        )
    }

    /** Step 2: core-form bookmarks on converted chapter
     * ids become page-anchored (page only). Bookmarks
     * carrying a locator are already page-form and stay. */
    private suspend fun translateBookmarks(
        book: Book,
        pageCount: Int,
        table: List<Anchor>?,
        chapterCount: Int,
        convertedIds: Set<Long>,
        bookmarkDao: BookmarkDao
    ) {
        if (pageCount <= 0) return
        val now = System.currentTimeMillis()
        for (bookmark in bookmarkDao.forBook(book.id)) {
            if (bookmark.locatorJson != null) continue
            if (bookmark.chapterId !in convertedIds) continue
            val page = if (table != null) {
                corePlaceToPage(
                    table, bookmark.chapterIndex, bookmark.charOffset
                ).first
            } else if (chapterCount > 0) {
                (bookmark.chapterIndex.toFloat() / chapterCount *
                    pageCount).toInt().coerceIn(0, pageCount - 1)
            } else {
                0
            }
            bookmarkDao.update(
                bookmark.copy(
                    chapterId = 0,
                    chapterIndex = page,
                    charOffset = 0,
                    updatedAt = now,
                    locatorJson = LocatorJson.pdfLocator(page, 0f)
                )
            )
        }
    }

    /** The bridge's core→page arithmetic, copied (the
     * retired PdfPlaceBridge.corePlaceToPage): the last
     * page-start at or before [charOffset] in the
     * chapter; the fraction is the remaining chars over
     * that page's char length (0 when it holds no text).
     * A chapter the table does not hold resolves to the
     * book's first page. */
    private fun corePlaceToPage(
        table: List<Anchor>,
        chapterIndex: Int,
        charOffset: Int
    ): Pair<Int, Float> {
        val anchors = table.filter { it.chapterIndex == chapterIndex }
        if (anchors.isEmpty()) return 0 to 0f
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
        return anchor.pageIndex to fraction
    }

    /** The minimal `anchors.json` reader (the retired
     * PdfAnchorTable.load's persisted form): a JSON
     * object with a `pages` array of
     * [pageIndex, chapterIndex, startCharOffset, charLength]
     * quadruples in page order. Anything malformed reads
     * as null — the OQ2 fallback then governs. */
    private fun loadAnchors(file: File): List<Anchor>? = runCatching {
        if (!file.isFile) return null
        val root = JSONObject(file.readText())
        if (root.optInt("totalChars", -1) < 0) return null
        val array = root.getJSONArray("pages")
        val pages = ArrayList<Anchor>(array.length())
        for (i in 0 until array.length()) {
            val entry = array.getJSONArray(i)
            if (entry.length() < 4) return null
            pages += Anchor(
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
        pages
    }.getOrNull()

    // ── Own copies of the retired stores' key formats ────
    // (LocalReaderPrefs' pdfPrepareKey / pdfFaceKey,
    // copied so the cleanup never depends on the code it
    // outlives; R31 step 4.)

    private fun prepareKey(bookId: Long) =
        stringPreferencesKey("localreader.$bookId.pdf.prepare")

    private fun faceKey(bookId: Long) =
        stringPreferencesKey("localreader.$bookId.pdf.face")
}
