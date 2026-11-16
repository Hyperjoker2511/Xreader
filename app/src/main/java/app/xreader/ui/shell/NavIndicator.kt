package app.xreader.ui.shell

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.xreader.core.motion.SpringMotionEngine
import app.xreader.core.motion.segmentColorSpec
import app.xreader.core.motion.toSpec
import app.xreader.data.library.NavBarStyle
import app.xreader.data.library.NavIndicatorStyle
import app.xreader.data.prefs.PerfTier
import kotlin.math.roundToInt

/**
 * Nav-bar styles (v1.6.2, SPEC-NAVBAR-STYLES-2026-11-16): the
 * dock indicator's renderers. ONE host ([IndicatorHost]) sits
 * where the direct GooeyIndicator call used to sit in the dock
 * and dispatches on the user's chosen style; every decision
 * (which style survives this tier, which colour sits where,
 * how fast it travels, each frame's geometry) is
 * [NavIndicatorLogic]'s pure math — this file only draws.
 *
 * The shared [travel] Animatable is the centre-x (px, bar
 * coordinates) every travelling style reads: SLIDE / GLOW draw
 * from it here, NOTCH's bar shape reads it in the surface, and
 * BUBBLE's overlay reads it outside the surface (the one style
 * that must escape the bar's clip). The host drives it on the
 * tier's travel spring — an Animatable retarget keeps the
 * current velocity, so a mid-flight tap re-aims the SAME
 * moving object (the spec's interruptibility bar). GOOEY keeps
 * its own melt driver inside [GooeyIndicator]; PLAIN draws the
 * settled pill at the target and never touches [travel].
 */

/** How far the BUBBLE's disc extends above the bar's top edge. */
internal val BubbleProud = 30.dp

/** The BUBBLE disc's diameter. */
internal val BubbleDiameter = 52.dp

/** How far the BUBBLE's centre rides above the bar's top edge. */
internal val BubbleRise = 4.dp

/** SLIDE's disc diameter (before the pill-height cap). */
private val SlideDiameter = 44.dp

/** GLOW's halo radius. */
private val GlowRadius = 26.dp

/** GLOW's dot diameter. */
private val GlowDotDiameter = 6.dp

@Composable
fun IndicatorHost(
    style: NavIndicatorStyle,
    colour: Color,
    targetCentreX: Float,
    slotWidthPx: Float,
    topPx: Float,
    heightPx: Float,
    travel: Animatable<Float, AnimationVector1D>,
    perfTier: PerfTier,
    reduceMotion: Boolean,
    modifier: Modifier = Modifier
) {
    val effective = NavIndicatorLogic.effectiveStyle(style, perfTier, reduceMotion)

    // Drive the shared travel for the styles that read it. The
    // first known target SNAPS (there is nothing to travel
    // from); every later target animates on the tier's spring.
    var travelInit by remember { mutableStateOf(false) }
    LaunchedEffect(targetCentreX, effective, perfTier) {
        if (effective == NavIndicatorStyle.GOOEY ||
            effective == NavIndicatorStyle.PLAIN
        ) {
            return@LaunchedEffect
        }
        if (!travelInit) {
            travel.snapTo(targetCentreX)
            travelInit = true
        } else {
            travel.animateTo(
                targetCentreX,
                NavIndicatorLogic.navTravelSpec(perfTier)
            )
        }
    }

    when (effective) {
        NavIndicatorStyle.GOOEY -> GooeyIndicator(
            targetCentreX = targetCentreX,
            slotWidthPx = slotWidthPx,
            topPx = topPx,
            heightPx = heightPx,
            colour = colour,
            perfTier = perfTier,
            reduceMotion = reduceMotion,
            modifier = modifier
        )

        NavIndicatorStyle.PLAIN -> Canvas(modifier) {
            // The settled pill, at the target, instantly — the
            // shipped reduced-motion frame, now a chosen style.
            if (slotWidthPx <= 0f || heightPx <= 0f) return@Canvas
            drawRoundRect(
                color = colour,
                topLeft = Offset(targetCentreX - slotWidthPx / 2f, topPx),
                size = Size(slotWidthPx, heightPx),
                cornerRadius = CornerRadius(heightPx / 2f, heightPx / 2f)
            )
        }

        NavIndicatorStyle.SLIDE -> Canvas(modifier) {
            if (heightPx <= 0f) return@Canvas
            val radius = NavIndicatorLogic.slideRadiusPx(
                heightPx, SlideDiameter.toPx()
            )
            drawCircle(
                color = colour,
                radius = radius,
                center = Offset(travel.value, topPx + heightPx / 2f)
            )
        }

        NavIndicatorStyle.GLOW -> Canvas(modifier) {
            if (heightPx <= 0f) return@Canvas
            val centre = Offset(travel.value, topPx + heightPx / 2f)
            val glowRadius = GlowRadius.toPx()
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        colour.copy(alpha = 0.38f), Color.Transparent
                    ),
                    center = centre,
                    radius = glowRadius
                ),
                radius = glowRadius,
                center = centre
            )
            val dotRadius = GlowDotDiameter.toPx() / 2f
            drawCircle(
                color = colour,
                radius = dotRadius,
                center = Offset(
                    travel.value,
                    NavIndicatorLogic.glowDotCentreYPx(
                        topPx, heightPx, dotRadius, 5.dp.toPx()
                    )
                )
            )
        }

        // BUBBLE is drawn by the overlay outside the bar's clip
        // and NOTCH by the bar's own shape — the host's only job
        // for both is driving the shared travel (above).
        NavIndicatorStyle.BUBBLE, NavIndicatorStyle.NOTCH ->
            Box(modifier) {}
    }
}

/**
 * The BUBBLE, drawn OUTSIDE the bar surface: the disc rides
 * proud of the bar's top edge, which the surface's clip would
 * cut off if it were drawn inside. The caller places this in a
 * box exactly [BubbleProud] taller than the bar and offset up
 * by the same amount, so overlay coordinates share the bar's x
 * and the bar's top edge sits at y = [BubbleProud]. The disc,
 * its soft shadow scrim on the bar's top edge (the cheap
 * notch illusion — no path cutting), and the selected icon
 * itself ride the shared [travel] value: the icon is drawn
 * HERE, inside the disc, because the slot's own icon (hidden
 * by NavSlot under this style) cannot leave the bar's clip.
 * Nothing in here is interactive — touches pass through.
 */
@Composable
fun BubbleIndicatorOverlay(
    travel: Animatable<Float, AnimationVector1D>,
    icon: ImageVector,
    colour: Color,
    onColour: Color,
    showScrim: Boolean,
    modifier: Modifier = Modifier
) {
    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            val proudPx = BubbleProud.toPx()
            val frame = NavIndicatorLogic.bubbleFrame(
                centreX = travel.value,
                diameterPx = BubbleDiameter.toPx(),
                risePx = BubbleRise.toPx()
            )
            val centre = Offset(frame.centreX, proudPx + frame.centreY)
            if (showScrim) {
                val scrimCentre = Offset(frame.centreX, proudPx + 2.dp.toPx())
                val scrimRadius = 30.dp.toPx()
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.28f), Color.Transparent
                        ),
                        center = scrimCentre,
                        radius = scrimRadius
                    ),
                    radius = scrimRadius,
                    center = scrimCentre
                )
            }
            drawCircle(color = colour, radius = frame.radius, center = centre)
        }
        // The carried icon, centred on the disc — its offset
        // reads the travel value in the layout phase, so it
        // rides the disc without recomposing per frame.
        Box(
            Modifier.offset {
                val proudPx = BubbleProud.toPx()
                val frame = NavIndicatorLogic.bubbleFrame(
                    centreX = travel.value,
                    diameterPx = BubbleDiameter.toPx(),
                    risePx = BubbleRise.toPx()
                )
                val halfIcon = 12.dp.toPx()
                IntOffset(
                    (frame.centreX - halfIcon).roundToInt(),
                    (proudPx + frame.centreY - halfIcon).roundToInt()
                )
            }
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = onColour
            )
        }
    }
}

/**
 * NOTCH's bar shape: the bar's outline with a concave scoop
 * dipped into its top edge around the animated indicator
 * centre. The centre is read from [centreX] INSIDE
 * [createOutline] — a snapshot read, so the clip, the glass
 * fill and the rim all follow the travel frame by frame. The
 * scoop's numbers are [NavIndicatorLogic.notchScoop]'s pure
 * math; this class only walks the path.
 */
class NotchBarShape(
    private val centreX: () -> Float,
    private val slotWidthPx: () -> Float,
    private val docked: Boolean
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density
    ): Outline {
        val scoop = NavIndicatorLogic.notchScoop(
            barWidthPx = size.width,
            barHeightPx = size.height,
            centreXPx = centreX(),
            slotWidthPx = slotWidthPx(),
            docked = docked
        )
        val w = size.width
        val h = size.height
        val r = if (docked) 0f else h / 2f
        val cx = scoop.centreX
        val hw = scoop.halfWidth
        val d = scoop.depth
        val path = Path().apply {
            // The scoop first: two cubics with horizontal
            // tangents at the rim and at the dip's floor, so
            // the top edge flows into and out of the scoop
            // without a kink.
            moveTo(cx - hw, 0f)
            cubicTo(cx - hw * 0.5f, 0f, cx - hw * 0.5f, d, cx, d)
            cubicTo(cx + hw * 0.5f, d, cx + hw * 0.5f, 0f, cx + hw, 0f)
            if (r > 0.5f) {
                lineTo(w - r, 0f)
                arcTo(Rect(w - 2f * r, 0f, w, 2f * r), -90f, 90f, false)
                lineTo(w, h - r)
                arcTo(Rect(w - 2f * r, h - 2f * r, w, h), 0f, 90f, false)
                lineTo(r, h)
                arcTo(Rect(0f, h - 2f * r, 2f * r, h), 90f, 90f, false)
                lineTo(0f, r)
                arcTo(Rect(0f, 0f, 2f * r, 2f * r), 180f, 90f, false)
                lineTo(cx - hw, 0f)
            } else {
                lineTo(w, 0f)
                lineTo(w, h)
                lineTo(0f, h)
                lineTo(0f, 0f)
                lineTo(cx - hw, 0f)
            }
            close()
        }
        return Outline.Generic(path)
    }
}

/**
 * The live miniature dock at the top of Customise's Navigation
 * section (SPEC-NAVBAR-STYLES-2026-11-16, gap decision 2 —
 * KEPT): five fake slots over the REAL [IndicatorHost], the
 * REAL notch shape and the REAL bubble overlay, driven by the
 * section's current choices — tapping a slot animates the
 * chosen style exactly as the dock will, so a style is judged
 * where it is set, not after a phone-proof round. The bar body
 * is a plain filled shape (no glass — the preview's job is the
 * indicator's motion, not the surface's material).
 */
@Composable
fun NavIndicatorPreview(
    style: NavIndicatorStyle,
    colour: Color,
    onColour: Color,
    barStyle: NavBarStyle,
    perfTier: PerfTier,
    reduceMotion: Boolean,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    var widthPx by remember { mutableIntStateOf(0) }
    val barHeight = 56.dp
    val barHeightPx = with(density) { barHeight.roundToPx() }
    val insetPx = with(density) { 6.dp.roundToPx() }
    var selectedIndex by remember { mutableIntStateOf(0) }
    val itemCount = Tab.entries.size
    val slotWidth = NavGeometry.pillWidthPx(widthPx, itemCount, insetPx)
    val pillHeight = NavGeometry.pillHeightPx(barHeightPx, insetPx)
    val targetX = NavGeometry.pillTargetXPx(
        widthPx, itemCount, selectedIndex, insetPx
    )
    val pillY = NavGeometry.pillTargetYPx(barHeightPx, insetPx)
    val travel = remember { Animatable(0f) }
    val effective = NavIndicatorLogic.effectiveStyle(style, perfTier, reduceMotion)
    // Read the travel value in composition: the notch shape's
    // centre lambda reads it too, but Modifier.clip only
    // re-derives its outline when the shape instance changes —
    // this read recomposes the preview per travel frame so the
    // miniature scoop follows exactly like the dock's (whose
    // surface re-reads the outline inside drawWithCache).
    @Suppress("UNUSED_VARIABLE") val travelFrame = travel.value
    val docked = barStyle == NavBarStyle.DOCKED_BAR
    val shape: Shape = if (effective == NavIndicatorStyle.NOTCH) {
        NotchBarShape(
            centreX = { travel.value },
            slotWidthPx = { slotWidth.toFloat() },
            docked = docked
        )
    } else if (docked) {
        RoundedCornerShape(12.dp)
    } else {
        RoundedCornerShape(50)
    }
    Box(
        modifier
            .fillMaxWidth()
            .padding(top = if (effective == NavIndicatorStyle.BUBBLE) {
                BubbleProud
            } else {
                0.dp
            })
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(barHeight)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .onSizeChanged { widthPx = it.width }
        ) {
            if (slotWidth > 0 && pillHeight > 0) {
                IndicatorHost(
                    style = style,
                    colour = colour,
                    targetCentreX = targetX + slotWidth / 2f,
                    slotWidthPx = slotWidth.toFloat(),
                    topPx = pillY.toFloat(),
                    heightPx = pillHeight.toFloat(),
                    travel = travel,
                    perfTier = perfTier,
                    reduceMotion = reduceMotion,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Row(Modifier.fillMaxSize()) {
                Tab.entries.forEachIndexed { index, tab ->
                    PreviewSlot(
                        tab = tab,
                        selected = index == selectedIndex,
                        effective = effective,
                        colour = colour,
                        onColour = onColour,
                        reduceMotion = reduceMotion,
                        onClick = { selectedIndex = index },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    )
                }
            }
        }
        if (effective == NavIndicatorStyle.BUBBLE && slotWidth > 0) {
            BubbleIndicatorOverlay(
                travel = travel,
                icon = Tab.entries[selectedIndex].icon,
                colour = colour,
                onColour = onColour,
                showScrim = NavIndicatorLogic.bubbleShowsScrim(
                    effective, perfTier
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(barHeight + BubbleProud)
                    .offset(y = -BubbleProud)
            )
        }
    }
}

/** One fake slot of the preview dock: the real tint and rise
 * decisions ([NavIndicatorLogic]) at miniature scale. */
@Composable
private fun PreviewSlot(
    tab: Tab,
    selected: Boolean,
    effective: NavIndicatorStyle,
    colour: Color,
    onColour: Color,
    reduceMotion: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interaction = remember { MutableInteractionSource() }
    val indicatorArgb = colour.toArgb().toLong() and 0xFFFFFFFFL
    val onIndicatorArgb = onColour.toArgb().toLong() and 0xFFFFFFFFL
    val targetTint = if (selected) {
        Color(
            NavIndicatorLogic.navIconArgb(
                effective, indicatorArgb, onIndicatorArgb
            ).toInt()
        )
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val tint by animateColorAsState(
        targetValue = targetTint,
        animationSpec = if (reduceMotion) {
            androidx.compose.animation.core.snap()
        } else {
            segmentColorSpec()
        },
        label = "previewSlotTint"
    )
    val rise by animateDpAsState(
        targetValue = when {
            !selected -> 0.dp
            effective == NavIndicatorStyle.BUBBLE -> (-4).dp
            effective == NavIndicatorStyle.NOTCH -> (-6).dp
            else -> 0.dp
        },
        animationSpec = if (reduceMotion) {
            androidx.compose.animation.core.snap()
        } else {
            SpringMotionEngine.Spatial.toSpec()
        },
        label = "previewSlotRise"
    )
    val hideIcon = selected && effective == NavIndicatorStyle.BUBBLE
    Box(
        modifier
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier.offset(y = rise),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                tab.icon,
                contentDescription = tab.label,
                tint = if (hideIcon) Color.Transparent else tint
            )
        }
    }
}
