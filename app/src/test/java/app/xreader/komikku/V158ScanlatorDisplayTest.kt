package app.xreader.komikku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * v1.5.8 (E2): the donor's chapter meta line — scanlator
 * dot-separated alongside the date; a null scanlator renders
 * NO segment (no placeholder, no dangling dot).
 */
class V158ScanlatorDisplayTest {

    @Test
    fun `date and scanlator render dot-separated`() {
        assertEquals(
            "09.09.2026 · Gengar",
            ScanlatorLine.format("09.09.2026", "Gengar")
        )
    }

    @Test
    fun `scanlator alone renders without a leading dot`() {
        assertEquals("Gengar", ScanlatorLine.format(null, "Gengar"))
    }

    @Test
    fun `no scanlator renders the date only`() {
        assertEquals("09.09.2026", ScanlatorLine.format("09.09.2026", null))
        assertEquals("09.09.2026", ScanlatorLine.format("09.09.2026", "  "))
    }

    @Test
    fun `neither value renders no line at all`() {
        assertNull(ScanlatorLine.format(null, null))
        assertNull(ScanlatorLine.format("", ""))
    }
}
