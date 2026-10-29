package app.xreader.data.extension

import app.xreader.extensionapi.HostDocument
import app.xreader.extensionapi.HostElement
import app.xreader.extensionapi.SourceException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The JS host bridge (PLAN §5/§9.1) — the whole of what a QuickJS
 * script can reach. Scripts run with NO ambient authority: no file
 * system, no Android classes, no network except through this
 * dispatcher, which forwards to the host facades (whitelist-enforced
 * HTTP, host-side HTML parsing, the extension's own store, logging).
 *
 * The dispatcher is pure Kotlin and fully unit-tested — including
 * against a hostile script's call patterns — because the QuickJS
 * native engine itself can't run on the test JVM.
 *
 * Wire format: `call(method, argsJson) -> resultJson`, or a thrown
 * [JsHostException] whose message the runtime surfaces to the script
 * as a JS Error. Unknown methods, malformed arguments, oversized
 * results and calls past the deadline ALL fail closed.
 */
class JsBridge(
    private val http: app.xreader.extensionapi.HostHttp,
    private val html: app.xreader.extensionapi.HostHtml,
    private val store: app.xreader.extensionapi.HostStore,
    private val log: app.xreader.extensionapi.HostLog,
    /** Milliseconds since the call started; the deadline is enforced
     * here so even a cooperative script can't chain calls forever. */
    private val elapsedMs: () -> Long = { 0 },
    private val deadlineMs: Long = DEFAULT_DEADLINE_MS
) {
    class JsHostException(message: String) : Exception(message)

    private val json = Json { ignoreUnknownKeys = true }

    fun call(method: String, argsJson: String): String {
        if (elapsedMs() > deadlineMs) {
            throw JsHostException("the script ran too long and was stopped")
        }
        val args = runCatching {
            json.parseToJsonElement(argsJson.ifBlank { "{}" }) as? JsonObject
        }.getOrNull() ?: throw JsHostException("bad arguments for $method")
        val result: JsonElement = try {
            when (method) {
                "http.get" -> httpGet(args)
                "http.post" -> httpPost(args)
                "html.select" -> htmlSelect(args)
                "store.get" -> JsonPrimitive(store.get(stringArg(args, "key")))
                "store.put" -> {
                    store.put(stringArg(args, "key"), stringArg(args, "value"))
                    JsonPrimitive(true)
                }
                "store.remove" -> {
                    store.remove(stringArg(args, "key"))
                    JsonPrimitive(true)
                }
                "log" -> {
                    log.log(stringArg(args, "message"))
                    JsonPrimitive(true)
                }
                else -> throw JsHostException(
                    "host has no '$method' — scripts can only use http, html, store and log"
                )
            }
        } catch (e: JsHostException) {
            throw e
        } catch (e: SourceException) {
            throw JsHostException(e.message ?: "the source failed")
        } catch (e: Exception) {
            throw JsHostException("host call failed (${e.javaClass.simpleName})")
        }
        val encoded = result.toString()
        if (encoded.length > MAX_RESULT_CHARS) {
            throw JsHostException("the script's result was too large")
        }
        return encoded
    }

    private fun httpGet(args: JsonObject): JsonElement {
        val response = http.get(stringArg(args, "url"), headersArg(args))
        return responseJson(response.status, response.body, response.finalUrl)
    }

    private fun httpPost(args: JsonObject): JsonElement {
        val response = http.post(
            stringArg(args, "url"),
            stringArg(args, "body"),
            (args["contentType"] as? JsonPrimitive)?.content
                ?: "application/x-www-form-urlencoded",
            headersArg(args)
        )
        return responseJson(response.status, response.body, response.finalUrl)
    }

    private fun responseJson(status: Int, body: String, finalUrl: String): JsonElement =
        buildJsonObject {
            put("status", JsonPrimitive(status))
            put("body", JsonPrimitive(body.take(MAX_BODY_CHARS)))
            put("finalUrl", JsonPrimitive(finalUrl))
        }

    private fun htmlSelect(args: JsonObject): JsonElement {
        val document: HostDocument = html.parse(
            stringArg(args, "html"), stringArg(args, "baseUrl", "")
        )
        val elements = document.select(stringArg(args, "selector"))
            .take(MAX_ELEMENTS)
        return buildJsonArray {
            for (element in elements) {
                add(elementJson(element))
            }
        }
    }

    private fun elementJson(element: HostElement): JsonElement = buildJsonObject {
        put("text", JsonPrimitive(element.text()))
        put("html", JsonPrimitive(element.html().take(MAX_ELEMENT_HTML_CHARS)))
        put("href", JsonPrimitive(element.attr("href") ?: ""))
        put("src", JsonPrimitive(element.attr("src") ?: ""))
    }

    private fun stringArg(args: JsonObject, name: String, default: String? = null): String {
        val value = (args[name] as? JsonPrimitive)?.content
        return value ?: default
            ?: throw JsHostException("missing '$name'")
    }

    private fun headersArg(args: JsonObject): Map<String, String> {
        val obj = args["headers"] as? JsonObject ?: return emptyMap()
        return obj.mapValues { (_, v) -> (v as? JsonPrimitive)?.content ?: "" }
    }

    companion object {
        const val DEFAULT_DEADLINE_MS = 30_000L
        const val MAX_RESULT_CHARS = 4_000_000
        const val MAX_BODY_CHARS = 2_000_000
        const val MAX_ELEMENTS = 2_000
        const val MAX_ELEMENT_HTML_CHARS = 200_000
    }
}

/** JSON helpers shared by the bridge and the JS source adapter. */
internal fun JsonElement?.asObject(): JsonObject? = this as? JsonObject
internal fun JsonElement?.asArray(): JsonArray? = this as? JsonArray
internal fun JsonObject.str(name: String): String? =
    (this[name] as? JsonPrimitive)?.content?.ifBlank { null }
internal fun JsonObject.strOr(name: String, default: String): String = str(name) ?: default
internal fun JsonObject.bool(name: String): Boolean =
    (this[name] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false
internal fun JsonObject.float(name: String): Float? =
    (this[name] as? JsonPrimitive)?.content?.toFloatOrNull()
