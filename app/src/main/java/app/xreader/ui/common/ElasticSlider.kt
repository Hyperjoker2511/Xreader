package app.xreader.ui.common

import android.animation.ValueAnimator
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import app.xreader.core.motion.toSpec
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Elastic Slider (React Bits pick #1, PATCH-SPEC-ELASTIC-SLIDER):
 * a Material-3-shaped slider whose thumb rubber-bands past the
 * ends (≤10.dp, exponential resistance), stretches with finger
 * velocity (1.0→1.30), and springs back on release with the app's
 * medium-bouncy spring (0.55 / 420). Stateless like M3 Slider:
 * the caller owns the value; [onValueChange] only ever reports
 * values inside [valueRange], even mid-overshoot;
 * [onValueChangeFinished] fires exactly once when a drag ends
 * normally (never on gesture cancellation, never during the
 * spring-back). Under reduced motion (animator duration scale 0)
 * it behaves exactly like a plain slider.
 *
 * Performance (the 2 GB rule): animated values are read only in
 * the draw phase; two hand-managed [Animatable]s (overshoot,
 * stretch) plus one state-driven press float; zero work when idle.
 */

private val TouchTargetHeight = 48.dp
private val RestingTrackHeight = 4.dp
private val PressedTrackHeight = 6.dp
private val ThumbDiameter = 20.dp
private val MaxRubberBandOverflow = 10.dp
private const val PressedThumbScale = 1.25f
// Code-129 motion pass: the elastic spring's numbers live in
// the engine now (SpringMotionEngine.ElasticPress — stiffness
// 420, dampingRatio 0.55, preserved verbatim), not as locals.
private const val MaxStretchScale = 1.30f
private const val StretchReferenceVelocityPxPerSec = 2400f
private const val KeyboardStepCount = 20

@Immutable
data class ElasticSliderColors(
    val thumbColor: Color,
    val activeTrackColor: Color,
    val inactiveTrackColor: Color,
    val disabledThumbColor: Color,
    val disabledActiveTrackColor: Color,
    val disabledInactiveTrackColor: Color
) {
    internal fun thumbColor(enabled: Boolean): Color =
        if (enabled) thumbColor else disabledThumbColor

    internal fun activeTrackColor(enabled: Boolean): Color =
        if (enabled) activeTrackColor else disabledActiveTrackColor

    internal fun inactiveTrackColor(enabled: Boolean): Color =
        if (enabled) inactiveTrackColor else disabledInactiveTrackColor
}

object ElasticSliderDefaults {
    @Composable
    fun colors(
        thumbColor: Color = MaterialTheme.colorScheme.primary,
        activeTrackColor: Color = MaterialTheme.colorScheme.primary,
        inactiveTrackColor: Color = MaterialTheme.colorScheme.surfaceVariant,
        disabledThumbColor: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
        disabledActiveTrackColor: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
        disabledInactiveTrackColor: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    ): ElasticSliderColors = ElasticSliderColors(
        thumbColor = thumbColor,
        activeTrackColor = activeTrackColor,
        inactiveTrackColor = inactiveTrackColor,
        disabledThumbColor = disabledThumbColor,
        disabledActiveTrackColor = disabledActiveTrackColor,
        disabledInactiveTrackColor = disabledInactiveTrackColor
    )
}

/**
 * Rubber-band displacement for a drag [rawOverflowPx] past an end
 * (negative before the start, positive past the end): the
 * exponential resistance curve `cap · (1 − e^(−|x|/cap))` —
 * monotonic, 0 at 0, diminishing, and NEVER beyond
 * ±[maxOverflowPx].
 */
internal fun rubberBandOverflow(
    rawOverflowPx: Float,
    maxOverflowPx: Float
): Float {
    if (maxOverflowPx <= 0f || rawOverflowPx == 0f || rawOverflowPx.isNaN()) return 0f
    if (!rawOverflowPx.isFinite()) {
        return if (rawOverflowPx > 0f) maxOverflowPx else -maxOverflowPx
    }
    val magnitude = maxOverflowPx * (1f - exp(-abs(rawOverflowPx) / maxOverflowPx))
    val bounded = magnitude.coerceIn(0f, maxOverflowPx)
    return if (rawOverflowPx > 0f) bounded else -bounded
}

/**
 * Velocity stretch factor for a horizontal finger velocity:
 * 1f at rest, rising with speed, capped at 1.30f.
 */
internal fun stretchForVelocity(pxPerSec: Float): Float {
    if (pxPerSec.isNaN()) return 1f
    val speed = abs(pxPerSec)
    if (speed <= 0f) return 1f
    if (!speed.isFinite()) return MaxStretchScale
    val normalized = speed / StretchReferenceVelocityPxPerSec
    val eased = if (normalized.isInfinite()) 1f else normalized / (1f + normalized)
    return (1f + (MaxStretchScale - 1f) * eased).coerceIn(1f, MaxStretchScale)
}

/**
 * The value at horizontal position [xPx] along a track spanning
 * `0..widthPx`, mapped linearly onto [valueRange] and clamped —
 * a position beyond either end maps to the clamped end value.
 */
internal fun fractionForPosition(
    xPx: Float,
    widthPx: Float,
    valueRange: ClosedFloatingPointRange<Float>
): Float {
    val start = valueRange.start
    val end = valueRange.endInclusive
    val span = end - start
    if (!xPx.isFinite() || !widthPx.isFinite() || widthPx <= 0f || span <= 0f) return start
    val fraction = (xPx / widthPx).coerceIn(0f, 1f)
    return start + fraction * span
}

/** Inverse of [fractionForPosition]: the x position of [value]. */
internal fun positionForValue(
    value: Float,
    widthPx: Float,
    valueRange: ClosedFloatingPointRange<Float>
): Float {
    val start = valueRange.start
    val end = valueRange.endInclusive
    val span = end - start
    if (!widthPx.isFinite() || widthPx <= 0f || span <= 0f) return 0f
    val clamped = if (value.isNaN()) start else value.coerceIn(start, end)
    return ((clamped - start) / span) * widthPx
}

/**
 * Reduced motion, read ONCE per composition (the light check —
 * Material's own Slider does not observe the setting live either):
 * the system animator duration scale is 0, or animators are
 * disabled outright.
 */
@Composable
internal fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        val scaleZero = runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            ) == 0f
        }.getOrDefault(false)
        scaleZero || !ValueAnimator.areAnimatorsEnabled()
    }
}

@Composable
fun ElasticSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    onValueChangeFinished: (() -> Unit)? = null,
    colors: ElasticSliderColors = ElasticSliderDefaults.colors()
) {
    val clampedValue = value.coerceIn(valueRange.start, valueRange.endInclusive)

    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val reducedMotion = rememberReducedMotion()
    val scope = rememberCoroutineScope()
    val interactionSource = remember { MutableInteractionSource() }

    // The two hand-managed animated values: rubber-band overshoot
    // (px) and velocity stretch (1f..1.30f). Press feedback rides
    // one state-driven float below; all read only in draw.
    val overflowAnim = remember { Animatable(0f) }
    val stretchAnim = remember { Animatable(1f) }
    var isPressed by remember { mutableStateOf(false) }
    val pressProgress by animateFloatAsState(
        targetValue = if (isPressed && !reducedMotion) 1f else 0f,
        animationSpec = app.xreader.core.motion.SpringMotionEngine
            .ElasticPress.toSpec(),
        label = "elasticPress"
    )

    val latestValue by rememberUpdatedState(clampedValue)
    val latestValueRange by rememberUpdatedState(valueRange)
    val latestOnValueChange by rememberUpdatedState(onValueChange)
    val latestOnValueChangeFinished by rememberUpdatedState(onValueChangeFinished)
    val latestReducedMotion by rememberUpdatedState(reducedMotion)

    val springSpec = remember {
        app.xreader.core.motion.SpringMotionEngine.ElasticPress
            .toSpec<Float>()
    }

    val semanticsModifier = Modifier.semantics(mergeDescendants = true) {
        if (!enabled) {
            disabled()
        }
        progressBarRangeInfo = ProgressBarRangeInfo(
            current = clampedValue,
            range = valueRange,
            steps = 0
        )
        if (enabled) {
            setProgress { targetValue ->
                val range = latestValueRange
                val coerced = targetValue.coerceIn(range.start, range.endInclusive)
                if (coerced != latestValue) {
                    latestOnValueChange(coerced)
                    latestOnValueChangeFinished?.invoke()
                    true
                } else {
                    false
                }
            }
        }
    }

    val keyModifier = Modifier.onKeyEvent { event ->
        if (!enabled || event.type != KeyEventType.KeyDown) return@onKeyEvent false
        val range = latestValueRange
        val span = range.endInclusive - range.start
        if (span <= 0f) return@onKeyEvent false
        val step = span / KeyboardStepCount
        val delta = when (event.key) {
            Key.DirectionLeft -> if (isRtl) step else -step
            Key.DirectionRight -> if (isRtl) -step else step
            Key.Minus -> -step
            Key.Plus, Key.Equals -> step
            Key.MoveHome -> range.start - latestValue
            Key.MoveEnd -> range.endInclusive - latestValue
            else -> return@onKeyEvent false
        }
        val nextValue = (latestValue + delta).coerceIn(range.start, range.endInclusive)
        if (nextValue != latestValue) {
            latestOnValueChange(nextValue)
            latestOnValueChangeFinished?.invoke()
        }
        true
    }

    val gestureModifier = if (enabled) {
        Modifier.pointerInput(isRtl) {
            val velocityTracker = VelocityTracker()
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                down.consume()

                val widthPx = size.width.toFloat()
                val thumbRadiusPx = ThumbDiameter.toPx() * 0.5f
                val maxOverflowPx = MaxRubberBandOverflow.toPx()
                val trackStartPx = thumbRadiusPx
                val trackEndPx = max(trackStartPx, widthPx - thumbRadiusPx)
                val trackSpanPx = trackEndPx - trackStartPx

                fun logicalX(rawX: Float): Float = if (isRtl) widthPx - rawX else rawX

                isPressed = true
                velocityTracker.resetTracking()
                velocityTracker.addPosition(down.uptimeMillis, down.position)

                scope.launch {
                    overflowAnim.snapTo(0f)
                    stretchAnim.snapTo(1f)
                }

                var lastEmitted = latestValue
                val downValue = fractionForPosition(
                    logicalX(down.position.x) - trackStartPx,
                    trackSpanPx,
                    latestValueRange
                )
                if (downValue != lastEmitted) {
                    lastEmitted = downValue
                    latestOnValueChange(downValue)
                }

                var completedNormally = false
                try {
                    horizontalDrag(down.id) { change ->
                        change.consume()
                        velocityTracker.addPosition(change.uptimeMillis, change.position)

                        val x = logicalX(change.position.x)
                        val nextValue = fractionForPosition(
                            x - trackStartPx,
                            trackSpanPx,
                            latestValueRange
                        )
                        if (nextValue != lastEmitted) {
                            lastEmitted = nextValue
                            latestOnValueChange(nextValue)
                        }

                        if (latestReducedMotion) {
                            scope.launch {
                                if (overflowAnim.value != 0f) overflowAnim.snapTo(0f)
                                if (stretchAnim.value != 1f) stretchAnim.snapTo(1f)
                            }
                        } else {
                            val rawOvershoot = when {
                                x < trackStartPx -> x - trackStartPx
                                x > trackEndPx -> x - trackEndPx
                                else -> 0f
                            }
                            val targetOverflow = rubberBandOverflow(rawOvershoot, maxOverflowPx)
                            val targetStretch =
                                stretchForVelocity(velocityTracker.calculateVelocity().x)
                            val smoothedStretch =
                                stretchAnim.value + (targetStretch - stretchAnim.value) * 0.45f
                            scope.launch {
                                overflowAnim.snapTo(targetOverflow)
                                stretchAnim.snapTo(smoothedStretch)
                            }
                        }
                    }
                    completedNormally = true
                } catch (cancelled: CancellationException) {
                    // A cancelled gesture is not a finished drag:
                    // no onValueChangeFinished (a seek caller must
                    // not jump on an interrupted gesture).
                    throw cancelled
                } finally {
                    isPressed = false
                    if (completedNormally) {
                        latestOnValueChangeFinished?.invoke()
                    }
                    scope.launch {
                        if (latestReducedMotion) {
                            overflowAnim.snapTo(0f)
                            stretchAnim.snapTo(1f)
                        } else {
                            launch { overflowAnim.animateTo(0f, springSpec) }
                            launch { stretchAnim.animateTo(1f, springSpec) }
                        }
                    }
                }
            }
        }
    } else {
        Modifier
    }

    val thumbColor = colors.thumbColor(enabled)
    val activeTrackColor = colors.activeTrackColor(enabled)
    val inactiveTrackColor = colors.inactiveTrackColor(enabled)

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(TouchTargetHeight)
            .then(semanticsModifier)
            .focusable(enabled = enabled, interactionSource = interactionSource)
            .then(keyModifier)
            .then(gestureModifier)
    ) {
        val widthPx = size.width
        val centerY = size.height * 0.5f
        val baseThumbRadius = ThumbDiameter.toPx() * 0.5f
        val trackStartPx = baseThumbRadius
        val trackEndPx = max(trackStartPx, widthPx - baseThumbRadius)
        val trackSpanPx = trackEndPx - trackStartPx

        // Animated state is read ONLY here, in the draw phase.
        val overflowPx = if (reducedMotion) 0f else overflowAnim.value
        val stretch = if (reducedMotion) 1f else stretchAnim.value
        val press = pressProgress

        val trackHeight = RestingTrackHeight.toPx() +
            (PressedTrackHeight.toPx() - RestingTrackHeight.toPx()) * press
        val trackCornerRadius = trackHeight * 0.5f

        val logicalThumbBaseX = trackStartPx +
            positionForValue(clampedValue, trackSpanPx, valueRange)
        val logicalThumbCenterX = logicalThumbBaseX + overflowPx

        val visualThumbCenterX = if (isRtl) widthPx - logicalThumbCenterX else logicalThumbCenterX
        val visualTrackStartX = if (isRtl) widthPx - trackEndPx else trackStartPx
        val visualTrackEndX = if (isRtl) widthPx - trackStartPx else trackEndPx

        // 1. Inactive track across the full resting bounds.
        val inactiveWidth = max(0f, visualTrackEndX - visualTrackStartX)
        if (inactiveWidth > 0f) {
            drawRoundRect(
                color = inactiveTrackColor,
                topLeft = Offset(visualTrackStartX, centerY - trackHeight * 0.5f),
                size = Size(inactiveWidth, trackHeight),
                cornerRadius = CornerRadius(trackCornerRadius, trackCornerRadius)
            )
        }

        // 2. Active track: follows the thumb, its tip stretched
        // by velocity, its body carried by the rubber band.
        val stretchLeadPx = (stretch - 1f) * baseThumbRadius
        val activeStartX: Float
        val activeEndX: Float
        if (!isRtl) {
            val anchorLeft = trackStartPx + min(0f, overflowPx)
            val tipRight = max(anchorLeft, logicalThumbCenterX + stretchLeadPx)
            activeStartX = anchorLeft
            activeEndX = tipRight
        } else {
            val anchorRight = (widthPx - trackStartPx) - min(0f, overflowPx)
            val tipLeft = min(anchorRight, visualThumbCenterX - stretchLeadPx)
            activeStartX = tipLeft
            activeEndX = anchorRight
        }
        val activeWidth = max(0f, activeEndX - activeStartX)
        if (activeWidth > 0f) {
            drawRoundRect(
                color = activeTrackColor,
                topLeft = Offset(activeStartX, centerY - trackHeight * 0.5f),
                size = Size(activeWidth, trackHeight),
                cornerRadius = CornerRadius(trackCornerRadius, trackCornerRadius)
            )
        }

        // 3. Thumb: 20.dp circle, 1.25x while pressed, stretched
        // horizontally up to 1.30x with finger velocity.
        val pressedScale = 1f + (PressedThumbScale - 1f) * press
        val thumbRadiusX = baseThumbRadius * pressedScale * stretch
        val thumbRadiusY = baseThumbRadius * pressedScale / (1f + (stretch - 1f) * 0.30f)
        drawOval(
            color = thumbColor,
            topLeft = Offset(
                x = visualThumbCenterX - thumbRadiusX,
                y = centerY - thumbRadiusY
            ),
            size = Size(
                width = thumbRadiusX * 2f,
                height = thumbRadiusY * 2f
            )
        )
    }
}
