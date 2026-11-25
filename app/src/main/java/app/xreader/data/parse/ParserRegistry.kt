package app.xreader.data.parse

import app.xreader.data.db.BookFormat

/**
 * Format → parser routing (PLAN §4). Sniffing decides the format from
 * file CONTENT ([FormatSniffer]); this registry only maps it to the
 * implementation. PDF needs the platform extractor, injected by the
 * container so unit tests can substitute a fake.
 */
class ParserRegistry(
    private val pdfExtractor: PdfTextExtractor?,
    private val txtParserFactory: (String?) -> TxtParser = { TxtParser() }
) {

    /** MR-6: a TXT parser bound to one book's encoding-record key
     * (its `Book.filePath`) — the re-detection / re-decode path,
     * where the per-file override must be consulted and the
     * detected charset recorded against that exact key. Plain
     * imports go through [parserFor] with no key: the book has no
     * path yet at parse time, and the importer records the
     * detected charset against the landed path itself. */
    fun txtParser(pathKey: String): TxtParser = txtParserFactory(pathKey)

    fun parserFor(format: BookFormat): BookParser? = when (format) {
        BookFormat.EPUB -> EpubParser()
        BookFormat.TXT -> txtParserFactory(null)
        BookFormat.MARKDOWN -> MarkdownParser()
        BookFormat.HTML -> HtmlParser()
        BookFormat.FB2 -> Fb2Parser()
        BookFormat.CBZ -> CbzParser()
        BookFormat.CBR -> CbrParser()
        BookFormat.MOBI -> MobiParser(BookFormat.MOBI)
        BookFormat.AZW3 -> MobiParser(BookFormat.AZW3)
        BookFormat.DOCX -> OfficeXmlParser(BookFormat.DOCX)
        BookFormat.ODT -> OfficeXmlParser(BookFormat.ODT)
        BookFormat.RTF -> RtfParser()
        BookFormat.MHTML -> MhtmlParser()
        BookFormat.CHM -> ChmParser()
        BookFormat.UMD -> UmdParser()
        BookFormat.PDF -> pdfExtractor?.let { PdfBookParser(it) }
        BookFormat.UNKNOWN -> null
    }
}
