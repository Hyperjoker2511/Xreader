package app.xreader.data.parse

import app.xreader.core.error.AppError
import app.xreader.core.error.AppResult
import app.xreader.data.db.BookFormat
import app.xreader.domain.content.ChapterBlock
import app.xreader.domain.content.ChapterContent
import app.xreader.domain.content.TextSpan
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

/**
 * DOCX / ODT (OE-3, Moon+ copy): ONE parser for both office
 * formats — Moon+ shares its DOCX/ODT converter, and the house
 * precedent is [MobiParser]'s constructor format. The file is
 * unzipped, its document XML walked into blocks, its pictures
 * copied to assets, and the whole book lands as ONE chapter
 * (the Moon+ shape for these formats); headings live inside as
 * heading blocks (the FB2 nested-section precedent).
 */
class OfficeXmlParser(private val formatWanted: BookFormat) : BookParser {

    override val format: BookFormat = formatWanted

    override fun parse(
        staged: File,
        workDir: File,
        onProgress: (Float?) -> Unit
    ): AppResult<ParsedBook> {
        val label = if (formatWanted == BookFormat.ODT) "ODT" else "DOCX"
        return try {
            val assetsDir = File(workDir, "assets").apply { mkdirs() }
            val document = if (formatWanted == BookFormat.ODT) {
                OdtDocument.parse(staged, assetsDir)
            } else {
                DocxDocument.parse(staged, assetsDir)
            }
            if (document == null || document.blocks.isEmpty()) {
                return AppResult.Err(AppError.ParseFailed(label, "the file has no readable text"))
            }
            val writer = ChapterFileWriter(File(workDir, "chapters"))
            writer.add(document.title ?: "", ChapterContent(document.blocks))
            AppResult.Ok(
                ParsedBook(
                    title = document.title,
                    author = document.author,
                    description = null,
                    language = null,
                    format = formatWanted,
                    coverFile = null,
                    chapters = writer.metas,
                    chaptersDir = File(workDir, "chapters"),
                    assetsDir = assetsDir.takeIf { it.list()?.isNotEmpty() == true }
                )
            )
        } catch (e: Exception) {
            AppResult.Err(AppError.ParseFailed(label, "the file couldn't be read", e.javaClass.simpleName))
        }
    }
}

/** What one office document walk produces. */
private data class OfficeDocument(
    val title: String?,
    val author: String?,
    val blocks: List<ChapterBlock>
)

/** Attribute by LOCAL name — office XML spreads the attributes
 * this reader needs across several namespaces (w:, r:, text:,
 * xlink:), and the pull parser reports local names uniformly. */
private fun attr(parser: XmlPullParser, localName: String): String? {
    for (i in 0 until parser.attributeCount) {
        if (parser.getAttributeName(i) == localName) return parser.getAttributeValue(i)
    }
    return null
}

private fun newOfficePullParser(input: InputStream): XmlPullParser {
    val factory = XmlPullParserFactory.newInstance()
    factory.isNamespaceAware = true
    val parser = factory.newPullParser()
    parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
    // Null encoding: the XML declaration is authoritative for
    // office documents (both writers always declare UTF-8).
    parser.setInput(input.buffered(), null)
    return parser
}

/** Copies one zip entry into the assets dir under a flat,
 * collision-free name (the FB2 image discipline). */
private fun stageZipImage(
    zip: ZipFile,
    entryPath: String,
    assetsDir: File,
    usedNames: MutableSet<String>
): String? {
    val entry = zip.getEntry(entryPath) ?: return null
    val name = ZipPaths.assetFileName(entryPath, usedNames)
    zip.getInputStream(entry).use { input ->
        File(assetsDir, name).outputStream().buffered().use { input.copyTo(it) }
    }
    return name
}

/**
 * Block assembly shared by both walks: spans accumulate into the
 * open paragraph with the FB2 walker's discipline (whitespace
 * collapsed, equal-styled neighbours merged, edges trimmed at
 * the flush), and a paragraph closes as a heading when its
 * style said so.
 */
private class BlockBuilder {
    val blocks = mutableListOf<ChapterBlock>()
    private val spans = mutableListOf<TextSpan>()
    private var open = false

    fun beginParagraph() {
        flushParagraph()
        open = true
    }

    fun text(raw: String, bold: Boolean, italic: Boolean) {
        if (!open) return
        val text = raw.replace(Regex("\\s+"), " ")
        if (text.isEmpty()) return
        val last = spans.lastOrNull()
        if (last != null && last.bold == bold && last.italic == italic) {
            spans[spans.lastIndex] = last.copy(text = last.text + text)
        } else {
            spans += TextSpan(text, bold, italic)
        }
    }

    /** A hard break inside the paragraph (w:br / text:line-break). */
    fun lineBreak() {
        if (!open) return
        val last = spans.lastOrNull()
        if (last != null) {
            spans[spans.lastIndex] = last.copy(text = last.text + "\n")
        } else {
            spans += TextSpan("\n")
        }
    }

    /** An image ends the current paragraph; the caller reopens
     * one when the source paragraph continues past the picture. */
    fun image(name: String) {
        flushParagraph()
        blocks += ChapterBlock.Image(name)
    }

    fun endParagraph(headingLevel: Int? = null) {
        if (headingLevel != null) {
            val text = spans.joinToString("") { it.text }
                .replace(Regex("\\s+"), " ").trim()
            spans.clear()
            open = false
            if (text.isNotEmpty()) blocks += ChapterBlock.Heading(headingLevel, text)
            return
        }
        flushParagraph()
    }

    private fun flushParagraph() {
        if (spans.isEmpty()) {
            open = false
            return
        }
        val cleaned = spans.map { it }.toMutableList()
        while (cleaned.isNotEmpty() && cleaned.first().text.isBlank()) cleaned.removeAt(0)
        while (cleaned.isNotEmpty() && cleaned.last().text.isBlank()) cleaned.removeAt(cleaned.lastIndex)
        if (cleaned.isNotEmpty()) {
            cleaned[0] = cleaned.first().copy(text = cleaned.first().text.trimStart())
            cleaned[cleaned.lastIndex] = cleaned.last().copy(text = cleaned.last().text.trimEnd())
            blocks += ChapterBlock.Paragraph(cleaned.toList())
        }
        spans.clear()
        open = false
    }
}

/** DOCX walk: word/document.xml + its rels + docProps/core.xml. */
private object DocxDocument {

    fun parse(file: File, assetsDir: File): OfficeDocument? {
        ZipFile(file).use { zip ->
            val documentEntry = zip.getEntry("word/document.xml") ?: return null

            // Image relationships: id → archive path of the picture.
            val imageRels = mutableMapOf<String, String>()
            zip.getEntry("word/_rels/document.xml.rels")?.let { relsEntry ->
                val parser = newOfficePullParser(zip.getInputStream(relsEntry))
                var event = parser.eventType
                while (event != XmlPullParser.END_DOCUMENT) {
                    if (event == XmlPullParser.START_TAG && parser.name == "Relationship") {
                        val id = attr(parser, "Id")
                        val type = attr(parser, "Type") ?: ""
                        val target = attr(parser, "Target")
                        if (id != null && target != null && type.endsWith("/image")) {
                            imageRels[id] = if (target.startsWith("/")) {
                                target.trimStart('/')
                            } else {
                                ZipPaths.resolve("word", target)
                            }
                        }
                    }
                    event = parser.next()
                }
            }

            var title: String? = null
            var author: String? = null
            zip.getEntry("docProps/core.xml")?.let { coreEntry ->
                val parser = newOfficePullParser(zip.getInputStream(coreEntry))
                var event = parser.eventType
                while (event != XmlPullParser.END_DOCUMENT) {
                    if (event == XmlPullParser.START_TAG) {
                        when (parser.name) {
                            "title" -> title = parser.nextText()
                                ?.trim()?.takeIf { it.isNotEmpty() } ?: title
                            "creator" -> author = parser.nextText()
                                ?.trim()?.takeIf { it.isNotEmpty() } ?: author
                        }
                    }
                    event = parser.next()
                }
            }

            val usedNames = mutableSetOf<String>()
            val stagedImages = mutableMapOf<String, String>()
            val builder = BlockBuilder()
            val parser = newOfficePullParser(zip.getInputStream(documentEntry))
            var paragraphStyle: String? = null
            var runBold = false
            var runItalic = false
            var inText = false

            fun headingLevelOf(style: String?): Int? =
                when (style?.lowercase()?.replace(" ", "")) {
                    "title" -> 1
                    "heading1" -> 1
                    "heading2" -> 2
                    "heading3" -> 3
                    else -> null
                }

            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "p" -> {
                            builder.beginParagraph()
                            paragraphStyle = null
                        }
                        "pStyle" -> paragraphStyle = attr(parser, "val") ?: paragraphStyle
                        "r" -> {
                            runBold = false
                            runItalic = false
                        }
                        "b" -> runBold = attr(parser, "val") !in listOf("false", "0", "off")
                        "i" -> runItalic = attr(parser, "val") !in listOf("false", "0", "off")
                        "t" -> inText = true
                        "tab" -> builder.text(" ", runBold, runItalic)
                        "br", "cr" -> builder.lineBreak()
                        "blip" -> {
                            val embed = attr(parser, "embed")
                            val entryPath = embed?.let { imageRels[it] }
                            if (entryPath != null) {
                                val name = stagedImages[entryPath] ?: run {
                                    stageZipImage(zip, entryPath, assetsDir, usedNames)
                                        ?.also { stagedImages[entryPath] = it }
                                }
                                if (name != null) {
                                    builder.image(name)
                                    builder.beginParagraph()
                                }
                            }
                        }
                    }
                    XmlPullParser.TEXT -> if (inText) {
                        builder.text(parser.text ?: "", runBold, runItalic)
                    }
                    XmlPullParser.END_TAG -> when (parser.name) {
                        "t" -> inText = false
                        "p" -> builder.endParagraph(headingLevelOf(paragraphStyle))
                    }
                }
                event = parser.next()
            }
            return OfficeDocument(title, author, builder.blocks)
        }
    }
}

/** ODT walk: content.xml (styles pass, then body) + meta.xml. */
private object OdtDocument {

    private data class StyleFlags(
        val bold: Boolean = false,
        val italic: Boolean = false,
        val parent: String? = null
    )

    fun parse(file: File, assetsDir: File): OfficeDocument? {
        ZipFile(file).use { zip ->
            val contentEntry = zip.getEntry("content.xml") ?: return null

            var title: String? = null
            var author: String? = null
            zip.getEntry("meta.xml")?.let { metaEntry ->
                val parser = newOfficePullParser(zip.getInputStream(metaEntry))
                var event = parser.eventType
                while (event != XmlPullParser.END_DOCUMENT) {
                    if (event == XmlPullParser.START_TAG) {
                        when (parser.name) {
                            "title" -> title = parser.nextText()
                                ?.trim()?.takeIf { it.isNotEmpty() } ?: title
                            "creator" -> author = parser.nextText()
                                ?.trim()?.takeIf { it.isNotEmpty() } ?: author
                        }
                    }
                    event = parser.next()
                }
            }

            // Pass 1: named styles → bold/italic (text-properties
            // of each style:style, parent chain followed).
            val styles = mutableMapOf<String, StyleFlags>()
            run {
                val parser = newOfficePullParser(zip.getInputStream(contentEntry))
                var styleName: String? = null
                var styleParent: String? = null
                var bold = false
                var italic = false
                var event = parser.eventType
                while (event != XmlPullParser.END_DOCUMENT) {
                    when (event) {
                        XmlPullParser.START_TAG -> when (parser.name) {
                            "style" -> {
                                styleName = attr(parser, "name")
                                styleParent = attr(parser, "parent-style-name")
                                bold = false
                                italic = false
                            }
                            "text-properties", "paragraph-properties" -> {
                                if (attr(parser, "font-weight") == "bold") bold = true
                                if (attr(parser, "font-style") == "italic") italic = true
                            }
                        }
                        XmlPullParser.END_TAG -> if (parser.name == "style") {
                            styleName?.let { styles[it] = StyleFlags(bold, italic, styleParent) }
                            styleName = null
                        }
                    }
                    event = parser.next()
                }
            }

            fun flagsOf(name: String?): StyleFlags {
                var resolved = StyleFlags()
                var current = name
                val seen = mutableSetOf<String>()
                while (current != null && seen.add(current)) {
                    val flags = styles[current] ?: break
                    resolved = StyleFlags(
                        bold = resolved.bold || flags.bold,
                        italic = resolved.italic || flags.italic
                    )
                    current = flags.parent
                }
                return resolved
            }

            // Pass 2: the body (office:text).
            val usedNames = mutableSetOf<String>()
            val builder = BlockBuilder()
            val parser = newOfficePullParser(zip.getInputStream(contentEntry))
            var inBody = false
            var baseBold = false
            var baseItalic = false
            var bold = false
            var italic = false
            val spanStack = ArrayDeque<Pair<Boolean, Boolean>>()
            var headingLevel: Int? = null

            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "text" -> inBody = true
                        "p", "h" -> if (inBody) {
                            builder.beginParagraph()
                            val flags = flagsOf(attr(parser, "style-name"))
                            baseBold = flags.bold
                            baseItalic = flags.italic
                            bold = baseBold
                            italic = baseItalic
                            spanStack.clear()
                            headingLevel = if (parser.name == "h") {
                                attr(parser, "outline-level")?.toIntOrNull() ?: 1
                            } else {
                                null
                            }
                        }
                        "span" -> if (inBody) {
                            spanStack.addLast(bold to italic)
                            val flags = flagsOf(attr(parser, "style-name"))
                            bold = bold || flags.bold
                            italic = italic || flags.italic
                        }
                        "s" -> if (inBody) {
                            val count = attr(parser, "c")?.toIntOrNull() ?: 1
                            builder.text(" ".repeat(count.coerceIn(1, 80)), bold, italic)
                        }
                        "tab" -> if (inBody) builder.text(" ", bold, italic)
                        "line-break" -> if (inBody) builder.lineBreak()
                        "image" -> if (inBody) {
                            val href = attr(parser, "href")
                            val entryPath = href?.trimStart('.', '/')
                            if (entryPath != null) {
                                val name = stageZipImage(zip, entryPath, assetsDir, usedNames)
                                if (name != null) {
                                    builder.image(name)
                                    builder.beginParagraph()
                                }
                            }
                        }
                    }
                    XmlPullParser.TEXT -> if (inBody) {
                        builder.text(parser.text ?: "", bold, italic)
                    }
                    XmlPullParser.END_TAG -> when (parser.name) {
                        "text" -> inBody = false
                        "span" -> if (inBody) {
                            val previous = spanStack.removeLastOrNull()
                            if (previous != null) {
                                bold = previous.first
                                italic = previous.second
                            }
                        }
                        "p" -> if (inBody) builder.endParagraph(null)
                        "h" -> if (inBody) builder.endParagraph(headingLevel)
                    }
                }
                event = parser.next()
            }
            return OfficeDocument(title, author, builder.blocks)
        }
    }
}
