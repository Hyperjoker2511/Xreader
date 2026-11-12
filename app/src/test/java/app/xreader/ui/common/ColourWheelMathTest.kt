package app.xreader.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** v1.5.40: the colour wheel's pure math — round trips and the
 * hex box's contract, with no Android framework in the way. */
class ColourWheelMathTest {

    @Test
    fun `primary colours survive the round trip`() {
        for (argb in listOf(
            0xFFFF0000L, 0xFF00FF00L, 0xFF0000FFL,
            0xFFFFFF00L, 0xFF00FFFFL, 0xFFFF00FFL,
            0xFF000000L, 0xFFFFFFFFL, 0xFF808080L
        )) {
            val hsv = ColourWheelMath.argbToHsv(argb)
            assertEquals(
                "round trip of %08X".format(argb),
                argb,
                ColourWheelMath.hsvToArgb(hsv[0], hsv[1], hsv[2])
            )
        }
    }

    @Test
    fun `a reader page colour survives the round trip within a step`() {
        // The novel reader's "Deep blue" custom page colour.
        val argb = 0xFF0E1524L
        val hsv = ColourWheelMath.argbToHsv(argb)
        val back = ColourWheelMath.hsvToArgb(hsv[0], hsv[1], hsv[2])
        for (shift in listOf(16, 8, 0)) {
            val a = ((argb shr shift) and 0xFF)
            val b = ((back shr shift) and 0xFF)
            assertEquals("channel at shift $shift", a, b, 1L)
        }
    }

    @Test
    fun `known hsv landmarks`() {
        val red = ColourWheelMath.argbToHsv(0xFFFF0000L)
        assertEquals(0f, red[0], 0.01f)
        assertEquals(1f, red[1], 0.01f)
        assertEquals(1f, red[2], 0.01f)
        val grey = ColourWheelMath.argbToHsv(0xFF808080L)
        assertEquals(0f, grey[1], 0.01f)
        assertEquals(0.5f, grey[2], 0.01f)
    }

    @Test
    fun `hex formats and parses per the photo spec`() {
        assertEquals("#0E1524", ColourWheelMath.formatHex(0xFF0E1524L))
        assertEquals("#FFFFFF", ColourWheelMath.formatHex(0xFFFFFFFFL))
        assertEquals(0xFF0E1524L, ColourWheelMath.parseHex("#0E1524"))
        assertEquals(0xFF0E1524L, ColourWheelMath.parseHex("0e1524"))
        assertNull(ColourWheelMath.parseHex("#0E15"))
        assertNull(ColourWheelMath.parseHex("#GGGGGG"))
        assertNull(ColourWheelMath.parseHex(""))
    }

    @Test
    fun `hex rejects signed and malformed input`() {
        // v1.5.40 re-scan D2: toLongOrNull(16) accepts a leading
        // sign, so "-12345" used to parse as a colour.
        assertNull(ColourWheelMath.parseHex("-12345"))
        assertNull(ColourWheelMath.parseHex("+12345"))
        assertNull(ColourWheelMath.parseHex("#-12345"))
        assertNull(ColourWheelMath.parseHex("12 456"))
        assertNull(ColourWheelMath.parseHex("#1234G6"))
        assertNull(ColourWheelMath.parseHex("##123456"))
    }
}
