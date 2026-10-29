package app.xreader.data.parse

import app.xreader.core.error.AppError
import app.xreader.core.error.AppResult
import app.xreader.data.db.BookFormat
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.Charset

/**
 * MOBI / AZW3 (Kindle) best-effort parser (PLAN §4 + Amendment 3).
 *
 * What this does, honestly:
 *  - Reads the PalmDB record table and the MOBI header (name at
 *    offset 60 says BOOKMOBI — see [FormatSniffer]).
 *  - **Encryption type ≠ 0 → [AppError.DrmProtected]**: AZW3 from the
 *    Kindle Store is DRM-locked; the refusal says so in plain words
 *    and points at Calibre. DRM is never cracked or faked past.
 *  - Unencrypted books: text records are PalmDOC-decompressed
 *    ([PalmDoc]) and the resulting HTML flows through the same
 *    converter/splitter as local HTML files. KF8 (AZW3) books store
 *    two boundary sections; the KF8 HTML is used when present.
 *  - Huffman-coded text (rare, some old MOBI) is NOT decoded — it
 *    fails honestly with a message suggesting Calibre conversion.
 */
class MobiParser(private val formatWanted: BookFormat = BookFormat.MOBI) : BookParser {

    override val format: BookFormat = formatWanted

    override fun parse(
        staged: File,
        workDir: File,
        onProgress: (Float?) -> Unit
    ): AppResult<ParsedBook> {
        val label = if (formatWanted == BookFormat.AZW3) "AZW3" else "MOBI"
        return try {
            val bytes = staged.readBytes()
            val book = MobiFile.read(bytes)
                ?: return AppResult.Err(AppError.ParseFailed(label, "the file isn't a readable Kindle book"))
            if (book.encryptionType != 0) {
                return AppResult.Err(AppError.DrmProtected)
            }
            if (book.compression == HUFFMAN_COMPRESSION) {
                return AppResult.Err(
                    AppError.ParseFailed(
                        label,
                        "this book uses a compression Xreader can't open — converting it with Calibre will work"
                    )
                )
            }
            val textBytes = book.extractTextRecordBytes()
            if (textBytes.isEmpty()) {
                return AppResult.Err(AppError.ParseFailed(label, "the book has no readable text"))
            }
            val charset = if (book.encoding == 65001) Charsets.UTF_8 else Charset.forName("windows-1252")
            val html = textBytes.toString(charset)

            // Write the HTML to a temp file and reuse the HTML pipeline,
            // but relative <img src="kindle:embed:XXXX"> references can't
            // resolve to files — Kindle-embedded images are skipped in
            // v0.2 (noted in the phase report); text is the deliverable.
            val tempHtml = File(workDir, "kindle-content.html")
            tempHtml.writeText(html)
            val fromHtml = HtmlParser().parse(tempHtml, File(workDir, "html-stage")) { }
            when (fromHtml) {
                is AppResult.Err -> fromHtml
                is AppResult.Ok -> AppResult.Ok(
                    fromHtml.value.copy(
                        title = fromHtml.value.title ?: book.title,
                        author = fromHtml.value.author ?: book.author,
                        format = formatWanted
                    )
                )
            }
        } catch (e: Exception) {
            AppResult.Err(AppError.ParseFailed(label, "the book couldn't be read", e.javaClass.simpleName))
        }
    }

    companion object {
        const val HUFFMAN_COMPRESSION = 17480
    }
}

/** PalmDOC (LZ77-variant) decompression — pure, fixture-testable. */
object PalmDoc {

    /**
     * Decodes one PalmDOC-compressed record. Scheme: 0x00 literal;
     * 0x01–0x08 copy that many literals; 0x09–0x7F literal; 0x80–0xBF
     * two-byte back-reference (distance 11 bits, length 3 bits +3);
     * 0xC0–0xFF space + printable char.
     */
    fun decompress(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(data.size * 3)
        var i = 0
        while (i < data.size) {
            val b = data[i].toInt() and 0xFF
            i++
            when {
                b == 0 -> out.write(0)
                b in 1..8 -> {
                    val count = minOf(b, data.size - i)
                    if (count > 0) {
                        out.write(data, i, count)
                        i += count
                    }
                }
                b < 0x80 -> out.write(b)
                b < 0xC0 -> {
                    if (i >= data.size) break
                    val second = data[i].toInt() and 0xFF
                    i++
                    val pair = (b shl 8) or second
                    val distance = (pair shr 3) and 0x7FF
                    val length = (pair and 0x7) + 3
                    if (distance == 0) break
                    val snapshot = out.toByteArray()
                    var from = snapshot.size - distance
                    if (from < 0) break
                    repeat(length) {
                        out.write(snapshot[from % snapshot.size].toInt())
                        from++
                    }
                }
                else -> {
                    out.write(' '.code)
                    out.write(b xor 0x80)
                }
            }
        }
        return out.toByteArray()
    }
}

/**
 * Minimal MOBI container reader: PalmDB records + MOBI/EXTH headers.
 */
class MobiFile private constructor(
    val title: String?,
    val author: String?,
    val encryptionType: Int,
    val compression: Int,
    val encoding: Int,
    private val records: List<ByteArray>,
    private val textRecordCount: Int,
    private val extraFlags: Int
) {

    /** Concatenated, decompressed MOBI text records (stops at the KF8
     * boundary record when a combined MOBI/KF8 file marks one — the
     * KF8 part duplicates content with better markup, but its HTML
     * references images by index; the MOBI part is self-contained). */
    fun extractTextRecordBytes(): ByteArray {
        val out = ByteArrayOutputStream()
        val last = minOf(textRecordCount, records.size - 1)
        for (index in 1..last) {
            var record = records[index]
            if (extraFlags != 0 && record.size >= 2) {
                // Trailing entries (multibyte chars etc.) — sizes are in
                // the last bytes; v0.2 trims the common single-entry case.
                val trailing = trailingBytes(record)
                record = record.copyOf(record.size - trailing)
            }
            out.write(
                when (compression) {
                    1 -> record
                    2 -> PalmDoc.decompress(record)
                    else -> return ByteArray(0)
                }
            )
        }
        return out.toByteArray()
    }

    private fun trailingBytes(record: ByteArray): Int {
        // Per the MOBI spec each flagged trailing entry is
        // `data + varint(size)` where size counts the data AND the
        // varint bytes; entries sit back-to-back at the record end.
        var flags = extraFlags
        var total = 0
        var end = record.size
        while (flags != 0 && end > 0) {
            if (flags and 1 != 0) {
                var value = 0
                var shift = 0
                var p = end - 1
                while (p >= 0) {
                    val b = record[p].toInt() and 0xFF
                    value = value or ((b and 0x7F) shl shift)
                    if (b and 0x80 != 0) break
                    shift += 7
                    p--
                    if (shift > 28) break
                }
                if (value <= 0) break
                total += value
                end -= value
            }
            flags = flags shr 1
        }
        return total.coerceIn(0, record.size / 2)
    }

    companion object {
        fun read(bytes: ByteArray): MobiFile? {
            if (bytes.size < 78) return null
            fun u16(offset: Int): Int =
                ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)
            fun u32(offset: Int): Int =
                ((bytes[offset].toInt() and 0xFF) shl 24) or
                    ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
                    ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
                    (bytes[offset + 3].toInt() and 0xFF)
            val type = String(bytes, 60, 8, Charsets.US_ASCII)
            if (!type.startsWith("BOOK") && !type.startsWith("TEXT")) return null
            val recordCount = u16(76)
            if (recordCount < 1 || recordCount > 100_000) return null
            val offsets = IntArray(recordCount + 1)
            for (i in 0 until recordCount) {
                val at = 78 + i * 8
                if (at + 4 > bytes.size) return null
                offsets[i] = u32(at)
            }
            offsets[recordCount] = bytes.size
            if (offsets.any { it < 0 || it > bytes.size }) return null
            val records = (0 until recordCount).map { i ->
                bytes.copyOfRange(offsets[i], offsets[i + 1])
            }
            val record0 = records[0]
            if (record0.size < 24) return null
            // PalmDOC header fields live in RECORD 0, not the file —
            // read them with the record-relative helpers.
            val compression = u16At(record0, 0)
            val textRecordCount = u16At(record0, 8)
            val encryptionType = u16At(record0, 12)
            // MOBI header begins at offset 16 in record 0.
            var encoding = 1252
            var extraFlags = 0
            var title: String? = null
            var author: String? = null
            if (record0.size >= 20 &&
                String(record0, 16, 4, Charsets.US_ASCII) == "MOBI"
            ) {
                val mobi = 16
                // Text encoding at MOBI+12, full-name offset/length at
                // +84/+88, extra data flags at +240 (header ≥ 232).
                if (record0.size >= mobi + 16) encoding = u32At(record0, mobi + 12)
                if (record0.size >= mobi + 92) {
                    val nameOffset = u32At(record0, mobi + 84)
                    val nameLength = u32At(record0, mobi + 88)
                    if (nameOffset > 0 && nameLength > 0 &&
                        nameOffset + nameLength <= record0.size
                    ) {
                        val charset = if (encoding == 65001) Charsets.UTF_8
                        else Charset.forName("windows-1252")
                        title = String(record0, nameOffset, nameLength, charset)
                            .trim().takeIf { it.isNotEmpty() }
                    }
                }
                // Extra data flags sit at MOBI+240 — only when the
                // header is long enough to contain them (≥ 244 bytes);
                // a shorter header means "no trailing entries", not
                // "read into whatever follows the header".
                val headerLength = if (record0.size >= mobi + 8) u32At(record0, mobi + 4) else 0
                if (headerLength >= 244 && record0.size >= mobi + 242) {
                    extraFlags = u16At(record0, mobi + 240)
                }
                // EXTH header follows the MOBI header.
                val exthStart = mobi + headerLength
                if (headerLength > 0 && record0.size >= exthStart + 12 &&
                    String(record0, exthStart, 4, Charsets.US_ASCII) == "EXTH"
                ) {
                    val exthLength = u32At(record0, exthStart + 4)
                    val count = u32At(record0, exthStart + 8)
                    var pos = exthStart + 12
                    val charset = if (encoding == 65001) Charsets.UTF_8
                    else Charset.forName("windows-1252")
                    repeat(count) {
                        if (pos + 8 > record0.size || pos + 8 > exthStart + exthLength) return@repeat
                        val typeCode = u32At(record0, pos)
                        val length = u32At(record0, pos + 4)
                        if (length < 8 || pos + length > record0.size) return@repeat
                        if (typeCode == 100) {
                            author = String(record0, pos + 8, length - 8, charset)
                                .trim().takeIf { it.isNotEmpty() } ?: author
                        }
                        if (typeCode == 503 && title == null) {
                            title = String(record0, pos + 8, length - 8, charset)
                                .trim().takeIf { it.isNotEmpty() }
                        }
                        pos += length
                    }
                }
            }
            return MobiFile(
                title = title,
                author = author,
                encryptionType = encryptionType,
                compression = compression,
                encoding = encoding,
                records = records,
                textRecordCount = textRecordCount,
                extraFlags = extraFlags
            )
        }

        private fun u32At(bytes: ByteArray, offset: Int): Int =
            ((bytes[offset].toInt() and 0xFF) shl 24) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
                (bytes[offset + 3].toInt() and 0xFF)

        private fun u16At(bytes: ByteArray, offset: Int): Int =
            ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)
    }
}
