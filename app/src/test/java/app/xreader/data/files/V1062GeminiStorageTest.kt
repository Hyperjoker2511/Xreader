package app.xreader.data.files

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v1.0.6.2 (Gemini fix round, G6): import staging counts as
 * TEMP storage — a crashed import's stranded staged file is
 * visible in (and clearable from) the "Temporary files" row
 * instead of hiding in the unaccountable "Other" remainder.
 */
class V1062GeminiStorageTest {

    @get:Rule val temp = TemporaryFolder()

    @Test fun importStagingCountsAsTempNotOther() {
        val filesDir = temp.newFolder("files")
        val staging = File(filesDir, "import-staging/tag-123").apply { mkdirs() }
        File(staging, "book.epub").writeBytes(ByteArray(2048))
        val downloadsTemp = File(filesDir, "downloads-temp").apply { mkdirs() }
        File(downloadsTemp, "queue-1.part").writeBytes(ByteArray(512))

        val breakdown = StorageMeter.measure(filesDir)
        assertEquals(2048L + 512L, breakdown.tempBytes)
        assertEquals(0L, breakdown.otherBytes)
        assertEquals(2048L + 512L, breakdown.totalBytes)
    }
}
