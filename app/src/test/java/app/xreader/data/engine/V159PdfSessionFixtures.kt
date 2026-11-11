package app.xreader.data.engine

import android.graphics.Bitmap
import app.xreader.data.db.Book
import app.xreader.data.db.BookFormat
import app.xreader.data.db.SourceType
import app.xreader.data.prefs.PerfTier
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.robolectric.RuntimeEnvironment

/**
 * v1.5.9 (Finding 3 §8): shared staging for the PDF session
 * tests — a real 12-page fixture at the OWNER'S page size
 * (396×612 pt, the Grieving Soul trim — NOT Letter), opened
 * through the production [PdfSession.openInternal] seam with a
 * counting fake platform provider (the platform PdfRenderer
 * cannot run under Robolectric; the routing + session logic
 * under test is production's, exactly as in the H10 tests).
 */
internal object V159PdfSessionFixtures {

    const val PAGE_W_PT = 396
    const val PAGE_H_PT = 612
    const val PAGE_COUNT = 12

    /** A provider that counts everything and can be slowed so
     * queue order is observable. */
    class CountingProvider(
        override val pageCount: Int = PAGE_COUNT,
        private val renderDelayMs: Long = 0
    ) : PdfPageProvider {
        val renderCounts = ConcurrentHashMap<Int, AtomicInteger>()
        val renderStarts = CopyOnWriteArrayList<Int>()
        val renderEnds = CopyOnWriteArrayList<Int>()
        val clipCalls = CopyOnWriteArrayList<Int>()
        val sizeCalls = AtomicInteger(0)

        override fun renderClip(
            page: Int,
            clip: PdfPageClip,
            targetClipWidthPx: Int
        ): Bitmap? {
            clipCalls.add(page)
            return super.renderClip(page, clip, targetClipWidthPx)
        }

        fun rendersOf(page: Int): Int = renderCounts[page]?.get() ?: 0

        override fun renderRaw(page: Int, targetWidthPx: Int): Bitmap {
            renderCounts.computeIfAbsent(page) { AtomicInteger(0) }.incrementAndGet()
            renderStarts.add(page)
            if (renderDelayMs > 0) Thread.sleep(renderDelayMs)
            val height = (targetWidthPx.toFloat() * PAGE_H_PT / PAGE_W_PT)
                .toInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(
                targetWidthPx, height, Bitmap.Config.ARGB_8888
            )
            renderEnds.add(page)
            return bitmap
        }

        override fun pagePointsSize(page: Int): Pair<Int, Int> {
            sizeCalls.incrementAndGet()
            return PAGE_W_PT to PAGE_H_PT
        }

        override fun close() = Unit
    }

    private fun writeFixture(file: File) {
        val document = PDDocument()
        repeat(PAGE_COUNT) {
            document.addPage(PDPage(PDRectangle(PAGE_W_PT.toFloat(), PAGE_H_PT.toFloat())))
        }
        document.save(file)
        document.close()
    }

    /** Opens a session over a fresh copy of the fixture; the
     * caller must close it. */
    fun openSession(
        tempRoot: File,
        bookId: Long,
        provider: CountingProvider,
        tier: PerfTier = PerfTier.FULL
    ): PdfSession {
        val filesRoot = File(tempRoot, "files-$bookId").apply { mkdirs() }
        val cacheDir = File(tempRoot, "cache-$bookId").apply { mkdirs() }
        writeFixture(File(filesRoot, "book.pdf"))
        val book = Book(
            id = bookId,
            title = "V159 Fixture",
            sourceType = SourceType.LOCAL_FILE,
            localFormat = BookFormat.PDF,
            filePath = "book.pdf",
            addedAt = 0,
            updatedAt = 0,
            fileFingerprint = "v159-fixture-$bookId"
        )
        return runBlocking {
            PdfSession.openInternal(
                context = RuntimeEnvironment.getApplication(),
                book = book,
                filesRoot = filesRoot,
                cacheDir = cacheDir,
                password = null,
                platformCanHonourPassword = false,
                tier = tier,
                platformFactory = { _, _, _, _ -> provider },
                fallbackFactory = { c, b, r, pw -> PdfBoxPageProvider.create(c, b, r, pw) }
            )
        }
    }

    fun cacheDirOf(tempRoot: File, bookId: Long): File =
        File(tempRoot, "cache-$bookId/local-pdf/$bookId")
}
