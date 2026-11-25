package app.xreader.ui.common

import androidx.compose.animation.core.Animatable
import app.xreader.core.motion.toSpec
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import kotlin.math.roundToInt

/**
 * Counter (React Bits pick #5, NumberFlow-style): a number whose
 * digits ROLL vertically when the value changes, odometer-style,
 * always rolling forward (9 → 0 ticks ahead, never rewinds).
 * Non-digit characters (spaces, "h", "min", commas) stand still.
 * Each digit cell is exactly one "0" wide and one line tall, so
 * the rendered width can never jitter as values change. Under
 * reduced motion the value swaps instantly, as a plain [Text].
 *
 * The splitting and wheel maths are pure and JVM-tested
 * (RollingCounterTest); the composable is a thin shell over them.
 */

/** One cell of a counter string: a rolling digit, or a fixed glyph. */
sealed interface CounterCell {
    data class Digit(val digit: Int) : CounterCell
    data class Fixed(val char: Char) : CounterCell
}

/** Splits [text] into rolling digits and fixed glyphs, in order. */
fun counterCellsFor(text: String): List<CounterCell> = text.map { ch ->
    if (ch.isDigit()) CounterCell.Digit(ch.digitToInt()) else CounterCell.Fixed(ch)
}

/** Forward distance on the 0-9 wheel from [fromDigit] to [toDigit], 0..9. */
fun rollDelta(fromDigit: Int, toDigit: Int): Int =
    (toDigit - fromDigit + 10) % 10

/**
 * The wheel's next resting position when the digit at a cell
 * becomes [newDigit]: the current target plus the forward roll —
 * the wheel never rewinds, so 9 → 0 is a one-step tick forward.
 */
fun nextWheelTarget(currentTarget: Float, newDigit: Int): Float {
    val currentDigit = ((currentTarget.roundToInt() % 10) + 10) % 10
    return currentTarget + rollDelta(currentDigit, newDigit)
}

/** The wheel's home band: resting targets live in [10, 20) and
 * rebase by −10 once settled (the strip repeats every 10). */
private const val WheelBandStart = 10f
private const val WheelStripCount = 30

@Composable
fun RollingNumberText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified
) {
    if (rememberReducedMotion()) {
        Text(text = text, modifier = modifier, style = style, color = color)
        return
    }
    val cells = counterCellsFor(text)
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        cells.forEachIndexed { index, cell ->
            // Identity is the PLACE (index from the right), so a
            // value growing 9 → 10 keeps its ones cell and rolls
            // it 9 → 0 instead of rebuilding every digit.
            key(cells.size - 1 - index) {
                when (cell) {
                    is CounterCell.Digit -> RollingDigitCell(
                        digit = cell.digit,
                        style = style,
                        color = color
                    )
                    is CounterCell.Fixed -> Text(
                        text = cell.char.toString(),
                        style = style,
                        color = color
                    )
                }
            }
        }
    }
}

@Composable
private fun RollingDigitCell(
    digit: Int,
    style: TextStyle,
    color: Color
) {
    var wheelTarget by remember { mutableStateOf(WheelBandStart + digit) }
    val wheel = remember { Animatable(WheelBandStart + digit) }
    LaunchedEffect(digit) {
        val next = nextWheelTarget(wheelTarget, digit)
        if (next != wheelTarget) {
            wheelTarget = next
            wheel.animateTo(
                next,
                // Code-129 motion pass: the wheel's settle is
                // the engine's DigitWheel profile (stiffness
                // 350, dampingRatio 0.75 — the numbers this
                // call always ran, moved under the engine).
                app.xreader.core.motion.SpringMotionEngine
                    .DigitWheel.toSpec()
            )
            // Settled: rebase into the home band (visually the
            // same digit — the strip repeats every 10).
            if (wheelTarget >= WheelBandStart + 10f) {
                wheelTarget -= 10f
                wheel.snapTo(wheelTarget)
            }
        }
    }

    var cellHeightPx by remember { mutableStateOf(0f) }
    var cellWidthPx by remember { mutableStateOf(0f) }
    val density = LocalDensity.current
    Box {
        // Sizer: an invisible "0" fixes the cell at one digit
        // wide and one line tall, whatever the font.
        Text(
            text = "0",
            style = style,
            color = Color.Transparent,
            modifier = Modifier.onGloballyPositioned {
                cellHeightPx = it.size.height.toFloat()
                cellWidthPx = it.size.width.toFloat()
            }
        )
        if (cellHeightPx <= 0f || cellWidthPx <= 0f) {
            // First frame, before the sizer lands: the plain digit.
            Text(text = digit.toString(), style = style, color = color)
        } else {
            val cellHeight = with(density) { cellHeightPx.toDp() }
            val cellWidth = with(density) { cellWidthPx.toDp() }
            // The window: exactly one cell (matchParentSize keeps
            // it out of the size negotiation), clipped. Inside it
            // the full 30-digit strip rides its own layer — the
            // layer is strip-sized, so no digit is layer-clipped;
            // the window does the clipping.
            //
            // Code 125 (F2): the strip Column is a REAL
            // 30-cell layout (requiredHeight), not the
            // window's 1-cell max. The shipped code measured
            // the Column at one cell — the window Box caps
            // its children at its own size — so 29 digits sat
            // outside the graphics layer's bounds and the
            // phone drew none of them: the History pulse
            // showed labels and a stray "min" while the fixed
            // glyphs (plain Texts) rendered. The layer is
            // strip-sized now, as the design note always
            // required; the window still clips to one cell.
            Box(
                Modifier
                    .matchParentSize()
                    .clipToBounds()
            ) {
                Column(
                    Modifier
                        .requiredHeight(cellHeight * WheelStripCount)
                        .graphicsLayer {
                            translationY = -wheel.value * cellHeightPx
                        }
                ) {
                    for (i in 0 until WheelStripCount) {
                        Text(
                            text = (i % 10).toString(),
                            style = style,
                            color = color,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .height(cellHeight)
                                .width(cellWidth)
                        )
                    }
                }
            }
        }
    }
}
