package app.xreader.data.parse

import android.app.Application
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * v1.5.8 (F4 design iii): the SAME strip pass that produces the
 * per-page texts also records each page's TEXT BOUNDS (the union
 * of its TextPositions) — H4's margin crop reads this record.
 * Fixture: page 1 carries a text block drawn at a known x; page
 * 2 is blank. Bounds must match the block's placement within
 * tolerance, the blank page must persist null, and the texts
 * must be identical to the plain strip's (one pass, both
 * products).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class V158PdfStripBoundsTest {

    @Before fun initPdfBoxResources() {
        PDFBoxResourceLoader.init(RuntimeEnvironment.getApplication())
    }

    private fun fixtureDocument(): PDDocument {
        val document = PDDocument()
        // Page 1: two lines starting at x=200pt.
        val page1 = PDPage()
        document.addPage(page1)
        PDPageContentStream(document, page1).use { stream ->
            stream.beginText()
            stream.setFont(PDType1Font.HELVETICA, 12f)
            stream.newLineAtOffset(200f, 700f)
            stream.showText("The sentinel kept watch over the harbour.")
            stream.newLineAtOffset(0f, -20f)
            stream.showText("The ferryman crossed the cold river.")
            stream.endText()
        }
        // Page 2: no text at all.
        document.addPage(PDPage())
        return document
    }

    @Test
    fun `one strip pass produces texts and matching bounds`() {
        fixtureDocument().use { document ->
            val result = PdfStripperLoop.stripWithBounds(document)
            assertEquals(2, result.texts.size)
            assertEquals(2, result.bounds.size)
            assertTrue(result.texts[0].contains("sentinel kept watch"))
            assertTrue(result.texts[1].isBlank())

            val bounds = result.bounds[0]
            assertNotNull("page 1 must carry text bounds", bounds)
            // The block starts at x=200pt (tolerance for glyph
            // origin vs. drawn offset) and spans two lines.
            assertTrue("left ${bounds!!.left}", bounds.left in 175f..225f)
            assertTrue("right ${bounds.right}", bounds.right > 300f)
            assertTrue("height ${bounds.height}", bounds.height in 15f..60f)
            assertTrue("within page", bounds.right <= 612f && bounds.bottom <= 792f)

            // The textless page persists null bounds.
            assertNull(result.bounds[1])

            // One pass, both products: the plain strip agrees.
            assertEquals(PdfStripperLoop.strip(document), result.texts)
        }
    }
}
