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
 * (base64) extracted to assets. Inline emphasis/strong map to spans,
 * like the other parsers.
 *
 * MR-4 (Revision 1, R4): the section walk is an explicit STACK,
 * copying Moon+'s Fb2 chapter-building. Every TITLED section is a
 * chapter at any depth (its level rides [ParsedBook.chapterLevels]);
 * a chapter holds its section's OWN blocks in document order —
 * before its first child section, between children, and after its
 * last child. An UNTITLED section reserves no chapter: on its
 * close its blocks append to the enclosing section (or, at top
 * level, to the previous chapter; before any chapter, to the
 * front-matter accumulator). A titled chapter is emitted even
 * with zero blocks of its own (R3 — a Part divider whose text
 * lives in its children still gets its Contents row). Content
 * before the first titled section, when non-blank, forms a first
 * chapter titled with the book title. A `<body name="notes">` is
 * diverted whole into ONE final chapter titled "Footnotes"
 * (Moon+'s string), its inner sections flattened into it; any
 * other named body is sliced as ordinary content in place, so
 * no text is ever lost.
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
                    assetsDir = assetsDir.takeIf { it.list()?.isNotEmpty() == true },
                    chapterLevels = document.levels
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
        val chapters: List<Chapter>,
        /** MR-4: Contents depth per chapter, parallel to
         * [chapters] (0 = top level). */
        val levels: List<Int> = emptyList()
    )

    /** One open `<section>` on the stack (R4): its title (null
     * until its `<title>` closes — an untitled section is only
     * known at its close), its tree level, and its OWN blocks
     * in document order. A titled frame IS its chapter slot:
     * [parse] keeps every frame in a slot list in document
     * order, and an untitled frame's slot is revoked at close,
     * its blocks absorbed upward. */
    private class SectionFrame(val level: Int) {
        var title: String? = null
        val blocks = mutableListOf<ChapterBlock>()
    }

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

        // MR-4 (R4): the section stack + chapter slots.
        val slots = mutableListOf<SectionFrame>()
        val stack = ArrayDeque<SectionFrame>()
        /** Blocks before the first titled section (Moon+'s
         * leftover-text home): slotted as chapter 0, titled
         * with the book title, the moment the first section
         * opens — or at the end if no section ever opens. */
        val frontFrame = SectionFrame(0)
        var frontSlotted = false
        /** The notes body's flattened blocks (R4). */
        val notesBlocks = mutableListOf<ChapterBlock>()

        // State machine over the flat event stream.
        var inTitleInfo = false
        var inBody = false
        var inNotesBody = false
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

        /** Where body content lands right now: the notes sink
         * inside the notes body; the innermost open section's
         * own blocks inside a section; the previous chapter's
         * blocks between top-level sections; the front-matter
         * accumulator before the first section. */
        fun currentTarget(): MutableList<ChapterBlock> = when {
            inNotesBody -> notesBlocks
            stack.isNotEmpty() -> stack.last().blocks
            slots.isNotEmpty() -> slots.last().blocks
            else -> frontFrame.blocks
        }

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
                currentTarget().add(ChapterBlock.Paragraph(cleaned.toList()))
            }
            spans.clear()
            paragraphOpen = false
        }

        /** R4: an untitled frame's slot is revoked and its
         * blocks absorbed — into the enclosing frame, or at
         * top level into the previous chapter (Moon+'s
         * absorption), or into the front matter when no
         * chapter exists yet. A titled frame simply keeps
         * its slot; its chapter is emitted even with zero
         * blocks (R3). */
        fun finaliseFrame(frame: SectionFrame) {
            if (!frame.title.isNullOrBlank()) return
            slots.remove(frame)
            val dest = when {
                stack.isNotEmpty() -> stack.last().blocks
                slots.isNotEmpty() -> slots.last().blocks
                else -> frontFrame.blocks
            }
            dest.addAll(frame.blocks)
            frame.blocks.clear()
        }

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "title-info" -> inTitleInfo = true
                    "body" -> {
                        inBody = true
                        // R4: Moon+ searches the body `name`
                        // attribute, "notes" preferred — that
                        // body is diverted whole; every other
                        // body slices in place.
                        inNotesBody =
                            parser.getAttributeValue(null, "name") == "notes"
                    }
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
                            currentTarget().add(ChapterBlock.Image(name))
                        }
                    }
                    "binary" -> {
                        inBinary = true
                        binaryId = parser.getAttributeValue(null, "id")
                        binaryType = parser.getAttributeValue(null, "content-type")
                        binaryText.setLength(0)
                    }
                    "section" -> if (inBody && !inNotesBody) {
                        flushParagraph()
                        // The front matter, if any has
                        // collected, takes chapter 0's slot
                        // before the first section's own.
                        if (!frontSlotted && stack.isEmpty() &&
                            slots.isEmpty() && frontFrame.blocks.isNotEmpty()
                        ) {
                            slots += frontFrame
                            frontSlotted = true
                        }
                        val frame = SectionFrame(stack.size)
                        stack.addLast(frame)
                        slots += frame
                    }
                    "title" -> {
                        if (inBody && (stack.isNotEmpty() || inNotesBody)) {
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
                        flushParagraph()
                        // Unwind any section left open by a
                        // malformed file, innermost first.
                        while (stack.isNotEmpty()) {
                            finaliseFrame(stack.removeLast())
                        }
                        inBody = false
                        inNotesBody = false
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
                    "section" -> if (inBody && !inNotesBody) {
                        flushParagraph()
                        val frame = stack.removeLastOrNull()
                        if (frame != null) finaliseFrame(frame)
                    }
                    "title" -> if (inSectionTitle) {
                        inSectionTitle = false
                        val text = sectionTitleCapture?.toString()
                            ?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
                        sectionTitleCapture = null
                        when {
                            inNotesBody -> if (text.isNotEmpty()) {
                                // R4: the notes chapter is ONE
                                // chapter — its sections' titles
                                // flatten into heading blocks.
                                notesBlocks += ChapterBlock.Heading(2, text)
                            }
                            else -> {
                                val frame = stack.lastOrNull()
                                if (frame != null) {
                                    if (frame.title == null) {
                                        frame.title =
                                            text.takeIf { it.isNotEmpty() }
                                    } else if (text.isNotEmpty()) {
                                        // A second title inside one
                                        // section is a heading in it
                                        // (the pre-MR-4 shape).
                                        frame.blocks +=
                                            ChapterBlock.Heading(2, text)
                                    }
                                }
                            }
                        }
                    }
                    "p", "subtitle", "text-author" -> if (inBody) flushParagraph()
                    "emphasis" -> if (italicDepth > 0) italicDepth--
                    "strong" -> if (boldDepth > 0) boldDepth--
                }
            }
            event = parser.next()
        }

        // A book with no titled section at all: the front
        // matter, if it collected anything, is the book's one
        // chapter (Moon+'s leftover-text chapter).
        if (slots.isEmpty() && frontFrame.blocks.isNotEmpty()) {
            slots += frontFrame
            frontSlotted = true
        }
        if (frontSlotted) {
            frontFrame.title = title
        }
        val chapters = mutableListOf<Chapter>()
        val levels = mutableListOf<Int>()
        slots.forEachIndexed { index, frame ->
            chapters += Chapter(
                frame.title?.takeIf { it.isNotBlank() }
                    ?: "Chapter ${index + 1}",
                frame.blocks.toList()
            )
            levels += frame.level
        }
        // Moon+ (Fb2.java): a lone chapter titled "..." is
        // renamed to the book title.
        if (chapters.size == 1 && chapters[0].title == "..." &&
            !title.isNullOrBlank()
        ) {
            chapters[0] = chapters[0].copy(title = title)
        }
        // R4: the notes body lands as ONE final chapter,
        // Moon+'s "Footnotes" string, level 0.
        if (notesBlocks.isNotEmpty()) {
            chapters += Chapter("Footnotes", notesBlocks.toList())
            levels += 0
        }

        val author = listOfNotNull(authorFirst, authorLast).joinToString(" ").trim()
        return Document(
            title = title,
            author = author.takeIf { it.isNotEmpty() },
            annotation = annotation,
            language = language,
            coverBinaryId = coverBinaryId,
            imageNames = imageNames,
            chapters = chapters,
            levels = levels
        )
    }
}
