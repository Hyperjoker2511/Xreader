package app.xreader.data.parse

import app.xreader.data.db.BookFormat
import java.io.File
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

    /** EPUB (has META-INF/container.xml) vs CBZ (images, no container). */
    private fun zipProbe(file: File): BookFormat? = runCatching {
        ZipFile(file).use { zip ->
            val names = zip.entries().asSequence().map { it.name.replace('\\', '/') }.toList()
            when {
                names.any { it.equals("META-INF/container.xml", ignoreCase = true) } ->
                    BookFormat.EPUB
                names.any { it.equals("mimetype", ignoreCase = true) &&
                    zip.getInputStream(zip.getEntry(it)).use { stream ->
                        val head = ByteArray(64)
                        val read = stream.read(head)
                        (if (read > 0) head.copyOf(read) else ByteArray(0))
                            .toString(Charsets.US_ASCII)
                    }.startsWith("application/epub") } ->
                    BookFormat.EPUB
                names.count { name ->
                    IMAGE_EXTENSIONS.any { name.lowercase().endsWith(it) }
                } >= 1 && names.none { it.endsWith(".opf", ignoreCase = true) } ->
                    BookFormat.CBZ
                else -> null
            }
        }
    }.getOrNull()

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
