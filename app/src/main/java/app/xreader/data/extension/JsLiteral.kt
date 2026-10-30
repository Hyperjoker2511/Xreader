package app.xreader.data.extension

/**
 * A tolerant reader for the object literal a compiled LNReader
 * multisrc plugin passes to its generator class (v1.0.9):
 *
 * ```
 * exports.default = new MadaraPlugin({id:"arnovel",
 *   sourceSite:"https://ar-no.com/", sourceName:"ArNovel",
 *   options:{useNewChapterEndpoint:!0, lang:"Arabic"},
 *   filters:{…}});
 * ```
 *
 * The literal is JSON-like but not JSON: keys may be unquoted,
 * booleans are minified to `!0` / `!1`, strings may be single-
 * quoted. This is a small recursive-descent parser for exactly
 * that grammar (objects, arrays, strings, numbers, booleans,
 * null, `!0`/`!1`) — it never throws; anything unexpected
 * yields null. Only the constructor's `options` bag is consumed
 * by the family templates; the rest is parsed and ignored.
 */
object JsConstructorOptions {

    /** The `options` object of the plugin's constructor call,
     * flattened to strings (booleans as "true"/"false", numbers
     * in plain form); null when no constructor literal is found. */
    fun extractOptions(compiledPluginJs: String): Map<String, String>? {
        val literal = constructorLiteral(compiledPluginJs) ?: return null
        val parsed = Parser(literal).parseValue() as? Map<*, *> ?: return null
        val options = parsed["options"] as? Map<*, *> ?: return emptyMap()
        return options.entries.associate { (key, value) ->
            key.toString() to when (value) {
                is Boolean -> value.toString()
                is Double -> if (value == value.toLong().toDouble()) {
                    value.toLong().toString()
                } else {
                    value.toString()
                }
                null -> ""
                else -> value.toString()
            }
        }
    }

    /** The constructor call's object literal text: the LAST
     * `new X({` in the file (the instantiation is the file's
     * final statement), brace-matched with string awareness. */
    private fun constructorLiteral(js: String): String? {
        val pattern = Regex("""new\s+[A-Za-z_$][A-Za-z0-9_$]*\s*\(\s*\{""")
        val match = pattern.findAll(js).lastOrNull() ?: return null
        val openBrace = js.indexOf('{', match.range.first)
        if (openBrace < 0) return null
        var depth = 0
        var quote: Char? = null
        var i = openBrace
        while (i < js.length) {
            val c = js[i]
            if (quote != null) {
                if (c == '\\') {
                    i += 2
                    continue
                }
                if (c == quote) quote = null
            } else {
                when (c) {
                    '"', '\'', '`' -> quote = c
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return js.substring(openBrace, i + 1)
                    }
                }
            }
            i++
        }
        return null
    }

    private class Parser(private val text: String) {
        private var pos = 0

        fun parseValue(): Any? {
            skipSpace()
            return when (peek()) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"', '\'' -> parseString()
                '!' -> {
                    // Minified booleans: !0 = true, !1 = false.
                    pos++
                    val digit = peek()
                    if (digit == '0' || digit == '1') {
                        pos++
                        digit == '0'
                    } else {
                        null
                    }
                }
                't' -> consumeWord("true", true)
                'f' -> consumeWord("false", false)
                'n' -> consumeWord("null", null)
                else -> parseNumber()
            }
        }

        private fun parseObject(): Map<String, Any?> {
            val result = LinkedHashMap<String, Any?>()
            pos++ // {
            while (true) {
                skipSpace()
                if (peek() == '}') {
                    pos++
                    return result
                }
                val key = when (peek()) {
                    '"', '\'' -> parseString()
                    else -> parseBareKey()
                } ?: return result
                skipSpace()
                if (peek() == ':') pos++
                result[key] = parseValue()
                skipSpace()
                if (peek() == ',') {
                    pos++
                    continue
                }
                if (peek() == '}') {
                    pos++
                    return result
                }
                return result
            }
        }

        private fun parseArray(): List<Any?> {
            val result = mutableListOf<Any?>()
            pos++ // [
            while (true) {
                skipSpace()
                if (peek() == ']') {
                    pos++
                    return result
                }
                result += parseValue()
                skipSpace()
                if (peek() == ',') {
                    pos++
                    continue
                }
                if (peek() == ']') {
                    pos++
                    return result
                }
                return result
            }
        }

        private fun parseString(): String {
            val quote = text[pos]
            pos++
            val builder = StringBuilder()
            while (pos < text.length) {
                val c = text[pos]
                if (c == '\\' && pos + 1 < text.length) {
                    val escaped = text[pos + 1]
                    builder.append(
                        when (escaped) {
                            'n' -> '\n'
                            't' -> '\t'
                            'r' -> '\r'
                            'u' -> {
                                val hex = text.substring(
                                    (pos + 2).coerceAtMost(text.length),
                                    (pos + 6).coerceAtMost(text.length)
                                )
                                val code = hex.toIntOrNull(16)
                                if (code != null) {
                                    pos += 4
                                    code.toChar()
                                } else {
                                    escaped
                                }
                            }
                            else -> escaped
                        }
                    )
                    pos += 2
                    continue
                }
                if (c == quote) {
                    pos++
                    return builder.toString()
                }
                builder.append(c)
                pos++
            }
            return builder.toString()
        }

        private fun parseBareKey(): String? {
            val start = pos
            while (pos < text.length &&
                (text[pos].isLetterOrDigit() || text[pos] == '_' ||
                    text[pos] == '$')
            ) {
                pos++
            }
            return if (pos > start) text.substring(start, pos) else null
        }

        private fun parseNumber(): Double? {
            val start = pos
            while (pos < text.length &&
                (text[pos].isDigit() || text[pos] in "-+.eE")
            ) {
                pos++
            }
            if (pos == start) return null
            return text.substring(start, pos).toDoubleOrNull()
        }

        private fun consumeWord(word: String, value: Any?): Any? {
            return if (text.startsWith(word, pos)) {
                pos += word.length
                value
            } else {
                null
            }
        }

        private fun peek(): Char? = text.getOrNull(pos)

        private fun skipSpace() {
            while (pos < text.length && text[pos].isWhitespace()) pos++
        }
    }
}
