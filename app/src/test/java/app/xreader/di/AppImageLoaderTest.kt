package app.xreader.di

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v1.2.0 (audit finding 3, loader half): the tuned Coil loader's
 * memory budget is tier-aware — the 2 GB-class tablet gets the
 * conservative fraction, larger devices the standard one, and an
 * unreadable RAM value follows the app convention (full tier,
 * never stranded on the smallest budget by accident). The pure
 * fraction function is what's pinned here; building an Android
 * ImageLoader is the coordinator's on-device/build check.
 */
class AppImageLoaderTest {

    @Test
    fun `minimal-class devices get the small memory fraction`() {
        assertEquals(0.12, AppImageLoader.memoryCacheFraction(2_048L), 0.0)
        assertEquals(0.12, AppImageLoader.memoryCacheFraction(2_500L), 0.0)
    }

    @Test
    fun `larger and unknown devices get the standard fraction`() {
        assertEquals(0.18, AppImageLoader.memoryCacheFraction(6_144L), 0.0)
        assertEquals(0.18, AppImageLoader.memoryCacheFraction(0L), 0.0)
    }

    @Test
    fun `disk cache is capped well under the coil stock size`() {
        assertEquals(128L * 1024 * 1024, AppImageLoader.DISK_CACHE_MAX_BYTES)
    }
}
