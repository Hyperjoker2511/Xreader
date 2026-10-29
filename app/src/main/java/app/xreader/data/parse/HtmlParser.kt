package app.xreader.data.parse

import app.xreader.core.error.AppError
import app.xreader.core.error.AppResult
import app.xreader.data.db.BookFormat
import app.xreader.domain.content.ChapterBlock
import app.xreader.domain.content.ChapterContent
import java.io.File

/**
 * Local HTML files (PLAN §4): converted through the same [XhtmlConverter]
 * as EPUB chapters — the output model cannot contain scripts, styles or
 * event handlers, so conversion IS the sanitisation. Chapters split at
 * top-level h1/h2 headings. Relative image files beside the HTML are
 * copied into the book's assets.
 */
class HtmlParser : BookParser {

    override val format: BookFormat = BookFormat.HTML

    override fun parse(
        staged: File,
        workDir: File,
        onProgress: (Float?) -> Unit
    ): AppResult<ParsedBook> {
        return try {
            val assetsDir = File(workDir, "assets").apply { mkdirs() }
            val usedNames = mutableSetOf<String>()
            val baseDir = staged.parentFile
            val converter = XhtmlConverter { src ->
                val clean = src.substringBefore('#').substringBefore('?')
                if (clean.startsWith("http", ignoreCase = true) ||
                    clean.startsWith("data:", ignoreCase = true)
                ) {
                    null
                } else {
                    val source = baseDir?.let { File(it, ZipPaths.percentDecode(clean)) }
                    if (source == null || !source.isFile) {
                        null
                    } else {
                        val name = ZipPaths.assetFileName(clean, usedNames)
                        source.copyTo(File(assetsDir, name), overwrite = true)
                        name
                    }
                }
            }
            val result = staged.inputStream().buffered().use { converter.convert(it) }
            if (result.blocks.isEmpty()) {
                return AppResult.Err(AppError.ParseFailed("HTML", "the file has no readable text"))
            }
            val writer = ChapterFileWriter(File(workDir, "chapters"))
            splitIntoChapters(result.blocks).forEach { (title, blocks) ->
                writer.add(title, ChapterContent(blocks))
            }
            AppResult.Ok(
                ParsedBook(
                    title = result.titleTag,
                    author = null,
                    description = null,
                    language = null,
                    format = BookFormat.HTML,
                    coverFile = null,
                    chapters = writer.metas,
                    chaptersDir = File(workDir, "chapters"),
                    assetsDir = assetsDir.takeIf { it.list()?.isNotEmpty() == true }
                )
            )
        } catch (e: Exception) {
            AppResult.Err(AppError.ParseFailed("HTML", "the file couldn't be read", e.javaClass.simpleName))
        }
    }

    companion object {
        /** h1 (else h2) headings start new chapters; leading content
         * becomes an untitled first chapter. */
        fun splitIntoChapters(blocks: List<ChapterBlock>): List<Pair<String, List<ChapterBlock>>> {
            val splitLevel = when {
                blocks.any { it is ChapterBlock.Heading && it.level == 1 } -> 1
                blocks.any { it is ChapterBlock.Heading && it.level == 2 } -> 2
                else -> -1
            }
            if (splitLevel < 0) return listOf("Part 1" to blocks)
            val chapters = mutableListOf<Pair<String, List<ChapterBlock>>>()
            var title = "Part 1"
            var current = mutableListOf<ChapterBlock>()
            fun flush() {
                if (current.isNotEmpty()) chapters += title to current.toList()
                current = mutableListOf()
            }
            for (block in blocks) {
                if (block is ChapterBlock.Heading && block.level == splitLevel) {
                    flush()
                    title = block.text
                    current += block
                } else {
                    current += block
                }
            }
            flush()
            return chapters.ifEmpty { listOf("Part 1" to blocks) }
        }
    }
}
