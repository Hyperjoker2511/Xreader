package app.xreader.ui.reader

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.sqrt
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * v1.5.2 (FIX-4, §6.1): the ONE tap arbiter for the manga reader.
 *
 * The defect it kills: in strip mode a per-page Initial-pass tap
 * observer fired the tap-zone action on the FIRST tap of a
 * double-tap while a separate Main-pass detector zoomed on the
 * second — a double-tap was zoom AND a page action. In paged mode
 * the same pair (page tap observer + the image's own double-tap
 * zoom) double-fired the same way.
 *
 * The arbiter is a single Initial-pass observer that classifies
 * the gesture stream itself: a tap is only reported as a SINGLE
 * tap once the double-tap window has closed with no second tap;
 * a second valid tap inside the window + slop reports DOUBLE and
 * the single never fires. Drags, long-presses and multi-touch
 * cancel classification entirely (scrolls and pinches flow
 * through untouched, exactly as [readerTapToggle] guarantees).
 *
 * The classification is a pure state machine ([TapArbiter152])
 * fed with pointer coordinates/timestamps; the Compose modifier
 * below is a thin adapter over it, so the rules are unit-tested
 * against the same machine the reader runs.
 */
class TapArbiter152(
    private val slopPx: Float,
    private val doubleTapTimeoutMs: Long,
    private val longPressTimeoutMs: Long,
    private val doubleTapSlopPx: Float = slopPx * 4f
) {
    sealed interface Action {
        data class SingleTap(val x: Float, val y: Float) : Action
        data class DoubleTap(val x: Float, val y: Float) : Action
    }

    /** The result of feeding an up-event: the actions to dispatch
     * and whether the up belonged to a classified tap (the caller
     * consumes it when it owns tap consumption). */
    data class Feed(val actions: List<Action>, val consumeUp: Boolean)

    private enum class State { IDLE, FIRST, AWAIT_SECOND, SECOND }

    private var state = State.IDLE
    private var downX = 0f
    private var downY = 0f
    private var downT = 0L
    private var tapX = 0f
    private var tapY = 0f
    private var firstUpT = 0L

    private fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x1 - x2
        val dy = y1 - y2
        return sqrt(dx * dx + dy * dy)
    }

    private fun isTapUp(x: Float, y: Float, t: Long): Boolean =
        t - downT >= 0 && t - downT < longPressTimeoutMs &&
            distance(x, y, downX, downY) <= slopPx

    /** A pointer went down. [pressedCount] is the number of
     * pointers pressed AFTER this down. */
    fun onDown(x: Float, y: Float, t: Long, pressedCount: Int): List<Action> {
        if (pressedCount > 1) {
            // Multi-touch (pinch) cancels all classification.
            state = State.IDLE
            return emptyList()
        }
        return when (state) {
            State.IDLE -> {
                state = State.FIRST
                downX = x; downY = y; downT = t
                emptyList()
            }
            State.AWAIT_SECOND -> {
                if (t - firstUpT <= doubleTapTimeoutMs &&
                    distance(x, y, tapX, tapY) <= doubleTapSlopPx
                ) {
                    state = State.SECOND
                    downX = x; downY = y; downT = t
                    emptyList()
                } else {
                    // A far or late second press: the pending tap
                    // resolves as a SINGLE right away, and this
                    // press starts a fresh classification.
                    val flushed = Action.SingleTap(tapX, tapY)
                    state = State.FIRST
                    downX = x; downY = y; downT = t
                    listOf(flushed)
                }
            }
            State.FIRST, State.SECOND -> {
                // An extra down mid-gesture cancels.
                state = State.IDLE
                emptyList()
            }
        }
    }

    /** The tracked pointer moved. */
    fun onMove(x: Float, y: Float) {
        if ((state == State.FIRST || state == State.SECOND) &&
            distance(x, y, downX, downY) > slopPx
        ) {
            state = State.IDLE
        }
    }

    /** The tracked pointer went up. */
    fun onUp(x: Float, y: Float, t: Long): Feed = when (state) {
        State.FIRST -> {
            if (isTapUp(x, y, t)) {
                tapX = x; tapY = y; firstUpT = t
                state = State.AWAIT_SECOND
                Feed(emptyList(), consumeUp = true)
            } else {
                state = State.IDLE
                Feed(emptyList(), consumeUp = false)
            }
        }
        State.SECOND -> {
            state = State.IDLE
            if (isTapUp(x, y, t)) {
                Feed(listOf(Action.DoubleTap(x, y)), consumeUp = true)
            } else {
                Feed(emptyList(), consumeUp = false)
            }
        }
        else -> Feed(emptyList(), consumeUp = false)
    }

    /** The gesture ended without an up (cancellation). */
    fun onCancel() {
        state = State.IDLE
    }

    /** The double-tap deadline while a single tap is pending,
     * else null. */
    fun pendingDeadlineMs(): Long? =
        if (state == State.AWAIT_SECOND) firstUpT + doubleTapTimeoutMs else null

    /** The clock reached [t]: a pending single tap whose window
     * has closed resolves as a SINGLE. */
    fun onTimeout(t: Long): List<Action> {
        if (state == State.AWAIT_SECOND && t >= firstUpT + doubleTapTimeoutMs) {
            state = State.IDLE
            return listOf(Action.SingleTap(tapX, tapY))
        }
        return emptyList()
    }
}

/**
 * The Compose adapter: an Initial-pass observer (the
 * [readerTapToggle] pattern) driving one [TapArbiter152].
 *
 * [consumeTaps] is true where the arbiter owns the tap actions
 * AND the zoom (strip mode): classified taps' up-events are
 * consumed so nothing downstream can also act on them. It is
 * false where a child owns the double-tap zoom (paged mode's
 * ZoomableImage): the arbiter then only DEFERS the single-tap
 * action until the window closes — the child sees every event
 * and zooms on a double-tap, and the deferred single is
 * cancelled by the arbiter the moment the second tap lands.
 */
fun Modifier.mangaTapArbiter(
    key: Any,
    consumeTaps: Boolean,
    onSingleTap: (xFraction: Float, yFraction: Float) -> Unit,
    onDoubleTap: (xFraction: Float, yFraction: Float) -> Unit
): Modifier = pointerInput(key) {
    val arbiter = TapArbiter152(
        slopPx = viewConfiguration.touchSlop,
        doubleTapTimeoutMs = viewConfiguration.doubleTapTimeoutMillis.toLong(),
        longPressTimeoutMs = viewConfiguration.longPressTimeoutMillis.toLong()
    )
    kotlinx.coroutines.coroutineScope {
        awaitPointerEventScope {
        var timeoutJob: Job? = null

        fun fractionX(x: Float): Float =
            if (size.width > 0) x / size.width else 0.5f

        fun fractionY(y: Float): Float =
            if (size.height > 0) y / size.height else 0.5f

        fun dispatch(actions: List<TapArbiter152.Action>) {
            for (action in actions) {
                when (action) {
                    is TapArbiter152.Action.SingleTap ->
                        onSingleTap(fractionX(action.x), fractionY(action.y))
                    is TapArbiter152.Action.DoubleTap ->
                        onDoubleTap(fractionX(action.x), fractionY(action.y))
                }
            }
        }

        fun armTimeout() {
            val deadline = arbiter.pendingDeadlineMs() ?: return
            timeoutJob?.cancel()
            timeoutJob = launch {
                // Pointer timestamps are uptimeMillis — the delay
                // is measured on that same monotonic clock.
                val remaining = deadline -
                    android.os.SystemClock.uptimeMillis()
                if (remaining > 0) delay(remaining)
                dispatch(arbiter.onTimeout(deadline))
            }
        }

        while (true) {
            val downEvent = awaitPointerEvent(PointerEventPass.Initial)
            val down = downEvent.changes.firstOrNull { it.pressed }
                ?: continue
            val pressedCount = downEvent.changes.count { it.pressed }
            dispatch(
                arbiter.onDown(
                    down.position.x, down.position.y,
                    down.uptimeMillis, pressedCount
                )
            )
            if (pressedCount > 1) continue
            val pointerId = down.id
            var finished = false
            while (!finished) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.count { it.pressed } > 1) {
                    arbiter.onCancel()
                }
                val change = event.changes.firstOrNull { it.id == pointerId }
                    ?: continue
                arbiter.onMove(change.position.x, change.position.y)
                when {
                    change.changedToUp() -> {
                        finished = true
                        val feed = arbiter.onUp(
                            change.position.x, change.position.y,
                            change.uptimeMillis
                        )
                        if (feed.consumeUp && consumeTaps) {
                            change.consume()
                        }
                        dispatch(feed.actions)
                        armTimeout()
                    }
                    !change.pressed -> {
                        finished = true
                        arbiter.onCancel()
                    }
                }
            }
        }
        }
    }
}
