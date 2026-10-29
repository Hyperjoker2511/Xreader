package app.xreader.data.ai.hub

import app.xreader.core.error.AppResult
import app.xreader.data.net.HttpTransport

/**
 * The add-a-provider test-drive (v1.0.3, AI hub §K): paste a site,
 * and the app checks — live — whether it answers like an AI provider
 * before anything is added.
 *
 * The probe is STATELESS by construction: it takes no prefs, no
 * vault, no storage, so a failed or refused check provably adds
 * NOTHING. The caller persists a provider only from a [Confirmed]
 * result, and only after the user taps Add.
 *
 * Detection keys on the two standard wire shapes, never on site
 * names (no allow-list to drift): an OpenAI-shaped `/models` answer
 * (Bearer auth), then an Anthropic-shaped one (`x-api-key`). A 401
 * with no key yet is not a failure — it is the normal "needs a key"
 * state of a real gateway, and the flow asks for the key and probes
 * again; a 401 WITH a key means the key was refused, said plainly.
 */
class ProviderDiscovery(private val transport: HttpTransport) {

    /** One model from a provider's own list. */
    data class ModelInfo(val id: String, val listedFree: Boolean)

    sealed interface Probe {
        /** The site answered like an AI provider. [keyValidated] is
         * true when a key was supplied and the model list came back
         * with it — the key is proven, not just plausible. */
        data class Confirmed(
            val protocol: AiProtocol,
            val baseUrl: String,
            val models: List<ModelInfo>,
            val keyValidated: Boolean
        ) : Probe

        /** Something answered and demanded a key — a real gateway's
         * front door. Probe again with the key to confirm. */
        data class NeedsKey(
            val baseUrl: String,
            val protocolGuess: AiProtocol?
        ) : Probe

        /** Not added, and [reason] says why in plain words. */
        data class NotProvider(val reason: String) : Probe
    }

    suspend fun probe(rawInput: String, key: String?): Probe {
        val site = ProviderUrls.normaliseSiteInput(rawInput)
            ?: return Probe.NotProvider(
                "That doesn't look like a web address — check what you pasted."
            )
        var refusedBase: String? = null
        var refusedProtocol: AiProtocol? = null
        for (base in ProviderUrls.candidateBases(site)) {
            // OpenAI shape first — the commonest gateway dialect.
            when (val openAi = tryModels(base, anthropic = false, key = key)) {
                is Attempt.Confirmed ->
                    return Probe.Confirmed(
                        AiProtocol.OPENAI_COMPATIBLE, base, openAi.models,
                        keyValidated = !key.isNullOrBlank()
                    )
                Attempt.Refused -> {
                    if (refusedBase == null) {
                        refusedBase = base
                        refusedProtocol = AiProtocol.OPENAI_COMPATIBLE
                    }
                }
                Attempt.NotShape -> Unit
            }
            // Anthropic shape on the same base.
            when (val anthropic = tryModels(base, anthropic = true, key = key)) {
                is Attempt.Confirmed ->
                    return Probe.Confirmed(
                        AiProtocol.ANTHROPIC, base, anthropic.models,
                        keyValidated = !key.isNullOrBlank()
                    )
                Attempt.Refused -> {
                    if (refusedBase == null) {
                        refusedBase = base
                        refusedProtocol = AiProtocol.ANTHROPIC
                    }
                }
                Attempt.NotShape -> Unit
            }
        }
        return when {
            refusedBase != null && key.isNullOrBlank() ->
                Probe.NeedsKey(refusedBase, refusedProtocol)
            refusedBase != null ->
                Probe.NotProvider(
                    "The site answered, but it refused that key — check the " +
                        "key on the site and try again. Nothing was added."
                )
            else ->
                Probe.NotProvider(
                    "That site didn't answer like an AI provider. If the " +
                        "service has a separate API address, paste that " +
                        "instead. Nothing was added."
                )
        }
    }

    private sealed interface Attempt {
        data class Confirmed(val models: List<ModelInfo>) : Attempt
        data object Refused : Attempt
        data object NotShape : Attempt
    }

    /** One model-list request in one dialect. */
    private suspend fun tryModels(
        base: String,
        anthropic: Boolean,
        key: String?
    ): Attempt {
        val url = if (anthropic) {
            ProviderUrls.anthropicModelsUrl(base)
        } else {
            "${base.trimEnd('/')}/models"
        }
        val headers = buildMap {
            if (!key.isNullOrBlank()) {
                if (anthropic) {
                    put("x-api-key", key)
                    put("anthropic-version", AnthropicClient.API_VERSION)
                } else {
                    put("Authorization", "Bearer $key")
                }
            } else if (anthropic) {
                put("anthropic-version", AnthropicClient.API_VERSION)
            }
        }
        val response = when (
            val result = transport.execute(
                HttpTransport.Request(url = url, method = "GET", headers = headers)
            )
        ) {
            is AppResult.Err -> return Attempt.NotShape
            is AppResult.Ok -> result.value
        }
        val bodyText = runCatching {
            response.body?.readBytes()?.toString(Charsets.UTF_8) ?: ""
        }.getOrDefault("")
        response.body?.close()
        return when {
            response.code in 200..299 -> {
                val models = if (anthropic) {
                    AnthropicClient.parseModels(bodyText)
                } else {
                    OpenAiCompatibleClient.parseModels(bodyText)
                }
                if (models.isEmpty()) Attempt.NotShape else Attempt.Confirmed(models)
            }
            response.code == 401 || response.code == 403 -> Attempt.Refused
            else -> Attempt.NotShape
        }
    }
}
