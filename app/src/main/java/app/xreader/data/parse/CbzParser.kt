package app.xreader.data.parse

import app.xreader.core.error.AppError
import app.xreader.core.error.AppResult
import app.xreader.data.db.BookFormat
import app.xreader.domain.content.ChapterBlock
import app.xreader.domain.content.ChapterContent
import java.io.File
import java.util.zip.ZipFile

/**
 * Natural (numeric-aware) ordering for page file names: `2.jpg` sorts
 * before `10.jpg`. Pure and directly testable.
 */
object NaturalSort : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                var endA = i
                while (endA < a.length && a[endA].isDigit()) endA++
                var endB = j
                while (endB < b.length && b[endB].isDigit()) endB++
                val numA = a.substring(i, endA).trimStart('0').ifEmpty { "0" }
                val numB = b.substring(j, endB).trimStart('0').ifEmpty { "0" }
                if (numA.length != numB.length) return numA.length - numB.length
                val byNum = numA.compareTo(numB)
                if (byNum != 0) return byNum
                // Equal numbers: fewer leading zeros first (stable, total).
                if (endA - i != endB - j) return (endA - i) - (endB - j)
                i = endA
                j = endB
            } else {
                val byChar = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (byChar != 0) return byChar
                i++
                j++
            }
        }
        return (a.length - i) - (b.length - j)
    }
}

/**
 * Content-based image detection (FIX-18, donor DirectoryPageLoader
 * semantics — Komikku's ImageUtil.isImage: extension OR content).
 * Sniffs the first bytes for the donor ImageType family's magic:
 * JPEG, PNG, GIF, WebP, AVIF, HEIF, JXL. Pure and testable.
 */
object ImageSniff {

    fun isImageContent(head: ByteArray): Boolean {
        if (head.size < 4) return false
        fun u(i: Int): Int = head[i].toInt() and 0xFF
        fun ascii(from: Int, text: String): Boolean {
            if (head.size < from + text.length) return false
            return text.indices.all { head[from + it] == text[it].code.toByte() }
        }
        return when {
            // JPEG: FF D8 FF
            u(0) == 0xFF && u(1) == 0xD8 && u(2) == 0xFF -> true
            // PNG: 89 50 4E 47
            u(0) == 0x89 && ascii(1, "PNG") -> true
            // GIF: "GIF8"
            ascii(0, "GIF8") -> true
            // WebP: "RIFF"...."WEBP"
            ascii(0, "RIFF") && ascii(8, "WEBP") -> true
            // AVIF / HEIF: "ftyp" box with an image brand
            ascii(4, "ftyp") && (
                ascii(8, "avif") || ascii(8, "avis") ||
                    ascii(8, "heic") || ascii(8, "heix") ||
                    ascii(8, "mif1") || ascii(8, "msf1")
                ) -> true
            // JXL codestream: FF 0A; container: 00 00 00 0C "JXL "
            (u(0) == 0xFF && u(1) == 0x0A) || ascii(4, "JXL ") -> true
            else -> false
        }
    }

    fun isImageFile(file: File): Boolean = runCatching {
        file.inputStream().use { stream ->
            val head = ByteArray(32)
            val read = stream.read(head)
            read > 0 && isImageContent(head.copyOf(read))
        }
    }.getOrDefault(false)
}

/**
 * Shared image-book assembly for CBZ/CBR (PLAN §4): pages are copied
 * into the staged assets dir in natural order and become ONE chapter
 * of image blocks (a comic is read as a single flow of pages; the
 * first page doubles as the cover). [ComicInfo] XML, when present,
 * supplies series/title/author.
 */
internal object ImageBookBuilder {

    data class Page(val archiveName: String, val stagedName: String)

    fun build(
        workDir: File,
        format: BookFormat,
        comicInfoXml: String?,
        extractPage: (archiveName: String, dest: File) -> Boolean,
        pageNames: List<String>
    ): AppResult<ParsedBook> {
        // FIX-18 (donor DirectoryPageLoader semantics): pages are
        // the archive's entries in NATURAL order (page2 before
        // page10 — NaturalSort), detected as images by extension
        // OR by content (ImageUtil.isImage's rule: an image with
        // no/foreign extension still counts; a text file named
        // .jpg does not). Content detection needs the bytes, so
        // non-extension entries are extracted and sniffed, and
        // dropped when the sniff fails.
        val candidates = pageNames
            .filterNot { it.startsWith("__MACOSX") || it.substringAfterLast('/').startsWith('.') }
            .sortedWith(NaturalSort)
        val assetsDir = File(workDir, "assets").apply { mkdirs() }
        val used = mutableSetOf<String>()
        val blocks = mutableListOf<ChapterBlock>()
        var coverFile: File? = null
        var copied = 0
        var extractedAny = false
        for (name in candidates) {
            val stagedName = ZipPaths.assetFileName(name, used)
            val dest = File(assetsDir, stagedName)
            if (!extractPage(name, dest)) continue
            extractedAny = true
            val byExtension = IMAGE_EXTENSIONS.any { name.lowercase().endsWith(it) }
            if (!byExtension && !ImageSniff.isImageFile(dest)) {
                dest.delete()
                continue
            }
            blocks += ChapterBlock.Image(stagedName)
            if (coverFile == null) {
                coverFile = File(workDir, "cover.${stagedName.substringAfterLast('.', "jpg")}")
                dest.copyTo(coverFile, overwrite = true)
            }
            copied++
        }
        if (copied == 0 && extractedAny) {
            // Entries extracted fine but none was an image (by
            // extension or content) — the no-images verdict.
            return AppResult.Err(
                AppError.ParseFailed(format.name, "the archive contains no page images")
            )
        }
        if (copied == 0) {
            return AppResult.Err(AppError.ParseFailed(format.name, "the pages couldn't be extracted"))
        }
        val info = comicInfoXml?.let { ComicInfo.parse(it) }
        val writer = ChapterFileWriter(File(workDir, "chapters"))
        val title = info?.series ?: info?.title
        writer.add(title ?: "Comic", ChapterContent(blocks))
        return AppResult.Ok(
            ParsedBook(
                title = title,
                author = info?.writer,
                description = info?.summary,
                language = null,
                format = format,
                coverFile = coverFile,
                chapters = writer.metas,
                chaptersDir = File(workDir, "chapters"),
                assetsDir = assetsDir
            )
        )
    }

    private val IMAGE_EXTENSIONS = listOf(".jpg", ".jpeg", ".png", ".webp", ".gif", ".avif", ".bmp")
}

/** The de-facto ComicInfo.xml metadata schema used by comic archives. */
object ComicInfo {
    data class Info(val series: String?, val title: String?, val writer: String?, val summary: String?)

    fun parse(xml: String): Info? = runCatching {
        var series: String? = null
        var title: String? = null
        var writer: String? = null
        var summary: String? = null
        val factory = org.xmlpull.v1.XmlPullParserFactory.newInstance()
        val parser = factory.newPullParser()
        parser.setInput(xml.reader())
        var event = parser.eventType
        while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (event == org.xmlpull.v1.XmlPullParser.START_TAG) {
                when (parser.name) {
                    "Series" -> series = parser.nextText()?.trim()?.takeIf { it.isNotEmpty() }
                    "Title" -> title = parser.nextText()?.trim()?.takeIf { it.isNotEmpty() }
                    "Writer" -> writer = parser.nextText()?.trim()?.takeIf { it.isNotEmpty() }
                    "Summary" -> summary = parser.nextText()?.trim()?.takeIf { it.isNotEmpty() }
                }
            }
            event = parser.next()
        }
        Info(series, title, writer, summary)
    }.getOrNull()
}

/** CBZ: a zip of page images (PLAN §4). */
class CbzParser : BookParser {

    override val format: BookFormat = BookFormat.CBZ

    override fun parse(
        staged: File,
        workDir: File,
        onProgress: (Float?) -> Unit
    ): AppResult<ParsedBook> = parse(
        staged, workDir, onProgress,
        maxEntries = ArchiveLimits.MAX_ENTRIES,
        maxMetadataBytes = ArchiveLimits.MAX_METADATA_ENTRY_BYTES,
        maxEntryBytes = ArchiveLimits.MAX_STREAMED_ENTRY_BYTES,
        maxTotalBytes = ArchiveLimits.MAX_TOTAL_EXPANDED_BYTES
    )

    /** [ArchiveLimits] apply; the limit parameters exist only so
     * tests can trip the caps with small fixtures. Pages are
     * counted against one shared budget as they are copied out,
     * so an oversized archive aborts mid-extraction with a
     * plain-words error instead of filling the staging dir. */
    internal fun parse(
        staged: File,
        workDir: File,
        onProgress: (Float?) -> Unit,
        maxEntries: Int,
        maxMetadataBytes: Long,
        maxEntryBytes: Long,
        maxTotalBytes: Long
    ): AppResult<ParsedBook> {
        return try {
            ZipFile(staged).use { zip ->
                val names = zip.entries().asSequence().map { it.name.replace('\\', '/') }.toList()
                if (names.size > maxEntries) {
                    return AppResult.Err(
                        AppError.ParseFailed(
                            format.name, "this comic has too many files inside"
                        )
                    )
                }
                val comicInfo = names.firstOrNull { it.equals("ComicInfo.xml", true) }
                    ?.let { zip.getEntry(it) }
                    ?.let { entry ->
                        zip.getInputStream(entry)
                            .readBytesCapped(maxMetadataBytes, "this comic's info file")
                            .toString(Charsets.UTF_8)
                    }
                val budget = ArchiveBudget(maxTotalBytes)
                ImageBookBuilder.build(
                    workDir = workDir,
                    format = BookFormat.CBZ,
                    comicInfoXml = comicInfo,
                    extractPage = { name, dest ->
                        val entry = zip.getEntry(name) ?: return@build false
                        try {
                            zip.getInputStream(entry).use { input ->
                                dest.outputStream().buffered().use { out ->
                                    input.copyToCapped(
                                        out, budget, maxEntryBytes, "this comic"
                                    )
                                }
                            }
                            true
                        } catch (e: ArchiveLimitException) {
                            // No half-written page stays behind.
                            dest.delete()
                            throw e
                        }
                    },
                    pageNames = names
                )
            }
        } catch (e: ArchiveLimitException) {
            AppResult.Err(
                AppError.ParseFailed("CBZ", "this comic is too large to open safely")
            )
        } catch (e: Exception) {
            AppResult.Err(AppError.ParseFailed("CBZ", "the archive couldn't be opened", e.javaClass.simpleName))
        }
    }
}

/**
 * CBR: RAR archives, extracted with junrar (Amendment 3 — extraction
 * only; RAR *creation* is never attempted). Pages junrar cannot decode
 * (some RAR5 compression variants) are skipped and counted; a book
 * whose pages all fail says so in plain words.
 */
class CbrParser : BookParser {

    override val format: BookFormat = BookFormat.CBR

    override fun parse(
        staged: File,
        workDir: File,
        onProgress: (Float?) -> Unit
    ): AppResult<ParsedBook> {
        return try {
            val archive = com.github.junrar.Archive(staged)
            try {
                val headers = archive.fileHeaders.filter { !it.isDirectory }
                val byName = headers.associateBy { it.fileName.replace('\\', '/') }
                val comicInfo = byName.entries
                    .firstOrNull { it.key.equals("ComicInfo.xml", true) }
                    ?.let { (_, header) ->
                        val out = java.io.ByteArrayOutputStream()
                        archive.extractFile(header, out)
                        out.toString(Charsets.UTF_8.name())
                    }
                ImageBookBuilder.build(
                    workDir = workDir,
                    format = BookFormat.CBR,
                    comicInfoXml = comicInfo,
                    extractPage = { name, dest ->
                        val header = byName[name] ?: return@build false
                        runCatching {
                            dest.outputStream().buffered().use { archive.extractFile(header, it) }
                            dest.length() > 0
                        }.getOrDefault(false)
                    },
                    pageNames = byName.keys.toList()
                )
            } finally {
                archive.close()
            }
        } catch (e: Exception) {
            AppResult.Err(
                AppError.ParseFailed(
                    "CBR",
                    "this RAR comic couldn't be read — converting it to CBZ will work",
                    e.javaClass.simpleName
                )
            )
        }
    }
}
