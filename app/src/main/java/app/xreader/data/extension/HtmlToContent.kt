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
 * readable elements) and image tags are resolved to null: web chapter
 * images aren't downloaded in Phase 3 (noted in the phase report).
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

    fun fromDocument(doc: Document, baseUrl: String): ChapterContent {
        val xml = xmlSerialise(doc)
        val converted = runCatching {
            XhtmlConverter { null }.convert(xml)
        }.getOrNull()
        if (converted != null && converted.blocks.isNotEmpty()) {
            return ChapterContent(converted.blocks)
        }
        return plainTextFallback(doc.body().text())
    }

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
        val paragraphs = text.split(Regex("\\n\\s*\\n"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        return ChapterContent(
            paragraphs.map { ChapterBlock.Paragraph.plain(it) }
        )
    }
}
