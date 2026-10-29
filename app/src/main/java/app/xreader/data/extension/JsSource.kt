package app.xreader.data.extension

import app.xreader.extensionapi.Source
import app.xreader.extensionapi.SourceBook
import app.xreader.extensionapi.SourceBookPage
import app.xreader.extensionapi.SourceChapter
import app.xreader.extensionapi.SourceChapterContent
import app.xreader.extensionapi.SourceException
import kotlinx.serialization.json.Json

/**
 * The JS runtime seam. The sandbox JVM can't execute QuickJS native
 * code, so everything above this line (bridge, adapter, result
 * parsing) is fixture-tested against fakes, and the native
 * implementation ([QuickJsRuntime]) is device-bound — stated plainly
 * in the phase report.
 */
interface JsRuntime : AutoCloseable {
    /** Install the host bridge dispatcher (method, argsJson) -> json. */
    fun installBridge(bridge: (String, String) -> String)

    /** Load the extension's script (defines the source functions). */
    fun loadBundle(script: String)

    /** Invoke a top-level function with JSON-encodable arguments;
     * returns the JSON string the function returned. */
    fun callFunction(name: String, argsJson: String): String

    override fun close()
}

/** The bootstrap installed into every JS context before the bundle:
 * a tiny `host` facade over the raw bridge, and the `__invoker` the
 * host calls into. Nothing else exists in the context — no require,
 * no filesystem, no timers, no Android. */
object JsBootstrap {
    const val SCRIPT = """
        (function () {
          var rawCall = globalThis.__hostCall;
          globalThis.host = {
            get: function (url, headers) {
              return JSON.parse(rawCall("http.get", JSON.stringify({ url: url, headers: headers || {} })));
            },
            post: function (url, body, contentType, headers) {
              return JSON.parse(rawCall("http.post", JSON.stringify({
                url: url, body: body, contentType: contentType || "application/x-www-form-urlencoded",
                headers: headers || {}
              })));
            },
            select: function (html, baseUrl, selector) {
              return JSON.parse(rawCall("html.select", JSON.stringify({
                html: html, baseUrl: baseUrl || "", selector: selector
              })));
            },
            storeGet: function (key) {
              return JSON.parse(rawCall("store.get", JSON.stringify({ key: key })));
            },
            storePut: function (key, value) {
              rawCall("store.put", JSON.stringify({ key: key, value: value }));
            },
            log: function (message) {
              rawCall("log", JSON.stringify({ message: String(message) }));
            }
          };
          globalThis.__invoker = {
            invoke: function (name, argsJson) {
              var fn = globalThis[name];
              if (typeof fn !== "function") {
                throw new Error("this script doesn't provide '" + name + "'");
              }
              var args = JSON.parse(argsJson);
              var result = fn.apply(null, args);
              return JSON.stringify(result === undefined ? null : result);
            }
          };
        })();
    """
}

/**
 * A [Source] backed by a JS bundle (single-source packages, §5):
 * the bundle defines top-level `search`, `popular`, `latest`,
 * `details`, `chapters`, `chapterContent` functions returning plain
 * JSON-shaped values. Every call is deadline-guarded by the bridge
 * and routed through the JS host facade — the script never touches
 * the network except via `host.get/post`.
 */
class JsSource(
    override val id: String,
    override val name: String,
    override val lang: String,
    override val baseUrl: String,
    private val runtime: JsRuntime
) : Source {

    private val json = Json { ignoreUnknownKeys = true }

    override fun search(query: String, page: Int): SourceBookPage =
        parseBookPage(call("search", query, page))

    override fun popular(page: Int): SourceBookPage =
        parseBookPage(call("popular", page))

    override fun latest(page: Int): SourceBookPage =
        parseBookPage(call("latest", page))

    override fun details(book: SourceBook): SourceBook {
        val result = callRaw("details", bookJson(book))
        val obj = parseJson(result).asObject()
            ?: throw SourceException("“$name” sent back details Xreader can't read")
        return book.copy(
            title = obj.str("title") ?: book.title,
            author = obj.str("author") ?: book.author,
            coverUrl = obj.str("coverUrl") ?: obj.str("cover") ?: book.coverUrl,
            description = obj.str("description") ?: book.description
        )
    }

    override fun chapters(book: SourceBook): List<SourceChapter> {
        val result = callRaw("chapters", bookJson(book))
        val array = parseJson(result).asArray()
            ?: throw SourceException("“$name” sent back a chapter list Xreader can't read")
        return array.mapNotNull { element ->
            val obj = element.asObject() ?: return@mapNotNull null
            val chapterId = obj.str("id") ?: return@mapNotNull null
            SourceChapter(
                id = chapterId,
                name = obj.strOr("name", chapterId),
                number = obj.float("number")
            )
        }
    }

    override fun chapterContent(chapter: SourceChapter): SourceChapterContent {
        val result = callRaw(
            "chapterContent",
            """{"id":${jsonString(chapter.id)},"name":${jsonString(chapter.name)}}"""
        )
        val element = parseJson(result)
        return when {
            element is kotlinx.serialization.json.JsonObject -> SourceChapterContent(
                html = element.str("html") ?: element.str("text") ?: "",
                title = element.str("title")
            )
            element is kotlinx.serialization.json.JsonPrimitive && element.isString ->
                SourceChapterContent(html = element.content)
            else -> throw SourceException("“$name” sent back a chapter Xreader can't read")
        }
    }

    private fun call(function: String, vararg args: Any): String =
        callRaw(function, args.joinToString(",", "[", "]") { argJson(it) })

    private fun callRaw(function: String, singleArgJson: String): String =
        callRawArgs(function, "[$singleArgJson]")

    private fun callRawArgs(function: String, argsJson: String): String =
        try {
            runtime.callFunction(function, argsJson)
        } catch (e: Exception) {
            throw SourceException(e.message ?: "the script failed")
        }

    private fun parseBookPage(result: String): SourceBookPage {
        val obj = parseJson(result).asObject()
            ?: throw SourceException("“$name” sent back a page Xreader can't read")
        val books = (obj["books"].asArray() ?: emptyList()).mapNotNull { element ->
            val bookObj = element.asObject() ?: return@mapNotNull null
            val bookId = bookObj.str("id") ?: return@mapNotNull null
            SourceBook(
                id = bookId,
                title = bookObj.strOr("title", bookId),
                author = bookObj.str("author"),
                coverUrl = bookObj.str("coverUrl") ?: bookObj.str("cover"),
                description = bookObj.str("description")
            )
        }
        return SourceBookPage(books, obj.bool("hasNextPage"))
    }

    private fun parseJson(raw: String) = runCatching {
        json.parseToJsonElement(raw)
    }.getOrElse { throw SourceException("“$name” sent back unreadable data") }

    private fun bookJson(book: SourceBook): String = buildString {
        append("{")
        append("\"id\":").append(jsonString(book.id)).append(',')
        append("\"title\":").append(jsonString(book.title)).append(',')
        append("\"author\":").append(book.author?.let { jsonString(it) } ?: "null").append(',')
        append("\"coverUrl\":").append(book.coverUrl?.let { jsonString(it) } ?: "null")
        append("}")
    }

    private fun argJson(arg: Any): String = when (arg) {
        is String -> jsonString(arg)
        is Number, is Boolean -> arg.toString()
        else -> jsonString(arg.toString())
    }

    private fun jsonString(value: String): String =
        kotlinx.serialization.json.JsonPrimitive(value).toString()
}
