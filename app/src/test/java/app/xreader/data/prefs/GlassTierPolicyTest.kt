package app.xreader.data.prefs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The glass tier resolver (Liquid Glass, piece 1): every cell of the
 * choice × sdk × RAM × perf-tier matrix is asserted against an
 * independent truth table written from the rules, the FROSTED choice
 * is pinned to the REAL [AppearanceValidation.effectiveGlassStyle]
 * so the resolver can never drift from the shipped rule, and the
 * boundary cells are pinned individually.
 */
class GlassTierPolicyTest {

    private val sdks = listOf(28, 30, 31, 32, 33, 34, 36)
    private val rams = listOf(1024L, 2048L, 2049L, 4096L, 4097L, 6144L)
    private val tiers = PerfTier.entries
    private val choices = GlassStyle.entries

    /** The rules restated test-side (never calls the resolver):
     * SOLID/TINTED pass through; FROSTED needs sdk >= 31 and
     * RAM > 2048; LIQUID needs sdk >= 33 and the FULL tier, else it
     * falls back through the frosted rule. */
    private fun expected(choice: GlassStyle, sdk: Int, ram: Long, tier: PerfTier): GlassTier {
        val frosted = sdk >= 31 && ram > 2048
        return when (choice) {
            GlassStyle.SOLID -> GlassTier.SOLID
            GlassStyle.TINTED -> GlassTier.TINTED
            GlassStyle.FROSTED -> if (frosted) GlassTier.FROSTED else GlassTier.TINTED
            GlassStyle.LIQUID -> when {
                sdk >= 33 && tier == PerfTier.FULL -> GlassTier.LIQUID
                frosted -> GlassTier.FROSTED
                else -> GlassTier.TINTED
            }
        }
    }

    @Test
    fun `full matrix matches the truth table`() {
        var cells = 0
        for (choice in choices) for (sdk in sdks) for (ram in rams) for (tier in tiers) {
            assertEquals(
                "choice=$choice sdk=$sdk ram=$ram tier=$tier",
                expected(choice, sdk, ram, tier),
                resolveGlassTier(choice, sdk, ram, tier)
            )
            cells++
        }
        assertEquals(choices.size * sdks.size * rams.size * tiers.size, cells)
    }

    @Test
    fun `frosted choice equals the real effectiveGlassStyle on every device`() {
        for (sdk in sdks) for (ram in rams) for (tier in tiers) {
            val effective = AppearanceValidation.effectiveGlassStyle(GlassStyle.FROSTED, sdk, ram)
            val expectedTier = when (effective) {
                GlassStyle.FROSTED -> GlassTier.FROSTED
                GlassStyle.TINTED -> GlassTier.TINTED
                else -> fail("effectiveGlassStyle(FROSTED) returned $effective")
            }
            assertEquals(
                "sdk=$sdk ram=$ram tier=$tier",
                expectedTier,
                resolveGlassTier(GlassStyle.FROSTED, sdk, ram, tier)
            )
        }
    }

    @Test
    fun `liquid result only for the liquid choice above both gates`() {
        for (choice in choices) for (sdk in sdks) for (ram in rams) for (tier in tiers) {
            val result = resolveGlassTier(choice, sdk, ram, tier)
            if (result == GlassTier.LIQUID) {
                assertTrue("choice=$choice", choice == GlassStyle.LIQUID)
                assertTrue("sdk=$sdk", sdk >= 33)
                assertTrue("tier=$tier", tier == PerfTier.FULL)
            }
        }
    }

    @Test
    fun `frosted result never below sdk 31 or at ram 2048 or less`() {
        for (choice in choices) for (sdk in sdks) for (ram in rams) for (tier in tiers) {
            if (resolveGlassTier(choice, sdk, ram, tier) == GlassTier.FROSTED) {
                assertTrue("choice=$choice sdk=$sdk", sdk >= 31)
                assertTrue("choice=$choice ram=$ram", ram > 2048)
            }
        }
    }

    @Test
    fun `frosted choice is independent of perf tier`() {
        for (sdk in sdks) for (ram in rams) {
            val results = tiers.map { resolveGlassTier(GlassStyle.FROSTED, sdk, ram, it) }.toSet()
            assertEquals("sdk=$sdk ram=$ram", 1, results.size)
        }
    }

    @Test
    fun `ram exactly 2048 is not frosted`() {
        assertEquals(
            GlassTier.TINTED,
            resolveGlassTier(GlassStyle.FROSTED, 36, 2048, PerfTier.FULL)
        )
        // The LIQUID fallback obeys the same bar (STANDARD keeps it
        // out of the liquid gate, so the frosted rule decides).
        assertEquals(
            GlassTier.TINTED,
            resolveGlassTier(GlassStyle.LIQUID, 33, 2048, PerfTier.STANDARD)
        )
    }

    @Test
    fun `ram 2049 is frosted eligible`() {
        assertEquals(
            GlassTier.FROSTED,
            resolveGlassTier(GlassStyle.FROSTED, 31, 2049, PerfTier.MINIMAL)
        )
    }

    @Test
    fun `sdk exactly 33 with full tier is liquid`() {
        assertEquals(
            GlassTier.LIQUID,
            resolveGlassTier(GlassStyle.LIQUID, 33, 6144, PerfTier.FULL)
        )
    }

    @Test
    fun `sdk 33 with standard tier is not liquid`() {
        assertEquals(
            GlassTier.FROSTED,
            resolveGlassTier(GlassStyle.LIQUID, 33, 6144, PerfTier.STANDARD)
        )
    }

    @Test
    fun `sdk 32 with full tier is not liquid`() {
        assertEquals(
            GlassTier.FROSTED,
            resolveGlassTier(GlassStyle.LIQUID, 32, 6144, PerfTier.FULL)
        )
    }

    @Test
    fun `liquid falls back to tinted on a low ram old phone`() {
        assertEquals(
            GlassTier.TINTED,
            resolveGlassTier(GlassStyle.LIQUID, 28, 1024, PerfTier.MINIMAL)
        )
        assertEquals(
            GlassTier.TINTED,
            resolveGlassTier(GlassStyle.LIQUID, 33, 1024, PerfTier.MINIMAL)
        )
    }

    @Test
    fun `solid and tinted pass through on every device`() {
        for (sdk in sdks) for (ram in rams) for (tier in tiers) {
            assertEquals(
                "sdk=$sdk ram=$ram tier=$tier",
                GlassTier.SOLID,
                resolveGlassTier(GlassStyle.SOLID, sdk, ram, tier)
            )
            assertEquals(
                "sdk=$sdk ram=$ram tier=$tier",
                GlassTier.TINTED,
                resolveGlassTier(GlassStyle.TINTED, sdk, ram, tier)
            )
        }
    }
}
