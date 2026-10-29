package app.xreader.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.abs

/**
 * Cover-colour extraction (v1.0.2, R05) with the fork-proven cost
 * shape: vibrant → muted → dark-vibrant targets, computed OFF the UI
 * thread from a downsampled pixel set, memoised in a bounded LRU.
 *
 * The maths runs on raw ARGB ints (no Bitmap, no AndroidX Palette)
 * so it is fully unit-testable, and the cache is rebuildable — it is
 * never backed up (R02).
 *
 * Scoring follows the classic Palette approach: quantise colours
 * into bins, then score each target by population × saturation fit
 * (vibrant wants saturated + mid-light; muted wants soft; dark
 * vibrant wants saturated + dark). Near-white/near-black pixels are
 * excluded from the vibrant/muted races so a mostly-white cover's
 * border doesn't become the "vibrant" colour.
 */
data class CoverPalette(
    val vibrant: Color?,
    val muted: Color?,
    val darkVibrant: Color?,
    val dominant: Color?
) {
    /** The colour the book-page ambience leads with: vibrant first,
     * then dominant, then muted — null only for an empty palette. */
    val ambient: Color? get() = vibrant ?: dominant ?: muted

    companion object {
        val EMPTY = CoverPalette(null, null, null, null)
    }
}

object CoverPaletteExtractor {

    /** Pixels beyond this are stride-sampled before quantising. */
    private const val MAX_SAMPLES = 16_384

    private data class Bin(val r: Int, val g: Int, val b: Int, var count: Int)

    fun extract(argbPixels: IntArray): CoverPalette {
        if (argbPixels.isEmpty()) return CoverPalette.EMPTY
        val stride = if (argbPixels.size > MAX_SAMPLES) {
            argbPixels.size / MAX_SAMPLES + 1
        } else 1
        val bins = HashMap<Int, Bin>()
        var i = 0
        var total = 0
        while (i < argbPixels.size) {
            val px = argbPixels[i]
            i += stride
            val a = (px ushr 24) and 0xFF
            if (a < 128) continue
            val r = (px ushr 16) and 0xFF
            val g = (px ushr 8) and 0xFF
            val b = px and 0xFF
            val key = ((r shr 3) shl 10) or ((g shr 3) shl 5) or (b shr 3)
            val bin = bins.getOrPut(key) { Bin(r, g, b, 0) }
            bin.count++
            total++
        }
        if (total == 0 || bins.isEmpty()) return CoverPalette.EMPTY

        var dominant: Bin? = null
        var vibrant: Bin? = null
        var vibrantScore = -1f
        var muted: Bin? = null
        var mutedScore = -1f
        var darkVibrant: Bin? = null
        var darkScore = -1f
        for (bin in bins.values) {
            if (dominant == null || bin.count > dominant.count) dominant = bin
            val (_, sat, light) = hslOf(bin.r, bin.g, bin.b)
            val pop = bin.count.toFloat() / total
            // Vibrant: saturated, mid-light, not near the extremes.
            if (light in 0.15f..0.85f) {
                val score = pop * (1f - abs(sat - 1.0f)) * (1f - abs(light - 0.5f))
                if (sat >= 0.35f && score > vibrantScore) {
                    vibrantScore = score; vibrant = bin
                }
                // Muted: soft colour, mid-light.
                val mScore = pop * (1f - abs(sat - 0.3f)) * (1f - abs(light - 0.5f))
                if (score > -1f && mScore > mutedScore) {
                    mutedScore = mScore; muted = bin
                }
            }
            // Dark vibrant: saturated and dark.
            if (light in 0.05f..0.45f && sat >= 0.3f) {
                val dScore = pop * sat * (1f - abs(light - 0.25f))
                if (dScore > darkScore) {
                    darkScore = dScore; darkVibrant = bin
                }
            }
        }
        fun Bin?.colour() = this?.let { Color(it.r, it.g, it.b) }
        return CoverPalette(
            vibrant = vibrant.colour(),
            muted = muted.colour(),
            darkVibrant = darkVibrant.colour(),
            dominant = dominant.colour()
        )
    }

    /**
     * The designed placeholder's palette (R05/R19): a book with no
     * cover gets a deterministic colour family from its title — the
     * app never pretends this was extracted from artwork.
     *
     * v1.0.6: the family is ONE family — every placeholder hue sits
     * inside the violet / bluish-purple band (232°–288°, around the
     * app's own #7B7BE0–#8B93F8 violets) instead of anywhere on the
     * wheel. A shelf of coverless books used to clash (green, rust,
     * maroon, magenta side by side — the user's "it looks ugly"
     * verdict); now neighbouring tiles differ by hue step and a
     * small lightness step, never by family. This function feeds
     * ONLY the designed placeholder (tiles + the grid glow) — real
     * covers still go through [extract] untouched.
     */
    fun placeholderPalette(title: String): CoverPalette {
        var hash = 0
        for (c in title.trim().lowercase()) hash = (hash * 31 + c.code) and 0x7FFFFFFF
        val hue = (PLACEHOLDER_HUE_MIN + hash % PLACEHOLDER_HUE_SPAN).toFloat()
        // A second hash slice steps the deep shades ±0.03 so books
        // sharing a hue still read as different tiles.
        val lift = ((hash / PLACEHOLDER_HUE_SPAN) % 3 - 1) * 0.03f
        return CoverPalette(
            vibrant = CustomSchemeGenerator.hsl(hue, 0.6f, 0.5f),
            muted = CustomSchemeGenerator.hsl(hue, 0.35f, 0.55f),
            darkVibrant = CustomSchemeGenerator.hsl(hue, 0.62f, 0.27f + lift),
            dominant = CustomSchemeGenerator.hsl(hue, 0.55f, 0.36f + lift)
        )
    }

    /** The placeholder family's band: violet through bluish-purple. */
    const val PLACEHOLDER_HUE_MIN = 232
    const val PLACEHOLDER_HUE_SPAN = 57

    private fun hslOf(r: Int, g: Int, b: Int): Triple<Float, Float, Float> {
        val rf = r / 255f; val gf = g / 255f; val bf = b / 255f
        val max = maxOf(rf, gf, bf); val min = minOf(rf, gf, bf)
        val l = (max + min) / 2f
        if (abs(max - min) < 0.0001f) return Triple(0f, 0f, l)
        val d = max - min
        val s = if (l > 0.5f) d / (2f - max - min) else d / (max + min)
        return Triple(0f, s, l)
    }
}

/**
 * Bounded LRU for cover palettes (R05 budgets come from
 * PerformancePolicy). Access-order; eviction is silent and the cache
 * is rebuildable by design.
 */
class CoverPaletteCache(capacity: Int) {
    private var maxSize = capacity.coerceAtLeast(1)
    private val map = object : LinkedHashMap<String, CoverPalette>(16, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, CoverPalette>?
        ): Boolean = size > maxSize
    }

    @Synchronized fun get(key: String): CoverPalette? = map[key]

    @Synchronized fun put(key: String, palette: CoverPalette) {
        map[key] = palette
    }

    @Synchronized fun size(): Int = map.size

    /** Resize when the performance tier changes (growing keeps
     * entries; shrinking evicts the least-recently-used at once). */
    @Synchronized fun resize(newCapacity: Int) {
        maxSize = newCapacity.coerceAtLeast(1)
        val entries = map.entries.toList()
        map.clear()
        entries.takeLast(maxSize).forEach { map[it.key] = it.value }
    }
}
