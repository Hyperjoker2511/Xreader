package app.xreader.data.parse

import app.xreader.domain.content.ChapterBlock
import app.xreader.domain.content.TextSpan
import java.io.ByteArrayInputStream
import java.io.InputStream
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

/**
 * XHTML/HTML → normalised blocks (PLAN §4). One converter serves EPUB
 * spine items, local HTML files, FB2-adjacent markup and MOBI HTML, so
 * every format reads identically downstream.
 *
 * Sanitisation is structural: the output model has no raw HTML at all —
 * only headings, paragraphs (with bold/italic spans) and image
 * references — so scripts, styles and event attributes cannot survive
 * conversion. Images pass through [imageResolver]: given the raw `src`,
 * it stores the bytes and returns the content-dir file name, or null
 * to drop the image.
 *
 * Chapter-sized inputs only (one spine item / one HTML file): the
 * bytes are read once so a malformed-entity retry is possible —
 * real-world XHTML loves `&nbsp;`, which strict XML parsers reject;
 * the retry path maps common named entities to numeric references.
 */
class XhtmlConverter(
    private val imageResolver: (src: String) -> String? = { null }
) {

    data class Result(
        val blocks: List<ChapterBlock>,
        /** First h1/h2 text, if any (chapter-title fallback). */
        val heading: String?,
        /** The document <title>, if any. */
        val titleTag: String?
    )

    fun convert(input: InputStream): Result {
        val bytes = input.readBytes()
        return try {
            parse { parser -> parser.setInput(ByteArrayInputStream(bytes), null) }
        } catch (e: Exception) {
            val sanitised = sanitizeEntities(String(bytes, Charsets.UTF_8))
            parse { parser -> parser.setInput(sanitised.reader()) }
        }
    }

    fun convert(html: String): Result =
        try {
            parse { parser -> parser.setInput(html.reader()) }
        } catch (e: Exception) {
            parse { parser -> parser.setInput(sanitizeEntities(html).reader()) }
        }

    private fun parse(configure: (XmlPullParser) -> Unit): Result {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = true
        val parser = factory.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        configure(parser)

        val blocks = mutableListOf<ChapterBlock>()
        var heading: String? = null
        var titleTag: String? = null

        var skipDepth = 0
        var inTitle = false
        var boldDepth = 0
        var italicDepth = 0
        var preDepth = 0

        // Current paragraph under construction.
        val spans = mutableListOf<TextSpan>()
        var paragraphId: String? = null

        fun appendText(raw: String) {
            if (raw.isEmpty()) return
            val text = if (preDepth > 0) raw else raw.replace(WHITESPACE, " ")
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
            // Trim edge whitespace from the assembled spans.
            while (spans.isNotEmpty() && spans.first().text.isBlank()) spans.removeAt(0)
            while (spans.isNotEmpty() && spans.last().text.isBlank()) spans.removeAt(spans.lastIndex)
            if (spans.isNotEmpty()) {
                val first = spans.first()
                spans[0] = first.copy(text = first.text.trimStart())
                val last = spans.last()
                spans[spans.lastIndex] = last.copy(text = last.text.trimEnd())
                blocks += ChapterBlock.Paragraph(spans.toList(), paragraphId)
            }
            spans.clear()
            paragraphId = null
        }

        var headingCapture: StringBuilder? = null
        var headingLevel = 1
        var headingId: String? = null

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val name = parser.name.lowercase()
                    if (name in SKIP_TAGS) skipDepth++
                    if (skipDepth == 0) {
                        when (name) {
                            "title" -> inTitle = true
                            "b", "strong" -> boldDepth++
                            "i", "em" -> italicDepth++
                            "pre" -> {
                                flushParagraph()
                                preDepth++
                            }
                            "br" -> appendText("\n")
                            "hr" -> {
                                flushParagraph()
                                blocks += ChapterBlock.Rule
                            }
                            "img", "image" -> {
                                flushParagraph()
                                val src = parser.getAttributeValue(null, "src")
                                    ?: parser.getAttributeValue(null, "href")
                                    ?: parser.getAttributeValue(
                                        "http://www.w3.org/1999/xlink", "href"
                                    )
                                val alt = parser.getAttributeValue(null, "alt") ?: ""
                                if (src != null) {
                                    imageResolver(src)?.let { fileName ->
                                        blocks += ChapterBlock.Image(fileName, alt)
                                    }
                                }
                            }
                            "h1", "h2", "h3", "h4", "h5", "h6" -> {
                                flushParagraph()
                                headingCapture = StringBuilder()
                                headingLevel = name[1] - '0'
                                headingId = parser.getAttributeValue(null, "id")
                            }
                            in BLOCK_TAGS -> {
                                flushParagraph()
                                if (paragraphId == null) {
                                    paragraphId = parser.getAttributeValue(null, "id")
                                }
                            }
                        }
                    }
                }
                XmlPullParser.TEXT -> {
                    val text = parser.text ?: ""
                    when {
                        skipDepth > 0 -> Unit
                        inTitle -> titleTag = ((titleTag ?: "") + text).trim()
                        headingCapture != null -> headingCapture.append(text)
                        else -> appendText(text)
                    }
                }
                XmlPullParser.END_TAG -> {
                    val name = parser.name.lowercase()
                    if (name in SKIP_TAGS && skipDepth > 0) skipDepth--
                    if (skipDepth == 0) {
                        when (name) {
                            "title" -> inTitle = false
                            "b", "strong" -> if (boldDepth > 0) boldDepth--
                            "i", "em" -> if (italicDepth > 0) italicDepth--
                            "pre" -> {
                                flushParagraph()
                                if (preDepth > 0) preDepth--
                            }
                            "h1", "h2", "h3", "h4", "h5", "h6" -> {
                                val text = headingCapture?.toString()
                                    ?.replace(WHITESPACE, " ")?.trim().orEmpty()
                                headingCapture = null
                                if (text.isNotEmpty()) {
                                    if (heading == null && headingLevel <= 2) heading = text
                                    blocks += ChapterBlock.Heading(headingLevel, text, headingId)
                                }
                                headingId = null
                            }
                            in BLOCK_TAGS -> flushParagraph()
                        }
                    }
                }
            }
            event = parser.next()
        }
        flushParagraph()
        return Result(blocks, heading, titleTag?.takeIf { it.isNotEmpty() })
    }

    companion object {
        private val WHITESPACE = Regex("\\s+")
        private val SKIP_TAGS = setOf("script", "style", "noscript", "template")
        private val BLOCK_TAGS = setOf(
            "p", "div", "section", "article", "header", "footer", "main", "aside",
            "li", "ul", "ol", "blockquote", "table", "tr", "td", "th",
            "figure", "figcaption", "nav", "fieldset", "address"
        )

        /** Named HTML entities strict XML rejects → numeric references. */
        private val NAMED_ENTITIES = mapOf(
            "nbsp" to 160, "mdash" to 8212, "ndash" to 8211, "hellip" to 8230,
            "ldquo" to 8220, "rdquo" to 8221, "lsquo" to 8216, "rsquo" to 8217,
            "laquo" to 171, "raquo" to 187, "copy" to 169, "reg" to 174,
            "trade" to 8482, "middot" to 183, "bull" to 8226, "dagger" to 8224,
            "eacute" to 233, "egrave" to 232, "agrave" to 224, "ccedil" to 231,
            "uuml" to 252, "ouml" to 246, "auml" to 228, "szlig" to 223,
            "times" to 215, "divide" to 247, "plusmn" to 177, "deg" to 176,
            "para" to 182, "sect" to 167, "euro" to 8364, "pound" to 163,
            "yen" to 165, "cent" to 162, "iexcl" to 161, "iquest" to 191
        )

        private val ENTITY_PATTERN = Regex("&([a-zA-Z][a-zA-Z0-9]+);")
        private val XML_SAFE = setOf("amp", "lt", "gt", "quot", "apos")

        /** Rewrites common named entities as numeric refs so a strict
         * XML parser accepts real-world XHTML. Unknown entities become
         * a plain space (better than losing the chapter). */
        fun sanitizeEntities(html: String): String =
            ENTITY_PATTERN.replace(html) { match ->
                val name = match.groupValues[1]
                when {
                    name in XML_SAFE -> match.value
                    NAMED_ENTITIES.containsKey(name) -> "&#${NAMED_ENTITIES[name]};"
                    else -> " "
                }
            }
    }
}

/** URL-path resolution shared by the zip-based parsers (EPUB/FB2/CBZ). */
object ZipPaths {

    /** Resolves [href] against a directory inside an archive, normalising `..`/`.`. */
    fun resolve(baseDir: String, href: String): String {
        val clean = percentDecode(href.substringBefore('#'))
        val stack = mutableListOf<String>()
        if (!clean.startsWith("/")) {
            baseDir.split('/').filter { it.isNotEmpty() }.forEach { stack += it }
        }
        for (segment in clean.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
                else -> stack += segment
            }
        }
        return stack.joinToString("/")
    }

    /** Percent-decodes a URI path without mangling '+' (unlike URLDecoder). */
    fun percentDecode(value: String): String {
        if ('%' !in value) return value
        val bytes = mutableListOf<Byte>()
        var i = 0
        while (i < value.length) {
            val ch = value[i]
            if (ch == '%' && i + 2 <= value.lastIndex) {
                val byte = value.substring(i + 1, i + 3).toIntOrNull(16)
                if (byte != null) {
                    bytes += byte.toByte()
                    i += 3
                    continue
                }
            }
            for (b in ch.toString().toByteArray(Charsets.UTF_8)) bytes += b
            i++
        }
        return bytes.toByteArray().toString(Charsets.UTF_8)
    }

    /** Flat, collision-free base name for an asset inside an archive. */
    fun assetFileName(archivePath: String, used: MutableSet<String>): String {
        var base = archivePath.substringAfterLast('/').ifBlank { "asset" }
        base = base.replace(Regex("[^A-Za-z0-9._-]"), "_")
        var candidate = base
        var n = 1
        while (!used.add(candidate)) {
            val stem = base.substringBeforeLast('.')
            val ext = base.substringAfterLast('.', "")
            candidate = if (ext.isEmpty() || ext == base) "${stem}_$n" else "${stem}_$n.$ext"
            n++
        }
        return candidate
    }
}
