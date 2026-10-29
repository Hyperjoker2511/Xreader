package app.xreader.data.ai.hub

import app.xreader.core.error.AppError
import app.xreader.core.error.AppResult
import app.xreader.data.ai.AiClient
import app.xreader.data.ai.GeminiClient
import app.xreader.data.net.HttpTransport
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
 * An [AiClient] for the OpenAI wire shape (v1.0.3): `POST
 * {base}/chat/completions` with a Bearer key. One class serves the
 * built-in OpenAI provider, the APInex gateway, and every user-added
 * OpenAI-compatible gateway — the protocol is the contract, the base
 * URL is the only difference.
 *
 * The key is read per call via [keyProvider] and travels only in the
 * Authorization header — never in a URL, never stored here.
 */
class OpenAiCompatibleClient(
    private val transport: HttpTransport,
    /** Display name used as the error vocabulary's service id. */
    private val serviceId: String,
    private val baseUrl: String,
    private val keyProvider: suspend () -> String?,
    private val modelProvider: suspend () -> String?
) : AiClient {

    override suspend fun hasKey(): Boolean = !keyProvider().isNullOrBlank()

    override suspend fun generateText(
        prompt: String,
        system: String?,
        jsonMode: Boolean,
        temperature: Float?,
        maxOutputTokens: Int?
    ): AppResult<GeminiClient.TextResult> {
        val key = keyProvider()?.takeIf { it.isNotBlank() }
            ?: return AppResult.Err(AiHttp.noKeyError(serviceId))
        val model = modelProvider()?.takeIf { it.isNotBlank() }
            ?: return AppResult.Err(AiHttp.noModelError(serviceId))
        val request = HttpTransport.Request(
            url = chatUrl(),
            method = "POST",
            headers = headers(key),
            body = requestBody(
                model = model,
                prompt = prompt,
                system = system,
                jsonMode = jsonMode,
                temperature = temperature,
                maxOutputTokens = maxOutputTokens,
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
            return AppResult.Err(AiHttp.httpError(serviceId, response.code, bodyText))
        }
        val text = parseText(bodyText)
        if (text.isNullOrBlank()) {
            return AppResult.Err(
                AppError.ProviderDown(
                    serviceId, retryable = false,
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
        val key = keyProvider()?.takeIf { it.isNotBlank() }
        if (key == null) {
            emit(GeminiClient.StreamEvent.Failed(AiHttp.noKeyError(serviceId)))
            return@flow
        }
        val model = modelProvider()?.takeIf { it.isNotBlank() }
        if (model == null) {
            emit(GeminiClient.StreamEvent.Failed(AiHttp.noModelError(serviceId)))
            return@flow
        }
        val request = HttpTransport.Request(
            url = chatUrl(),
            method = "POST",
            headers = headers(key),
            body = requestBody(
                model = model,
                prompt = prompt,
                system = system,
                jsonMode = false,
                temperature = temperature,
                maxOutputTokens = null,
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
                    AiHttp.httpError(serviceId, response.code, errorBody)
                )
            )
            return@flow
        }
        val reader = response.body?.let {
            BufferedReader(InputStreamReader(it, Charsets.UTF_8))
        }
        if (reader == null) {
            emit(GeminiClient.StreamEvent.Failed(AppError.ProviderDown(serviceId)))
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
            serviceId, retryable = false,
            detail = "$serviceId can't create images — concept art uses Google Gemini"
        )
    )

    /** The provider's own model list, fetched with [key] (or the
     * saved key when null) — used by "Test & save" and the
     * add-a-provider test-drive. */
    suspend fun listModels(key: String?): AppResult<List<ProviderDiscovery.ModelInfo>> {
        val effectiveKey = key ?: keyProvider()
        val request = HttpTransport.Request(
            url = modelsUrl(),
            method = "GET",
            headers = if (effectiveKey.isNullOrBlank()) {
                emptyMap()
            } else {
                mapOf("Authorization" to "Bearer $effectiveKey")
            }
        )
        val response = when (val result = transport.execute(request)) {
            is AppResult.Err -> return AppResult.Err(result.error.withService())
            is AppResult.Ok -> result.value
        }
        val bodyText = readBody(response)
        response.body?.close()
        if (response.code !in 200..299) {
            return AppResult.Err(AiHttp.httpError(serviceId, response.code, bodyText))
        }
        return AppResult.Ok(parseModels(bodyText))
    }

    // -- plumbing -------------------------------------------------------------

    private fun chatUrl(): String = "${baseUrl.trimEnd('/')}/chat/completions"

    private fun modelsUrl(): String = "${baseUrl.trimEnd('/')}/models"

    private fun headers(key: String): Map<String, String> = mapOf(
        "Authorization" to "Bearer $key",
        "Content-Type" to "application/json"
    )

    private fun readBody(response: HttpTransport.Response): String =
        runCatching {
            response.body?.readBytes()?.toString(Charsets.UTF_8) ?: ""
        }.getOrDefault("")

    private fun AppError.withService(): AppError = when (this) {
        is AppError.ProviderDown -> copy(serviceId = serviceId)
        is AppError.RateLimited -> copy(serviceId = serviceId)
        is AppError.AuthFailed -> copy(serviceId = serviceId)
        else -> this
    }

    companion object {

        private val json = Json { ignoreUnknownKeys = true }

        fun requestBody(
            model: String,
            prompt: String,
            system: String?,
            jsonMode: Boolean,
            temperature: Float?,
            maxOutputTokens: Int?,
            stream: Boolean
        ): String {
            val root = buildJsonObject {
                put("model", model)
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
                if (jsonMode) {
                    put("response_format", buildJsonObject {
                        put("type", "json_object")
                    })
                }
                if (temperature != null) put("temperature", temperature)
                if (maxOutputTokens != null) put("max_tokens", maxOutputTokens)
                if (stream) put("stream", true)
            }
            return root.toString()
        }

        /** `choices[0].message.content` — a plain string on most
         * gateways, an array of text parts on some. */
        fun parseText(bodyJson: String): String? {
            val root = runCatching { json.parseToJsonElement(bodyJson) as? JsonObject }
                .getOrNull() ?: return null
            val choices = root["choices"] as? JsonArray ?: return null
            val first = choices.firstOrNull() as? JsonObject ?: return null
            val message = first["message"] as? JsonObject ?: return null
            return when (val content = message["content"]) {
                is JsonPrimitive -> content.content.ifBlank { null }
                is JsonArray -> content.mapNotNull { part ->
                    ((part as? JsonObject)?.get("text") as? JsonPrimitive)?.content
                }.joinToString("").ifBlank { null }
                else -> null
            }
        }

        /** `data[]` → model infos, with the free badge applied from
         * the provider's own listing fields. */
        fun parseModels(bodyJson: String): List<ProviderDiscovery.ModelInfo> {
            val root = runCatching { json.parseToJsonElement(bodyJson) as? JsonObject }
                .getOrNull() ?: return emptyList()
            val data = root["data"] as? JsonArray ?: return emptyList()
            return data.mapNotNull { element ->
                val entry = element as? JsonObject ?: return@mapNotNull null
                val id = (entry["id"] as? JsonPrimitive)?.content
                    ?.trim()?.ifBlank { null } ?: return@mapNotNull null
                ProviderDiscovery.ModelInfo(
                    id = id,
                    listedFree = FreeBadge.isListedFree(id, entry)
                )
            }
        }

        /** One SSE line → its text delta (null for keep-alives,
         * `[DONE]`, and non-content chunks like reasoning traces). */
        fun parseStreamDelta(line: String): String? {
            val trimmed = line.trim()
            if (!trimmed.startsWith("data:")) return null
            val payload = trimmed.removePrefix("data:").trim()
            if (payload.isEmpty() || payload == "[DONE]") return null
            val root = runCatching { json.parseToJsonElement(payload) as? JsonObject }
                .getOrNull() ?: return null
            val choices = root["choices"] as? JsonArray ?: return null
            val first = choices.firstOrNull() as? JsonObject ?: return null
            val delta = first["delta"] as? JsonObject ?: return null
            return (delta["content"] as? JsonPrimitive)?.content
        }
    }
}
