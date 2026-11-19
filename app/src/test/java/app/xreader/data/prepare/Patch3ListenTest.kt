package app.xreader.data.prepare

import app.xreader.core.error.AppResult
import app.xreader.data.parse.EpubParser
import app.xreader.data.parse.decodeChapter
import app.xreader.data.reader.ReadAloudController
import app.xreader.data.reader.TtsEngine
import app.xreader.domain.reader.ChapterText
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v1.7 Patch 3 (Item 5/6): the Listen seam for a converted
 * EPUB chapter — a ChapterText built from the EpubParser's
 * own conversion feeds the shared ReadAloudController, and
 * starting mid-chapter (the place the reader restored)
 * speaks from the sentence at that offset, not from the
 * chapter's first sentence. This is the wiring the novel
 * road already had and the river road never did (see
 * TRACE-EPUB-LISTEN-NO-VOICE); on the core road a prepared
 * EPUB is indistinguishable from a novel chapter, which is
 * the fix.
 */
class Patch3ListenTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class FakeTtsEngine : TtsEngine {
        var capturedListener: TtsEngine.Listener? = null
        val spoken = ArrayList<String>()
        private var lastId: String = ""
        override val isReady: Boolean = true
        override fun setListener(listener: TtsEngine.Listener?) {
            capturedListener = listener
        }
        override fun speak(utteranceId: String, text: String) {
            lastId = utteranceId
            spoken += text
        }
        override fun setRate(rate: Float) = Unit
        override fun stop() = Unit
        override fun shutdown() = Unit
        fun done() = capturedListener?.onDone(lastId)
    }

    private val scope = CoroutineScope(Dispatchers.Unconfined)

    @After
    fun tearDown() {
        scope.cancel()
    }

    /** Chapter 2 of the simple fixture, converted by the real
     * parser and decoded through the production seam. */
    private fun convertedChapterText(): ChapterText {
        val dir = tmp.newFolder("listen-${System.nanoTime()}")
        val file = File(dir, "book.epub")
        file.writeBytes(Patch3EpubFixtures.simpleBook())
        val parsed = when (
            val result = EpubParser().parse(file, File(dir, "work"))
        ) {
            is AppResult.Ok -> result.value
            is AppResult.Err -> throw AssertionError("parse failed: ${result.error}")
        }
        val meta = parsed.chapters[1]
        val json = Json { ignoreUnknownKeys = true }
        val content = json.decodeChapter(
            File(parsed.chaptersDir, meta.contentFileName).readText()
        )
        return ChapterText(content)
    }

    @Test
    fun listenStartsAtTheSentenceUnderTheRestoredOffset() {
        val text = convertedChapterText()
        assertTrue(text.text.contains("Bravo crosses"))
        assertTrue(text.text.contains("ferry lantern"))

        val engine = FakeTtsEngine()
        val controller = ReadAloudController(engine, scope)
        // The reader's restore offset sits inside the second
        // sentence — Listen must start THERE.
        val offset = text.text.indexOf("The ferry lantern")
        assertTrue(offset > 0)
        controller.loadChapter(text, startCharOffset = offset)
        controller.play()
        assertEquals(
            listOf("The ferry lantern sways in the wind."),
            engine.spoken
        )
        val state = controller.state.value
        assertTrue(state.playing)
        assertEquals(offset, state.sentenceStart)
    }

    @Test
    fun listenFromTheTopStartsAtTheFirstSentence() {
        val text = convertedChapterText()
        val engine = FakeTtsEngine()
        val controller = ReadAloudController(engine, scope)
        controller.loadChapter(text)
        controller.play()
        // The chapter heading is its own first sentence on
        // this road (the splitter breaks at paragraph bounds);
        // the body sentences follow in order.
        assertEquals(listOf("Two"), engine.spoken)
        engine.done()
        assertEquals(
            "Bravo crosses the river valley under a low sky.",
            engine.spoken.last()
        )
        engine.done()
        assertEquals(
            "The ferry lantern sways in the wind.",
            engine.spoken.last()
        )
    }
}
