package app.xreader.data.extension

import app.xreader.domain.content.ChapterBlock
import app.xreader.domain.content.ChapterContent
import app.xreader.data.parse.XhtmlConverter
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser
import org.jsoup.nodes.Element

/**
 * Turns a fetched chapter HTML fragment into the app's normalised
 * [ChapterContent]. The fragment is serialised by jsoup with XML
 * syntax (so the Phase 2 XHTML converter accepts it — scripts and
 * styles are dropped by construction, since the converter only walks
 * readable elements).
 *
 * Images: the plain entry points resolve every image tag to null —
 * the Phase 3 behaviour, kept for the text-only consumers (the
 * declarative engine's internal conversion, test-drive sampling).
 * The download path (ExtensionChapterFetcher) calls the
 * imageResolver overloads instead: it downloads the fragment's
 * images first, through the source's own session, and the resolver
 * maps each raw `src` to its stored file name (v1.0.6.2).
 *
 * If the structured conversion somehow yields nothing, the fallback is
 * plain paragraphs from the element's text — a chapter must never come
 * back empty just because its markup was unusual.
 */
object HtmlToContent {

    fun fromFragment(fragmentHtml: String, baseUrl: String): ChapterContent {
        val doc = Jsoup.parseBodyFragment(fragmentHtml, baseUrl)
        return fromDocument(doc, baseUrl)
    }

    /** Chapter-path conversion: [imageResolver] gets each raw image
     * `src` and returns the stored content-dir file name, or null
     * to drop that image (the G4 UrlChapterFetcher contract). */
    fun fromFragment(
        fragmentHtml: String,
        baseUrl: String,
        imageResolver: (String) -> String?
    ): ChapterContent {
        val doc = Jsoup.parseBodyFragment(fragmentHtml, baseUrl)
        return fromDocument(doc, baseUrl, imageResolver)
    }

    fun fromDocument(doc: Document, baseUrl: String): ChapterContent =
        fromDocument(doc, baseUrl) { null }

    fun fromDocument(
        doc: Document,
        baseUrl: String,
        imageResolver: (String) -> String?
    ): ChapterContent {
        val xml = xmlSerialise(doc)
        val converted = runCatching {
            XhtmlConverter(imageResolver).convert(xml)
        }.getOrNull()
        if (converted != null && converted.blocks.isNotEmpty()) {
            // v1.0.8.3 (the woopread lesson): the structured
            // conversion can succeed yet keep only a SLIVER of
            // a chapter — markup it walks past (content held in
            // elements it skips) while a promo card converts
            // cleanly. The drive then reads the card, not the
            // chapter, and the reader would too. When the
            // blocks carry less than half of the text the
            // document itself holds, the plain-text reading of
            // the whole element is the honest chapter. Small
            // documents never trigger this (the floor), and a
            // faithful conversion always carries ~all of the
            // text, so ordinary chapters are untouched.
            val blockChars = converted.blocks.sumOf { block ->
                when (block) {
                    is ChapterBlock.Paragraph -> block.text.length
                    is ChapterBlock.Heading -> block.text.length
                    else -> 0
                }
            }
            val docText = visibleText(doc)
            if (docText.length < MIN_GUARD_TEXT ||
                blockChars * 2 >= docText.length
            ) {
                return ChapterContent(converted.blocks)
            }
            return plainTextFallback(docText)
        }
        return plainTextFallback(visibleText(doc))
    }

    /** The document's readable text with scripts and styles
     * removed — what a person looking at the page could read.
     * (The pre-v1.0.8.3 fallback read body text raw, which on
     * script-carrying fragments could fold a page's data
     * payload into the "chapter".) */
    private fun visibleText(doc: Document): String {
        val copy = doc.clone()
        copy.select("script, style").remove()
        return structuredText(copy)
    }

    /**
     * v1.3.2 (item 87): the same readable text with PARAGRAPH
     * STRUCTURE kept. jsoup's `Element.text()` collapses every
     * boundary into a single space, so the fallback built one
     * giant paragraph per chapter and the reader's paragraph
     * spacing could never show. Here block elements close a
     * paragraph (`\n\n`) and `<br>` breaks a line (`\n`), which
     * is exactly what [plainTextFallback] splits on.
     */
    private fun structuredText(doc: Document): String {
        val body = doc.body() ?: return ""
        val out = StringBuilder()
        fun walk(node: org.jsoup.nodes.Node) {
            when (node) {
                is org.jsoup.nodes.TextNode -> out.append(node.text())
                is Element -> {
                    if (node.tagName() == "br") {
                        out.append('\n')
                        return
                    }
                    node.childNodes().forEach(::walk)
                    if (node.tagName() in BLOCK_TAGS) out.append("\n\n")
                }
                else -> node.childNodes().forEach(::walk)
            }
        }
        walk(body)
        return out.toString()
            .replace(Regex("[ \\t\\x0B\\f]+"), " ")
            .replace(Regex(" *\\n *"), "\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    private val BLOCK_TAGS = setOf(
        "p", "div", "li", "ul", "ol", "blockquote", "pre", "tr",
        "h1", "h2", "h3", "h4", "h5", "h6",
        "section", "article", "header", "footer", "table"
    )

    /** Below this much readable text the sliver guard stays
     * out of the way — short chapters and fragments convert
     * exactly as they always have. */
    private const val MIN_GUARD_TEXT = 400

    fun fromElement(element: Element, baseUrl: String): ChapterContent {
        val doc = Jsoup.parseBodyFragment(element.html(), baseUrl)
        return fromDocument(doc, baseUrl)
    }

    /** Serialise a jsoup document as well-formed XHTML for the
     * converter: XML syntax, XHTML escaping, no pretty-printing. */
    fun xmlSerialise(doc: Document): String {
        val copy = doc.clone()
        copy.outputSettings(
            Document.OutputSettings()
                .syntax(Document.OutputSettings.Syntax.xml)
                .escapeMode(org.jsoup.nodes.Entities.EscapeMode.xhtml)
                .prettyPrint(false)
        )
        copy.charset(Charsets.UTF_8)
        return copy.html()
    }

    fun plainTextFallback(text: String): ChapterContent {
        var paragraphs = text.split(Regex("\\n\\s*\\n"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (paragraphs.size == 1) {
            // v1.3.2 (item 87): line-per-paragraph plain text
            // (single line breaks, no blank lines) would render
            // as one wall. When the lines look like paragraphs —
            // several of them, long, and mostly ending a
            // sentence — each line is its own paragraph.
            // Hard-wrapped prose fails the punctuation test and
            // stays whole.
            val lines = paragraphs[0].split('\n')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            if (lines.size >= 4) {
                val lengths = lines.map { it.length }.sorted()
                val median = lengths[lengths.size / 2]
                val sentenceEnds = lines.count {
                    it.last() in ".!?\"”’"
                }
                if (median >= 50 && sentenceEnds * 2 >= lines.size) {
                    paragraphs = lines
                }
            }
        }
        return ChapterContent(
            paragraphs.map { ChapterBlock.Paragraph.plain(it) }
        )
    }
}
