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
 * lists, blockquotes, fenced code, horizontal rules. Deliberately
 * small: novel Markdown is prose with headings, not a
 * spec-conformance contest.
 *
 * MR-4 (D4 + Revision 1 R6): chapters split at H1 ONLY, copying
 * Moon+'s Md chapter-building — content before the first H1, when
 * non-blank, is a first chapter named after the book; when the
 * split yields at most one chapter the book is ONE chapter named
 * after the book. The book name here is the staged file's base
 * name (R6's labelled delta for Moon+'s library-side
 * `getBookName()`). Setext headings are an accepted delta of the
 * ATX-only subset (R6/F13): a Setext-H1 book stays one chapter.
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
            // v1.0.6.2 (Gemini fix round, G5): stream the file
            // line by line — a whole book never sits in memory as
            // one String. MR-4: the parse is a SINGLE pass now
            // (the split level is always H1, so the old scan pass
            // is gone — it also consumed the line sequence twice
            // for H2-only books and threw, a code-114 defect this
            // removes). Chapters still flush through
            // ChapterFileWriter as before.
            // R6: the book name = the staged file's base name,
            // cleaned — the same value the importer displays for
            // a title-less book.
            val bookName = staged.nameWithoutExtension
                .replace('_', ' ').replace('-', ' ')
                .replace(Regex("\\s+"), " ").trim()
                .ifBlank { "Untitled" }
            val document = staged.bufferedReader(charset).useLines { lines ->
                MarkdownDocument.parse(lines, bookName)
            }
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

    /** The chapter split level for a line stream: H1 when any
     * H1 exists, else H2, else H1. Its own pass, so streaming
     * callers compute it without holding the text (G5). */
    fun splitLevel(lines: Sequence<String>): Int = when {
        lines.any { it.startsWith("# ") } -> 1
        lines.any { it.startsWith("## ") } -> 2
        else -> 1
    }

    fun parse(markdown: String): Parsed =
        parse(markdown.lineSequence(), "Untitled")

    /**
     * MR-4: the H1-only parse. [bookName] titles the pre-H1
     * chapter and the ≤1-chapter fallback (Moon+'s
     * `getBookName()` seat — see the parser's R6 note).
     * [splitLevel] is retained for source compatibility but the
     * split is H1-only now (D4): a non-1 value no longer
     * re-opens the H2 fallback.
     */
    fun parse(
        lines: Sequence<String>,
        splitLevel: Int,
        bookName: String = "Untitled"
    ): Parsed = parseH1(lines, bookName)

    fun parse(lines: Sequence<String>, bookName: String): Parsed =
        parseH1(lines, bookName)

    private fun parseH1(lines: Sequence<String>, bookName: String): Parsed {
        var docTitle: String? = null
        val chapters = mutableListOf<Chapter>()
        var currentTitle: String? = null
        var blocks = mutableListOf<ChapterBlock>()
        // Every block in document order, for the ≤1-chapter
        // fallback (Moon+ lands the WHOLE document as the one
        // chapter, H1 heading included).
        val allBlocks = mutableListOf<ChapterBlock>()

        fun flush() {
            val trimmed = blocks.trimEmptyParagraphs()
            blocks = mutableListOf()
            when {
                // R3: a titled chapter is emitted even when
                // its trimmed blocks are empty (two H1s in a
                // row keep the first).
                currentTitle != null -> {
                    chapters += Chapter(currentTitle!!, trimmed)
                }
                // The pre-H1 chapter: only when it has
                // non-blank text (Moon+ Md.java:150-154).
                trimmed.isNotEmpty() -> {
                    chapters += Chapter(bookName, trimmed)
                }
            }
            currentTitle = null
        }

        var inFence = false
        val fence = StringBuilder()
        val paragraph = StringBuilder()

        fun emit(block: ChapterBlock) {
            blocks += block
            allBlocks += block
        }

        fun flushParagraph() {
            val raw = paragraph.toString().trim()
            paragraph.setLength(0)
            if (raw.isNotEmpty()) emit(ChapterBlock.Paragraph(inlineSpans(raw)))
        }

        for (line in lines) {
            val trimmedLine = line.trim()
            when {
                trimmedLine.startsWith("```") -> {
                    if (inFence) {
                        emit(ChapterBlock.Paragraph.plain(fence.toString().trim()))
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
                            if (level == 1) {
                                flushParagraph()
                                flush()
                                if (docTitle == null && chapters.isEmpty()) docTitle = text
                                currentTitle = text
                                // The H1 itself is not a block
                                // of its chapter (its text is the
                                // title) — but the ≤1-chapter
                                // fallback lands the whole
                                // document, heading included.
                                allBlocks += ChapterBlock.Heading(
                                    1, stripInline(text)
                                )
                            } else {
                                flushParagraph()
                                emit(ChapterBlock.Heading(level, stripInline(text)))
                            }
                        }
                        trimmedLine == "---" || trimmedLine == "***" || trimmedLine == "___" -> {
                            flushParagraph()
                            emit(ChapterBlock.Rule)
                        }
                        trimmedLine.isEmpty() -> flushParagraph()
                        trimmedLine.startsWith("> ") || trimmedLine == ">" -> {
                            paragraph.append(trimmedLine.removePrefix(">").trim()).append(' ')
                        }
                        LIST_ITEM.matchEntire(line) != null -> {
                            flushParagraph()
                            val item = LIST_ITEM.matchEntire(line)!!.groupValues[2]
                            emit(ChapterBlock.Paragraph(inlineSpans(item.trim())))
                        }
                        IMAGE_LINE.matchEntire(trimmedLine) != null -> {
                            flushParagraph()
                            val image = IMAGE_LINE.matchEntire(trimmedLine)!!
                            val src = image.groupValues[2]
                            emit(
                                ChapterBlock.Image(
                                    src.substringAfterLast('/'),
                                    image.groupValues[1]
                                )
                            )
                        }
                        else -> paragraph.append(line.trim()).append(' ')
                    }
                }
            }
        }
        flushParagraph()
        flush()
        // Moon+ (Md.java:168-170): the split stands only when
        // it yields MORE THAN ONE chapter (the pre-H1 chapter
        // counts). Otherwise the book is one chapter named
        // after the book, holding the whole document.
        if (chapters.size <= 1) {
            val whole = allBlocks.trimEmptyParagraphs()
            if (whole.isEmpty()) return Parsed(docTitle, emptyList())
            return Parsed(docTitle, listOf(Chapter(bookName, whole)))
        }
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
