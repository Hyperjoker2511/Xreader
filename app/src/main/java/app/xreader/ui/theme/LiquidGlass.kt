package app.xreader.ui.theme

import android.graphics.RenderEffect as AndroidRenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import app.xreader.data.prefs.GlassTier
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

/**
 * The Liquid Glass surface (Liquid Glass, piece 2): ONE reusable glass
 * surface driven by the [GlassTier] from piece 1 — LIQUID (backdrop
 * blur + edge refraction + specular rim), FROSTED (backdrop blur +
 * rim), TINTED (smoked fill + rim + sheen, no blur), SOLID (opaque).
 * This piece wires the surface into NO screen; the dock piece does
 * that (piece 3).
 *
 * ARCHITECTURE — and the honest Haze 1.5.1 ceiling. Haze 1.5.1 has NO
 * shader/custom-effect hook (verified against the real AAR:
 * HazeEffectScope exposes only alpha, backgroundColor, blurEnabled,
 * blurRadius, fallbackTint, inputScale, mask, noiseFactor, style,
 * tints), so true in-chain backdrop refraction does not exist on this
 * version. The maximum available — and what LIQUID does — is:
 *
 * 1. a DEDICATED backdrop layer inside the surface carries the
 *    [hazeEffect], sampling the shell's shared [LocalHazeState]
 *    backdrop exactly like every other glass surface in the app;
 * 2. on API 33+, a RuntimeShader [RenderEffect] is attached to THAT
 *    BACKDROP LAYER's own graphicsLayer, post-processing the blurred
 *    backdrop the layer just rendered — the sample bends at the
 *    edges, the glass illusion.
 *
 * The FORBIDDEN pattern — a RenderEffect on the surface's own or the
 * content's graphicsLayer, which bends the component itself into a
 * smeared slab — appears nowhere here: the outer container and the
 * content carry no effect at all. Every shader step is guarded
 * (API 33 check + runCatching): any shader failure degrades LIQUID
 * to the plain blurred backdrop, never a crash, never a blank.
 *
 * Performance (the 2 GB phone rule): the shader runs only inside
 * this surface's own bounds, only on the LIQUID plan; TINTED/SOLID
 * plans draw plain — zero blur, zero shader. Nothing allocates in
 * the draw phase; the rim/sheen are built in drawWithCache.
 */

/**
 * What one tier draws, as pure data — the whole tier→effect mapping
 * lives here so no surface invents its own idea of "liquid".
 *
 * [fillAlpha] is null for exactly one tier: TINTED, whose fill IS
 * the user's glass-opacity token ([GlassSettings.opacity], via
 * `LocalGlassSettings`) — a value this pure function cannot know
 * and must never hardcode a stand-in for. The surface resolves
 * `plan.fillAlpha ?: glass.opacity` at composition.
 */
data class GlassEffectPlan(
    val usesBackdropBlur: Boolean,
    val usesRefraction: Boolean,
    /** Specular rim strength (white gradient, brightest at the top). */
    val rimAlpha: Float,
    /** Soft top-sheen strength; 0 = no sheen. */
    val sheenAlpha: Float,
    /** The glass fill's alpha, or null = the user's opacity token decides (TINTED). */
    val fillAlpha: Float?
)

/**
 * The tier→effect plan. Pure and total over [GlassTier]; pinned
 * cell-by-cell by GlassEffectPlanTest. FROSTED's fill (0.72) is
 * today's shipped [GlassSettings.glassSurface] alpha — the frosted
 * tier must not drift from the behaviour the contrast gate pinned.
 */
fun glassEffectPlan(tier: GlassTier): GlassEffectPlan = when (tier) {
    GlassTier.LIQUID -> GlassEffectPlan(
        usesBackdropBlur = true,
        usesRefraction = true,
        rimAlpha = 0.45f,
        sheenAlpha = 0.14f,
        fillAlpha = 0.55f
    )
    GlassTier.FROSTED -> GlassEffectPlan(
        usesBackdropBlur = true,
        usesRefraction = false,
        rimAlpha = 0.30f,
        sheenAlpha = 0.08f,
        fillAlpha = 0.72f
    )
    GlassTier.TINTED -> GlassEffectPlan(
        usesBackdropBlur = false,
        usesRefraction = false,
        rimAlpha = 0.18f,
        sheenAlpha = 0.05f,
        fillAlpha = null // the user's opacity token decides — never a constant
    )
    GlassTier.SOLID -> GlassEffectPlan(
        usesBackdropBlur = false,
        usesRefraction = false,
        rimAlpha = 0.12f,
        sheenAlpha = 0.0f,
        fillAlpha = 1.0f
    )
}

/**
 * The edge-refraction shader (AGSL). It post-processes the backdrop
 * layer's own raster: inside an outer band (~12.dp) the sample point
 * is pushed toward the centre, compressing the blurred backdrop at
 * the rim the way a real glass edge does, with a slight chromatic
 * split along the same direction. The interior is sampled untouched.
 *
 * `uniform shader content;` is the layer's own drawing, bound by
 * [AndroidRenderEffect.createRuntimeShaderEffect] under the name
 * "content" — it MUST be declared (an earlier draft omitted it and
 * the shader throws at first use).
 */
private const val LIQUID_REFRACTION_AGSL = """
uniform shader content;
uniform float2 resolution;
uniform float edgeWidth;
uniform float bend;

half4 main(float2 coord) {
    float d = min(min(coord.x, resolution.x - coord.x),
                  min(coord.y, resolution.y - coord.y));
    float band = 1.0 - smoothstep(0.0, edgeWidth, max(d, 0.0));
    if (band <= 0.0) {
        return content.eval(coord);
    }
    float2 toCenter = resolution * 0.5 - coord;
    float2 dir = toCenter / max(length(toCenter), 1.0);
    float strength = band * band * bend;
    float2 offset = dir * strength;
    float chroma = strength * 0.35;
    half4 c = content.eval(coord + offset);
    c.r = content.eval(coord + offset + dir * chroma).r;
    c.b = content.eval(coord + offset - dir * chroma).b;
    return c;
}
"""

/** A compiled refraction shader + its render effect (API 33+ only). */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private class LiquidRefraction(
    val shader: RuntimeShader,
    val effect: RenderEffect
)

/** Builds the refraction pair, or null when the device refuses —
 * the caller then renders the plain blurred backdrop. */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun createLiquidRefraction(): LiquidRefraction? = runCatching {
    val shader = RuntimeShader(LIQUID_REFRACTION_AGSL)
    LiquidRefraction(
        shader = shader,
        effect = AndroidRenderEffect
            .createRuntimeShaderEffect(shader, "content")
            .asComposeRenderEffect()
    )
}.getOrNull()

/**
 * One glass surface in the tier the caller resolved (piece 1's
 * [app.xreader.data.prefs.resolveGlassTier]). The caller's
 * [modifier] lands on the outer container untouched; the surface
 * clips to [shape]; [content] sits above every effect, itself
 * effect-free.
 *
 * Layer order, back to front: the dedicated backdrop layer (haze
 * blur [+ the refraction post-process on LIQUID/API 33+], or the
 * plain fill when no blur applies / no haze source exists in this
 * part of the tree), then the sheen + specular rim, then content.
 */
@Composable
fun LiquidGlassSurface(
    shape: Shape,
    modifier: Modifier = Modifier,
    tier: GlassTier,
    content: @Composable () -> Unit
) {
    val plan = glassEffectPlan(tier)
    val glass = LocalGlassSettings.current
    val haze = LocalHazeState.current
    val scheme = MaterialTheme.colorScheme

    // TINTED's fill is the user's opacity token; every other tier
    // takes the plan's pinned alpha. This is also the null-haze
    // fallback alpha — a LIQUID/FROSTED surface with no haze source
    // above it renders exactly this fill, at exactly this alpha.
    val fillAlpha = plan.fillAlpha ?: glass.opacity
    val fillColor = scheme.surface.copy(alpha = fillAlpha)
    val blurActive = plan.usesBackdropBlur && haze != null

    // The refraction pair exists only for the LIQUID plan on
    // API 33+; creation failure leaves it null and the surface
    // stays plain blurred glass (see the class doc).
    val refraction = remember(plan.usesRefraction) {
        if (plan.usesRefraction && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            createLiquidRefraction()
        } else {
            null
        }
    }

    Box(modifier = modifier.clip(shape)) {
        // ── The dedicated backdrop layer ──────────────────────────
        // The ONLY layer any effect touches. The refraction
        // graphicsLayer wraps the haze effect, so the shader
        // post-processes the blurred backdrop raster — never the
        // container, never the content.
        var backdrop: Modifier = Modifier.fillMaxSize()
        if (refraction != null && blurActive) {
            backdrop = backdrop.graphicsLayer {
                // Uniforms track THIS layer's own size; a failure
                // here must cost the bend, not the surface.
                val armed = runCatching {
                    refraction.shader.setFloatUniform("resolution", size.width, size.height)
                    refraction.shader.setFloatUniform("edgeWidth", 12.dp.toPx())
                    refraction.shader.setFloatUniform("bend", 5.dp.toPx())
                }.isSuccess
                renderEffect = if (armed) refraction.effect else null
            }
        }
        if (blurActive) {
            backdrop = backdrop.hazeEffect(haze!!) {
                // backgroundColor is NOT optional: Haze 1.5.1 throws
                // on the first hardware frame without it (the v1.0.1
                // frosted crash, R01 — every call site in the app
                // sets it). The plan's fill rides as the tint, so
                // fillAlpha means the same thing blurred or not.
                backgroundColor = scheme.background
                blurRadius = 24.dp
                tints = listOf(HazeTint(fillColor))
                fallbackTint = HazeTint(fillColor)
            }
        } else {
            // No blur on this plan (TINTED/SOLID), or no haze source
            // in this part of the tree: the plain fill at the plan's
            // alpha (or the user's token for TINTED).
            backdrop = backdrop.background(fillColor)
        }
        Box(backdrop)

        // ── Sheen + specular rim ──────────────────────────────────
        // One cached draw: the soft top sheen (a shape-filled
        // gradient fading out by mid-height) and the 1.dp rim — a
        // gradient stroke following the shape outline, brightest at
        // the top. The stroke is drawn 2.dp wide centred on the
        // outline; the container's clip removes the outer half,
        // leaving a true 1.dp inside the bounds.
        Box(
            Modifier
                .fillMaxSize()
                .drawWithCache {
                    val outline = shape.createOutline(size, layoutDirection, this)
                    val rimBrush = Brush.verticalGradient(
                        listOf(
                            Color.White.copy(alpha = plan.rimAlpha),
                            Color.White.copy(alpha = plan.rimAlpha * 0.28f),
                            Color.White.copy(alpha = plan.rimAlpha * 0.5f)
                        )
                    )
                    val sheenBrush = Brush.verticalGradient(
                        0.0f to Color.White.copy(alpha = plan.sheenAlpha),
                        0.45f to Color.White.copy(alpha = 0.0f)
                    )
                    onDrawWithContent {
                        drawContent()
                        if (plan.sheenAlpha > 0.0f) {
                            drawOutline(outline, brush = sheenBrush)
                        }
                        drawOutline(
                            outline,
                            brush = rimBrush,
                            style = Stroke(width = 2.dp.toPx())
                        )
                    }
                }
        )

        // ── Content: above everything, touched by nothing ─────────
        content()
    }
}
