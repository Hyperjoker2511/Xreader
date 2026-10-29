package app.xreader.core.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.OverscrollEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.CoroutineScope
import kotlin.math.abs
import kotlin.math.sign

/**
 * Virtual Resistance overscroll (PLAN §2.2/§3, MOTION-RESEARCH §4):
 * dragging past a list's edge bends it with the engine's rubber-band
 * curve ([SpringMotionEngine.rubberBand] — diminishing, bounded, never
 * 1:1), and releasing springs it home on the OverscrollReturn profile.
 *
 * This is the ONLY overscroll implementation in the app (Amendment 5's
 * single motion source): every scrollable surface in every phase wires
 * `Modifier.overscroll(rememberRubberBandOverscroll(...))` and inherits
 * the same feel. With reduce-motion on, no band forms at all.
 *
 * Mechanics: drag deltas the content cannot consume grow the band;
 * dragging back unwinds the band BEFORE the content scrolls (so the
 * gesture feels attached to the finger); a fling with a band showing
 * is spent springing home instead of scrolling.
 */
@OptIn(ExperimentalFoundationApi::class)
class RubberBandOverscrollEffect(
    private val scope: CoroutineScope,
    private val dimensionPx: () -> Float,
    private val enabled: () -> Boolean
) : OverscrollEffect {

    /** Accumulated signed drag past the edge, in raw (unresisted) px. */
    private var rawDrag = 0f

    /** The resisted, on-screen offset. Snapshot state: drags update it
     * synchronously; the release spring animates it. */
    private var appliedPx by mutableFloatStateOf(0f)

    override val isInProgress: Boolean get() = appliedPx != 0f

    override val effectModifier: Modifier = Modifier.graphicsLayer {
        translationY = appliedPx
    }

    override fun applyToScroll(
        delta: Offset,
        source: NestedScrollSource,
        performScroll: (Offset) -> Offset
    ): Offset {
        if (!enabled() || source != NestedScrollSource.Drag) {
            return performScroll(delta)
        }
        var consumed = Offset.Zero
        var remaining = delta

        // 1) Unwind an existing band before the content scrolls.
        if (rawDrag != 0f && remaining.y != 0f && sign(remaining.y) != sign(rawDrag)) {
            val unwind = minOf(abs(remaining.y), abs(rawDrag))
            rawDrag -= sign(rawDrag) * unwind
            val consumedY = sign(remaining.y) * unwind
            consumed += Offset(0f, consumedY)
            remaining = Offset(remaining.x, remaining.y - consumedY)
            updateApplied()
        }

        // 2) Let the content scroll what's left.
        val scrolled = performScroll(remaining)
        consumed += scrolled
        val leftover = remaining - scrolled

        // 3) Whatever the edge couldn't take grows the band.
        if (leftover.y != 0f) {
            rawDrag += leftover.y
            updateApplied()
            consumed += Offset(0f, leftover.y)
        }
        return consumed
    }

    override suspend fun applyToFling(
        velocity: Velocity,
        performFling: suspend (Velocity) -> Velocity
    ) {
        if (rawDrag != 0f || appliedPx != 0f) {
            // Spend the gesture springing home, not scrolling.
            val start = appliedPx
            rawDrag = 0f
            Animatable(start).animateTo(
                targetValue = 0f,
                animationSpec = SpringMotionEngine.OverscrollReturn.toSpec()
            ) { appliedPx = value }
            appliedPx = 0f
        } else {
            performFling(velocity)
        }
    }

    private fun updateApplied() {
        val dimension = dimensionPx().coerceAtLeast(1f)
        appliedPx = sign(rawDrag) * SpringMotionEngine.rubberBand(abs(rawDrag), dimension)
    }
}

/**
 * Remembers the app's one overscroll effect. [dimensionPx] is the
 * scrollable's height (the band curve's bound) — pass its measured
 * size. [enabled] should carry the reduce-motion decision so the band
 * never forms when motion is off.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun rememberRubberBandOverscroll(
    dimensionPx: Float,
    enabled: Boolean = true
): OverscrollEffect {
    val scope = rememberCoroutineScope()
    val dimensionState = rememberUpdatedState(dimensionPx)
    val enabledState = rememberUpdatedState(enabled)
    return remember {
        RubberBandOverscrollEffect(
            scope = scope,
            dimensionPx = { dimensionState.value },
            enabled = { enabledState.value }
        )
    }
}
