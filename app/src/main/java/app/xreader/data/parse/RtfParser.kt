package app.xreader.data.parse

import app.xreader.core.error.AppError
import app.xreader.core.error.AppResult
import app.xreader.data.db.BookFormat
import app.xreader.domain.content.ChapterBlock
import app.xreader.domain.content.ChapterContent
import app.xreader.domain.content.TextSpan
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.Charset

/**
 * RTF (OE-3, Moon+ copy): a small hand reader for the format —
 * groups and control words, nothing more. `\par` ends a
 * paragraph; `\b`/`\i` toggle run styling; `\uN` and `\'hh`
 * carry the text (the hex bytes decode through the file's own
 * `\ansicpgNNNN` when it declares one, else windows-1252). The
 * font table, colour table, stylesheet and every `{\*\…}`
 * destination group are skipped whole — they are document
 * furniture, never book text. The book lands as ONE chapter
 * (the Moon+ shape for the format).
 */
class RtfParser : BookParser {

    override val format: BookFormat = BookFormat.RTF

    override fun parse(
        staged: File,
        workDir: File,
        onProgress: (Float?) -> Unit
    ): AppResult<ParsedBook> {
        return try {
            val blocks = RtfDocument.parse(staged)
            if (blocks.isEmpty()) {
                return AppResult.Err(AppError.ParseFailed("RTF", "the file has no readable text"))
            }
            val writer = ChapterFileWriter(File(workDir, "chapters"))
            writer.add("", ChapterContent(blocks))
            AppResult.Ok(
                ParsedBook(
                    title = null,
                    author = null,
                    description = null,
                    language = null,
                    format = BookFormat.RTF,
                    coverFile = null,
                    chapters = writer.metas,
                    chaptersDir = File(workDir, "chapters"),
                    assetsDir = null
                )
            )
        } catch (e: Exception) {
            AppResult.Err(AppError.ParseFailed("RTF", "the file couldn't be read", e.javaClass.simpleName))
        }
    }
}

/** Pure RTF walk, split out for fixture tests (the FB2 shape). */
object RtfDocument {

    /** Groups whose contents are never book text. `{\*\…}`
     * destinations are caught separately — the `\*` itself is
     * the marker. */
    private val SKIP_DESTINATIONS = setOf(
        "fonttbl", "colortbl", "stylesheet", "info",
        "pict", "object", "objdata"
    )

    private val SYMBOL_WORDS = mapOf(
        "emdash" to "—", "endash" to "–", "bullet" to "•",
        "lquote" to "‘", "rquote" to "’",
        "ldblquote" to "“", "rdblquote" to "”"
    )

    fun parse(file: File): List<ChapterBlock> {
        // The file is read through windows-1252 so the ASCII
        // markup survives byte-for-byte; \'hh escapes are
        // collected as raw bytes and decoded through the file's
        // own codepage below.
        val raw = file.readBytes().toString(Charset.forName("windows-1252"))
        val codepage = Regex("""\\ansicpg(\d+)""").find(raw)
            ?.groupValues?.get(1)?.toIntOrNull()
        val charset = codepage?.let { cp ->
            runCatching { Charset.forName("windows-$cp") }.getOrNull()
        } ?: Charset.forName("windows-1252")

        val blocks = mutableListOf<ChapterBlock>()
        val spans = mutableListOf<TextSpan>()
        var bold = false
        var italic = false
        val stateStack = ArrayDeque<Pair<Boolean, Boolean>>()
        var ucSkip = 1
        val pendingBytes = ByteArrayOutputStream()

        fun appendText(text: String) {
            if (text.isEmpty()) return
            val last = spans.lastOrNull()
            if (last != null && last.bold == bold && last.italic == italic) {
                spans[spans.lastIndex] = last.copy(text = last.text + text)
            } else {
                spans += TextSpan(text, bold, italic)
            }
        }

        fun flushBytes() {
            if (pendingBytes.size() > 0) {
                appendText(String(pendingBytes.toByteArray(), charset))
                pendingBytes.reset()
            }
        }

        fun flushParagraph() {
            flushBytes()
            if (spans.isEmpty()) return
            val cleaned = spans.map { it }.toMutableList()
            while (cleaned.isNotEmpty() && cleaned.first().text.isBlank()) cleaned.removeAt(0)
            while (cleaned.isNotEmpty() && cleaned.last().text.isBlank()) {
                cleaned.removeAt(cleaned.lastIndex)
            }
            if (cleaned.isNotEmpty()) {
                cleaned[0] = cleaned.first().copy(text = cleaned.first().text.trimStart())
                cleaned[cleaned.lastIndex] = cleaned.last().copy(
                    text = cleaned.last().text.trimEnd()
                )
                blocks += ChapterBlock.Paragraph(cleaned.toList())
            }
            spans.clear()
        }

        var i = 0
        val n = raw.length
        while (i < n) {
            when (val c = raw[i]) {
                // Bare CR/LF in the source are not content.
                '\r', '\n' -> i++
                '{' -> {
                    stateStack.addLast(bold to italic)
                    val skipTo = destinationEnd(raw, i + 1)
                    if (skipTo != null) {
                        stateStack.removeLast()
                        i = skipTo
                    } else {
                        i++
                    }
                }
                '}' -> {
                    flushBytes()
                    val previous = stateStack.removeLastOrNull()
                    if (previous != null) {
                        bold = previous.first
                        italic = previous.second
                    }
                    i++
                }
                '\\' -> {
                    if (i + 1 >= n) {
                        i = n
                        continue
                    }
                    val next = raw[i + 1]
                    when {
                        next == '\'' -> {
                            val hex = if (i + 3 < n) raw.substring(i + 2, i + 4) else ""
                            val byte = hex.toIntOrNull(16)
                            if (byte != null) pendingBytes.write(byte)
                            i += 4
                        }
                        next.isLetter() -> {
                            var j = i + 1
                            while (j < n && raw[j].isLetter()) j++
                            val word = raw.substring(i + 1, j)
                            // Optional signed parameter, then at
                            // most one space delimiter.
                            var d = j
                            if (d < n && (raw[d] == '-' || raw[d] == '+')) d++
                            val digitsStart = d
                            while (d < n && raw[d].isDigit()) d++
                            val param = if (d > digitsStart) {
                                raw.substring(j, d).toIntOrNull()
                            } else {
                                null
                            }
                            var end = d
                            if (end < n && raw[end] == ' ') end++
                            when (word) {
                                "par" -> flushParagraph()
                                "line" -> {
                                    flushBytes()
                                    appendText("\n")
                                }
                                "b" -> bold = param == null || param != 0
                                "i" -> italic = param == null || param != 0
                                "plain" -> {
                                    bold = false
                                    italic = false
                                }
                                "uc" -> ucSkip = param ?: 1
                                "u" -> {
                                    flushBytes()
                                    val code = param ?: 0
                                    val cp = if (code < 0) code + 65536 else code
                                    if (cp in 0..0xFFFF) appendText(cp.toChar().toString())
                                    // Skip the ANSI fallback the
                                    // writer appended (\'hh or one
                                    // plain char), \ucN of them.
                                    var skip = ucSkip
                                    var p = end
                                    while (skip > 0 && p < n) {
                                        if (p + 3 < n && raw[p] == '\\' && raw[p + 1] == '\'') {
                                            p += 4
                                        } else if (raw[p] != '\\' && raw[p] != '{' && raw[p] != '}') {
                                            p++
                                        } else {
                                            break
                                        }
                                        skip--
                                    }
                                    end = p
                                }
                                "tab" -> {
                                    flushBytes()
                                    appendText(" ")
                                }
                                in SYMBOL_WORDS -> {
                                    flushBytes()
                                    appendText(SYMBOL_WORDS.getValue(word))
                                }
                                else -> Unit
                            }
                            i = end
                        }
                        else -> {
                            // Control symbol: \\ \{ \} are literal
                            // characters, \~ a non-breaking space;
                            // the rest carry no text.
                            flushBytes()
                            when (next) {
                                '\\' -> appendText("\\")
                                '{' -> appendText("{")
                                '}' -> appendText("}")
                                '~' -> appendText(" ")
                            }
                            i += 2
                        }
                    }
                }
                else -> {
                    flushBytes()
                    appendText(c.toString())
                    i++
                }
            }
        }
        flushParagraph()
        return blocks
    }

    /**
     * When the group opening just before [from] is a destination
     * group (a `{\*\…}` group, or one of [SKIP_DESTINATIONS]),
     * returns the index just past its matching close brace;
     * null when the group is ordinary content.
     */
    private fun destinationEnd(raw: String, from: Int): Int? {
        var i = from
        if (i + 1 < raw.length && raw[i] == '\\' && raw[i + 1] == '*') {
            return skipGroup(raw, from)
        }
        if (i < raw.length && raw[i] == '\\') {
            var j = i + 1
            while (j < raw.length && raw[j].isLetter()) j++
            if (raw.substring(i + 1, j) in SKIP_DESTINATIONS) {
                return skipGroup(raw, from)
            }
        }
        return null
    }

    /** Index just past the close brace matching the group whose
     * contents start at [from] (the group is already open). */
    private fun skipGroup(raw: String, from: Int): Int {
        var depth = 1
        var i = from
        while (i < raw.length && depth > 0) {
            when (raw[i]) {
                '\\' -> {
                    // Escapes (\\, \{, \}, \'hh) never move depth.
                    i += if (i + 1 < raw.length && raw[i + 1] == '\'') 4 else 2
                }
                '{' -> {
                    depth++
                    i++
                }
                '}' -> {
                    depth--
                    i++
                }
                else -> i++
            }
        }
        return i
    }
}
