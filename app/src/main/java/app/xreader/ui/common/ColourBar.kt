package app.xreader.ui.common

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

/**
 * Code 121 — THE colour bar (design §2.1.1 / §5.1): the one
 * control for changing colour anywhere in the Material 3
 * design. One horizontal strip, scrollable, containing in
 * order:
 *
 *  1. a **Dynamic colour** swatch (a split-tone circle in the
 *     current scheme's primary/tertiary) — only where dynamic
 *     colour exists (API 31+) and only in scheme mode;
 *  2. a row of **seed swatches** — plain colour circles in the
 *     MCU seed hues (red, orange, yellow, green, teal, blue,
 *     indigo, purple, pink, brown), colours only, NO names;
 *  3. a **Pick any colour** swatch (rainbow ring + plus)
 *     opening the S37 colour picker dialog ([ColourPickerDialog],
 *     in this file).
 *
 * The selected swatch carries the spec selected state (ring +
 * check). Picking a swatch reports a [ColourBarSelection]; the
 * caller regenerates whatever the colour drives, in place.
 *
 * ## Modes — read this before importing (W5 / W7)
 *
 * **Scheme mode** (R30 Customise → Design, S38 first-start
 * ask): leave [themeColour] null. The bar is Dynamic (where
 * offered) + seeds + pick-any. [ColourBarSelection.Dynamic]
 * means "return to the phone's dynamic colour";
 * [ColourBarSelection.Seed] carries the picked ARGB.
 *
 * **Selection-bar mode** (S23, the reader selection bar's
 * colour row): pass [themeColour] = the active scheme's
 * `primary`. The bar's FIRST swatch is then that colour
 * ("Theme colour" — the highlight follows the scheme), the
 * Dynamic swatch is not shown, and picking a seed/pick-any
 * colour reports [ColourBarSelection.Seed] — the caller stores
 * that colour AS DATA with the highlight, exactly as highlight
 * colours already work. Selecting the first swatch reports
 * [ColourBarSelection.ThemeColour].
 *
 * The bar holds no state of its own beyond the picker dialog's
 * open flag: [selection] is the caller's truth.
 */
sealed interface ColourBarSelection {
    /** Scheme mode: use the phone's dynamic colour (door 1). */
    data object Dynamic : ColourBarSelection

    /** A concrete picked colour (opaque ARGB). */
    data class Seed(val argb: Long) : ColourBarSelection

    /** Selection-bar mode: the highlight follows the scheme. */
    data object ThemeColour : ColourBarSelection
}

/** The bar's seed hues (§2.1.1): colours only, no names, no
 * scheme system behind them. Material 500 values. */
val ColourBarSeedHues: List<Long> = listOf(
    0xFFF44336L, // red
    0xFFFF9800L, // orange
    0xFFFFEB3BL, // yellow
    0xFF4CAF50L, // green
    0xFF009688L, // teal
    0xFF2196F3L, // blue
    0xFF3F51B5L, // indigo
    0xFF9C27B0L, // purple
    0xFFE91E63L, // pink
    0xFF795548L  // brown
)

@Composable
fun ColourBar(
    selection: ColourBarSelection?,
    onSelect: (ColourBarSelection) -> Unit,
    modifier: Modifier = Modifier,
    /** Scheme mode only: show the Dynamic swatch where the
     * platform offers dynamic colour. Ignored in selection-bar
     * mode ([themeColour] != null). */
    showDynamic: Boolean = Build.VERSION.SDK_INT >= 31,
    /** Selection-bar mode: the scheme's `primary`, rendered as
     * the first swatch ("Theme colour"). Null = scheme mode. */
    themeColour: Color? = null,
    /** Recent colours offered inside the picker dialog. */
    recentColours: List<Long> = emptyList(),
    pickerTitle: String = "Pick a colour"
) {
    var pickerOpen by remember { mutableStateOf(false) }
    val schemePrimary = MaterialTheme.colorScheme.primary
    val schemeTertiary = MaterialTheme.colorScheme.tertiary
    Row(
        modifier
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (themeColour != null) {
            ColourSwatch(
                selected = selection == ColourBarSelection.ThemeColour,
                contentDescription = "Theme colour",
                onClick = { onSelect(ColourBarSelection.ThemeColour) }
            ) {
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(themeColour)
                )
            }
        } else if (showDynamic) {
            ColourSwatch(
                selected = selection == ColourBarSelection.Dynamic,
                contentDescription = "Dynamic colour",
                onClick = { onSelect(ColourBarSelection.Dynamic) }
            ) {
                // Split-tone circle: primary / tertiary halves.
                Row(
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                ) {
                    Box(
                        Modifier
                            .weight(1f)
                            .height(36.dp)
                            .background(schemePrimary)
                    )
                    Box(
                        Modifier
                            .weight(1f)
                            .height(36.dp)
                            .background(schemeTertiary)
                    )
                }
            }
        }
        ColourBarSeedHues.forEach { argb ->
            val picked = (selection as? ColourBarSelection.Seed)
                ?.argb == argb
            ColourSwatch(
                selected = picked,
                contentDescription = "Colour",
                onClick = { onSelect(ColourBarSelection.Seed(argb)) }
            ) {
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color(argb.toInt()))
                )
            }
        }
        // Pick any colour: rainbow ring + plus → S37 dialog.
        ColourSwatch(
            selected = false,
            contentDescription = "Pick any colour",
            onClick = { pickerOpen = true }
        ) {
            Box(
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.sweepGradient(RainbowStops)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
    if (pickerOpen) {
        val initial = (selection as? ColourBarSelection.Seed)?.argb
            ?: themeColour?.let {
                it.toArgb().toLong() and 0xFFFFFFFFL
            }
            ?: (schemePrimary.toArgb().toLong() and 0xFFFFFFFFL)
        ColourPickerDialog(
            title = pickerTitle,
            initialArgb = initial,
            recentColours = recentColours,
            onDismiss = { pickerOpen = false },
            onPick = { argb ->
                pickerOpen = false
                onSelect(ColourBarSelection.Seed(argb))
            }
        )
    }
}

/** One swatch slot: the caller's circle, the spec selected
 * state (ring + check) drawn over it when [selected]. */
@Composable
private fun ColourSwatch(
    selected: Boolean,
    contentDescription: String,
    onClick: () -> Unit,
    circle: @Composable () -> Unit
) {
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .then(
                if (selected) {
                    Modifier.border(
                        2.dp,
                        MaterialTheme.colorScheme.primary,
                        CircleShape
                    )
                } else {
                    Modifier
                }
            )
            .clickable(onClick = onClick)
            .padding(2.dp),
        contentAlignment = Alignment.Center
    ) {
        circle()
        if (selected) {
            // The check's tone follows the swatch under it only
            // approximately — the ring already carries the state;
            // the check is drawn in the scheme's onPrimary over a
            // primary disc so it reads on any hue.
            Box(
                Modifier
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = contentDescription,
                    modifier = Modifier.size(13.dp),
                    tint = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
    }
}

private val RainbowStops: List<Color> = listOf(
    Color(0xFFF44336), Color(0xFFFF9800), Color(0xFFFFEB3B),
    Color(0xFF4CAF50), Color(0xFF009688), Color(0xFF2196F3),
    Color(0xFF9C27B0), Color(0xFFF44336)
)

/**
 * S37 — the colour picker dialog: the colour bar's deep door.
 * One picker component for every design: a saturation/value
 * square + hue strip (the wheel's working area, drawn from
 * [ColourWheelMath]), a hex field, and a recent-colours row.
 * It wears the ACTIVE design's dialog frame (under Material 3
 * an M3 basic dialog; under Nocturne the same component in
 * Nocturne's frame, §5.2). "Use this colour" commits through
 * [onPick] with an opaque ARGB; dismissing changes nothing.
 */
@Composable
fun ColourPickerDialog(
    title: String,
    initialArgb: Long,
    recentColours: List<Long> = emptyList(),
    onDismiss: () -> Unit,
    onPick: (Long) -> Unit
) {
    val startHsv = remember(initialArgb) {
        ColourWheelMath.argbToHsv(initialArgb)
    }
    var hue by remember(initialArgb) {
        mutableStateOf(startHsv[0])
    }
    var saturation by remember(initialArgb) {
        mutableStateOf(startHsv[1].coerceIn(0f, 1f))
    }
    var value by remember(initialArgb) {
        mutableStateOf(startHsv[2].coerceIn(0f, 1f))
    }
    var hexText by remember(initialArgb) {
        mutableStateOf(ColourWheelMath.formatHex(initialArgb))
    }
    val currentArgb = ColourWheelMath.hsvToArgb(hue, saturation, value)
    val hueColour = Color(
        ColourWheelMath.hsvToArgb(hue, 1f, 1f).toInt()
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    // Saturation (x) / value (y) square.
                    var squareSize by remember {
                        mutableStateOf(IntSize.Zero)
                    }
                    Box(
                        Modifier
                            .weight(1f)
                            .height(150.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                Brush.horizontalGradient(
                                    listOf(Color.White, hueColour)
                                )
                            )
                            .onSizeChanged { squareSize = it }
                            .pointerInput(squareSize) {
                                fun apply(offset: Offset) {
                                    if (squareSize.width > 0 &&
                                        squareSize.height > 0
                                    ) {
                                        saturation = (offset.x /
                                            squareSize.width)
                                            .coerceIn(0f, 1f)
                                        value = 1f - (offset.y /
                                            squareSize.height)
                                            .coerceIn(0f, 1f)
                                        hexText = ColourWheelMath
                                            .formatHex(
                                                ColourWheelMath.hsvToArgb(
                                                    hue, saturation, value
                                                )
                                            )
                                    }
                                }
                                detectTapGestures { apply(it) }
                            }
                            .pointerInput(squareSize) {
                                fun apply(offset: Offset) {
                                    if (squareSize.width > 0 &&
                                        squareSize.height > 0
                                    ) {
                                        saturation = (offset.x /
                                            squareSize.width)
                                            .coerceIn(0f, 1f)
                                        value = 1f - (offset.y /
                                            squareSize.height)
                                            .coerceIn(0f, 1f)
                                        hexText = ColourWheelMath
                                            .formatHex(
                                                ColourWheelMath.hsvToArgb(
                                                    hue, saturation, value
                                                )
                                            )
                                    }
                                }
                                detectDragGestures { change, _ ->
                                    apply(change.position)
                                }
                            }
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(150.dp)
                                .background(
                                    Brush.verticalGradient(
                                        listOf(
                                            Color.Transparent,
                                            Color.Black
                                        )
                                    )
                                )
                        )
                    }
                    // Hue strip.
                    var stripSize by remember {
                        mutableStateOf(IntSize.Zero)
                    }
                    Box(
                        Modifier
                            .size(width = 28.dp, height = 150.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                Brush.verticalGradient(
                                    listOf(
                                        Color(0xFFFF0000),
                                        Color(0xFFFFFF00),
                                        Color(0xFF00FF00),
                                        Color(0xFF00FFFF),
                                        Color(0xFF0000FF),
                                        Color(0xFFFF00FF),
                                        Color(0xFFFF0000)
                                    )
                                )
                            )
                            .onSizeChanged { stripSize = it }
                            .pointerInput(stripSize) {
                                fun apply(offset: Offset) {
                                    if (stripSize.height > 0) {
                                        hue = ((offset.y /
                                            stripSize.height)
                                            .coerceIn(0f, 1f) * 360f)
                                        hexText = ColourWheelMath
                                            .formatHex(
                                                ColourWheelMath.hsvToArgb(
                                                    hue, saturation, value
                                                )
                                            )
                                    }
                                }
                                detectTapGestures { apply(it) }
                            }
                            .pointerInput(stripSize) {
                                fun apply(offset: Offset) {
                                    if (stripSize.height > 0) {
                                        hue = ((offset.y /
                                            stripSize.height)
                                            .coerceIn(0f, 1f) * 360f)
                                        hexText = ColourWheelMath
                                            .formatHex(
                                                ColourWheelMath.hsvToArgb(
                                                    hue, saturation, value
                                                )
                                            )
                                    }
                                }
                                detectDragGestures { change, _ ->
                                    apply(change.position)
                                }
                            }
                    )
                }
                // Old → new chips + hex field.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color(initialArgb.toInt()))
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.outline,
                                CircleShape
                            )
                    )
                    Text("→", style = MaterialTheme.typography.bodyLarge)
                    Box(
                        Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color(currentArgb.toInt()))
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.outline,
                                CircleShape
                            )
                    )
                    OutlinedTextField(
                        value = hexText,
                        onValueChange = { text ->
                            hexText = text
                            ColourWheelMath.parseHex(text)?.let { argb ->
                                val hsv = ColourWheelMath.argbToHsv(argb)
                                hue = hsv[0]
                                saturation = hsv[1]
                                value = hsv[2]
                            }
                        },
                        label = { Text("Hex") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                if (recentColours.isNotEmpty()) {
                    Text(
                        "Recent colours",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        recentColours.distinct().take(8).forEach { argb ->
                            Box(
                                Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(Color(argb.toInt()))
                                    .border(
                                        1.dp,
                                        MaterialTheme.colorScheme.outline,
                                        CircleShape
                                    )
                                    .clickable {
                                        val hsv = ColourWheelMath
                                            .argbToHsv(argb)
                                        hue = hsv[0]
                                        saturation = hsv[1]
                                        value = hsv[2]
                                        hexText = ColourWheelMath
                                            .formatHex(argb)
                                    }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick(currentArgb) }) {
                Text("Use this colour")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

/** Contrast helper for importers that draw on a picked colour. */
fun colourBarOnColour(argb: Long): Color =
    if (Color(argb.toInt()).luminance() > 0.5f) {
        Color.Black
    } else {
        Color.White
    }
