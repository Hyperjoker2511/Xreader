package app.xreader.ui.ai
import app.xreader.ui.theme.errorTextColor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Summarize
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.xreader.data.ai.Summarizer
import app.xreader.data.prefs.AiFeature
import app.xreader.ui.common.GlassCard
import app.xreader.ui.common.StatusPill
import app.xreader.ui.common.XreaderPrimaryButton

/**
 * The reader's "Ask AI" sheet (Stitch ai-sheet design): violet
 * Gemini accent, one-tap rows for summarize / catch-up / translate /
 * illustrate, and lore chat with the spoiler guard — everything
 * answered only from chapters up to the reader's position.
 */
@Composable
fun AiSheetContent(
    viewModel: AiSheetViewModel,
    showingTranslation: Boolean,
    onShowOriginal: () -> Unit,
    onShowTranslation: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val engineLabel by viewModel.engineLabel.collectAsStateWithLifecycle()
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 640.dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary
            )
            Text(
                "Ask AI",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 8.dp)
            )
            Spacer(Modifier.weight(1f))
            EngineChip(label = engineLabel)
        }
        Text(
            "Answers only use the story up to where you're reading — no spoilers.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )
        Spacer(Modifier.height(12.dp))

        if (!state.hasKey) {
            GlassCard(Modifier.fillMaxWidth()) {
                Text(
                    "AI features need an AI provider",
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    "Add a free Gemini key (aistudio.google.com) or any " +
                        "other provider in More → Providers & keys. Keys stay " +
                        "encrypted on this device and are never synced. " +
                        "Reading works fully without one.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            return@Column
        }

        // -- Summarize -----------------------------------------------------
        if (AiFeature.SUMMARIZER in state.featuresOn) {
            ActionRow(
                icon = Icons.Filled.Summarize,
                title = "Summarize this chapter",
                subtitle = "Three bullets, saved for the Contents sheet",
                busy = state.summarizing,
                onClick = { viewModel.summarizeChapter() }
            )
            state.summaryError?.let { error ->
                ErrorLine(error)
            }
            state.summary?.let { summary ->
                val bullets = Summarizer.decodeBullets(summary.bulletsJson)
                Column(Modifier.padding(start = 40.dp, top = 4.dp)) {
                    bullets.forEach { bullet ->
                        Text(
                            "• $bullet",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }
                }
            }
            ActionRow(
                icon = Icons.Filled.MenuBook,
                title = "Catch me up",
                subtitle = "The story so far, up to this chapter",
                busy = state.catchingUp,
                onClick = { viewModel.catchUp() }
            )
            state.catchUpError?.let { ErrorLine(it) }
            state.catchUp?.let { text ->
                Text(
                    text,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 40.dp, top = 4.dp)
                )
            }
            HorizontalDivider(Modifier.padding(vertical = 10.dp))
        }

        // -- Translate -------------------------------------------------------
        if (AiFeature.RAW_TRANSLATION in state.featuresOn) {
            ActionRow(
                icon = Icons.Filled.Translate,
                title = "Translate this chapter",
                subtitle = "Detected: ${state.detectedLanguage} — uses the " +
                    "book's glossary and lore",
                busy = state.translating,
                onClick = { viewModel.translateChapter() }
            )
            state.translationError?.let { ErrorLine(it) }
            if (state.translating && state.translationPreview.isNotBlank()) {
                Text(
                    "…" + state.translationPreview,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 40.dp, top = 4.dp)
                )
            }
            if (state.translationExists) {
                Row(
                    Modifier.padding(start = 32.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    TextButton(
                        onClick = onShowTranslation,
                        enabled = !showingTranslation
                    ) { Text("Show translation") }
                    TextButton(
                        onClick = onShowOriginal,
                        enabled = showingTranslation
                    ) { Text("Show original") }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 10.dp))
        }

        // -- Illustrate --------------------------------------------------------
        if (AiFeature.CONCEPT_ART in state.featuresOn) {
            ActionRow(
                icon = Icons.Filled.Image,
                title = "Illustrate this moment",
                subtitle = "Paints the chapter's opening scene into the Lore art",
                busy = state.illustrating,
                onClick = { viewModel.illustrateMoment() }
            )
            state.illustrateMessage?.let { message ->
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 40.dp, top = 4.dp)
                )
            }
            HorizontalDivider(Modifier.padding(vertical = 10.dp))
        }

        // -- Lore chat -----------------------------------------------------------
        if (AiFeature.LORE_CHAT in state.featuresOn) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.ChatBubbleOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary
                )
                Text(
                    "Lore chat",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
            Text(
                "Ask about the people, places and events so far.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            state.chat.forEach { message ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = if (message.fromUser) {
                        Arrangement.End
                    } else {
                        Arrangement.Start
                    }
                ) {
                    GlassCard(
                        modifier = Modifier.fillMaxWidth(
                            if (message.fromUser) 0.85f else 0.95f
                        )
                    ) {
                        Text(
                            message.text.ifBlank { "…" },
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
            state.chatError?.let { ErrorLine(it) }
            var question by remember { mutableStateOf("") }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = question,
                    onValueChange = { question = it },
                    label = { Text("Ask about the story…") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                TextButton(
                    onClick = {
                        viewModel.sendChat(question)
                        question = ""
                    },
                    enabled = question.isNotBlank() && !state.chatBusy
                ) { Text("Ask") }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun ActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    busy: Boolean,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.tertiary
        )
        Column(
            Modifier
                .weight(1f)
                .padding(start = 12.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (busy) {
            CircularProgressIndicator(Modifier.padding(end = 8.dp))
        } else {
            TextButton(onClick = onClick) { Text("Run") }
        }
    }
}

@Composable
private fun ErrorLine(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = errorTextColor(),
        modifier = Modifier.padding(start = 40.dp, top = 2.dp)
    )
}
