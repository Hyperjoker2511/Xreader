package app.xreader.ui.common
import app.xreader.ui.theme.primaryTextColor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.xreader.core.motion.pressSquish
import app.xreader.ui.theme.GlassSettings
import app.xreader.ui.theme.LocalGlassSettings
import app.xreader.ui.theme.LocalHazeState
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

/**
 * Real frosted glass: when the effective glass style is FROSTED, the
 * surface blurs whatever scrolls behind it (Haze over the shared
 * haze source). TINTED/SOLID render exactly as before — this
 * modifier is then a no-op.
 *
 * v1.0.2 (R01, the P0 fix): Haze 1.5.1's graphics-layer draw path
 * THROWS IllegalArgumentException("backgroundColor not specified")
 * on the first hardware-accelerated frame when no background colour
 * is set — that was the v1.0.1 frosted-glass crash. The colour Haze
 * composites the blur over is now always the scheme's opaque
 * background. TRUE CHROME ONLY (nav bar, rail, genuine overlay
 * bars): content cards inside the haze source must NOT carry their
 * own effect node (nested effect areas are Haze's second IAE), so
 * [GlassCard] deliberately does not use this modifier any more.
 */
@Composable
fun Modifier.frostedGlass(glass: GlassSettings, tint: Color): Modifier {
    val haze = LocalHazeState.current
    if (!glass.frosted || haze == null) return this
    val background = MaterialTheme.colorScheme.background
    return this.hazeEffect(haze) {
        backgroundColor = background
        blurRadius = 24.dp
        tints = listOf(HazeTint(tint.copy(alpha = 0.55f)))
        fallbackTint = HazeTint(tint)
    }
}

/**
 * The aquamorphic surface (SPEC §D / PLAN §2.2): layered translucency +
 * hairline border + soft shadow.
 *
 * v1.0.2 (R01 topology): content cards live INSIDE the shell's single
 * haze source, so they render the tinted glass surface WITHOUT their
 * own Haze effect node — nesting an effect area inside the source's
 * backdrop is Haze's second IllegalArgumentException, defused here
 * structurally. Real blur is reserved for true overlay chrome (nav
 * bar, rail, overlay bars) via [frostedGlass]. Under FROSTED the card
 * keeps its mostly-translucent tint over the shell background; the
 * tint pairs are pinned by the contrast gate.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable () -> Unit
) {
    val glass = LocalGlassSettings.current
    val shape = RoundedCornerShape(glass.cardRadius)
    val surfaceColor = glass.glassSurface(MaterialTheme.colorScheme.surface)
    val border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    if (onClick == null) {
        Surface(
            modifier = modifier,
            shape = shape,
            color = surfaceColor,
            border = border,
            shadowElevation = glass.cardElevation
        ) {
            Column(Modifier.padding(contentPadding)) { content() }
        }
    } else {
        val interaction = remember { MutableInteractionSource() }
        Surface(
            onClick = onClick,
            modifier = modifier.pressSquish(interaction),
            shape = shape,
            color = surfaceColor,
            border = border,
            shadowElevation = glass.cardElevation,
            interactionSource = interaction
        ) {
            Column(Modifier.padding(contentPadding)) { content() }
        }
    }
}

/** The design's full-pill primary action (ember amber), with the
 * ColorOS press squish + haptic tick from the motion engine. */
@Composable
fun XreaderPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val interaction = remember { MutableInteractionSource() }
    Button(
        onClick = onClick,
        modifier = modifier.pressSquish(interaction, enabled = enabled),
        enabled = enabled,
        interactionSource = interaction,
        shape = RoundedCornerShape(50),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        ),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 14.dp)
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/** Small rounded status readout (design: downloaded = sage, AI = violet,
 * progress = amber). A readout, never a fake button.
 *
 * v1.0.1: the label colour is no longer the raw accent — sage/violet
 * labels on their own tint were 3.3–3.9:1. The pill keeps the accent
 * as its tint, but the TEXT is nudged toward white (dark scheme) or
 * black (light scheme) until it reaches 4.5:1 against the effective
 * pill background (accent at 16% over the surface). */
@Composable
fun StatusPill(text: String, color: Color, modifier: Modifier = Modifier) {
    val surface = MaterialTheme.colorScheme.surface
    val pillBackground = app.xreader.ui.theme.Contrast.blendOver(color, surface, 0.16f)
    val toward = if (surface.luminance() < 0.5f) Color.White else Color.Black
    val textColor = app.xreader.ui.theme.Contrast.ensureContrast(color, pillBackground, toward)
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = color.copy(alpha = 0.16f),
        contentColor = textColor
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

/**
 * The compact header every tab screen opens with (the ReTranslate v1.1.2
 * lesson: no tall app-bar band eating the top of the screen). Eyebrow +
 * Jakarta title, optional trailing action.
 */
@Composable
fun ScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    eyebrow: String? = null,
    action: @Composable (() -> Unit)? = null
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            if (eyebrow != null) {
                Text(
                    eyebrow.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = primaryTextColor()
                )
            }
            Text(title, style = MaterialTheme.typography.headlineMedium)
        }
        if (action != null) action()
    }
}

/** Designed empty state (PLAN §2.4 #32): plain words + the one obvious
 * next action. Every list screen renders this instead of a blank void. */
@Composable
fun EmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center
        )
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        if (actionLabel != null && onAction != null) {
            XreaderPrimaryButton(text = actionLabel, onClick = onAction)
        }
    }
}
