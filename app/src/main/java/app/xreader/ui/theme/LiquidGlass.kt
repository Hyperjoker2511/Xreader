package app.xreader.ui.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RenderEffect as AndroidRenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import android.os.PowerManager
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.xreader.data.prefs.GlassStyle
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
    val fillAlpha: Float?,
    /** The rim stroke's full width in dp; the surface's clip
     * removes the outer half, so the visible rim is half this. */
    val rimWidthDp: Float,
    // ── Glass blueprint (2026-11-15): identity fields. Defaults
    // are all "absent", so a plan that sets none of them renders
    // exactly as the pre-blueprint plans did — SOLID's cell in
    // particular is byte-identical to the shipped one.
    /** Theme-primary gradient over the base fill, top stop
     * (0 = no chroma). The chroma COLOUR is always the scheme's
     * primary at composition — never a hardcoded hue. */
    val chromaTopAlpha: Float = 0f,
    /** Theme-primary gradient over the base fill, bottom stop. */
    val chromaBottomAlpha: Float = 0f,
    /** White haze baked into the top of the fill, fading to 0 by
     * 45% of the height (FROSTED's identity — visible even with
     * blur off). 0 = none. */
    val hazeTopAlpha: Float = 0f,
    /** A second rim stroke drawn UNDER the main rim (0 = none). */
    val supportRimAlpha: Float = 0f,
    /** True = the support rim is primary-coloured; false = white. */
    val supportRimChroma: Boolean = false,
    /** The support rim stroke's full width in dp. */
    val supportRimWidthDp: Float = 0f,
    /** True = the sheen is a linear gradient from the TOP-LEFT
     * corner (one consistent light direction); false = the legacy
     * vertical sheen. */
    val diagonalSheen: Boolean = false,
    /** True = the main rim is primary-coloured (TINTED's pure
     * chroma identity) instead of the white specular gradient. */
    val rimChroma: Boolean = false,
    /** The chroma rim's bottom-stop alpha (rimChroma plans only). */
    val rimBottomAlpha: Float = 0f,
    /** True = this plan is the identity's STATIC variant: the
     * identity rendered without blur/refraction (down-resolved
     * tier, or power-save). Static variants are the only plans
     * eligible for the grain dither layer. */
    val staticVariant: Boolean = false
)

/**
 * The tier→effect plan (legacy entry point). Pure and total over
 * [GlassTier]; pinned cell-by-cell by GlassEffectPlanTest. Since
 * the glass blueprint this is a delegate: a bare tier resolves
 * the identity OF THE SAME NAME against that tier, which is
 * exactly what the pre-blueprint tier-keyed plans meant.
 * FROSTED's fill (0.72) is today's shipped
 * [GlassSettings.glassSurface] alpha — the frosted identity must
 * not drift from the behaviour the contrast gate pinned.
 */
fun glassEffectPlan(tier: GlassTier): GlassEffectPlan =
    glassEffectPlanFor(GlassStyle.valueOf(tier.name), tier)

/**
 * The glass blueprint resolver: IDENTITY follows the chosen
 * [style]; CAPABILITY (backdrop blur, refraction) follows the
 * resolved [tier]. A LIQUID choice on a device that resolves to
 * the FROSTED tier renders the Liquid identity with frosted
 * capability (blur, no shader, the live 0.62 fill); on the
 * TINTED tier it renders the Liquid identity STATICALLY (raised
 * 0.68 fill, no blur, no shader). A FROSTED choice on the TINTED
 * tier renders the Frosted identity statically (0.78 fill). The
 * styles never erase each other again.
 *
 * [forceStatic] is the power-save leg: the surface passes it
 * when the device is in power-save mode, and every identity
 * degrades to its static variant (SOLID and TINTED are static
 * by construction and return their ordinary cells).
 */
fun glassEffectPlanFor(
    style: GlassStyle,
    tier: GlassTier,
    forceStatic: Boolean = false
): GlassEffectPlan = when (style) {
    // SOLID: the shipped cell, exactly — no blueprint field set.
    GlassStyle.SOLID -> GlassEffectPlan(
        usesBackdropBlur = false,
        usesRefraction = false,
        rimAlpha = 0.12f,
        sheenAlpha = 0.0f,
        fillAlpha = 1.0f,
        rimWidthDp = 2f
    )
    // TINTED: pure chroma (Kimi) — the fill IS a theme-primary
    // gradient (0.20 → 0.10, multiplied by the user's opacity
    // token at composition) over a see-through background base
    // at the fill alpha (v1.7 Patch 1, item 3d — the base used
    // to be opaque, so the plain-tier dock read as a solid
    // slab); the hue is the surface. Rim is primary too (0.55
    // top, 0.25 bottom). No blur, no shader, ever — static by
    // construction.
    GlassStyle.TINTED -> GlassEffectPlan(
        usesBackdropBlur = false,
        usesRefraction = false,
        rimAlpha = 0.55f,
        sheenAlpha = 0.05f,
        fillAlpha = null, // the user's opacity token decides — never a constant
        rimWidthDp = 3f,
        chromaTopAlpha = 0.20f,
        chromaBottomAlpha = 0.10f,
        rimChroma = true,
        rimBottomAlpha = 0.25f,
        staticVariant = true
    )
    // FROSTED: white haze + bright rim. Base fill 0.72 live (the
    // contrast-pinned value — do not drift), 0.78 static. Rim
    // 0.40 on the shared 3.dp stroke (1.5.dp visible, the same
    // geometry as LIQUID), white support rim 0.10 underneath,
    // diagonal sheen from the top-left.
    GlassStyle.FROSTED -> {
        val live = !forceStatic &&
            (tier == GlassTier.FROSTED || tier == GlassTier.LIQUID)
        GlassEffectPlan(
            usesBackdropBlur = live,
            usesRefraction = false,
            rimAlpha = 0.40f,
            sheenAlpha = 0.08f,
            fillAlpha = if (live) 0.72f else 0.78f,
            rimWidthDp = 3f,
            hazeTopAlpha = 0.08f,
            supportRimAlpha = 0.10f,
            supportRimChroma = false,
            supportRimWidthDp = 5f,
            diagonalSheen = true,
            staticVariant = !live
        )
    }
    // LIQUID: chroma core + layered rim. Live (LIQUID tier):
    // blur + refraction, fill 0.62. FROSTED tier: the same
    // identity with frosted capability — blur, no shader, fill
    // stays 0.62. Below that (or power-save): the static
    // variant — fill 0.68, no blur, no shader. Main rim white
    // 0.65 on the 3.dp stroke; support rim primary 0.30 on a
    // 5.dp stroke drawn first, so ~1.dp of violet peeks inside
    // the white rim's inner edge. Sheen 0.22, diagonal.
    GlassStyle.LIQUID -> {
        val liveTier = !forceStatic && tier == GlassTier.LIQUID
        val blurTier = !forceStatic &&
            (tier == GlassTier.LIQUID || tier == GlassTier.FROSTED)
        GlassEffectPlan(
            usesBackdropBlur = blurTier,
            usesRefraction = liveTier,
            rimAlpha = 0.65f,
            sheenAlpha = 0.22f,
            fillAlpha = if (blurTier) 0.62f else 0.68f,
            rimWidthDp = 3f,
            chromaTopAlpha = 0.15f,
            chromaBottomAlpha = 0.04f,
            supportRimAlpha = 0.30f,
            supportRimChroma = true,
            supportRimWidthDp = 5f,
            diagonalSheen = true,
            staticVariant = !blurTier
        )
    }
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
    // Glass blueprint: a 1.08x saturation step in the bent band
    // (mix each channel's colour AWAY from its luminance by
    // -0.08) — the sampled colours read richer at the rim
    // instead of greying out. Interior samples (band <= 0)
    // return above, untouched.
    float lum = dot(c.rgb, half3(0.2126, 0.7152, 0.0722));
    c.rgb = mix(half3(lum), c.rgb, 1.08);
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
 * part of the tree), then the identity overlay (chroma, haze,
 * dither, sheen, rims), then content.
 *
 * Glass blueprint (2026-11-15): [style] is the user's chosen
 * identity and [tier] the device's capability — see
 * [glassEffectPlanFor]. [sampleBackdrop] = false composes NO
 * hazeEffect node at all (cards over the static canvas: blur
 * there is a paid no-op). [fillAlphaOverride] replaces the
 * plan's fill alpha for this one surface (the dock's LIQUID
 * 0.48). Power-save mode forces the static variant.
 */
@Composable
fun LiquidGlassSurface(
    shape: Shape,
    modifier: Modifier = Modifier,
    tier: GlassTier,
    style: GlassStyle? = null,
    sampleBackdrop: Boolean = true,
    fillAlphaOverride: Float? = null,
    content: @Composable () -> Unit
) {
    val glass = LocalGlassSettings.current
    val haze = LocalHazeState.current
    val scheme = MaterialTheme.colorScheme
    // Glass blueprint: identity follows the chosen STYLE,
    // capability follows the resolved TIER. A null style (legacy
    // call sites) means the identity OF THE TIER'S OWN NAME —
    // exactly the pre-blueprint behaviour.
    val identityStyle = style ?: GlassStyle.valueOf(tier.name)
    // Power-save gating: read once per composition of the
    // surface (no flow, no prefs); in power-save mode blur and
    // refraction are treated as off and the identity renders
    // its static variant.
    val context = LocalContext.current
    val powerSave = remember(context) {
        runCatching {
            (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)
                ?.isPowerSaveMode == true
        }.getOrDefault(false)
    }
    val plan = glassEffectPlanFor(identityStyle, tier, forceStatic = powerSave)

    // TINTED's fill is the user's opacity token; every other plan
    // takes its pinned alpha; a caller's override (the dock's
    // LIQUID 0.48) wins over both. This is also the null-haze
    // fallback alpha — a LIQUID/FROSTED surface with no haze source
    // above it renders exactly this fill, at exactly this alpha.
    val fillAlpha = fillAlphaOverride ?: plan.fillAlpha ?: glass.opacity
    val fillColor = scheme.surface.copy(alpha = fillAlpha)
    // Blur-off-over-black: when the caller says the backdrop is
    // not worth sampling (cards over the shell's static canvas),
    // NO hazeEffect node is composed at all, whatever the plan
    // says — blurring a uniform field is a paid no-op.
    val blurActive = plan.usesBackdropBlur && sampleBackdrop && haze != null

    // The grain dither tile (glass blueprint, conditional): only
    // static-variant plans draw it, and only when the user's
    // film-grain setting exists and is ON (GlassSettings.grainOn,
    // from AppearanceState.grain). One pre-baked 128x128 tile,
    // seeded, generated once and remembered — the same fixed-seed
    // construction as the shell's GrainOverlay.
    val ditherTile = remember(plan.staticVariant, glass.grainOn) {
        if (plan.staticVariant && glass.grainOn) {
            val size = 128
            val bitmap = Bitmap.createBitmap(
                size, size, Bitmap.Config.ARGB_8888
            )
            val pixels = IntArray(size * size)
            val random = kotlin.random.Random(20261115)
            for (i in pixels.indices) {
                val v = random.nextInt(256)
                pixels[i] = android.graphics.Color.rgb(v, v, v)
            }
            bitmap.setPixels(pixels, 0, size, 0, 0, size, size)
            bitmap.asImageBitmap()
        } else {
            null
        }
    }

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
            // No blur on this plan (TINTED/SOLID), a static variant,
            // backdrop sampling off (cards), or no haze source in
            // this part of the tree: the plain fill. TINTED's base
            // is the scheme background AT THE FILL ALPHA — a
            // see-through tint, never an opaque slab: the
            // pre-Patch-1 base was the opaque scheme background,
            // so on the plain tier the dock read as a solid bar
            // with nothing showing through (v1.7 Patch 1,
            // item 3d). The identity's chroma gradient rides
            // over it in the overlay layer below (deterministic
            // on black; no neutral surface colour in the fill
            // at all). Every other identity takes the plan's
            // fill at its pinned alpha (or the user's token /
            // the caller's override).
            backdrop = if (identityStyle == GlassStyle.TINTED) {
                backdrop.background(scheme.background.copy(alpha = fillAlpha))
            } else {
                backdrop.background(fillColor)
            }
        }
        Box(backdrop)

        // ── Identity overlay: chroma, haze, dither, sheen, rims ──
        // One cached draw, back to front: the chroma gradient
        // (the identity's hue, over the base fill), FROSTED's
        // baked-in white haze, the grain dither (static variants,
        // grain setting on only), the sheen, the SUPPORT rim
        // (drawn first, wider, so its colour peeks inside the
        // main rim's inner edge), and the main rim — a gradient
        // stroke following the shape outline, brightest at the
        // top. Rim strokes are drawn plan.rimWidthDp wide centred
        // on the outline; the container's clip removes the outer
        // half, leaving half the width inside the bounds (1.5.dp
        // on the blueprint's 3.dp strokes — ChatGPT's correction:
        // a rim that reads ~1.5.dp looks optical, not painted).
        // A plan with every identity field at its default (SOLID)
        // draws exactly the pre-blueprint rim and nothing else.
        Box(
            Modifier
                .fillMaxSize()
                .drawWithCache {
                    val outline = shape.createOutline(size, layoutDirection, this)
                    val primary = scheme.primary
                    // TINTED's chroma stops are multiplied by the
                    // user's opacity token (the slider keeps its
                    // meaning); every other identity's chroma is
                    // a fixed part of its fill construction.
                    val chromaScale =
                        if (identityStyle == GlassStyle.TINTED) glass.opacity else 1f
                    val chromaBrush = if (plan.chromaTopAlpha > 0.0f) {
                        Brush.verticalGradient(
                            listOf(
                                primary.copy(alpha = plan.chromaTopAlpha * chromaScale),
                                primary.copy(alpha = plan.chromaBottomAlpha * chromaScale)
                            )
                        )
                    } else {
                        null
                    }
                    val hazeBrush = if (plan.hazeTopAlpha > 0.0f) {
                        Brush.verticalGradient(
                            0.0f to Color.White.copy(alpha = plan.hazeTopAlpha),
                            0.45f to Color.White.copy(alpha = 0.0f)
                        )
                    } else {
                        null
                    }
                    val rimBrush = if (plan.rimChroma) {
                        Brush.verticalGradient(
                            listOf(
                                primary.copy(alpha = plan.rimAlpha),
                                primary.copy(alpha = plan.rimBottomAlpha)
                            )
                        )
                    } else {
                        Brush.verticalGradient(
                            listOf(
                                Color.White.copy(alpha = plan.rimAlpha),
                                Color.White.copy(alpha = plan.rimAlpha * 0.28f),
                                Color.White.copy(alpha = plan.rimAlpha * 0.5f)
                            )
                        )
                    }
                    val sheenBrush = if (plan.diagonalSheen) {
                        // One consistent light direction: the
                        // top-left corner (DeepSeek #2 adopt).
                        Brush.linearGradient(
                            0.0f to Color.White.copy(alpha = plan.sheenAlpha),
                            0.45f to Color.White.copy(alpha = 0.0f),
                            start = Offset.Zero,
                            end = Offset(size.width, size.height)
                        )
                    } else {
                        Brush.verticalGradient(
                            0.0f to Color.White.copy(alpha = plan.sheenAlpha),
                            0.45f to Color.White.copy(alpha = 0.0f)
                        )
                    }
                    onDrawWithContent {
                        drawContent()
                        if (chromaBrush != null) {
                            drawOutline(outline, brush = chromaBrush)
                        }
                        if (hazeBrush != null) {
                            drawOutline(outline, brush = hazeBrush)
                        }
                        if (ditherTile != null) {
                            var x = 0f
                            while (x < size.width) {
                                var y = 0f
                                while (y < size.height) {
                                    drawImage(
                                        ditherTile,
                                        topLeft = Offset(x, y),
                                        alpha = 0.03f
                                    )
                                    y += 128f
                                }
                                x += 128f
                            }
                        }
                        if (plan.sheenAlpha > 0.0f) {
                            drawOutline(outline, brush = sheenBrush)
                        }
                        if (plan.supportRimAlpha > 0.0f) {
                            drawOutline(
                                outline,
                                color = if (plan.supportRimChroma) primary else Color.White,
                                alpha = plan.supportRimAlpha,
                                style = Stroke(width = plan.supportRimWidthDp.dp.toPx())
                            )
                        }
                        drawOutline(
                            outline,
                            brush = rimBrush,
                            style = Stroke(width = plan.rimWidthDp.dp.toPx())
                        )
                    }
                }
        )

        // ── Content: above everything, touched by nothing ─────────
        content()
    }
}
