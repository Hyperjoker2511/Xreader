package app.xreader.data.ai

import app.xreader.core.error.AppResult
import kotlinx.coroutines.flow.Flow

/**
 * The one shape every AI engine in the app speaks (v1.0.3, AI hub).
 *
 * Until now every feature talked to [GeminiClient] directly, which
 * made Gemini the only possible engine. Features now depend on this
 * interface instead: GeminiClient implements it, the OpenAI-compatible
 * / Anthropic / Ollama clients in `data.ai.hub` implement it, and the
 * hub's delegating client implements it — so a feature cannot tell
 * (and does not care) which provider answered. The result types stay
 * the ones GeminiClient defined, so existing callers and tests keep
 * their shapes.
 *
 * Contract every implementation honours:
 *  - [hasKey] answers "can this engine run at all right now" (a key
 *    is saved / the engine is configured) without making a request.
 *  - Failures come back as [app.xreader.core.error.AppError] in the
 *    app's vocabulary — never raw exceptions, never silent fallbacks.
 *  - [generateImage] is a real capability only of image-capable
 *    engines; the others return a plain capability error rather than
 *    pretending or silently switching engines.
 */
interface AiClient {

    suspend fun hasKey(): Boolean

    suspend fun generateText(
        prompt: String,
        system: String? = null,
        jsonMode: Boolean = false,
        temperature: Float? = null,
        maxOutputTokens: Int? = null
    ): AppResult<GeminiClient.TextResult>

    fun generateTextStream(
        prompt: String,
        system: String? = null,
        temperature: Float? = null
    ): Flow<GeminiClient.StreamEvent>

    suspend fun generateImage(
        prompt: String
    ): AppResult<GeminiClient.ImageResult>
}
