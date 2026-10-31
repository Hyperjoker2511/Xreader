package app.xreader.ui.ai

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
import app.xreader.ui.common.GhostPill
import app.xreader.ui.common.GlassCard
import app.xreader.ui.common.NocturneChip
import app.xreader.ui.common.NocturneSwitch
import app.xreader.ui.common.PrimaryPill
import app.xreader.ui.common.SectionHeader

/** Friendly names for the cascade (the ids stay in GeminiModels). */
fun geminiModelLabel(model: String): String = when (model) {
    "gemini-3.5-flash-lite" -> "Gemini 3.5 Flash Lite — fastest, lightest"
    "gemini-3.1-flash-lite" -> "Gemini 3.1 Flash Lite"
    "gemini-3.8-flash" -> "Gemini 3.8 Flash — stronger"
    "gemini-3.6-flash" -> "Gemini 3.6 Flash"
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

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 16.dp)
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
            Spacer(Modifier.height(88.dp))
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
                "translation, concept art, tag suggestions, and the smart " +
                "source detector. Get a free key at aistudio.google.com — it " +
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
