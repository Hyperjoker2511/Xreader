package app.xreader.data.prefs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** R04 foundation: true black, vibrant preset registry, and the
 * make-your-own spec storage format. */
class AppearanceR04Test {

    @Test
    fun `true black defaults off`() {
        assertFalse(AppearanceState().trueBlack)
    }

    @Test
    fun `vibrant presets are registered`() {
        listOf("Nebula violet", "Emerald", "Sunset").forEach { name ->
            assertTrue(name, name in AppearanceValidation.COLOUR_SCHEMES)
            assertEquals(name, AppearanceValidation.parseColourScheme(name))
        }
    }

    @Test
    fun `custom scheme specs round-trip through storage format`() {
        val specs = listOf(
            CustomSchemeSpec("c1", "My violet", 0xFF7C5CD6L, null, 1),
            CustomSchemeSpec("c2", "Sun | set", 0xFFF76B15L, 0xFFD6409FL, 1)
        )
        val decoded = AppearanceValidation.decodeCustomSchemes(
            AppearanceValidation.encodeCustomSchemes(specs)
        )
        assertEquals(2, decoded.size)
        assertEquals("c1", decoded[0].id)
        assertEquals(0xFF7C5CD6L, decoded[0].seedArgb)
        assertEquals(null, decoded[0].endArgb)
        assertEquals("c2", decoded[1].id)
        // The separator in the name was sanitised, not corrupting.
        assertEquals("Sun   set", decoded[1].name)
        assertEquals(0xFFD6409FL, decoded[1].endArgb)
    }

    @Test
    fun `malformed custom rows are skipped never fatal`() {
        val decoded = AppearanceValidation.decodeCustomSchemes(
            "garbage\nc9|Ok|123||1\n|no-id|5||1\nc10|Bad|notanumber||1"
        )
        assertEquals(listOf("c9"), decoded.map { it.id })
        assertTrue(AppearanceValidation.decodeCustomSchemes(null).isEmpty())
        assertTrue(AppearanceValidation.decodeCustomSchemes("").isEmpty())
    }

    @Test
    fun `custom selection validates only against stored specs`() {
        assertEquals(
            "custom:c1",
            AppearanceValidation.parseColourScheme("custom:c1", setOf("c1"))
        )
        assertEquals(
            "Ember",
            AppearanceValidation.parseColourScheme("custom:gone", setOf("c1"))
        )
        assertEquals(
            "Ember",
            AppearanceValidation.parseColourScheme("custom:c1", emptySet())
        )
    }
}
