package app.xreader.ui.common

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Tactile press tilt (v1.0.2, R30 — the fork's one delight worth
 * adopting): while pressed, the surface leans up to ±3° toward the
 * touch point and settles at ~0.98 scale; on release it springs
 * back. Motion is the subtle Xreader register, never the bouncy
 * one. A no-op under Reduce Motion (the caller passes [enabled]).
 */
fun Modifier.pressTilt(enabled: Boolean = true): Modifier = composed {
    if (!enabled) return@composed this
    var pressed by remember { mutableStateOf(false) }
    var pressPoint by remember { mutableStateOf(Offset(0.5f, 0.5f)) }
    val rotationX by animateFloatAsState(
        targetValue = if (pressed) (0.5f - pressPoint.y) * 6f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "pressTiltX"
    )
    val rotationY by animateFloatAsState(
        targetValue = if (pressed) (pressPoint.x - 0.5f) * 6f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "pressTiltY"
    )
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.98f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "pressTiltScale"
    )
    this
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    when (event.type) {
                        PointerEventType.Press -> {
                            val change = event.changes.firstOrNull()
                            if (change != null && size.width > 0 && size.height > 0) {
                                pressPoint = Offset(
                                    (change.position.x / size.width).coerceIn(0f, 1f),
                                    (change.position.y / size.height).coerceIn(0f, 1f)
                                )
                            }
                            pressed = true
                        }
                        PointerEventType.Release, PointerEventType.Exit -> {
                            pressed = false
                        }
                    }
                }
            }
        }
        .graphicsLayer {
            this.rotationX = rotationX
            this.rotationY = rotationY
            this.scaleX = scale
            this.scaleY = scale
            cameraDistance = 14f * density
        }
}
