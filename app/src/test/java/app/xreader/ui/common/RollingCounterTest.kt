package app.xreader.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Counter (Patch 15) pure-logic proofs: the string splitting
 * (digits roll, glyphs stand still) and the odometer wheel maths
 * (always forward, never rewinds, 9 → 0 is one tick).
 */
class RollingCounterTest {

    @Test
    fun plainNumberIsAllDigits() {
        assertEquals(
            listOf(
                CounterCell.Digit(1),
                CounterCell.Digit(2),
                CounterCell.Digit(8)
            ),
            counterCellsFor("128")
        )
    }

    @Test
    fun formattedMinutesKeepTheirGlyphs() {
        // History's formatMinutes shapes: "25 min", "2 h", "1 h 5 min".
        assertEquals(
            listOf(
                CounterCell.Digit(2),
                CounterCell.Digit(5),
                CounterCell.Fixed(' '),
                CounterCell.Fixed('m'),
                CounterCell.Fixed('i'),
                CounterCell.Fixed('n')
            ),
            counterCellsFor("25 min")
        )
        assertEquals(
            listOf(
                CounterCell.Digit(1),
                CounterCell.Fixed(' '),
                CounterCell.Fixed('h'),
                CounterCell.Fixed(' '),
                CounterCell.Digit(5),
                CounterCell.Fixed(' '),
                CounterCell.Fixed('m'),
                CounterCell.Fixed('i'),
                CounterCell.Fixed('n')
            ),
            counterCellsFor("1 h 5 min")
        )
    }

    @Test
    fun groupedNumberKeepsItsComma() {
        assertEquals(
            listOf(
                CounterCell.Digit(1),
                CounterCell.Fixed(','),
                CounterCell.Digit(2),
                CounterCell.Digit(3),
                CounterCell.Digit(4)
            ),
            counterCellsFor("1,234")
        )
    }

    @Test
    fun emptyStringHasNoCells() {
        assertEquals(emptyList<CounterCell>(), counterCellsFor(""))
    }

    @Test
    fun rollDeltaIsAlwaysForward() {
        assertEquals(0, rollDelta(5, 5))
        assertEquals(4, rollDelta(3, 7))
        assertEquals(1, rollDelta(9, 0)) // the odometer tick
        assertEquals(9, rollDelta(0, 9)) // forward, never a rewind
        assertEquals(9, rollDelta(1, 0))
    }

    @Test
    fun nextWheelTargetRollsForwardFromRestingPosition() {
        // Resting at 13 (digit 3): becoming 7 rolls ahead to 17.
        assertEquals(17f, nextWheelTarget(13f, 7), 0.0001f)
        // Becoming the same digit does not move.
        assertEquals(13f, nextWheelTarget(13f, 3), 0.0001f)
        // Resting at 19 (digit 9): becoming 0 ticks to 20.
        assertEquals(20f, nextWheelTarget(19f, 0), 0.0001f)
        // Resting at 10 (digit 0): becoming 9 rolls the long way
        // forward to 19 — forward only, by design.
        assertEquals(19f, nextWheelTarget(10f, 9), 0.0001f)
    }
}
