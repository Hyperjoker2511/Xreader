/*
 * Copyright 2024 The Komikku Authors
 * Copyright 2015 Javier Tomás
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * v1.5.12 (M2): the paged viewer's Recycler-level turn
 * decision — pure logic, exercised by the gate proofs
 * (V1512MangaTurnTest) in the exact shape production runs.
 */
package app.xreader.komikku.viewer

/**
 * Decides, for ONE touch gesture on the paged viewer, whether
 * the gesture is a page TURN (decided at the Recycler level)
 * or the page view's own PAN (the SSIV's business).
 *
 * Why this exists (v1.5.11 forensics, Lane 1 — convicted): the
 * page's SubsamplingScaleImageView calls
 * requestDisallowInterceptTouchEvent(true) on DOWN, and its
 * edge-release is conditional — a first threshold-crossing
 * move that is diagonal / not-yet-clamped / not axis-dominant
 * latches the SSIV's isPanning for the whole gesture, so at
 * fit scale the "pan" clamps to zero visible movement and the
 * page never turns (short/sloppy swipes produced literally
 * nothing on device). The donor (ViewPager) never had this
 * failure because the PAGER decided turns above the page
 * view. This decider restores that arrangement for Xreader's
 * RecyclerView pager: the host's OnItemTouchListener feeds it
 * every event of the gesture and, the moment it says TURN,
 * intercepts and turns — the SSIV receives ACTION_CANCEL and
 * its latched pan dies with the gesture.
 *
 * The rules:
 *  - Movement below the touch slop decides nothing.
 *  - A gesture whose axis component is negligible next to its
 *    cross-axis component (a mostly cross-axis drag) is the
 *    page's — PASS (a zoomed page pans diagonally too).
 *  - A turn-shaped gesture (axis component at/past the slop
 *    and at least half its cross-axis component — the short /
 *    diagonal swipes the SSIV latches as no-op pans included)
 *    turns ONLY when the page cannot pan further in that
 *    reading direction: at fit scale it never can, so the
 *    turn fires; genuinely zoomed, the pan belongs to the
 *    SSIV until its edge, then the turn proceeds (the donor's
 *    navigateToPan order, decided one level up).
 *  - A TURN, once fired, stands for the whole gesture (one
 *    turn per gesture, never a mid-gesture reversal). A PASS
 *    does NOT latch: like the donor's pager, the question is
 *    re-asked on every MOVE, so a pan that REACHES the page's
 *    edge mid-gesture still turns within the same gesture.
 *    The one latched pass is a second finger (pinch): pinch
 *    territory is the page's for the rest of the gesture.
 *
 * Directions are expressed in READING terms (NEXT/PREV page),
 * with the layout direction folded in by the caller-provided
 * signs: [forwardSign] maps a raw axis delta (dx for a
 * horizontal pager, dy for a vertical one) onto reading
 * direction — +1 when a POSITIVE raw delta moves toward the
 * NEXT page (right-to-left horizontal, where items stack
 * leftwards), -1 when a NEGATIVE raw delta does (left-to-
 * right horizontal and vertical pagers, where a left/up
 * swipe advances).
 */
class PagerTurnDecider(
    private val touchSlopPx: Float,
    private val horizontal: Boolean,
    private val forwardSign: Float
) {

    enum class Decision {
        /** Not enough evidence yet — keep observing. */
        UNDECIDED,

        /** Turn to the next page (reading order). */
        TURN_NEXT,

        /** Turn to the previous page (reading order). */
        TURN_PREV,

        /** The page view's gesture (pan / zoom territory). */
        PASS_TO_PAGE
    }

    private var downX: Float = 0f
    private var downY: Float = 0f

    /** The latched TURN of this gesture, once one has fired. */
    private var latchedTurn: Decision? = null

    /** True once a second finger made this a pinch gesture. */
    private var pinched: Boolean = false

    fun onDown(x: Float, y: Float) {
        downX = x
        downY = y
        latchedTurn = null
        pinched = false
    }

    /** A second finger landed: pinch territory — the page
     * view owns the rest of this gesture. */
    fun onMultiTouch() {
        if (latchedTurn == null) pinched = true
    }

    /**
     * One MOVE of the gesture. [canPanNext] / [canPanPrev]
     * report whether the current page's image can still pan
     * toward the NEXT / PREV page (the host reads them off
     * the page view's pan-remaining, in reading direction);
     * both false at fit scale.
     */
    fun onMove(
        x: Float,
        y: Float,
        canPanNext: Boolean,
        canPanPrev: Boolean
    ): Decision {
        latchedTurn?.let { return it }
        if (pinched) return Decision.PASS_TO_PAGE
        val dx = x - downX
        val dy = y - downY
        val axis = if (horizontal) dx else dy
        val cross = if (horizontal) dy else dx
        val axisAbs = kotlin.math.abs(axis)
        val crossAbs = kotlin.math.abs(cross)
        if (axisAbs < touchSlopPx && crossAbs < touchSlopPx) {
            return Decision.UNDECIDED
        }
        // Reading-direction delta: positive = toward NEXT.
        val reading = axis * forwardSign
        if (axisAbs < touchSlopPx || axisAbs * 2f < crossAbs) {
            // No meaningful axis travel, or a mostly
            // cross-axis drag: not a turn shape. (At fit scale
            // such a gesture simply ends; zoomed, it is the
            // page's pan.) Not latched — see the class docs.
            return Decision.PASS_TO_PAGE
        }
        val towardNext = reading > 0f
        val canPan = if (towardNext) canPanNext else canPanPrev
        if (canPan) return Decision.PASS_TO_PAGE
        val turn = if (towardNext) Decision.TURN_NEXT else Decision.TURN_PREV
        latchedTurn = turn
        return turn
    }

    /** Gesture over (UP / CANCEL): forget it. */
    fun onGestureEnd() {
        latchedTurn = null
        pinched = false
    }
}
