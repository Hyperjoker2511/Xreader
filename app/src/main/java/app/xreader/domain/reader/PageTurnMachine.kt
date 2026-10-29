package app.xreader.domain.reader

/**
 * The paged reader's turn state machine (v1.0.1) — extracted so the
 * gesture logic is unit-testable and, above all, CANNOT WEDGE.
 *
 * The v1.0.0 bug this replaces: the composable kept a bare
 * `targetPage` flag; a drag's `progress.snapTo()` could cancel the
 * in-flight commit animation, whose coroutine then died before
 * clearing the flag — and every later tap hit `if (targetPage !=
 * null) return`. The reader looked "stuck on pages" forever.
 *
 * The invariant here: every path out of [State.Turning] is explicit.
 * AnimationFinished commits; AnimationCancelled rolls back; both land
 * in Idle. A cancelled animation can never strand the machine.
 */
class PageTurnMachine(
    val pageCount: Int,
    val step: Int = 1,
    startPage: Int = 0
) {
    sealed interface State {
        data class Idle(val page: Int) : State
        data class Turning(val from: Int, val to: Int, val direction: Int) : State
        /** A finger is down, dragging toward [to] (not yet committed). */
        data class Dragging(val from: Int, val to: Int, val direction: Int, val fraction: Float) : State
    }

    var state: State = State.Idle(startPage.coerceIn(0, maxOf(0, pageCount - 1)))
        private set

    val currentPage: Int
        get() = when (val s = state) {
            is State.Idle -> s.page
            is State.Turning -> s.from
            is State.Dragging -> s.from
        }

    /** The page being rendered on top during a turn/drag, if any. */
    val incomingPage: Int?
        get() = when (val s = state) {
            is State.Idle -> null
            is State.Turning -> s.to
            is State.Dragging -> s.to
        }

    /** A tap (or volume key / external turn) asks for [direction].
     * Returns the page to animate to, a chapter-boundary signal
     * (null target but [Outcome.Boundary]), or Ignored while busy. */
    sealed interface Outcome {
        data class AnimateTo(val page: Int) : Outcome
        data object Boundary : Outcome
        data object Ignored : Outcome
    }

    fun requestTurn(direction: Int): Outcome {
        val s = state
        if (s !is State.Idle) return Outcome.Ignored
        val target = s.page + direction * step
        return when {
            target in 0 until pageCount -> {
                state = State.Turning(s.page, target, direction)
                Outcome.AnimateTo(target)
            }
            else -> Outcome.Boundary
        }
    }

    /** Finger starts dragging in [direction]; arms a candidate turn. */
    fun dragStart(direction: Int): Boolean {
        val s = state
        if (s !is State.Idle) return false
        val target = s.page + direction * step
        if (target !in 0 until pageCount) return false
        state = State.Dragging(s.page, target, direction, 0f)
        return true
    }

    fun dragBy(fraction: Float) {
        val s = state
        if (s is State.Dragging) {
            state = s.copy(fraction = fraction.coerceIn(0f, 1f))
        }
    }

    /** Finger lifts: past the commit threshold the turn completes,
     * below it the page springs back. Either way the machine settles. */
    fun dragEnd(): Outcome {
        val s = state
        if (s !is State.Dragging) return Outcome.Ignored
        return if (s.fraction > COMMIT_FRACTION) {
            state = State.Turning(s.from, s.to, s.direction)
            Outcome.AnimateTo(s.to)
        } else {
            state = State.Idle(s.from)
            Outcome.Ignored
        }
    }

    fun dragCancel() {
        val s = state
        if (s is State.Dragging) state = State.Idle(s.from)
    }

    /** The turn animation reached its end: commit the target page. */
    fun animationFinished() {
        val s = state
        if (s is State.Turning) state = State.Idle(s.to)
    }

    /**
     * THE ANTI-WEDGE RULE: if the animation is cancelled for ANY
     * reason (a competing snap, disposal, a new gesture), the turn
     * rolls back to the page the reader was actually on. The machine
     * returns to Idle — it can never sit in Turning forever.
     */
    fun animationCancelled() {
        val s = state
        if (s is State.Turning) state = State.Idle(s.from)
    }

    /** Jump straight to a page (restore, TTS follow, auto-turn). */
    fun jumpTo(page: Int) {
        state = State.Idle(page.coerceIn(0, maxOf(0, pageCount - 1)))
    }

    companion object {
        const val COMMIT_FRACTION = 0.32f
    }
}

/**
 * Tap-zone resolution (v1.0.1 customisation): where a tap lands →
 * what it means, for each layout. Pure; the composable only maps
 * pixels to fractions. Returns -1 (previous), +1 (next), 0 (menu).
 */
object TapZones {
    fun action(layout: TapZoneLayout, direction: PagedDirection, xFraction: Float, yFraction: Float): Int {
        val raw = when (layout) {
            TapZoneLayout.OFF -> 0
            TapZoneLayout.STANDARD -> when {
                xFraction < 0.28f -> -1
                xFraction > 0.72f -> 1
                else -> 0
            }
            TapZoneLayout.REVERSED -> when {
                xFraction < 0.28f -> 1
                xFraction > 0.72f -> -1
                else -> 0
            }
            TapZoneLayout.NINE_ZONE -> {
                val col = (xFraction * 3).toInt().coerceIn(0, 2)
                val row = (yFraction * 3).toInt().coerceIn(0, 2)
                when {
                    col == 1 && row == 1 -> 0
                    col == 0 -> -1
                    col == 2 -> 1
                    row == 0 -> -1
                    else -> 1
                }
            }
        }
        // RTL flips the meaning of left/right zones (not the menu).
        return if (direction == PagedDirection.RTL && raw != 0) -raw else raw
    }
}
