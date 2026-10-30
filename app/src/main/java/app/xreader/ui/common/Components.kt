package app.xreader.ui.common
import app.xreader.ui.theme.errorTextColor
import app.xreader.ui.theme.primaryTextColor

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.EaseInOutSine
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.xreader.core.motion.pressBounce
import app.xreader.core.motion.pressSquish
import app.xreader.ui.theme.GlassSettings
import app.xreader.ui.theme.LiquidGlassBorderBright
import app.xreader.ui.theme.LiquidGlassFillDark
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
 * background.
 *
 * v1.0.4 (D1 topology change): the ONE haze source is the shell's
 * mesh backdrop layer — a SIBLING behind the content, never an
 * ancestor of any effect node. That is what makes it safe for
 * content surfaces ([GlassCard], the nav chrome) to carry this
 * effect: an effect node inside the source's own subtree was
 * Haze's second IAE (the other half of R01), and that structure
 * no longer exists anywhere in the shell.
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
 * v1.0.4 (D1/D6/D7): under FROSTED the card is a REAL Haze effect
 * surface. The shell's mesh backdrop is the single haze source and a
 * sibling layer behind the content (see [frostedGlass]), so a card
 * effect node never sits inside the source — the R01 structure is
 * defused by topology, not by withholding blur. The card's own fill
 * drops to the user's D6 values so the blur carries the surface
 * (white 10% in dark mode; a light 35% surface veil in light mode),
 * and the dark-mode border takes his specular gradient (white 30%
 * at the top fading down). TINTED/SOLID render exactly as before —
 * no effect node, the tinted [GlassSettings.glassSurface] fill whose
 * pairs the contrast gate pins. [bouncePress] swaps the clickable
 * variant's MicroPress squish for the Apple bounce (D5) — the hero
 * and other showcase cards opt in.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    bouncePress: Boolean = false,
    content: @Composable () -> Unit
) {
    val glass = LocalGlassSettings.current
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(glass.cardRadius)
    val dark = scheme.background.luminance() < 0.5f
    val surfaceColor = if (glass.frosted) {
        if (dark) LiquidGlassFillDark else scheme.surface.copy(alpha = 0.35f)
    } else {
        glass.glassSurface(scheme.surface)
    }
    val border = if (glass.frosted && dark) {
        BorderStroke(
            1.dp,
            Brush.verticalGradient(
                listOf(LiquidGlassBorderBright, Color.White.copy(alpha = 0.08f))
            )
        )
    } else {
        BorderStroke(1.dp, scheme.outline)
    }
    val effectModifier = modifier.frostedGlass(glass, scheme.surface)
    if (onClick == null) {
        Surface(
            modifier = effectModifier,
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
            modifier = effectModifier.then(
                if (bouncePress) Modifier.pressBounce(interaction)
                else Modifier.pressSquish(interaction)
            ),
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

/**
 * v1.0.5 (D1): one action inside a bottom sheet — a glass row with
 * an icon and a label, never a bare text line floating in a tall
 * black sheet (the v1.0.4 Library long-press sheet was exactly
 * that). Built on [GlassCard], so a sheet consumes glass exactly
 * the way every other surface does — the same frostedGlass path,
 * the same specular border, and the Apple bounce press (D5).
 * [caption] carries the honest extra (a chapter count, a size);
 * [destructive] tints icon and label with the error colour so a
 * dangerous row reads as dangerous before it's tapped.
 */
@Composable
fun GlassSheetActionRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    caption: String? = null,
    destructive: Boolean = false,
    enabled: Boolean = true
) {
    val labelColor = if (destructive) {
        errorTextColor()
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    GlassCard(
        modifier = modifier.fillMaxWidth(),
        onClick = if (enabled) onClick else ({}),
        bouncePress = true,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (destructive) {
                    errorTextColor()
                } else {
                    MaterialTheme.colorScheme.primary
                }
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    label,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (enabled) {
                        labelColor
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                if (caption != null) {
                    Text(
                        caption,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * v1.0.5 (D2): one destructive choice inside a confirmation
 * dialog's BODY — full-width and left-aligned, stacked vertically
 * with its sibling choices — instead of an action crammed into
 * the dialog's button row. The v1.0.4 Remove dialog put a Column
 * of two long TextButtons in the confirm slot beside the dismiss
 * button, and "Remove book and downloaded chapters (0.0 MB)"
 * wrapped and crowded "Keep the book" (the user's screenshot).
 * With this pattern the choices stack in the body and the calm
 * way out sits ALONE in the button row, never wrapped.
 */
@Composable
fun DialogDangerOption(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(
            label,
            color = errorTextColor(),
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Start
        )
    }
}

/** The design's full-pill primary action (ember amber), with the
 * Apple bounce press + haptic tick from the motion engine
 * (v1.0.4, D5: primary buttons are on the user's bounce list). */
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
        modifier = modifier.pressBounce(interaction, enabled = enabled),
        enabled = enabled,
        interactionSource = interaction,
        shape = RoundedCornerShape(50),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        ),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 14.dp)
    ) {
        // v1.0.6.3 (P1): a primary button's label NEVER wraps —
        // a starved row must shrink elsewhere, not fold the
        // words into a tall pill (the Library "Add / a / boo / k"
        // defect).
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            softWrap = false
        )
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

/**
 * The D13 hover gate, shared by the tab screens (v1.0.4, Stage 2):
 * the [EmptyState] hover drift is allowed only when motion is on
 * for this user AND the device's motion tier is above Minimal —
 * the same rule Library applied inline in Stage 1 (full motion
 * tier only, never under reduce-motion, static on the 2 GB tier).
 */
@Composable
fun rememberHoverEmptyState(reduceMotion: Boolean): Boolean {
    val context = androidx.compose.ui.platform.LocalContext.current
    val totalRamMb = remember(context) {
        runCatching {
            val am = context.getSystemService(android.content.Context.ACTIVITY_SERVICE)
                as android.app.ActivityManager
            val info = android.app.ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            info.totalMem / (1024L * 1024L)
        }.getOrDefault(4096L)
    }
    return !reduceMotion &&
        app.xreader.core.motion.SpringMotionEngine.tierFor(
            totalRamMb, animationsOn = true
        ) != app.xreader.core.motion.SpringMotionEngine.MotionTier.Minimal
}

/** Designed empty state (PLAN §2.4 #32): plain words + the one obvious
 * next action. Every list screen renders this instead of a blank void.
 *
 * v1.0.4 (D13): [hover] adds the gentle floating treatment from the
 * user's Downloads/History mocks — a slow ±10 dp sine drift so an
 * empty screen feels alive. Callers own the perf decision (motion
 * tier / reduce-motion): pass hover only where the device tier
 * allows it; the default stays static. */
@Composable
fun EmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    hover: Boolean = false
) {
    val hoverModifier = if (hover) {
        val transition = rememberInfiniteTransition(label = "emptyStateHover")
        val offsetY by transition.animateFloat(
            initialValue = -10f,
            targetValue = 10f,
            animationSpec = infiniteRepeatable(
                animation = tween(2000, easing = EaseInOutSine),
                repeatMode = RepeatMode.Reverse
            ),
            label = "emptyStateHoverY"
        )
        Modifier.offset(y = offsetY.dp)
    } else {
        Modifier
    }
    Column(
        modifier = modifier
            .then(hoverModifier)
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
