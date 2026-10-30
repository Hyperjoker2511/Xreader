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
