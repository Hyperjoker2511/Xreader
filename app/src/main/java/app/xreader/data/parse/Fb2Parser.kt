package app.xreader.data.parse

import app.xreader.core.error.AppError
import app.xreader.core.error.AppResult
import app.xreader.data.db.BookFormat
import app.xreader.domain.content.ChapterBlock
import app.xreader.domain.content.ChapterContent
import app.xreader.domain.content.TextSpan
import java.io.File
import java.util.Base64
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

/**
 * FB2 (FictionBook) parser (PLAN §4): description/title-info for
 * metadata + coverpage, body sections as chapters, `<binary>` images
 * (base64) extracted to assets. Nested sections flatten: a top-level
 * section is a chapter; deeper sections become heading blocks inside
 * it. Inline emphasis/strong map to spans, like the other parsers.
 */
class Fb2Parser : BookParser {

    override val format: BookFormat = BookFormat.FB2

    override fun parse(
        staged: File,
        workDir: File,
        onProgress: (Float?) -> Unit
    ): AppResult<ParsedBook> {
        return try {
            val assetsDir = File(workDir, "assets").apply { mkdirs() }
            val document = Fb2Document.parse(staged, assetsDir)
            if (document == null || document.chapters.isEmpty()) {
                return AppResult.Err(AppError.ParseFailed("FB2", "the file has no readable chapters"))
            }
            val writer = ChapterFileWriter(File(workDir, "chapters"))
            for (chapter in document.chapters) {
                writer.add(chapter.title, ChapterContent(chapter.blocks))
            }
            val coverFile = document.coverBinaryId?.let { id ->
                val name = document.imageNames[id] ?: return@let null
                File(assetsDir, name).takeIf { it.isFile }
            }
            AppResult.Ok(
                ParsedBook(
                    title = document.title,
                    author = document.author,
                    description = document.annotation,
                    language = document.language,
                    format = BookFormat.FB2,
                    coverFile = coverFile,
                    chapters = writer.metas,
                    chaptersDir = File(workDir, "chapters"),
                    assetsDir = assetsDir.takeIf { it.list()?.isNotEmpty() == true }
                )
            )
        } catch (e: Exception) {
            AppResult.Err(AppError.ParseFailed("FB2", "the file couldn't be read", e.javaClass.simpleName))
        }
    }
}

/** Pure FB2 document walk, split out for fixture tests. */
object Fb2Document {

    data class Chapter(val title: String, val blocks: List<ChapterBlock>)

    data class Document(
        val title: String?,
        val author: String?,
        val annotation: String?,
        val language: String?,
        val coverBinaryId: String?,
        /** binary id → staged asset file name. */
        val imageNames: Map<String, String>,
        val chapters: List<Chapter>
    )

    fun parse(file: File, assetsDir: File): Document? {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = true
        val parser = factory.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        val charset = EncodingDetector.detect(file)
        parser.setInput(file.inputStream().buffered(), charset.name())

        var title: String? = null
        var authorFirst: String? = null
        var authorLast: String? = null
        var annotation: String? = null
        var language: String? = null
        var coverBinaryId: String? = null
        val imageNames = mutableMapOf<String, String>()
        val usedNames = mutableSetOf<String>()
        val chapters = mutableListOf<Chapter>()

        // State machine over the flat event stream.
        var inTitleInfo = false
        var inBody = false
        var sectionDepth = 0
        var currentBlocks: MutableList<ChapterBlock>? = null
        var currentTitle: String? = null
        var sectionTitleCapture: StringBuilder? = null
        var inSectionTitle = false
        var binaryId: String? = null
        var binaryType: String? = null
        val binaryText = StringBuilder()
        var inBinary = false
        var annotationCapture: StringBuilder? = null
        var inAnnotation = false

        var boldDepth = 0
        var italicDepth = 0
        val spans = mutableListOf<TextSpan>()
        var paragraphOpen = false

        fun appendText(raw: String) {
            if (!paragraphOpen) return
            val text = raw.replace(Regex("\\s+"), " ")
            if (text.isEmpty()) return
            val bold = boldDepth > 0
            val italic = italicDepth > 0
            val last = spans.lastOrNull()
            if (last != null && last.bold == bold && last.italic == italic) {
                spans[spans.lastIndex] = last.copy(text = last.text + text)
            } else {
                spans += TextSpan(text, bold, italic)
            }
        }

        fun flushParagraph() {
            if (spans.isEmpty()) {
                paragraphOpen = false
                return
            }
            val cleaned = spans.map { it }.toMutableList()
            while (cleaned.isNotEmpty() && cleaned.first().text.isBlank()) cleaned.removeAt(0)
            while (cleaned.isNotEmpty() && cleaned.last().text.isBlank()) cleaned.removeAt(cleaned.lastIndex)
            if (cleaned.isNotEmpty()) {
                cleaned[0] = cleaned.first().copy(text = cleaned.first().text.trimStart())
                cleaned[cleaned.lastIndex] = cleaned.last().copy(text = cleaned.last().text.trimEnd())
                currentBlocks?.add(ChapterBlock.Paragraph(cleaned.toList()))
            }
            spans.clear()
            paragraphOpen = false
        }

        fun closeChapter() {
            flushParagraph()
            val blocks = currentBlocks
            if (blocks != null && blocks.isNotEmpty()) {
                chapters += Chapter(currentTitle ?: "Chapter ${chapters.size + 1}", blocks.toList())
            }
            currentBlocks = null
            currentTitle = null
        }

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "title-info" -> inTitleInfo = true
                    "body" -> inBody = true
                    "book-title" -> if (inTitleInfo) {
                        title = parser.nextText()?.trim()?.takeIf { it.isNotEmpty() } ?: title
                    }
                    "first-name" -> if (inTitleInfo) {
                        authorFirst = parser.nextText()?.trim()?.takeIf { it.isNotEmpty() } ?: authorFirst
                    }
                    "last-name" -> if (inTitleInfo) {
                        authorLast = parser.nextText()?.trim()?.takeIf { it.isNotEmpty() } ?: authorLast
                    }
                    "lang" -> if (inTitleInfo && language == null) {
                        language = parser.nextText()?.trim()?.takeIf { it.isNotEmpty() }
                    }
                    "annotation" -> if (inTitleInfo) {
                        inAnnotation = true
                        annotationCapture = StringBuilder()
                    }
                    "coverpage" -> Unit
                    "image" -> {
                        val href = parser.getAttributeValue(null, "href")
                            ?: parser.getAttributeValue("http://www.w3.org/1999/xlink", "href")
                        val id = href?.removePrefix("#")
                        if (inTitleInfo && coverBinaryId == null && id != null) {
                            coverBinaryId = id
                        } else if (inBody && id != null) {
                            // Image appears inline as its own block; the
                            // binary may be declared later in the file —
                            // record the reference by staged name now.
                            val name = imageNames.getOrPut(id) {
                                val guessed = "$id.jpg"
                                ZipPaths.assetFileName(guessed, usedNames)
                            }
                            flushParagraph()
                            currentBlocks?.add(ChapterBlock.Image(name))
                        }
                    }
                    "binary" -> {
                        inBinary = true
                        binaryId = parser.getAttributeValue(null, "id")
                        binaryType = parser.getAttributeValue(null, "content-type")
                        binaryText.setLength(0)
                    }
                    "section" -> if (inBody) {
                        sectionDepth++
                        if (sectionDepth == 1) {
                            closeChapter()
                            currentBlocks = mutableListOf()
                            currentTitle = null
                        }
                    }
                    "title" -> {
                        if (inBody && sectionDepth >= 1) {
                            inSectionTitle = true
                            sectionTitleCapture = StringBuilder()
                        }
                    }
                    "p", "subtitle", "text-author" -> if (inBody) {
                        flushParagraph()
                        paragraphOpen = true
                    }
                    "emphasis" -> italicDepth++
                    "strong" -> boldDepth++
                    "empty-line" -> if (inBody) flushParagraph()
                }
                XmlPullParser.TEXT -> {
                    val text = parser.text ?: ""
                    when {
                        inBinary -> binaryText.append(text)
                        inSectionTitle -> sectionTitleCapture?.append(text)
                        inAnnotation -> annotationCapture?.append(text)
                        inBody -> appendText(text)
                    }
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "title-info" -> inTitleInfo = false
                    "body" -> {
                        closeChapter()
                        inBody = false
                    }
                    "annotation" -> {
                        inAnnotation = false
                        annotation = annotationCapture?.toString()
                            ?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() }
                    }
                    "binary" -> {
                        inBinary = false
                        val id = binaryId
                        if (id != null) {
                            val ext = when (binaryType) {
                                "image/png" -> "png"
                                "image/webp" -> "webp"
                                "image/gif" -> "gif"
                                else -> "jpg"
                            }
                            val name = imageNames.getOrPut(id) {
                                ZipPaths.assetFileName("$id.$ext", usedNames)
                            }
                            runCatching {
                                val bytes = Base64.getMimeDecoder().decode(
                                    binaryText.toString().replace(Regex("\\s+"), "")
                                )
                                File(assetsDir, name).writeBytes(bytes)
                            }
                        }
                        binaryId = null
                    }
                    "section" -> if (inBody) {
                        if (sectionDepth == 1) closeChapter()
                        if (sectionDepth > 0) sectionDepth--
                    }
                    "title" -> if (inSectionTitle) {
                        inSectionTitle = false
                        val text = sectionTitleCapture?.toString()
                            ?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
                        sectionTitleCapture = null
                        if (sectionDepth == 1) {
                            currentTitle = text.takeIf { it.isNotEmpty() } ?: currentTitle
                        } else if (text.isNotEmpty()) {
                            currentBlocks?.add(ChapterBlock.Heading(2, text))
                        }
                    }
                    "p", "subtitle", "text-author" -> if (inBody) flushParagraph()
                    "emphasis" -> if (italicDepth > 0) italicDepth--
                    "strong" -> if (boldDepth > 0) boldDepth--
                }
            }
            // Author name parts arrive as nextText of specific tags; the
            // flat walker handles them via dedicated reads below.
            event = parser.next()
        }

        val author = listOfNotNull(authorFirst, authorLast).joinToString(" ").trim()
        return Document(
            title = title,
            author = author.takeIf { it.isNotEmpty() },
            annotation = annotation,
            language = language,
            coverBinaryId = coverBinaryId,
            imageNames = imageNames,
            chapters = chapters
        )
    }
}
