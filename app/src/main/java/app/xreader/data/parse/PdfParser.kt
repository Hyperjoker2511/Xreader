package app.xreader.data.parse

import android.content.Context
import app.xreader.core.error.AppError
import app.xreader.core.error.AppResult
import app.xreader.data.db.BookFormat
import app.xreader.domain.content.ChapterBlock
import app.xreader.domain.content.ChapterContent
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File

/**
 * The seam between the PDF engine and the rest of the app: production
 * uses PDFBox-Android (which needs an Android [Context] and cannot run
 * in JVM unit tests), tests use a fake. The parser logic — reflow,
 * chapter splitting, low-text honesty — is all JVM-side.
 */
interface PdfTextExtractor {
    /** Extracted plain text per page, in page order. */
    fun extractPages(file: File): AppResult<List<String>>

    /**
     * v1.0.6.2 (F6a): the PDF's own description metadata (the Info
     * dictionary's Subject — the PDF equivalent of EPUB's
     * dc:description), or null when the file carries none.
     */
    fun extractDescription(file: File): String? = null
}

class PdfBoxTextExtractor(context: Context) : PdfTextExtractor {

    private val appContext = context.applicationContext

    @Volatile
    private var initialised = false

    override fun extractPages(file: File): AppResult<List<String>> {
        return try {
            if (!initialised) {
                PDFBoxResourceLoader.init(appContext)
                initialised = true
            }
            PDDocument.load(file).use { document ->
                if (document.isEncrypted) {
                    return AppResult.Err(AppError.DrmProtected)
                }
                val stripper = PDFTextStripper()
                val pages = ArrayList<String>(document.numberOfPages)
                for (page in 1..document.numberOfPages) {
                    stripper.startPage = page
                    stripper.endPage = page
                    pages += stripper.getText(document)
                }
                AppResult.Ok(pages)
            }
        } catch (e: Exception) {
            AppResult.Err(
                AppError.ParseFailed("PDF", "the file couldn't be read", e.javaClass.simpleName)
            )
        }
    }

    override fun extractDescription(file: File): String? = try {
        if (!initialised) {
            PDFBoxResourceLoader.init(appContext)
            initialised = true
        }
        PDDocument.load(file).use { document ->
            document.documentInformation?.subject
                ?.trim()?.takeIf { it.isNotEmpty() }
        }
    } catch (e: Exception) {
        null
    }
}

/**
 * PDF text reflow (PLAN §4): extracted PDF text is hard-wrapped at
 * column width with hyphenated line breaks. Reflow joins lines back
 * into paragraphs:
 *  - a line ending in "-" joins the next line without a space when the
 *    next line starts lowercase (dehyphenation);
 *  - other single newlines become spaces;
 *  - blank lines are paragraph breaks;
 *  - lines that look like chapter headings ("Chapter 12", "제3장", …)
 *    are kept as boundaries by the caller.
 * Pure Kotlin — the goldens in the test suite pin this behaviour.
 */
object PdfReflow {

    private val headingRegex = Regex(
        """^\s*(chapter|episode|part|volume|book|prologue|epilogue|interlude|afterword)\b.*$|^\s*第.{1,12}[章节回卷篇]\s*.*$""",
        RegexOption.IGNORE_CASE
    )

    fun looksLikeHeading(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.length > 90 || trimmed.isEmpty()) return false
        return headingRegex.matches(trimmed)
    }

    /**
     * Joins one extracted page/chunk of hard-wrapped text into flowing
     * paragraphs. Returns paragraphs (headings included as their own
     * entries, flagged by [looksLikeHeading] at the caller).
     */
    fun reflow(text: String): List<String> {
        val paragraphs = mutableListOf<String>()
        val current = StringBuilder()
        fun flush() {
            val value = current.toString().replace(Regex("\\s+"), " ").trim()
            if (value.isNotEmpty()) paragraphs += value
            current.setLength(0)
        }
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        for (rawLine in lines) {
            val line = rawLine.trim()
            when {
                line.isEmpty() -> flush()
                looksLikeHeading(line) -> {
                    flush()
                    paragraphs += line
                }
                current.isEmpty() -> current.append(line)
                current.endsWith("-") && line.firstOrNull()?.isLowerCase() == true -> {
                    current.setLength(current.length - 1)
                    current.append(line)
                }
                current.endsWith("-") && line.firstOrNull()?.isDigit() == true -> {
                    current.setLength(current.length - 1)
                    current.append(line)
                }
                else -> current.append(' ').append(line)
            }
        }
        flush()
        return paragraphs
    }
}

/**
 * PDF book parser (PLAN §4): page texts → reflowed chapters. A PDF
 * with almost no extractable text is a scan — the parser says so
 * plainly (image mode is the Phase 4 reader's job) instead of
 * importing an empty book.
 */
class PdfBookParser(private val extractor: PdfTextExtractor) : BookParser {

    override val format: BookFormat = BookFormat.PDF

    override fun parse(
        staged: File,
        workDir: File,
        onProgress: (Float?) -> Unit
    ): AppResult<ParsedBook> {
        val pages = when (val extracted = extractor.extractPages(staged)) {
            is AppResult.Err -> return extracted
            is AppResult.Ok -> extracted.value
        }
        if (pages.isEmpty()) {
            return AppResult.Err(AppError.ParseFailed("PDF", "the file has no pages"))
        }
        val totalChars = pages.sumOf { it.trim().length }
        if (totalChars < pages.size * MIN_CHARS_PER_PAGE) {
            return AppResult.Err(
                AppError.ParseFailed(
                    "PDF",
                    "this PDF looks like scanned images — there's no text Xreader can read yet"
                )
            )
        }

        // Group pages into chapters at heading boundaries; a heading
        // paragraph found mid-stream starts the next chapter.
        data class DraftChapter(val title: String, val paragraphs: MutableList<String>)
        val chapters = mutableListOf(DraftChapter("Part 1", mutableListOf()))
        var chapterNumber = 1
        pages.forEachIndexed { index, pageText ->
            for (paragraph in PdfReflow.reflow(pageText)) {
                if (PdfReflow.looksLikeHeading(paragraph) &&
                    chapters.last().paragraphs.isNotEmpty()
                ) {
                    chapterNumber++
                    chapters += DraftChapter(paragraph, mutableListOf(paragraph))
                } else {
                    if (chapters.last().paragraphs.isEmpty() &&
                        PdfReflow.looksLikeHeading(paragraph)
                    ) {
                        chapters[chapters.lastIndex] =
                            DraftChapter(paragraph, mutableListOf(paragraph))
                    } else {
                        chapters.last().paragraphs += paragraph
                    }
                }
            }
            onProgress((index + 1).toFloat() / pages.size)
        }

        val writer = ChapterFileWriter(File(workDir, "chapters"))
        for (chapter in chapters) {
            if (chapter.paragraphs.isEmpty()) continue
            val blocks = chapter.paragraphs.mapIndexed { position, paragraph ->
                if (position == 0 && PdfReflow.looksLikeHeading(paragraph)) {
                    ChapterBlock.Heading(2, paragraph)
                } else {
                    ChapterBlock.Paragraph.plain(paragraph)
                }
            }
            writer.add(chapter.title, ChapterContent(blocks))
        }
        if (writer.metas.isEmpty()) {
            return AppResult.Err(AppError.ParseFailed("PDF", "no readable text was found"))
        }
        return AppResult.Ok(
            ParsedBook(
                title = null,
                author = null,
                description = extractor.extractDescription(staged),
                language = null,
                format = BookFormat.PDF,
                coverFile = null,
                chapters = writer.metas,
                chaptersDir = File(workDir, "chapters"),
                assetsDir = null
            )
        )
    }

    companion object {
        /** Below this average the "book" is pictures, not text. */
        const val MIN_CHARS_PER_PAGE = 24
    }
}
