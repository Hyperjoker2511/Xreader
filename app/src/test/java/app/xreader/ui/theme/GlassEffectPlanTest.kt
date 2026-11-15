package app.xreader.ui.theme

import app.xreader.data.prefs.GlassTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Liquid Glass tier→effect plan (Liquid Glass, piece 2): every
 * tier's exact plan values are pinned, FROSTED's fill is pinned to
 * today's shipped glassSurface alpha so the frosted tier can never
 * silently drift, and TINTED is pinned to carrying NO fixed fill —
 * its fill is the user's opacity token, resolved at composition.
 */
class GlassEffectPlanTest {

    @Test
    fun `plan is total over every tier`() {
        val plans = GlassTier.entries.associateWith { glassEffectPlan(it) }
        assertEquals(GlassTier.entries.size, plans.size)
        // No tier silently shares another tier's plan.
        assertEquals(GlassTier.entries.size, plans.values.toSet().size)
    }

    @Test
    fun `liquid plan blurs and bends`() {
        val plan = glassEffectPlan(GlassTier.LIQUID)
        assertTrue(plan.usesBackdropBlur)
        assertTrue(plan.usesRefraction)
        // Visible-layer fix round (§B): the LIQUID cell is raised
        // (fill 0.62, rim 0.65, sheen 0.22) so the tier is
        // tellable from FROSTED at a glance on black — the
        // shipped 0.55/0.45/0.14 cell read as the old frosted
        // pill on the owner's phone.
        assertEquals(0.62f, plan.fillAlpha!!, 0.0f)
        assertEquals(0.65f, plan.rimAlpha, 0.0f)
        assertEquals(0.22f, plan.sheenAlpha, 0.0f)
    }

    @Test
    fun `rim width is 3 dp on liquid and 2 dp elsewhere`() {
        assertEquals(3f, glassEffectPlan(GlassTier.LIQUID).rimWidthDp, 0.0f)
        assertEquals(2f, glassEffectPlan(GlassTier.FROSTED).rimWidthDp, 0.0f)
        assertEquals(2f, glassEffectPlan(GlassTier.TINTED).rimWidthDp, 0.0f)
        assertEquals(2f, glassEffectPlan(GlassTier.SOLID).rimWidthDp, 0.0f)
    }

    @Test
    fun `glass settings carry the resolved tier and blur liveness`() {
        // The theme's construction (fix round §B): a LIQUID
        // choice on a device that earns the tier (API 33+, FULL
        // performance tier) yields tier LIQUID and a live blur
        // gate; on a device that resolves down to TINTED the
        // gate is dead — no Haze node may be composed.
        val liquidTier = app.xreader.data.prefs.resolveGlassTier(
            app.xreader.data.prefs.GlassStyle.LIQUID, 36, 6144,
            app.xreader.data.prefs.PerfTier.FULL
        )
        val liquid = GlassSettings(tier = liquidTier)
        assertEquals(GlassTier.LIQUID, liquid.tier)
        assertTrue(liquid.blurLive)
        val tintedTier = app.xreader.data.prefs.resolveGlassTier(
            app.xreader.data.prefs.GlassStyle.LIQUID, 28, 1024,
            app.xreader.data.prefs.PerfTier.MINIMAL
        )
        val tinted = GlassSettings(tier = tintedTier)
        assertEquals(GlassTier.TINTED, tinted.tier)
        assertTrue(!tinted.blurLive)
    }

    @Test
    fun `frosted plan matches today's shipped fill`() {
        val plan = glassEffectPlan(GlassTier.FROSTED)
        assertTrue(plan.usesBackdropBlur)
        assertTrue(!plan.usesRefraction)
        // GlassSettings.glassSurface's FROSTED alpha at v1.5.42 —
        // the value the contrast gate pinned.
        assertEquals(0.72f, plan.fillAlpha!!, 0.0f)
    }

    @Test
    fun `tinted plan carries no fixed fill alpha`() {
        val plan = glassEffectPlan(GlassTier.TINTED)
        assertTrue(!plan.usesBackdropBlur)
        assertTrue(!plan.usesRefraction)
        // The fill is the user's opacity token (LocalGlassSettings);
        // a hardcoded stand-in here was the drafts' rejected defect.
        assertNull(plan.fillAlpha)
    }

    @Test
    fun `solid plan is fully opaque and effect free`() {
        val plan = glassEffectPlan(GlassTier.SOLID)
        assertTrue(!plan.usesBackdropBlur)
        assertTrue(!plan.usesRefraction)
        assertEquals(1.0f, plan.fillAlpha!!, 0.0f)
        assertEquals(0.0f, plan.sheenAlpha, 0.0f)
    }

    @Test
    fun `refraction is liquid only`() {
        val bending = GlassTier.entries.filter { glassEffectPlan(it).usesRefraction }
        assertEquals(listOf(GlassTier.LIQUID), bending)
    }

    @Test
    fun `blur is liquid and frosted only`() {
        val blurring = GlassTier.entries.filter { glassEffectPlan(it).usesBackdropBlur }
        assertEquals(listOf(GlassTier.LIQUID, GlassTier.FROSTED), blurring)
    }

    @Test
    fun `rim is strongest on liquid and weakest on solid`() {
        val rim = GlassTier.entries.associateWith { glassEffectPlan(it).rimAlpha }
        assertTrue(rim.getValue(GlassTier.LIQUID) > rim.getValue(GlassTier.FROSTED))
        assertTrue(rim.getValue(GlassTier.FROSTED) > rim.getValue(GlassTier.TINTED))
        assertTrue(rim.getValue(GlassTier.TINTED) > rim.getValue(GlassTier.SOLID))
    }

    @Test
    fun `sheen lives on the glass tiers only`() {
        assertTrue(glassEffectPlan(GlassTier.LIQUID).sheenAlpha > 0.0f)
        assertTrue(glassEffectPlan(GlassTier.FROSTED).sheenAlpha > 0.0f)
        assertTrue(glassEffectPlan(GlassTier.TINTED).sheenAlpha > 0.0f)
        assertEquals(0.0f, glassEffectPlan(GlassTier.SOLID).sheenAlpha, 0.0f)
    }
}
