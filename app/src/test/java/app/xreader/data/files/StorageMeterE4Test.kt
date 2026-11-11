package app.xreader.data.files

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v1.5.2 (E4): the clean-cache BYTE REPORT against the real
 * owner-tracked stores. The stores are BookStorage's actual
 * directories (measured by [StorageMeter], the same meter the
 * Storage screen reads): content/ (chapter bodies + book
 * assets), covers/, downloads-temp/, import-staging/. The
 * production Clean cache (AppContainer.cleanCache) clears the
 * CACHE-classified parts — AUTOMATIC chapter bodies via the
 * chapter rows, the Coil disk cache, and the two temp stores
 * via [StorageMeter.clearContents] — and deliberately keeps
 * covers and USER content. This test pins the meter/clear pair
 * the byte report is computed with: measure → clear the two
 * temp stores → the same measure reads zero there and is
 * unchanged everywhere else.
 */
class StorageMeterE4Test {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `byte report - temp stores clear to zero, owner stores untouched`() {
        val filesDir = tmp.newFolder()
        fun seed(relative: String, bytes: Int) {
            val file = File(filesDir, relative)
            file.parentFile?.mkdirs()
            file.writeBytes(ByteArray(bytes))
        }
        seed("content/7/ch-1.json", 5_000)
        seed("content/7/img-cover.png", 2_000)
        seed("covers/7.jpg", 3_000)
        seed("downloads-temp/part.bin", 4_000)
        seed("import-staging/up/book.epub", 6_000)

        val before = StorageMeter.measure(filesDir)
        assertEquals(7_000, before.downloadedTextBytes)
        assertEquals(3_000, before.coverCacheBytes)
        // tempBytes is the meter's temp class: downloads-temp
        // PLUS import-staging (the G6 rule in StorageMeter).
        assertEquals(10_000, before.tempBytes)

        // The two directory clears cleanCache performs.
        StorageMeter.clearContents(File(filesDir, "downloads-temp"))
        StorageMeter.clearContents(File(filesDir, "import-staging"))

        val after = StorageMeter.measure(filesDir)
        assertEquals(0, after.tempBytes)
        // Owner stores are not cache prey.
        assertEquals(before.downloadedTextBytes, after.downloadedTextBytes)
        assertEquals(before.coverCacheBytes, after.coverCacheBytes)
        assertTrue(File(filesDir, "covers/7.jpg").isFile)
    }
}
