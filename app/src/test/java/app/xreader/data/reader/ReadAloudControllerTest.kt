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

    // -- Patch 12: priming, chapter-ahead, status, interruption --------------

    /** A fake that records the Patch 12 seam calls (utterance ids
     * included) — the pre-existing FakeTtsEngine above is untouched
     * and its tests run against the defaulted seam exactly as
     * before. */
    private class PrimingFakeTtsEngine : TtsEngine {
        var capturedListener: TtsEngine.Listener? = null
        val spokenIds = ArrayList<String>()
        val spoken = ArrayList<String>()
        val primeCalls = ArrayList<List<PrimedSentence>>()
        val cacheChapterCalls = ArrayList<List<String>>()
        private var lastId: String = ""
        override val isReady: Boolean = true
        override fun setListener(listener: TtsEngine.Listener?) {
            capturedListener = listener
        }
        override fun speak(utteranceId: String, text: String) {
            lastId = utteranceId
            spokenIds += utteranceId
            spoken += text
        }
        override fun setRate(rate: Float) = Unit
        override fun stop() = Unit
        override fun shutdown() = Unit
        override fun prime(sentences: List<PrimedSentence>) {
            primeCalls += sentences
        }
        override fun cacheChapter(sentences: List<String>) {
            cacheChapterCalls += sentences
        }
        fun done() = capturedListener?.onDone(lastId)
    }

    private fun fiveSentenceText(): ChapterText = ChapterText(
        ChapterContent(
            listOf(
                ChapterBlock.Paragraph.plain(
                    "One here. Two here. Three here. Four here. Five here."
                )
            )
        )
    )

    private fun primingController(
        engine: PrimingFakeTtsEngine
    ): ReadAloudController =
        ReadAloudController(engine, scope, prefetchDepth = 2)

    @Test
    fun playPrimesTheNextTwoSentencesWithTheirSpeakIds() {
        val fake = PrimingFakeTtsEngine()
        val c = primingController(fake)
        c.loadChapter(fiveSentenceText())
        c.play()
        assertEquals(listOf("One here."), fake.spoken)
        val window = fake.primeCalls.last()
        assertEquals(listOf("Two here.", "Three here."), window.map { it.text })
        // Advancing speaks sentence two with the SAME id it was
        // primed under — that is the whole point of pre-minting.
        val primedIdForTwo = window[0].utteranceId
        fake.done()
        assertEquals(primedIdForTwo, fake.spokenIds.last())
        // And the window re-primes: sentence three keeps its id,
        // sentence four joins it.
        val nextWindow = fake.primeCalls.last()
        assertEquals(listOf("Three here.", "Four here."), nextWindow.map { it.text })
        assertEquals(window[1].utteranceId, nextWindow[0].utteranceId)
    }

    @Test
    fun skipClearsPrimedIdsSoNoStaleIdIsSpoken() {
        val fake = PrimingFakeTtsEngine()
        val c = primingController(fake)
        c.loadChapter(fiveSentenceText())
        c.play()
        val primedIdForTwo = fake.primeCalls.last()[0].utteranceId
        c.skipNext()
        assertEquals("Two here.", fake.spoken.last())
        assertTrue(fake.spokenIds.last() != primedIdForTwo)
    }

    @Test
    fun cacheChapterReceivesTheRemainingSentences() {
        val fake = PrimingFakeTtsEngine()
        val c = primingController(fake)
        c.loadChapter(fiveSentenceText())
        assertEquals(
            listOf("One here.", "Two here.", "Three here.", "Four here.", "Five here."),
            fake.cacheChapterCalls.last()
        )
        c.play()
        fake.done()
        // After advancing, the remaining list starts at sentence two.
        assertEquals(
            listOf("Two here.", "Three here.", "Four here.", "Five here."),
            fake.cacheChapterCalls.last()
        )
    }

    @Test
    fun voiceStatusLandsInState() {
        val fake = PrimingFakeTtsEngine()
        val c = primingController(fake)
        c.loadChapter(fiveSentenceText())
        assertEquals(VoiceSurface.DEVICE, c.state.value.voiceStatus.surface)
        fake.capturedListener?.onVoiceStatus(
            VoiceStatus(VoiceSurface.ONLINE, voiceName = "Aria")
        )
        val status = c.state.value.voiceStatus
        assertEquals(VoiceSurface.ONLINE, status.surface)
        assertEquals("Aria", status.voiceName)
        assertFalse(status.fellBack)
    }

    @Test
    fun interruptionPausesPlayback() {
        val fake = PrimingFakeTtsEngine()
        val c = primingController(fake)
        c.loadChapter(fiveSentenceText())
        c.play()
        assertTrue(c.state.value.playing)
        fake.capturedListener?.onInterrupted()
        assertFalse(c.state.value.playing)
    }

    @Test
    fun zeroDepthNeverPrimesButStillOffersTheChapter() {
        val fake = PrimingFakeTtsEngine()
        val c = ReadAloudController(fake, scope) // depth defaults to 0
        c.loadChapter(fiveSentenceText())
        c.play()
        fake.done()
        assertTrue(fake.primeCalls.isEmpty())
        assertTrue(fake.cacheChapterCalls.isNotEmpty())
    }
}
