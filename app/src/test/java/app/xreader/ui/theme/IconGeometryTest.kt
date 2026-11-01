package app.xreader.ui.theme

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.Inflater
import kotlin.math.abs
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Launcher icon, pinned (v1.4.0, the user's own artwork,
 * superseding the v1.2.0 "D" vector glyph): the same guarantees
 * the vector-era test pinned, re-expressed against the raster
 * layers generated from design/icon-concepts/
 * xreader-icon-next-update.png —
 *
 * - the two-colour spec (AMOLED black + one flat white);
 * - the glyph's pixels fit inside the 66 dp safe circle of the
 *   108 dp canvas, so no launcher mask can crop the X;
 * - the monochrome (themed-icon) layer is the same silhouette;
 * - every density carries every asset at the right size.
 *
 * PNGs are decoded by the small reader at the bottom of this
 * file: the unit-test classpath has no java.desktop (ImageIO)
 * or android.graphics (stubbed), so neither standard decoder
 * is available here. The reader covers exactly what the
 * generated assets use — 8-bit RGB/RGBA, no interlace, all
 * five row filters — and fails loudly on anything else.
 */
class IconGeometryTest {

    private fun resFile(path: String): File {
        val candidates = listOf(
            File("src/main/res/$path"),
            File("app/src/main/res/$path")
        )
        val file = candidates.firstOrNull { it.isFile }
        assertTrue("res file not found: $path", file != null)
        return file!!
    }

    private fun resText(path: String): String = resFile(path).readText()

    private fun resImage(path: String): Raster =
        Raster.decodePng(resFile(path))

    private fun glyphBounds(img: Raster, threshold: Int): IntArray {
        var minX = img.width
        var minY = img.height
        var maxX = -1
        var maxY = -1
        for (y in 0 until img.height) {
            for (x in 0 until img.width) {
                if (img.brightness(x, y) > threshold) {
                    if (x < minX) minX = x
                    if (y < minY) minY = y
                    if (x > maxX) maxX = x
                    if (y > maxY) maxY = y
                }
            }
        }
        check(maxX >= 0) { "no glyph pixels above $threshold" }
        return intArrayOf(minX, minY, maxX, maxY)
    }

    @Test
    fun `glyph is one flat white on pure black`() {
        val fg = resImage("mipmap-xxxhdpi/ic_launcher_foreground.png")
        var twoTone = 0
        var total = 0
        var whites = 0
        for (y in 0 until fg.height) {
            for (x in 0 until fg.width) {
                val (r, g, b) = fg.rgb(x, y)
                val nearBlack = maxOf(r, g, b) <= 24
                val nearWhite = minOf(r, g, b) >= 235 &&
                    (maxOf(r, g, b) - minOf(r, g, b)) <= 12
                if (nearBlack || nearWhite) twoTone++
                if (nearWhite) whites++
                total++
            }
        }
        // The artwork is a flat two-colour JPEG: apart from the
        // anti-aliased glyph edges, every pixel is near-black or
        // near-white, and the white is a substantial share.
        assertTrue(
            "only $twoTone of $total pixels are two-tone",
            twoTone >= (total * 0.97).toInt()
        )
        // Presence floor, calibrated to THIS artwork (gate,
        // v1.4.0): the mark is a HOLLOW outline X — its strokes
        // are ~4% of the canvas in near-white pixels, where the
        // old solid glyph was far denser. 1/40 still proves a
        // real white glyph (thousands of pixels at xxxhdpi);
        // the bounds assertions pin its size separately.
        assertTrue("glyph share too small: $whites", whites > total / 40)
        // Background colour resource stays pure black.
        assertTrue(
            resText("values/ic_launcher_colors.xml")
                .contains("#000000")
        )
    }

    @Test
    fun `every glyph pixel fits inside the safe circle`() {
        val fg = resImage("mipmap-xxxhdpi/ic_launcher_foreground.png")
        assertEquals(432, fg.width)
        assertEquals(432, fg.height)
        val centre = fg.width / 2.0
        val safeRadius = 33.0 / 108.0 * fg.width // 66 dp of 108 dp
        var worst = 0.0
        for (y in 0 until fg.height) {
            for (x in 0 until fg.width) {
                if (fg.brightness(x, y) > 24) {
                    val d = hypot(x - centre, y - centre)
                    if (d > worst) worst = d
                }
            }
        }
        assertTrue(
            "glyph reaches $worst px from centre — outside the " +
                "$safeRadius px safe circle",
            worst <= safeRadius + 2.0 // resize anti-alias tolerance
        )
        // And the glyph is substantial. Calibrated to this
        // artwork at the gate (v1.4.0): the hollow X spans ~62%
        // of its own frame, and the frame sits at 66/108 of the
        // canvas, so the glyph's bright bounds measure ~0.38 of
        // the canvas — the old solid glyph's 0.45..0.65 range
        // described a different mark.
        val bounds = glyphBounds(fg, 24)
        val widthShare = (bounds[2] - bounds[0] + 1).toDouble() / fg.width
        assertTrue(
            "glyph width share $widthShare",
            widthShare in 0.33..0.45
        )
    }

    @Test
    fun `monochrome is the same silhouette`() {
        val fg = resImage("mipmap-xxxhdpi/ic_launcher_foreground.png")
        val mono = resImage("mipmap-xxxhdpi/ic_launcher_monochrome.png")
        assertEquals(fg.width, mono.width)
        assertEquals(fg.height, mono.height)
        // Monochrome carries the glyph as alpha over transparency:
        // its opaque bounds must match the foreground's bright
        // bounds exactly (both derive from the same artwork).
        var minX = mono.width
        var minY = mono.height
        var maxX = -1
        var maxY = -1
        for (y in 0 until mono.height) {
            for (x in 0 until mono.width) {
                if (mono.alpha(x, y) > 128) {
                    if (x < minX) minX = x
                    if (y < minY) minY = y
                    if (x > maxX) maxX = x
                    if (y > maxY) maxY = y
                }
            }
        }
        assertTrue(
            glyphBounds(fg, 128).contentEquals(
                intArrayOf(minX, minY, maxX, maxY)
            )
        )
        // The silhouette itself is pure white wherever it is
        // opaque (themed icons tint it themselves; the layer
        // must carry no colour of its own).
        for (y in 0 until mono.height) {
            for (x in 0 until mono.width) {
                if (mono.alpha(x, y) > 128) {
                    val (r, g, b) = mono.rgb(x, y)
                    assertTrue(
                        "monochrome pixel at ($x, $y) is not white",
                        r == 0xFF && g == 0xFF && b == 0xFF
                    )
                }
            }
        }
    }

    @Test
    fun `adaptive icon wires the raster layers`() {
        for (name in listOf("ic_launcher.xml", "ic_launcher_round.xml")) {
            val adaptive = resText("mipmap-anydpi-v26/$name")
            assertTrue(adaptive.contains("@drawable/ic_launcher_background"))
            assertTrue(adaptive.contains("@mipmap/ic_launcher_foreground"))
            assertTrue(adaptive.contains("@mipmap/ic_launcher_monochrome"))
        }
    }

    @Test
    fun `every density carries every asset at the right size`() {
        val densities = mapOf(
            "mdpi" to 1.0, "hdpi" to 1.5, "xhdpi" to 2.0,
            "xxhdpi" to 3.0, "xxxhdpi" to 4.0
        )
        for ((density, scale) in densities) {
            val legacy = (48 * scale).toInt()
            val adaptive = (108 * scale).toInt()
            val expectations = mapOf(
                "ic_launcher.png" to legacy,
                "ic_launcher_round.png" to legacy,
                "ic_launcher_foreground.png" to adaptive,
                "ic_launcher_monochrome.png" to adaptive
            )
            for ((asset, px) in expectations) {
                val img = resImage("mipmap-$density/$asset")
                assertEquals("$density/$asset width", px, img.width)
                assertEquals("$density/$asset height", px, img.height)
            }
        }
    }

    /** A decoded PNG as packed ARGB ints, row-major. */
    private class Raster(
        val width: Int,
        val height: Int,
        private val argb: IntArray
    ) {
        fun alpha(x: Int, y: Int): Int =
            (argb[y * width + x] ushr 24) and 0xFF

        fun rgb(x: Int, y: Int): Triple<Int, Int, Int> {
            val v = argb[y * width + x]
            return Triple(
                (v shr 16) and 0xFF, (v shr 8) and 0xFF, v and 0xFF
            )
        }

        fun brightness(x: Int, y: Int): Int {
            val (r, g, b) = rgb(x, y)
            return maxOf(r, g, b)
        }

        companion object {
            private fun int32(bytes: ByteArray, at: Int): Int =
                ((bytes[at].toInt() and 0xFF) shl 24) or
                    ((bytes[at + 1].toInt() and 0xFF) shl 16) or
                    ((bytes[at + 2].toInt() and 0xFF) shl 8) or
                    (bytes[at + 3].toInt() and 0xFF)

            private fun paeth(a: Int, b: Int, c: Int): Int {
                val p = a + b - c
                val pa = abs(p - a)
                val pb = abs(p - b)
                val pc = abs(p - c)
                return if (pa <= pb && pa <= pc) a
                else if (pb <= pc) b else c
            }

            fun decodePng(file: File): Raster {
                val bytes = file.readBytes()
                val signature = byteArrayOf(
                    -119, 80, 78, 71, 13, 10, 26, 10
                )
                check(
                    bytes.size > 8 &&
                        bytes.copyOfRange(0, 8).contentEquals(signature)
                ) { "not a PNG: ${file.name}" }
                var width = 0
                var height = 0
                var bitDepth = 0
                var colorType = -1
                var interlace = 0
                val idat = ByteArrayOutputStream()
                var pos = 8
                while (pos + 8 <= bytes.size) {
                    val length = int32(bytes, pos)
                    val type = String(
                        bytes, pos + 4, 4, Charsets.US_ASCII
                    )
                    when (type) {
                        "IHDR" -> {
                            width = int32(bytes, pos + 8)
                            height = int32(bytes, pos + 12)
                            bitDepth = bytes[pos + 16].toInt()
                            colorType = bytes[pos + 17].toInt()
                            interlace = bytes[pos + 20].toInt()
                        }
                        "IDAT" -> idat.write(bytes, pos + 8, length)
                    }
                    pos += 12 + length
                }
                require(bitDepth == 8 && interlace == 0 &&
                    (colorType == 2 || colorType == 6)) {
                    "unsupported PNG shape in ${file.name}: " +
                        "depth=$bitDepth type=$colorType " +
                        "interlace=$interlace"
                }
                val bpp = if (colorType == 2) 3 else 4
                val stride = width * bpp
                val raw = ByteArray(height * (stride + 1))
                val inflater = Inflater()
                inflater.setInput(idat.toByteArray())
                var filled = 0
                while (filled < raw.size) {
                    val n = inflater.inflate(raw, filled, raw.size - filled)
                    check(n > 0) { "truncated PNG data: ${file.name}" }
                    filled += n
                }
                inflater.end()
                // Undo the per-row filters.
                val pixels = ByteArray(height * stride)
                var src = 0
                for (y in 0 until height) {
                    val filter = raw[src++].toInt() and 0xFF
                    val rowOff = y * stride
                    for (x in 0 until stride) {
                        val v = raw[src++].toInt() and 0xFF
                        val left = if (x >= bpp) {
                            pixels[rowOff + x - bpp].toInt() and 0xFF
                        } else 0
                        val up = if (y > 0) {
                            pixels[rowOff - stride + x].toInt() and 0xFF
                        } else 0
                        val upLeft = if (x >= bpp && y > 0) {
                            pixels[rowOff - stride + x - bpp]
                                .toInt() and 0xFF
                        } else 0
                        val recon = when (filter) {
                            0 -> v
                            1 -> v + left
                            2 -> v + up
                            3 -> v + (left + up) / 2
                            4 -> v + paeth(left, up, upLeft)
                            else -> error(
                                "unknown PNG filter $filter"
                            )
                        }
                        pixels[rowOff + x] = (recon and 0xFF).toByte()
                    }
                }
                val argb = IntArray(width * height)
                for (i in argb.indices) {
                    val o = i * bpp
                    val r = pixels[o].toInt() and 0xFF
                    val g = pixels[o + 1].toInt() and 0xFF
                    val b = pixels[o + 2].toInt() and 0xFF
                    val a = if (bpp == 4) {
                        pixels[o + 3].toInt() and 0xFF
                    } else 0xFF
                    argb[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
                }
                return Raster(width, height, argb)
            }
        }
    }
}
