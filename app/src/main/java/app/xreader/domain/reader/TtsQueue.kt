package app.xreader.domain.reader

/**
 * The read-aloud queue (PLAN §6): an ordered sentence list with a
 * cursor, transport state, and an end-of-chapter signal. Pure — the
 * Android TTS engine and the Media Overlay player are driven by it,
 * and the fake-engine tests run here.
 */
class TtsQueue(sentences: List<Sentence>) {

    val sentences: List<Sentence> = sentences
    var index: Int = 0
        private set
    var playing: Boolean = false
        private set
    /** True once the cursor has run off the end (chapter finished). */
    var finished: Boolean = sentences.isEmpty()
        private set

    val current: Sentence? get() = sentences.getOrNull(index)
    val hasNext: Boolean get() = index + 1 < sentences.size
    val hasPrevious: Boolean get() = index > 0

    fun play() {
        if (sentences.isNotEmpty()) playing = true
    }

    fun pause() {
        playing = false
    }

    fun stop() {
        playing = false
    }

    /** Starts speaking at [sentenceIndex] (clamped); returns it. */
    fun jumpTo(sentenceIndex: Int): Sentence? {
        if (sentences.isEmpty()) return null
        index = sentenceIndex.coerceIn(0, sentences.lastIndex)
        finished = false
        return current
    }

    /** The utterance at the cursor finished: advance. Returns the next
     * sentence to speak, or null when the chapter is done. */
    fun onUtteranceDone(): Sentence? {
        if (index + 1 >= sentences.size) {
            playing = false
            finished = true
            return null
        }
        index++
        return current
    }

    /** Manual skip forward; returns the sentence to speak now. */
    fun skipNext(): Sentence? {
        if (!hasNext) return current
        index++
        finished = false
        return current
    }

    /** Manual skip back; returns the sentence to speak now. */
    fun skipPrevious(): Sentence? {
        if (!hasPrevious) return current
        index--
        finished = false
        return current
    }
}

/** When read-aloud should stop on its own (PLAN §6 sleep timer). */
sealed interface SleepMode {
    data object Off : SleepMode
    data class AfterMinutes(val minutes: Int) : SleepMode
    data object EndOfChapter : SleepMode
}

/**
 * Sleep-timer arithmetic (pure, injected clock). [SleepMode.EndOfChapter]
 * has no deadline — the queue's `finished` signal is its trigger.
 */
class SleepTimer(private val clock: () -> Long) {

    var mode: SleepMode = SleepMode.Off
        private set
    private var deadlineMs: Long? = null

    fun arm(newMode: SleepMode) {
        mode = newMode
        deadlineMs = when (newMode) {
            is SleepMode.AfterMinutes -> clock() + newMode.minutes * 60_000L
            else -> null
        }
    }

    fun disarm() {
        mode = SleepMode.Off
        deadlineMs = null
    }

    /** Remaining time for timed modes; null for Off/EndOfChapter. */
    fun remainingMs(now: Long = clock()): Long? =
        deadlineMs?.let { maxOf(0L, it - now) }

    fun isExpired(now: Long = clock()): Boolean =
        deadlineMs?.let { now >= it } ?: false

    /** EndOfChapter fires through this when the queue reports finished. */
    fun chapterEnded(): Boolean = mode == SleepMode.EndOfChapter
}

/**
 * Fallback sentence timing for TTS engines that never report utterance
 * progress (PLAN §6 risk mitigation): estimated speaking duration from
 * character count and speech rate — ~850 chars/min at 1.0× for Latin,
 * ~400 chars/min for CJK-dense text (counted by wide chars).
 */
object SentenceTimingEstimator {
    const val LATIN_CHARS_PER_MIN = 850.0
    const val CJK_CHARS_PER_MIN = 400.0

    fun estimateMillis(text: String, rate: Float): Long {
        if (text.isEmpty()) return 0L
        var wide = 0
        for (c in text) if (LineWrap.isWide(c)) wide++
        val narrow = text.length - wide
        val minutes = narrow / LATIN_CHARS_PER_MIN + wide / CJK_CHARS_PER_MIN
        val safeRate = if (rate <= 0f) 1f else rate
        return (minutes * 60_000.0 / safeRate).toLong().coerceAtLeast(250L)
    }
}
