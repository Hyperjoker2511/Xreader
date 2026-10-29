package app.xreader.ui.more

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.app.ActivityManager
import android.content.Context
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import app.xreader.data.prefs.AppearanceValidation
import app.xreader.data.prefs.GlassStyle
import app.xreader.data.prefs.ThemeMode
import app.xreader.ui.common.GlassCard
import app.xreader.ui.theme.AppSchemes
import app.xreader.ui.theme.appSchemeNamed
import kotlin.math.roundToInt

@Composable
private fun rememberGlassRamMb(context: Context): Long =
    androidx.compose.runtime.remember(context) {
        runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val info = ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            info.totalMem / (1024L * 1024L)
        }.getOrDefault(4096L)
    }

/**
 * Appearance (PLAN §2.2/§3): theme mode, the three glass sliders working
 * live against the theme tokens, and reduce motion. Every control writes
 * real state — what you set here is what the whole app does.
 */
@Composable
fun AppearanceScreen(
    viewModel: AppearanceViewModel,
    onBack: () -> Unit,
    onOpenCustomise: () -> Unit = {}
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 24.dp, vertical = 16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text("Appearance", style = MaterialTheme.typography.headlineSmall)
        }

        GlassCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Theme", style = MaterialTheme.typography.titleMedium)
                ThemeMode.entries.forEach { mode ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = state.themeMode == mode,
                                role = Role.RadioButton,
                                onClick = { viewModel.setThemeMode(mode) }
                            )
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = state.themeMode == mode,
                            onClick = { viewModel.setThemeMode(mode) }
                        )
                        Text(
                            when (mode) {
                                ThemeMode.FOLLOW_SYSTEM -> "Follow system"
                                ThemeMode.LIGHT -> "Light — warm paper"
                                ThemeMode.DARK -> "Dark — ember ink"
                            },
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }
        }

        // v1.0.1 ONE HOME PER SETTING: colour themes, glass and the
        // glass sliders live in Customise now — this card is the door.
        GlassCard(Modifier.fillMaxWidth(), onClick = onOpenCustomise) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Colours, glass & layout", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Colour themes, frosted glass, library layout, navigation and " +
                        "reader looks all live in Customise — one home for how the app looks.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "Open Customise",
                    style = MaterialTheme.typography.labelLarge,
                    color = app.xreader.ui.theme.primaryTextColor()
                )
            }
        }

        GlassCard(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Reduce motion", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Springs settle instantly instead of travelling.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = state.reduceMotion,
                    onCheckedChange = viewModel::setReduceMotion
                )
            }
        }
    }
}

@Composable
private fun GlassSlider(
    label: String,
    percent: Int,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit
) {
    Column {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                "$percent%",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}
