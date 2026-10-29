package app.xreader.data.ai

import app.xreader.core.error.AppError
import app.xreader.core.error.AppResult
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
 * The one Gemini client (PLAN §7). Every AI feature goes through here
 * so the model cascade, the error vocabulary, and the Details line
 * (HTTP code + Google's own message, ≤200 chars) exist exactly once.
 *
 * The key comes from the KeyVault via [keyProvider] — it is read per
 * call, held only for the request, and never stored, logged, or put
 * in a URL (it travels in the `x-goog-api-key` header).
 */
class GeminiClient(
    private val transport: HttpTransport,
    private val keyProvider: suspend () -> String?,
    private val pickerProvider: suspend () -> String = { GeminiModels.AUTO }
) : AiClient {

    data class TextResult(val text: String, val modelUsed: String)

    data class ImageResult(val bytes: ByteArray, val mimeType: String, val modelUsed: String) {
        override fun equals(other: Any?): Boolean =
            other is ImageResult && mimeType == other.mimeType &&
                modelUsed == other.modelUsed && bytes.contentEquals(other.bytes)

        override fun hashCode(): Int =
            31 * (31 * bytes.contentHashCode() + mimeType.hashCode()) + modelUsed.hashCode()
    }

    sealed interface StreamEvent {
        data class Delta(val text: String) : StreamEvent
        data class Done(val modelUsed: String) : StreamEvent
        data class Failed(val error: AppError) : StreamEvent
    }

    /** The text model that last answered this session (cascade memory). */
    @Volatile
    var sessionTextModel: String? = null
        private set

    @Volatile
    private var sessionImageModel: String? = null

    override suspend fun hasKey(): Boolean = !keyProvider().isNullOrBlank()

    // -- Text ---------------------------------------------------------------

    override suspend fun generateText(
        prompt: String,
        system: String? = null,
        jsonMode: Boolean = false,
        temperature: Float? = null,
        maxOutputTokens: Int? = null
    ): AppResult<TextResult> {
        val key = keyProvider()?.takeIf { it.isNotBlank() }
            ?: return AppResult.Err(noKeyError())
        val body = requestBody(
            turns = listOf(Turn("user", prompt)),
            system = system,
            jsonMode = jsonMode,
            temperature = temperature,
            maxOutputTokens = maxOutputTokens,
            imageModalities = false
        )
        val order = GeminiModels.attemptOrder(pickerProvider(), sessionTextModel)
        var lastModelError: AppError? = null
        for (model in order) {
            when (val outcome = callOnce(model, key, body, stream = false)) {
                is CallOutcome.Success -> {
                    val text = parseText(outcome.bodyJson)
                    if (text.isNullOrBlank()) {
                        return AppResult.Err(
                            AppError.ProviderDown(
                                SERVICE_ID, retryable = false,
                                detail = "the model returned no text (it may have declined this request)"
                            )
                        )
                    }
                    sessionTextModel = model
                    return AppResult.Ok(TextResult(text, model))
                }
                is CallOutcome.ModelUnavailable -> {
                    lastModelError = outcome.error
                    continue
                }
                is CallOutcome.Failure -> return AppResult.Err(outcome.error)
            }
        }
        return AppResult.Err(
            lastModelError ?: AppError.ProviderDown(SERVICE_ID, retryable = false)
        )
    }

    /**
     * Streaming text (Lore Chat, RAW translation). The cascade applies
     * until the first token: a model-404 steps to the next model, any
     * other failure ends the stream with [StreamEvent.Failed].
     */
    override fun generateTextStream(
        prompt: String,
        system: String? = null,
        temperature: Float? = null
    ): Flow<StreamEvent> = flow {
        val key = keyProvider()?.takeIf { it.isNotBlank() }
        if (key == null) {
            emit(StreamEvent.Failed(noKeyError()))
            return@flow
        }
        val body = requestBody(
            turns = listOf(Turn("user", prompt)),
            system = system,
            jsonMode = false,
            temperature = temperature,
            maxOutputTokens = null,
            imageModalities = false
        )
        val order = GeminiModels.attemptOrder(pickerProvider(), sessionTextModel)
        var lastModelError: AppError? = null
        for (model in order) {
            val response = when (
                val result = transport.execute(streamRequest(model, key, body))
            ) {
                is AppResult.Err -> {
                    emit(StreamEvent.Failed(result.error.withService()))
                    return@flow
                }
                is AppResult.Ok -> result.value
            }
            if (response.code !in 200..299) {
                val errorBody = readBody(response)
                response.body?.close()
                if (GeminiModels.isModelUnavailable(response.code, errorBody)) {
                    lastModelError = httpError(response.code, errorBody)
                    continue
                }
                emit(StreamEvent.Failed(httpError(response.code, errorBody)))
                return@flow
            }
            sessionTextModel = model
            val reader = response.body?.let {
                BufferedReader(InputStreamReader(it, Charsets.UTF_8))
            }
            if (reader == null) {
                emit(StreamEvent.Failed(AppError.ProviderDown(SERVICE_ID)))
                return@flow
            }
            reader.use { lines ->
                while (true) {
                    val line = lines.readLine() ?: break
                    val delta = parseStreamDelta(line) ?: continue
                    if (delta.isNotEmpty()) emit(StreamEvent.Delta(delta))
                }
            }
            emit(StreamEvent.Done(model))
            return@flow
        }
        emit(
            StreamEvent.Failed(
                lastModelError ?: AppError.ProviderDown(SERVICE_ID, retryable = false)
            )
        )
    }.flowOn(Dispatchers.IO)

    // -- Images ---------------------------------------------------------------

    override suspend fun generateImage(prompt: String): AppResult<ImageResult> {
        val key = keyProvider()?.takeIf { it.isNotBlank() }
            ?: return AppResult.Err(noKeyError())
        val body = requestBody(
            turns = listOf(Turn("user", prompt)),
            system = null,
            jsonMode = false,
            temperature = null,
            maxOutputTokens = null,
            imageModalities = true
        )
        val order = GeminiModels.attemptOrderForImages(sessionImageModel)
        var lastModelError: AppError? = null
        for (model in order) {
            when (val outcome = callOnce(model, key, body, stream = false)) {
                is CallOutcome.Success -> {
                    val image = parseImage(outcome.bodyJson)
                    if (image == null) {
                        return AppResult.Err(
                            AppError.ProviderDown(
                                SERVICE_ID, retryable = false,
                                detail = "the model answered with words instead of a picture"
                            )
                        )
                    }
                    sessionImageModel = model
                    return AppResult.Ok(
                        ImageResult(image.first, image.second, model)
                    )
                }
                is CallOutcome.ModelUnavailable -> {
                    lastModelError = outcome.error
                    continue
                }
                is CallOutcome.Failure -> return AppResult.Err(outcome.error)
            }
        }
        return AppResult.Err(
            lastModelError ?: AppError.ProviderDown(SERVICE_ID, retryable = false)
        )
    }

    /** A one-word round trip used by "Test & save" on the key screen. */
    suspend fun testKey(): AppResult<TextResult> =
        generateText(prompt = "Reply with the single word: ready", maxOutputTokens = 16)

    // -- Plumbing -------------------------------------------------------------

    data class Turn(val role: String, val text: String)

    private sealed interface CallOutcome {
        data class Success(val bodyJson: String) : CallOutcome
        data class ModelUnavailable(val error: AppError) : CallOutcome
        data class Failure(val error: AppError) : CallOutcome
    }

    private suspend fun callOnce(
        model: String,
        key: String,
        body: String,
        stream: Boolean
    ): CallOutcome {
        val request = HttpTransport.Request(
            url = endpoint(model, stream),
            method = "POST",
            headers = mapOf(
                "x-goog-api-key" to key,
                "Content-Type" to "application/json"
            ),
            body = body
        )
        val response = when (val result = transport.execute(request)) {
            is AppResult.Err -> return CallOutcome.Failure(result.error.withService())
            is AppResult.Ok -> result.value
        }
        val bodyText = readBody(response)
        response.body?.close()
        if (response.code in 200..299) return CallOutcome.Success(bodyText)
        if (GeminiModels.isModelUnavailable(response.code, bodyText)) {
            return CallOutcome.ModelUnavailable(httpError(response.code, bodyText))
        }
        return CallOutcome.Failure(httpError(response.code, bodyText))
    }

    private fun readBody(response: HttpTransport.Response): String =
        runCatching {
            response.body?.readBytes()?.toString(Charsets.UTF_8) ?: ""
        }.getOrDefault("")

    /** Maps an HTTP failure onto the app's error vocabulary, keeping
     * Google's own words in the Details line (PLAN §1.5). */
    fun httpError(code: Int, body: String?): AppError {
        val message = GeminiModels.googleMessage(body)
        val detail = "HTTP $code" + (message?.let { ": $it" } ?: "")
        return when {
            GeminiModels.isKeyInvalid(code, body) || code == 401 ->
                AppError.AuthFailed(SERVICE_ID, detail)
            code == 403 -> AppError.AuthFailed(SERVICE_ID, detail)
            code == 429 -> AppError.RateLimited(SERVICE_ID)
            code in 500..599 -> AppError.ProviderDown(SERVICE_ID, code, true, detail)
            else -> AppError.ProviderDown(SERVICE_ID, code, false, detail)
        }
    }

    private fun AppError.withService(): AppError = when (this) {
        is AppError.ProviderDown -> copy(serviceId = SERVICE_ID)
        is AppError.RateLimited -> copy(serviceId = SERVICE_ID)
        is AppError.AuthFailed -> copy(serviceId = SERVICE_ID)
        else -> this
    }

    private fun noKeyError(): AppError =
        AppError.AuthFailed(SERVICE_ID, "no Gemini key is saved on this device")

    private fun streamRequest(model: String, key: String, body: String) =
        HttpTransport.Request(
            url = endpoint(model, stream = true),
            method = "POST",
            headers = mapOf(
                "x-goog-api-key" to key,
                "Content-Type" to "application/json"
            ),
            body = body
        )

    private fun endpoint(model: String, stream: Boolean): String =
        if (stream) {
            "$API_BASE/models/$model:streamGenerateContent?alt=sse"
        } else {
            "$API_BASE/models/$model:generateContent"
        }

    private fun requestBody(
        turns: List<Turn>,
        system: String?,
        jsonMode: Boolean,
        temperature: Float?,
        maxOutputTokens: Int?,
        imageModalities: Boolean
    ): String {
        val root = buildJsonObject {
            put("contents", buildJsonArray {
                turns.forEach { turn ->
                    add(buildJsonObject {
                        put("role", turn.role)
                        put("parts", buildJsonArray {
                            add(buildJsonObject { put("text", turn.text) })
                        })
                    })
                }
            })
            if (system != null) {
                put("systemInstruction", buildJsonObject {
                    put("parts", buildJsonArray {
                        add(buildJsonObject { put("text", system) })
                    })
                })
            }
            val hasConfig = jsonMode || temperature != null ||
                maxOutputTokens != null || imageModalities
            if (hasConfig) {
                put("generationConfig", buildJsonObject {
                    if (jsonMode) put("responseMimeType", "application/json")
                    if (temperature != null) put("temperature", temperature)
                    if (maxOutputTokens != null) put("maxOutputTokens", maxOutputTokens)
                    if (imageModalities) {
                        put("responseModalities", buildJsonArray {
                            add(JsonPrimitive("TEXT"))
                            add(JsonPrimitive("IMAGE"))
                        })
                    }
                })
            }
        }
        return root.toString()
    }

    companion object {
        const val SERVICE_ID = "Gemini"
        const val API_BASE = "https://generativelanguage.googleapis.com/v1beta"

        private val json = Json { ignoreUnknownKeys = true }

        /** All text parts of the first candidate, joined. */
        fun parseText(bodyJson: String): String? {
            val root = runCatching { json.parseToJsonElement(bodyJson) as? JsonObject }
                .getOrNull() ?: return null
            val parts = firstCandidateParts(root) ?: return null
            val text = parts.mapNotNull { part ->
                ((part as? JsonObject)?.get("text") as? JsonPrimitive)?.content
            }.joinToString("")
            return text.ifBlank { null }
        }

        /** The first inline image part as (bytes, mimeType). */
        fun parseImage(bodyJson: String): Pair<ByteArray, String>? {
            val root = runCatching { json.parseToJsonElement(bodyJson) as? JsonObject }
                .getOrNull() ?: return null
            val parts = firstCandidateParts(root) ?: return null
            for (part in parts) {
                val inline = (part as? JsonObject)?.get("inlineData") as? JsonObject
                    ?: continue
                val data = (inline["data"] as? JsonPrimitive)?.content ?: continue
                val mime = (inline["mimeType"] as? JsonPrimitive)?.content ?: "image/png"
                val bytes = runCatching {
                    java.util.Base64.getDecoder().decode(data)
                }.getOrNull() ?: continue
                return bytes to mime
            }
            return null
        }

        /** One SSE line → its text delta (null for keep-alives/end). */
        fun parseStreamDelta(line: String): String? {
            val trimmed = line.trim()
            if (!trimmed.startsWith("data:")) return null
            val payload = trimmed.removePrefix("data:").trim()
            if (payload.isEmpty() || payload == "[DONE]") return null
            return parseText(payload)
        }

        private fun firstCandidateParts(root: JsonObject): JsonArray? {
            val candidates = root["candidates"] as? JsonArray ?: return null
            val first = candidates.firstOrNull() as? JsonObject ?: return null
            val content = first["content"] as? JsonObject ?: return null
            return content["parts"] as? JsonArray
        }
    }
}
