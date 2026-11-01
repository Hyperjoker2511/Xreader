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
 * An [AiClient] for the Anthropic wire shape (v1.0.3): `POST
 * {base}/v1/messages` with an `x-api-key` header. Serves the built-in
 * Claude provider and any user-added Anthropic-compatible gateway.
 *
 * Two shape notes, honestly recorded:
 *  - Anthropic requires `max_tokens` on every call, so a default
 *    applies when the caller doesn't set one.
 *  - There is no JSON-mode flag; [generateText]'s jsonMode is
 *    accepted and ignored — every feature's prompt already asks for
 *    JSON-only output, and replies are parsed tolerantly.
 */
class AnthropicClient(
    private val transport: HttpTransport,
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
        // v1.3.5 (P0): a key must never cross public plain http.
        LocalHttpPolicy.refusalFor(
            ProviderUrls.anthropicMessagesUrl(baseUrl)
        )?.let { return AppResult.Err(it) }
        val request = HttpTransport.Request(
            url = ProviderUrls.anthropicMessagesUrl(baseUrl),
            method = "POST",
            headers = headers(key),
            body = requestBody(
                model = model,
                prompt = prompt,
                system = system,
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
        // v1.3.5 (P0): a key must never cross public plain http.
        LocalHttpPolicy.refusalFor(
            ProviderUrls.anthropicMessagesUrl(baseUrl)
        )?.let {
            emit(GeminiClient.StreamEvent.Failed(it))
            return@flow
        }
        val request = HttpTransport.Request(
            url = ProviderUrls.anthropicMessagesUrl(baseUrl),
            method = "POST",
            headers = headers(key),
            body = requestBody(
                model = model,
                prompt = prompt,
                system = system,
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

    /** The provider's own model list (used by "Test & save" and the
     * add-a-provider test-drive). */
    suspend fun listModels(key: String?): AppResult<List<ProviderDiscovery.ModelInfo>> {
        // v1.3.5 (P0): a key must never cross public plain http.
        LocalHttpPolicy.refusalFor(
            ProviderUrls.anthropicModelsUrl(baseUrl)
        )?.let { return AppResult.Err(it) }
        val effectiveKey = key ?: keyProvider()
        val request = HttpTransport.Request(
            url = ProviderUrls.anthropicModelsUrl(baseUrl),
            method = "GET",
            headers = if (effectiveKey.isNullOrBlank()) {
                mapOf("anthropic-version" to API_VERSION)
            } else {
                headers(effectiveKey)
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

    private fun headers(key: String): Map<String, String> = mapOf(
        "x-api-key" to key,
        "anthropic-version" to API_VERSION,
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

        const val API_VERSION = "2023-06-01"

        /** Anthropic requires max_tokens; generous default so long
         * chapter work is never silently truncated by OUR cap. */
        const val DEFAULT_MAX_TOKENS = 8192

        private val json = Json { ignoreUnknownKeys = true }

        fun requestBody(
            model: String,
            prompt: String,
            system: String?,
            temperature: Float?,
            maxOutputTokens: Int?,
            stream: Boolean
        ): String {
            val root = buildJsonObject {
                put("model", model)
                put("max_tokens", maxOutputTokens ?: DEFAULT_MAX_TOKENS)
                if (system != null) put("system", system)
                put("messages", buildJsonArray {
                    add(buildJsonObject {
                        put("role", "user")
                        put("content", prompt)
                    })
                })
                if (temperature != null) put("temperature", temperature)
                if (stream) put("stream", true)
            }
            return root.toString()
        }

        /** The text blocks of the reply's `content` array, joined. */
        fun parseText(bodyJson: String): String? {
            val root = runCatching { json.parseToJsonElement(bodyJson) as? JsonObject }
                .getOrNull() ?: return null
            val content = root["content"] as? JsonArray ?: return null
            val text = content.mapNotNull { block ->
                val obj = block as? JsonObject ?: return@mapNotNull null
                if ((obj["type"] as? JsonPrimitive)?.content != "text") {
                    return@mapNotNull null
                }
                (obj["text"] as? JsonPrimitive)?.content
            }.joinToString("")
            return text.ifBlank { null }
        }

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

        /** One SSE line → text delta. Anthropic streams typed events;
         * only `content_block_delta` with a `text_delta` carries prose. */
        fun parseStreamDelta(line: String): String? {
            val trimmed = line.trim()
            if (!trimmed.startsWith("data:")) return null
            val payload = trimmed.removePrefix("data:").trim()
            if (payload.isEmpty()) return null
            val root = runCatching { json.parseToJsonElement(payload) as? JsonObject }
                .getOrNull() ?: return null
            if ((root["type"] as? JsonPrimitive)?.content != "content_block_delta") {
                return null
            }
            val delta = root["delta"] as? JsonObject ?: return null
            if ((delta["type"] as? JsonPrimitive)?.content != "text_delta") return null
            return (delta["text"] as? JsonPrimitive)?.content
        }
    }
}
