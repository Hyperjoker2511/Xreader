package app.xreader.data.reader

import app.xreader.domain.reader.ChapterText
import app.xreader.domain.reader.Sentence
import app.xreader.domain.reader.SentenceSplitter
import app.xreader.domain.reader.SleepMode
import app.xreader.domain.reader.SleepTimer
import app.xreader.domain.reader.TtsQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Hybrid read-aloud orchestration (PLAN §6): owns the [TtsQueue], the
 * engine seam, and the [SleepTimer], and publishes one state the
 * reader UI binds to — including the current sentence's char range
 * for karaoke highlighting. Chapter boundaries are reported through
 * [onChapterFinished] so the ViewModel can chain into the next
 * chapter (unless the sleep timer says stop).
 */
class ReadAloudController(
    private val engine: TtsEngine,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis
) {
    data class State(
        val available: Boolean = true,
        val unavailableMessage: String? = null,
        val playing: Boolean = false,
        /** Char range of the sentence being spoken (karaoke highlight). */
        val sentenceStart: Int = -1,
        val sentenceEnd: Int = -1,
        val sentenceIndex: Int = 0,
        val sentenceCount: Int = 0,
        val sleepMode: SleepMode = SleepMode.Off,
        val sleepRemainingMs: Long? = null
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private var chapterText: ChapterText? = null
    private var queue: TtsQueue? = null
    private val sleepTimer = SleepTimer(clock)
    private var sleepJob: Job? = null
    private var currentUtteranceId: String? = null

    /** Fired when a chapter's last sentence finishes (not on stop). */
    var onChapterFinished: (() -> Unit)? = null

    init {
        engine.setListener(object : TtsEngine.Listener {
            override fun onStart(utteranceId: String) = Unit

            override fun onDone(utteranceId: String) {
                if (utteranceId != currentUtteranceId) return
                advance()
            }

            override fun onError(utteranceId: String) {
                if (utteranceId != currentUtteranceId) return
                // A failed sentence must not wedge the chapter: move on.
                advance()
            }

            override fun onUnavailable(message: String) {
                _state.value = _state.value.copy(
                    available = false,
                    unavailableMessage = message,
                    playing = false
                )
            }
        })
    }

    /** Loads a chapter's sentences; [startCharOffset] begins near the
     * reader's current position (read from where you are). */
    fun loadChapter(text: ChapterText, startCharOffset: Int = 0) {
        stopInternal()
        chapterText = text
        val sentences = SentenceSplitter.split(text.text)
        val startIndex = sentences.indexOfFirst { it.end > startCharOffset }
            .let { if (it < 0) 0 else it }
        queue = TtsQueue(sentences).apply { if (sentences.isNotEmpty()) jumpTo(startIndex) }
        publish()
    }

    fun play() {
        val q = queue ?: return
        if (q.sentences.isEmpty()) return
        q.play()
        speakCurrent()
        publish()
    }

    fun pause() {
        queue?.pause()
        engine.stop()
        currentUtteranceId = null
        publish()
    }

    fun stopAll() {
        stopInternal()
        sleepTimer.disarm()
        sleepJob?.cancel()
        publish()
    }

    /** Chapter change: stop speaking but keep the sleep timer armed
     * (a 20-minute timer must survive chapter boundaries). */
    fun stopForChapterChange() {
        stopInternal()
        publish()
    }

    fun skipNext() {
        val q = queue ?: return
        val wasPlaying = q.playing
        engine.stop()
        q.skipNext()
        if (wasPlaying) speakCurrent()
        publish()
    }

    fun skipPrevious() {
        val q = queue ?: return
        val wasPlaying = q.playing
        engine.stop()
        q.skipPrevious()
        if (wasPlaying) speakCurrent()
        publish()
    }

    fun setRate(rate: Float) = engine.setRate(rate)

    fun armSleep(mode: SleepMode) {
        sleepTimer.arm(mode)
        sleepJob?.cancel()
        if (mode is SleepMode.AfterMinutes) {
            sleepJob = scope.launch {
                while (isActive) {
                    if (sleepTimer.isExpired()) {
                        pause()
                        sleepTimer.disarm()
                        publish()
                        break
                    }
                    _state.value = _state.value.copy(
                        sleepMode = sleepTimer.mode,
                        sleepRemainingMs = sleepTimer.remainingMs()
                    )
                    delay(1_000L)
                }
            }
        }
        publish()
    }

    fun shutdown() {
        stopAll()
        engine.shutdown()
    }

    private fun advance() {
        val q = queue ?: return
        val next: Sentence? = q.onUtteranceDone()
        if (next == null) {
            currentUtteranceId = null
            val endOfChapterStop = sleepTimer.chapterEnded()
            if (endOfChapterStop) sleepTimer.disarm()
            publish()
            if (!endOfChapterStop) onChapterFinished?.invoke()
            return
        }
        speakCurrent()
        publish()
    }

    private fun speakCurrent() {
        val q = queue ?: return
        val text = chapterText ?: return
        val sentence = q.current ?: return
        val id = AndroidTtsEngine.newUtteranceId()
        currentUtteranceId = id
        engine.speak(id, sentence.textIn(text.text))
    }

    private fun stopInternal() {
        queue?.stop()
        engine.stop()
        currentUtteranceId = null
    }

    private fun publish() {
        val q = queue
        val current = q?.current
        _state.value = _state.value.copy(
            playing = q?.playing ?: false,
            sentenceStart = current?.start ?: -1,
            sentenceEnd = current?.end ?: -1,
            sentenceIndex = q?.index ?: 0,
            sentenceCount = q?.sentences?.size ?: 0,
            sleepMode = sleepTimer.mode,
            sleepRemainingMs = sleepTimer.remainingMs()
        )
    }
}
