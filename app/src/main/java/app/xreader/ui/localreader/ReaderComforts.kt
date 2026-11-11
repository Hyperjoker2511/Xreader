package app.xreader.ui.localreader

import android.app.Activity
import android.content.pm.ActivityInfo
import android.view.WindowManager
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.xreader.ui.reader.VolumeKeyTurn

/**
 * v1.5.8 (G8): the shared reader comforts — ONE implementation
 * in the local-reader host, serving EPUB + PDF (the novel reader
 * is NOT touched this round). The seams are the host's because
 * they are NOT composable-level: volume keys never reach a
 * composable, and brightness / keep-awake / orientation are
 * window state.
 *
 *  - Left-edge vertical swipe adjusts the host Activity's
 *    WINDOW brightness (reader-local override, restored on
 *    exit).
 *  - Volume keys page/scroll through the tree's existing
 *    mechanism: this host registers a [VolumeKeyTurn] handler
 *    while the setting is on and unregisters on exit, exactly
 *    as the novel reader does (ReaderScreen.kt:324, registry
 *    :1433); events arrive via MainActivity.dispatchKeyEvent.
 *    Gated by a per-reader setting, default OFF.
 *  - Keep-screen-on = a flag on the host window; orientation
 *    lock sets the host's requested orientation. Both are
 *    restored on exit.
 */

/** Brightness for a left-edge drag: dragging UP brightens.
 * [dragDeltaPx] is the accumulated drag (positive = downward)
 * over a surface [heightPx] tall, starting from [start]. */
fun brightnessForDrag(start: Float, dragDeltaPx: Float, heightPx: Float): Float {
    if (heightPx <= 0f) return start
    return (start - dragDeltaPx / heightPx).coerceIn(0.01f, 1f)
}

/** The ActivityInfo constant a [ReaderOrientation] maps to. */
fun orientationConstantFor(orientation: ReaderOrientation): Int = when (orientation) {
    ReaderOrientation.FREE -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    ReaderOrientation.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    ReaderOrientation.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
}

@Composable
internal fun LocalReaderComfortHost(
    volumeKeyTurn: Boolean,
    keepScreenOn: Boolean,
    orientation: ReaderOrientation,
    onVolumeTurn: (Int) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val activity = remember(context) { context as? Activity }
    val turnState = rememberUpdatedState(onVolumeTurn)

    // ── Volume keys (the tree's registry, novel-reader pattern)
    DisposableEffect(volumeKeyTurn) {
        if (volumeKeyTurn) {
            VolumeKeyTurn.handler = { direction -> turnState.value(direction) }
        }
        onDispose {
            if (volumeKeyTurn) VolumeKeyTurn.handler = null
        }
    }

    // ── Keep-screen-on (window flag)
    DisposableEffect(activity, keepScreenOn) {
        val window = activity?.window
        if (window != null && keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            if (window != null && keepScreenOn) {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    // ── Orientation lock (host's requested orientation)
    DisposableEffect(activity, orientation) {
        val host = activity
        if (host != null && orientation != ReaderOrientation.FREE) {
            host.requestedOrientation = orientationConstantFor(orientation)
        }
        onDispose {
            if (host != null && orientation != ReaderOrientation.FREE) {
                host.requestedOrientation =
                    ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
    }

    // ── Brightness: restored to the system value on exit.
    DisposableEffect(activity) {
        onDispose {
            val window = activity?.window ?: return@onDispose
            val params = window.attributes
            params.screenBrightness =
                WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            window.attributes = params
        }
    }

    Box(modifier.fillMaxSize()) {
        content()
        if (activity != null) {
            BrightnessEdgeStrip(activity = activity)
        }
    }
}

/** The left-edge brightness strip: a narrow drag target along
 * the screen's left edge. Vertical drags rewrite the window
 * brightness via [brightnessForDrag]; taps pass elsewhere —
 * only drags are consumed. */
@Composable
private fun BrightnessEdgeStrip(activity: Activity) {
    var dragTotal by remember { mutableFloatStateOf(0f) }
    var dragStartBrightness by remember { mutableFloatStateOf(0.5f) }
    Box(
        Modifier.fillMaxHeight()
            .width(28.dp)
            .pointerInput(activity) {
                detectVerticalDragGestures(
                    onDragStart = {
                        dragTotal = 0f
                        dragStartBrightness = activity.window.attributes
                            .screenBrightness
                            .takeIf { it >= 0f } ?: 0.5f
                    },
                    onVerticalDrag = { _, dragAmount ->
                        dragTotal += dragAmount
                        val next = brightnessForDrag(
                            start = dragStartBrightness,
                            dragDeltaPx = dragTotal,
                            heightPx = size.height.toFloat()
                        )
                        val params = activity.window.attributes
                        params.screenBrightness = next
                        activity.window.attributes = params
                    }
                )
            },
        contentAlignment = Alignment.CenterStart
    ) {}
}
