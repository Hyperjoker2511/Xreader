package app.xreader.data.parse

import app.xreader.core.error.AppError
import app.xreader.core.error.AppResult
import app.xreader.data.db.BookFormat
import app.xreader.domain.content.ChapterBlock
import app.xreader.domain.content.ChapterContent
import java.io.File

/**
 * Plain-text books (PLAN §4): encoding detection first (incl. CJK
 * encodings via [EncodingDetector]), then chapter splitting.
 *
 * Splitting favours the novel ecosystem's real conventions:
 *  - English "Chapter 12" / "Chapter Twelve" / "CHAPTER 3: …" lines
 *  - Chinese 第十二章 / 第三回 / 卷一 style headings (also 。。。
 *    Japanese 第3話 / Korean 제3화 patterns share the CJK branch)
 *  - lines of the form "1. Title" are NOT treated as headings (too
 *    many false positives in prose); if no headings are found at all,
 *    the book is chunked at paragraph boundaries so no chapter is
 *    unreasonably long.
 *
 * Streaming: the file is read line by line; only the chapter under
 * construction is held in memory.
 */
class TxtParser : BookParser {

    override val format: BookFormat = BookFormat.TXT

    override fun parse(
        staged: File,
        workDir: File,
        onProgress: (Float?) -> Unit
    ): AppResult<ParsedBook> {
        return try {
            val charset = EncodingDetector.detect(staged)
            val writer = ChapterFileWriter(File(workDir, "chapters"))

            var currentTitle: String? = null
            val current = StringBuilder()
            var sawHeading = false
            var totalChars = 0L
            var preamble: String? = null
            val fileLength = staged.length().coerceAtLeast(1)

            fun flushChapter() {
                val text = current.toString().trim()
                current.setLength(0)
                if (text.isEmpty() && currentTitle == null) return
                if (currentTitle == null) {
                    // Preamble before the first heading (a title page):
                    // a SHORT one is held and prepended to chapter 1 —
                    // it must not become a spurious "Part 1". Real
                    // heading-less content is long and writes directly.
                    if (!sawHeading && writer.metas.isEmpty() && preamble == null &&
                        text.length < PREAMBLE_MAX_CHARS
                    ) {
                        preamble = text
                        return
                    }
                    val combined = listOfNotNull(preamble, text).joinToString("\n\n")
                    preamble = null
                    if (combined.isNotEmpty()) {
                        writer.add("Part ${writer.metas.size + 1}", textToContent(combined))
                    }
                    return
                }
                if (text.isEmpty()) {
                    // A heading with no body yet — keep the title for the
                    // next flush instead of emitting an empty chapter.
                    return
                }
                val combined = listOfNotNull(preamble, text).joinToString("\n\n")
                preamble = null
                writer.add(currentTitle!!, textToContent(combined))
                currentTitle = null
            }

            staged.bufferedReader(charset).useLines { lines ->
                for (line in lines) {
                    totalChars += line.length + 1
                    val heading = ChapterHeadings.match(line)
                    if (heading != null) {
                        flushChapter()
                        currentTitle = heading
                        sawHeading = true
                    } else {
                        current.append(line).append('\n')
                        // Safety valve for heading-less dumps: split at
                        // a paragraph boundary past the soft cap.
                        if (!sawHeading && current.length > SOFT_CHAPTER_CHARS &&
                            line.isBlank()
                        ) {
                            flushChapter()
                        }
                    }
                    if (totalChars % (256 * 1024) < 4096) {
                        onProgress((totalChars.toFloat() / fileLength).coerceIn(0f, 1f))
                    }
                }
            }
            flushChapter()
            // A book that was ONLY a short preamble still imports.
            if (writer.metas.isEmpty() && !preamble.isNullOrEmpty()) {
                writer.add("Part 1", textToContent(preamble!!))
            }

            if (writer.metas.isEmpty()) {
                return AppResult.Err(
                    AppError.ParseFailed("TXT", "the file has no readable text")
                )
            }
            AppResult.Ok(
                ParsedBook(
                    title = null,
                    author = null,
                    description = null,
                    language = null,
                    format = BookFormat.TXT,
                    coverFile = null,
                    chapters = writer.metas,
                    chaptersDir = File(workDir, "chapters"),
                    assetsDir = null
                )
            )
        } catch (e: Exception) {
            AppResult.Err(AppError.ParseFailed("TXT", "the file couldn't be read", e.javaClass.simpleName))
        }
    }

    companion object {
        private const val SOFT_CHAPTER_CHARS = 30_000
        private const val PREAMBLE_MAX_CHARS = 2_000

        /** Paragraphs → blocks (blank-line separated). */
        fun textToContent(text: String): ChapterContent {
            val blocks = mutableListOf<ChapterBlock>()
            for (paragraph in text.split(BLANK_LINE)) {
                val cleaned = paragraph.replace(Regex("[ \\t]+"), " ")
                    .lines().joinToString(" ") { it.trim() }.trim()
                if (cleaned.isNotEmpty()) {
                    blocks += ChapterBlock.Paragraph.plain(cleaned)
                }
            }
            return ChapterContent(blocks)
        }

        private val BLANK_LINE = Regex("\\n\\s*\\n")
    }
}

/**
 * The heading grammar, split out so it is directly unit-testable and
 * shared by the Markdown fallback path.
 */
object ChapterHeadings {

    private val ENGLISH = Regex(
        """^\s{0,4}(chapter|chap\.?|episode|part|prologue|epilogue|interlude|afterword)\s*""" +
            """([0-9]+|[ivxlcdm]+|one|two|three|four|five|six|seven|eight|nine|ten)?""" +
            """\s*[:.\-—]?\s*(.{0,80})$""",
        RegexOption.IGNORE_CASE
    )

    // 第 + number (Arabic or CJK numerals) + chapter-ish counter word.
    private val CJK = Regex(
        """^\s{0,4}第\s*[0-9零一二三四五六七八九十百千万壱弐参四五六七八九拾佰阡]+\s*""" +
            """[章节回話话卷篇幕折]\s*[:：、.\-—]?\s*(.{0,60})$"""
    )

    private val VOLUME = Regex(
        """^\s{0,4}(卷|volume|book)\s*[:：]?\s*([0-9]+|[ivxlcdm]+|.{1,30})$""",
        RegexOption.IGNORE_CASE
    )

    /** Returns the heading text to use as a chapter title, or null. */
    fun match(line: String): String? {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.length > 120) return null
        // Lines ending like sentences are prose, not headings.
        if (trimmed.endsWith(",") || trimmed.endsWith("，")) return null
        val cjk = CJK.matchEntire(trimmed)
        if (cjk != null) return trimmed
        val english = ENGLISH.matchEntire(trimmed)
        if (english != null) {
            // Require a number or a standalone keyword chapter start so
            // prose like "part of me wondered…" never splits a book.
            val number = english.groupValues[2]
            val keyword = english.groupValues[1].lowercase()
            if (number.isNotEmpty() || keyword in STANDALONE_KEYWORDS) return trimmed
            return null
        }
        if (VOLUME.matchEntire(trimmed) != null && trimmed.length <= 40) return trimmed
        return null
    }

    private val STANDALONE_KEYWORDS = setOf("prologue", "epilogue", "interlude", "afterword")
}
