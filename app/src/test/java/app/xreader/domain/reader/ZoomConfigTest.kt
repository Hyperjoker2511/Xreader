package app.xreader.domain.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.2.0 (WS2) pins for the manga-zoom settings on [ReaderConfig]:
 * Mihon's long-strip defaults ("Double-tap to zoom" on,
 * "Disable zoom out" off) and validation pass-through — the
 * flags are booleans, so [ReaderValidation] must return them
 * exactly as stored in both directions; a validate() that
 * silently reset either one would make the settings sheet lie.
 */
class ZoomConfigTest {

    @Test
    fun zoomDefaultsFollowMihon() {
        val config = ReaderConfig()
        assertTrue(config.doubleTapToZoom)
        assertFalse(config.disableZoomOut)
    }

    @Test
    fun validationPreservesZoomFlagsBothWays() {
        val off = ReaderConfig(doubleTapToZoom = false, disableZoomOut = true)
        val validatedOff = ReaderValidation.validate(off)
        assertFalse(validatedOff.doubleTapToZoom)
        assertTrue(validatedOff.disableZoomOut)

        val on = ReaderConfig(doubleTapToZoom = true, disableZoomOut = false)
        val validatedOn = ReaderValidation.validate(on)
        assertTrue(validatedOn.doubleTapToZoom)
        assertFalse(validatedOn.disableZoomOut)
    }

    @Test
    fun validationOfTheDefaultConfigKeepsZoomDefaults() {
        val validated = ReaderValidation.validate(ReaderConfig())
        assertTrue(validated.doubleTapToZoom)
        assertFalse(validated.disableZoomOut)
        assertEquals(ReaderConfig(), validated)
    }
}
