package app.xreader.ui.ai

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.xreader.ui.common.StatusPill

/**
 * The engine chip (v1.0.3, the picking rule made visible): every AI
 * surface shows which engine will answer BEFORE anything is spent —
 * "AI · Automatic" when the hub routes, or "AI · <provider> ·
 * <model>" when the user pinned one. Display-only: the pick itself
 * is changed in Providers & keys.
 */
@Composable
fun EngineChip(label: String, modifier: Modifier = Modifier) {
    StatusPill(
        text = label,
        color = MaterialTheme.colorScheme.tertiary,
        modifier = modifier
    )
}
