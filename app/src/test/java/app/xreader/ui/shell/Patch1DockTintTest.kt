package app.xreader.ui.shell

import app.xreader.data.prefs.GlassTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * v1.7 Patch 1 (item 3d): the dock's fill override — the
 * covers scrolling under the bar must visibly show through
 * on the glass tiers AND on the plain TINTED tier (whose
 * base is a see-through tint now, not the opaque slab it
 * was). FROSTED keeps its pinned 0.72 and SOLID its opaque
 * identity.
 */
class Patch1DockTintTest {

    @Test
    fun `liquid dock fill stays 0_48`() {
        assertEquals(0.48f, dockFillAlphaOverride(GlassTier.LIQUID))
    }

    @Test
    fun `plain tinted dock fill is 0_48 see-through`() {
        assertEquals(0.48f, dockFillAlphaOverride(GlassTier.TINTED))
    }

    @Test
    fun `frosted dock keeps its pinned fill`() {
        assertNull(dockFillAlphaOverride(GlassTier.FROSTED))
    }

    @Test
    fun `solid dock keeps its opaque identity`() {
        assertNull(dockFillAlphaOverride(GlassTier.SOLID))
    }
}
