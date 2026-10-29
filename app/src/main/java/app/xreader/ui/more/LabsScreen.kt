package app.xreader.ui.more

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.xreader.di.AppContainer
import app.xreader.domain.reader.ReaderConfig
import app.xreader.ui.common.GlassCard
import kotlinx.coroutines.launch

/**
 * Labs (PLAN §0.2 / Amendment 3): experimental features live here,
 * clearly labelled, off by default, and never allowed to block normal
 * reading. Phase 4 ships one: eye-tracking page turns. The same
 * switch also lives in the reader's Display settings.
 */
@Composable
fun LabsScreen(container: AppContainer, onBack: () -> Unit) {
    val config by container.readerPrefs.config
        .collectAsStateWithLifecycle(initialValue = ReaderConfig())
    val scope = rememberCoroutineScope()
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text("Labs", style = MaterialTheme.typography.headlineSmall)
        }
        Text(
            "Experiments live here. They're real features, but their " +
                "quality depends on your device — if one misbehaves it " +
                "turns itself off and tells you why. Your reading is " +
                "never affected.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Eye-tracking page turns — EXPERIMENTAL",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        "Double-blink, nod, or hold your gaze down to turn " +
                            "the page. Uses the front camera on this device " +
                            "only: images are classified on the phone and " +
                            "never recorded, stored, or sent anywhere. " +
                            "Needs the camera permission, asked for only " +
                            "when you turn this on.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = config.eyeTracking,
                    onCheckedChange = { on ->
                        scope.launch {
                            container.readerPrefs.update { it.copy(eyeTracking = on) }
                        }
                    }
                )
            }
        }
    }
}
