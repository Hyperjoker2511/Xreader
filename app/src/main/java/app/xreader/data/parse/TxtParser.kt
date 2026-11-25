package app.xreader.data.parse

import app.xreader.core.error.AppError
import app.xreader.core.error.AppResult
import app.xreader.data.db.BookFormat
import app.xreader.data.prefs.TxtPrefs
import app.xreader.domain.content.ChapterBlock
import app.xreader.domain.content.ChapterContent
import java.io.File
import java.nio.charset.Charset

/**
 * Plain-text books (PLAN §4): encoding detection first (incl. CJK
 * encodings via [EncodingDetector]), then chapter splitting.
 *
 * MR-6 (SPEC-MR6-TXT-2026-11-25): chapter splitting is now Moon+'s
 * machinery — a detection pass over the decoded text
 * ([TxtChapterDetection]: the `txt_toc_reg` mode switch, the
 * per-language regexes, the two heuristic detectors, Moon+'s winner
 * selection and pruning) produces (title, charOffset) boundaries,
 * and the chapter writer below emits the chapters between them via
 * the unchanged [ChapterFileWriter] seam.
 *
 * When detection finds no boundary at all, the only fallback is
 * the spec's Item 6 survivor: the 30k heading-less soft split
 * (paragraph-boundary chunks, "Part N" titles). There is no third
 * grammar — an earlier build of this patch quietly kept the
 * pre-MR-6 ChapterHeadings grammar as a zero-boundary net; the
 * code-126 fresh-context re-check convicted it (the spec
 * authorises exactly two modes) and it was removed.
 *
 * A per-file encoding override (Item 5) wins over auto-detection
 * when present. Overrides and detected-encoding records are keyed
 * by `Book.filePath`; [pathKey] carries that key when the caller
 * knows it (the re-detection path — at first import the book has
 * no path yet, so the importer records [ParsedBook.detectedEncoding]
 * against the landed path itself).
 *
 * Stored chapter text keeps its blank-line structure and
 * paragraph edge spacing ([textToContentPreserving]): Item 9's
 * trim switches act at render time, so import must not bake the
 * trims in. The render trims default ON, which reproduces the
 * pre-MR-6 stored shape exactly on screen.
 */
class TxtParser(
    private val settingsProvider: () -> TxtPrefs.State = { TxtPrefs.State() },
    private val pathKey: String? = null,
    private val encodingOverrideFor: (String) -> String? = { null },
    private val recordDetectedEncoding: (String, String) -> Unit = { _, _ -> }
) : BookParser {

    override val format: BookFormat = BookFormat.TXT

    override fun parse(
        staged: File,
        workDir: File,
        onProgress: (Float?) -> Unit
    ): AppResult<ParsedBook> {
        return try {
            // Overrides live under the book's filePath key only;
            // with no key (a first import) there is nothing to
            // consult — the book has no path yet.
            val override = pathKey?.let(encodingOverrideFor)
                ?.let { name -> runCatching { Charset.forName(name) }.getOrNull() }
            val charset = override ?: EncodingDetector.detect(staged)
            if (pathKey != null) {
                recordDetectedEncoding(pathKey, charset.name())
            }
            val text = staged.readText(charset)
            onProgress(0.5f)

            val writer = ChapterFileWriter(File(workDir, "chapters"))
            val boundaries = TxtChapterDetection.detect(text, settingsProvider())
            if (boundaries.isNotEmpty()) {
                writeBoundaryChapters(text, boundaries, writer, onProgress)
            } else {
                writeSoftSplitChapters(text, writer)
            }

            if (writer.metas.isEmpty()) {
                return AppResult.Err(
                    AppError.ParseFailed("TXT", "the file has no readable text")
                )
            }
            onProgress(1f)
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
                    assetsDir = null,
                    detectedEncoding = charset.name()
                )
            )
        } catch (e: Exception) {
            AppResult.Err(AppError.ParseFailed("TXT", "the file couldn't be read", e.javaClass.simpleName))
        }
    }

    /** Emits the chapters between Moon+-detected boundaries. The
     * heading line is consumed as the title (never in the body), so
     * the reader prints it exactly once. A short preamble is
     * prepended to chapter 1; a long one becomes its own chapter.
     * A heading with no body before the next heading drops its
     * title. */
    private fun writeBoundaryChapters(
        text: String,
        boundaries: List<DetectedChapter>,
        writer: ChapterFileWriter,
        onProgress: (Float?) -> Unit
    ) {
        var preamble: String? = null
        val pre = text.substring(0, boundaries.first().lineStart).trim()
        if (pre.isNotEmpty()) {
            if (pre.length < PREAMBLE_MAX_CHARS) {
                preamble = pre
            } else {
                writer.add("Part 1", textToContentPreserving(pre))
            }
        }
        for (i in boundaries.indices) {
            val boundary = boundaries[i]
            val bodyEnd = if (i + 1 < boundaries.size) {
                boundaries[i + 1].lineStart
            } else {
                text.length
            }
            val body = text.substring(boundary.bodyStart, bodyEnd).trim()
            if (body.isEmpty()) continue
            val combined = listOfNotNull(preamble, body).joinToString("\n\n")
            preamble = null
            writer.add(boundary.title, textToContentPreserving(combined))
            onProgress(0.5f + 0.5f * (i + 1) / boundaries.size)
        }
        // A book that was ONLY a short preamble still imports.
        if (writer.metas.isEmpty() && !preamble.isNullOrEmpty()) {
            writer.add("Part 1", textToContentPreserving(preamble!!))
        }
    }

    /** The zero-boundary fallback — the spec Item 6 survivor and
     * nothing else: the whole text, soft-split at paragraph
     * boundaries past [SOFT_CHAPTER_CHARS], "Part N" titles. No
     * heading grammar runs here; the mode switch (Item 4) has
     * exactly two modes and this is neither — it is what a book
     * with no detectable chapters has always imported as. */
    private fun writeSoftSplitChapters(text: String, writer: ChapterFileWriter) {
        val current = StringBuilder()
        fun flush() {
            val body = current.toString().trim()
            current.setLength(0)
            if (body.isNotEmpty()) {
                writer.add(
                    "Part ${writer.metas.size + 1}",
                    textToContentPreserving(body)
                )
            }
        }
        for (line in text.lineSequence()) {
            current.append(line).append('\n')
            if (current.length > SOFT_CHAPTER_CHARS && line.isBlank()) {
                flush()
            }
        }
        flush()
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

        /** MR-6 (Item 9): the TXT writer's shape — like
         * [textToContent], but blank paragraphs survive as empty
         * blocks and each paragraph keeps its first line's leading
         * and last line's trailing spacing, so the render-time
         * trim switches have the as-printed structure to act on.
         * With both trims ON (the default) the displayed blocks
         * are exactly [textToContent]'s. TXT-only: every other
         * parser keeps the fixed rules above. */
        fun textToContentPreserving(text: String): ChapterContent {
            val blocks = mutableListOf<ChapterBlock>()
            var paragraphLines = mutableListOf<String>()
            fun flushParagraph() {
                if (paragraphLines.isEmpty()) return
                val lines = paragraphLines.map {
                    it.replace(Regex("[ \\t]+"), " ")
                }
                val cleaned = lines.mapIndexed { index, line ->
                    when {
                        lines.size == 1 -> line
                        index == 0 -> line.trimEnd()
                        index == lines.lastIndex -> line.trimStart()
                        else -> line.trim()
                    }
                }.joinToString(" ")
                blocks += ChapterBlock.Paragraph.plain(cleaned)
                paragraphLines = mutableListOf()
            }
            for (line in text.lineSequence()) {
                if (line.isBlank()) {
                    if (paragraphLines.isNotEmpty()) {
                        flushParagraph()
                    } else {
                        // A blank line beyond the paragraph
                        // separator: an as-printed blank line.
                        blocks += ChapterBlock.Paragraph.plain("")
                    }
                } else {
                    paragraphLines += line
                }
            }
            flushParagraph()
            return ChapterContent(blocks)
        }

        private val BLANK_LINE = Regex("\\n\\s*\\n")
    }
}
