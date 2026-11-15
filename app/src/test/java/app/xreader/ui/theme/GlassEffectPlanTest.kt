package app.xreader.ui.theme

import androidx.compose.ui.graphics.Color
import app.xreader.data.prefs.GlassStyle
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
 *
 * Glass blueprint (2026-11-15): the plan is now resolved
 * style + tier (glassEffectPlanFor) — identity follows the chosen
 * style, capability follows the resolved tier. glassEffectPlan
 * (tier) remains as the same-name delegate. Two pre-blueprint
 * pins moved, both BY THE SPEC: FROSTED/TINTED rim stroke 2 dp →
 * 3 dp (the ~1.5 dp visible-rim geometry), and FROSTED's rim
 * alpha 0.30 → 0.40. The old white-rim ordering pin
 * (LIQUID > FROSTED > TINTED > SOLID) no longer applies to
 * TINTED, whose rim is now primary chroma (0.55 top / 0.25
 * bottom), pinned separately below.
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
    fun `rim width is 3 dp on the glass identities and 2 dp on solid`() {
        // Blueprint pin move (spec): FROSTED and TINTED move from
        // the shipped 2 dp stroke to the shared 3 dp stroke, so
        // their visible rim is ~1.5 dp like LIQUID's. SOLID keeps
        // its shipped 2 dp — its cell is byte-untouched.
        assertEquals(3f, glassEffectPlan(GlassTier.LIQUID).rimWidthDp, 0.0f)
        assertEquals(3f, glassEffectPlan(GlassTier.FROSTED).rimWidthDp, 0.0f)
        assertEquals(3f, glassEffectPlan(GlassTier.TINTED).rimWidthDp, 0.0f)
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
    fun `solid plan carries no blueprint identity fields`() {
        // SOLID is byte-untouched by the blueprint: every new
        // field stays at its absent default, on any tier.
        GlassTier.entries.forEach { tier ->
            val plan = glassEffectPlanFor(GlassStyle.SOLID, tier)
            assertEquals(0.12f, plan.rimAlpha, 0.0f)
            assertEquals(2f, plan.rimWidthDp, 0.0f)
            assertEquals(0f, plan.chromaTopAlpha, 0.0f)
            assertEquals(0f, plan.chromaBottomAlpha, 0.0f)
            assertEquals(0f, plan.hazeTopAlpha, 0.0f)
            assertEquals(0f, plan.supportRimAlpha, 0.0f)
            assertTrue(!plan.supportRimChroma)
            assertTrue(!plan.diagonalSheen)
            assertTrue(!plan.rimChroma)
            assertTrue(!plan.staticVariant)
        }
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
    fun `white rim is strongest on liquid, then frosted, weakest on solid`() {
        // Blueprint pin move (spec): the old ordering included
        // TINTED's white rim (0.18); TINTED's rim is primary
        // chroma now and is pinned by the chroma test below.
        val rim = GlassTier.entries.associateWith { glassEffectPlan(it).rimAlpha }
        assertTrue(rim.getValue(GlassTier.LIQUID) > rim.getValue(GlassTier.FROSTED))
        assertTrue(rim.getValue(GlassTier.FROSTED) > rim.getValue(GlassTier.SOLID))
    }

    @Test
    fun `sheen lives on the glass tiers only`() {
        assertTrue(glassEffectPlan(GlassTier.LIQUID).sheenAlpha > 0.0f)
        assertTrue(glassEffectPlan(GlassTier.FROSTED).sheenAlpha > 0.0f)
        assertTrue(glassEffectPlan(GlassTier.TINTED).sheenAlpha > 0.0f)
        assertEquals(0.0f, glassEffectPlan(GlassTier.SOLID).sheenAlpha, 0.0f)
    }

    // ── Glass blueprint: identity cells ─────────────────────────

    @Test
    fun `liquid identity has the chroma core and layered rim`() {
        val plan = glassEffectPlanFor(GlassStyle.LIQUID, GlassTier.LIQUID)
        assertEquals(0.15f, plan.chromaTopAlpha, 0.0f)
        assertEquals(0.04f, plan.chromaBottomAlpha, 0.0f)
        assertEquals(0.30f, plan.supportRimAlpha, 0.0f)
        assertTrue(plan.supportRimChroma)
        assertEquals(5f, plan.supportRimWidthDp, 0.0f)
        assertTrue(plan.diagonalSheen)
        assertTrue(!plan.rimChroma)
        assertTrue(!plan.staticVariant)
        assertEquals(0f, plan.hazeTopAlpha, 0.0f)
    }

    @Test
    fun `frosted identity has the white haze and bright rim`() {
        val plan = glassEffectPlanFor(GlassStyle.FROSTED, GlassTier.FROSTED)
        assertEquals(0.72f, plan.fillAlpha!!, 0.0f)
        assertEquals(0.08f, plan.hazeTopAlpha, 0.0f)
        // Blueprint pin move (spec): rim 0.30 → 0.40.
        assertEquals(0.40f, plan.rimAlpha, 0.0f)
        assertEquals(0.10f, plan.supportRimAlpha, 0.0f)
        assertTrue(!plan.supportRimChroma)
        assertEquals(5f, plan.supportRimWidthDp, 0.0f)
        assertTrue(plan.diagonalSheen)
        assertEquals(0.08f, plan.sheenAlpha, 0.0f)
        assertTrue(!plan.staticVariant)
        assertEquals(0f, plan.chromaTopAlpha, 0.0f)
    }

    @Test
    fun `tinted identity is pure chroma`() {
        val plan = glassEffectPlanFor(GlassStyle.TINTED, GlassTier.TINTED)
        assertEquals(0.20f, plan.chromaTopAlpha, 0.0f)
        assertEquals(0.10f, plan.chromaBottomAlpha, 0.0f)
        assertTrue(plan.rimChroma)
        assertEquals(0.55f, plan.rimAlpha, 0.0f)
        assertEquals(0.25f, plan.rimBottomAlpha, 0.0f)
        assertEquals(0.05f, plan.sheenAlpha, 0.0f)
        assertTrue(!plan.diagonalSheen) // TINTED's sheen stays vertical
        assertEquals(0f, plan.supportRimAlpha, 0.0f)
        assertEquals(0f, plan.hazeTopAlpha, 0.0f)
        assertTrue(plan.staticVariant) // renders without blur by construction
    }

    // ── Glass blueprint: the resolver (identity × capability) ───

    @Test
    fun `liquid choice on tinted tier renders liquid identity statically`() {
        val plan = glassEffectPlanFor(GlassStyle.LIQUID, GlassTier.TINTED)
        // Identity: Liquid's own values, NOT the tinted plan's.
        assertEquals(0.65f, plan.rimAlpha, 0.0f)
        assertEquals(0.22f, plan.sheenAlpha, 0.0f)
        assertEquals(0.15f, plan.chromaTopAlpha, 0.0f)
        assertEquals(0.04f, plan.chromaBottomAlpha, 0.0f)
        assertEquals(0.30f, plan.supportRimAlpha, 0.0f)
        // Capability: none — raised static fill, no blur/refraction.
        assertEquals(0.68f, plan.fillAlpha!!, 0.0f)
        assertTrue(!plan.usesBackdropBlur)
        assertTrue(!plan.usesRefraction)
        assertTrue(plan.staticVariant)
    }

    @Test
    fun `liquid choice on frosted tier keeps liquid identity with frosted capability`() {
        val plan = glassEffectPlanFor(GlassStyle.LIQUID, GlassTier.FROSTED)
        assertEquals(0.65f, plan.rimAlpha, 0.0f)
        assertEquals(0.15f, plan.chromaTopAlpha, 0.0f)
        assertEquals(0.62f, plan.fillAlpha!!, 0.0f)
        assertTrue(plan.usesBackdropBlur)
        assertTrue(!plan.usesRefraction)
        assertTrue(!plan.staticVariant)
    }

    @Test
    fun `frosted choice on tinted tier renders frosted identity statically`() {
        val plan = glassEffectPlanFor(GlassStyle.FROSTED, GlassTier.TINTED)
        // Identity: Frosted's own values, NOT the tinted plan's.
        assertEquals(0.40f, plan.rimAlpha, 0.0f)
        assertEquals(0.08f, plan.hazeTopAlpha, 0.0f)
        assertEquals(0.10f, plan.supportRimAlpha, 0.0f)
        assertEquals(0f, plan.chromaTopAlpha, 0.0f)
        // Capability: none — raised static fill, no blur.
        assertEquals(0.78f, plan.fillAlpha!!, 0.0f)
        assertTrue(!plan.usesBackdropBlur)
        assertTrue(!plan.usesRefraction)
        assertTrue(plan.staticVariant)
    }

    @Test
    fun `power save forces the static variants`() {
        val liquid = glassEffectPlanFor(
            GlassStyle.LIQUID, GlassTier.LIQUID, forceStatic = true
        )
        assertEquals(0.68f, liquid.fillAlpha!!, 0.0f)
        assertTrue(!liquid.usesBackdropBlur)
        assertTrue(!liquid.usesRefraction)
        assertTrue(liquid.staticVariant)
        val frosted = glassEffectPlanFor(
            GlassStyle.FROSTED, GlassTier.FROSTED, forceStatic = true
        )
        assertEquals(0.78f, frosted.fillAlpha!!, 0.0f)
        assertTrue(!frosted.usesBackdropBlur)
        assertTrue(frosted.staticVariant)
        // SOLID and TINTED are static by construction: unchanged.
        assertEquals(
            glassEffectPlanFor(GlassStyle.SOLID, GlassTier.SOLID),
            glassEffectPlanFor(GlassStyle.SOLID, GlassTier.SOLID, forceStatic = true)
        )
        assertEquals(
            glassEffectPlanFor(GlassStyle.TINTED, GlassTier.TINTED),
            glassEffectPlanFor(GlassStyle.TINTED, GlassTier.TINTED, forceStatic = true)
        )
    }

    @Test
    fun `glassSurface alphas mirror the plan fills`() {
        // Theme.kt consistency (blueprint): the surfaces that
        // still consume glassSurface agree with the plan fills.
        // Tolerance 0.004f: a Color stores alpha in 255 steps, so
        // the exact plan values are unrepresentable (0.62 lands on
        // 158/255 ≈ 0.6196) — the original 0.0f delta could never
        // pass for ANY correct code (CI 43734085152: 1 of 2,819
        // tests, this one). One step (1/255) is the tightest
        // tolerance a Color can honour and stays ~15x tighter
        // than the smallest gap between pinned fills (0.06), so
        // the pin still catches any real drift. Owner-approved
        // repair, 2026-11-15.
        val surface = Color(0xFF161822)
        assertEquals(
            0.62f,
            GlassSettings(style = GlassStyle.LIQUID, tier = GlassTier.LIQUID)
                .glassSurface(surface).alpha, 0.004f
        )
        assertEquals(
            0.62f,
            GlassSettings(style = GlassStyle.LIQUID, tier = GlassTier.FROSTED)
                .glassSurface(surface).alpha, 0.004f
        )
        assertEquals(
            0.68f,
            GlassSettings(style = GlassStyle.LIQUID, tier = GlassTier.TINTED)
                .glassSurface(surface).alpha, 0.004f
        )
        assertEquals(
            0.72f,
            GlassSettings(style = GlassStyle.FROSTED, tier = GlassTier.FROSTED)
                .glassSurface(surface).alpha, 0.004f
        )
        assertEquals(
            0.78f,
            GlassSettings(style = GlassStyle.FROSTED, tier = GlassTier.TINTED)
                .glassSurface(surface).alpha, 0.004f
        )
        assertEquals(
            1.0f,
            GlassSettings(style = GlassStyle.SOLID, tier = GlassTier.SOLID)
                .glassSurface(surface).alpha, 0.004f
        )
    }
}
