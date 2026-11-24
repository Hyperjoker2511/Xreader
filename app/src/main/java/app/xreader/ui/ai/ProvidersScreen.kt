package app.xreader.ui.ai

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.xreader.data.ai.GeminiModels
import app.xreader.data.ai.hub.AiProtocol
import app.xreader.data.ai.hub.AiProviders
import app.xreader.data.prefs.AiFeature
import app.xreader.data.prefs.EnginePick
import app.xreader.data.prefs.ImageEngine
import app.xreader.data.prefs.VoiceEngine
import app.xreader.ui.common.GhostPill
import app.xreader.ui.common.GlassCard
import app.xreader.ui.common.NocturneChip
import app.xreader.ui.common.NocturneSwitch
import app.xreader.ui.common.PrimaryPill
import app.xreader.ui.common.SectionHeader
import app.xreader.ui.theme.LocalDockClearance
import app.xreader.ui.theme.LocalUiMetrics
import app.xreader.ui.theme.effectiveBottomPadding

/** Friendly names for the cascade (the ids stay in GeminiModels). */
fun geminiModelLabel(model: String): String = when (model) {
    "gemini-3.5-flash-lite" -> "Gemini 3.5 Flash Lite — fastest, lightest"
    "gemini-3.1-flash-lite" -> "Gemini 3.1 Flash Lite"
    "gemini-3.8-flash" -> "Gemini 3.8 Flash — stronger"
    "gemini-4-hydrogen" -> "Gemini 4 Hydrogen — deepest, heaviest"
    else -> model
}

/**
 * Providers & keys (screen #26, v1.0.3 the AI hub): the engine pick
 * at the top (Automatic, or one provider + model, used as picked),
 * one card per provider with Test & save keys, Ollama's address
 * fields, "Add a provider" by pasting a site (checked live before
 * anything is added), and the per-feature switches.
 *
 * Without any key this screen explains itself and the rest of the
 * app simply carries on — AI is an upgrade here, never a requirement.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ProvidersScreen(viewModel: ProvidersViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Code 121 W8 (R32, D-121-W7-03 retired): the Material 3
    // providers page — settings-list rows; each row's setup is
    // a full-screen dialog form. A raw key is never rendered:
    // key fields are password fields and saved keys show only
    // as "Key saved". Nocturne keeps the card page below.
    if (app.xreader.ui.theme.LocalDesignState.current.design !=
        app.xreader.data.prefs.AppDesign.NOCTURNE
    ) {
        M3ProvidersContent(state = state, viewModel = viewModel, onBack = onBack)
        return
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(start = 20.dp, end = 20.dp, top = LocalUiMetrics.current.topGap, bottom = 16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                "Providers & keys",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(start = 4.dp)
            )
        }
        Spacer(Modifier.height(8.dp))
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            state.statusMessage?.let { message ->
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (state.statusIsError) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.secondary
                    }
                )
            }

            EngineCard(state, viewModel)

            ImageEngineCard(state, viewModel)

            VoiceEngineCard(state, viewModel)

            state.providers.forEach { card ->
                when (card.def.protocol) {
                    AiProtocol.GEMINI -> GeminiCard(state, card, viewModel)
                    AiProtocol.OLLAMA -> OllamaCard(state, card, viewModel)
                    else -> KeyedProviderCard(card, viewModel)
                }
            }

            AddProviderCard(state, viewModel)

            GlassCard {
                SectionHeader(title = "AI features")
                Spacer(Modifier.height(4.dp))
                Text(
                    "Turn individual features off to save quota or keep things " +
                        "simple. They light up once any provider above is set up — " +
                        "nothing here is needed for reading.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                AiFeature.entries.forEach { feature ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            feature.label,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f)
                        )
                        NocturneSwitch(
                            checked = state.settings.enabled(feature),
                            onCheckedChange = { viewModel.setFeature(feature, it) },
                            enabled = true
                        )
                    }
                }
            }
            Spacer(
                Modifier.height(
                    effectiveBottomPadding(88.dp, LocalDockClearance.current)
                )
            )
        }
    }
}

/** The picking rule, as a card: Automatic, or one specific
 * provider + model. Unconfigured providers are listed but can't be
 * picked — the card says what to do instead. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun EngineCard(state: ProvidersUiState, viewModel: ProvidersViewModel) {
    val pick = state.settings.enginePick
    GlassCard {
        SectionHeader(title = "AI engine")
        Spacer(Modifier.height(4.dp))
        Text(
            "Automatic routes across your set-up providers and says which one " +
                "answered. Pick one provider to use exactly that one — its " +
                "failures are reported, never quietly swapped for another.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        ModelRow(
            label = "Automatic (recommended)",
            selected = pick is EnginePick.Automatic,
            onClick = { viewModel.setEngineAutomatic() }
        )
        state.providers.forEach { card ->
            val isPicked = pick is EnginePick.Specific &&
                pick.providerId == card.def.id
            ModelRow(
                label = if (card.configured) {
                    card.def.name
                } else {
                    "${card.def.name} — no key yet (add it below)"
                },
                selected = isPicked,
                enabled = card.configured,
                onClick = { viewModel.setEngineSpecific(card.def.id) }
            )
            if (isPicked) {
                when (card.def.protocol) {
                    AiProtocol.GEMINI -> {
                        ModelRow(
                            label = "Automatic model (lightest first)",
                            selected = (pick as EnginePick.Specific).model ==
                                GeminiModels.AUTO,
                            onClick = {
                                viewModel.setEngineModel(
                                    card.def.id, GeminiModels.AUTO
                                )
                            },
                            indented = true
                        )
                        GeminiModels.CASCADE.forEach { model ->
                            ModelRow(
                                label = geminiModelLabel(model),
                                selected = (pick as EnginePick.Specific).model ==
                                    model,
                                onClick = {
                                    viewModel.setEngineModel(card.def.id, model)
                                },
                                indented = true
                            )
                        }
                    }
                    AiProtocol.OLLAMA -> {
                        val models = card.models.ifEmpty {
                            listOfNotNull(
                                state.settings.ollamaModel.takeIf { it.isNotBlank() }
                            )
                        }
                        models.forEach { model ->
                            ModelRow(
                                label = model,
                                selected = (pick as EnginePick.Specific).model == model,
                                onClick = {
                                    viewModel.setEngineModel(card.def.id, model)
                                },
                                indented = true
                            )
                        }
                    }
                    else -> {
                        val models = card.models.ifEmpty {
                            listOfNotNull(card.chosenModel)
                        }
                        models.forEach { model ->
                            ModelRow(
                                label = modelLabel(model, card),
                                selected = (pick as EnginePick.Specific).model == model,
                                onClick = {
                                    viewModel.setEngineModel(card.def.id, model)
                                },
                                indented = true
                            )
                        }
                    }
                }
            }
        }
        if (pick is EnginePick.Automatic) {
            Spacer(Modifier.height(4.dp))
            Text(
                "When Gemini answers under Automatic, it uses: " +
                    if (state.settings.modelChoice == GeminiModels.AUTO) {
                        "the lightest model first, stepping up only if Google " +
                            "says a model isn't available to your key."
                    } else {
                        geminiModelLabel(state.settings.modelChoice) +
                            " first, with the same step-up if it's unavailable."
                    },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                NocturneChip(
                    label = "Lightest first",
                    selected = state.settings.modelChoice == GeminiModels.AUTO,
                    dotColor = null,
                    onClick = { viewModel.setModelChoice(GeminiModels.AUTO) }
                )
                GeminiModels.CASCADE.forEach { model ->
                    NocturneChip(
                        label = model.removePrefix("gemini-").replace('-', ' '),
                        selected = state.settings.modelChoice == model,
                        dotColor = null,
                        onClick = { viewModel.setModelChoice(model) }
                    )
                }
            }
        }
    }
}

/** A model row label, with the free badge when the provider lists
 * the model as free — their claim, labelled as theirs. */
private fun modelLabel(model: String, card: ProviderCardState): String =
    if (model in card.freeModels) {
        "$model — listed as free by ${card.def.name}"
    } else {
        model
    }

/** Patch 10: the Illustrate picture engine — Horde (free, keyless,
 * the default), or one of the two bring-your-own-key FLUX engines.
 * The key fields appear ONLY under the engine they belong to, and
 * the secrets go straight to the encrypted vault (the Cloudflare
 * account id is an identifier, so it is a plain setting). A picked
 * engine that can't paint falls back to Horde with a note on the
 * picture's screen — nothing here can break Illustrate. */
@Composable
private fun ImageEngineCard(state: ProvidersUiState, viewModel: ProvidersViewModel) {
    val engine = state.settings.imageEngine
    var accountInput by remember(state.settings.cloudflareAccountId) {
        mutableStateOf(state.settings.cloudflareAccountId)
    }
    var tokenInput by remember { mutableStateOf("") }
    var pollinationsInput by remember { mutableStateOf("") }
    GlassCard {
        SectionHeader(title = "Image engine")
        Spacer(Modifier.height(4.dp))
        Text(
            "Which service paints Illustrate pictures. AI Horde is free and " +
                "needs no key. If the engine you pick can't paint — or its " +
                "free allowance runs out — AI Horde paints instead, and " +
                "the picture's screen tells you it did.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        ModelRow(
            label = "AI Horde — free, no key (default)",
            selected = engine == ImageEngine.HORDE,
            onClick = { viewModel.setImageEngine(ImageEngine.HORDE) }
        )
        ModelRow(
            label = "Cloudflare FLUX — free daily allowance",
            selected = engine == ImageEngine.CLOUDFLARE,
            onClick = { viewModel.setImageEngine(ImageEngine.CLOUDFLARE) }
        )
        ModelRow(
            label = "Pollinations FLUX — free with a key",
            selected = engine == ImageEngine.POLLINATIONS,
            onClick = { viewModel.setImageEngine(ImageEngine.POLLINATIONS) }
        )
        if (engine == ImageEngine.CLOUDFLARE) {
            Spacer(Modifier.height(8.dp))
            Text(
                "FLUX on your own Cloudflare account: the free Workers AI " +
                    "allowance paints a few hundred pictures a day. Your " +
                    "account ID is on the dash.cloudflare.com dashboard; " +
                    "the token needs Workers AI permission. Both stay on " +
                    "this phone — the token in the encrypted vault.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = accountInput,
                onValueChange = { accountInput = it },
                label = { Text("Cloudflare account ID") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            PrimaryPill(
                text = "Save account ID",
                onClick = { viewModel.saveCloudflareAccountId(accountInput) },
                enabled = accountInput.isNotBlank() &&
                    accountInput.trim() != state.settings.cloudflareAccountId,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            if (!state.cloudflareTokenSaved) {
                OutlinedTextField(
                    value = tokenInput,
                    onValueChange = { tokenInput = it },
                    label = { Text("Cloudflare API token") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                PrimaryPill(
                    text = "Save token",
                    onClick = {
                        viewModel.saveCloudflareToken(tokenInput)
                        tokenInput = ""
                    },
                    enabled = tokenInput.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                Text(
                    "Your token is saved in the encrypted vault. To change " +
                        "it, remove it and add the new one.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { viewModel.removeCloudflareToken() }) {
                    Text("Remove token")
                }
            }
        }
        if (engine == ImageEngine.POLLINATIONS) {
            Spacer(Modifier.height(8.dp))
            Text(
                "FLUX with your free Pollinations key — get one at " +
                    "enter.pollinations.ai. A key is required: without " +
                    "one the service quietly swaps in a different model, " +
                    "so Xreader never calls it keyless.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            if (!state.pollinationsKeySaved) {
                OutlinedTextField(
                    value = pollinationsInput,
                    onValueChange = { pollinationsInput = it },
                    label = { Text("Pollinations key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                PrimaryPill(
                    text = "Save key",
                    onClick = {
                        viewModel.savePollinationsKey(pollinationsInput)
                        pollinationsInput = ""
                    },
                    enabled = pollinationsInput.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                Text(
                    "Your key is saved in the encrypted vault. To change " +
                        "it, remove it and add the new one.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { viewModel.removePollinationsKey() }) {
                    Text("Remove key")
                }
            }
        }
    }
}

/** Patch 12: the read-aloud voice engine — the device voice
 * (default, nothing leaves the phone) or Microsoft's Edge online
 * voices (free, no key, chapter text is sent to Microsoft to be
 * read). A book can override this for itself in its Listen
 * sheet; voices are picked there per book. If the online voice
 * can't be reached, the device voice reads instead and the
 * Listen sheet says so. */
@Composable
private fun VoiceEngineCard(state: ProvidersUiState, viewModel: ProvidersViewModel) {
    val engine = state.voiceSettings.engine
    GlassCard {
        SectionHeader(title = "Voice engine")
        Spacer(Modifier.height(4.dp))
        Text(
            "Which voice reads chapters aloud. The device voice stays on " +
                "this phone. The online voice sounds better and is free, " +
                "but sends chapter text to Microsoft to read it.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        ModelRow(
            label = "Device voice — on this phone (default)",
            selected = engine == VoiceEngine.DEVICE,
            onClick = { viewModel.setVoiceEngine(VoiceEngine.DEVICE) }
        )
        ModelRow(
            label = "Online voice — Microsoft Edge voices, free, no key",
            selected = engine == VoiceEngine.EDGE,
            onClick = { viewModel.setVoiceEngine(VoiceEngine.EDGE) }
        )
        if (engine == VoiceEngine.EDGE) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Chapter text is sent to Microsoft for online voices. " +
                    "If the online voice can't be reached, the device " +
                    "voice reads instead and the Listen sheet says so. " +
                    "Voices themselves are picked per book in the " +
                    "reader's Listen sheet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun GeminiCard(
    state: ProvidersUiState,
    card: ProviderCardState,
    viewModel: ProvidersViewModel
) {
    var keyInput by remember { mutableStateOf("") }
    val testing = state.testingId == AiProviders.GEMINI_ID
    GlassCard {
        ProviderHeader(card)
        Spacer(Modifier.height(6.dp))
        Text(
            "One free Gemini key powers the AI extras: chapter summaries, " +
                "Catch me up, LoreKeeper (characters & terms), Lore Chat, RAW " +
                "translation, tag suggestions, and the smart " +
                "source detector. (Pictures are the Image engine's job, " +
                "above — a free Gemini key has no picture allowance.) " +
                "Get a free key at aistudio.google.com — it " +
                "stays encrypted on this phone and is never synced or backed up.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        if (!card.configured) {
            OutlinedTextField(
                value = keyInput,
                onValueChange = { keyInput = it },
                label = { Text("Gemini API key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            PrimaryPill(
                text = if (testing) "Testing…" else "Test & save key",
                onClick = { viewModel.testAndSave(keyInput) },
                enabled = !testing && keyInput.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            Text(
                "Your key is saved in the encrypted vault. To change it, " +
                    "remove it and add the new one.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { viewModel.removeKey(AiProviders.GEMINI_ID) }) {
                Text("Remove key")
            }
            // Micro-build M: the AI health check — one tap
            // probes every cascade model with the saved key.
            TextButton(
                onClick = { viewModel.checkAiHealth() },
                enabled = !state.checkingHealth
            ) {
                Text(
                    if (state.checkingHealth) {
                        "Checking AI health…"
                    } else {
                        "Check AI health"
                    }
                )
            }
            state.aiHealth?.let { lines ->
                Spacer(Modifier.height(4.dp))
                Text(
                    "AI health — each model, asked once:",
                    style = MaterialTheme.typography.titleSmall
                )
                lines.forEach { line ->
                    Text(
                        "• " + geminiModelLabel(line.model)
                            .substringBefore(" — ") +
                            if (line.ok) {
                                " — working"
                            } else {
                                " — " + line.note
                            },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (line.ok) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
            }
        }
    }
}

/** OpenAI / Claude / APInex / user-added providers: the same card
 * shape — the protocol is the only difference, and it isn't the
 * user's problem. */
@Composable
private fun KeyedProviderCard(
    card: ProviderCardState,
    viewModel: ProvidersViewModel
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var keyInput by remember(card.def.id) { mutableStateOf("") }
    val testing = state.testingId == card.def.id
    GlassCard {
        ProviderHeader(card)
        Spacer(Modifier.height(6.dp))
        Text(
            card.def.blurb,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (!card.def.builtIn) {
            Text(
                card.def.baseUrl,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(12.dp))
        if (!card.configured) {
            OutlinedTextField(
                value = keyInput,
                onValueChange = { keyInput = it },
                label = { Text("${card.def.name} API key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            PrimaryPill(
                text = if (testing) "Testing…" else "Test & save key",
                onClick = { viewModel.testAndSaveKey(card.def.id, keyInput) },
                enabled = !testing && keyInput.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            if (card.models.isNotEmpty()) {
                Text(
                    "${card.models.size} models" +
                        if (card.freeModels.isNotEmpty()) {
                            " · ${card.freeModels.size} listed as free by " +
                                card.def.name
                        } else {
                            ""
                        } +
                        (card.chosenModel?.let { " · using $it" } ?: ""),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
            }
            Row {
                TextButton(onClick = { viewModel.removeKey(card.def.id) }) {
                    Text("Remove key")
                }
                if (!card.def.builtIn) {
                    TextButton(
                        onClick = { viewModel.removeCustomProvider(card.def.id) }
                    ) {
                        Text("Remove provider")
                    }
                }
            }
        }
    }
}

@Composable
private fun OllamaCard(
    state: ProvidersUiState,
    card: ProviderCardState,
    viewModel: ProvidersViewModel
) {
    var address by remember(state.settings.ollamaBaseUrl) {
        mutableStateOf(state.settings.ollamaBaseUrl)
    }
    var model by remember(state.settings.ollamaModel) {
        mutableStateOf(state.settings.ollamaModel)
    }
    GlassCard {
        ProviderHeader(card)
        Spacer(Modifier.height(6.dp))
        Text(
            card.def.blurb + " On the computer: run Ollama, then put its " +
                "address here (for a phone on the same Wi-Fi, that's usually " +
                "the computer's local address, like http://192.168.1.10:11434).",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = address,
            onValueChange = { address = it },
            label = { Text("Ollama address") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = model,
            onValueChange = { model = it },
            label = { Text("Model name (as Ollama lists it)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        if (card.models.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                "On that computer: ${card.models.joinToString(", ")}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryPill(
                text = if (state.testingOllama) "Testing…" else "Test & save",
                onClick = { viewModel.testOllama(address, model) },
                enabled = !state.testingOllama && address.isNotBlank(),
                modifier = Modifier.weight(1f)
            )
            GhostPill(
                text = "Save",
                enabled = true,
                onClick = { viewModel.saveOllama(address, model) }
            )
        }
    }
}

/** "Add a provider": paste a site → the app checks it live → paste
 * the key → the key is checked → Add. A failed check adds nothing,
 * and the card says so. */
@Composable
private fun AddProviderCard(
    state: ProvidersUiState,
    viewModel: ProvidersViewModel
) {
    val add = state.add
    var site by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    GlassCard {
        SectionHeader(title = "Add a provider")
        Spacer(Modifier.height(4.dp))
        Text(
            "Using another AI service — OpenRouter, Oxion, or a new one? Paste " +
                "its site here. Xreader checks that it answers like an AI " +
                "provider, then checks your key, and only then adds it — with " +
                "its full model list, free ones marked as the provider lists " +
                "them. Your book text will pass through that service.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = site,
            onValueChange = { site = it },
            label = { Text("Provider site or API address") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        if (add.phase == AddPhase.NEEDS_KEY ||
            add.phase == AddPhase.CONFIRMED_KEY
        ) {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = key,
                onValueChange = { key = it },
                label = { Text("Your key from that site") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
        }
        add.message?.let { message ->
            Spacer(Modifier.height(8.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = if (add.phase == AddPhase.FAILED) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
        if (add.phase == AddPhase.CONFIRMED_KEY) {
            val freeCount = add.models.count { it.listedFree }
            Spacer(Modifier.height(8.dp))
            Text(
                "Checked and working: ${add.models.size} models" +
                    if (freeCount > 0) {
                        ", $freeCount listed as free by the provider."
                    } else {
                        "."
                    },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary
            )
        }
        Spacer(Modifier.height(10.dp))
        when (add.phase) {
            AddPhase.CONFIRMED_KEY -> Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PrimaryPill(
                    text = "Add provider",
                    enabled = true,
                    onClick = { viewModel.addCheckedProvider() },
                    modifier = Modifier.weight(1f)
                )
                GhostPill(
                    text = "Cancel",
                    enabled = true,
                    onClick = { viewModel.cancelAdd() }
                )
            }
            else -> PrimaryPill(
                text = if (add.phase == AddPhase.CHECKING) {
                    "Checking…"
                } else if (add.phase == AddPhase.NEEDS_KEY) {
                    "Check with key"
                } else {
                    "Check site"
                },
                onClick = { viewModel.checkSite(site, key) },
                enabled = add.phase != AddPhase.CHECKING && site.isNotBlank() &&
                    (add.phase != AddPhase.NEEDS_KEY || key.isNotBlank()),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun ProviderHeader(card: ProviderCardState) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(card.def.name, style = MaterialTheme.typography.titleMedium)
        NocturneChip(
            label = if (card.configured) "Set up" else "Not set up",
            selected = false,
            dotColor = if (card.configured) {
                MaterialTheme.colorScheme.secondary
            } else {
                MaterialTheme.colorScheme.tertiary
            },
            onClick = null
        )
    }
}

@Composable
private fun ModelRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
    indented: Boolean = false
) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, enabled = enabled, onClick = onClick)
            .padding(vertical = 8.dp, horizontal = if (indented) 24.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick, enabled = enabled)
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}

// ────────────────────────────────────────────────────────────────
// Code 121 W8 (R32): the Material 3 Providers & keys page.
// Settings-list rows (name · model line · key state as TEXT —
// never a key), each opening its setup as a full-screen dialog
// form. Every control calls the same ProvidersViewModel doors
// the Nocturne cards call; the key fields are password fields
// whose contents go straight to the VM (test & save), exactly
// as shipped.
// ────────────────────────────────────────────────────────────────

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun M3ProvidersContent(
    state: ProvidersUiState,
    viewModel: ProvidersViewModel,
    onBack: () -> Unit
) {
    var openForm by remember { mutableStateOf<String?>(null) }
    val pick = state.settings.enginePick
    val pickLine = when (pick) {
        is EnginePick.Specific -> {
            val name = state.providers
                .firstOrNull { it.def.id == pick.providerId }
                ?.def?.name ?: "One provider"
            if (pick.model.isBlank()) name else "$name · ${pick.model}"
        }
        EnginePick.Automatic -> "Automatic"
    }
    androidx.compose.material3.Scaffold(
        topBar = {
            androidx.compose.material3.TopAppBar(
                title = { Text("Providers & keys") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp)
        ) {
            state.statusMessage?.let { message ->
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (state.statusIsError) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(
                        horizontal = 16.dp, vertical = 8.dp
                    )
                )
            }
            M3ProviderRow(
                title = "AI engine",
                line = pickLine,
                onClick = { openForm = "engine" }
            )
            Text(
                "Providers",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(
                    start = 16.dp, top = 16.dp, bottom = 4.dp
                )
            )
            state.providers.forEach { card ->
                val keyState = when {
                    card.def.protocol == AiProtocol.OLLAMA ->
                        if (card.configured) "Set up" else "Not set up"
                    card.configured -> "Key saved"
                    else -> "No key"
                }
                val modelLine = card.chosenModel
                    ?: card.models.firstOrNull()
                M3ProviderRow(
                    title = card.def.name,
                    line = listOfNotNull(modelLine, keyState)
                        .joinToString(" · "),
                    onClick = { openForm = "provider:${card.def.id}" }
                )
            }
            M3ProviderRow(
                title = "Add a provider",
                line = "Paste a site — it is checked before anything is added",
                onClick = { openForm = "add" }
            )
            Text(
                "More engines",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(
                    start = 16.dp, top = 16.dp, bottom = 4.dp
                )
            )
            M3ProviderRow(
                title = "Image engine",
                line = when (state.settings.imageEngine) {
                    ImageEngine.HORDE -> "AI Horde — free, no key"
                    ImageEngine.CLOUDFLARE -> "Cloudflare FLUX"
                    ImageEngine.POLLINATIONS -> "Pollinations FLUX"
                },
                onClick = { openForm = "image" }
            )
            M3ProviderRow(
                title = "Voice engine",
                line = when (state.voiceSettings.engine) {
                    VoiceEngine.DEVICE -> "Device voice — on this phone"
                    VoiceEngine.EDGE -> "Online voice — Microsoft Edge"
                },
                onClick = { openForm = "voice" }
            )
            Text(
                "AI features",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(
                    start = 16.dp, top = 16.dp, bottom = 4.dp
                )
            )
            Text(
                "Turn individual features off to save quota. They " +
                    "light up once any provider above is set up — " +
                    "nothing here is needed for reading.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            AiFeature.entries.forEach { feature ->
                androidx.compose.material3.ListItem(
                    headlineContent = { Text(feature.label) },
                    trailingContent = {
                        androidx.compose.material3.Switch(
                            checked = state.settings.enabled(feature),
                            onCheckedChange = {
                                viewModel.setFeature(feature, it)
                            }
                        )
                    }
                )
            }
        }
    }
    when (val form = openForm) {
        "engine" -> M3EngineForm(
            state = state,
            viewModel = viewModel,
            onClose = { openForm = null }
        )
        "image" -> M3ImageEngineForm(
            state = state,
            viewModel = viewModel,
            onClose = { openForm = null }
        )
        "voice" -> M3VoiceEngineForm(
            state = state,
            viewModel = viewModel,
            onClose = { openForm = null }
        )
        "add" -> M3AddProviderForm(
            state = state,
            viewModel = viewModel,
            onClose = { openForm = null }
        )
        else -> if (form != null && form.startsWith("provider:")) {
            val card = state.providers.firstOrNull {
                it.def.id == form.removePrefix("provider:")
            }
            if (card != null) {
                M3ProviderSetupForm(
                    state = state,
                    card = card,
                    viewModel = viewModel,
                    onClose = { openForm = null }
                )
            }
        }
    }
}

@Composable
private fun M3ProviderRow(
    title: String,
    line: String,
    onClick: () -> Unit
) {
    androidx.compose.material3.ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(line) },
        trailingContent = {
            Icon(
                androidx.compose.material.icons.Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        modifier = Modifier.clickable(onClick = onClick)
    )
}

/** A full-screen dialog form (R32's setup form shape): the
 * M3 dialog frame at full size, its own top bar + Close. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun M3FullScreenForm(
    title: String,
    onClose: () -> Unit,
    content: @Composable () -> Unit
) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onClose,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false
        )
    ) {
        androidx.compose.material3.Scaffold(
            topBar = {
                androidx.compose.material3.TopAppBar(
                    title = { Text(title) },
                    navigationIcon = {
                        IconButton(onClick = onClose) {
                            Icon(
                                androidx.compose.material.icons.Icons
                                    .Filled.Close,
                                contentDescription = "Close"
                            )
                        }
                    }
                )
            }
        ) { innerPadding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                content()
            }
        }
    }
}

@Composable
private fun M3RadioRow(
    label: String,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    androidx.compose.material3.ListItem(
        headlineContent = { Text(label) },
        leadingContent = {
            RadioButton(
                selected = selected,
                onClick = onClick,
                enabled = enabled
            )
        },
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick)
    )
}

@Composable
private fun M3EngineForm(
    state: ProvidersUiState,
    viewModel: ProvidersViewModel,
    onClose: () -> Unit
) {
    val pick = state.settings.enginePick
    M3FullScreenForm(title = "AI engine", onClose = onClose) {
        Text(
            "Automatic routes across your set-up providers and says " +
                "which one answered. Pick one provider to use exactly " +
                "that one — its failures are reported, never quietly " +
                "swapped for another.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        M3RadioRow(
            label = "Automatic (recommended)",
            selected = pick is EnginePick.Automatic,
            onClick = { viewModel.setEngineAutomatic() }
        )
        state.providers.forEach { card ->
            val isPicked = pick is EnginePick.Specific &&
                pick.providerId == card.def.id
            M3RadioRow(
                label = if (card.configured) {
                    card.def.name
                } else {
                    "${card.def.name} — no key yet"
                },
                selected = isPicked,
                enabled = card.configured,
                onClick = { viewModel.setEngineSpecific(card.def.id) }
            )
            if (isPicked) {
                val specific = pick as EnginePick.Specific
                when (card.def.protocol) {
                    AiProtocol.GEMINI -> {
                        M3RadioRow(
                            label = "Automatic model (lightest first)",
                            selected = specific.model == GeminiModels.AUTO,
                            onClick = {
                                viewModel.setEngineModel(
                                    card.def.id, GeminiModels.AUTO
                                )
                            }
                        )
                        GeminiModels.CASCADE.forEach { model ->
                            M3RadioRow(
                                label = geminiModelLabel(model),
                                selected = specific.model == model,
                                onClick = {
                                    viewModel.setEngineModel(
                                        card.def.id, model
                                    )
                                }
                            )
                        }
                    }
                    AiProtocol.OLLAMA -> {
                        val models = card.models.ifEmpty {
                            listOfNotNull(
                                state.settings.ollamaModel
                                    .takeIf { it.isNotBlank() }
                            )
                        }
                        models.forEach { model ->
                            M3RadioRow(
                                label = model,
                                selected = specific.model == model,
                                onClick = {
                                    viewModel.setEngineModel(
                                        card.def.id, model
                                    )
                                }
                            )
                        }
                    }
                    else -> {
                        val models = card.models.ifEmpty {
                            listOfNotNull(card.chosenModel)
                        }
                        models.forEach { model ->
                            M3RadioRow(
                                label = modelLabel(model, card),
                                selected = specific.model == model,
                                onClick = {
                                    viewModel.setEngineModel(
                                        card.def.id, model
                                    )
                                }
                            )
                        }
                    }
                }
            }
        }
        if (pick is EnginePick.Automatic && state.geminiConfigured) {
            Text(
                "When Gemini answers under Automatic, it starts with:",
                style = MaterialTheme.typography.titleSmall
            )
            M3RadioRow(
                label = "Lightest first",
                selected = state.settings.modelChoice == GeminiModels.AUTO,
                onClick = { viewModel.setModelChoice(GeminiModels.AUTO) }
            )
            GeminiModels.CASCADE.forEach { model ->
                M3RadioRow(
                    label = geminiModelLabel(model),
                    selected = state.settings.modelChoice == model,
                    onClick = { viewModel.setModelChoice(model) }
                )
            }
        }
    }
}

@Composable
private fun M3ProviderSetupForm(
    state: ProvidersUiState,
    card: ProviderCardState,
    viewModel: ProvidersViewModel,
    onClose: () -> Unit
) {
    M3FullScreenForm(title = card.def.name, onClose = onClose) {
        when (card.def.protocol) {
            AiProtocol.GEMINI -> M3GeminiSetup(state, card, viewModel)
            AiProtocol.OLLAMA -> M3OllamaSetup(state, card, viewModel)
            else -> M3KeyedSetup(state, card, viewModel)
        }
    }
}

@Composable
private fun M3GeminiSetup(
    state: ProvidersUiState,
    card: ProviderCardState,
    viewModel: ProvidersViewModel
) {
    var keyInput by remember { mutableStateOf("") }
    val testing = state.testingId == AiProviders.GEMINI_ID
    Text(
        "One free Gemini key powers the AI extras: chapter summaries, " +
            "Catch me up, LoreKeeper, RAW translation and more. Get a " +
            "free key at aistudio.google.com — it stays encrypted on " +
            "this phone and is never synced or backed up.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    if (!card.configured) {
        OutlinedTextField(
            value = keyInput,
            onValueChange = { keyInput = it },
            label = { Text("Gemini API key") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        androidx.compose.material3.Button(
            onClick = { viewModel.testAndSave(keyInput) },
            enabled = !testing && keyInput.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (testing) "Testing…" else "Test & save key") }
    } else {
        Text(
            "Key saved — your key is in the encrypted vault. To change " +
                "it, remove it and add the new one.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        TextButton(
            onClick = { viewModel.removeKey(AiProviders.GEMINI_ID) }
        ) { Text("Remove key") }
        TextButton(
            onClick = { viewModel.checkAiHealth() },
            enabled = !state.checkingHealth
        ) {
            Text(
                if (state.checkingHealth) {
                    "Checking AI health…"
                } else {
                    "Check AI health"
                }
            )
        }
        state.aiHealth?.let { lines ->
            Text(
                "AI health — each model, asked once:",
                style = MaterialTheme.typography.titleSmall
            )
            lines.forEach { line ->
                Text(
                    "• " + geminiModelLabel(line.model)
                        .substringBefore(" — ") +
                        if (line.ok) {
                            " — working"
                        } else {
                            " — " + line.note
                        },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (line.ok) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.error
                    }
                )
            }
        }
    }
}

@Composable
private fun M3KeyedSetup(
    state: ProvidersUiState,
    card: ProviderCardState,
    viewModel: ProvidersViewModel
) {
    var keyInput by remember(card.def.id) { mutableStateOf("") }
    val testing = state.testingId == card.def.id
    Text(
        card.def.blurb,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    if (!card.def.builtIn) {
        Text(
            card.def.baseUrl,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    if (!card.configured) {
        OutlinedTextField(
            value = keyInput,
            onValueChange = { keyInput = it },
            label = { Text("${card.def.name} API key") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        androidx.compose.material3.Button(
            onClick = { viewModel.testAndSaveKey(card.def.id, keyInput) },
            enabled = !testing && keyInput.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (testing) "Testing…" else "Test & save key") }
    } else {
        if (card.models.isNotEmpty()) {
            Text(
                "${card.models.size} models" +
                    if (card.freeModels.isNotEmpty()) {
                        " · ${card.freeModels.size} listed as free by " +
                            card.def.name
                    } else {
                        ""
                    } +
                    (card.chosenModel?.let { " · using $it" } ?: ""),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Text(
                "Key saved — your key is in the encrypted vault.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Row {
            TextButton(onClick = { viewModel.removeKey(card.def.id) }) {
                Text("Remove key")
            }
            if (!card.def.builtIn) {
                TextButton(
                    onClick = { viewModel.removeCustomProvider(card.def.id) }
                ) { Text("Remove provider") }
            }
        }
    }
}

@Composable
private fun M3OllamaSetup(
    state: ProvidersUiState,
    card: ProviderCardState,
    viewModel: ProvidersViewModel
) {
    var address by remember(state.settings.ollamaBaseUrl) {
        mutableStateOf(state.settings.ollamaBaseUrl)
    }
    var model by remember(state.settings.ollamaModel) {
        mutableStateOf(state.settings.ollamaModel)
    }
    Text(
        card.def.blurb + " On the computer: run Ollama, then put its " +
            "address here (for a phone on the same Wi-Fi, that's " +
            "usually the computer's local address, like " +
            "http://192.168.1.10:11434).",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    OutlinedTextField(
        value = address,
        onValueChange = { address = it },
        label = { Text("Ollama address") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    OutlinedTextField(
        value = model,
        onValueChange = { model = it },
        label = { Text("Model name (as Ollama lists it)") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    if (card.models.isNotEmpty()) {
        Text(
            "On that computer: ${card.models.joinToString(", ")}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        androidx.compose.material3.Button(
            onClick = { viewModel.testOllama(address, model) },
            enabled = !state.testingOllama && address.isNotBlank(),
            modifier = Modifier.weight(1f)
        ) {
            Text(if (state.testingOllama) "Testing…" else "Test & save")
        }
        androidx.compose.material3.OutlinedButton(
            onClick = { viewModel.saveOllama(address, model) }
        ) { Text("Save") }
    }
}

@Composable
private fun M3ImageEngineForm(
    state: ProvidersUiState,
    viewModel: ProvidersViewModel,
    onClose: () -> Unit
) {
    val engine = state.settings.imageEngine
    var accountInput by remember(state.settings.cloudflareAccountId) {
        mutableStateOf(state.settings.cloudflareAccountId)
    }
    var tokenInput by remember { mutableStateOf("") }
    var pollinationsInput by remember { mutableStateOf("") }
    M3FullScreenForm(title = "Image engine", onClose = onClose) {
        Text(
            "Which service paints Illustrate pictures. AI Horde is " +
                "free and needs no key. If the engine you pick can't " +
                "paint, AI Horde paints instead and the picture's " +
                "screen tells you it did.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        M3RadioRow(
            label = "AI Horde — free, no key (default)",
            selected = engine == ImageEngine.HORDE,
            onClick = { viewModel.setImageEngine(ImageEngine.HORDE) }
        )
        M3RadioRow(
            label = "Cloudflare FLUX — free daily allowance",
            selected = engine == ImageEngine.CLOUDFLARE,
            onClick = { viewModel.setImageEngine(ImageEngine.CLOUDFLARE) }
        )
        M3RadioRow(
            label = "Pollinations FLUX — free with a key",
            selected = engine == ImageEngine.POLLINATIONS,
            onClick = { viewModel.setImageEngine(ImageEngine.POLLINATIONS) }
        )
        if (engine == ImageEngine.CLOUDFLARE) {
            OutlinedTextField(
                value = accountInput,
                onValueChange = { accountInput = it },
                label = { Text("Cloudflare account ID") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            androidx.compose.material3.Button(
                onClick = { viewModel.saveCloudflareAccountId(accountInput) },
                enabled = accountInput.isNotBlank() &&
                    accountInput.trim() != state.settings.cloudflareAccountId,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Save account ID") }
            if (!state.cloudflareTokenSaved) {
                OutlinedTextField(
                    value = tokenInput,
                    onValueChange = { tokenInput = it },
                    label = { Text("Cloudflare API token") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                androidx.compose.material3.Button(
                    onClick = {
                        viewModel.saveCloudflareToken(tokenInput)
                        tokenInput = ""
                    },
                    enabled = tokenInput.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Save token") }
            } else {
                Text(
                    "Token saved — in the encrypted vault. To change " +
                        "it, remove it and add the new one.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = { viewModel.removeCloudflareToken() }) {
                    Text("Remove token")
                }
            }
        }
        if (engine == ImageEngine.POLLINATIONS) {
            if (!state.pollinationsKeySaved) {
                OutlinedTextField(
                    value = pollinationsInput,
                    onValueChange = { pollinationsInput = it },
                    label = { Text("Pollinations key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                androidx.compose.material3.Button(
                    onClick = {
                        viewModel.savePollinationsKey(pollinationsInput)
                        pollinationsInput = ""
                    },
                    enabled = pollinationsInput.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Save key") }
            } else {
                Text(
                    "Key saved — in the encrypted vault. To change it, " +
                        "remove it and add the new one.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = { viewModel.removePollinationsKey() }) {
                    Text("Remove key")
                }
            }
        }
    }
}

@Composable
private fun M3VoiceEngineForm(
    state: ProvidersUiState,
    viewModel: ProvidersViewModel,
    onClose: () -> Unit
) {
    val engine = state.voiceSettings.engine
    M3FullScreenForm(title = "Voice engine", onClose = onClose) {
        Text(
            "Which voice reads chapters aloud. The device voice stays " +
                "on this phone. The online voice sounds better and is " +
                "free, but sends chapter text to Microsoft to read it.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        M3RadioRow(
            label = "Device voice — on this phone (default)",
            selected = engine == VoiceEngine.DEVICE,
            onClick = { viewModel.setVoiceEngine(VoiceEngine.DEVICE) }
        )
        M3RadioRow(
            label = "Online voice — Microsoft Edge voices, free, no key",
            selected = engine == VoiceEngine.EDGE,
            onClick = { viewModel.setVoiceEngine(VoiceEngine.EDGE) }
        )
        if (engine == VoiceEngine.EDGE) {
            Text(
                "Chapter text is sent to Microsoft for online voices. " +
                    "If the online voice can't be reached, the device " +
                    "voice reads instead and the Listen sheet says so.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun M3AddProviderForm(
    state: ProvidersUiState,
    viewModel: ProvidersViewModel,
    onClose: () -> Unit
) {
    val add = state.add
    var site by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    M3FullScreenForm(title = "Add a provider", onClose = onClose) {
        Text(
            "Using another AI service? Paste its site here. Xreader " +
                "checks that it answers like an AI provider, then " +
                "checks your key, and only then adds it. Your book " +
                "text will pass through that service.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedTextField(
            value = site,
            onValueChange = { site = it },
            label = { Text("Provider site or API address") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        if (add.phase == AddPhase.NEEDS_KEY ||
            add.phase == AddPhase.CONFIRMED_KEY
        ) {
            OutlinedTextField(
                value = key,
                onValueChange = { key = it },
                label = { Text("Your key from that site") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
        }
        add.message?.let { message ->
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = if (add.phase == AddPhase.FAILED) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
        if (add.phase == AddPhase.CONFIRMED_KEY) {
            val freeCount = add.models.count { it.listedFree }
            Text(
                "Checked and working: ${add.models.size} models" +
                    if (freeCount > 0) {
                        ", $freeCount listed as free by the provider."
                    } else {
                        "."
                    },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.Button(
                    onClick = { viewModel.addCheckedProvider() },
                    modifier = Modifier.weight(1f)
                ) { Text("Add provider") }
                androidx.compose.material3.OutlinedButton(
                    onClick = { viewModel.cancelAdd() }
                ) { Text("Cancel") }
            }
        } else {
            androidx.compose.material3.Button(
                onClick = { viewModel.checkSite(site, key) },
                enabled = add.phase != AddPhase.CHECKING &&
                    site.isNotBlank() &&
                    (add.phase != AddPhase.NEEDS_KEY || key.isNotBlank()),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    if (add.phase == AddPhase.CHECKING) {
                        "Checking…"
                    } else if (add.phase == AddPhase.NEEDS_KEY) {
                        "Check with key"
                    } else {
                        "Check site"
                    }
                )
            }
        }
    }
}
