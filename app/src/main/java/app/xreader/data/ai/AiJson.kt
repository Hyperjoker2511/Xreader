package app.xreader.data.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * Pulling structured data out of model output (PLAN §7 risk: "AI JSON
 * unreliability"). Models wrap JSON in prose or code fences, trail
 * commas, and truncate. Strategy: find the outermost JSON value in the
 * text and parse leniently; callers validate the shape against their
 * own schema and treat anything else as a plain failure (with one
 * repair retry where the feature allows it) — never a crash.
 */
object AiJson {

    @PublishedApi
    internal val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    val parser: Json get() = json

    /** The first balanced JSON object/array embedded in [text], parsed. */
    fun extract(text: String): JsonElement? {
        val start = text.indexOfFirst { it == '{' || it == '[' }
        if (start < 0) return null
        val candidate = balancedSlice(text, start) ?: return null
        return runCatching { json.parseToJsonElement(candidate) }.getOrNull()
    }

    fun extractObject(text: String): JsonObject? =
        extract(text) as? JsonObject

    /** Decode an extracted element into a serializable type. */
    inline fun <reified T> decode(element: JsonElement): T? =
        runCatching { json.decodeFromJsonElement<T>(element) }.getOrNull()

    /** The slice from [start] to its matching close bracket, honouring
     * strings and escapes. Null when the text ends first (truncated). */
    fun balancedSlice(text: String, start: Int): String? {
        val open = text[start]
        val close = if (open == '{') '}' else ']'
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                open -> depth++
                close -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
        }
        return null
    }
}

/** A string field, tolerating numbers/bools by stringifying them. */
fun JsonObject.stringField(key: String): String? =
    (this[key] as? JsonPrimitive)?.content
        ?.trim()
        ?.ifBlank { null }

fun JsonObject.stringListField(key: String): List<String> =
    (this[key] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { element ->
        (element as? JsonPrimitive)?.content
            ?.trim()
            ?.ifBlank { null }
    } ?: emptyList()

fun JsonObject.objectListField(key: String): List<JsonObject> =
    (this[key] as? kotlinx.serialization.json.JsonArray)?.mapNotNull {
        it as? JsonObject
    } ?: emptyList()
