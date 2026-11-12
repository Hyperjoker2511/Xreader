package app.xreader.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

// ─────────────────────────────────────────────────────────────────
// v1.5.40: the colour wheel — the owner's photo spec, built once
// and shared by every reader that has a free colour to pick (the
// novel reader's custom page/word colours, the EPUB reader's text
// and page colours). Layout, top to bottom:
//
//  - one row: a BIG SQUARE shade picker (saturation runs left →
//    right, brightness top → bottom) with a RAINBOW HUE STRIP
//    down its right side;
//  - OLD → NEW colour chips, so the change is visible before it
//    is committed;
//  - a HEX BOX at the bottom (#RRGGBB) for an exact colour.
//
// "Use this colour" commits through [onPick]; Cancel leaves the
// stored colour untouched. Alpha is not edited — reader colours
// are opaque; the picked ARGB keeps the original's alpha byte.
// ─────────────────────────────────────────────────────────────────

/** Pure HSV <-> ARGB math (no Android framework calls, so the
 * round trip is unit-testable on the JVM). Hue 0..360, saturation
 * and value 0..1. */
object ColourWheelMath {

    fun argbToHsv(argb: Long): FloatArray {
        val r = ((argb shr 16) and 0xFF).toInt() / 255f
        val g = ((argb shr 8) and 0xFF).toInt() / 255f
        val b = (argb and 0xFF).toInt() / 255f
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val delta = max - min
        val hue = when {
            delta == 0f -> 0f
            max == r -> 60f * (((g - b) / delta) % 6f)
            max == g -> 60f * (((b - r) / delta) + 2f)
            else -> 60f * (((r - g) / delta) + 4f)
        }
        return floatArrayOf(
            if (hue < 0f) hue + 360f else hue,
            if (max == 0f) 0f else delta / max,
            max
        )
    }

    fun hsvToArgb(hue: Float, saturation: Float, value: Float, alpha: Long = 0xFF): Long {
        val h = ((hue % 360f) + 360f) % 360f
        val s = saturation.coerceIn(0f, 1f)
        val v = value.coerceIn(0f, 1f)
        val c = v * s
        val x = c * (1f - kotlin.math.abs((h / 60f) % 2f - 1f))
        val m = v - c
        val (r, g, b) = when {
            h < 60f -> Triple(c, x, 0f)
            h < 120f -> Triple(x, c, 0f)
            h < 180f -> Triple(0f, c, x)
            h < 240f -> Triple(0f, x, c)
            h < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        val ri = ((r + m) * 255f).roundToInt().coerceIn(0, 255)
        val gi = ((g + m) * 255f).roundToInt().coerceIn(0, 255)
        val bi = ((b + m) * 255f).roundToInt().coerceIn(0, 255)
        return ((alpha and 0xFF) shl 24) or
            (ri.toLong() shl 16) or (gi.toLong() shl 8) or bi.toLong()
    }

    /** "#RRGGBB" for the hex box. */
    fun formatHex(argb: Long): String =
        "#%06X".format(argb and 0xFFFFFF)

    /** Parses "RRGGBB" or "#RRGGBB"; null when the text is not
     * exactly six hex digits. Every character must be a hex
     * digit before the parse runs: toLongOrNull(16) alone
     * accepts a leading sign, so "-12345" would otherwise
     * parse as a colour. (v1.5.40 re-scan D2.) */
    fun parseHex(text: String): Long? {
        val digits = text.trim().removePrefix("#")
        if (digits.length != 6) return null
        if (!digits.all {
                it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F'
            }
        ) {
            return null
        }
        val value = digits.toLongOrNull(16) ?: return null
        return 0xFF000000L or value
    }
}

@Composable
fun ColourWheelPickerDialog(
    title: String,
    initialArgb: Long,
    onDismiss: () -> Unit,
    onPick: (Long) -> Unit
) {
    val alpha = (initialArgb shr 24) and 0xFF
    val startHsv = remember(initialArgb) { ColourWheelMath.argbToHsv(initialArgb) }
    var hue by remember(initialArgb) { mutableStateOf(startHsv[0]) }
    var saturation by remember(initialArgb) { mutableStateOf(startHsv[1]) }
    var value by remember(initialArgb) { mutableStateOf(startHsv[2]) }
    var hexText by remember(initialArgb) {
        mutableStateOf(ColourWheelMath.formatHex(initialArgb))
    }
    val pickedArgb = ColourWheelMath.hsvToArgb(hue, saturation, value, alpha)

    fun syncHexFromSliders() {
        hexText = ColourWheelMath.formatHex(pickedArgb)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SaturationValueSquare(
                        hue = hue,
                        saturation = saturation,
                        value = value,
                        onChange = { s, v ->
                            saturation = s
                            value = v
                        },
                        onChangeEnd = { syncHexFromSliders() },
                        modifier = Modifier
                            .weight(1f)
                            .height(210.dp)
                    )
                    HueStrip(
                        hue = hue,
                        onChange = { h -> hue = h },
                        onChangeEnd = { syncHexFromSliders() },
                        modifier = Modifier
                            .width(30.dp)
                            .height(210.dp)
                    )
                }

                // Old → new: the change is visible before it
                // is committed.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    ColourChip(
                        argb = initialArgb,
                        label = "Now"
                    )
                    Text(
                        "→",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    ColourChip(
                        argb = pickedArgb,
                        label = "New"
                    )
                    Text(
                        ColourWheelMath.formatHex(pickedArgb),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                OutlinedTextField(
                    value = hexText,
                    onValueChange = { text ->
                        hexText = text
                        val parsed = ColourWheelMath.parseHex(text)
                        if (parsed != null) {
                            val hsv = ColourWheelMath.argbToHsv(parsed)
                            hue = hsv[0]
                            saturation = hsv[1]
                            value = hsv[2]
                        }
                    },
                    label = { Text("Hex colour") },
                    placeholder = { Text("#RRGGBB") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick(pickedArgb) }) {
                Text("Use this colour")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun ColourChip(argb: Long, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(argb.toInt()))
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.outline,
                    RoundedCornerShape(10.dp)
                )
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** The big square: white → hue colour across, transparent →
 * black down. Tap or drag moves the ring. */
@Composable
private fun SaturationValueSquare(
    hue: Float,
    saturation: Float,
    value: Float,
    onChange: (Float, Float) -> Unit,
    onChangeEnd: () -> Unit,
    modifier: Modifier = Modifier
) {
    var boxSize by remember { mutableStateOf(Offset.Zero) }
    val hueColour = Color(
        ColourWheelMath.hsvToArgb(hue, 1f, 1f).toInt()
    )

    fun pickAt(position: Offset) {
        if (boxSize.x <= 0f || boxSize.y <= 0f) return
        onChange(
            (position.x / boxSize.x).coerceIn(0f, 1f),
            1f - (position.y / boxSize.y).coerceIn(0f, 1f)
        )
    }

    Box(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(
                Brush.horizontalGradient(
                    listOf(Color.White, hueColour)
                )
            )
            .background(
                Brush.verticalGradient(
                    listOf(Color.Transparent, Color.Black)
                )
            )
            .onSizeChanged { size ->
                boxSize = Offset(size.width.toFloat(), size.height.toFloat())
            }
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    pickAt(offset)
                    onChangeEnd()
                }
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset -> pickAt(offset) },
                    onDrag = { change, _ ->
                        change.consume()
                        pickAt(change.position)
                    },
                    onDragEnd = { onChangeEnd() },
                    onDragCancel = { onChangeEnd() }
                )
            }
    ) {
        val density = LocalDensity.current
        val ringX = with(density) {
            (saturation * boxSize.x).toDp() - 11.dp
        }
        val ringY = with(density) {
            ((1f - value) * boxSize.y).toDp() - 11.dp
        }
        Box(
            Modifier
                .offset(x = ringX, y = ringY)
                .size(22.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.25f))
                .border(2.5.dp, Color.White, CircleShape)
                .border(1.dp, Color.Black.copy(alpha = 0.35f), CircleShape)
        )
    }
}

/** The rainbow strip down the side: red at the top through the
 * spectrum back to red at the bottom. Tap or drag moves the
 * marker. */
@Composable
private fun HueStrip(
    hue: Float,
    onChange: (Float) -> Unit,
    onChangeEnd: () -> Unit,
    modifier: Modifier = Modifier
) {
    var stripHeight by remember { mutableStateOf(0f) }
    val rainbow = remember {
        listOf(
            Color.Red, Color.Yellow, Color.Green, Color.Cyan,
            Color.Blue, Color.Magenta, Color.Red
        )
    }

    fun pickAt(y: Float) {
        if (stripHeight <= 0f) return
        onChange((y / stripHeight).coerceIn(0f, 1f) * 360f)
    }

    Box(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Brush.verticalGradient(rainbow))
            .onSizeChanged { size -> stripHeight = size.height.toFloat() }
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    pickAt(offset.y)
                    onChangeEnd()
                }
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset -> pickAt(offset.y) },
                    onDrag = { change, _ ->
                        change.consume()
                        pickAt(change.position.y)
                    },
                    onDragEnd = { onChangeEnd() },
                    onDragCancel = { onChangeEnd() }
                )
            }
    ) {
        val density = LocalDensity.current
        val markerY = with(density) {
            (hue / 360f * stripHeight).toDp() - 3.dp
        }
        Box(
            Modifier
                .offset(y = markerY)
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Color.White)
                .border(
                    1.dp,
                    Color.Black.copy(alpha = 0.35f),
                    RoundedCornerShape(3.dp)
                )
        )
    }
}
