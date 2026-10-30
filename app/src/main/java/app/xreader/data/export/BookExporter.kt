package app.xreader.data.export

import app.xreader.core.error.AppError
import app.xreader.core.error.AppResult
import app.xreader.data.db.Book
import app.xreader.data.db.BookFormat
import app.xreader.data.db.ChapterDao
import app.xreader.data.files.BookStorage
import app.xreader.domain.content.ChapterBlock
import app.xreader.domain.content.ChapterContent
import app.xreader.domain.content.TextSpan
import java.io.File
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Export a library book (PLAN §4 — the Downloads screen's "Make EPUB"
 * action, and Book Detail's export):
 *  - EPUB: rebuilt from the stored normalised chapters — mimetype
 *    stored uncompressed first (spec), container, OPF with manifest/
 *    spine, EPUB 3 nav + NCX, one XHTML per chapter, cover and page
 *    images embedded, Media Overlay audio carried over with a fresh
 *    SMIL per chapter.
 *  - CBZ: page images in reading order, for image books.
 * The EPUB writer is pure stream code: the round-trip test parses the
 * export with our own EpubParser and compares chapter text.
 */
class BookExporter(
    private val storage: BookStorage,
    private val chapterDao: ChapterDao
) {

    suspend fun exportEpub(book: Book, out: OutputStream): AppResult<Unit> {
        val chapters = chapterDao.forBook(book.id)
        if (chapters.isEmpty()) {
            return AppResult.Err(AppError.ExportFailed("this book has no chapters yet"))
        }
        // v1.0.6.2 (Gemini fix round, G2): the export used to
        // materialise every chapter's full content up front — on
        // a ~1,500-chapter book that is the whole book in memory
        // before the first byte is written, an OOM waiting on the
        // 2 GB tablet. Now a METADATA pass mines each chapter once
        // for the lightweight facts assembly needs (asset names in
        // encounter order, which chapters carry a media overlay,
        // whether anything is readable) and releases it before the
        // next; the write pass below re-reads ONE chapter at a
        // time. Peak memory is one chapter, the chapter contents
        // are never all held at once, and the refusal below still
        // happens before any byte is written.
        val assetNames = LinkedHashSet<String>()
        val hasOverlay = BooleanArray(chapters.size)
        var anyReadable = false
        chapters.forEachIndexed { index, chapter ->
            val content = if (chapter.contentPath != null) {
                storage.readChapterContent(book.id, chapter.id).getOrNull()
            } else {
                null
            } ?: return@forEachIndexed
            if (content.blocks.isNotEmpty()) anyReadable = true
            for (block in content.blocks) {
                if (block is ChapterBlock.Image) assetNames += block.fileName
            }
            content.mediaOverlay?.let { overlay ->
                assetNames += overlay.audioFileName
                hasOverlay[index] = true
            }
        }
        if (!anyReadable) {
            return AppResult.Err(
                AppError.ExportFailed("download the chapters first, then export")
            )
        }

        return guardExport {
            ZipOutputStream(out).use { zip ->
                // mimetype: stored, first.
                val mime = ZipEntry("mimetype")
                val mimeBytes = "application/epub+zip".toByteArray(Charsets.US_ASCII)
                mime.method = ZipEntry.STORED
                mime.size = mimeBytes.size.toLong()
                mime.crc = CRC32().apply { update(mimeBytes) }.value
                zip.putNextEntry(mime)
                zip.write(mimeBytes)
                zip.closeEntry()

                writeEntry(zip, "META-INF/container.xml", CONTAINER_XML)

                // Assets: images + overlay audio, referenced by
                // chapters (names collected by the metadata pass).
                val contentDir = storage.contentDir(book.id)
                val assetIds = mutableMapOf<String, String>()
                for ((index, name) in assetNames.withIndex()) {
                    // Assets live on disk with the img- prefix (§1.4).
                    val file = File(contentDir, "img-$name")
                    if (!file.isFile) continue
                    val path = "assets/${name.replace(' ', '_')}"
                    assetIds[name] = "asset$index"
                    zip.putNextEntry(ZipEntry(path))
                    file.inputStream().buffered().use { it.copyTo(zip) }
                    zip.closeEntry()
                }

                val hrefOf = { name: String -> "assets/${name.replace(' ', '_')}" }
                val coverRel = book.coverPath?.substringAfterLast('/')
                val coverFile = book.coverPath?.let { storage.resolve(it) }
                val coverExt = coverRel?.substringAfterLast('.', "jpg") ?: "jpg"
                if (coverFile?.isFile == true) {
                    zip.putNextEntry(ZipEntry("cover.$coverExt"))
                    coverFile.inputStream().buffered().use { it.copyTo(zip) }
                    zip.closeEntry()
                }

                // Chapter XHTML + SMIL — the streaming pass: load
                // ONE chapter, write it, release it (G2).
                val chapterHrefs = chapters.indices.map { "chapter-${it + 1}.xhtml" }
                chapters.forEachIndexed { index, chapter ->
                    val content = if (chapter.contentPath != null) {
                        storage.readChapterContent(book.id, chapter.id).getOrNull()
                    } else {
                        null
                    } ?: ChapterContent()
                    writeEntry(
                        zip, chapterHrefs[index],
                        chapterXhtml(chapter.title, content, hrefOf)
                    )
                    content.mediaOverlay?.let { overlay ->
                        writeEntry(
                            zip, "chapter-${index + 1}.smil",
                            smilXml(chapterHrefs[index], overlay, hrefOf)
                        )
                    }
                }

                writeEntry(zip, "nav.xhtml", navXhtml(book, chapters.map { it.title }, chapterHrefs))
                writeEntry(zip, "toc.ncx", ncxXml(book, chapters.map { it.title }, chapterHrefs))
                writeEntry(
                    zip, "content.opf",
                    opfXml(book, chapters.map { it.title }, chapterHrefs, hasOverlay.toList(),
                        assetIds, hrefOf, coverFile?.isFile == true, coverExt)
                )
            }
        }
    }

    suspend fun exportCbz(book: Book, out: OutputStream): AppResult<Unit> {
        val chapters = chapterDao.forBook(book.id)
        val contentDir = storage.contentDir(book.id)
        val pages = mutableListOf<File>()
        for (chapter in chapters) {
            val content = if (chapter.contentPath != null) {
                storage.readChapterContent(book.id, chapter.id).getOrNull()
            } else {
                null
            } ?: continue
            for (block in content.blocks) {
                if (block is ChapterBlock.Image) {
                    val file = File(contentDir, "img-${block.fileName}")
                    if (file.isFile) pages += file
                }
            }
        }
        if (pages.isEmpty()) {
            return AppResult.Err(
                AppError.ExportFailed("CBZ export is for comic books — this one has no page images")
            )
        }
        return guardExport {
            ZipOutputStream(out).use { zip ->
                pages.forEachIndexed { index, file ->
                    val ext = file.extension.ifBlank { "jpg" }
                    zip.putNextEntry(ZipEntry("%04d.%s".format(index + 1, ext)))
                    file.inputStream().buffered().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
    }

    // -- XHTML generation --------------------------------------------------------

    private fun chapterXhtml(
        title: String,
        content: ChapterContent,
        hrefOf: (String) -> String
    ): String {
        val body = StringBuilder()
        var headingWritten = false
        for (block in content.blocks) {
            when (block) {
                is ChapterBlock.Heading -> {
                    body.append("<h").append(block.level.coerceIn(1, 6))
                    block.id?.let { body.append(" id=\"").append(escapeAttr(it)).append('"') }
                    body.append('>').append(escapeXml(block.text))
                        .append("</h").append(block.level.coerceIn(1, 6)).append('>')
                    if (block.level == 1) headingWritten = true
                }
                is ChapterBlock.Paragraph -> {
                    body.append("<p")
                    block.id?.let { body.append(" id=\"").append(escapeAttr(it)).append('"') }
                    body.append('>')
                    for (span in block.spans) body.append(spanXhtml(span))
                    body.append("</p>")
                }
                is ChapterBlock.Image ->
                    body.append("<p><img src=\"").append(escapeAttr(hrefOf(block.fileName)))
                        .append("\" alt=\"").append(escapeAttr(block.alt)).append("\"/></p>")
                is ChapterBlock.Rule -> body.append("<hr/>")
            }
        }
        if (!headingWritten) {
            body.insert(0, "<h1>${escapeXml(title)}</h1>")
        }
        return """<?xml version="1.0" encoding="utf-8"?>
<html xmlns="http://www.w3.org/1999/xhtml"><head><title>${escapeXml(title)}</title></head>
<body>$body</body></html>"""
    }

    private fun spanXhtml(span: TextSpan): String {
        val escaped = escapeXml(span.text)
        return when {
            span.bold && span.italic -> "<strong><em>$escaped</em></strong>"
            span.bold -> "<strong>$escaped</strong>"
            span.italic -> "<em>$escaped</em>"
            else -> escaped
        }
    }

    private fun smilXml(
        chapterHref: String,
        overlay: app.xreader.domain.content.MediaOverlay,
        hrefOf: (String) -> String
    ): String {
        val pars = overlay.clips.joinToString("") { clip ->
            "<par><text src=\"${escapeAttr(chapterHref)}#${escapeAttr(clip.textId)}\"/>" +
                "<audio src=\"${escapeAttr(hrefOf(overlay.audioFileName))}\" " +
                "clipBegin=\"${clip.beginSec}s\" clipEnd=\"${clip.endSec}s\"/></par>"
        }
        return """<?xml version="1.0" encoding="utf-8"?>
<smil xmlns="http://www.w3.org/ns/SMIL" xmlns:epub="http://www.idpf.org/2007/ops"><body><seq>$pars</seq></body></smil>"""
    }

    // -- package documents -------------------------------------------------------

    private fun navXhtml(book: Book, titles: List<String>, hrefs: List<String>): String {
        val items = titles.mapIndexed { index, title ->
            "<li><a href=\"${hrefs[index]}\">${escapeXml(title)}</a></li>"
        }.joinToString("")
        return """<?xml version="1.0" encoding="utf-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>${escapeXml(book.title)}</title></head>
<body><nav epub:type="toc"><ol>$items</ol></nav></body></html>"""
    }

    private fun ncxXml(book: Book, titles: List<String>, hrefs: List<String>): String {
        val points = titles.mapIndexed { index, title ->
            "<navPoint id=\"np${index + 1}\" playOrder=\"${index + 1}\">" +
                "<navLabel><text>${escapeXml(title)}</text></navLabel>" +
                "<content src=\"${hrefs[index]}\"/></navPoint>"
        }.joinToString("")
        return """<?xml version="1.0" encoding="utf-8"?>
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1"><head></head>
<docTitle><text>${escapeXml(book.title)}</text></docTitle><navMap>$points</navMap></ncx>"""
    }

    private fun opfXml(
        book: Book,
        titles: List<String>,
        hrefs: List<String>,
        /** G2: only the overlay PRESENCE per chapter is needed
         * here — the full contents are never held for assembly. */
        hasOverlay: List<Boolean>,
        assetIds: Map<String, String>,
        hrefOf: (String) -> String,
        hasCover: Boolean,
        coverExt: String
    ): String {
        val manifest = StringBuilder()
        val spine = StringBuilder()
        titles.forEachIndexed { index, _ ->
            val overlay = hasOverlay.getOrNull(index) == true
            val overlayAttr = if (overlay) " media-overlay=\"smil${index + 1}\"" else ""
            manifest.append(
                "<item id=\"ch${index + 1}\" href=\"${hrefs[index]}\" " +
                    "media-type=\"application/xhtml+xml\"$overlayAttr/>"
            )
            if (overlay) {
                manifest.append(
                    "<item id=\"smil${index + 1}\" href=\"chapter-${index + 1}.smil\" " +
                        "media-type=\"application/smil+xml\"/>"
                )
            }
            spine.append("<itemref idref=\"ch${index + 1}\"/>")
        }
        for ((name, id) in assetIds) {
            val mime = mimeFor(name)
            manifest.append("<item id=\"$id\" href=\"${hrefOf(name)}\" media-type=\"$mime\"/>")
        }
        manifest.append(
            "<item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>"
        )
        manifest.append(
            "<item id=\"ncx\" href=\"toc.ncx\" media-type=\"application/x-dtbncx+xml\"/>"
        )
        if (hasCover) {
            manifest.append(
                "<item id=\"cover\" href=\"cover.$coverExt\" media-type=\"${mimeFor("cover.$coverExt")}\" properties=\"cover-image\"/>"
            )
        }
        val identifier = "urn:xreader:book:${book.id}"
        return """<?xml version="1.0" encoding="utf-8"?>
<package xmlns="http://www.idpf.org/2007/opf" unique-identifier="bookid" version="3.0">
<metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
<dc:identifier id="bookid">$identifier</dc:identifier>
<dc:title>${escapeXml(book.title)}</dc:title>
${book.author?.let { "<dc:creator>${escapeXml(it)}</dc:creator>" } ?: ""}
${book.description?.let { "<dc:description>${escapeXml(it)}</dc:description>" } ?: ""}
<dc:language>en</dc:language>
<meta property="dcterms:modified">2026-01-01T00:00:00Z</meta>
</metadata>
<manifest>$manifest</manifest>
<spine toc="ncx">$spine</spine>
</package>"""
    }

    private fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "svg" -> "image/svg+xml"
        "mp3" -> "audio/mpeg"
        "m4a", "aac" -> "audio/aac"
        "ogg" -> "audio/ogg"
        "opus" -> "audio/opus"
        else -> "image/jpeg"
    }

    /** Export failures speak as ExportFailed, never as a raw crash.
     * Suspend since G2: the streaming write pass reads chapter
     * contents inside the guarded block. */
    private suspend fun guardExport(block: suspend () -> Unit): AppResult<Unit> = try {
        block()
        AppResult.Ok(Unit)
    } catch (e: Exception) {
        AppResult.Err(
            AppError.ExportFailed((e.message ?: "the file couldn't be written").take(160))
        )
    }

    private fun writeEntry(zip: ZipOutputStream, name: String, content: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(content.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    companion object {
        private const val CONTAINER_XML = """<?xml version="1.0" encoding="utf-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
<rootfiles><rootfile full-path="content.opf" media-type="application/oebps-package+xml"/></rootfiles>
</container>"""

        fun escapeXml(text: String): String = text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")

        fun escapeAttr(text: String): String = escapeXml(text).replace("\"", "&quot;")
    }
}
