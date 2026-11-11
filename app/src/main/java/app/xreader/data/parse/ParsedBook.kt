package app.xreader.data.parse

import app.xreader.core.error.AppResult
import app.xreader.data.db.BookFormat
import java.io.File

/**
 * What a parser hands the importer (PLAN §4). Chapter *content* is not
 * carried in memory: the parser writes one normalised-content JSON per
 * chapter into [ParsedBook.chaptersDir] as `ch-%06d.json` (index order)
 * plus any images/audio into `assets/`, and returns metadata only — a
 * 1,500-chapter book is parsed chapter-by-chapter, never whole.
 */
data class ParsedBook(
    val title: String?,
    val author: String?,
    val description: String?,
    val language: String?,
    val format: BookFormat,
    /** Extracted cover image staged beside the chapters, if any. */
    val coverFile: File?,
    val chapters: List<ParsedChapterMeta>,
    val chaptersDir: File,
    /** Directory of image/audio assets referenced by chapter content. */
    val assetsDir: File?,
    /**
     * v1.5.4 (WS-7): pictures the source file referenced that the
     * parser could not read (an EPUB image reference resolving to
     * no entry; a PDF embedded image that failed to decode). The
     * importer surfaces a non-zero count in the Adding-books
     * result ("Added — but N pictures in this file couldn't be
     * read") — a partial import never poses as a complete one.
     * Parsers that cannot lose pictures leave the default.
     */
    val droppedImages: Int = 0,
    /**
     * v1.5.5 (§4): chapters the parser assembled but whose body
     * fell under the per-chapter PROSE FLOOR (printable prose
     * below the floor and no image to carry the chapter — the
     * heading-only hollow shape of the V154 PDF finding). The
     * chapters are still stored (their titles are real), but the
     * count rides to the importer exactly the way [droppedImages]
     * does, so a book full of hollow chapters can never pose as
     * a complete import. Parsers without a prose floor leave
     * the default.
     */
    val droppedChapters: Int = 0
)

data class ParsedChapterMeta(
    val title: String,
    val wordCount: Int,
    /** Staged content file name inside [ParsedBook.chaptersDir]. */
    val contentFileName: String
)

/**
 * One format parser. Implementations stream: they read [staged] (the
 * user's file, already stream-copied to disk by the importer) and write
 * normalised chapter files into [workDir]. Failures are typed
 * [app.xreader.core.error.AppError]s — never raw exceptions (§1.5).
 */
interface BookParser {
    val format: BookFormat

    fun parse(
        staged: File,
        workDir: File,
        onProgress: (fraction: Float?) -> Unit = {}
    ): AppResult<ParsedBook>
}

/** Writes staged chapter files the way every parser must (§4). */
internal class ChapterFileWriter(private val chaptersDir: File) {
    private val json = kotlinx.serialization.json.Json { encodeDefaults = false }
    private var index = 0
    val metas = mutableListOf<ParsedChapterMeta>()

    /**
     * v1.5.5 (§4): fired after each chapter file is fully
     * written, with its meta — the importer's progressive-commit
     * hook (chapter 1 lands in the Library the moment it exists,
     * the rest stream in behind it). Null (every legacy caller)
     * keeps the write-only behaviour.
     */
    var onChapterWritten: ((ParsedChapterMeta) -> Unit)? = null

    init {
        chaptersDir.mkdirs()
    }

    fun add(title: String, content: app.xreader.domain.content.ChapterContent): ParsedChapterMeta {
        val name = "ch-%06d.json".format(index++)
        val target = File(chaptersDir, name)
        val temp = File(chaptersDir, "$name.tmp")
        temp.writeText(json.encodeToString(app.xreader.domain.content.ChapterContent.serializer(), content))
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
        val meta = ParsedChapterMeta(
            title = title.ifBlank { "Chapter ${metas.size + 1}" },
            wordCount = content.wordCount,
            contentFileName = name
        )
        metas += meta
        onChapterWritten?.invoke(meta)
        return meta
    }
}

internal fun kotlinx.serialization.json.Json.encodeChapter(
    content: app.xreader.domain.content.ChapterContent
): String = encodeToString(app.xreader.domain.content.ChapterContent.serializer(), content)

internal fun kotlinx.serialization.json.Json.decodeChapter(
    text: String
): app.xreader.domain.content.ChapterContent =
    decodeFromString(app.xreader.domain.content.ChapterContent.serializer(), text)
