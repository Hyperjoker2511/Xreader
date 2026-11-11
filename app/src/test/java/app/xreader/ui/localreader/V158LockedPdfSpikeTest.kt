package app.xreader.ui.localreader

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.rendering.ImageType
import com.tom_roush.pdfbox.rendering.PDFRenderer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * v1.5.8 (H10) SPIKE — the three-part proof bar for shipping
 * a PdfBox fallback for locked PDFs, measured on THIS BUILD
 * MACHINE (the Robolectric JVM stands in for it — the same
 * environment the plan's machine-side spike runs in):
 *
 *  (i)   a generated password-protected fixture PDF
 *        (publisher-shaped: 200+ pages, an image plate) renders
 *        pages at reading quality via PdfBox;
 *  (ii)  the fixture's heaviest page at reading resolution
 *        (the shipped ×2.0 oversample = 2160 px wide for a
 *        612 pt page ≈ 254 dpi) renders in ≤ 2,000 ms — an
 *        ABSOLUTE budget; this test PRINTS the actual number;
 *  (iii) a wrong password yields the honest failure
 *        (InvalidPasswordException), never a crash.
 *
 * All three must hold for the fallback to ship; if any fails,
 * the failure IS the verdict and the honest state ships
 * unchanged.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class V158LockedPdfSpikeTest {

    @get:Rule val temp = TemporaryFolder()

    private val userPassword = "spike-user-password"
    private val pageCount = 210
    private val platePage = 100

    private fun heavyJpegBytes(): ByteArray {
        // A dense colour plate, 1600×2200, deterministic pattern
        // (blocks + diagonals) so JPEG size is realistic.
        val bitmap = Bitmap.createBitmap(1600, 2200, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint()
        for (y in 0 until 22) {
            for (x in 0 until 16) {
                paint.color = Color.rgb(
                    (x * 37 + y * 11) % 256,
                    (x * 13 + y * 53) % 256,
                    (x * 71 + y * 7) % 256
                )
                canvas.drawRect(
                    x * 100f, y * 100f, x * 100f + 100f, y * 100f + 100f, paint
                )
            }
        }
        paint.color = Color.WHITE
        paint.strokeWidth = 6f
        for (i in 0 until 40) {
            canvas.drawLine(0f, i * 55f, 1600f, 2200f - i * 40f, paint)
        }
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    private fun writeFixture(): File {
        val file = File(temp.root, "locked-fixture.pdf")
        val document = PDDocument()
        val font = PDType1Font.HELVETICA
        val plateBytes = heavyJpegBytes()
        for (index in 0 until pageCount) {
            val page = PDPage(PDRectangle(612f, 792f))
            document.addPage(page)
            PDPageContentStream(document, page).use { stream ->
                if (index == platePage) {
                    val image = JPEGFactory.createFromStream(
                        document, ByteArrayInputStream(plateBytes)
                    )
                    stream.drawImage(image, 36f, 36f, 540f, 720f)
                }
                stream.beginText()
                stream.setFont(font, 12f)
                stream.newLineAtOffset(72f, 720f)
                stream.showText("Fixture page ${index + 1} of $pageCount.")
                stream.newLineAtOffset(0f, -20f)
                stream.showText("The quick brown fox jumps over the lazy dog.")
                stream.endText()
            }
        }
        val permission = AccessPermission()
        val policy = StandardProtectionPolicy("owner-pw", userPassword, permission)
        policy.encryptionKeyLength = 128
        document.protect(policy)
        document.save(file)
        document.close()
        return file
    }

    @Test
    fun `spike - locked fixture renders at reading resolution within budget`() {
        PDFBoxResourceLoader.init(
            org.robolectric.RuntimeEnvironment.getApplication()
        )
        val file = writeFixture()
        assertTrue("fixture is substantial", file.length() > 100_000)

        // (iii) wrong password → honest failure, never a crash.
        try {
            PDDocument.load(file, "definitely-wrong")
            fail("wrong password must not open the document")
        } catch (expected: InvalidPasswordException) {
            // The honest state.
        }

        val document = PDDocument.load(file, userPassword)
        try {
            assertEquals(pageCount, document.numberOfPages)
            val renderer = PDFRenderer(document)
            // Warm-up on a light page (first-render font/setup
            // cost is not the page budget).
            renderer.renderImageWithDPI(0, 254f, ImageType.RGB)
            // (i) + (ii): the heaviest page — the image plate —
            // at 254 dpi (2160 px wide = the shipped ×2.0
            // oversample of a 1080 px viewport).
            val started = System.nanoTime()
            val rendered = renderer.renderImageWithDPI(platePage, 254f, ImageType.RGB)
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            println(
                "H10 SPIKE: heaviest page render = ${elapsedMs} ms " +
                    "(${rendered.width}×${rendered.height})"
            )
            // 612 pt × 254 dpi / 72 = 2159.0 exactly — the
            // rendered width at exactly 254 dpi for this
            // fixture's page size. (The constant briefly read
            // 2160 — a miscalculation in the spike's first
            // writing, corrected at the v1.5.8 gate with the
            // arithmetic recorded; the render was always at
            // the intended resolution, as the printed size
            // 2159×2794 = 612×792 pt at 254 dpi proves.)
            assertEquals(2159, rendered.width)
            assertTrue(
                "heaviest page must render in ≤ 2000 ms (was ${elapsedMs} ms)",
                elapsedMs <= 2_000
            )
        } finally {
            document.close()
        }
    }
}
