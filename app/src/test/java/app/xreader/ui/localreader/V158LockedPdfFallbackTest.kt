package app.xreader.ui.localreader

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import app.xreader.data.db.Book
import app.xreader.data.db.BookFormat
import app.xreader.data.db.SourceType
import app.xreader.data.engine.LocalPasswordRequiredException
import app.xreader.data.engine.PdfBoxPageProvider
import app.xreader.data.engine.PdfPageFilter
import app.xreader.data.engine.PdfPageProvider
import app.xreader.data.engine.PdfSession
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * v1.5.8 (H10) — the SHIPPED locked-PDF fallback, pinned.
 *
 * The spike (V158LockedPdfSpikeTest) proved PdfBox can render a
 * locked fixture at reading resolution within budget; this test
 * proves the production path uses it: [PdfSession.openInternal]
 * — the seam [PdfSession.open] delegates to — routes a locked
 * file to [PdfBoxPageProvider] when the platform cannot open
 * it, and ONLY then.
 *
 * The seam exists because the platform PdfRenderer cannot run
 * in this harness at all (its constructor throws
 * NoSuchMethodError under Robolectric — V156's contract note,
 * re-measured at the H10 gate), so the platform road is stood
 * in for by a factory: one that throws SecurityException (the
 * device behaviour for a locked file the platform cannot open)
 * or one that serves a fake provider (the device behaviour for
 * a file it can). The fallback factory is the PRODUCTION one in
 * every test — the PdfBox road is never faked.
 *
 * Fixtures are the spike's shape, generated the same way: 210
 * pages at 612×792 pt, a 1600×2200 JPEG plate on page 101, a
 * user password on the locked copy.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class V158LockedPdfFallbackTest {

    @get:Rule val temp = TemporaryFolder()

    @Before fun initPdfBox() {
        PDFBoxResourceLoader.init(RuntimeEnvironment.getApplication())
    }

    // ── Fixtures (the spike's generator) ─────────────────────

    private fun heavyJpegBytes(): ByteArray {
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

    private fun writeFixture(file: File, locked: Boolean) {
        val document = PDDocument()
        val font = PDType1Font.HELVETICA
        val plateBytes = heavyJpegBytes()
        for (index in 0 until PAGE_COUNT) {
            val page = PDPage(PDRectangle(612f, 792f))
            document.addPage(page)
            PDPageContentStream(document, page).use { stream ->
                if (index == PLATE_PAGE) {
                    val image = JPEGFactory.createFromStream(
                        document, ByteArrayInputStream(plateBytes)
                    )
                    stream.drawImage(image, 36f, 36f, 540f, 720f)
                }
                stream.beginText()
                stream.setFont(font, 12f)
                stream.newLineAtOffset(72f, 720f)
                stream.showText("Fixture page ${index + 1} of $PAGE_COUNT.")
                stream.newLineAtOffset(0f, -20f)
                stream.showText("The quick brown fox jumps over the lazy dog.")
                stream.endText()
            }
        }
        if (locked) {
            val policy = StandardProtectionPolicy(
                "owner-pw", USER_PASSWORD, AccessPermission()
            )
            policy.encryptionKeyLength = 128
            document.protect(policy)
        }
        document.save(file)
        document.close()
    }

    private fun fixture(locked: Boolean): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "v158-fallback-fixtures")
        dir.mkdirs()
        val file = File(dir, if (locked) "locked.pdf" else "unlocked.pdf")
        val cached = if (locked) lockedFixture else unlockedFixture
        if (cached != null && cached.isFile) return cached
        synchronized(FIXTURE_LOCK) {
            val again = if (locked) lockedFixture else unlockedFixture
            if (again != null && again.isFile) return again
            if (!file.isFile) writeFixture(file, locked)
            if (locked) lockedFixture = file else unlockedFixture = file
            return file
        }
    }

    // ── Staging ──────────────────────────────────────────────

    private data class Staged(val filesRoot: File, val cacheDir: File, val book: Book)

    private fun stage(fixtureFile: File, id: Long): Staged {
        val filesRoot = temp.newFolder("files-$id")
        val cacheDir = temp.newFolder("cache-$id")
        fixtureFile.copyTo(File(filesRoot, "book.pdf"))
        return Staged(
            filesRoot = filesRoot,
            cacheDir = cacheDir,
            book = Book(
                id = id,
                title = "Fallback Fixture",
                sourceType = SourceType.LOCAL_FILE,
                localFormat = BookFormat.PDF,
                filePath = "book.pdf",
                addedAt = 0,
                updatedAt = 0,
                fileFingerprint = "fallback-fixture-$id"
            )
        )
    }

    /** A platform provider that serves pages itself — the stand-
     * in for a device whose PdfRenderer CAN open the file. */
    private class FakePlatformProvider(override val pageCount: Int) : PdfPageProvider {
        var renderCalls = 0
        var closed = false
        override fun renderRaw(page: Int, targetWidthPx: Int): Bitmap {
            renderCalls++
            val height = (targetWidthPx.toFloat() * 792 / 612).toInt().coerceAtLeast(1)
            return Bitmap.createBitmap(targetWidthPx, height, Bitmap.Config.ARGB_8888)
        }
        override fun pagePointsSize(page: Int): Pair<Int, Int> = 612 to 792
        override fun close() {
            closed = true
        }
    }

    private fun productionFallback() = { c: android.content.Context, b: Book, r: File, pw: String ->
        PdfBoxPageProvider.create(c, b, r, pw)
    }

    // ── The fallback ships ───────────────────────────────────

    @Test
    fun `locked fixture opens through the fallback with the correct password and renders at reading resolution`() =
        runBlocking {
            val staged = stage(fixture(locked = true), id = 101)
            var platformCalls = 0
            val session = PdfSession.openInternal(
                context = RuntimeEnvironment.getApplication(),
                book = staged.book,
                filesRoot = staged.filesRoot,
                cacheDir = staged.cacheDir,
                password = USER_PASSWORD,
                // The owner phones' shape: below API 35 the
                // platform cannot honour a password at all.
                platformCanHonourPassword = false,
                platformFactory = { _, _, _, _ ->
                    platformCalls++
                    throw SecurityException("platform cannot open locked files")
                },
                fallbackFactory = productionFallback()
            )
            try {
                assertTrue(
                    "locked + accepted password below API 35 must use the fallback",
                    session.usesPdfBoxFallback
                )
                assertEquals(
                    "the platform is never asked a question it cannot answer",
                    0, platformCalls
                )
                assertEquals(PAGE_COUNT, session.sectionCount)

                // The heaviest page (the JPEG plate) at the
                // shipped ×2.0 oversample: 1080 px viewport × 2 =
                // 2160 px requested ≈ 254 dpi for a 612 pt page.
                val started = System.nanoTime()
                val bitmap = session.renderPage(
                    PLATE_PAGE, 1080, PdfPageFilter.NONE, oversample = 2f
                )
                val elapsedMs = (System.nanoTime() - started) / 1_000_000
                println(
                    "H10 FALLBACK: plate page render = ${elapsedMs} ms " +
                        "(${bitmap?.width}×${bitmap?.height})"
                )
                assertNotNull(bitmap)
                assertTrue("width ${bitmap!!.width}", bitmap.width in 2150..2170)
                assertTrue("height ${bitmap.height}", bitmap.height in 2780..2810)

                // The strip is WIRED for locked books: it runs
                // over the password-opened document (session
                // password, in memory only) and lands complete.
                val text = withTimeout(STRIP_TIMEOUT_MS) { session.textAt(0) }
                assertTrue(text.contains("Fixture page 1"))
                val strip = session.stripSnapshot()
                assertNotNull(strip)
                assertEquals(PAGE_COUNT, strip!!.texts.size)

                // Password discipline — the negative: after a
                // fallback open (strip persisted, page cached),
                // NO file in the app's stores contains the
                // password string.
                val stripFile = File(staged.cacheDir, "local-pdf/101/strip.json")
                assertTrue("strip persisted", stripFile.isFile)
                assertFalse(stripFile.readText().contains(USER_PASSWORD))
                val offenders = staged.cacheDir.walkTopDown()
                    .filter { it.isFile }
                    .filter { file ->
                        String(file.readBytes(), Charsets.ISO_8859_1)
                            .contains(USER_PASSWORD)
                    }
                    .toList()
                assertTrue("password leaked into $offenders", offenders.isEmpty())
            } finally {
                session.close()
            }
        }

    @Test
    fun `platform refusal on an API 35 shaped device also routes a locked file to the fallback`() =
        runBlocking {
            val staged = stage(fixture(locked = true), id = 105)
            var platformCalls = 0
            val session = PdfSession.openInternal(
                context = RuntimeEnvironment.getApplication(),
                book = staged.book,
                filesRoot = staged.filesRoot,
                cacheDir = staged.cacheDir,
                password = USER_PASSWORD,
                platformCanHonourPassword = true,
                platformFactory = { _, _, _, _ ->
                    platformCalls++
                    throw SecurityException("renderer refused the locked file")
                },
                fallbackFactory = productionFallback()
            )
            try {
                assertEquals("platform is tried first", 1, platformCalls)
                assertTrue(session.usesPdfBoxFallback)
                assertEquals(PAGE_COUNT, session.sectionCount)
                val bitmap = session.renderPage(0, 540, PdfPageFilter.NONE, 2f)
                assertNotNull(bitmap)
                assertTrue("width ${bitmap!!.width}", bitmap.width in 1070..1090)
            } finally {
                session.close()
            }
        }

    // ── Honest states ────────────────────────────────────────

    @Test
    fun `wrong password yields the honest password state, never a crash`() = runBlocking {
        val staged = stage(fixture(locked = true), id = 102)
        var fallbackCalls = 0
        val error = runCatching {
            PdfSession.openInternal(
                context = RuntimeEnvironment.getApplication(),
                book = staged.book,
                filesRoot = staged.filesRoot,
                cacheDir = staged.cacheDir,
                password = "definitely-wrong",
                platformCanHonourPassword = false,
                platformFactory = { _, _, _, _ ->
                    throw SecurityException("platform cannot open locked files")
                },
                fallbackFactory = { c, b, r, pw ->
                    fallbackCalls++
                    PdfBoxPageProvider.create(c, b, r, pw)
                }
            )
        }.exceptionOrNull()
        assertTrue(
            "expected LocalPasswordRequiredException, was $error",
            error is LocalPasswordRequiredException
        )
        assertEquals(
            "a disproven password never reaches the fallback",
            0, fallbackCalls
        )
    }

    @Test
    fun `no password yet yields the password state and never the fallback`() = runBlocking {
        val staged = stage(fixture(locked = true), id = 103)
        var fallbackCalls = 0
        val error = runCatching {
            PdfSession.openInternal(
                context = RuntimeEnvironment.getApplication(),
                book = staged.book,
                filesRoot = staged.filesRoot,
                cacheDir = staged.cacheDir,
                password = null,
                platformCanHonourPassword = false,
                platformFactory = { _, _, _, _ ->
                    throw SecurityException("locked")
                },
                fallbackFactory = { c, b, r, pw ->
                    fallbackCalls++
                    PdfBoxPageProvider.create(c, b, r, pw)
                }
            )
        }.exceptionOrNull()
        assertTrue(
            "expected LocalPasswordRequiredException, was $error",
            error is LocalPasswordRequiredException
        )
        assertEquals(0, fallbackCalls)
    }

    // ── The fence: unlocked files never engage the fallback ──

    @Test
    fun `unlocked fixture opens via the platform road and the fallback does not engage`() =
        runBlocking {
            val staged = stage(fixture(locked = false), id = 104)
            val fake = FakePlatformProvider(PAGE_COUNT)
            var fallbackCalls = 0
            val session = PdfSession.openInternal(
                context = RuntimeEnvironment.getApplication(),
                book = staged.book,
                filesRoot = staged.filesRoot,
                cacheDir = staged.cacheDir,
                password = null,
                platformCanHonourPassword = false,
                platformFactory = { _, _, _, _ -> fake },
                fallbackFactory = { c, b, r, pw ->
                    fallbackCalls++
                    PdfBoxPageProvider.create(c, b, r, pw)
                }
            )
            try {
                assertFalse(session.usesPdfBoxFallback)
                assertEquals(0, fallbackCalls)
                assertEquals(PAGE_COUNT, session.sectionCount)
                val bitmap = session.renderPage(3, 540, PdfPageFilter.NONE, 2f)
                assertNotNull(bitmap)
                assertEquals(1080, bitmap!!.width)
                assertEquals("the platform provider served the page", 1, fake.renderCalls)
            } finally {
                session.close()
            }
            assertTrue("close reaches the platform provider", fake.closed)
        }

    // NOTE on the production entry point: PdfSession.open is a
    // one-line delegation to openInternal with the real
    // factories and platformCanHonourPassword = (SDK >= 35), so
    // the routing pinned above IS production's routing. A
    // sandbox at sdk 28 driving open() itself was attempted and
    // dropped: Robolectric's sdk-28 android-all artifact is not
    // in this machine's cache and fetching it needs a network
    // approval that was not granted during the build — the
    // below-35 shape is instead pinned deterministically via
    // platformCanHonourPassword = false, which is exactly the
    // value open() computes on the owner phones.

    companion object {
        const val USER_PASSWORD = "fallback-open-sesame-158"
        const val PAGE_COUNT = 210
        const val PLATE_PAGE = 100
        const val STRIP_TIMEOUT_MS = 120_000L

        private val FIXTURE_LOCK = Any()
        @Volatile private var lockedFixture: File? = null
        @Volatile private var unlockedFixture: File? = null
    }
}
