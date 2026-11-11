package app.xreader.data.parse

import app.xreader.core.error.AppError
import app.xreader.core.error.AppResult
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v1.5.4 (WS-7): the PDF encryption classification, proven against
 * REAL standard-security-handler fixtures (QPDF-generated — see
 * src/test/resources/ws7pdf/). The load seam runs on the JVM
 * (PDDocument.load needs no Android); text stripping stays
 * Android-bound and is the named device leg.
 *
 * Both directions live in this one class by construction: the
 * owner-password file the old `isEncrypted` gate refused now
 * opens (its load result IS the pre-fix refusal pair — loadable
 * AND encrypted), and the user-password file refuses without /
 * with a wrong password and opens with the correct one.
 */
class PdfLoadClassificationTest {

    @get:Rule val temp = TemporaryFolder()

    private fun fixture(name: String): File {
        val bytes = javaClass.getResourceAsStream("/ws7pdf/$name")!!
            .readBytes()
        return File(temp.newFolder(), name).apply { writeBytes(bytes) }
    }

    private fun load(
        name: String, password: String? = null
    ): AppResult<Pair<Int, Boolean>> =
        loadPdfDocument(fixture(name), password) { doc ->
            doc.numberOfPages to doc.isEncrypted
        }

    @Test fun plainPdfOpens() {
        val result = load("plain.pdf")
        assertTrue(result is AppResult.Ok)
        assertEquals(4 to false, (result as AppResult.Ok).value)
    }

    @Test fun ownerPasswordOnlyPdfOpensWithEmptyPassword() {
        // THE defect class: encrypted (the old gate's trigger) yet
        // fully readable with no password at all — Moon+ parity.
        val result = load("owner-password-only.pdf")
        assertTrue("owner-only file must open, got $result", result is AppResult.Ok)
        assertEquals(4 to true, (result as AppResult.Ok).value)
    }

    @Test fun ownerPasswordOnlyAes256PdfOpensWithEmptyPassword() {
        val result = load("owner-password-only-256.pdf")
        assertTrue("AES-256 owner-only file must open, got $result", result is AppResult.Ok)
        assertEquals(4 to true, (result as AppResult.Ok).value)
    }

    @Test fun userPasswordPdfRefusesWithoutPassword() {
        val result = load("user-password.pdf")
        assertTrue(result is AppResult.Err)
        assertEquals(AppError.DrmProtected, (result as AppResult.Err).error)
    }

    @Test fun userPasswordPdfRefusesWrongPassword() {
        val result = load("user-password.pdf", "not-the-password")
        assertTrue(result is AppResult.Err)
        assertEquals(AppError.DrmProtected, (result as AppResult.Err).error)
    }

    @Test fun userPasswordPdfOpensWithCorrectPassword() {
        val result = load("user-password.pdf", "reader-secret")
        assertTrue("correct password must open, got $result", result is AppResult.Ok)
        assertEquals(4 to true, (result as AppResult.Ok).value)
    }
}

/**
 * v1.5.4 (WS-7, addendum C): the password wiring at the parser
 * seam — a locked book refuses bare and with a wrong password,
 * and imports COMPLETELY (chapters, page-1 cover, honesty count)
 * with the correct one. The extractor is a fake that models the
 * production classification (loadPdfDocument's contract, proven
 * against real fixtures above); the Android extractor itself is
 * the named device leg.
 */
class PdfPasswordParserTest : ParserTestBase() {

    private val prose =
        "The road wound on through the long valley and the river ran beside it all the way north."
    private val pages = listOf(
        "Chapter 1\n$prose\n",
        "Chapter 2\n$prose And then the mountains rose ahead of them, white with snow.\n"
    )
    private val coverPlate = ByteArray(120 * 1024) { 7 }

    /** Models a locked file: bare extraction is DrmProtected;
     * the password-bound twin opens only for the right password. */
    private inner class LockedExtractor : PdfTextExtractor {
        override fun extractPages(file: File): AppResult<List<String>> =
            AppResult.Err(AppError.DrmProtected)

        override fun withPassword(password: String): PdfTextExtractor =
            UnlockedExtractor(password)
    }

    private inner class UnlockedExtractor(
        private val password: String
    ) : PdfTextExtractor {
        override fun extractPages(file: File): AppResult<List<String>> =
            if (password == "reader-secret") {
                AppResult.Ok(pages)
            } else {
                AppResult.Err(AppError.DrmProtected)
            }

        override fun extractImagesWithLoss(
            file: File
        ): Pair<List<PdfPageImage>, Int> =
            listOf(PdfPageImage(0, coverPlate)) to 2
    }

    @Test fun lockedPdfRefusesBare() {
        val (staged, work) = stage("locked.pdf", byteArrayOf(1))
        val error = PdfBookParser(LockedExtractor()).parse(staged, work) {}.unwrapErr()
        assertEquals(AppError.DrmProtected, error)
    }

    @Test fun lockedPdfRefusesWrongPassword() {
        val (staged, work) = stage("locked.pdf", byteArrayOf(1))
        val error = PdfBookParser(LockedExtractor())
            .parseWithPassword(staged, work, "not-the-password") {}.unwrapErr()
        assertEquals(AppError.DrmProtected, error)
    }

    @Test fun lockedPdfImportsCompletelyWithCorrectPassword() {
        val (staged, work) = stage("locked.pdf", byteArrayOf(1))
        val parsed = PdfBookParser(LockedExtractor())
            .parseWithPassword(staged, work, "reader-secret") {}.unwrap()
        assertEquals(2, parsed.chapters.size)
        // Addendum (A) through the unlock: the first-page cover
        // is derived exactly as for an unlocked file.
        val cover = parsed.coverFile
        assertTrue("cover must be derived", cover != null && cover.isFile)
        assertTrue(cover!!.readBytes().contentEquals(coverPlate))
        // The image blocks and the honesty count ride along.
        assertEquals(2, parsed.droppedImages)
        val blocks = firstChapterBlocks(parsed)
        assertTrue(blocks.any { it is app.xreader.domain.content.ChapterBlock.Image })
    }

    @Test fun extractorThatCannotUnlockNeverFakesSuccess() {
        val bare = object : PdfTextExtractor {
            override fun extractPages(file: File): AppResult<List<String>> =
                AppResult.Err(AppError.DrmProtected)
        }
        val (staged, work) = stage("locked.pdf", byteArrayOf(1))
        val error = PdfBookParser(bare)
            .parseWithPassword(staged, work, "reader-secret") {}.unwrapErr()
        assertEquals(AppError.DrmProtected, error)
    }
}
