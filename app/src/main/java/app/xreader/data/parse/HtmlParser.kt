package app.xreader.data.parse

import app.xreader.core.error.AppError
import app.xreader.core.error.AppResult
import app.xreader.data.db.BookFormat
import app.xreader.domain.content.ChapterBlock
import app.xreader.domain.content.ChapterContent
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.charset.StandardCharsets
import org.jsoup.Jsoup
import org.jsoup.nodes.Comment
import org.jsoup.nodes.Document
import org.jsoup.nodes.Entities
import org.jsoup.nodes.Node

/**
 * Local HTML files (PLAN §4): converted through the same [XhtmlConverter]
 * as EPUB chapters — the output model cannot contain scripts, styles or
 * event handlers, so conversion IS the sanitisation. Chapters split at
 * top-level h1/h2 headings. Relative image files beside the HTML are
 * copied into the book's assets.
 *
 * OE-5 (part E): the staged bytes are first normalised through jsoup's
 * lenient HTML parser and re-serialised as well-formed XHTML. The
 * converter is a strict XmlPullParser reader and ordinary HTML
 * (unclosed `<img>`/`<meta>`/`<br>`, bare `&`) used to throw
 * XmlPullParserException — the code-112 MHTML phone-proof failure;
 * MhtmlParser delegates here, and CHM topics are the same kind of
 * HTML, so this one normalisation repairs all three doors. Scripts,
 * styles, noscripts and comments are stripped before serialising:
 * the converter would drop those elements anyway, and removing them
 * early also kills the script-CDATA trap (a raw `<` inside script
 * text would break the XML parse — the same failure class).
 */
class HtmlParser(
    /** Charset jsoup should assume when the document carries no
     * BOM/meta declaration of its own (a declared one still wins,
     * which is what the CHM research prescribes). Null = detect. */
    private val charsetHint: String? = null,
    /** Extra directories image references may resolve against,
     * AFTER the staged file's own directory: a reference that
     * misses beside the document is retried against each root
     * with any leading `/` stripped (root-relative references —
     * the CHM norm — resolve from the extraction root, never as
     * an absolute filesystem path). Existing callers pass nothing
     * and behave exactly as before. */
    private val extraImageRoots: List<File> = emptyList()
) : BookParser {

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
            val converter = XhtmlConverter(imageResolver = { src ->
                val clean = src.substringBefore('#').substringBefore('?')
                if (clean.startsWith("http", ignoreCase = true) ||
                    clean.startsWith("data:", ignoreCase = true)
                ) {
                    null
                } else {
                    // Backslashes are path separators in the
                    // documents' own world (HH-era HTML, Windows-
                    // authored pages); normalise once, for both
                    // resolution and the staged asset name.
                    val path = clean.replace('\\', '/')
                    val source = resolveImage(path, baseDir)
                    if (source == null) {
                        null
                    } else {
                        val name = ZipPaths.assetFileName(path, usedNames)
                        source.copyTo(File(assetsDir, name), overwrite = true)
                        name
                    }
                }
            })
            val result = converter.convert(ByteArrayInputStream(normaliseToXhtml(staged.readBytes())))
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

    /** Lenient-parse [bytes] with jsoup (empty base URI, so relative
     * `src`s stay relative), strip the elements the converter drops
     * anyway, and serialise as well-formed XHTML in UTF-8. */
    private fun normaliseToXhtml(bytes: ByteArray): ByteArray {
        val doc = Jsoup.parse(ByteArrayInputStream(bytes), charsetHint, "")
        doc.select("script, style, noscript").remove()
        stripComments(doc)
        doc.outputSettings()
            .syntax(Document.OutputSettings.Syntax.xml)
            .escapeMode(Entities.EscapeMode.xhtml)
            .prettyPrint(false)
            .charset(StandardCharsets.UTF_8)
        return doc.outerHtml().toByteArray(StandardCharsets.UTF_8)
    }

    private fun stripComments(root: Node) {
        val comments = mutableListOf<Comment>()
        fun collect(node: Node) {
            for (child in node.childNodes()) {
                if (child is Comment) comments += child else collect(child)
            }
        }
        collect(root)
        comments.forEach { it.remove() }
    }

    /** Image lookup: the staged file's own directory first (the
     * long-standing behaviour — `..` segments resolve through the
     * filesystem), then each extra root with the reference's
     * leading `/` stripped. [src] arrives backslash-normalised. */
    private fun resolveImage(src: String, baseDir: File?): File? {
        val decoded = ZipPaths.percentDecode(src)
        if (baseDir != null) {
            val beside = File(baseDir, decoded)
            if (beside.isFile) return beside
        }
        if (extraImageRoots.isNotEmpty()) {
            val rootRelative = decoded.trimStart('/')
            if (rootRelative.isNotEmpty()) {
                for (root in extraImageRoots) {
                    val fromRoot = File(root, rootRelative)
                    if (fromRoot.isFile) return fromRoot
                }
            }
        }
        return null
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
