package app.xreader.ui.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.xreader.core.error.AppResult
import app.xreader.core.error.ErrorText
import app.xreader.core.security.KeyVault
import app.xreader.data.ai.GeminiClient
import app.xreader.data.ai.hub.AiProtocol
import app.xreader.data.ai.hub.AiProviderDef
import app.xreader.data.ai.hub.AiProviders
import app.xreader.data.ai.hub.CustomProvider
import app.xreader.data.ai.hub.ProviderDiscovery
import app.xreader.data.ai.hub.ProviderUrls
import app.xreader.data.prefs.AiFeature
import app.xreader.data.prefs.AiSettings
import app.xreader.data.prefs.EnginePick
import app.xreader.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One provider's card + engine-row state. */
data class ProviderCardState(
    val def: AiProviderDef,
    val configured: Boolean,
    /** Model ids this provider listed (empty until a key test or a
     * test-drive fetched them; Gemini's cascade is handled by the
     * screen's own rows). */
    val models: List<String> = emptyList(),
    /** Subset of [models] the provider itself lists as free. */
    val freeModels: Set<String> = emptySet(),
    /** The model Automatic routing would use for this provider. */
    val chosenModel: String? = null
)

/** The add-a-provider flow's phases — a provider exists only after
 * CONFIRMED_KEY + an explicit Add tap. */
enum class AddPhase { IDLE, CHECKING, NEEDS_KEY, CONFIRMED_KEY, FAILED }

data class AddProviderUiState(
    val phase: AddPhase = AddPhase.IDLE,
    val message: String? = null,
    /** Set when the test-drive confirmed the site's shape. */
    val baseUrl: String? = null,
    val protocol: AiProtocol? = null,
    val models: List<ProviderDiscovery.ModelInfo> = emptyList()
)

data class ProvidersUiState(
    val settings: AiSettings = AiSettings(),
    val providers: List<ProviderCardState> = emptyList(),
    /** Provider id currently being key-tested, if any. */
    val testingId: String? = null,
    val testingOllama: Boolean = false,
    val statusMessage: String? = null,
    val statusIsError: Boolean = false,
    val add: AddProviderUiState = AddProviderUiState()
) {
    val geminiConfigured: Boolean
        get() = providers.firstOrNull { it.def.id == AiProviders.GEMINI_ID }
            ?.configured == true
}

/**
 * Providers & keys (v1.0.3, the AI hub): every engine in one place.
 *
 * The rules this VM enforces:
 *  - "Test & save" everywhere: a key is proven against the provider
 *    (Gemini's cascade probe; the others' own model lists) BEFORE it
 *    is stored, so a typo'd key never gets saved as if it worked.
 *  - Adding a provider by site runs the hub's stateless test-drive;
 *    nothing is persisted until a key validates AND the user taps
 *    Add. A failed check adds nothing — there is no path around it.
 *  - Removing a provider removes its key with it, and a pick that
 *    pointed at it falls back to Automatic in the open (the user is
 *    told), never to a silent substitute engine.
 */
class ProvidersViewModel(private val container: AppContainer) : ViewModel() {

    private data class LocalState(
        val testingId: String? = null,
        val testingOllama: Boolean = false,
        val statusMessage: String? = null,
        val statusIsError: Boolean = false,
        val add: AddProviderUiState = AddProviderUiState(),
        /** The key from the add-flow's successful check, held only
         * until Add (or the flow is abandoned). Never persisted
         * anywhere else, never logged. */
        val pendingAddKey: String? = null
    )

    private val local = MutableStateFlow(LocalState())

    val state: StateFlow<ProvidersUiState> = combine(
        container.keyVault.presentKeys,
        container.aiPrefs.settings,
        container.aiProviderPrefs.data,
        local
    ) { presentKeys, settings, providerData, localState ->
        val defs = AiProviders.allDefs(providerData.customs)
        ProvidersUiState(
            settings = settings,
            providers = defs.map { def ->
                ProviderCardState(
                    def = def,
                    configured = when (def.protocol) {
                        AiProtocol.OLLAMA -> settings.ollamaModel.isNotBlank()
                        else -> def.keyVaultName != null &&
                            def.keyVaultName in presentKeys
                    },
                    models = providerData.models[def.id] ?: emptyList(),
                    freeModels = providerData.freeModels[def.id]
                        ?.toSet() ?: emptySet(),
                    chosenModel = providerData.chosenModels[def.id]
                        ?: providerData.models[def.id]?.firstOrNull()
                        ?: def.defaultModel
                )
            },
            testingId = localState.testingId,
            testingOllama = localState.testingOllama,
            statusMessage = localState.statusMessage,
            statusIsError = localState.statusIsError,
            add = localState.add
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProvidersUiState())

    private fun say(message: String, isError: Boolean = false) {
        local.value = local.value.copy(
            statusMessage = message, statusIsError = isError
        )
    }

    // -- Gemini (its own probe: the model cascade) ------------------------------

    /** Tests the entered key live; saves it only when a model answers. */
    fun testAndSave(enteredKey: String) {
        val key = enteredKey.trim()
        if (key.isBlank()) {
            say("Paste your Gemini key first.", isError = true)
            return
        }
        local.value = local.value.copy(
            testingId = AiProviders.GEMINI_ID, statusMessage = null
        )
        viewModelScope.launch {
            val probe = GeminiClient(
                transport = container.httpTransport,
                keyProvider = { key },
                pickerProvider = { container.aiPrefs.settings.first().modelChoice }
            )
            when (val result = probe.testKey()) {
                is AppResult.Ok -> {
                    container.keyVault.put(KeyVault.GEMINI_KEY, key)
                    local.value = local.value.copy(testingId = null)
                    say(
                        "Gemini key saved — ${result.value.modelUsed} answered. " +
                            "AI features are on."
                    )
                }
                is AppResult.Err -> {
                    local.value = local.value.copy(testingId = null)
                    say(
                        "That key didn't work, so it wasn't saved.\n" +
                            ErrorText.displayText(result.error),
                        isError = true
                    )
                }
            }
        }
    }

    // -- Keyed providers (OpenAI / Claude / APInex / customs) --------------------

    /** Tests the key against the provider's own model list; on
     * success saves the key AND remembers the models it can see. */
    fun testAndSaveKey(providerId: String, enteredKey: String) {
        val key = enteredKey.trim()
        if (key.isBlank()) {
            say("Paste the key first.", isError = true)
            return
        }
        viewModelScope.launch {
            val providerData = container.aiProviderPrefs.data.first()
            val def = AiProviders.defFor(providerId, providerData.customs)
            if (def == null) {
                say("That provider isn't there anymore.", isError = true)
                return@launch
            }
            local.value = local.value.copy(
                testingId = providerId, statusMessage = null
            )
            when (val result = container.aiHub.fetchModels(def, key)) {
                is AppResult.Ok -> {
                    val vaultName = def.keyVaultName
                    if (vaultName != null) container.keyVault.put(vaultName, key)
                    val ids = result.value.map { it.id }
                    val free = result.value.filter { it.listedFree }.map { it.id }
                    container.aiProviderPrefs.setModels(def.id, ids, free)
                    if (container.aiProviderPrefs.data.first()
                            .chosenModels[def.id] == null
                    ) {
                        val firstChoice = result.value.firstOrNull { it.listedFree }
                            ?: result.value.firstOrNull()
                        firstChoice?.let {
                            container.aiProviderPrefs.setChosenModel(def.id, it.id)
                        }
                    }
                    local.value = local.value.copy(testingId = null)
                    say(
                        "${def.name} key saved — it can see ${ids.size} models" +
                            if (free.isNotEmpty()) {
                                ", ${free.size} of them listed as free by ${def.name}."
                            } else {
                                "."
                            }
                    )
                }
                is AppResult.Err -> {
                    local.value = local.value.copy(testingId = null)
                    say(
                        "That key didn't work, so it wasn't saved.\n" +
                            ErrorText.displayText(result.error),
                        isError = true
                    )
                }
            }
        }
    }

    /** Removes a built-in provider's key (its models memory goes too). */
    fun removeKey(providerId: String) {
        viewModelScope.launch {
            val providerData = container.aiProviderPrefs.data.first()
            val def = AiProviders.defFor(providerId, providerData.customs)
                ?: return@launch
            def.keyVaultName?.let { container.keyVault.remove(it) }
            if (def.id == AiProviders.GEMINI_ID) {
                say("Gemini key removed. AI features that need it are off; reading is unaffected.")
            } else {
                say("${def.name} key removed.")
            }
        }
    }

    // -- Ollama --------------------------------------------------------------------

    fun saveOllama(baseUrl: String, model: String) {
        viewModelScope.launch {
            container.aiPrefs.setOllama(baseUrl, model)
            say("Ollama settings saved.")
        }
    }

    /** Proves the address answers and remembers the models it hosts. */
    fun testOllama(baseUrl: String, model: String) {
        viewModelScope.launch {
            container.aiPrefs.setOllama(baseUrl, model)
            local.value = local.value.copy(testingOllama = true, statusMessage = null)
            val def = AiProviders.defFor(
                AiProviders.OLLAMA_ID,
                container.aiProviderPrefs.data.first().customs
            )!!
            when (val result = container.aiHub.fetchModels(def, key = "")) {
                is AppResult.Ok -> {
                    val names = result.value.map { it.id }
                    container.aiProviderPrefs.setModels(
                        AiProviders.OLLAMA_ID, names, names
                    )
                    local.value = local.value.copy(testingOllama = false)
                    say(
                        if (names.isEmpty()) {
                            "Ollama answered, but it has no models yet — " +
                                "pull one on the computer first."
                        } else {
                            "Ollama answered — it has ${names.size} model(s): " +
                                names.take(4).joinToString(", ") +
                                if (names.size > 4) "…" else ""
                        }
                    )
                }
                is AppResult.Err -> {
                    local.value = local.value.copy(testingOllama = false)
                    say(
                        "Couldn't reach Ollama at that address.\n" +
                            ErrorText.displayText(result.error),
                        isError = true
                    )
                }
            }
        }
    }

    fun setOllamaModel(model: String) {
        viewModelScope.launch {
            val settings = container.aiPrefs.settings.first()
            container.aiPrefs.setOllama(settings.ollamaBaseUrl, model)
        }
    }

    // -- The engine pick --------------------------------------------------------------

    fun setEngineAutomatic() {
        viewModelScope.launch {
            container.aiPrefs.setEnginePick(EnginePick.Automatic)
            say("Engine set to Automatic — Xreader routes across your providers.")
        }
    }

    /** Pins one provider. The model starts as that provider's
     * current choice (Gemini: its model choice). */
    fun setEngineSpecific(providerId: String) {
        viewModelScope.launch {
            val settings = container.aiPrefs.settings.first()
            val providerData = container.aiProviderPrefs.data.first()
            val def = AiProviders.defFor(providerId, providerData.customs)
                ?: return@launch
            val model = when (def.protocol) {
                AiProtocol.GEMINI -> settings.modelChoice
                AiProtocol.OLLAMA -> settings.ollamaModel
                else -> providerData.chosenModels[def.id]
                    ?: providerData.models[def.id]?.firstOrNull()
                    ?: def.defaultModel
                    ?: ""
            }
            container.aiPrefs.setEnginePick(
                EnginePick.Specific(providerId, model)
            )
            say("Engine set to ${def.name} — its answers (and its failures) are the ones you'll see.")
        }
    }

    /** The model for a Specific pick; for Gemini this also updates
     * the long-standing model choice (one memory, two doors). */
    fun setEngineModel(providerId: String, model: String) {
        viewModelScope.launch {
            val providerData = container.aiProviderPrefs.data.first()
            val def = AiProviders.defFor(providerId, providerData.customs)
                ?: return@launch
            if (def.protocol == AiProtocol.GEMINI) {
                container.aiPrefs.setModelChoice(model)
            } else if (def.protocol == AiProtocol.OLLAMA) {
                val settings = container.aiPrefs.settings.first()
                container.aiPrefs.setOllama(settings.ollamaBaseUrl, model)
            } else {
                container.aiProviderPrefs.setChosenModel(providerId, model)
            }
            val settings = container.aiPrefs.settings.first()
            if (settings.enginePick is EnginePick.Specific &&
                settings.enginePick.providerId == providerId
            ) {
                container.aiPrefs.setEnginePick(
                    EnginePick.Specific(providerId, model)
                )
            }
        }
    }

    /** The Gemini model choice used under Automatic (kept from the
     * original screen; also what sync carries). */
    fun setModelChoice(choice: String) {
        viewModelScope.launch { container.aiPrefs.setModelChoice(choice) }
    }

    fun setFeature(feature: AiFeature, enabled: Boolean) {
        viewModelScope.launch { container.aiPrefs.setFeatureEnabled(feature, enabled) }
    }

    // -- Add a provider by site ---------------------------------------------------------

    /** Runs the test-drive. With no key this can confirm a public
     * model list or land on NEEDS_KEY; with a key, a confirmed list
     * proves the key too. NOTHING is saved by checking. */
    fun checkSite(siteInput: String, keyInput: String) {
        val site = siteInput.trim()
        if (site.isBlank()) {
            local.value = local.value.copy(
                add = AddProviderUiState(
                    phase = AddPhase.FAILED,
                    message = "Paste the site's address first."
                )
            )
            return
        }
        local.value = local.value.copy(
            add = AddProviderUiState(phase = AddPhase.CHECKING),
            pendingAddKey = null
        )
        viewModelScope.launch {
            val key = keyInput.trim().ifBlank { null }
            when (val probe = container.aiHub.discovery.probe(site, key)) {
                is ProviderDiscovery.Probe.Confirmed -> {
                    if (probe.keyValidated) {
                        local.value = local.value.copy(
                            add = AddProviderUiState(
                                phase = AddPhase.CONFIRMED_KEY,
                                baseUrl = probe.baseUrl,
                                protocol = probe.protocol,
                                models = probe.models,
                                message = null
                            ),
                            pendingAddKey = key
                        )
                    } else {
                        local.value = local.value.copy(
                            add = AddProviderUiState(
                                phase = AddPhase.NEEDS_KEY,
                                baseUrl = probe.baseUrl,
                                protocol = probe.protocol,
                                models = probe.models,
                                message = "That site answers like an AI provider. " +
                                    "Now paste your key from it — the key is " +
                                    "checked before anything is added."
                            )
                        )
                    }
                }
                is ProviderDiscovery.Probe.NeedsKey -> {
                    local.value = local.value.copy(
                        add = AddProviderUiState(
                            phase = AddPhase.NEEDS_KEY,
                            baseUrl = probe.baseUrl,
                            protocol = probe.protocolGuess,
                            message = "That looks like an AI provider — it needs " +
                                "a key to confirm. Paste your key from the site " +
                                "and check again."
                        )
                    )
                }
                is ProviderDiscovery.Probe.NotProvider -> {
                    local.value = local.value.copy(
                        add = AddProviderUiState(
                            phase = AddPhase.FAILED,
                            message = probe.reason
                        )
                    )
                }
            }
        }
    }

    /** The Add tap — the ONLY write in the whole add-flow. */
    fun addCheckedProvider() {
        val addState = local.value.add
        val key = local.value.pendingAddKey
        val baseUrl = addState.baseUrl
        val protocol = addState.protocol
        if (addState.phase != AddPhase.CONFIRMED_KEY ||
            key == null || baseUrl == null || protocol == null
        ) {
            say("Check the site with its key first — then Add works.", isError = true)
            return
        }
        viewModelScope.launch {
            val id = AiProviders.newCustomId(System.currentTimeMillis())
            val name = ProviderUrls.displayNameFor(baseUrl)
                .replaceFirstChar { it.uppercase() }
            val custom = CustomProvider(
                id = id, name = name, baseUrl = baseUrl, protocol = protocol
            )
            container.aiProviderPrefs.addCustom(custom)
            container.keyVault.put(KeyVault.providerKey(id), key)
            val ids = addState.models.map { it.id }
            val free = addState.models.filter { it.listedFree }.map { it.id }
            container.aiProviderPrefs.setModels(id, ids, free)
            val firstChoice = addState.models.firstOrNull { it.listedFree }
                ?: addState.models.firstOrNull()
            firstChoice?.let { container.aiProviderPrefs.setChosenModel(id, it.id) }
            local.value = local.value.copy(
                add = AddProviderUiState(),
                pendingAddKey = null
            )
            say(
                "Added $name — it's in the engine list now, with " +
                    "${ids.size} models" +
                    if (free.isNotEmpty()) {
                        ", ${free.size} listed as free by $name."
                    } else {
                        "."
                    }
            )
        }
    }

    fun cancelAdd() {
        local.value = local.value.copy(
            add = AddProviderUiState(), pendingAddKey = null
        )
    }

    /** Removes a user-added provider: its entry, its models, and
     * its key. A pick pointing at it returns to Automatic — in the
     * open, with a message, never a silent substitute. */
    fun removeCustomProvider(providerId: String) {
        viewModelScope.launch {
            val providerData = container.aiProviderPrefs.data.first()
            val custom = providerData.customs.firstOrNull { it.id == providerId }
                ?: return@launch
            container.aiProviderPrefs.removeCustom(providerId)
            container.keyVault.remove(KeyVault.providerKey(providerId))
            val settings = container.aiPrefs.settings.first()
            val pick = settings.enginePick
            if (pick is EnginePick.Specific && pick.providerId == providerId) {
                container.aiPrefs.setEnginePick(EnginePick.Automatic)
                say(
                    "Removed ${custom.name} and its key. It was your picked " +
                        "engine, so the engine is back to Automatic."
                )
            } else {
                say("Removed ${custom.name} and its key.")
            }
        }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    ProvidersViewModel(container) as T
            }
    }
}
