package app.xreader.data.parse

import app.xreader.data.db.BookFormat

/**
 * Format → parser routing (PLAN §4). Sniffing decides the format from
 * file CONTENT ([FormatSniffer]); this registry only maps it to the
 * implementation. PDF needs the platform extractor, injected by the
 * container so unit tests can substitute a fake.
 */
class ParserRegistry(private val pdfExtractor: PdfTextExtractor?) {

    fun parserFor(format: BookFormat): BookParser? = when (format) {
        BookFormat.EPUB -> EpubParser()
        BookFormat.TXT -> TxtParser()
        BookFormat.MARKDOWN -> MarkdownParser()
        BookFormat.HTML -> HtmlParser()
        BookFormat.FB2 -> Fb2Parser()
        BookFormat.CBZ -> CbzParser()
        BookFormat.CBR -> CbrParser()
        BookFormat.MOBI -> MobiParser(BookFormat.MOBI)
        BookFormat.AZW3 -> MobiParser(BookFormat.AZW3)
        BookFormat.PDF -> pdfExtractor?.let { PdfBookParser(it) }
        BookFormat.UNKNOWN -> null
    }
}
