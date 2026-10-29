package app.xreader.data.reader

import app.xreader.domain.content.ChapterBlock
import app.xreader.domain.content.ChapterContent
import app.xreader.domain.reader.ChapterText
import app.xreader.domain.reader.SleepMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ReadAloudControllerTest {

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
    private lateinit var engine: FakeTtsEngine
    private lateinit var controller: ReadAloudController

    private fun chapterText(): ChapterText = ChapterText(
        ChapterContent(
            listOf(
                ChapterBlock.Paragraph.plain(
                    "First sentence here. Second one follows. And a third to end."
                )
            )
        )
    )

    @Before
    fun setUp() {
        engine = FakeTtsEngine()
        controller = ReadAloudController(engine, scope)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun playSpeaksSentencesInOrderAndHighlightsEach() {
        controller.loadChapter(chapterText())
        controller.play()
        assertEquals(listOf("First sentence here."), engine.spoken)
        var state = controller.state.value
        assertTrue(state.playing)
        assertEquals(0, state.sentenceIndex)
        assertEquals(3, state.sentenceCount)
        assertEquals(
            "First sentence here.",
            chapterText().text.substring(state.sentenceStart, state.sentenceEnd)
        )
        engine.done()
        assertEquals(
            listOf("First sentence here.", "Second one follows."),
            engine.spoken
        )
        state = controller.state.value
        assertEquals(1, state.sentenceIndex)
        assertEquals(
            "Second one follows.",
            chapterText().text.substring(state.sentenceStart, state.sentenceEnd)
        )
    }

    @Test
    fun chapterEndChainsUnlessSleepSaysStop() {
        var chained = 0
        controller.onChapterFinished = { chained++ }
        controller.loadChapter(chapterText())
        controller.play()
        engine.done(); engine.done(); engine.done()
        assertEquals(1, chained)
        assertFalse(controller.state.value.playing)

        // With EndOfChapter sleep armed, no chaining happens.
        controller.armSleep(SleepMode.EndOfChapter)
        controller.loadChapter(chapterText())
        controller.play()
        engine.done(); engine.done(); engine.done()
        assertEquals(1, chained)
        assertEquals(SleepMode.Off, controller.state.value.sleepMode)
    }

    @Test
    fun loadChapterStartsNearTheGivenOffset() {
        val text = chapterText()
        val secondSentenceStart = text.text.indexOf("Second")
        controller.loadChapter(text, startCharOffset = secondSentenceStart)
        controller.play()
        assertEquals(listOf("Second one follows."), engine.spoken)
    }

    @Test
    fun pauseStopsSpeakingAndSkipMovesTheCursor() {
        controller.loadChapter(chapterText())
        controller.play()
        controller.pause()
        assertFalse(controller.state.value.playing)
        controller.skipNext()
        assertEquals(1, controller.state.value.sentenceIndex)
        controller.skipPrevious()
        assertEquals(0, controller.state.value.sentenceIndex)
    }

    @Test
    fun engineUnavailableSurfacesPlainly() {
        controller.loadChapter(chapterText())
        engine.capturedListener?.onUnavailable("Speech isn't ready.")
        val state = controller.state.value
        assertFalse(state.available)
        assertEquals("Speech isn't ready.", state.unavailableMessage)
        assertFalse(state.playing)
    }
}
