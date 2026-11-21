package app.xreader.data.prepare

import app.xreader.core.error.AppResult
import app.xreader.data.db.Book
import app.xreader.data.db.BookFormat
import app.xreader.data.db.Chapter
import app.xreader.data.db.ChapterDao
import app.xreader.data.db.DownloadState
import app.xreader.data.engine.LocatorJson
import app.xreader.data.engine.OutlineEntry
import app.xreader.data.extension.DiagnosticLog
import app.xreader.data.files.BookStorage
import app.xreader.data.parse.PdfProse
import app.xreader.data.parse.PdfReflow
import app.xreader.data.parse.PdfStripResult
import app.xreader.domain.content.ChapterBlock
import app.xreader.domain.content.ChapterContent
import app.xreader.domain.reader.ChapterText
import app.xreader.ui.localreader.LocalReaderPrefs
import app.xreader.ui.localreader.PdfPrepareRecord
import java.io.File
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield

/** v1.7 MR-2 (P1): how a PDF conversion attempt ended. */
sealed interface PdfConversionOutcome {
    /** Chapters landed + recorded; [chapterCount] rows. */
    data class Converted(val chapterCount: Int) : PdfConversionOutcome

    /** The strip carries no usable text layer (P1's
     * test): NO conversion and no record pretending
     * otherwise — the book is as-printed only, and the
     * surfaces show P5's honest line. */
    data object ScannedNoText : PdfConversionOutcome

    /** The conversion itself failed (landing / verify);
     * nothing was recorded, so the next open retries. */
    data class Failed(val reason: String) : PdfConversionOutcome
}

/**
 * v1.7 MR-2 (P1): the PDF twin of [EpubPrepareService] —
 * a landed text strip becomes a core document: Chapter
 * rows + chapter content files in the same storage shape
 * EPUB prepare writes, so the core reader, Listen, marks,
 * search and the chapter wall work on a converted PDF
 * unchanged.
 *
 * The shape mirrors prepare exactly: build everything
 * first, land rows + files in ONE transaction, verify,
 * persist the anchor table, and write the prepare record
 * LAST — routing keys on the record, so a conversion
 * interrupted anywhere before it simply runs again.
 *
 * Conversion consumes the STRIP's per-page texts (never
 * the file — the strip is the session's own background
 * product, fingerprint-keyed and persisted):
 *
 *  - Cleanup (amendment A5) runs on the raw lines first:
 *    a line equal to a page's first or last non-empty
 *    line, repeating in that position on ≥ 60% of a run
 *    of ≥ 5 consecutive pages, is a running header/footer
 *    and drops; standalone page-number lines in first or
 *    last position drop with them (the importer's rule).
 *    Nothing else is rewritten — the text is the book's
 *    own.
 *  - Each page's cleaned text then passes through
 *    [PdfReflow] (A6: dehyphenation, line joins,
 *    paragraph breaks, heading detection) before it
 *    becomes blocks. Embedded page images are DROPPED
 *    by conversion in v1 (A6) — the strip carries no
 *    image extractions into the reflow chapters.
 *  - Segmentation is outline-first: one chapter per
 *    outline entry, split at its target page (entries
 *    past [MAX_OUTLINE_CHAPTERS] thin to a spread — the
 *    persisted outline carries no depth, so "top-level"
 *    is unrecoverable; thinning by page spread is the
 *    honest flattening). No outline (or fewer than two
 *    usable targets): page chunks of ~25 pages or
 *    ~60k chars, whichever comes first.
 *  - The anchor table (A7) is computed in ChapterText
 *    coordinates from the landed blocks themselves, so
 *    it cannot drift from the content it describes.
 *
 * Cost (Risk 4): the work is CPU over the strip's
 * in-memory strings, chunked with yields, run by the
 * owning ViewModel's scope — cancellable on book close,
 * never on the open path.
 */
class PdfConversionService(
    private val chapterDao: ChapterDao,
    private val storage: BookStorage,
    private val prefs: LocalReaderPrefs,
    /** The book's `local-pdf` directory (beside the
     * strip) — the anchor table's home. */
    private val stripDir: File,
    private val inTransaction: suspend (suspend () -> Unit) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis
) {

    suspend fun convert(
        book: Book,
        strip: PdfStripResult,
        outline: List<OutlineEntry>
    ): PdfConversionOutcome {
        if (book.localFormat != BookFormat.PDF) {
            return PdfConversionOutcome.Failed("not a PDF book")
        }
        val pageCount = strip.texts.size
        // ── P1's scanned test: total text below ~200
        // chars, or averaging under 50 chars/page, means
        // there is no usable text layer to convert.
        val totalProse = strip.texts.sumOf { PdfProse.printableCount(it) }
        if (pageCount == 0 || totalProse < MIN_TOTAL_CHARS ||
            totalProse < MIN_AVG_CHARS_PER_PAGE * pageCount
        ) {
            return PdfConversionOutcome.ScannedNoText
        }

        // ── A5 cleanup, then A6 reflow, per page.
        val cleaned = cleanPageTexts(strip.texts)
        val pageParagraphs = cleaned.map { PdfReflow.reflow(it) }

        // ── Blocks per page (the anchor computation needs
        // each page's own block span inside its chapter).
        val pageBlocks: List<MutableList<ChapterBlock>> =
            pageParagraphs.map { paragraphs ->
                paragraphs.mapTo(mutableListOf()) { paragraph ->
                    if (PdfReflow.looksLikeHeading(paragraph)) {
                        ChapterBlock.Heading(2, paragraph)
                    } else {
                        ChapterBlock.Paragraph.plain(paragraph)
                    }
                }
            }

        // ── Segmentation.
        val drafts = segment(pageCount, outline, pageParagraphs, pageBlocks)
        if (drafts.isEmpty()) {
            return PdfConversionOutcome.ScannedNoText
        }

        // ── Chapters + anchors, in ChapterText
        // coordinates, from the final block lists.
        val builtChapters = ArrayList<BuiltChapter>(drafts.size)
        val anchors = ArrayList<PdfPageAnchor>(pageCount)
        var totalChars = 0
        drafts.forEachIndexed { chapterIndex, draft ->
            currentCoroutineContext().ensureActive()
            val blocks = ArrayList<ChapterBlock>()
            val firstBlockOfPage = HashMap<Int, Int>()
            for (page in draft.pages) {
                val pageList = pageBlocks[page]
                if (pageList.isNotEmpty()) {
                    firstBlockOfPage[page] = blocks.size
                }
                blocks += pageList
            }
            val content = ChapterContent(blocks = blocks)
            val chapterText = ChapterText(content)
            totalChars += chapterText.length
            // Page starts: a page with blocks starts at its
            // first block; an empty page starts where the
            // next page with blocks starts (or at the
            // chapter's end) — the pages partition the
            // chapter exactly (A7).
            val starts = IntArray(draft.pages.size)
            var nextStart = chapterText.length
            for (i in draft.pages.indices.reversed()) {
                val firstBlock = firstBlockOfPage[draft.pages[i]]
                if (firstBlock != null) {
                    nextStart = chapterText.startOf(firstBlock)
                }
                starts[i] = nextStart
            }
            draft.pages.forEachIndexed { i, page ->
                val start = starts[i]
                val end = if (i + 1 < draft.pages.size) {
                    starts[i + 1]
                } else {
                    chapterText.length
                }
                anchors += PdfPageAnchor(
                    pageIndex = page,
                    chapterIndex = chapterIndex,
                    startCharOffsetInChapter = start,
                    charLength = (end - start).coerceAtLeast(0)
                )
            }
            builtChapters += BuiltChapter(draft.title, content)
            if (chapterIndex % 8 == 7) yield()
        }
        anchors.sortBy { it.pageIndex }
        val table = PdfAnchorTable(anchors, totalChars)

        // ── Landing: rows + content files in ONE
        // transaction (the EPUB prepare shape). Existing
        // rows are replaced wholesale — for a local PDF
        // they can only be provisional import rows or a
        // previous conversion's (the book never opened on
        // the core road while unconverted).
        val now = clock()
        val landedIds = ArrayList<Long>(builtChapters.size)
        try {
            inTransaction {
                chapterDao.deleteForBook(book.id)
                builtChapters.forEachIndexed { index, built ->
                    val chapterId = chapterDao.insert(
                        Chapter(
                            bookId = book.id,
                            index = index,
                            title = built.title,
                            wordCount = built.content.wordCount,
                            updatedAt = now
                        )
                    )
                    val written = storage.writeChapterContent(
                        book.id, chapterId, built.content
                    )
                    val relative = (written as? AppResult.Ok)?.value
                        ?: throw IllegalStateException(
                            "chapter content write failed"
                        )
                    chapterDao.setContent(
                        chapterId, relative, built.content.wordCount,
                        DownloadState.READY
                    )
                    landedIds += chapterId
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            DiagnosticLog.record(
                "pdf-convert",
                "landing failed for book ${book.id}: ${e.javaClass.simpleName}"
            )
            return PdfConversionOutcome.Failed(
                "landing failed: ${e.javaClass.simpleName}"
            )
        }

        // ── Verification: every row and content file
        // checks out BEFORE anything else is written.
        val landedRows = chapterDao.forBook(book.id)
        val verified = landedRows.size == builtChapters.size &&
            landedRows.all { row ->
                val path = row.contentPath
                path != null && storage.resolve(path).isFile
            }
        if (!verified) {
            DiagnosticLog.record(
                "pdf-convert",
                "landing verification failed for book ${book.id}"
            )
            return PdfConversionOutcome.Failed("landing verification failed")
        }

        // ── The anchor table, then the record LAST.
        PdfAnchorTable.write(stripDir, table)
        prefs.savePdfPrepareRecord(
            book.id,
            PdfPrepareRecord(
                fingerprint = PdfPrepareAccess.fingerprintFor(book),
                loaderVersion = PdfPrepareAccess.LOADER_VERSION
            )
        )
        pruneStaleChapterFiles(book.id, landedIds.toSet())
        return PdfConversionOutcome.Converted(builtChapters.size)
    }

    private data class BuiltChapter(
        val title: String,
        val content: ChapterContent
    )

    private class Draft(
        val title: String,
        val pages: List<Int>
    )

    /**
     * Outline-first segmentation. [pageBlocks] is mutated
     * for outline chapters only: the entry's Heading block
     * is prepended to its first page's blocks, and that
     * page's own extracted title line (a paragraph equal
     * to the entry's title) is dropped so the title never
     * prints twice — the importer's outline shape.
     */
    private fun segment(
        pageCount: Int,
        outline: List<OutlineEntry>,
        pageParagraphs: List<List<String>>,
        pageBlocks: List<MutableList<ChapterBlock>>
    ): List<Draft> {
        val usable = outline
            .mapNotNull { entry ->
                val page = LocatorJson.targetIndex(entry.targetJson)
                    ?: return@mapNotNull null
                val title = entry.title.trim()
                if (title.isBlank() || page !in 0 until pageCount) {
                    null
                } else {
                    title to page
                }
            }
            .sortedBy { it.second }
            .distinctBy { it.second }
        if (usable.size >= 2) {
            val splits = if (usable.size <= MAX_OUTLINE_CHAPTERS) {
                usable
            } else {
                thinOutline(usable, pageCount)
            }
            val drafts = mutableListOf<Draft>()
            val firstSplitPage = splits.first().second
            // Pages before the first entry belong to a
            // front-matter chapter when they carry anything
            // at all; when they carry nothing, they fold
            // into the first split's chapter — every page
            // must belong to exactly one chapter, or the
            // anchor table cannot cover the book.
            val frontHasBlocks = firstSplitPage > 0 &&
                (0 until firstSplitPage).any { pageBlocks[it].isNotEmpty() }
            if (frontHasBlocks) {
                val frontPages = (0 until firstSplitPage).toList()
                val paragraphs = frontPages.flatMap { pageParagraphs[it] }
                val tocHeavy = paragraphs.count { PdfProse.isTocLine(it) }
                drafts += Draft(
                    title = if (paragraphs.isNotEmpty() &&
                        tocHeavy * 2 >= paragraphs.size
                    ) {
                        "Table of Contents"
                    } else {
                        "Front Matter"
                    },
                    pages = frontPages
                )
            }
            splits.forEachIndexed { index, (title, startPage) ->
                val end = splits.getOrNull(index + 1)?.second ?: pageCount
                if (startPage >= end) return@forEachIndexed
                val blocks = pageBlocks[startPage]
                val titleParagraph = pageParagraphs[startPage]
                    .indexOfFirst {
                        it.trim().equals(title, ignoreCase = true)
                    }
                // Blocks align 1:1 with paragraphs at this
                // point (the mapping above), so the
                // paragraph's index is its block's index.
                val titleBlock = blocks.getOrNull(titleParagraph)
                val titleBlockText = when (titleBlock) {
                    is ChapterBlock.Paragraph -> titleBlock.text
                    is ChapterBlock.Heading -> titleBlock.text
                    else -> null
                }
                if (titleBlockText != null &&
                    titleBlockText.trim().equals(title, ignoreCase = true)
                ) {
                    blocks.removeAt(titleParagraph)
                }
                blocks.add(0, ChapterBlock.Heading(2, title))
                val rangeStart =
                    if (index == 0 && !frontHasBlocks) 0 else startPage
                drafts += Draft(title, (rangeStart until end).toList())
            }
            return drafts
        }
        // No usable outline: page chunks — a new chapter
        // every ~25 pages or ~60k chars, whichever first.
        val drafts = mutableListOf<Draft>()
        var chunkStart = 0
        var chunkChars = 0
        for (page in 0 until pageCount) {
            val paragraphs = pageParagraphs[page]
            chunkChars += paragraphs.sumOf { it.length } +
                2 * paragraphs.size
            val chunkPages = page - chunkStart + 1
            if (page < pageCount - 1 &&
                (chunkPages >= CHUNK_PAGES || chunkChars >= CHUNK_CHARS)
            ) {
                drafts += Draft(
                    chunkTitle(chunkStart, page),
                    (chunkStart..page).toList()
                )
                chunkStart = page + 1
                chunkChars = 0
            }
        }
        if (chunkStart < pageCount) {
            drafts += Draft(
                chunkTitle(chunkStart, pageCount - 1),
                (chunkStart until pageCount).toList()
            )
        }
        return drafts
    }

    /** Flattens an over-deep outline to at most
     * [MAX_OUTLINE_CHAPTERS] splits: the first entry
     * always splits; a later entry splits only when it
     * falls at least a fortieth of the book past the last
     * kept split. */
    private fun thinOutline(
        usable: List<Pair<String, Int>>,
        pageCount: Int
    ): List<Pair<String, Int>> {
        val minGap = maxOf(1, pageCount / MAX_OUTLINE_CHAPTERS)
        val kept = mutableListOf<Pair<String, Int>>()
        for (entry in usable) {
            val last = kept.lastOrNull()
            if (last == null || entry.second - last.second >= minGap) {
                kept += entry
            }
        }
        return kept
    }

    private fun chunkTitle(firstPage: Int, lastPage: Int): String =
        if (firstPage == lastPage) {
            "Page ${firstPage + 1}"
        } else {
            "Pages ${firstPage + 1}–${lastPage + 1}"
        }

    /**
     * Amendment A5's cleanup, over the strip's raw page
     * texts. Returns the kept text per page (lines joined
     * back with newlines, ready for [PdfReflow]).
     */
    private fun cleanPageTexts(texts: List<String>): List<String> {
        val pageLines = texts.map { text ->
            text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        }
        val firstIndex = pageLines.map { lines ->
            lines.indexOfFirst { it.isNotBlank() }
        }
        val lastIndex = pageLines.map { lines ->
            lines.indexOfLast { it.isNotBlank() }
        }
        val dropFirst = BooleanArray(texts.size)
        val dropLast = BooleanArray(texts.size)
        // Running headers/footers: a first/last line value
        // repeating in that position on ≥ 60% of a run of
        // ≥ 5 consecutive pages.
        for (position in 0..1) {
            val lineAt: (Int) -> String? = { page ->
                val lines = pageLines[page]
                val index = if (position == 0) {
                    firstIndex[page]
                } else {
                    lastIndex[page]
                }
                if (index >= 0) lines[index].trim() else null
            }
            val pagesByValue = HashMap<String, MutableList<Int>>()
            for (page in texts.indices) {
                val value = lineAt(page)?.takeIf { it.isNotEmpty() }
                    ?: continue
                pagesByValue.getOrPut(value) { mutableListOf() } += page
            }
            for ((_, pages) in pagesByValue) {
                for (i in pages.indices) {
                    var bestEnd = -1
                    for (j in i until pages.size) {
                        val span = pages[j] - pages[i] + 1
                        val count = j - i + 1
                        if (span >= MIN_RUN_PAGES &&
                            count * 5 >= span * 3
                        ) {
                            bestEnd = j
                        }
                    }
                    if (bestEnd >= 0) {
                        for (k in i..bestEnd) {
                            if (position == 0) {
                                dropFirst[pages[k]] = true
                            } else {
                                dropLast[pages[k]] = true
                            }
                        }
                    }
                }
            }
        }
        // Standalone page numbers in first/last position
        // drop with them (the importer's rule — a lone
        // number at a page's edge is furniture, never
        // prose).
        for (page in texts.indices) {
            val lines = pageLines[page]
            val first = firstIndex[page]
            val last = lastIndex[page]
            if (first >= 0 && isStandalonePageNumber(lines[first])) {
                dropFirst[page] = true
            }
            if (last >= 0 && isStandalonePageNumber(lines[last])) {
                dropLast[page] = true
            }
        }
        return pageLines.mapIndexed { page, lines ->
            lines.filterIndexed { index, _ ->
                !(index == firstIndex[page] && dropFirst[page]) &&
                    !(index == lastIndex[page] && dropLast[page])
            }.joinToString("\n")
        }
    }

    private fun isStandalonePageNumber(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.length > 8) return false
        if (!trimmed.any { it.isDigit() }) return false
        return PAGE_NUMBER_LINE.matches(trimmed)
    }

    /** Removes landed-out chapter files of replaced rows
     * (a previous conversion's or the provisional
     * import's), so a re-conversion never leaves orphan
     * content behind. Narrow by construction: only
     * `<id>.json` / `tr-<id>.json` names whose id is not
     * in [landedIds]. */
    private fun pruneStaleChapterFiles(bookId: Long, landedIds: Set<Long>) {
        val dir = storage.contentDir(bookId)
        dir.listFiles()?.forEach { file ->
            val name = file.name
            val id = when {
                name.endsWith(".json") && !name.startsWith("tr-") ->
                    name.removeSuffix(".json").toLongOrNull()
                name.startsWith("tr-") && name.endsWith(".json") ->
                    name.removePrefix("tr-").removeSuffix(".json")
                        .toLongOrNull()
                else -> null
            }
            if (id != null && id !in landedIds) {
                runCatching { file.delete() }
            }
        }
        storage.invalidateBookFilesSize(bookId)
    }

    companion object {
        /** P1's scanned test: below this many printable
         * chars in the whole strip, there is no text
         * layer worth converting. */
        const val MIN_TOTAL_CHARS = 200

        /** P1's scanned test: the per-page average floor. */
        const val MIN_AVG_CHARS_PER_PAGE = 50

        /** Outline segmentation's split ceiling (spec:
         * deeper outlines flatten at 40). */
        const val MAX_OUTLINE_CHAPTERS = 40

        /** The header/footer run rule (A5): at least this
         * many consecutive pages… */
        const val MIN_RUN_PAGES = 5

        /** …and the chunk fallback's size fences. */
        const val CHUNK_PAGES = 25
        const val CHUNK_CHARS = 60_000

        private val PAGE_NUMBER_LINE = Regex("""\D{0,3}\d{1,5}\D{0,3}""")
    }
}
