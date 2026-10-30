package app.xreader.ui.reader

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.changedToUpOrCanceled
import androidx.compose.ui.input.pointer.pointerInput
import app.xreader.domain.reader.CentreTap
import kotlin.math.sqrt

/**
 * v1.0.6.2 (F7): tap observation for the readers that CANNOT be
 * swallowed. The old design ran detectTapGestures on the parent
 * box (Main pointer pass, children first): every paragraph's
 * combinedClickable — and the SelectionContainer around the text —
 * consumed taps landing on text, which is nearly the whole page,
 * so centre taps toggled the chrome only when they happened to
 * land in a paragraph gap. Once the bars auto-hid, tapping the
 * middle of the screen did nothing.
 *
 * This observer runs in the INITIAL pass at the reader root: it
 * watches every press before children see it, consumes NOTHING
 * while the gesture could still become a scroll or a long-press,
 * and only when the press ends as a genuine tap (short, unmoved,
 * single-finger) does it consume the up-event and report the tap.
 * Scrolls, drags and long-presses flow through untouched, in
 * every reader mode, regardless of chrome or auto-hide state.
 */
fun Modifier.readerTapToggle(
    key: Any,
    onTap: (xFraction: Float, yFraction: Float) -> Unit
): Modifier = pointerInput(key) {
    awaitPointerEventScope {
        while (true) {
            val downEvent = awaitPointerEvent(PointerEventPass.Initial)
            val down = downEvent.changes.firstOrNull { it.pressed }
                ?: continue
            val pointerId = down.id
            val startX = down.position.x
            val startY = down.position.y
            val startMs = down.uptimeMillis
            var dragged = false
            var multiTouch = false
            var finished = false
            while (!finished) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.count { it.pressed } > 1) {
                    multiTouch = true
                }
                val change = event.changes.firstOrNull { it.id == pointerId }
                    ?: continue
                val dx = change.position.x - startX
                val dy = change.position.y - startY
                if (sqrt(dx * dx + dy * dy) > viewConfiguration.touchSlop) {
                    dragged = true
                }
                when {
                    change.changedToUp() -> {
                        finished = true
                        if (!dragged && !multiTouch &&
                            CentreTap.isTap(
                                change.uptimeMillis - startMs,
                                viewConfiguration.longPressTimeoutMillis
                            )
                        ) {
                            change.consume()
                            onTap(
                                if (size.width > 0) {
                                    change.position.x / size.width
                                } else {
                                    0.5f
                                },
                                if (size.height > 0) {
                                    change.position.y / size.height
                                } else {
                                    0.5f
                                }
                            )
                        }
                    }
                    change.changedToUpOrCanceled() -> finished = true
                }
            }
        }
    }
}
