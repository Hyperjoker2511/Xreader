package app.xreader.data.download

import app.xreader.data.db.ChapterSourceMap
import app.xreader.domain.content.ChapterContent
import app.xreader.domain.content.ChapterBlock
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class FailoverOrderTest {

    private fun map(id: Long, remote: String, confidence: Float, verified: Boolean) =
        ChapterSourceMap(
            id = id, chapterId = 1, altExtensionId = 0, altRemoteId = remote,
            matchConfidence = confidence, verified = verified
        )

    @Test fun verifiedFirstThenConfidence() {
        val primary = ChapterCandidate(mapId = 0, label = "primary source", locator = "https://a.test/1")
        val ordered = FailoverOrder.order(
            primary,
            listOf(
                map(1, "https://b.test/1", 0.95f, verified = false),
                map(2, "https://c.test/1", 0.50f, verified = true),
                map(3, "https://d.test/1", 0.80f, verified = false)
            )
        )
        assertEquals(
            listOf("https://a.test/1", "https://c.test/1", "https://b.test/1", "https://d.test/1"),
            ordered.map { it.locator }
        )
    }

    @Test fun labelPrefixSplits() {
        val (label, url) = FailoverOrder.splitLocator("Mirror copy|https://m.test/x")
        assertEquals("Mirror copy", label)
        assertEquals("https://m.test/x", url)
        val (label2, url2) = FailoverOrder.splitLocator("https://plain.test/y")
        assertEquals("alternate source", label2)
        assertEquals("https://plain.test/y", url2)
    }
}

class ChapterContentStoreTest {

    @get:org.junit.Rule val temp = org.junit.rules.TemporaryFolder()

    @Test fun storageRoundTripPreservesOverlay() = runTest {
        val storage = app.xreader.data.files.BookStorage(temp.newFolder("files"))
        val content = ChapterContent(
            blocks = listOf(
                ChapterBlock.Heading(1, "Start"),
                ChapterBlock.Paragraph.plain("Spoken words here.", id = "p1")
            ),
            mediaOverlay = app.xreader.domain.content.MediaOverlay(
                audioFileName = "narration.mp3",
                clips = listOf(app.xreader.domain.content.MediaClip("p1", 0.0, 3.25))
            )
        )
        val written = storage.writeChapterContent(3, 5, content)
        assertTrue2(written is app.xreader.core.error.AppResult.Ok)
        val read = storage.readChapterContent(3, 5)
        val value = (read as app.xreader.core.error.AppResult.Ok).value
        assertEquals(content.blocks.size, value.blocks.size)
        assertEquals("narration.mp3", value.mediaOverlay?.audioFileName)
        assertEquals(3.25, value.mediaOverlay?.clips?.single()?.endSec ?: -1.0, 0.001)
        assertEquals(4, value.wordCount)
    }

    private fun assertTrue2(condition: Boolean) = org.junit.Assert.assertTrue(condition)
}
