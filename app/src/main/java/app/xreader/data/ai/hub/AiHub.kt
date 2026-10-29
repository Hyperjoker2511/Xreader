package app.xreader.data.ai.hub

import app.xreader.core.error.AppError
import app.xreader.core.error.AppResult
import app.xreader.core.security.KeyVault
import app.xreader.data.ai.AiClient
import app.xreader.data.ai.GeminiClient
import app.xreader.data.net.HttpTransport
import app.xreader.data.prefs.AiPrefs
import app.xreader.data.prefs.AiProviderPrefs
import app.xreader.data.prefs.AiSettings
import app.xreader.data.prefs.EnginePick
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * The AI hub (v1.0.3): the one place that knows every provider, and
 * the [AiClient] every feature actually talks to.
 *
 * Resolution, per call (settings are read live, so a change in
 * Providers takes effect on the next call, never needs a restart):
 *  1. Build the provider states (registry order; configured = key
 *     saved, or a model named for Ollama).
 *  2. [EngineRouting.order] applies the picking rule: Automatic →
 *     every configured provider in order; Specific → only the pick.
 *  3. Automatic tries each in turn until one answers, and the
 *     result's `modelUsed` names the provider that answered.
 *     Specific returns the picked provider's result — success OR
 *     failure — untouched: a specific pick is used as picked, and
 *     its failure is reported plainly, never silently re-routed.
 *
 * Images are the one capability asymmetry: only Gemini makes them.
 * Automatic routes image calls to Gemini when it is configured;
 * a Specific non-Gemini pick gets the client's plain capability
 * error, not a quiet switch.
 */
class AiHub(
    private val transport: HttpTransport,
    private val keyVault: KeyVault,
    private val aiPrefs: AiPrefs,
    private val providerPrefs: AiProviderPrefs,
    /** Builds the Gemini engine; a non-null override pins the model
     * (a Specific Gemini pick), null uses the live model choice. */
    private val geminiFactory: (pickerOverride: String?) -> GeminiClient
) {

    val discovery: ProviderDiscovery = ProviderDiscovery(transport)

    /** The client features use. Safe to hold forever: it resolves
     * the real engine per call. */
    val client: AiClient = HubClient()

    // -- state ----------------------------------------------------------------

    /** Live provider states for the Providers screen and chips. */
    fun statesFlow(): Flow<List<EngineRouting.ProviderState>> = combine(
        aiPrefs.settings,
        providerPrefs.data,
        keyVault.presentKeys
    ) { settings, providerData, presentKeys ->
        buildStates(settings, providerData.customs, providerData, presentKeys)
    }

    suspend fun states(): List<EngineRouting.ProviderState> {
        val settings = aiPrefs.settings.first()
        val providerData = providerPrefs.data.first()
        return buildStates(
            settings, providerData.customs, providerData, keyVault.presentKeys.value
        )
    }

    /** The engine chip's text, kept live for every AI surface. */
    val chipLabel: Flow<String> = combine(
        aiPrefs.settings, statesFlow()
    ) { settings, states ->
        EngineRouting.chipLabel(settings.enginePick, states)
    }

    private fun buildStates(
        settings: AiSettings,
        customs: List<CustomProvider>,
        providerData: app.xreader.data.prefs.AiProviderData,
        presentKeys: Set<String>
    ): List<EngineRouting.ProviderState> =
        AiProviders.allDefs(customs).map { def ->
            EngineRouting.ProviderState(
                id = def.id,
                name = def.name,
                configured = isConfigured(def, settings, presentKeys),
                model = modelFor(def, settings, providerData)
            )
        }

    private fun isConfigured(
        def: AiProviderDef,
        settings: AiSettings,
        presentKeys: Set<String>
    ): Boolean = when (def.protocol) {
        AiProtocol.OLLAMA -> settings.ollamaModel.isNotBlank()
        else -> def.keyVaultName != null && def.keyVaultName in presentKeys
    }

    /** The model a call to [def] would use: a Specific pick wins,
     * then the user's per-provider choice, then the default. */
    private fun modelFor(
        def: AiProviderDef,
        settings: AiSettings,
        providerData: app.xreader.data.prefs.AiProviderData
    ): String? {
        val pick = settings.enginePick
        if (pick is EnginePick.Specific && pick.providerId == def.id &&
            pick.model.isNotBlank()
        ) {
            return pick.model
        }
        if (def.protocol == AiProtocol.GEMINI) return settings.modelChoice
        if (def.protocol == AiProtocol.OLLAMA) {
            return settings.ollamaModel.ifBlank { null }
        }
        return providerData.chosenModels[def.id]
            ?: providerData.models[def.id]?.firstOrNull()
            ?: def.defaultModel
    }

    // -- engine construction ----------------------------------------------------

    /** The concrete client for one provider state, honouring the
     * current pick for its model. */
    suspend fun engineFor(state: EngineRouting.ProviderState): AiClient {
        val settings = aiPrefs.settings.first()
        val providerData = providerPrefs.data.first()
        val def = AiProviders.defFor(state.id, providerData.customs)
            ?: error("engineFor on an unknown provider: ${state.id}")
        return engineFor(def, settings, providerData)
    }

    private fun engineFor(
        def: AiProviderDef,
        settings: AiSettings,
        providerData: app.xreader.data.prefs.AiProviderData
    ): AiClient = when (def.protocol) {
        AiProtocol.GEMINI -> {
            val pick = settings.enginePick
            if (pick is EnginePick.Specific && pick.providerId == def.id &&
                pick.model.isNotBlank()
            ) {
                geminiFactory(pick.model)
            } else {
                geminiFactory(null)
            }
        }
        AiProtocol.OPENAI_COMPATIBLE -> OpenAiCompatibleClient(
            transport = transport,
            serviceId = def.name,
            baseUrl = def.baseUrl,
            keyProvider = { def.keyVaultName?.let { keyVault.get(it) } },
            modelProvider = { modelFor(def, aiPrefs.settings.first(), providerPrefs.data.first()) }
        )
        AiProtocol.ANTHROPIC -> AnthropicClient(
            transport = transport,
            serviceId = def.name,
            baseUrl = def.baseUrl,
            keyProvider = { def.keyVaultName?.let { keyVault.get(it) } },
            modelProvider = { modelFor(def, aiPrefs.settings.first(), providerPrefs.data.first()) }
        )
        AiProtocol.OLLAMA -> OllamaClient(
            transport = transport,
            baseUrlProvider = { aiPrefs.settings.first().ollamaBaseUrl },
            modelProvider = { aiPrefs.settings.first().ollamaModel.ifBlank { null } }
        )
    }

    // -- key testing --------------------------------------------------------------

    /**
     * Proves a key against the provider's own model list BEFORE it
     * is saved (the ReTranslate "Test & save" rule): Ok carries the
     * models the key can see. Gemini keeps its own probe (its client
     * is constructed directly by the caller, as before).
     */
    suspend fun fetchModels(
        def: AiProviderDef,
        key: String
    ): AppResult<List<ProviderDiscovery.ModelInfo>> = when (def.protocol) {
        AiProtocol.OPENAI_COMPATIBLE -> OpenAiCompatibleClient(
            transport, def.name, def.baseUrl, { key }, { null }
        ).listModels(key)
        AiProtocol.ANTHROPIC -> AnthropicClient(
            transport, def.name, def.baseUrl, { key }, { null }
        ).listModels(key)
        AiProtocol.OLLAMA -> OllamaClient(
            transport,
            baseUrlProvider = { aiPrefs.settings.first().ollamaBaseUrl },
            modelProvider = { null }
        ).listModels().mapOk { names ->
            names.map { ProviderDiscovery.ModelInfo(it, listedFree = true) }
        }
        AiProtocol.GEMINI -> AppResult.Err(
            AppError.BadFile("Gemini keys are tested from the Gemini card.")
        )
    }

    // -- the delegating client ------------------------------------------------------

    private inner class HubClient : AiClient {

        override suspend fun hasKey(): Boolean {
            val settings = aiPrefs.settings.first()
            val states = states()
            return EngineRouting.order(settings.enginePick, states)
                .any { it.configured }
        }

        override suspend fun generateText(
            prompt: String,
            system: String?,
            jsonMode: Boolean,
            temperature: Float?,
            maxOutputTokens: Int?
        ): AppResult<GeminiClient.TextResult> {
            val settings = aiPrefs.settings.first()
            val states = states()
            val ordered = EngineRouting.order(settings.enginePick, states)
            if (ordered.isEmpty()) {
                return AppResult.Err(stalePickOrEmptyError(settings.enginePick))
            }
            val specific = EngineRouting.isSpecific(settings.enginePick)
            var lastError: AppError? = null
            for (state in ordered) {
                if (!state.configured) {
                    val error = AiHttp.noKeyError(state.name)
                    if (specific) return AppResult.Err(error)
                    lastError = error
                    continue
                }
                val engine = engineFor(state)
                when (val result = engine.generateText(
                    prompt, system, jsonMode, temperature, maxOutputTokens
                )) {
                    is AppResult.Ok -> return AppResult.Ok(
                        result.value.copy(
                            modelUsed = "${state.name} · ${result.value.modelUsed}"
                        )
                    )
                    is AppResult.Err -> {
                        if (specific) return result
                        lastError = result.error
                    }
                }
            }
            return AppResult.Err(
                lastError ?: AppError.AuthFailed(
                    "AI", "no AI provider is set up yet — add a key in Providers & keys"
                )
            )
        }

        override fun generateTextStream(
            prompt: String,
            system: String?,
            temperature: Float?
        ): Flow<GeminiClient.StreamEvent> = flow {
            val settings = aiPrefs.settings.first()
            val states = states()
            val ordered = EngineRouting.order(settings.enginePick, states)
            if (ordered.isEmpty()) {
                emit(
                    GeminiClient.StreamEvent.Failed(
                        stalePickOrEmptyError(settings.enginePick)
                    )
                )
                return@flow
            }
            val specific = EngineRouting.isSpecific(settings.enginePick)
            var lastError: AppError? = null
            var answered = false
            for (state in ordered) {
                if (answered) break
                var emittedDelta = false
                var failedBeforeDelta: AppError? = null
                var finished = false
                engineFor(state).generateTextStream(prompt, system, temperature)
                    .collect { event ->
                        when (event) {
                            is GeminiClient.StreamEvent.Delta -> {
                                emittedDelta = true
                                emit(event)
                            }
                            is GeminiClient.StreamEvent.Done -> {
                                finished = true
                                answered = true
                                emit(
                                    GeminiClient.StreamEvent.Done(
                                        "${state.name} · ${event.modelUsed}"
                                    )
                                )
                            }
                            is GeminiClient.StreamEvent.Failed -> {
                                if (emittedDelta || specific) {
                                    emit(event)
                                    answered = true
                                } else {
                                    failedBeforeDelta = event.error
                                }
                            }
                        }
                    }
                if (finished) answered = true
                if (!answered && failedBeforeDelta != null) {
                    lastError = failedBeforeDelta
                }
                if (!answered && failedBeforeDelta == null && !finished) {
                    // A stream that ended with neither Done nor Failed:
                    // treat as answered to avoid duplicating prose.
                    answered = true
                }
            }
            if (!answered) {
                emit(
                    GeminiClient.StreamEvent.Failed(
                        lastError ?: AppError.ProviderDown("AI", retryable = false)
                    )
                )
            }
        }.flowOn(kotlinx.coroutines.Dispatchers.IO)

        override suspend fun generateImage(
            prompt: String
        ): AppResult<GeminiClient.ImageResult> {
            val settings = aiPrefs.settings.first()
            val states = states()
            val pick = settings.enginePick
            if (pick is EnginePick.Specific) {
                val state = states.firstOrNull { it.id == pick.providerId }
                    ?: return AppResult.Err(stalePickOrEmptyError(pick))
                // The picked engine is used as picked: a non-Gemini
                // pick returns its own plain capability error.
                return engineFor(state).generateImage(prompt)
            }
            val gemini = states.firstOrNull { it.id == AiProviders.GEMINI_ID }
            if (gemini == null || !gemini.configured) {
                return AppResult.Err(
                    AppError.AuthFailed(
                        "Google Gemini",
                        "concept art needs a Gemini key — add one in Providers & keys"
                    )
                )
            }
            return engineFor(gemini).generateImage(prompt)
        }

        private fun stalePickOrEmptyError(pick: EnginePick): AppError =
            if (pick is EnginePick.Specific) {
                AppError.BadFile(
                    "The AI provider you picked isn't there anymore — choose " +
                        "another engine in Providers & keys."
                )
            } else {
                AppError.AuthFailed(
                    "AI", "no AI provider is set up yet — add a key in Providers & keys"
                )
            }
    }
}

/** Small local map for result types (AppResult has no map helper). */
private suspend fun <T, R> AppResult<T>.mapOk(
    transform: suspend (T) -> R
): AppResult<R> = when (this) {
    is AppResult.Ok -> AppResult.Ok(transform(value))
    is AppResult.Err -> this
}
