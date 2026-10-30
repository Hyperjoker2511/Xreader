package app.xreader.ui.ai

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.xreader.data.prefs.AiFeature
import app.xreader.ui.theme.FloatingChromeLift

/**
 * v1.0.6.2 (F9b): Ask AI as a full PAGE — a real navigation
 * destination with room to read. The features, their order and
 * their logic are the sheet's own ([AiSheetContent] in page
 * mode, same view model, bound to the chapter on screen); only
 * the presentation moved out of the cramped bottom sheet. The
 * Lore chat input docks at the page bottom, floated above the
 * navigation inset by the shared chrome lift.
 */
@Composable
fun AiPage(
    viewModel: AiSheetViewModel,
    showingTranslation: Boolean,
    onShowOriginal: () -> Unit,
    onShowTranslation: () -> Unit,
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val engineLabel by viewModel.engineLabel.collectAsStateWithLifecycle()
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Column(Modifier.weight(1f)) {
                Text(
                    "Ask AI",
                    style = MaterialTheme.typography.titleLarge
                )
                Text(
                    "Only the story up to where you're reading — no spoilers.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            EngineChip(label = engineLabel)
        }
        AiSheetContent(
            viewModel = viewModel,
            showingTranslation = showingTranslation,
            onShowOriginal = onShowOriginal,
            onShowTranslation = onShowTranslation,
            pageMode = true,
            modifier = Modifier.weight(1f)
        )
        if (state.hasKey && AiFeature.LORE_CHAT in state.featuresOn) {
            var question by remember { mutableStateOf("") }
            Row(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(
                        start = 24.dp,
                        end = 24.dp,
                        top = 8.dp,
                        bottom = 8.dp + FloatingChromeLift
                    ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = question,
                    onValueChange = { question = it },
                    label = { Text("Ask about the story…") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                Spacer(Modifier.width(8.dp))
                TextButton(
                    onClick = {
                        viewModel.sendChat(question)
                        question = ""
                    },
                    enabled = question.isNotBlank() && !state.chatBusy
                ) { Text("Ask") }
            }
        }
    }
}
