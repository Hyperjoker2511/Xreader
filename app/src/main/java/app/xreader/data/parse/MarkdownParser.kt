package app.xreader.data.parse

import app.xreader.core.error.AppError
import app.xreader.core.error.AppResult
import app.xreader.data.db.BookFormat
import app.xreader.domain.content.ChapterBlock
import app.xreader.domain.content.ChapterContent
import app.xreader.domain.content.TextSpan
import java.io.File

/**
 * Markdown books (PLAN §4): a hand-rolled CommonMark *subset* — ATX
 * headings, paragraphs, bold/italic spans, links (text kept), images,
 * lists, blockquotes, fenced code, horizontal rules. Chapters split
 * at H1 (or H2 when no H1 exists). Deliberately small: novel Markdown
 * is prose with headings, not a spec-conformance contest.
 */
class MarkdownParser : BookParser {

    override val format: BookFormat = BookFormat.MARKDOWN

    override fun parse(
        staged: File,
        workDir: File,
        onProgress: (Float?) -> Unit
    ): AppResult<ParsedBook> {
        return try {
            val charset = EncodingDetector.detect(staged)
            val text = staged.readText(charset)
            val document = MarkdownDocument.parse(text)
            if (document.chapters.isEmpty()) {
                return AppResult.Err(AppError.ParseFailed("Markdown", "the file has no readable text"))
            }
            val writer = ChapterFileWriter(File(workDir, "chapters"))
            for (chapter in document.chapters) {
                writer.add(chapter.title, ChapterContent(chapter.blocks))
            }
            AppResult.Ok(
                ParsedBook(
                    title = document.title,
                    author = null,
                    description = null,
                    language = null,
                    format = BookFormat.MARKDOWN,
                    coverFile = null,
                    chapters = writer.metas,
                    chaptersDir = File(workDir, "chapters"),
                    assetsDir = null
                )
            )
        } catch (e: Exception) {
            AppResult.Err(AppError.ParseFailed("Markdown", "the file couldn't be read", e.javaClass.simpleName))
        }
    }
}

/** Pure Markdown → chapters parse, directly unit-testable. */
object MarkdownDocument {

    data class Chapter(val title: String, val blocks: List<ChapterBlock>)

    data class Parsed(val title: String?, val chapters: List<Chapter>)

    fun parse(markdown: String): Parsed {
        val lines = markdown.lines()
        val splitLevel = when {
            lines.any { it.startsWith("# ") } -> 1
            lines.any { it.startsWith("## ") } -> 2
            else -> 1
        }
        var docTitle: String? = null
        val chapters = mutableListOf<Chapter>()
        var currentTitle: String? = null
        var blocks = mutableListOf<ChapterBlock>()

        fun flush() {
            val trimmed = blocks.trimEmptyParagraphs()
            if (trimmed.isEmpty() && currentTitle == null) return
            val title = currentTitle ?: docTitle ?: "Part ${chapters.size + 1}"
            if (trimmed.isNotEmpty()) {
                chapters += Chapter(title, trimmed)
                blocks = mutableListOf()
            }
            currentTitle = null
        }

        var inFence = false
        val fence = StringBuilder()
        val paragraph = StringBuilder()

        fun flushParagraph() {
            val raw = paragraph.toString().trim()
            paragraph.setLength(0)
            if (raw.isNotEmpty()) blocks += ChapterBlock.Paragraph(inlineSpans(raw))
        }

        for (line in lines) {
            val trimmedLine = line.trim()
            when {
                trimmedLine.startsWith("```") -> {
                    if (inFence) {
                        blocks += ChapterBlock.Paragraph.plain(fence.toString().trim())
                        fence.setLength(0)
                        inFence = false
                    } else {
                        flushParagraph()
                        inFence = true
                    }
                }
                inFence -> fence.append(line).append('\n')
                else -> {
                    val heading = HEADING.matchEntire(line)
                    when {
                        heading != null -> {
                            val level = heading.groupValues[1].length
                            val text = heading.groupValues[2].trim()
                            if (level == splitLevel) {
                                flushParagraph()
                                flush()
                                if (docTitle == null && chapters.isEmpty()) docTitle = text
                                currentTitle = text
                            } else {
                                flushParagraph()
                                blocks += ChapterBlock.Heading(level, stripInline(text))
                            }
                        }
                        trimmedLine == "---" || trimmedLine == "***" || trimmedLine == "___" -> {
                            flushParagraph()
                            blocks += ChapterBlock.Rule
                        }
                        trimmedLine.isEmpty() -> flushParagraph()
                        trimmedLine.startsWith("> ") || trimmedLine == ">" -> {
                            paragraph.append(trimmedLine.removePrefix(">").trim()).append(' ')
                        }
                        LIST_ITEM.matchEntire(line) != null -> {
                            flushParagraph()
                            val item = LIST_ITEM.matchEntire(line)!!.groupValues[2]
                            blocks += ChapterBlock.Paragraph(inlineSpans(item.trim()))
                        }
                        IMAGE_LINE.matchEntire(trimmedLine) != null -> {
                            flushParagraph()
                            val image = IMAGE_LINE.matchEntire(trimmedLine)!!
                            val src = image.groupValues[2]
                            blocks += ChapterBlock.Image(
                                src.substringAfterLast('/'),
                                image.groupValues[1]
                            )
                        }
                        else -> paragraph.append(line.trim()).append(' ')
                    }
                }
            }
        }
        flushParagraph()
        flush()
        return Parsed(docTitle, chapters)
    }

    private val HEADING = Regex("""^(#{1,6})\s+(.+)$""")
    private val LIST_ITEM = Regex("""^\s{0,3}([-*+]|[0-9]+[.)])\s+(.+)$""")
    private val IMAGE_LINE = Regex("""^!\[([^\]]*)\]\(([^)\s]+)(?:\s+"[^"]*")?\)$""")

    private fun MutableList<ChapterBlock>.trimEmptyParagraphs(): List<ChapterBlock> =
        filterNot { it is ChapterBlock.Paragraph && it.text.isBlank() }

    /** Removes inline markup, returning plain text (for headings). */
    fun stripInline(text: String): String =
        text.replace(Regex("""!\[([^\]]*)\]\([^)]*\)"""), "$1")
            .replace(Regex("""\[([^\]]*)\]\([^)]*\)"""), "$1")
            .replace("**", "").replace("__", "").replace("*", "").replace("_", "")
            .replace("`", "")
            .trim()

    /** **bold**, *italic*, `code`, [text](url) → spans. */
    fun inlineSpans(text: String): List<TextSpan> {
        val spans = mutableListOf<TextSpan>()
        val pattern = Regex("""(\*\*|__)(.+?)\1|(\*|_)(.+?)\3|\[([^\]]+)\]\([^)]*\)|`([^`]+)`""")
        var lastIndex = 0
        fun push(raw: String) {
            if (raw.isEmpty()) return
            val last = spans.lastOrNull()
            if (last != null && !last.bold && !last.italic) {
                spans[spans.lastIndex] = last.copy(text = last.text + raw)
            } else {
                spans += TextSpan(raw)
            }
        }
        for (match in pattern.findAll(text)) {
            push(text.substring(lastIndex, match.range.first))
            when {
                match.groupValues[2].isNotEmpty() -> spans += TextSpan(match.groupValues[2], bold = true)
                match.groupValues[4].isNotEmpty() -> spans += TextSpan(match.groupValues[4], italic = true)
                match.groupValues[5].isNotEmpty() -> push(match.groupValues[5])
                match.groupValues[6].isNotEmpty() -> push(match.groupValues[6])
            }
            lastIndex = match.range.last + 1
        }
        push(text.substring(lastIndex))
        return spans.ifEmpty { listOf(TextSpan(text)) }
    }
}
