package app.xreader.data.ai.hub

import app.xreader.core.error.AppError
import app.xreader.core.error.AppResult
import app.xreader.data.ai.AiClient
import app.xreader.data.ai.GeminiClient
import app.xreader.data.net.HttpTransport
import app.xreader.data.net.LocalHttpPolicy
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * An [AiClient] for Ollama (v1.0.3): a model server on the user's own
 * computer (`POST {base}/api/chat`). No key exists in this world —
 * "configured" means the user named a model; the address and model
 * are plain settings read per call, so editing them in Providers
 * takes effect immediately.
 *
 * An unreachable computer is the normal failure here, and the
 * transport's own error (NoNetwork / ProviderDown) is what surfaces —
 * plainly, with no pretending another engine answered.
 */
class OllamaClient(
    private val transport: HttpTransport,
    private val baseUrlProvider: suspend () -> String,
    private val modelProvider: suspend () -> String?
) : AiClient {

    override suspend fun hasKey(): Boolean =
        !modelProvider().isNullOrBlank()

    override suspend fun generateText(
        prompt: String,
        system: String?,
        jsonMode: Boolean,
        temperature: Float?,
        maxOutputTokens: Int?
    ): AppResult<GeminiClient.TextResult> {
        val model = modelProvider()?.takeIf { it.isNotBlank() }
            ?: return AppResult.Err(AiHttp.noModelError(SERVICE_ID))
        val url = chatUrl(baseUrlProvider())
        // v1.3.5 (P0): plain http only to the user's own network.
        LocalHttpPolicy.refusalFor(url)?.let { return AppResult.Err(it) }
        val request = HttpTransport.Request(
            url = url,
            method = "POST",
            headers = mapOf("Content-Type" to "application/json"),
            body = requestBody(
                model = model,
                prompt = prompt,
                system = system,
                jsonMode = jsonMode,
                temperature = temperature,
                stream = false
            )
        )
        val response = when (val result = transport.execute(request)) {
            is AppResult.Err -> return AppResult.Err(result.error.withService())
            is AppResult.Ok -> result.value
        }
        val bodyText = readBody(response)
        response.body?.close()
        if (response.code !in 200..299) {
            return AppResult.Err(AiHttp.httpError(SERVICE_ID, response.code, bodyText))
        }
        val text = parseText(bodyText)
        if (text.isNullOrBlank()) {
            return AppResult.Err(
                AppError.ProviderDown(
                    SERVICE_ID, retryable = false,
                    detail = "the model returned no text (it may have declined this request)"
                )
            )
        }
        return AppResult.Ok(GeminiClient.TextResult(text, model))
    }

    override fun generateTextStream(
        prompt: String,
        system: String?,
        temperature: Float?
    ): Flow<GeminiClient.StreamEvent> = flow {
        val model = modelProvider()?.takeIf { it.isNotBlank() }
        if (model == null) {
            emit(GeminiClient.StreamEvent.Failed(AiHttp.noModelError(SERVICE_ID)))
            return@flow
        }
        val url = chatUrl(baseUrlProvider())
        // v1.3.5 (P0): plain http only to the user's own network.
        LocalHttpPolicy.refusalFor(url)?.let {
            emit(GeminiClient.StreamEvent.Failed(it))
            return@flow
        }
        val request = HttpTransport.Request(
            url = url,
            method = "POST",
            headers = mapOf("Content-Type" to "application/json"),
            body = requestBody(
                model = model,
                prompt = prompt,
                system = system,
                jsonMode = false,
                temperature = temperature,
                stream = true
            )
        )
        val response = when (val result = transport.execute(request)) {
            is AppResult.Err -> {
                emit(GeminiClient.StreamEvent.Failed(result.error.withService()))
                return@flow
            }
            is AppResult.Ok -> result.value
        }
        if (response.code !in 200..299) {
            val errorBody = readBody(response)
            response.body?.close()
            emit(
                GeminiClient.StreamEvent.Failed(
                    AiHttp.httpError(SERVICE_ID, response.code, errorBody)
                )
            )
            return@flow
        }
        val reader = response.body?.let {
            BufferedReader(InputStreamReader(it, Charsets.UTF_8))
        }
        if (reader == null) {
            emit(GeminiClient.StreamEvent.Failed(AppError.ProviderDown(SERVICE_ID)))
            return@flow
        }
        reader.use { lines ->
            while (true) {
                val line = lines.readLine() ?: break
                val delta = parseStreamDelta(line) ?: continue
                if (delta.isNotEmpty()) {
                    emit(GeminiClient.StreamEvent.Delta(delta))
                }
            }
        }
        emit(GeminiClient.StreamEvent.Done(model))
    }.flowOn(Dispatchers.IO)

    override suspend fun generateImage(
        prompt: String
    ): AppResult<GeminiClient.ImageResult> = AppResult.Err(
        AppError.ProviderDown(
            SERVICE_ID, retryable = false,
            detail = "Ollama can't create images — concept art uses Google Gemini"
        )
    )

    /** Models the Ollama server itself reports (`GET /api/tags`) —
     * the Providers screen's "Test" for the address + model fields. */
    suspend fun listModels(): AppResult<List<String>> {
        val url = "${baseUrlProvider().trimEnd('/')}/api/tags"
        // v1.3.5 (P0): plain http only to the user's own network.
        LocalHttpPolicy.refusalFor(url)?.let { return AppResult.Err(it) }
        val request = HttpTransport.Request(
            url = url,
            method = "GET"
        )
        val response = when (val result = transport.execute(request)) {
            is AppResult.Err -> return AppResult.Err(result.error.withService())
            is AppResult.Ok -> result.value
        }
        val bodyText = readBody(response)
        response.body?.close()
        if (response.code !in 200..299) {
            return AppResult.Err(AiHttp.httpError(SERVICE_ID, response.code, bodyText))
        }
        return AppResult.Ok(parseModelNames(bodyText))
    }

    // -- plumbing -------------------------------------------------------------

    private fun chatUrl(base: String): String = "${base.trimEnd('/')}/api/chat"

    private fun readBody(response: HttpTransport.Response): String =
        runCatching {
            response.body?.readBytes()?.toString(Charsets.UTF_8) ?: ""
        }.getOrDefault("")

    private fun AppError.withService(): AppError = when (this) {
        is AppError.ProviderDown -> copy(serviceId = SERVICE_ID)
        is AppError.RateLimited -> copy(serviceId = SERVICE_ID)
        is AppError.AuthFailed -> copy(serviceId = SERVICE_ID)
        else -> this
    }

    companion object {

        const val SERVICE_ID = "Ollama"

        private val json = Json { ignoreUnknownKeys = true }

        fun requestBody(
            model: String,
            prompt: String,
            system: String?,
            jsonMode: Boolean,
            temperature: Float?,
            stream: Boolean
        ): String {
            val root = buildJsonObject {
                put("model", model)
                put("stream", stream)
                put("messages", buildJsonArray {
                    if (system != null) {
                        add(buildJsonObject {
                            put("role", "system")
                            put("content", system)
                        })
                    }
                    add(buildJsonObject {
                        put("role", "user")
                        put("content", prompt)
                    })
                })
                if (jsonMode) put("format", "json")
                if (temperature != null) {
                    put("options", buildJsonObject {
                        put("temperature", temperature)
                    })
                }
            }
            return root.toString()
        }

        /** `message.content` of a (non-streamed) chat reply. */
        fun parseText(bodyJson: String): String? {
            val root = runCatching { json.parseToJsonElement(bodyJson) as? JsonObject }
                .getOrNull() ?: return null
            val message = root["message"] as? JsonObject ?: return null
            return (message["content"] as? JsonPrimitive)?.content
                ?.ifBlank { null }
        }

        /** One NDJSON stream line → its content delta. */
        fun parseStreamDelta(line: String): String? {
            val trimmed = line.trim()
            if (!trimmed.startsWith("{")) return null
            val root = runCatching { json.parseToJsonElement(trimmed) as? JsonObject }
                .getOrNull() ?: return null
            val message = root["message"] as? JsonObject ?: return null
            return (message["content"] as? JsonPrimitive)?.content
        }

        fun parseModelNames(bodyJson: String): List<String> {
            val root = runCatching { json.parseToJsonElement(bodyJson) as? JsonObject }
                .getOrNull() ?: return emptyList()
            val models = root["models"] as? JsonArray ?: return emptyList()
            return models.mapNotNull { element ->
                ((element as? JsonObject)?.get("name") as? JsonPrimitive)?.content
                    ?.trim()?.ifBlank { null }
            }
        }
    }
}
