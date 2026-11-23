package app.xreader.data.parse

import app.xreader.data.db.BookFormat
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Format detection by CONTENT, not extension (PLAN §4): a `.bin` that
 * is really an EPUB imports as an EPUB. Only the first bytes (and, for
 * zip containers, the central directory) are inspected — the file is
 * never loaded whole.
 */
object FormatSniffer {

    private const val HEAD_BYTES = 8192

    fun sniff(file: File, displayName: String? = null): BookFormat {
        val head = ByteArray(HEAD_BYTES)
        val read = runCatching {
            file.inputStream().buffered().use { it.read(head) }
        }.getOrDefault(-1)
        if (read <= 0) return BookFormat.UNKNOWN
        return sniffBytes(head.copyOf(read), displayName ?: file.name) { zipProbe(file) }
    }

    /** Byte-level probe, split out so tests don't need files for the
     * non-zip formats. [zipProbe] runs only for zip-magic input. */
    fun sniffBytes(head: ByteArray, displayName: String?, zipProbe: () -> BookFormat?): BookFormat {
        // PDF: %PDF-
        if (startsWith(head, "%PDF-")) return BookFormat.PDF
        // RAR: Rar!
        if (startsWith(head, "Rar!")) return BookFormat.CBR
        // RTF (OE-3): every RTF file opens with the {\rtf header.
        if (startsWith(head, "{\\rtf")) return BookFormat.RTF
        // CHM (OE-5): every CHM opens with the ITSF signature.
        if (startsWith(head, "ITSF")) return BookFormat.CHM
        // UMD (OE-5): the magic is BINARY (89 9B 9A DE) and is
        // compared as raw bytes — the startsWith helper encodes its
        // argument as US-ASCII, which cannot express these bytes
        // and would make the arm a dead door.
        if (head.size >= 4 &&
            head[0] == 0x89.toByte() && head[1] == 0x9B.toByte() &&
            head[2] == 0x9A.toByte() && head[3] == 0xDE.toByte()
        ) {
            return BookFormat.UMD
        }
        // MOBI/AZW3: "BOOKMOBI" at offset 60 of the PalmDB header.
        if (head.size > 68 && String(head, 60, 8, Charsets.US_ASCII) == "BOOKMOBI") {
            return if (displayName?.endsWith(".azw3", ignoreCase = true) == true) {
                BookFormat.AZW3
            } else {
                BookFormat.MOBI
            }
        }
        // ZIP family: EPUB vs CBZ (vs unknown zip) needs the entry list.
        if (head.size >= 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()) {
            return zipProbe() ?: BookFormat.UNKNOWN
        }
        // Text family: XML/HTML dialects, else plain text.
        val text = decodeHead(head)
        // MHTML (OE-3): a web archive opens with MIME headers, the
        // HTML part riding later — headers decide, not extension.
        if (isMhtmlHead(text)) return BookFormat.MHTML
        val trimmed = text.trimStart('﻿', ' ', '\t', '\r', '\n')
        if (trimmed.startsWith("<?xml") || trimmed.startsWith("<FictionBook")) {
            return if (text.contains("<FictionBook")) BookFormat.FB2 else BookFormat.TXT
        }
        if (trimmed.startsWith("<!DOCTYPE html", ignoreCase = true) ||
            trimmed.startsWith("<html", ignoreCase = true)
        ) {
            return BookFormat.HTML
        }
        if (isMostlyText(head)) {
            val ext = displayName?.substringAfterLast('.', "")?.lowercase()
            return if (ext == "md" || ext == "markdown" || looksLikeMarkdown(text)) {
                BookFormat.MARKDOWN
            } else {
                BookFormat.TXT
            }
        }
        return BookFormat.UNKNOWN
    }

    /** The MHTML header shape: a multipart/related content type,
     * or a MIME-Version line together with a part boundary. */
    private fun isMhtmlHead(text: String): Boolean {
        val head = text.take(4096)
        return head.contains("Content-Type: multipart/related", ignoreCase = true) ||
            (head.contains("MIME-Version:", ignoreCase = true) &&
                head.contains("boundary=", ignoreCase = true))
    }

    /** ZIP family by marker entries: EPUB (container.xml) vs DOCX
     * (word/document.xml) vs ODT (content.xml + ODF manifest, or
     * the stored ODF text mimetype) vs CBZ (images, no container).
     * The office arms sit BEFORE the CBZ arm on purpose: a DOCX or
     * ODT carrying pictures would otherwise read as a comic.
     *
     * OE-5 (part D): names are [ZipNames]-normalised (leading `/`
     * and backslash packings used to sniff UNKNOWN), and the
     * EPUB/DOCX/ODT arms are evaluated against the raw names and
     * then against the [ZipNames.unwrapped] names (one shared
     * wrapper folder stripped); the CBZ arm is tried only after
     * the office arms have had BOTH name sets — a wrapped DOCX
     * carrying a picture must not read as a comic (Revision 1).
     * Mimetype bytes are read through the PAIRED entry, never
     * re-looked-up by name (a backslashed stored name would miss
     * the re-lookup and fail the whole probe). */
    private fun zipProbe(file: File): BookFormat? = runCatching {
        ZipFile(file).use { zip ->
            val pairs = zip.entries().asSequence()
                .map { ZipNames.normalise(it.name) to it }
                .toList()
            val stripped = ZipNames.unwrapped(pairs.map { it.first })
            val unwrappedPairs = if (stripped == pairs.map { it.first }) {
                pairs
            } else {
                pairs.mapIndexed { index, pair -> stripped[index] to pair.second }
            }
            officeVerdict(zip, pairs) ?: officeVerdict(zip, unwrappedPairs)
                ?: cbzVerdict(pairs.map { it.first })
                ?: cbzVerdict(unwrappedPairs.map { it.first })
        }
    }.getOrNull()

    /** The EPUB / DOCX / ODT arms over one (name, entry) set. */
    private fun officeVerdict(zip: ZipFile, pairs: List<Pair<String, ZipEntry>>): BookFormat? {
        val names = pairs.map { it.first }
        fun mimetypeStartsWith(prefix: String): Boolean {
            val entry = pairs.firstOrNull {
                it.first.equals("mimetype", ignoreCase = true)
            }?.second ?: return false
            val text = runCatching {
                zip.getInputStream(entry).use { stream ->
                    val head = ByteArray(64)
                    val read = stream.read(head)
                    (if (read > 0) head.copyOf(read) else ByteArray(0))
                        .toString(Charsets.US_ASCII)
                }
            }.getOrNull() ?: return false
            return text.startsWith(prefix)
        }
        return when {
            names.any { it.equals("META-INF/container.xml", ignoreCase = true) } ->
                BookFormat.EPUB
            mimetypeStartsWith("application/epub") ->
                BookFormat.EPUB
            names.any { it.equals("word/document.xml", ignoreCase = true) } ->
                BookFormat.DOCX
            names.any { it.equals("content.xml", ignoreCase = true) } &&
                names.any { it.equals("META-INF/manifest.xml", ignoreCase = true) } ->
                BookFormat.ODT
            mimetypeStartsWith("application/vnd.oasis.opendocument.text") ->
                BookFormat.ODT
            else -> null
        }
    }

    /** The CBZ arm over one name set: at least one image entry
     * and no package descriptor. */
    private fun cbzVerdict(names: List<String>): BookFormat? =
        if (names.count { name ->
                IMAGE_EXTENSIONS.any { name.lowercase().endsWith(it) }
            } >= 1 && names.none { it.endsWith(".opf", ignoreCase = true) }
        ) {
            BookFormat.CBZ
        } else {
            null
        }

    private fun decodeHead(head: ByteArray): String {
        // BOM-aware enough for sniffing; full decode happens in parsers.
        return when {
            head.size >= 2 && head[0] == 0xFF.toByte() && head[1] == 0xFE.toByte() ->
                String(head, Charsets.UTF_16LE)
            head.size >= 2 && head[0] == 0xFE.toByte() && head[1] == 0xFF.toByte() ->
                String(head, Charsets.UTF_16BE)
            else -> String(head, Charsets.UTF_8)
        }
    }

    private fun isMostlyText(head: ByteArray): Boolean {
        if (head.isEmpty()) return false
        var control = 0
        for (b in head) {
            val v = b.toInt() and 0xFF
            if (v == 0) return false
            if (v < 0x09 || (v in 0x0E..0x1F && v != 0x1B)) control++
        }
        return control.toDouble() / head.size < 0.02
    }

    private fun looksLikeMarkdown(text: String): Boolean =
        text.lineSequence().take(40).any { line ->
            line.startsWith("# ") || line.startsWith("## ") || line.startsWith("```")
        }

    private fun startsWith(head: ByteArray, magic: String): Boolean {
        val bytes = magic.toByteArray(Charsets.US_ASCII)
        if (head.size < bytes.size) return false
        return bytes.indices.all { head[it] == bytes[it] }
    }

    private val IMAGE_EXTENSIONS = listOf(".jpg", ".jpeg", ".png", ".webp", ".gif", ".avif", ".bmp")
}

/**
 * Zip entry-name handling shared by the sniffer and the office
 * parser (OE-5 part D). Real-world packings store the same
 * document with backslash separators, a leading `/` on every
 * name, or every entry wrapped in one shared top folder (a
 * zipped template folder — the owner's code-112 DOCX failure);
 * all three used to be invisible to exact-name lookups.
 */
internal object ZipNames {

    /** Backslashes → `/`, leading `/` stripped, leading `./`
     * segments stripped (repeatedly, so `/./x` and `././x`
     * normalise too). */
    fun normalise(name: String): String {
        var result = name.replace('\\', '/')
        while (true) {
            result = when {
                result.startsWith("./") -> result.substring(2)
                result.startsWith("/") -> result.substring(1)
                else -> return result
            }
        }
    }

    /**
     * If ALL non-directory normalised names share one first path
     * segment AND at least one name has ≥2 segments, return the
     * names with that segment stripped; otherwise return the
     * names unchanged. (A normal archive's names do not share
     * one root segment, so it is untouched; directory entries
     * keep their form and never drive the decision.)
     */
    fun unwrapped(names: List<String>): List<String> {
        val files = names.filter { it.isNotEmpty() && !it.endsWith("/") }
        if (files.isEmpty() || files.none { '/' in it }) return names
        val root = files.first().substringBefore('/')
        if (files.any { it.substringBefore('/') != root }) return names
        return names.map { name ->
            if (name.startsWith("$root/")) name.substring(root.length + 1) else name
        }
    }
}
