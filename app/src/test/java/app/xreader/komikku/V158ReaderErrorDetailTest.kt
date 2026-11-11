package app.xreader.komikku

import app.xreader.data.extension.DiagnosticLog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * v1.5.8 (K5): the reader error state's model carries
 * ensureStored's recorded failure detail verbatim (the
 * DiagnosticLog record ChapterTextLoader leaves, tag
 * "download", detail "chapter N · book M") — for THIS chapter
 * only.
 */
class V158ReaderErrorDetailTest {

    @Before fun setUp() = DiagnosticLog.clear()
    @After fun tearDown() = DiagnosticLog.clear()

    @Test
    fun `a failed ensureStored with a recorded detail surfaces it verbatim`() {
        DiagnosticLog.record(
            "download", "couldn't load that page in time",
            "chapter 42 · book 7"
        )
        val detail = ReaderErrorDetail.fromLog(DiagnosticLog.entries(), 42)
        assertEquals("couldn't load that page in time", detail)
    }

    @Test
    fun `another chapter's record is not this chapter's detail`() {
        DiagnosticLog.record(
            "download", "HTTP 403 for chapter", "chapter 9 · book 7"
        )
        assertNull(ReaderErrorDetail.fromLog(DiagnosticLog.entries(), 42))
        assertNull(ReaderErrorDetail.fromLog(emptyList(), 9))
    }
}
