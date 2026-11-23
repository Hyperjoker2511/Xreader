package app.xreader.data.parse

import app.xreader.core.error.AppError
import app.xreader.core.error.AppResult
import app.xreader.data.db.BookFormat
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.Charset
import java.util.Base64

/**
 * MHTML (OE-3, Moon+ copy): a web archive is a MIME multipart
 * file. The multipart is split on its boundary, the first
 * text/html part is decoded (quoted-printable / base64 per its
 * Content-Transfer-Encoding), written to a staged temp file,
 * and delegated to [HtmlParser] — exactly the [MobiParser]
 * shape. Related parts are staged as sibling files under their
 * Content-Location base names, so the HTML pipeline's own
 * relative-image resolution finds the pictures the page
 * references by file name — no new asset machinery. Pictures
 * the page references in a form that resolver cannot reach
 * (an absolute URL, a cid:) are counted on
 * [ParsedBook.droppedImages], the honesty path every parser
 * uses — never silently lost.
 */
class MhtmlParser : BookParser {

    override val format: BookFormat = BookFormat.MHTML

    override fun parse(
        staged: File,
        workDir: File,
        onProgress: (Float?) -> Unit
    ): AppResult<ParsedBook> {
        return try {
            val parts = MimeParts.split(staged.readBytes())
                ?: return AppResult.Err(
                    AppError.ParseFailed("MHTML", "the file isn't a readable web archive")
                )
            val htmlPart = parts.firstOrNull { it.contentType == "text/html" }
                ?: return AppResult.Err(
                    AppError.ParseFailed("MHTML", "the archive has no web page in it")
                )
            val htmlBytes = htmlPart.decodedBody()
            val htmlText = String(htmlBytes, htmlPart.charset())

            var droppedImages = 0
            for (part in parts) {
                if (part === htmlPart) continue
                val location = part.contentLocation?.trim()?.trim('"')
                val baseName = location
                    ?.substringBefore('?')?.substringBefore('#')
                    ?.substringAfterLast('/')
                if (!baseName.isNullOrEmpty() &&
                    baseName.all { it.isLetterOrDigit() || it in "._-" }
                ) {
                    runCatching { File(workDir, baseName).writeBytes(part.decodedBody()) }
                }
                // A reference in a form the HTML resolver refuses
                // by design (absolute http src, cid:) is a dropped
                // picture — count it, never hide it.
                val id = part.contentId
                val referencedByCid = id != null && htmlText.contains("cid:$id")
                val referencedByUrl = location != null &&
                    location.startsWith("http", ignoreCase = true) &&
                    htmlText.contains(location)
                if (referencedByCid || referencedByUrl) droppedImages++
            }

            val tempHtml = File(workDir, "mhtml-content.html")
            tempHtml.writeBytes(htmlBytes)
            val fromHtml = HtmlParser().parse(tempHtml, File(workDir, "html-stage")) { }
            when (fromHtml) {
                is AppResult.Err -> fromHtml
                is AppResult.Ok -> AppResult.Ok(
                    fromHtml.value.copy(
                        format = BookFormat.MHTML,
                        droppedImages = fromHtml.value.droppedImages + droppedImages
                    )
                )
            }
        } catch (e: Exception) {
            AppResult.Err(AppError.ParseFailed("MHTML", "the file couldn't be read", e.javaClass.simpleName))
        }
    }
}

/** One MIME part: its headers (lower-cased names) and raw body. */
private class MimePart(
    val headers: Map<String, String>,
    private val body: ByteArray
) {
    val contentType: String =
        headers["content-type"]?.substringBefore(';')?.trim()?.lowercase() ?: ""

    val contentLocation: String? = headers["content-location"]

    val contentId: String? = headers["content-id"]?.trim()?.trim('<', '>')

    fun charset(): Charset {
        val declared = headers["content-type"]
            ?.split(';')?.map { it.trim() }
            ?.firstOrNull { it.startsWith("charset=", ignoreCase = true) }
            ?.substringAfter('=')?.trim()?.trim('"')
        return declared?.let { runCatching { Charset.forName(it) }.getOrNull() }
            ?: Charsets.UTF_8
    }

    fun decodedBody(): ByteArray =
        when (headers["content-transfer-encoding"]?.trim()?.lowercase()) {
            "base64" -> runCatching {
                Base64.getMimeDecoder().decode(body)
            }.getOrDefault(body)
            "quoted-printable" -> decodeQuotedPrintable(body)
            else -> body
        }
}

/** MIME multipart splitting over a latin-1 view of the bytes, so
 * string offsets map 1:1 onto byte offsets. Nested multiparts
 * (an alternative block inside the related block) are collected
 * one level down, which covers the archives browsers write. */
private object MimeParts {

    fun split(bytes: ByteArray): List<MimePart>? {
        val text = bytes.toString(Charsets.ISO_8859_1)
        val rootHeaders = parseHeaders(headBlock(text)) ?: return null
        val boundary = boundaryOf(rootHeaders["content-type"]) ?: return null
        val parts = mutableListOf<MimePart>()
        collect(text, boundary, parts)
        return parts
    }

    private fun collect(text: String, boundary: String, out: MutableList<MimePart>) {
        val segments = text.split("--$boundary")
        // segments[0] is the preamble (root headers + anything
        // before the first boundary); each later segment is one
        // part, and a segment opening with "--" is the epilogue.
        for (segment in segments.drop(1)) {
            var part = segment
            if (part.startsWith("\r\n")) part = part.substring(2)
            else if (part.startsWith("\n")) part = part.substring(1)
            if (part.startsWith("--")) break
            // The line break before the next boundary belongs to
            // the boundary, not the body.
            if (part.endsWith("\r\n")) part = part.dropLast(2)
            else if (part.endsWith("\n") || part.endsWith("\r")) part = part.dropLast(1)
            val headers = parseHeaders(headBlock(part)) ?: continue
            val bodyStart = bodyStart(part)
            if (bodyStart < 0) continue
            val body = part.substring(bodyStart).toByteArray(Charsets.ISO_8859_1)
            val partBoundary = boundaryOf(headers["content-type"])
            if (headers["content-type"]?.contains("multipart/", ignoreCase = true) == true &&
                partBoundary != null
            ) {
                collect(String(body, Charsets.ISO_8859_1), partBoundary, out)
            } else {
                out += MimePart(headers, body)
            }
        }
    }

    /** The header block of a message or part: everything up to
     * the first blank line, or null when there is none. */
    private fun headBlock(text: String): String? {
        val crlf = text.indexOf("\r\n\r\n")
        val lf = text.indexOf("\n\n")
        val end = when {
            crlf >= 0 && (lf < 0 || crlf < lf) -> crlf
            lf >= 0 -> lf
            else -> return null
        }
        return text.substring(0, end)
    }

    /** Index just past the blank line ending the header block. */
    private fun bodyStart(part: String): Int {
        val crlf = part.indexOf("\r\n\r\n")
        val lf = part.indexOf("\n\n")
        return when {
            crlf >= 0 && (lf < 0 || crlf < lf) -> crlf + 4
            lf >= 0 -> lf + 2
            else -> -1
        }
    }

    /** Header names lower-cased; folded continuation lines are
     * joined onto the previous header, per RFC 2045. */
    private fun parseHeaders(block: String?): Map<String, String>? {
        if (block == null) return null
        val headers = mutableMapOf<String, String>()
        var lastKey: String? = null
        for (line in block.split("\r\n", "\n")) {
            if (line.startsWith(" ") || line.startsWith("\t")) {
                val key = lastKey
                if (key != null) headers[key] = headers[key] + " " + line.trim()
                continue
            }
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val key = line.substring(0, colon).trim().lowercase()
            headers[key] = line.substring(colon + 1).trim()
            lastKey = key
        }
        return headers
    }

    private fun boundaryOf(contentType: String?): String? {
        if (contentType == null) return null
        val param = contentType.split(';').map { it.trim() }
            .firstOrNull { it.startsWith("boundary=", ignoreCase = true) }
            ?: return null
        return param.substringAfter('=').trim().trim('"').ifEmpty { null }
    }
}

/** Quoted-printable: `=HH` is a byte, `=` at a line end is a
 * soft break, everything else is literal. */
private fun decodeQuotedPrintable(body: ByteArray): ByteArray {
    val text = body.toString(Charsets.ISO_8859_1)
    val out = ByteArrayOutputStream(body.size)
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (c == '=' && i + 1 < text.length) {
            val next = text[i + 1]
            if (next == '\r' || next == '\n') {
                // Soft break: swallow the '=' and the line ending.
                i += 2
                if (next == '\r' && i < text.length && text[i] == '\n') i++
                continue
            }
            if (i + 2 < text.length) {
                val byte = text.substring(i + 1, i + 3).toIntOrNull(16)
                if (byte != null) {
                    out.write(byte)
                    i += 3
                    continue
                }
            }
        }
        out.write(c.code and 0xFF)
        i++
    }
    return out.toByteArray()
}
