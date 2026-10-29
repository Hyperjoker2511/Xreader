package app.xreader.ui.profile

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import app.xreader.core.motion.SpringMotionEngine
import app.xreader.core.motion.toSpec
import app.xreader.ui.theme.aiAccentColor
import coil.compose.AsyncImage
import java.io.File
import kotlin.math.sin
import kotlinx.coroutines.launch

/**
 * The Me screen's showpiece pieces (v1.0.1) — the app's ONE
 * expressive surface, a user-sanctioned exception to the
 * subtle-motion default. Every animation runs on a Celebration
 * profile from SpringMotionEngine (no screen-local constants),
 * scales down on the low-RAM tier, and settles to a static,
 * still-beautiful state under Reduce Motion. All effects are
 * entry/idle-state decoration on the graphics layer — they never
 * block taps or scrolling.
 */

/** The avatar disc colour choices (name → colour). */
val AvatarColours: List<Pair<String, Color>> = listOf(
    "Violet" to Color(0xFF7C6AAE),
    "Ember" to Color(0xFFC97B2D),
    "Ocean" to Color(0xFF2E7FA8),
    "Forest" to Color(0xFF4E7A51),
    "Rose" to Color(0xFFB04A5E),
    "Slate" to Color(0xFF5A6472)
)

fun avatarColourNamed(name: String): Color? =
    AvatarColours.firstOrNull { it.first == name }?.second

/**
 * Slow scheme-colour drift behind the Me header. Under Reduce
 * Motion (or on the low-RAM tier) it renders the same gradient,
 * still.
 */
@Composable
fun MeGradientHeader(
    reduceMotion: Boolean,
    lowRam: Boolean,
    modifier: Modifier = Modifier
) {
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val background = MaterialTheme.colorScheme.background
    val drift = remember { Animatable(0f) }
    LaunchedEffect(reduceMotion, lowRam) {
        if (reduceMotion || lowRam) return@LaunchedEffect
        while (true) {
            drift.animateTo(
                1f,
                SpringMotionEngine.CelebrationBadge.toSpec()
            )
            drift.animateTo(
                0f,
                SpringMotionEngine.CelebrationBadge.toSpec()
            )
        }
    }
    val t = drift.value
    Box(
        modifier
            .fillMaxWidth()
            .height(10.dp)
            .then(
                Modifier // gradient painted in the Canvas below
            )
    ) {
        Canvas(Modifier.fillMaxWidth().height(10.dp)) {
            val brush = Brush.horizontalGradient(
                listOf(
                    primary.copy(alpha = 0.55f + 0.25f * t),
                    tertiary.copy(alpha = 0.55f + 0.25f * (1f - t)),
                    background
                )
            )
            drawRect(brush = brush)
        }
    }
}

/**
 * The streak FLAME: a Canvas flame with layered sine-wobble flicker
 * in scheme flame colours (primary outer, tertiary mid, surface
 * core). Milestones grow it: 7+ days adds rising ember particles,
 * 30+ enlarges, 100+ adds more particles. Low-RAM halves the
 * particle count; Reduce Motion draws one still flame.
 */
@Composable
fun StreakFlame(
    streakDays: Int,
    reduceMotion: Boolean,
    lowRam: Boolean,
    modifier: Modifier = Modifier
) {
    val outer = MaterialTheme.colorScheme.primary
    val mid = MaterialTheme.colorScheme.tertiary
    val core = MaterialTheme.colorScheme.surface
    val phase = remember { Animatable(0f) }
    LaunchedEffect(reduceMotion) {
        if (reduceMotion) return@LaunchedEffect
        while (true) {
            phase.animateTo(
                (phase.value + 6.28f),
                androidx.compose.animation.core.tween(
                    durationMillis = 1_600,
                    easing = androidx.compose.animation.core.LinearEasing
                )
            )
        }
    }
    val milestoneScale = when {
        streakDays >= 100 -> 1.35f
        streakDays >= 30 -> 1.2f
        streakDays >= 7 -> 1.1f
        else -> 1f
    }
    val particleCount = when {
        reduceMotion || streakDays < 7 -> 0
        streakDays >= 100 -> if (lowRam) 4 else 8
        streakDays >= 30 -> if (lowRam) 3 else 6
        else -> if (lowRam) 2 else 4
    }
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val t = phase.value
        fun flamePath(scale: Float, wobbleAmp: Float): Path {
            val cx = w / 2f
            val baseY = h * 0.92f
            val tipY = h * (0.92f - 0.78f * scale)
            val halfW = w * 0.30f * scale
            val wob = sin(t * 2.1f) * wobbleAmp * w
            return Path().apply {
                moveTo(cx - halfW, baseY)
                cubicTo(
                    cx - halfW, h * 0.55f,
                    cx - halfW * 0.25f + wob, tipY + h * 0.22f,
                    cx + wob, tipY
                )
                cubicTo(
                    cx + halfW * 0.25f + wob, tipY + h * 0.22f,
                    cx + halfW, h * 0.55f,
                    cx + halfW, baseY
                )
                cubicTo(cx + halfW * 0.4f, h * 0.99f, cx - halfW * 0.4f, h * 0.99f, cx - halfW, baseY)
                close()
            }
        }
        drawPath(flamePath(milestoneScale, 0.03f), color = outer.copy(alpha = 0.85f))
        drawPath(flamePath(milestoneScale * 0.68f, 0.05f), color = mid.copy(alpha = 0.9f))
        drawPath(flamePath(milestoneScale * 0.36f, 0.07f), color = core.copy(alpha = 0.95f))
        // Rising embers at milestones.
        for (i in 0 until particleCount) {
            val seed = i * 1.37f
            val rise = ((t * 0.35f + seed) % 1f)
            val px = w / 2f + sin(seed * 7f + t) * w * 0.22f
            val py = h * (0.9f - rise * 0.75f)
            drawCircle(
                color = mid.copy(alpha = (1f - rise) * 0.8f),
                radius = (1f - rise) * 3.2f + 0.8f,
                center = Offset(px, py)
            )
        }
    }
}

/** A stat number that counts up on entry (static under Reduce Motion). */
@Composable
fun CountUpText(
    value: Int,
    reduceMotion: Boolean,
    modifier: Modifier = Modifier
) {
    val counter = remember { Animatable(0f) }
    LaunchedEffect(value, reduceMotion) {
        if (reduceMotion) {
            counter.snapTo(value.toFloat())
        } else {
            counter.animateTo(
                value.toFloat(),
                SpringMotionEngine.CelebrationBadge.toSpec()
            )
        }
    }
    Text(
        text = "%,d".format(counter.value.toInt()),
        style = MaterialTheme.typography.titleLarge,
        modifier = modifier
    )
}

// ─────────────────────────────────────────────────────────────────
// v1.0.2 (R29/R30): the Identity Card — the Me showpiece's centre.
// A+B hybrid: Ember Stage stage-lighting behind, Identity Card in
// front. It tilts in 3D toward the finger (or the device, when the
// user opts into motion tilt), throws a moving glare, and flips to
// a stats back. Every effect settles still under Reduce Motion and
// scales down on the low-RAM tier; nothing here blocks a tap.
// ─────────────────────────────────────────────────────────────────

/** Device tilt (game rotation vector) as -1..1 pitch/roll, or
 * zeros when unavailable / not opted in. Registered only while
 * [enabled] and the composable is on screen. */
@Composable
private fun rememberDeviceTilt(enabled: Boolean): State<Offset> {
    val context = androidx.compose.ui.platform.LocalContext.current
    val tilt = remember { mutableStateOf(Offset.Zero) }
    DisposableEffect(enabled) {
        if (!enabled) {
            tilt.value = Offset.Zero
            return@DisposableEffect onDispose {}
        }
        val manager = context.getSystemService(android.content.Context.SENSOR_SERVICE)
            as? android.hardware.SensorManager
        val sensor = manager?.getDefaultSensor(
            android.hardware.Sensor.TYPE_GAME_ROTATION_VECTOR
        )
        if (manager == null || sensor == null) {
            return@DisposableEffect onDispose {}
        }
        val listener = object : android.hardware.SensorEventListener {
            private val rotationMatrix = FloatArray(9)
            private val orientation = FloatArray(3)
            override fun onSensorChanged(event: android.hardware.SensorEvent) {
                android.hardware.SensorManager.getRotationMatrixFromVector(
                    rotationMatrix, event.values
                )
                android.hardware.SensorManager.getOrientation(
                    rotationMatrix, orientation
                )
                // orientation[1] = pitch, [2] = roll (radians).
                val roll = (orientation[2] / 0.6f).coerceIn(-1f, 1f)
                val pitch = (orientation[1] / 0.6f).coerceIn(-1f, 1f)
                tilt.value = Offset(roll, pitch)
            }
            override fun onAccuracyChanged(
                sensor: android.hardware.Sensor?, accuracy: Int
            ) = Unit
        }
        manager.registerListener(
            listener, sensor, android.hardware.SensorManager.SENSOR_DELAY_UI
        )
        onDispose { manager.unregisterListener(listener) }
    }
    return tilt
}

@Composable
fun IdentityCard3D(
    name: String,
    title: String,
    level: Int,
    levelFraction: Float,
    streakDays: Int,
    avatarFile: File?,
    avatarColour: Color,
    backStats: List<Pair<String, String>>,
    reduceMotion: Boolean,
    lowRam: Boolean,
    motionTiltEnabled: Boolean,
    onAvatarClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var flipped by remember { mutableStateOf(false) }
    val flip by animateFloatAsState(
        targetValue = if (flipped) 180f else 0f,
        animationSpec = if (reduceMotion) {
            androidx.compose.animation.core.tween(0)
        } else {
            spring(
                dampingRatio = Spring.DampingRatioLowBouncy,
                stiffness = Spring.StiffnessLow
            )
        },
        label = "identityFlip"
    )
    val tiltX = remember { Animatable(0f) }
    val tiltY = remember { Animatable(0f) }
    val deviceTilt by rememberDeviceTilt(
        motionTiltEnabled && !reduceMotion && !flipped
    )
    val scope = rememberCoroutineScope()
    LaunchedEffect(deviceTilt) {
        if (motionTiltEnabled && !reduceMotion) {
            tiltX.snapTo(deviceTilt.y * -7f)
            tiltY.snapTo(deviceTilt.x * 7f)
        }
    }
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary

    Box(modifier.fillMaxWidth()) {
        // Ember Stage: two stage-light blobs at different parallax
        // depths behind the card, tinted from the scheme.
        if (!lowRam) {
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(190.dp)
            ) {
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(
                            primary.copy(alpha = 0.30f),
                            Color.Transparent
                        )
                    ),
                    radius = size.minDimension * 0.75f,
                    center = Offset(
                        size.width * 0.22f + tiltY.value * 6f,
                        size.height * 0.35f + tiltX.value * 4f
                    )
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(
                            tertiary.copy(alpha = 0.22f),
                            Color.Transparent
                        )
                    ),
                    radius = size.minDimension * 0.6f,
                    center = Offset(
                        size.width * 0.85f - tiltY.value * 10f,
                        size.height * 0.7f - tiltX.value * 6f
                    )
                )
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(190.dp)
                .padding(horizontal = 4.dp, vertical = 10.dp)
                .graphicsLayer {
                    rotationX = tiltX.value
                    rotationY = flip + tiltY.value
                    cameraDistance = 14f * density
                }
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(24.dp))
                .background(
                    Brush.linearGradient(
                        listOf(
                            MaterialTheme.colorScheme.surfaceVariant,
                            MaterialTheme.colorScheme.surface
                        )
                    )
                )
                .pointerInput(reduceMotion, motionTiltEnabled) {
                    if (reduceMotion) return@pointerInput
                    detectDragGestures(
                        onDragEnd = {
                            if (!motionTiltEnabled) {
                                scope.launch {
                                    launch {
                                        tiltX.animateTo(
                                            0f,
                                            spring(
                                                dampingRatio =
                                                    Spring.DampingRatioMediumBouncy,
                                                stiffness = Spring.StiffnessLow
                                            )
                                        )
                                    }
                                    launch {
                                        tiltY.animateTo(
                                            0f,
                                            spring(
                                                dampingRatio =
                                                    Spring.DampingRatioMediumBouncy,
                                                stiffness = Spring.StiffnessLow
                                            )
                                        )
                                    }
                                }
                            }
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            if (!motionTiltEnabled) {
                                val nx = ((change.position.y / size.height) - 0.5f) * -14f
                                val ny = ((change.position.x / size.width) - 0.5f) * 14f
                                scope.launch {
                                    tiltX.snapTo(nx.coerceIn(-12f, 12f))
                                    tiltY.snapTo(ny.coerceIn(-12f, 12f))
                                }
                            }
                        }
                    )
                }
                .clickable { flipped = !flipped }
        ) {
            // The moving glare.
            if (!reduceMotion) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.radialGradient(
                                listOf(
                                    Color.White.copy(alpha = 0.16f),
                                    Color.Transparent
                                ),
                                center = Offset(
                                    (0.5f + tiltY.value / 24f) * 900f,
                                    (0.5f - tiltX.value / 24f) * 400f
                                ),
                                radius = 420f
                            )
                        )
                )
            }
            if (flip <= 90f) {
                // FRONT: identity.
                Row(
                    Modifier
                        .fillMaxSize()
                        .padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box {
                        Surface(
                            onClick = onAvatarClick,
                            shape = CircleShape,
                            color = avatarColour,
                            modifier = Modifier.size(76.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                if (avatarFile != null && avatarFile.exists()) {
                                    AsyncImage(
                                        model = avatarFile,
                                        contentDescription =
                                            "Your profile photo — tap to change",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                } else {
                                    Text(
                                        name.trim().firstOrNull()?.uppercase()
                                            ?: "R",
                                        style = MaterialTheme.typography.headlineLarge,
                                        color = Color.White
                                    )
                                }
                            }
                        }
                        // Level ring around the avatar.
                        Canvas(Modifier.size(76.dp)) {
                            drawArc(
                                color = primary,
                                startAngle = -90f,
                                sweepAngle = 360f * levelFraction.coerceIn(0f, 1f),
                                useCenter = false,
                                style = Stroke(width = 4.dp.toPx())
                            )
                        }
                    }
                    Column(
                        Modifier
                            .weight(1f)
                            .padding(start = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            name.ifBlank { "Reader" },
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1
                        )
                        Text(
                            title,
                            style = MaterialTheme.typography.bodyLarge,
                            color = aiAccentColor()
                        )
                        Text(
                            "Level $level",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (streakDays > 0) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                StreakFlame(
                                    streakDays = streakDays,
                                    reduceMotion = reduceMotion,
                                    lowRam = lowRam,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    "$streakDays-day streak",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 4.dp)
                                )
                            }
                        }
                    }
                    Text(
                        "Flip",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                // BACK: the reading DNA, counter-rotated to read
                // correctly at 180°.
                Column(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer { rotationY = 180f }
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        "Your reading so far",
                        style = MaterialTheme.typography.titleMedium
                    )
                    backStats.chunked(2).forEach { pair ->
                        Row(Modifier.fillMaxWidth()) {
                            pair.forEach { (label, value) ->
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        value,
                                        style = MaterialTheme.typography.titleLarge
                                    )
                                    Text(
                                        label,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme
                                            .onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                    Text(
                        "Tap to flip back",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
