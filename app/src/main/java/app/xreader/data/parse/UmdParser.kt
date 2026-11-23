package app.xreader.data.parse

import app.xreader.core.error.AppError
import app.xreader.core.error.AppResult
import app.xreader.data.db.BookFormat
import app.xreader.domain.content.ChapterBlock
import app.xreader.domain.content.ChapterContent
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * UMD (OE-5, from build-logs/research/RESEARCH-UMD.md): the old
 * Chinese mobile-novel format — a flat sequence of `#` function
 * blocks (metadata, pointers) and `$` data blocks (bulk payloads
 * addressed by arbitrary 32-bit IDs). Text chapters exist only as
 * tables: 0x83 holds BYTE offsets into the concatenated
 * decompressed text, 0x84 the chapter titles; the text itself is
 * one logical UTF-16LE string chopped into independently
 * zlib-compressed `$` blocks with no regard to chapter boundaries.
 *
 * Two research rules are load-bearing and easy to get wrong:
 *  - the 0x81 index is a MEMBERSHIP SET only — its IDs are
 *    written in reverse physical order by producers, and both
 *    real readers assemble text in PHYSICAL file order;
 *  - inflated BYTES are concatenated first and decoded once per
 *    chapter AFTER byte-slicing — a block boundary can split a
 *    UTF-16 code unit or a surrogate pair.
 * Comic UMD (header type 2) is refused honestly: no open reader
 * implements it, and guessing its layout would produce garbage.
 */
class UmdParser : BookParser {

    override val format: BookFormat = BookFormat.UMD

    override fun parse(
        staged: File,
        workDir: File,
        onProgress: (Float?) -> Unit
    ): AppResult<ParsedBook> {
        return try {
            parseBook(staged.readBytes(), workDir)
        } catch (e: UmdFailure) {
            AppResult.Err(AppError.ParseFailed("UMD", e.message ?: "the file couldn't be read"))
        } catch (e: Exception) {
            AppResult.Err(AppError.ParseFailed("UMD", "the file couldn't be read", e.javaClass.simpleName))
        }
    }

    private class UmdFailure(message: String) : Exception(message)

    private fun fail(reason: String): Nothing = throw UmdFailure(reason)

    private fun parseBook(bytes: ByteArray, workDir: File): AppResult<ParsedBook> {
        if (bytes.size < 4 ||
            bytes[0] != 0x89.toByte() || bytes[1] != 0x9B.toByte() ||
            bytes[2] != 0x9A.toByte() || bytes[3] != 0xDE.toByte()
        ) {
            fail("the file isn't a readable UMD book")
        }
        // Book type: content byte 0 of the 0x01 header block,
        // which sits at offset 9 in the standard framing. Read
        // tolerantly (RESEARCH-UMD §1.1): degenerate producers
        // zero the header block's framing bytes, so the generic
        // walk below starts after the header in that shape.
        var bookType: Int? = if (bytes.size > 9) bytes[9].toInt() and 0xFF else null

        var title: String? = null
        var author: String? = null
        var textIndexId: Long? = null
        var offsetsId: Long? = null
        var titlesId: Long? = null
        var coverId: Long? = null
        // Data blocks by ID, in PHYSICAL file order (insertion
        // order) — the text assembly order.
        val dataBlocks = LinkedHashMap<Long, ByteArray>()

        var offset = when {
            bytes.size > 4 && (bytes[4] == '#'.code.toByte() || bytes[4] == '$'.code.toByte()) -> 4
            bytes.size >= 12 -> 12 // degenerate zeroed header framing ([S2] shape)
            else -> fail("the file isn't a readable UMD book")
        }
        while (offset < bytes.size) {
            when (bytes[offset]) {
                '#'.code.toByte() -> {
                    if (offset + 5 > bytes.size) fail("the file is cut short")
                    val id = bytes[offset + 1].toInt() and 0xFF
                    val length = bytes[offset + 4].toInt() and 0xFF
                    if (length < 5 || offset + length > bytes.size) {
                        fail("the file's block framing is damaged")
                    }
                    val content = bytes.copyOfRange(offset + 5, offset + length)
                    when (id) {
                        0x01 -> if (content.isNotEmpty()) {
                            bookType = content[0].toInt() and 0xFF
                        }
                        0x02 -> title = decodeUtf16Le(content)
                            ?.trim()?.takeIf { it.isNotEmpty() } ?: title
                        0x03 -> author = decodeUtf16Le(content)
                            ?.trim()?.takeIf { it.isNotEmpty() } ?: author
                        // 0x04–0x09 (year/month/day/genre/publisher/
                        // retailer) are walked and dropped: the
                        // book model has no slot for them.
                        0x81 -> if (content.size >= 4) textIndexId = u32le(content, 0)
                        0x82 -> if (content.size >= 5) {
                            // NOT the bare pointer pattern: content
                            // byte 0 is the format flag (1 = JPEG),
                            // the data-block ID is bytes 1..4.
                            coverId = u32le(content, 1)
                        }
                        0x83 -> if (content.size >= 4) offsetsId = u32le(content, 0)
                        0x84 -> if (content.size >= 4) titlesId = u32le(content, 0)
                        // 0x0A / 0x0B / 0x0C / 0x87 / 0xF0 / 0xF1:
                        // fillers, hints and keys — no reader use.
                        else -> Unit
                    }
                    offset += length
                }
                '$'.code.toByte() -> {
                    if (offset + 9 > bytes.size) fail("the file is cut short")
                    val id = u32le(bytes, offset + 1)
                    val length = u32le(bytes, offset + 5)
                    if (length < 9 || offset + length > bytes.size) {
                        fail("the file's block framing is damaged")
                    }
                    dataBlocks[id] = bytes.copyOfRange(offset + 9, (offset + length).toInt())
                    offset += length.toInt()
                }
                else -> fail("the file's block framing is damaged")
            }
        }

        if (bookType == 2) {
            fail("comic UMD books aren't supported")
        }

        // Text: the 0x81 payload names the member blocks; assembly
        // is in physical file order, bytes first, decode later.
        val memberIds = textIndexId?.let { dataBlocks[it] }?.let { payload ->
            (0 until payload.size / 4).map { u32le(payload, it * 4) }.toSet()
        } ?: emptySet()
        val textBytes = ByteArrayOutputStream()
        var textBlockCount = 0
        for ((id, payload) in dataBlocks) {
            if (id in memberIds) {
                textBytes.write(inflate(payload))
                textBlockCount++
            }
        }
        if (textBlockCount == 0) {
            fail("the file has no readable text")
        }
        val text = textBytes.toByteArray()

        // Chapter offsets: BYTE offsets into the UTF-16LE stream.
        val offsets = offsetsId?.let { dataBlocks[it] }?.let { payload ->
            (0 until payload.size / 4).map { u32le(payload, it * 4) }
        } ?: emptyList()
        val titles = titlesId?.let { dataBlocks[it] }?.let { parseTitles(it) } ?: emptyList()

        val writer = ChapterFileWriter(File(workDir, "chapters"))
        if (offsets.isEmpty()) {
            writer.add(titles.firstOrNull() ?: "", chapterContent(text, 0, text.size))
        } else {
            for (i in offsets.indices) {
                val start = offsets[i].toInt().coerceIn(0, text.size)
                val end = (if (i + 1 < offsets.size) offsets[i + 1].toInt() else text.size)
                    .coerceIn(start, text.size)
                writer.add(titles.getOrNull(i) ?: "", chapterContent(text, start, end))
            }
        }
        if (writer.metas.isEmpty()) {
            fail("the file has no readable text")
        }

        // Cover: the 0x82-pointed block's raw bytes, trusted by
        // their magic, not by the flag byte (producer variance).
        val assetsDir = File(workDir, "assets")
        var coverFile: File? = null
        val coverPayload = coverId?.let { dataBlocks[it] }
        if (coverPayload != null) {
            val ext = imageExtension(coverPayload)
            if (ext != null) {
                assetsDir.mkdirs()
                val file = File(assetsDir, "cover.$ext")
                file.writeBytes(coverPayload)
                coverFile = file
            }
        }

        return AppResult.Ok(
            ParsedBook(
                title = title,
                author = author,
                description = null,
                language = null,
                format = BookFormat.UMD,
                coverFile = coverFile,
                chapters = writer.metas,
                chaptersDir = File(workDir, "chapters"),
                assetsDir = assetsDir.takeIf { it.list()?.isNotEmpty() == true }
            )
        )
    }

    /** One chapter's blocks: slice BYTES, decode once, split on
     * U+2029, and pass each segment through the TXT parser's
     * blank-line paragraph rule (Revision 1). */
    private fun chapterContent(text: ByteArray, start: Int, end: Int): ChapterContent {
        val decoded = String(text, start, end - start, Charsets.UTF_16LE)
        val blocks = mutableListOf<ChapterBlock>()
        for (segment in decoded.split('\u2029')) {
            blocks += TxtParser.textToContent(segment).blocks
        }
        return ChapterContent(blocks)
    }

    /** The 0x84 blob: [u8 byte-length + UTF-16LE title] × N. A
     * blob that runs out mid-title yields the titles read so
     * far; the caller names the missing ones "Chapter N". */
    private fun parseTitles(payload: ByteArray): List<String> {
        val titles = mutableListOf<String>()
        var pos = 0
        while (pos < payload.size) {
            val byteLen = payload[pos].toInt() and 0xFF
            pos++
            if (pos + byteLen > payload.size) break
            titles += String(payload, pos, byteLen, Charsets.UTF_16LE).trim()
            pos += byteLen
        }
        return titles
    }

    /** One independent zlib (RFC 1950) stream per text block. */
    private fun inflate(payload: ByteArray): ByteArray {
        val inflater = Inflater(false)
        return try {
            inflater.setInput(payload)
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                if (count == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) break
                } else {
                    out.write(buffer, 0, count)
                }
            }
            out.toByteArray()
        } catch (e: DataFormatException) {
            fail("the file's text couldn't be decompressed")
        } finally {
            inflater.end()
        }
    }

    private fun imageExtension(payload: ByteArray): String? = when {
        payload.size >= 3 &&
            payload[0] == 0xFF.toByte() && payload[1] == 0xD8.toByte() &&
            payload[2] == 0xFF.toByte() -> "jpg"
        payload.size >= 8 &&
            payload[0] == 0x89.toByte() && payload[1] == 0x50.toByte() &&
            payload[2] == 0x4E.toByte() && payload[3] == 0x47.toByte() -> "png"
        payload.size >= 6 &&
            payload[0] == 'G'.code.toByte() && payload[1] == 'I'.code.toByte() &&
            payload[2] == 'F'.code.toByte() && payload[3] == '8'.code.toByte() -> "gif"
        payload.size >= 12 &&
            payload[0] == 'R'.code.toByte() && payload[1] == 'I'.code.toByte() &&
            payload[2] == 'F'.code.toByte() && payload[3] == 'F'.code.toByte() &&
            payload[8] == 'W'.code.toByte() && payload[9] == 'E'.code.toByte() &&
            payload[10] == 'B'.code.toByte() && payload[11] == 'P'.code.toByte() -> "webp"
        else -> null
    }

    private fun decodeUtf16Le(bytes: ByteArray): String? =
        if (bytes.isEmpty()) null else String(bytes, Charsets.UTF_16LE)

    private fun u32le(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xFF) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFF) shl 24)
}
