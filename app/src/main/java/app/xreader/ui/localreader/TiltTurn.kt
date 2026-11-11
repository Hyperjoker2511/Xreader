package app.xreader.ui.localreader

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlin.math.abs

/**
 * v1.5.10 (F6, §6 Q2 → A, build-map Group F): tilt-to-turn —
 * a deliberate tilt past a threshold turns the page through
 * the reader's OWN turn advance. Built, OFF by default
 * ([ReaderBarStore.tiltEnabled] defaults false), no runtime
 * permission (the accelerometer needs none), and the sensor
 * listens ONLY while a local reader is foregrounded (the
 * effect registers on ON_RESUME and unregisters on ON_PAUSE /
 * dispose — the census pins both sides).
 *
 * Reference constants from the return's `TiltPageTurnMath`
 * (sift §6c — adopted shape): 26° trigger, 10° neutral
 * hysteresis, 950 ms cooldown. All three are TUNE items for
 * the phone proof.
 */
object TiltTurnMath {
    const val TRIGGER_DEGREES = 26f
    const val NEUTRAL_HYSTERESIS_DEGREES = 10f
    const val COOLDOWN_MS = 950L

    /**
     * The pure decision: given the tilt [deltaDegrees] from
     * the reader's neutral pose, whether the detector is
     * [armed], and the cooldown clock, returns
     * (nextArmed, pageDelta) — pageDelta +1 = forward,
     * -1 = back, 0 = no turn. A fired turn DISARMS; re-arming
     * requires returning inside the neutral hysteresis band,
     * so one tilt can never fire twice and a phone shifting
     * in bed below the trigger fires never.
     */
    fun evaluate(
        deltaDegrees: Float,
        armed: Boolean,
        lastTriggerMs: Long,
        nowMs: Long
    ): Pair<Boolean, Int> {
        if (!armed) {
            return (abs(deltaDegrees) <= NEUTRAL_HYSTERESIS_DEGREES) to 0
        }
        if (nowMs - lastTriggerMs < COOLDOWN_MS) return true to 0
        return when {
            deltaDegrees >= TRIGGER_DEGREES -> false to 1
            deltaDegrees <= -TRIGGER_DEGREES -> false to -1
            else -> true to 0
        }
    }

    /** Approximate pitch delta in degrees from the
     * accelerometer's Y axis against [baselineY] (g ≈ 9.81
     * m/s² maps to 90°) — the return's conversion, kept. */
    fun deltaDegrees(y: Float, baselineY: Float): Float =
        ((y - baselineY) / 9.81f) * 90f
}

/**
 * The stateful detector over [TiltTurnMath]: feed it samples,
 * it emits page deltas. The first sample after [reset] sets
 * the neutral baseline (the pose the reader is holding when
 * tilt turns on is "level" by definition).
 */
class TiltTurnController {
    private var baselineY: Float? = null
    private var armed: Boolean = true
    private var lastTriggerMs: Long = Long.MIN_VALUE / 2

    fun reset() {
        baselineY = null
        armed = true
        lastTriggerMs = Long.MIN_VALUE / 2
    }

    /** Returns +1/-1 when this sample turns a page, else 0. */
    fun onSample(y: Float, nowMs: Long): Int {
        val base = baselineY
        if (base == null) {
            baselineY = y
            return 0
        }
        val (nextArmed, delta) = TiltTurnMath.evaluate(
            deltaDegrees = TiltTurnMath.deltaDegrees(y, base),
            armed = armed,
            lastTriggerMs = lastTriggerMs,
            nowMs = nowMs
        )
        armed = nextArmed
        if (delta != 0) lastTriggerMs = nowMs
        return delta
    }
}

/**
 * Registers the accelerometer while [enabled] AND the host
 * lifecycle is RESUMED; unregisters on pause and on dispose.
 * Each turn calls [onTurn] with +1 (forward) / -1 (back) —
 * the reader routes it to its own advance (EPUB
 * goForward/goBackward, PDF page turn).
 */
@Composable
internal fun ReaderTiltTurnEffect(
    enabled: Boolean,
    onTurn: (Int) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val controller = remember { TiltTurnController() }
    val turnState = androidx.compose.runtime.rememberUpdatedState(onTurn)
    DisposableEffect(enabled, lifecycleOwner) {
        if (!enabled) return@DisposableEffect onDispose {}
        val sensorManager =
            context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val accelerometer =
            sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (sensorManager == null || accelerometer == null) {
            return@DisposableEffect onDispose {}
        }
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                val values = event?.values ?: return
                if (values.size < 2) return
                val delta = controller.onSample(
                    values[1], System.currentTimeMillis()
                )
                if (delta != 0) turnState.value(delta)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        var registered = false
        fun register() {
            if (!registered) {
                controller.reset()
                sensorManager.registerListener(
                    listener, accelerometer, SensorManager.SENSOR_DELAY_UI
                )
                registered = true
            }
        }
        fun unregister() {
            if (registered) {
                sensorManager.unregisterListener(listener)
                registered = false
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> register()
                Lifecycle.Event.ON_PAUSE -> unregister()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState
                .isAtLeast(Lifecycle.State.RESUMED)
        ) {
            register()
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            unregister()
        }
    }
}
