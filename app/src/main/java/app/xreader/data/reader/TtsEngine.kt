package app.xreader.data.reader

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import app.xreader.domain.reader.SentenceTimingEstimator
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The speech seam (PLAN §6): the read-aloud controller talks to this
 * interface; tests substitute a fake. [speak] reports completion via
 * [Listener.onDone] — engines that never fire progress callbacks are
 * handled by [AndroidTtsEngine]'s estimated-timing fallback.
 */
interface TtsEngine {
    interface Listener {
        fun onStart(utteranceId: String)
        fun onDone(utteranceId: String)
        fun onError(utteranceId: String)
        /** The engine itself is unusable (no engine installed, init failed…). */
        fun onUnavailable(message: String)
    }

    val isReady: Boolean

    fun setListener(listener: Listener?)
    fun speak(utteranceId: String, text: String)
    fun setRate(rate: Float)
    fun stop()
    fun shutdown()
}

/**
 * Android's on-device TTS (Samsung/Google engines on the M31 — PLAN
 * A8). Sentence-at-a-time utterances with an [UtteranceProgressListener];
 * if an engine goes silent for a whole estimated sentence duration
 * without reporting done, the fallback timer advances the queue so
 * read-aloud can never wedge (PLAN §6 risk mitigation).
 */
class AndroidTtsEngine(
    context: Context,
    private val scope: CoroutineScope,
    private val fallbackEnabled: Boolean = true
) : TtsEngine {

    private var listener: TtsEngine.Listener? = null
    private var tts: TextToSpeech? = null
    private var ready = false
    private var rate: Float = 1f
    private var fallbackJob: Job? = null
    private var generation = 0

    override val isReady: Boolean get() = ready

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ready = true
                tts?.language = Locale.getDefault()
                tts?.setSpeechRate(rate)
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String) {
                        listener?.onStart(utteranceId)
                    }

                    override fun onDone(utteranceId: String) {
                        fallbackJob?.cancel()
                        listener?.onDone(utteranceId)
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String) {
                        fallbackJob?.cancel()
                        listener?.onError(utteranceId)
                    }
                })
            } else {
                listener?.onUnavailable(
                    "The device's speech engine couldn't start. " +
                        "Check that a text-to-speech engine is installed."
                )
            }
        }
    }

    override fun setListener(listener: TtsEngine.Listener?) {
        this.listener = listener
    }

    override fun speak(utteranceId: String, text: String) {
        val engine = tts
        if (engine == null || !ready) {
            listener?.onUnavailable(
                "Speech isn't ready yet on this device — give it a moment and try again."
            )
            return
        }
        val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        if (result == TextToSpeech.ERROR) {
            listener?.onError(utteranceId)
            return
        }
        if (fallbackEnabled) {
            val gen = ++generation
            fallbackJob?.cancel()
            fallbackJob = scope.launch {
                // Engines that report progress cancel this via onDone.
                // A silent engine gets the estimated duration + grace.
                delay(SentenceTimingEstimator.estimateMillis(text, rate) + FALLBACK_GRACE_MS)
                if (gen == generation) listener?.onDone(utteranceId)
            }
        }
    }

    override fun setRate(rate: Float) {
        this.rate = rate
        tts?.setSpeechRate(rate)
    }

    override fun stop() {
        generation++
        fallbackJob?.cancel()
        tts?.stop()
    }

    override fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
        ready = false
    }

    companion object {
        const val FALLBACK_GRACE_MS = 4_000L

        fun newUtteranceId(): String = "xr-" + UUID.randomUUID().toString()
    }
}
