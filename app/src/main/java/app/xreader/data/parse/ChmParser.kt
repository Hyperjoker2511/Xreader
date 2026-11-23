package app.xreader.data.parse

import app.xreader.core.error.AppError
import app.xreader.core.error.AppResult
import app.xreader.data.db.BookFormat
import app.xreader.domain.content.ChapterBlock
import app.xreader.domain.content.ChapterContent
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.charset.Charset
import kotlinx.serialization.json.Json
import org.jsoup.Jsoup

/**
 * CHM (OE-5 part B, from build-logs/research/RESEARCH-CHM.md):
 * Microsoft Compiled HTML Help — an ITSF container whose
 * directory (ITSP/PMGL) names every file in the book, whose
 * content lives in an uncompressed section 0 and an
 * LZX-compressed section 1, and whose own Contents (the .hhc
 * sitemap) gives the chapter order and titles — the Moon+ shape:
 * topics become chapters through the one HTML pipeline.
 *
 * The research's most common first-implementation bug, stated
 * here because every read below depends on it: **all
 * directory-entry offsets are relative to the ITSF header's
 * `data_offset` (0x58), not to the start of the file.**
 */
class ChmParser : BookParser {

    override val format: BookFormat = BookFormat.CHM

    override fun parse(
        staged: File,
        workDir: File,
        onProgress: (Float?) -> Unit
    ): AppResult<ParsedBook> {
        return try {
            AppResult.Ok(ChmBook(staged.readBytes()).convert(workDir, onProgress))
        } catch (e: ChmFailure) {
            AppResult.Err(AppError.ParseFailed("CHM", e.message ?: "the file couldn't be read"))
        } catch (e: Exception) {
            AppResult.Err(AppError.ParseFailed("CHM", "the file couldn't be read", e.javaClass.simpleName))
        }
    }

    private class ChmFailure(message: String) : Exception(message)

    /** What the #SYSTEM file carried (ChmBook.readSystem). */
    private class SystemInfo(
        val title: String?,
        val contentsPath: String?,
        val charset: Charset
    )

    private fun fail(reason: String): Nothing = throw ChmFailure(reason)

    /** One directory listing entry (§2.2). [offset] is relative
     * to the section it names; the section's own base is applied
     * at read time (section 0's base is `data_offset`). */
    private data class DirEntry(
        val rawName: String,
        val section: Long,
        val offset: Long,
        val length: Long
    )

    private inner class ChmBook(private val bytes: ByteArray) {

        private val dataOffset: Long
        private val headerLcid: Int
        private val entries: List<DirEntry>
        private val byKey: Map<String, DirEntry>
        private val section1: ByteArray?

        init {
            if (bytes.size < 0x58 || !magicAt(0, "ITSF")) {
                fail("the file isn't a readable CHM book")
            }
            val version = u32le(0x04)
            headerLcid = u32le(0x14).toInt()
            val dirOffset = u64le(0x48)
            val dirLength = u64le(0x50)
            // v3 carries the content offset at 0x58; v2 computes
            // it as the end of the directory (§1.1).
            dataOffset = if (version >= 3) {
                if (bytes.size < 0x60) fail("the file isn't a readable CHM book")
                u64le(0x58)
            } else {
                dirOffset + dirLength
            }
            entries = readDirectory(dirOffset)
            val map = LinkedHashMap<String, DirEntry>()
            for (entry in entries) map.putIfAbsent(key(entry.rawName), entry)
            byKey = map
            section1 = openCompressedSection()
        }

        // ---- container ----

        private fun readDirectory(dirOffset: Long): List<DirEntry> {
            if (dirOffset < 0 || dirOffset + 0x54 > bytes.size || !magicAt(dirOffset.toInt(), "ITSP")) {
                fail("the file's directory couldn't be read")
            }
            val base = dirOffset.toInt()
            val chunkSize = u32le(base + 0x10)
            if (chunkSize < 0x14 || chunkSize > bytes.size) {
                fail("the file's directory couldn't be read")
            }
            var chunk = i32le(base + 0x20) // first PMGL chunk
            val found = mutableListOf<DirEntry>()
            val visited = mutableSetOf<Int>()
            while (chunk >= 0) {
                if (!visited.add(chunk)) fail("the file's directory couldn't be read")
                val chunkStart = dirOffset + 0x54 + chunk.toLong() * chunkSize
                if (chunkStart < 0 || chunkStart + chunkSize > bytes.size ||
                    !magicAt(chunkStart.toInt(), "PMGL")
                ) {
                    fail("the file's directory couldn't be read")
                }
                val start = chunkStart.toInt()
                val freeSpace = u32le(start + 0x04)
                val entriesEnd = (chunkStart + chunkSize - freeSpace).toInt()
                var pos = start + 0x14
                while (pos < entriesEnd) {
                    val cursor = Cursor(pos, entriesEnd)
                    val nameLength = cursor.encint()
                    if (nameLength < 0 || nameLength > (entriesEnd - cursor.pos).toLong()) {
                        fail("the file's directory couldn't be read")
                    }
                    val name = String(bytes, cursor.pos, nameLength.toInt(), Charsets.UTF_8)
                    cursor.pos += nameLength.toInt()
                    val section = cursor.encint()
                    val offset = cursor.encint()
                    val length = cursor.encint()
                    found += DirEntry(name, section, offset, length)
                    pos = cursor.pos
                }
                chunk = i32le(start + 0x10) // block_next; PMGI is an index — never walked
            }
            return found
        }

        /** ENCINT cursor (§2.5): big-endian base-128, high bit
         * set on every byte except the last. */
        private inner class Cursor(var pos: Int, private val end: Int) {
            fun encint(): Long {
                var value = 0L
                while (true) {
                    if (pos >= end) fail("the file's directory couldn't be read")
                    val b = bytes[pos++].toInt() and 0xFF
                    value = (value shl 7) or (b and 0x7F).toLong()
                    if (b < 0x80) return value
                }
            }
        }

        // ---- sections ----

        /** Section bytes for one entry, or null when the entry
         * lives in a section this book cannot produce (an index
         * beyond 1, or section 1 of an uncompressed book). */
        private fun entryBytes(entry: DirEntry): ByteArray? = when (entry.section) {
            0L -> slice(dataOffset + entry.offset, entry.length)
            1L -> section1?.let { sliceFrom(it, entry.offset, entry.length) }
            else -> null
        }

        private fun slice(from: Long, length: Long): ByteArray? {
            if (from < 0 || length < 0 || from + length > bytes.size) return null
            return bytes.copyOfRange(from.toInt(), (from + length).toInt())
        }

        private fun sliceFrom(source: ByteArray, from: Long, length: Long): ByteArray? {
            if (from < 0 || length < 0 || from + length > source.size) return null
            return source.copyOfRange(from.toInt(), (from + length).toInt())
        }

        /**
         * The MSCompressed storage, by its EXACT paths (§3.2 —
         * the ResetTable lives under the Transform/{GUID}/
         * InstanceData/ segment; do not abbreviate). Posture
         * (§7.2, Revision 1): the storage is either ALL FOUR
         * entries present or absent entirely (an uncompressed
         * book); a partial set is a damaged file, never a guess.
         */
        private fun openCompressedSection(): ByteArray? {
            val control = byKey[key(CONTROL_DATA)]
            val reset = byKey[key(RESET_TABLE)]
            val span = byKey[key(SPAN_INFO)]
            val content = byKey[key(CONTENT)]
            val present = listOf(control, reset, span, content).count { it != null }
            if (present == 0) return null
            if (present != 4) fail("the file's compressed storage is incomplete")

            val controlBytes = entryBytes(control!!)
                ?: fail("the file's compressed storage couldn't be read")
            if (controlBytes.size < 0x18 || !magicIn(controlBytes, 4, "LZXC")) {
                fail("the file's compressed storage couldn't be read")
            }
            if (u32le(controlBytes, 0x08) != 2L) {
                fail("the file's compression version isn't supported")
            }
            val resetBlocks = u32le(controlBytes, 0x0C)
            val windowField = u32le(controlBytes, 0x10)
            val windowBytes = windowField * 0x8000L
            if (windowBytes < (1 shl 15) || windowBytes > (1 shl 21) ||
                (windowBytes and (windowBytes - 1)) != 0L
            ) {
                fail("the file's compressed storage couldn't be read")
            }
            if (resetBlocks < 1 || resetBlocks > Int.MAX_VALUE) {
                fail("the file's compressed storage couldn't be read")
            }
            // chmlib's own consistency rule (§3.3).
            if ((resetBlocks * 0x8000L) % (windowBytes / 2) != 0L) {
                fail("the file's compressed storage couldn't be read")
            }

            val resetBytes = entryBytes(reset!!)
                ?: fail("the file's compressed storage couldn't be read")
            if (resetBytes.size < 0x28) fail("the file's compressed storage couldn't be read")
            val blockCount = u32le(resetBytes, 0x04)
            val tableOffset = u32le(resetBytes, 0x0C)
            val tableUncompressed = u64le(resetBytes, 0x10)
            val compressedTotal = u64le(resetBytes, 0x18)
            if (u64le(resetBytes, 0x20) != 0x8000L) {
                fail("the file's compressed storage couldn't be read")
            }
            if (blockCount > (Int.MAX_VALUE - 8) / 8 ||
                tableOffset + blockCount * 8 > resetBytes.size
            ) {
                fail("the file's compressed storage couldn't be read")
            }
            val addresses = LongArray(blockCount.toInt()) { i ->
                u64le(resetBytes, (tableOffset + i * 8L).toInt())
            }

            val spanBytes = entryBytes(span!!)
                ?: fail("the file's compressed storage couldn't be read")
            if (spanBytes.size < 8) fail("the file's compressed storage couldn't be read")
            val spanTotal = u64le(spanBytes, 0)
            // The final block clamps to SpanInfo's total (§3.4);
            // the table must agree with it on the block count.
            val expectedBlocks = if (spanTotal == 0L) 0L else (spanTotal + 0x7FFF) / 0x8000
            if (expectedBlocks != blockCount || tableUncompressed != spanTotal) {
                fail("the file's compressed storage couldn't be read")
            }
            if (spanTotal > EXTRACT_CAP_BYTES) fail("the book is too large to open")

            val contentBytes = entryBytes(content!!)
                ?: fail("the file's compressed storage couldn't be read")
            if (compressedTotal > contentBytes.size) {
                fail("the file's compressed storage couldn't be read")
            }
            return try {
                LzxDecoder.decompress(
                    contentBytes,
                    addresses,
                    compressedTotal,
                    spanTotal,
                    windowBytes.toInt(),
                    resetBlocks.toInt()
                )
            } catch (e: LzxException) {
                fail("the file's compressed content couldn't be read")
            }
        }

        // ---- #SYSTEM + charset ----

        private fun readSystem(): SystemInfo {
            val records = mutableMapOf<Int, ByteArray>()
            val entry = byKey[key("#SYSTEM")]
            val data = entry?.let { entryBytes(it) }
            if (data != null && data.size >= 4) {
                var pos = 4 // DWORD version word precedes the records
                while (pos + 4 <= data.size) {
                    val code = u16le(data, pos)
                    val length = u16le(data, pos + 2)
                    pos += 4
                    if (pos + length > data.size) break
                    records[code] = data.copyOfRange(pos, pos + length)
                    pos += length
                }
            }
            // LCID chain (Revision 1): #SYSTEM code 4 → the ITSF
            // header LCID at 0x14 → windows-1252.
            val lcid = records[4]?.takeIf { it.size >= 4 }
                ?.let { u32le(it, 0).toInt() } ?: headerLcid
            val charset = codepage(lcid)
            fun ntString(code: Int): String? = records[code]?.let { raw ->
                val end = raw.indexOf(0.toByte()).takeIf { it >= 0 } ?: raw.size
                String(raw, 0, end, charset).trim().takeIf { it.isNotEmpty() }
            }
            return SystemInfo(
                title = ntString(3),
                contentsPath = ntString(0),
                charset = charset
            )
        }

        /** LCID → codepage (§5.4's table; unlisted locales fall
         * back to windows-1252, the HH-era default). */
        private fun codepage(lcid: Int): Charset {
            val name = when (lcid) {
                0x0419 -> "windows-1251"
                0x0408 -> "windows-1253"
                0x041F -> "windows-1254"
                0x040D -> "windows-1255"
                0x0401 -> "windows-1256"
                0x0411 -> "Shift_JIS"
                0x0412 -> "windows-949"
                0x0804 -> "GBK"
                0x0404, 0x0C04 -> "Big5"
                else -> "windows-1252"
            }
            return runCatching { Charset.forName(name) }.getOrDefault(Charsets.UTF_8)
        }

        // ---- conversion ----

        fun convert(workDir: File, onProgress: (Float?) -> Unit): ParsedBook {
            val system = readSystem()

            // Extract EVERY content entry under its normalised
            // name — the extraction layout is what lets the HTML
            // pipeline's relative resolution find topic pictures.
            val stageDir = File(workDir, "chm-stage").apply { mkdirs() }
            val stageCanonical = stageDir.canonicalPath
            val extractedByKey = mutableMapOf<String, File>()
            val topicsInOrder = mutableListOf<Pair<String, File>>()
            val topicKeys = mutableSetOf<String>()
            var totalExtracted = 0L
            for (entry in entries) {
                val normalised = entry.rawName.replace('\\', '/')
                if (!normalised.startsWith("/")) continue // container meta files
                val rel = normalised.removePrefix("/")
                if (rel.isEmpty()) continue
                val data = entryBytes(entry) ?: continue
                totalExtracted += data.size
                if (totalExtracted > EXTRACT_CAP_BYTES) fail("the book is too large to open")
                val dest = File(stageDir, rel)
                // Path safety: an entry whose normalised path
                // escapes the stage dir is not extracted.
                if (!dest.canonicalPath.startsWith(stageCanonical + File.separator)) continue
                // Real files carry prefix collisions (a file
                // entry named exactly like another entry's
                // folder — the $WW* metadata family does this):
                // a folder wins over a same-named file, and a
                // file whose path is already a folder is dropped.
                val parent = dest.parentFile
                if (parent != null && !parent.isDirectory) {
                    if (parent.exists()) parent.delete()
                    parent.mkdirs()
                }
                if (dest.isDirectory) continue
                dest.writeBytes(data)
                val k = key(entry.rawName)
                extractedByKey.putIfAbsent(k, dest)
                val lower = rel.lowercase()
                if ((lower.endsWith(".htm") || lower.endsWith(".html")) && topicKeys.add(k)) {
                    topicsInOrder += k to dest
                }
            }

            // Chapter list: the .hhc's entries in document order,
            // then every extracted topic the .hhc never named, in
            // directory order. Dedupe key = the Local path with
            // any #fragment stripped (Revision 1).
            var skippedTopics = 0
            val chapterList = mutableListOf<Pair<String?, File>>()
            val usedKeys = mutableSetOf<String>()
            val hhcFile = system.contentsPath?.let { extractedByKey[key(it)] }
                ?: entries.firstOrNull { key(it.rawName).endsWith(".hhc") }
                    ?.let { extractedByKey[key(it.rawName)] }
            if (hhcFile != null) {
                val doc = Jsoup.parse(
                    ByteArrayInputStream(hhcFile.readBytes()), system.charset.name(), ""
                )
                for (obj in doc.getElementsByTag("object")) {
                    if (!obj.attr("type").equals("text/sitemap", ignoreCase = true)) continue
                    var name: String? = null
                    var local: String? = null
                    for (param in obj.getElementsByTag("param")) {
                        when (param.attr("name").lowercase()) {
                            "name" -> name = param.attr("value")
                            "local" -> local = param.attr("value")
                        }
                    }
                    // No Local: the site-properties object and
                    // heading ("book") nodes — both dropped
                    // deliberately; the flat chapter list has no
                    // place for headings (RESEARCH-CHM §5.2).
                    val localPath = local ?: continue
                    if (localPath.contains("::") ||
                        localPath.startsWith("http", ignoreCase = true) ||
                        localPath.contains("://")
                    ) {
                        continue // external or cross-CHM — not this book's topic
                    }
                    val k = key(localPath.substringBefore('#'))
                    if (!k.endsWith(".htm") && !k.endsWith(".html")) continue
                    if (!usedKeys.add(k)) continue // converts once, first wins
                    val file = extractedByKey[k]
                    if (file == null) {
                        skippedTopics++ // named in the Contents, absent from the file
                        continue
                    }
                    chapterList += (name?.trim()?.takeIf { it.isNotEmpty() }) to file
                }
            }
            for ((k, file) in topicsInOrder) {
                if (usedKeys.add(k)) chapterList += null to file
            }

            // Convert per topic in its OWN temp dir, then merge
            // (Revision 1's prescribed merge): decode each
            // produced chapter, remap colliding asset names into
            // the book's one assets dir (rewriting the Image
            // references), and re-emit through ONE writer.
            val finalWriter = ChapterFileWriter(File(workDir, "chapters"))
            val bookAssetsDir = File(workDir, "assets").apply { mkdirs() }
            val bookUsedNames = mutableSetOf<String>()
            var droppedImages = 0
            val json = Json { encodeDefaults = false }
            chapterList.forEachIndexed { index, (hhcTitle, topicFile) ->
                onProgress((index + 1).toFloat() / chapterList.size.coerceAtLeast(1))
                val parsed = when (val result = HtmlParser(
                    charsetHint = system.charset.name(),
                    extraImageRoots = listOf(stageDir)
                ).parse(topicFile, File(workDir, "topic-$index")) { }) {
                    is AppResult.Err -> {
                        skippedTopics++
                        return@forEachIndexed
                    }
                    is AppResult.Ok -> result.value
                }
                droppedImages += parsed.droppedImages
                val remap = mutableMapOf<String, String>()
                parsed.assetsDir?.let { topicAssets ->
                    topicAssets.listFiles()?.filter { it.isFile }
                        ?.sortedBy { it.name }?.forEach { assetFile ->
                            val stagedName = assetFile.name
                            if (stagedName in bookUsedNames) {
                                val fresh = ZipPaths.assetFileName(stagedName, bookUsedNames)
                                assetFile.copyTo(File(bookAssetsDir, fresh), overwrite = true)
                                remap[stagedName] = fresh
                            } else {
                                bookUsedNames += stagedName
                                assetFile.copyTo(File(bookAssetsDir, stagedName), overwrite = true)
                            }
                        }
                }
                var firstForTopic = true
                for (meta in parsed.chapters) {
                    val chapterFile = File(parsed.chaptersDir, meta.contentFileName)
                    val content = json.decodeChapter(chapterFile.readText())
                    val remapped = if (remap.isEmpty()) {
                        content
                    } else {
                        ChapterContent(content.blocks.map { block ->
                            if (block is ChapterBlock.Image && remap.containsKey(block.fileName)) {
                                block.copy(fileName = remap.getValue(block.fileName))
                            } else {
                                block
                            }
                        })
                    }
                    // The .hhc title names the topic's FIRST
                    // re-emitted chapter; later chapters keep
                    // their heading-derived titles.
                    finalWriter.add(
                        if (firstForTopic && hhcTitle != null) hhcTitle else meta.title,
                        remapped
                    )
                    firstForTopic = false
                }
            }
            if (finalWriter.metas.isEmpty()) {
                fail("the file has no readable chapters")
            }
            return ParsedBook(
                title = system.title ?: chapterList.firstOrNull()?.first,
                author = null,
                description = null,
                language = null,
                format = BookFormat.CHM,
                coverFile = null,
                chapters = finalWriter.metas,
                chaptersDir = File(workDir, "chapters"),
                assetsDir = bookAssetsDir.takeIf { it.list()?.isNotEmpty() == true },
                droppedImages = droppedImages,
                droppedChapters = skippedTopics
            )
        }

        // ---- byte helpers ----

        private fun magicAt(offset: Int, magic: String): Boolean {
            if (offset < 0 || offset + magic.length > bytes.size) return false
            return magic.indices.all { bytes[offset + it] == magic[it].code.toByte() }
        }

        private fun magicIn(source: ByteArray, offset: Int, magic: String): Boolean {
            if (offset < 0 || offset + magic.length > source.size) return false
            return magic.indices.all { source[offset + it] == magic[it].code.toByte() }
        }

        private fun u16le(offset: Int): Int =
            (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

        private fun u16le(source: ByteArray, offset: Int): Int =
            (source[offset].toInt() and 0xFF) or ((source[offset + 1].toInt() and 0xFF) shl 8)

        private fun u32le(offset: Int): Long = u32le(bytes, offset)

        private fun u32le(source: ByteArray, offset: Int): Long =
            (source[offset].toLong() and 0xFF) or
                ((source[offset + 1].toLong() and 0xFF) shl 8) or
                ((source[offset + 2].toLong() and 0xFF) shl 16) or
                ((source[offset + 3].toLong() and 0xFF) shl 24)

        private fun i32le(offset: Int): Int = u32le(offset).toInt()

        private fun u64le(offset: Int): Long = u64le(bytes, offset)

        private fun u64le(source: ByteArray, offset: Int): Long {
            var value = 0L
            for (i in 7 downTo 0) {
                value = (value shl 8) or (source[offset + i].toLong() and 0xFF)
            }
            return value
        }

        /** Directory/.hhc name matching (§2.6/§5.2): `/`
         * separators, leading `/` stripped, case-insensitive. */
        private fun key(name: String): String =
            name.replace('\\', '/').removePrefix("/").lowercase()
    }

    private companion object {
        /** Total extracted-size cap (§7 corrupt-file posture). */
        const val EXTRACT_CAP_BYTES = 512L * 1024 * 1024

        // The MSCompressed storage paths, EXACTLY as
        // RESEARCH-CHM §3.2 gives them — including the
        // Transform/{GUID}/InstanceData/ segment.
        const val CONTROL_DATA = "::DataSpace/Storage/MSCompressed/ControlData"
        const val SPAN_INFO = "::DataSpace/Storage/MSCompressed/SpanInfo"
        const val CONTENT = "::DataSpace/Storage/MSCompressed/Content"
        const val RESET_TABLE =
            "::DataSpace/Storage/MSCompressed/Transform/" +
                "{7FC28940-9D31-11D0-9B27-00A0C91E9C7C}/InstanceData/ResetTable"
    }
}
