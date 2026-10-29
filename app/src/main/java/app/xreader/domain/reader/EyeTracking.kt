package app.xreader.domain.reader

/**
 * Eye-tracking page turns — a LABS experiment (PLAN §0.2, Amendment 3:
 * the feature exists and is labelled experimental; its accuracy is
 * device-judged, never a gate promise).
 *
 * Two pure pieces, both unit-tested:
 *  - [EyeTrackingController]: the lifecycle state machine. It FAILS
 *    CLOSED — every unavailable/denied/error path lands in a terminal
 *    state carrying a plain-words reason, and turning never depends
 *    on it (normal reading is untouched).
 *  - [EyeTurnDetector]: face-event → page-turn detection (double
 *    blink, nod, sustained look-down) with a post-turn cooldown, so a
 *    natural blink pattern can't fire runaway turns.
 */

enum class EyeState {
    /** Toggle off. The default. */
    OFF,
    /** Toggle on, camera permission not yet answered. */
    WAITING_PERMISSION,
    /** Permission granted, detector warming up. */
    STARTING,
    /** Events flowing; gestures can turn pages. */
    ACTIVE,
    /** Permanently unavailable on this device (no detector/camera). */
    UNAVAILABLE,
    /** The user said no to the camera. */
    PERMISSION_DENIED,
    /** The camera/detector failed mid-flight; reading continues. */
    FAILED
}

class EyeTrackingController {

    var state: EyeState = EyeState.OFF
        private set
    /** Plain-words reason for UNAVAILABLE / FAILED / PERMISSION_DENIED. */
    var message: String? = null
        private set

    val isOn: Boolean
        get() = state == EyeState.WAITING_PERMISSION ||
            state == EyeState.STARTING || state == EyeState.ACTIVE

    fun toggle(on: Boolean) {
        if (on) {
            if (state == EyeState.OFF || state == EyeState.PERMISSION_DENIED ||
                state == EyeState.FAILED
            ) {
                state = EyeState.WAITING_PERMISSION
                message = null
            }
        } else {
            state = EyeState.OFF
            message = null
        }
    }

    fun onPermissionResult(granted: Boolean) {
        if (state != EyeState.WAITING_PERMISSION) return
        if (granted) {
            state = EyeState.STARTING
        } else {
            state = EyeState.PERMISSION_DENIED
            message = "Eye tracking needs the camera. You can turn pages " +
                "with taps, swipes or the volume keys instead."
        }
    }

    fun onDetectorReady() {
        if (state == EyeState.STARTING) state = EyeState.ACTIVE
    }

    /** The detector or camera stack doesn't exist here — fail closed. */
    fun onUnavailable(reason: String) {
        state = EyeState.UNAVAILABLE
        message = reason
    }

    fun onFailure(reason: String) {
        if (state == EyeState.OFF) return
        state = EyeState.FAILED
        message = reason
    }
}

/** One classified face observation from the detector (ML Kit on device). */
sealed interface FaceEvent {
    val atMs: Long

    /** Eye-open probabilities 0..1 (null = classifier didn't report). */
    data class Eyes(
        val leftOpen: Float?,
        val rightOpen: Float?,
        override val atMs: Long
    ) : FaceEvent

    /** Head pitch in degrees: positive = looking down (ML Kit euler X). */
    data class Pitch(
        val degreesDown: Float,
        override val atMs: Long
    ) : FaceEvent
}

enum class TurnCommand { NEXT }

/**
 * Gesture detection over [FaceEvent]s. Thresholds are deliberately
 * conservative — a missed turn costs a tap; a false turn loses the
 * reader's place in the story:
 *  - **Double blink**: both eyes shut (< 0.25 open) then open, twice
 *    within 1.4 s, each blink shorter than 600 ms.
 *  - **Nod**: pitch down past 16°, back above 6°, within 1.2 s.
 *  - **Look-down dwell**: pitch held past 20° for 1.6 s.
 * After any command, a 1.8 s cooldown swallows further events.
 */
class EyeTurnDetector {

    private var cooldownUntilMs: Long = Long.MIN_VALUE

    // Blink tracking.
    private var eyesClosedSinceMs: Long? = null
    private var firstBlinkEndMs: Long? = null

    // Nod tracking.
    private var nodDownSinceMs: Long? = null

    // Dwell tracking.
    private var dwellSinceMs: Long? = null

    fun reset() {
        cooldownUntilMs = Long.MIN_VALUE
        eyesClosedSinceMs = null
        firstBlinkEndMs = null
        nodDownSinceMs = null
        dwellSinceMs = null
    }

    fun onEvent(event: FaceEvent): TurnCommand? {
        if (event.atMs < cooldownUntilMs) return null
        return when (event) {
            is FaceEvent.Eyes -> onEyes(event)
            is FaceEvent.Pitch -> onPitch(event)
        }
    }

    private fun fire(atMs: Long): TurnCommand {
        cooldownUntilMs = atMs + COOLDOWN_MS
        eyesClosedSinceMs = null
        firstBlinkEndMs = null
        nodDownSinceMs = null
        dwellSinceMs = null
        return TurnCommand.NEXT
    }

    private fun onEyes(event: FaceEvent.Eyes): TurnCommand? {
        val left = event.leftOpen ?: return null
        val right = event.rightOpen ?: return null
        val closed = left < EYE_CLOSED_THRESHOLD && right < EYE_CLOSED_THRESHOLD
        val open = left > EYE_OPEN_THRESHOLD && right > EYE_OPEN_THRESHOLD
        if (closed) {
            if (eyesClosedSinceMs == null) eyesClosedSinceMs = event.atMs
            return null
        }
        if (!open) return null
        val closedSince = eyesClosedSinceMs ?: return null
        eyesClosedSinceMs = null
        val blinkDuration = event.atMs - closedSince
        if (blinkDuration > MAX_BLINK_MS) {
            // A long closure (squint, looking away) is not a blink.
            firstBlinkEndMs = null
            return null
        }
        val firstEnd = firstBlinkEndMs
        return if (firstEnd != null && event.atMs - firstEnd <= DOUBLE_BLINK_WINDOW_MS) {
            fire(event.atMs)
        } else {
            firstBlinkEndMs = event.atMs
            null
        }
    }

    private fun onPitch(event: FaceEvent.Pitch): TurnCommand? {
        // Nod: down past threshold, then back up, all inside the window.
        val downSince = nodDownSinceMs
        if (event.degreesDown >= NOD_DOWN_DEGREES) {
            if (downSince == null) nodDownSinceMs = event.atMs
        } else if (downSince != null) {
            nodDownSinceMs = null
            if (event.degreesDown <= NOD_UP_DEGREES &&
                event.atMs - downSince <= NOD_WINDOW_MS
            ) {
                return fire(event.atMs)
            }
        }
        // Dwell: held down hard for long enough.
        if (event.degreesDown >= DWELL_DEGREES) {
            val since = dwellSinceMs ?: event.atMs.also { dwellSinceMs = it }
            if (event.atMs - since >= DWELL_MS) return fire(event.atMs)
        } else {
            dwellSinceMs = null
        }
        return null
    }

    companion object {
        const val EYE_CLOSED_THRESHOLD = 0.25f
        const val EYE_OPEN_THRESHOLD = 0.6f
        const val MAX_BLINK_MS = 600L
        const val DOUBLE_BLINK_WINDOW_MS = 1_400L
        const val NOD_DOWN_DEGREES = 16f
        const val NOD_UP_DEGREES = 6f
        const val NOD_WINDOW_MS = 1_200L
        const val DWELL_DEGREES = 20f
        const val DWELL_MS = 1_600L
        const val COOLDOWN_MS = 1_800L
    }
}
