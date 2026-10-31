package app.xreader.ui.theme

import java.io.File
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Launcher icon, pinned (v1.2.0, user pick "D" 2026-10-31,
 * superseding the R09 open-book glyph): the two-colour spec
 * (AMOLED black + one flat white), the bold-X silhouette traced
 * from the approved art, and the geometry rule from the review —
 * the glyph's full bounding box, control points included (a
 * conservative superset of the curve), fits inside the 66 dp
 * safe circle of the 108 canvas.
 */
class IconGeometryTest {

    private fun resText(path: String): String {
        val candidates = listOf(
            File("src/main/res/$path"),
            File("app/src/main/res/$path")
        )
        val file = candidates.firstOrNull { it.isFile }
        assertTrue("res file not found: $path", file != null)
        return file!!.readText()
    }

    private fun pathData(xml: String): String =
        Regex("android:pathData=\"([^\"]+)\"").find(xml)?.groupValues?.get(1)
            ?: error("no pathData")

    private fun fillColor(xml: String): String =
        Regex("android:fillColor=\"([^\"]+)\"").find(xml)?.groupValues?.get(1)
            ?: error("no fillColor")

    private fun pointsOf(pathData: String): List<Pair<Double, Double>> {
        val numbers = Regex("-?\\d+\\.?\\d*").findAll(pathData)
            .map { it.value.toDouble() }.toList()
        return numbers.chunked(2).map { it[0] to it[1] }
    }

    @Test
    fun `glyph is one flat white on pure black`() {
        val foreground = resText("drawable/ic_launcher_foreground.xml")
        val background = resText("drawable/ic_launcher_background.xml")
        assertEquals("#FFFFFF", fillColor(foreground))
        assertEquals("#000000", fillColor(background))
        // No gradients or second colours anywhere in the layers.
        for (xml in listOf(foreground, background)) {
            assertFalse(xml.contains("gradient"))
            assertEquals(
                1,
                Regex("android:fillColor=").findAll(xml).count()
            )
        }
    }

    @Test
    fun `every glyph point fits inside the safe circle`() {
        val points = pointsOf(pathData(resText("drawable/ic_launcher_foreground.xml")))
        assertTrue(points.isNotEmpty())
        for ((x, y) in points) {
            val distance = hypot(x - 54.0, y - 54.0)
            assertTrue(
                "point ($x, $y) is $distance from centre — outside the safe circle",
                distance <= 33.0
            )
        }
        // And the glyph is substantial: ~half the canvas wide.
        val xs = points.map { it.first }
        assertTrue(xs.max() - xs.min() in 45.0..60.0)
    }

    @Test
    fun `monochrome is the same silhouette`() {
        val foreground = pathData(resText("drawable/ic_launcher_foreground.xml"))
        val monochrome = pathData(resText("drawable/ic_launcher_monochrome.xml"))
        assertEquals(foreground, monochrome)
    }

    @Test
    fun `adaptive icon wires the vector layers`() {
        val adaptive = resText("mipmap-anydpi-v26/ic_launcher.xml")
        assertTrue(adaptive.contains("@drawable/ic_launcher_background"))
        assertTrue(adaptive.contains("@drawable/ic_launcher_foreground"))
        assertTrue(adaptive.contains("@drawable/ic_launcher_monochrome"))
    }
}
