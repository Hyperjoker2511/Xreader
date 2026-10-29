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
    val assetsDir: File?
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
