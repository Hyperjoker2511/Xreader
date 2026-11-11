package app.xreader.data.parse

import android.app.Application
import app.xreader.core.error.AppResult
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * v1.5.5 (§4, reality check d — the production-class leg):
 * [PdfBoxTextExtractor] itself — resource-loader init, load
 * classification, probe and the single-load extractAll —
 * driven under Robolectric against a synthetic commercial
 * PDF. Before v1.5.5 this class had never executed anywhere
 * but the owner's phone.
 *
 * KNOWN RISK (reported with the build): PDFBox-Android's
 * resource init reads font resources through the Android
 * asset system; if Robolectric's shadow assets can't serve
 * them, extractPages comes back Err and this class fails at
 * the init assertion — that failure is itself the harness
 * doing its job. The loop/assembly harness
 * (V155PdfRealHarnessTest, also Robolectric — a plain-JVM run
 * is proven impossible: PDType1Font's clinit needs the
 * asset-served AFM metrics) then fails the same way, and the
 * fallback proof is the device leg the WS-7 fixtures README
 * already prescribes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class V155PdfBoxRobolectricTest {

    @get:Rule val temp = TemporaryFolder()

    /** Production-shaped init, before ANY PDType1Font touch
     * (fixture building included): the standard-14 AFM
     * metrics load through the Android asset system, so the
     * resource loader must be initialised first — the same
     * order the app itself uses on device. */
    @Before fun initPdfBoxResources() {
        PDFBoxResourceLoader.init(RuntimeEnvironment.getApplication())
    }

    private fun buildPdf(): File {
        val file = File(temp.newFolder(), "robo.pdf")
        PDDocument().use { document ->
            fun addPage(lines: List<String>) {
                val page = PDPage()
                document.addPage(page)
                PDPageContentStream(document, page).use { stream ->
                    stream.beginText()
                    stream.setFont(PDType1Font.HELVETICA, 12f)
                    stream.newLineAtOffset(50f, 750f)
                    stream.setLeading(16f)
                    for (line in lines) {
                        stream.showText(line)
                        stream.newLine()
                    }
                    stream.endText()
                }
            }
            addPage(
                listOf(
                    "Chapter 1",
                    "The amber sentinel watched the harbour through the long evening.",
                    "And the story continued from there with enough plain prose " +
                        "to be unmistakably a chapter body, not a title page."
                )
            )
            addPage(
                listOf(
                    "Chapter 2",
                    "The ferryman crossed the cold river with his lantern lit.",
                    "More prose followed, steady and plain, the way a real " +
                        "chapter reads when its text extracts cleanly."
                )
            )
            document.save(file)
        }
        return file
    }

    @Test fun productionExtractorStripsAndProbes() {
        val context = RuntimeEnvironment.getApplication()
        val extractor = PdfBoxTextExtractor(context)
        val pdf = buildPdf()

        val pages = when (val result = extractor.extractPages(pdf)) {
            is AppResult.Ok -> result.value
            is AppResult.Err -> throw AssertionError(
                "production extractPages failed under Robolectric: ${result.error}"
            )
        }
        assertEquals(2, pages.size)
        assertTrue(pages[0].contains("amber sentinel"))
        assertTrue(pages[1].contains("cold river"))

        // The probe: page count from ONE metadata load, no strip.
        val probe = when (val result = extractor.probe(pdf)) {
            is AppResult.Ok -> result.value
            is AppResult.Err -> throw AssertionError("probe failed: ${result.error}")
        }
        assertEquals(2, probe.pageCount)

        // The single-load completion read agrees with the
        // per-method legs.
        val all = extractor.extractAll(pdf)
        assertTrue("extractAll must produce the completion data", all != null)
        assertEquals(2, all!!.pages.size)
        assertTrue(all.pages[0].contains("amber sentinel"))
    }
}
