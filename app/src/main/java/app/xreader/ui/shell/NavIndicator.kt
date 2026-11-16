package app.xreader.ui.shell

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
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
import kotlinx.coroutines.launch

/**
 * Nav-bar styles (v1.6.2, SPEC-NAVBAR-STYLES-2026-11-16) + the
 * nav engine round (SPEC-NAV-ENGINE-2026-11-16): the dock
 * indicator's renderers. ONE host ([IndicatorHost]) sits where
 * the direct GooeyIndicator call used to sit in the dock and
 * dispatches on the user's chosen style; every decision (which
 * style survives this tier, which colour sits where, how fast
 * it travels, each frame's geometry) is [NavIndicatorLogic]'s
 * pure math — this file only draws.
 *
 * Three animation objects, owned by the CALLER (the dock /
 * the preview) and driven here — hoisted in the
 * travel-choreography round (SPEC-NAV-TRAVEL-WAVE-2026-11-16
 * §B.1) so the dock can read the same values for the slots'
 * label coverage without a second travel Animatable:
 * - the shared [travel] Animatable — the centre-x (px, bar
 *   coordinates) the centre-riding styles read: SLIDE / GLOW /
 *   LENS / SEGMENTED / SQUIRCLE / RING / DOT / TOPLINE draw
 *   from it here, NOTCH's bar shape reads it in the surface,
 *   BUBBLE's overlay reads it outside the surface, and WAVE's
 *   bar shape + circle overlay read it the same two ways (the
 *   styles that must escape the bar's clip). MINIMAL and
 *   FLASHY draw nothing from it here, but it is driven for
 *   them too — their slot content follows its coverage
 *   (§B.1). The host drives it on the tier's travel spring —
 *   an Animatable retarget keeps the current velocity, so a
 *   mid-flight tap re-aims the SAME moving object (the spec's
 *   interruptibility bar).
 * - the lead / trail EDGE Animatables — the stretched pill of
 *   GOOEY (rebuilt: the shipped neck/trail melt never escaped
 *   the pill's outline on a 1-slot jump) and WORM. The edge
 *   toward the target rides the lead spring, the other edge
 *   lags on TrailLag; the stadium drawn between them IS the
 *   bridge, at any distance.
 * - PLAIN draws the settled pill at the target and touches
 *   nothing; MINIMAL / EXPANDING / FLASHY draw nothing here
 *   at all — they live in the slots and the row's weights.
 *
 * Painters read animation values in the DRAW phase only (the
 * Canvas lambdas), so a travelling indicator never recomposes
 * the bar per frame; at settle every Animatable stops and the
 * idle cost is zero.
 */

/** How far the BUBBLE's disc extends above the bar's top edge. */
internal val BubbleProud = 30.dp

/** The BUBBLE disc's diameter. */
internal val BubbleDiameter = 52.dp

/** How far the BUBBLE's centre rides above the bar's top edge. */
internal val BubbleRise = 4.dp

/** SLIDE's disc diameter (before the pill-height cap). */
private val SlideDiameter = 44.dp

/** WAVE's circle diameter, cradled in the bar's waved top
 * edge. Sized so the circle's bottom (centre ≈ 25 dp into
 * the 64 dp bar, bottom ≈ 45 dp) clears the slot label's
 * bottom band — see NavSlot's WAVE arrangement note. */
internal val WaveCircleDiameter = 40.dp

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
    /** The stretch engine's two edges (GOOEY / WORM) — owned
     * by the caller like [travel], driven here. */
    leadEdge: Animatable<Float, AnimationVector1D>,
    trailEdge: Animatable<Float, AnimationVector1D>,
    perfTier: PerfTier,
    reduceMotion: Boolean,
    /** Slot layout for the SEGMENTED dividers and the
     * SQUIRCLE cell: how many slots, the centre-to-centre
     * pitch, and the first slot's centre (all px). Zero =
     * unknown (dividers skipped). */
    slotCount: Int = 0,
    slotPitchPx: Float = 0f,
    firstSlotCentreX: Float = 0f,
    modifier: Modifier = Modifier
) {
    val effective = NavIndicatorLogic.effectiveStyle(style, perfTier, reduceMotion)

    // Drive the shared centre travel for the styles that read
    // it. The first known target SNAPS (there is nothing to
    // travel from); every later target animates on the tier's
    // spring. The run's from/to are remembered so the LENS can
    // read its trip progress in the draw phase. MINIMAL and
    // FLASHY are driven too (travel-choreography round,
    // SPEC-NAV-TRAVEL-WAVE-2026-11-16 §B.1): they draw nothing
    // from the centre here, but their slots' label / swap
    // coverage is computed from it in the dock — the travel
    // must move for the coverage to move.
    var travelInit by remember { mutableStateOf(false) }
    var travelFrom by remember { mutableFloatStateOf(0f) }
    var travelTo by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(targetCentreX, effective, perfTier) {
        if (effective == NavIndicatorStyle.GOOEY ||
            effective == NavIndicatorStyle.WORM ||
            effective == NavIndicatorStyle.PLAIN ||
            effective == NavIndicatorStyle.EXPANDING
        ) {
            return@LaunchedEffect
        }
        if (!travelInit) {
            travel.snapTo(targetCentreX)
            travelFrom = targetCentreX
            travelTo = targetCentreX
            travelInit = true
        } else {
            travelFrom = travel.value
            travelTo = targetCentreX
            travel.animateTo(
                targetCentreX,
                NavIndicatorLogic.navTravelSpec(perfTier)
            )
        }
    }

    // The stretch engine (nav engine round §1): the caller's
    // two edge Animatables. On a target change both animate
    // to the target slot's edges — the edge toward the target
    // on the style's lead spring, the far edge on TrailLag —
    // continuing from their current values AND velocities
    // (Animatable's contract; never a snap except the first
    // placement, which snaps all three objects).
    var edgesInit by remember { mutableStateOf(false) }
    var runFromLead by remember { mutableFloatStateOf(0f) }
    var runToLead by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(targetCentreX, effective, perfTier) {
        if (effective != NavIndicatorStyle.GOOEY &&
            effective != NavIndicatorStyle.WORM
        ) {
            return@LaunchedEffect
        }
        if (slotWidthPx <= 0f) return@LaunchedEffect
        val targetLeft = targetCentreX - slotWidthPx / 2f
        val targetRight = targetCentreX + slotWidthPx / 2f
        if (!edgesInit) {
            leadEdge.snapTo(targetRight)
            trailEdge.snapTo(targetLeft)
            runFromLead = targetRight
            runToLead = targetRight
            edgesInit = true
            return@LaunchedEffect
        }
        val currentCentre = (leadEdge.value + trailEdge.value) / 2f
        val movingRight = targetCentreX >= currentCentre
        val leadTarget = if (movingRight) targetRight else targetLeft
        val trailTarget = if (movingRight) targetLeft else targetRight
        runFromLead = leadEdge.value
        runToLead = leadTarget
        val leadSpec = NavIndicatorLogic.leadEdgeSpec(effective, perfTier)
        val trailSpec = NavIndicatorLogic.trailEdgeSpec(effective, perfTier)
        launch { leadEdge.animateTo(leadTarget, leadSpec) }
        launch { trailEdge.animateTo(trailTarget, trailSpec) }
    }
    // The lead edge's run progress, read by the stretch
    // painter in its draw phase for GOOEY's squash curve.
    val squashProgress: () -> Float = {
        if (runToLead != runFromLead) {
            ((leadEdge.value - runFromLead) / (runToLead - runFromLead))
                .coerceIn(0f, 1f)
        } else {
            1f
        }
    }
    // The centre's run progress, read by the lens painter for
    // its first-third squash.
    val tripProgress: () -> Float = {
        if (travelTo != travelFrom) {
            ((travel.value - travelFrom) / (travelTo - travelFrom))
                .coerceIn(0f, 1f)
        } else {
            1f
        }
    }

    when (effective) {
        NavIndicatorStyle.GOOEY, NavIndicatorStyle.WORM -> StretchIndicator(
            style = effective,
            colour = colour,
            leadEdge = leadEdge,
            trailEdge = trailEdge,
            topPx = topPx,
            heightPx = heightPx,
            slotWidthPx = slotWidthPx,
            slotPitchPx = slotPitchPx,
            squashProgress = squashProgress,
            modifier = modifier
        )

        NavIndicatorStyle.LENS -> LensIndicator(
            travel = travel,
            colour = colour,
            topPx = topPx,
            heightPx = heightPx,
            slotWidthPx = slotWidthPx,
            tripProgress = tripProgress,
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

        NavIndicatorStyle.SEGMENTED -> Canvas(modifier) {
            if (heightPx <= 0f || slotWidthPx <= 0f) return@Canvas
            // The track's hairline dividers: static geometry,
            // white 0.08, 1 dp, inset a quarter of the bar from
            // both edges.
            if (slotCount > 1 && slotPitchPx > 0f) {
                val divider = Color.White.copy(alpha = 0.08f)
                val lineWidth = 1.dp.toPx()
                val divTop = size.height * 0.25f
                val divBottom = size.height * 0.75f
                for (i in 1 until slotCount) {
                    val x = NavIndicatorLogic.slotBoundaryXPx(
                        firstSlotCentreX, slotPitchPx, i
                    )
                    drawLine(
                        color = divider,
                        start = Offset(x, divTop),
                        end = Offset(x, divBottom),
                        strokeWidth = lineWidth
                    )
                }
            }
            // The raised thumb: a pale stadium inset 3 dp from
            // the slot, riding the centre travel.
            val inset = 3.dp.toPx()
            val thumb = NavIndicatorLogic.segmentedThumb(
                travel.value, slotWidthPx, topPx, heightPx, inset
            )
            val thumbCorner = CornerRadius(
                thumb.heightPx / 2f, thumb.heightPx / 2f
            )
            val thumbTopLeft = Offset(thumb.leftPx, thumb.topPx)
            val thumbSize = Size(thumb.widthPx, thumb.heightPx)
            drawRoundRect(
                color = Color.White.copy(alpha = 0.10f),
                topLeft = thumbTopLeft,
                size = thumbSize,
                cornerRadius = thumbCorner
            )
            drawRoundRect(
                color = Color.White.copy(alpha = 0.14f),
                topLeft = thumbTopLeft,
                size = thumbSize,
                cornerRadius = thumbCorner,
                style = Stroke(width = 1.dp.toPx())
            )
        }

        NavIndicatorStyle.SQUIRCLE -> Canvas(modifier) {
            if (heightPx <= 0f || slotWidthPx <= 0f) return@Canvas
            val pitch = if (slotPitchPx > 0f) slotPitchPx else slotWidthPx
            val rect = NavIndicatorLogic.squircleRect(
                centreX = travel.value,
                pitchPx = pitch,
                barTopPx = 0f,
                barHeightPx = size.height,
                insetPx = 4.dp.toPx()
            )
            val corner = 14.dp.toPx()
            drawRoundRect(
                color = colour,
                topLeft = Offset(rect.leftPx, rect.topPx),
                size = Size(rect.widthPx, rect.heightPx),
                cornerRadius = CornerRadius(corner, corner)
            )
        }

        NavIndicatorStyle.RING -> Canvas(modifier) {
            if (heightPx <= 0f) return@Canvas
            // A 2 dp ring around the selected icon only: the
            // circle's diameter is the pill height, its centre
            // the icon's centre inside the slot (the pure
            // stack math in NavIndicatorLogic).
            val centreY = NavIndicatorLogic.ringCentreYPx(
                topPx = topPx,
                heightPx = heightPx,
                iconPx = 24.dp.toPx(),
                labelPx = 14.dp.toPx()
            )
            drawCircle(
                color = colour,
                radius = heightPx / 2f,
                center = Offset(travel.value, centreY),
                style = Stroke(width = 2.dp.toPx())
            )
        }

        NavIndicatorStyle.DOT -> Canvas(modifier) {
            if (heightPx <= 0f) return@Canvas
            // A matte 6 dp dot below the label — no halo; the
            // halo is exactly what makes GLOW a different style.
            val dotRadius = 3.dp.toPx()
            drawCircle(
                color = colour,
                radius = dotRadius,
                center = Offset(
                    travel.value,
                    NavIndicatorLogic.dotCentreYPx(
                        topPx, heightPx, dotRadius, 2.dp.toPx()
                    )
                )
            )
        }

        NavIndicatorStyle.TOPLINE -> Canvas(modifier) {
            if (heightPx <= 0f || slotWidthPx <= 0f) return@Canvas
            // A 3 dp rounded line at the bar's top inner edge,
            // 55% of the slot wide, centred on the traveller.
            val lineHeight = 3.dp.toPx()
            val lineY = (topPx / 2f - lineHeight / 2f).coerceAtLeast(0f)
            drawRoundRect(
                color = colour,
                topLeft = Offset(
                    NavIndicatorLogic.topLineLeftPx(travel.value, slotWidthPx),
                    lineY
                ),
                size = Size(
                    NavIndicatorLogic.topLineWidthPx(slotWidthPx),
                    lineHeight
                ),
                cornerRadius = CornerRadius(lineHeight / 2f, lineHeight / 2f)
            )
        }

        // BUBBLE is drawn by the overlay outside the bar's clip
        // and NOTCH by the bar's own shape — the host's only job
        // for both is driving the shared travel (above). WAVE
        // splits the same way (travel-choreography round §C):
        // the bar's own shape carries the dip, the circle rides
        // in the overlay outside the clip (its crown sits in the
        // carved void, which the surface's clip would cut).
        // MINIMAL draws no shape at all (the slots wear the
        // colour), EXPANDING lives in the row's weights and the
        // selected slot's own pill, FLASHY inside the selected
        // slot.
        NavIndicatorStyle.BUBBLE, NavIndicatorStyle.NOTCH,
        NavIndicatorStyle.WAVE,
        NavIndicatorStyle.MINIMAL, NavIndicatorStyle.EXPANDING,
        NavIndicatorStyle.FLASHY ->
            Box(modifier) {}
    }
}

/**
 * The stretched pill (nav engine round §1–§2): ONE stadium
 * drawn between the host's two edge Animatables — the bridge
 * is the pill's own body, so it spans any jump distance,
 * unlike the retired neck geometry. GOOEY and WORM share the
 * engine and differ in the height curve: GOOEY squeezes on
 * the trip's own clock (the melt's squash curve,
 * [gooeySquashAt] — 0.70× at mid-trip regardless of distance),
 * WORM thins WITH the stretch itself
 * ([NavIndicatorLogic.wormHeightScale] — down to 0.72× when
 * fully stretched, however long the jump). Settled, both are
 * exactly the slot's pill. Shared by the dock (via
 * [IndicatorHost]) and the Library switch (two stops).
 */
@Composable
fun StretchIndicator(
    style: NavIndicatorStyle,
    colour: Color,
    leadEdge: Animatable<Float, AnimationVector1D>,
    trailEdge: Animatable<Float, AnimationVector1D>,
    topPx: Float,
    heightPx: Float,
    slotWidthPx: Float,
    slotPitchPx: Float,
    squashProgress: () -> Float,
    modifier: Modifier = Modifier
) {
    Canvas(modifier) {
        if (heightPx <= 0f || slotWidthPx <= 0f) return@Canvas
        val frame = NavIndicatorLogic.stretchFrame(
            leadEdge.value, trailEdge.value, slotWidthPx
        )
        if (frame.widthPx <= 0f) return@Canvas
        val heightScale = if (style == NavIndicatorStyle.WORM) {
            NavIndicatorLogic.wormHeightScale(frame.stretchPx, slotPitchPx)
        } else {
            gooeySquashAt(squashProgress())
        }
        val ry = heightPx / 2f * heightScale
        val cy = topPx + heightPx / 2f
        drawRoundRect(
            color = colour,
            topLeft = Offset(frame.leftPx, cy - ry),
            size = Size(frame.widthPx, ry * 2f),
            cornerRadius = CornerRadius(ry, ry)
        )
    }
}

/**
 * The fake liquid lens (nav engine round §2): a stadium lens
 * over the selected slot, DRAWN — no backdrop capture, no
 * RenderEffect, so it costs one Canvas path on any tier that
 * keeps it (MINIMAL degrades it to SLIDE instead). Fill is the
 * indicator colour at 0.28, the rim the same colour at 0.65,
 * and a white specular streak (0.10) lies along the top. The
 * lens rides the centre travel; its WIDTH grows with the
 * travel speed ([NavIndicatorLogic.lensWidthPx]) and its
 * height dips to 0.92 in the first third of the trip
 * ([NavIndicatorLogic.lensHeightScale]).
 */
@Composable
fun LensIndicator(
    travel: Animatable<Float, AnimationVector1D>,
    colour: Color,
    topPx: Float,
    heightPx: Float,
    slotWidthPx: Float,
    tripProgress: () -> Float,
    modifier: Modifier = Modifier
) {
    Canvas(modifier) {
        if (heightPx <= 0f || slotWidthPx <= 0f) return@Canvas
        val stretch = NavIndicatorLogic.lensStretchFactor(
            travel.velocity, slotWidthPx
        )
        val width = NavIndicatorLogic.lensWidthPx(slotWidthPx, stretch)
        val heightScale = NavIndicatorLogic.lensHeightScale(tripProgress())
        val cy = topPx + heightPx / 2f
        val rh = heightPx / 2f * heightScale
        val left = travel.value - width / 2f
        val topLeft = Offset(left, cy - rh)
        val lensSize = Size(width, rh * 2f)
        val corner = CornerRadius(rh, rh)
        drawRoundRect(
            color = colour.copy(alpha = 0.28f),
            topLeft = topLeft,
            size = lensSize,
            cornerRadius = corner
        )
        // The specular streak: a soft white band along the
        // lens's top, inside the fill.
        val streakHeight = rh * 0.55f
        drawRoundRect(
            color = Color.White.copy(alpha = 0.10f),
            topLeft = Offset(left + width * 0.12f, cy - rh + 2.dp.toPx()),
            size = Size(width * 0.76f, streakHeight),
            cornerRadius = CornerRadius(streakHeight / 2f, streakHeight / 2f)
        )
        drawRoundRect(
            color = colour.copy(alpha = 0.65f),
            topLeft = topLeft,
            size = lensSize,
            cornerRadius = corner,
            style = Stroke(width = 1.dp.toPx())
        )
    }
}

/**
 * EXPANDING's slot weight (nav engine round §2): the selected
 * slot widens to [NavIndicatorLogic.EXPANDING_SELECTED_WEIGHT]
 * while the others stay 1.0 — the bar's width never changes,
 * the slots trade room. The weight rides the tier's weight
 * spring; under reduced motion it swaps instantly (the
 * style's widths, without the glide).
 */
@Composable
internal fun expandingSlotWeight(
    selected: Boolean,
    perfTier: PerfTier,
    reduceMotion: Boolean
): Float = expandingSlotWeightState(selected, perfTier, reduceMotion).value

/**
 * The weight as a [androidx.compose.runtime.State] (travel-
 * choreography round, SPEC-NAV-TRAVEL-WAVE-2026-11-16 §B.1):
 * the dock reads the State when building the slot's label
 * coverage, so the coverage lambda can read the LIVE weight
 * in the layout / draw phases instead of a value frozen at
 * composition.
 */
@Composable
internal fun expandingSlotWeightState(
    selected: Boolean,
    perfTier: PerfTier,
    reduceMotion: Boolean
): androidx.compose.runtime.State<Float> =
    animateFloatAsState(
        targetValue = if (selected) {
            NavIndicatorLogic.EXPANDING_SELECTED_WEIGHT
        } else {
            1f
        },
        animationSpec = if (reduceMotion) {
            androidx.compose.animation.core.snap()
        } else {
            NavIndicatorLogic.expandingWeightSpec(perfTier)
        },
        label = "expandingSlotWeight"
    )

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
 * WAVE's bar shape (travel-choreography round,
 * SPEC-NAV-TRAVEL-WAVE-2026-11-16 §C): the bar's outline with
 * a smooth DIP in its top edge around the animated travel
 * centre — the notch's sibling, shallower and wider. The
 * centre is read from [centreX] INSIDE [createOutline] (a
 * snapshot read), so the clip, the glass fill and the rim all
 * follow the travel frame by frame; the one shape governs
 * fill, glass and clip, exactly as the notch taught. The
 * dip's numbers are [NavIndicatorLogic.waveDip]'s pure math;
 * this class only walks the path. The circle that rides in
 * the dip is NOT part of the shape — it is drawn by
 * [WaveIndicatorOverlay], outside the surface's clip, like
 * the bubble.
 */
class WaveBarShape(
    private val centreX: () -> Float,
    private val pitchPx: () -> Float,
    private val docked: Boolean
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density
    ): Outline {
        val dip = NavIndicatorLogic.waveDip(
            barWidthPx = size.width,
            barHeightPx = size.height,
            centreXPx = centreX(),
            pitchPx = pitchPx(),
            docked = docked
        )
        val w = size.width
        val h = size.height
        val r = if (docked) 0f else h / 2f
        val cx = dip.centreX
        val hw = dip.halfWidth
        val d = dip.depth
        val path = Path().apply {
            // The dip first: two cubics with horizontal
            // tangents at the rim and at the trough's floor,
            // so the top edge flows into and out of the dip
            // without a kink (the notch's walk, shallower).
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
 * WAVE's circle, drawn OUTSIDE the bar surface like the
 * bubble: the circle's crown sits in the dip's carved void,
 * which the surface's shape clip would cut off if it were
 * drawn inside. The caller places this in a box sharing the
 * bar's exact bounds, so overlay coordinates ARE bar
 * coordinates. The circle and its carried icon ride the
 * shared [travel] value — the circle's centre-x is the DIP's
 * centre (read through the same [NavIndicatorLogic.waveDip]
 * clamp the shape uses), so circle, dip and icon can never
 * decouple (SPEC-NAV-TRAVEL-WAVE-2026-11-16 §C: one
 * parameter). The slot's own icon hides under this style
 * (NavSlot), as it does under BUBBLE. Nothing in here is
 * interactive — touches pass through.
 */
@Composable
fun WaveIndicatorOverlay(
    travel: Animatable<Float, AnimationVector1D>,
    icon: ImageVector,
    colour: Color,
    onColour: Color,
    slotPitchPx: Float,
    barWidthPx: Float,
    barHeightPx: Float,
    docked: Boolean,
    modifier: Modifier = Modifier
) {
    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            val dip = NavIndicatorLogic.waveDip(
                barWidthPx = barWidthPx,
                barHeightPx = barHeightPx,
                centreXPx = travel.value,
                pitchPx = slotPitchPx,
                docked = docked
            )
            val circle = NavIndicatorLogic.waveCircle(
                centreXPx = dip.centreX,
                diameterPx = WaveCircleDiameter.toPx(),
                dipDepthPx = dip.depth
            )
            drawCircle(
                color = colour,
                radius = circle.radius,
                center = Offset(circle.centreX, circle.centreY)
            )
        }
        // The carried icon, centred on the circle — its offset
        // reads the travel value in the layout phase, so it
        // rides the circle without recomposing per frame (the
        // bubble overlay's idiom).
        Box(
            Modifier.offset {
                val dip = NavIndicatorLogic.waveDip(
                    barWidthPx = barWidthPx,
                    barHeightPx = barHeightPx,
                    centreXPx = travel.value,
                    pitchPx = slotPitchPx,
                    docked = docked
                )
                val circle = NavIndicatorLogic.waveCircle(
                    centreXPx = dip.centreX,
                    diameterPx = WaveCircleDiameter.toPx(),
                    dipDepthPx = dip.depth
                )
                val halfIcon = 12.dp.toPx()
                IntOffset(
                    (circle.centreX - halfIcon).roundToInt(),
                    (circle.centreY - halfIcon).roundToInt()
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
 * The live miniature dock at the top of Customise's Navigation
 * section (SPEC-NAVBAR-STYLES-2026-11-16, gap decision 2 —
 * KEPT): five fake slots over the REAL [IndicatorHost], the
 * REAL notch shape and the REAL bubble overlay, driven by the
 * section's current choices — tapping a slot animates the
 * chosen style exactly as the dock will, so a style is judged
 * where it is set, not after a phone-proof round. The bar body
 * is a plain filled shape (no glass — the preview's job is the
 * indicator's motion, not the surface's material). The nav
 * engine round's styles preview too: EXPANDING widens the
 * fake slots on the same weights, FLASHY settles its selected
 * slot into the label, the lens magnifies its icon. WAVE
 * previews through the same host + wave shape + circle
 * overlay as the dock (travel-choreography round §C).
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
    val pitchPx = NavGeometry.slotWidthPx(widthPx, itemCount)
    val travel = remember { Animatable(0f) }
    // The stretch edges, owned here and driven by the host —
    // the same hoisting the dock uses (travel-choreography
    // round §B.1).
    val leadEdge = remember { Animatable(0f) }
    val trailEdge = remember { Animatable(0f) }
    val effective = NavIndicatorLogic.effectiveStyle(style, perfTier, reduceMotion)
    // Read the travel value in composition: the notch / wave
    // shapes' centre lambdas read it too, but Modifier.clip
    // only re-derives its outline when the shape instance
    // changes — this read recomposes the preview per travel
    // frame so the miniature scoop / dip follows exactly like
    // the dock's (whose surface re-reads the outline inside
    // drawWithCache).
    @Suppress("UNUSED_VARIABLE") val travelFrame = travel.value
    val docked = barStyle == NavBarStyle.DOCKED_BAR
    val shape: Shape = if (effective == NavIndicatorStyle.NOTCH) {
        NotchBarShape(
            centreX = { travel.value },
            slotWidthPx = { slotWidth.toFloat() },
            docked = docked
        )
    } else if (effective == NavIndicatorStyle.WAVE) {
        WaveBarShape(
            centreX = { travel.value },
            pitchPx = { pitchPx.toFloat() },
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
                    leadEdge = leadEdge,
                    trailEdge = trailEdge,
                    perfTier = perfTier,
                    reduceMotion = reduceMotion,
                    slotCount = itemCount,
                    slotPitchPx = pitchPx.toFloat(),
                    firstSlotCentreX = pitchPx / 2f,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Row(Modifier.fillMaxSize()) {
                Tab.entries.forEachIndexed { index, tab ->
                    val slotWeight = if (effective ==
                        NavIndicatorStyle.EXPANDING
                    ) {
                        expandingSlotWeight(
                            selected = index == selectedIndex,
                            perfTier = perfTier,
                            reduceMotion = reduceMotion
                        )
                    } else {
                        1f
                    }
                    PreviewSlot(
                        tab = tab,
                        selected = index == selectedIndex,
                        effective = effective,
                        colour = colour,
                        onColour = onColour,
                        reduceMotion = reduceMotion,
                        onClick = { selectedIndex = index },
                        modifier = Modifier
                            .weight(slotWeight)
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
        if (effective == NavIndicatorStyle.WAVE && slotWidth > 0) {
            // The wave's circle rides in an overlay sharing the
            // bar's exact bounds (no proud offset — the circle
            // lives inside the bar's rect, its crown in the
            // dip's void), exactly as the dock draws it.
            WaveIndicatorOverlay(
                travel = travel,
                icon = Tab.entries[selectedIndex].icon,
                colour = colour,
                onColour = onColour,
                slotPitchPx = pitchPx.toFloat(),
                barWidthPx = widthPx.toFloat(),
                barHeightPx = barHeightPx.toFloat(),
                docked = docked,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(barHeight)
            )
        }
    }
}

/** One fake slot of the preview dock: the real tint and rise
 * decisions ([NavIndicatorLogic]) at miniature scale. The
 * layout styles render their SETTLED states here: EXPANDING's
 * selected slot is a filled pill carrying icon + label (the
 * others icon-only), FLASHY's selected slot shows its label
 * and dot in place of the icon, and LENS magnifies its icon. */
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
    val lensScale by animateFloatAsState(
        targetValue = if (selected && effective == NavIndicatorStyle.LENS) {
            1.12f
        } else {
            1f
        },
        animationSpec = if (reduceMotion) {
            androidx.compose.animation.core.snap()
        } else {
            SpringMotionEngine.Spatial.toSpec()
        },
        label = "previewLensScale"
    )
    // Under BUBBLE and WAVE the selected icon rides in the
    // style's overlay (the disc / the cradled circle) — the
    // slot's own copy hides so the two never double.
    val hideIcon = selected && (
        effective == NavIndicatorStyle.BUBBLE ||
            effective == NavIndicatorStyle.WAVE
        )
    Box(
        modifier
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        when {
            // FLASHY settled: the label stands where the icon
            // was, with its dot under it; the icon is gone.
            selected && effective == NavIndicatorStyle.FLASHY -> Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    tab.label,
                    color = colour,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1
                )
                Box(
                    Modifier
                        .padding(top = 2.dp)
                        .size(6.dp)
                        .background(colour, CircleShape)
                )
            }
            // EXPANDING settled: the selected slot is a filled
            // pill carrying icon + label, on-indicator colours.
            selected && effective == NavIndicatorStyle.EXPANDING -> Row(
                Modifier
                    .background(colour, RoundedCornerShape(50))
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    tab.icon,
                    contentDescription = tab.label,
                    tint = onColour
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    tab.label,
                    color = onColour,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1
                )
            }
            else -> Box(
                Modifier
                    .offset(y = rise)
                    .graphicsLayer {
                        scaleX = lensScale
                        scaleY = lensScale
                    },
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
}
