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
    private val imageResolver: (src: String) -> String? = { null },
    /**
     * D13 (Moon+ copy): resolves a footnote REFERENCE to the
     * note's converted blocks, or null when the reference is
     * not a footnote. Consulted only for anchors whose own
     * markup carries the `noteref` mark (Moon+'s substring
     * test — `epub:type="noteref"`, `role="doc-noteref"`, a
     * class carrying the word) and whose href names a
     * fragment. The caller (the EPUB parser) converts the
     * note body through a nested converter WITHOUT a
     * resolver, so a note's own references are never chased
     * (no recursion — Moon+ doesn't either). Null (the
     * default) disables footnote handling entirely: every
     * other converter consumer converts exactly as before.
     */
    private val footnoteResolver: ((href: String) -> List<ChapterBlock>?)? = null,
    /**
     * D13 (owner ruling Q3 — show once): element ids in THIS
     * document whose subtrees are suppressed at home — notes
     * that were inlined into their referencing paragraphs
     * elsewhere in the book must not also render where they
     * live. Only ids the parser's note index resolved AND a
     * marked reference claimed are ever passed here.
     */
    private val suppressedIds: Set<String> = emptySet()
) {

    data class Result(
        val blocks: List<ChapterBlock>,
        /** First h1/h2 text, if any (chapter-title fallback). */
        val heading: String?,
        /** The document <title>, if any. */
        val titleTag: String?,
        /**
         * v1.5.5 (§5): image elements whose reference(s) the
         * resolver could not resolve — ONE entry per failed
         * element (its primary reference), however many fallback
         * candidates (srcset entries, dual hrefs) were tried.
         * The EPUB parser counts the local ones into
         * droppedImages; before v1.5.5 a failed plate page could
         * convert to zero blocks and be skipped without the
         * resolver ever being consulted for its constructs
         * (srcset, CSS backgrounds), so nothing was counted.
         */
        val unresolvedImages: List<String> = emptyList(),
        /** v1.5.5 (§5): image blocks actually emitted. */
        val imagesEmitted: Int = 0
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
        // v1.5.5 (§5): per-element image accounting (see Result).
        val unresolvedImages = mutableListOf<String>()
        var imagesEmitted = 0

        /**
         * v1.5.5 (§5): resolve ONE image element from its
         * candidate references, in order, emitting at most one
         * Image block. The candidates are the shapes real
         * publisher markup uses for the same picture (src /
         * href / xlink:href / srcset) — trying the next one when
         * the resolver declines is what lets a dual-href SVG
         * plate or a srcset-only <img> land. Only a total miss
         * is recorded (once) in [unresolvedImages].
         */
        fun emitImage(candidates: List<String?>, alt: String) {
            val refs = candidates.mapNotNull { it?.trim()?.takeIf { v -> v.isNotEmpty() } }
                .distinct()
            if (refs.isEmpty()) return
            for (ref in refs) {
                val fileName = imageResolver(ref)
                if (fileName != null) {
                    blocks += ChapterBlock.Image(fileName, alt)
                    imagesEmitted++
                    return
                }
            }
            unresolvedImages += refs.first()
        }

        var skipDepth = 0
        var inTitle = false
        var boldDepth = 0
        var italicDepth = 0
        var preDepth = 0

        // D13: depth inside a suppressed note element (see
        // suppressedIds) — the subtree is walked for balance
        // only; nothing inside it converts.
        var suppressDepth = 0

        // D13: the notes collected for the paragraph under
        // construction, in reference order — emitted as ONE
        // FootnoteBox right after that paragraph (Moon+'s
        // paragraph-end placement).
        val pendingNotes = mutableListOf<ChapterBlock.FootnoteBox.Entry>()

        // D13: the open anchor's capture — its href, whether
        // its markup carries the noteref mark, and its
        // visible text (the note's linkName) as it flows
        // through appendSpanText.
        var anchorOpen = false
        var anchorHref: String? = null
        var anchorMarked = false
        val anchorText = StringBuilder()

        // Current paragraph under construction.
        val spans = mutableListOf<TextSpan>()
        var paragraphId: String? = null

        // v1.7 Moon Reader update (patch 1, A5): the id of an
        // <a id="…"> / <a name="…"> anchor seen since the last
        // emitted block. EPUB 2 chapter anchors are commonly
        // bare <a> elements sitting just before (or inside)
        // the heading or paragraph they name; the old walk
        // never read them, so a TOC fragment naming one could
        // not resolve. The next Heading or Paragraph emitted
        // carries it (its own id wins when it has one), and
        // emitting that block consumes it.
        var pendingAnchorId: String? = null

        // v1.0.6.2 (F7b): true right after a <br> with no real text
        // since — a second <br> in a row is a paragraph break in the
        // wild (chapters separated by <br/><br/>), not a line break.
        var lastWasBr = false

        fun flushParagraph() {
            // Trim edge whitespace from the assembled spans.
            while (spans.isNotEmpty() && spans.first().text.isBlank()) spans.removeAt(0)
            while (spans.isNotEmpty() && spans.last().text.isBlank()) spans.removeAt(spans.lastIndex)
            if (spans.isNotEmpty()) {
                val first = spans.first()
                spans[0] = first.copy(text = first.text.trimStart())
                val last = spans.last()
                spans[spans.lastIndex] = last.copy(text = last.text.trimEnd())
                blocks += ChapterBlock.Paragraph(
                    spans.toList(), paragraphId ?: pendingAnchorId
                )
                pendingAnchorId = null
                // D13: the paragraph's footnote box follows
                // it immediately — ONE box holding every note
                // the paragraph referred to, in reference
                // order (Moon+'s placement, §1.1 of the spec).
                if (pendingNotes.isNotEmpty()) {
                    blocks += ChapterBlock.FootnoteBox(pendingNotes.toList())
                }
            }
            // Notes collected against a paragraph that never
            // materialised (its only content was the
            // reference, trimmed away above) have no
            // paragraph to hang on — they are dropped with
            // it, never re-homed onto a later paragraph.
            pendingNotes.clear()
            spans.clear()
            paragraphId = null
        }

        fun appendSpanText(text: String) {
            // D13: the open anchor's visible text is its
            // note's linkName — capture it as it flows.
            if (anchorOpen) anchorText.append(text)
            val bold = boldDepth > 0
            val italic = italicDepth > 0
            val last = spans.lastOrNull()
            if (last != null && last.bold == bold && last.italic == italic) {
                spans[spans.lastIndex] = last.copy(text = last.text + text)
            } else {
                spans += TextSpan(text, bold, italic)
            }
        }

        fun appendText(raw: String) {
            if (raw.isEmpty()) return
            if (preDepth > 0) {
                appendSpanText(raw)
                if (raw.isNotBlank()) lastWasBr = false
                return
            }
            // v1.0.6.2 (F7b): a blank line INSIDE one text node (a
            // chapter stored as plain text in a single <div>, common
            // in machine-made EPUBs and site HTML) is a paragraph
            // break — the old whitespace collapse glued those
            // paragraphs into one block with no visible separation.
            val chunks = raw.split(BLANK_LINE)
            chunks.forEachIndexed { index, chunk ->
                if (index > 0) flushParagraph()
                val text = chunk.replace(WHITESPACE, " ")
                if (text.isNotEmpty()) appendSpanText(text)
                if (chunk.isNotBlank()) lastWasBr = false
            }
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
                    // D13 (Q3): suppression bookkeeping — an
                    // element carrying a suppressed id opens a
                    // muted subtree; elements inside it only
                    // count toward its depth.
                    if (suppressDepth > 0) {
                        suppressDepth++
                    } else if (suppressedIds.isNotEmpty() &&
                        parser.getAttributeValue(null, "id") in suppressedIds
                    ) {
                        suppressDepth = 1
                    }
                    if (skipDepth == 0 && suppressDepth == 0) {
                        // v1.5.5 (§5): a CSS background plate —
                        // a div/figure whose inline style paints
                        // the illustration as background-image.
                        // There is no <img> to walk; the style
                        // attribute IS the reference. Checked for
                        // every element before its own handling.
                        parser.getAttributeValue(null, "style")?.let { style ->
                            val urls = cssBackgroundUrls(style)
                            if (urls.isNotEmpty()) {
                                flushParagraph()
                                emitImage(urls, "")
                            }
                        }
                        when (name) {
                            "title" -> inTitle = true
                            "b", "strong" -> boldDepth++
                            "i", "em" -> italicDepth++
                            "pre" -> {
                                flushParagraph()
                                preDepth++
                            }
                            "br" -> {
                                // v1.0.6.2 (F7b): <br><br> (nothing but
                                // whitespace between) ends the paragraph;
                                // a single <br> stays a line break.
                                if (lastWasBr) {
                                    flushParagraph()
                                    lastWasBr = false
                                } else {
                                    appendText("\n")
                                    lastWasBr = true
                                }
                            }
                            "hr" -> {
                                flushParagraph()
                                // MR-4 (R7): the MOBI pre-pass's
                                // pagebreak marker class — a split
                                // signal for the HTML splitter,
                                // never a visible rule. The class
                                // only ever appears when that
                                // pre-pass ran (the MOBI flag), so
                                // every other parse is unchanged.
                                if (parser.getAttributeValue(null, "class")
                                        ?.split(' ', '\t')
                                        ?.contains("xreader-pagebreak") == true
                                ) {
                                    blocks += ChapterBlock.PageBreak
                                } else {
                                    blocks += ChapterBlock.Rule
                                }
                            }
                            "img", "image" -> {
                                flushParagraph()
                                // v1.5.5 (§5): every reference
                                // shape the element carries is a
                                // candidate — a plain src, an SVG
                                // <image>'s href AND xlink:href
                                // (publisher plates commonly carry
                                // both, one of them stale), and
                                // the srcset's best entry for a
                                // srcset-only <img>.
                                val alt = parser.getAttributeValue(null, "alt") ?: ""
                                emitImage(
                                    listOf(
                                        parser.getAttributeValue(null, "src"),
                                        parser.getAttributeValue(null, "href"),
                                        parser.getAttributeValue(
                                            "http://www.w3.org/1999/xlink", "href"
                                        ),
                                        bestSrcsetEntry(
                                            parser.getAttributeValue(null, "srcset")
                                        )
                                    ),
                                    alt
                                )
                            }
                            "source" -> {
                                // v1.5.5 (§5): a <picture>'s
                                // <source srcset> is the plate's
                                // only reference in some books.
                                emitImage(
                                    listOf(
                                        parser.getAttributeValue(null, "src"),
                                        bestSrcsetEntry(
                                            parser.getAttributeValue(null, "srcset")
                                        )
                                    ),
                                    ""
                                )
                            }
                            "h1", "h2", "h3", "h4", "h5", "h6" -> {
                                // Patch 1 (A5): a wrapper's id
                                // (<section id="ch3"><h2>…) is
                                // parked in paragraphId by the
                                // BLOCK_TAGS branch; the flush
                                // below used to discard it when
                                // no paragraph preceded the
                                // heading, losing the anchor.
                                // When the flush emits nothing,
                                // the id was naming THIS heading
                                // — transfer it (the heading's
                                // own id still wins).
                                val wrapperId = paragraphId
                                val blocksBefore = blocks.size
                                flushParagraph()
                                headingCapture = StringBuilder()
                                headingLevel = name[1] - '0'
                                headingId = parser.getAttributeValue(null, "id")
                                    ?: wrapperId.takeIf {
                                        blocks.size == blocksBefore
                                    }
                            }
                            "a" -> {
                                // Patch 1 (A5): a bare anchor
                                // element — park its id for the
                                // next emitted block (see
                                // pendingAnchorId above). An
                                // <a> that also links keeps its
                                // text handling untouched; only
                                // the id is new.
                                val anchorId = parser
                                    .getAttributeValue(null, "id")
                                    ?: parser.getAttributeValue(null, "name")
                                if (pendingAnchorId == null &&
                                    !anchorId.isNullOrBlank()
                                ) {
                                    pendingAnchorId = anchorId
                                }
                                // D13: open the footnote
                                // reference capture — the href,
                                // the noteref mark (Moon+'s
                                // substring test over the whole
                                // tag, run here over attribute
                                // names and values: the mark
                                // lives in epub:type, role or
                                // class), and the visible text
                                // via appendSpanText. The
                                // capture opens even inside a
                                // heading — its note then rides
                                // the next paragraph's box with
                                // an empty linkName, but it is
                                // never lost: the parser's
                                // claim scan reads the same
                                // tags and would suppress the
                                // note at home.
                                if (footnoteResolver != null && !anchorOpen) {
                                    val href = parser
                                        .getAttributeValue(null, "href")
                                    if (href != null) {
                                        anchorOpen = true
                                        anchorHref = href
                                        anchorText.setLength(0)
                                        anchorMarked =
                                            (0 until parser.attributeCount)
                                                .any { i ->
                                                    parser.getAttributeName(i)
                                                        ?.contains("noteref") == true ||
                                                        parser.getAttributeValue(i)
                                                            ?.contains("noteref") == true
                                                }
                                    }
                                }
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
                        skipDepth > 0 || suppressDepth > 0 -> Unit
                        inTitle -> titleTag = ((titleTag ?: "") + text).trim()
                        headingCapture != null -> headingCapture.append(text)
                        else -> appendText(text)
                    }
                }
                XmlPullParser.END_TAG -> {
                    val name = parser.name.lowercase()
                    if (name in SKIP_TAGS && skipDepth > 0) skipDepth--
                    // D13 (Q3): inside a suppressed subtree an
                    // end tag only unwinds the depth — nothing
                    // in it converts, not even a paragraph
                    // flush.
                    if (suppressDepth > 0) {
                        suppressDepth--
                    } else if (skipDepth == 0) {
                        when (name) {
                            "title" -> inTitle = false
                            "a" -> {
                                // D13: close the reference
                                // capture. A marked reference
                                // the resolver confirms becomes
                                // a pending note on the current
                                // paragraph; anything else (an
                                // unmarked link, a resolver
                                // miss) leaves the text exactly
                                // as it always was — plain.
                                if (anchorOpen) {
                                    anchorOpen = false
                                    val href = anchorHref
                                    anchorHref = null
                                    val resolver = footnoteResolver
                                    if (anchorMarked && href != null &&
                                        resolver != null
                                    ) {
                                        val noteBlocks = runCatching {
                                            resolver(href)
                                        }.getOrNull()
                                        if (noteBlocks != null) {
                                            pendingNotes +=
                                                ChapterBlock.FootnoteBox.Entry(
                                                    linkName = anchorText
                                                        .toString()
                                                        .replace(WHITESPACE, " ")
                                                        .trim(),
                                                    blocks = noteBlocks
                                                )
                                        }
                                    }
                                    anchorMarked = false
                                }
                            }
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
                                    // Patch 1 (A5): an anchor
                                    // parked inside or before
                                    // this heading names it when
                                    // the heading carries no id
                                    // of its own; the heading
                                    // consumes the parked anchor
                                    // either way — its position
                                    // was the anchor's target.
                                    val resolvedId = headingId ?: pendingAnchorId
                                    pendingAnchorId = null
                                    blocks += ChapterBlock.Heading(headingLevel, text, resolvedId)
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
        return Result(
            blocks, heading, titleTag?.takeIf { it.isNotEmpty() },
            unresolvedImages = unresolvedImages,
            imagesEmitted = imagesEmitted
        )
    }

    companion object {
        private val WHITESPACE = Regex("\\s+")

        /** v1.0.6.2 (F7b): a blank line inside a text node. */
        private val BLANK_LINE = Regex("\\n\\s*\\n")
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

        private val CSS_URL = Regex(
            """url\(\s*['"]?([^'")]+?)['"]?\s*\)""", RegexOption.IGNORE_CASE
        )

        /**
         * v1.5.5 (§5): the url() references of an inline style's
         * background declarations, in order. Only styles that
         * actually paint a background are read — a stray url() in
         * an unrelated property is not a plate.
         */
        fun cssBackgroundUrls(style: String): List<String> {
            if (!style.contains("background", ignoreCase = true)) return emptyList()
            return CSS_URL.findAll(style).map { it.groupValues[1].trim() }
                .filter { it.isNotEmpty() }.toList()
        }

        /**
         * v1.5.5 (§5): the best entry of a srcset attribute — the
         * widest `w` candidate when widths are declared, else the
         * first entry. Null for a blank srcset.
         */
        fun bestSrcsetEntry(srcset: String?): String? {
            val value = srcset?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val entries = value.split(',').mapNotNull { raw ->
                val parts = raw.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                if (parts.isEmpty()) {
                    null
                } else {
                    val width = parts.getOrNull(1)
                        ?.removeSuffix("w")?.toIntOrNull() ?: 0
                    parts[0] to width
                }
            }
            if (entries.isEmpty()) return null
            return entries.maxByOrNull { it.second }?.first ?: entries.first().first
        }

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
