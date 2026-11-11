package app.xreader.ui.localreader

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import kotlin.math.abs

/**
 * v1.5.7 (Finding 1, §(d) step 4): the PDF surface's tap source —
 * a small WATCH-ONLY tap observer local to `ui/localreader/`.
 * It is written in the spirit of the novel reader's tap observer
 * but imports nothing from `ui/reader/` (that surface is fenced).
 *
 * Contract: the observer runs on the INITIAL pointer pass and
 * consumes NOTHING, so scrolling, pinch zoom and every other
 * gesture keep their events untouched. Only when a press ends as
 * a clean single tap — the pointer goes up close to where it
 * went down, quickly, without a second pointer ever joining —
 * does it report the tap's horizontal fraction of the view width
 * through [onTap]. Drags, scrolls, pinches and long presses
 * report nothing.
 */
fun Modifier.pdfTapObserver(onTap: (xFraction: Float) -> Unit): Modifier =
    pointerInput(onTap) {
        awaitEachGesture {
            val down = awaitFirstDown(
                requireUnconsumed = false,
                pass = PointerEventPass.Initial
            )
            val downPosition = down.position
            val downTime = down.uptimeMillis
            var sawSecondPointer = false
            var moved = false
            var upTime = 0L
            var upPosition = downPosition
            // Watch (never consume) until every pointer is up.
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.size > 1) sawSecondPointer = true
                for (change in event.changes) {
                    if (abs(change.position.x - downPosition.x) > viewConfiguration.touchSlop ||
                        abs(change.position.y - downPosition.y) > viewConfiguration.touchSlop
                    ) {
                        moved = true
                    }
                    if (!change.pressed) {
                        upTime = change.uptimeMillis
                        upPosition = change.position
                    }
                }
                if (event.changes.all { !it.pressed }) break
            }
            val isCleanTap = !sawSecondPointer && !moved &&
                upTime > 0L && (upTime - downTime) <= 400L
            if (isCleanTap && size.width > 0) {
                onTap((upPosition.x / size.width).coerceIn(0f, 1f))
            }
        }
    }

/**
 * v1.5.18 (F3 / C-E1): the EPUB scroll mode chrome-tap source —
 * the same watch-only contract as [pdfTapObserver] (INITIAL
 * pointer pass, consumes NOTHING, clean single taps only),
 * reporting BOTH fractions because the tap presets' chrome
 * zone can depend on Y (nine-zone). It observes the Compose
 * container hosting the navigator's AndroidView, so a tap is
 * seen natively on this side of the toolkit: no Readium
 * InputListener is registered in scroll mode, hence no JS
 * bridge round-trip per gesture. Because it consumes nothing,
 * everything the tap also means to the WebView still happens
 * — scrolling, link activation and long-press selection are
 * entirely the engine's; drags and long presses report
 * nothing here (movement past touch slop, a second pointer,
 * or >400 ms each suppress the report), so a scroll attempt
 * can never toggle the chrome.
 */
fun Modifier.epubScrollTapObserver(
    onTap: (xFraction: Float, yFraction: Float) -> Unit
): Modifier = pointerInput(onTap) {
    awaitEachGesture {
        val down = awaitFirstDown(
            requireUnconsumed = false,
            pass = PointerEventPass.Initial
        )
        val downPosition = down.position
        val downTime = down.uptimeMillis
        var sawSecondPointer = false
        var moved = false
        var upTime = 0L
        var upPosition = downPosition
        // Watch (never consume) until every pointer is up.
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.size > 1) sawSecondPointer = true
            for (change in event.changes) {
                if (abs(change.position.x - downPosition.x) > viewConfiguration.touchSlop ||
                    abs(change.position.y - downPosition.y) > viewConfiguration.touchSlop
                ) {
                    moved = true
                }
                if (!change.pressed) {
                    upTime = change.uptimeMillis
                    upPosition = change.position
                }
            }
            if (event.changes.all { !it.pressed }) break
        }
        val isCleanTap = !sawSecondPointer && !moved &&
            upTime > 0L && (upTime - downTime) <= 400L
        if (isCleanTap && size.width > 0 && size.height > 0) {
            onTap(
                (upPosition.x / size.width).coerceIn(0f, 1f),
                (upPosition.y / size.height).coerceIn(0f, 1f)
            )
        }
    }
}

/**
 * v1.5.34 (R8): the v1.5.19 boundary-push observer
 * (epubScrollBoundaryObserver + EpubScrollBoundary) that
 * lived here is REMOVED with the rest of the apparatus —
 * the river is the EPUB scroll surface now, and it has no
 * chapter walls to push against. The watch-only tap
 * observers above remain: they carry the river's and the
 * legacy scroll mode's chrome toggles.
 */
