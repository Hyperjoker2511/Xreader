package app.xreader.data.reader

import app.xreader.data.prefs.VoiceEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The voice-engine route (Patch 12, spec §E.2 — the Patch 10
 * router pattern): the controller talks to THIS; it owns the
 * device and Edge delegates and the failover policy, so each
 * delegate stays single-purpose.
 *
 * The fork kept fallback INSIDE its Edge engine; here it is
 * inverted into the router, with the fork's manners kept and
 * named: a 60 s cool-down after an Edge failure
 * ([EDGE_RETRY_COOLDOWN_MS]) and a readiness wait of up to 6 s
 * when re-speaking through the device engine
 * ([FALLBACK_READY_WAIT_MS]).
 *
 * The choice is read fresh at every [speak] — an engine switch
 * in the Listen sheet lands at the next sentence boundary, never
 * mid-sentence. The controller sees exactly ONE terminal
 * callback per utterance id: when Edge fails the current
 * utterance, the router re-speaks the same (id, text) through
 * the device delegate and swallows the stale Edge callback.
 */
class VoiceEngineRouter(
    private val device: TtsEngine,
    private val edge: TtsEngine,
    private val engineChoice: () -> VoiceEngine,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis
) : TtsEngine {

    private var listener: TtsEngine.Listener? = null

    /** The utterance currently in flight and the delegate that
     * owns it. Stale callbacks from the other delegate (an id
     * already failed over) are swallowed against this pair. */
    private var currentId: String? = null
    private var currentText: String? = null
    private var currentOnEdge: Boolean = false

    /** Edge is not tried again until this clock time. */
    private var edgeDownUntil: Long = 0L

    private val deviceListener = object : TtsEngine.Listener {
        override fun onStart(utteranceId: String) {
            if (utteranceId == currentId && !currentOnEdge) {
                listener?.onStart(utteranceId)
            }
        }

        override fun onDone(utteranceId: String) {
            if (utteranceId == currentId && !currentOnEdge) {
                listener?.onDone(utteranceId)
            }
        }

        override fun onError(utteranceId: String) {
            if (utteranceId == currentId && !currentOnEdge) {
                listener?.onError(utteranceId)
            }
        }

        override fun onUnavailable(message: String) {
            // The device engine itself is unusable: today's
            // behaviour, forwarded verbatim.
            listener?.onUnavailable(message)
        }

        override fun onVoiceStatus(status: VoiceStatus) {
            listener?.onVoiceStatus(status)
        }

        override fun onInterrupted() {
            listener?.onInterrupted()
        }
    }

    private val edgeListener = object : TtsEngine.Listener {
        override fun onStart(utteranceId: String) {
            if (utteranceId == currentId && currentOnEdge) {
                listener?.onStart(utteranceId)
            }
        }

        override fun onDone(utteranceId: String) {
            if (utteranceId == currentId && currentOnEdge) {
                listener?.onDone(utteranceId)
            }
        }

        override fun onError(utteranceId: String) {
            if (utteranceId == currentId && currentOnEdge) {
                failOver(utteranceId)
            }
            // A stale id (already failed over, or not current):
            // swallowed — the controller must see exactly one
            // terminal callback per utterance.
        }

        override fun onUnavailable(message: String) {
            // The Edge delegate never raises this (its failures
            // are per-utterance); ignore defensively.
        }

        override fun onVoiceStatus(status: VoiceStatus) {
            listener?.onVoiceStatus(status)
        }

        override fun onInterrupted() {
            listener?.onInterrupted()
        }
    }

    override val isReady: Boolean
        get() = selected().isReady

    override fun setListener(listener: TtsEngine.Listener?) {
        this.listener = listener
        device.setListener(if (listener == null) null else deviceListener)
        edge.setListener(if (listener == null) null else edgeListener)
    }

    override fun speak(utteranceId: String, text: String) {
        currentId = utteranceId
        currentText = text
        if (engineChoice() == VoiceEngine.EDGE && clock() >= edgeDownUntil) {
            currentOnEdge = true
            edge.speak(utteranceId, text)
        } else {
            currentOnEdge = false
            device.speak(utteranceId, text)
        }
    }

    override fun prime(sentences: List<PrimedSentence>) {
        // Only the selected delegate prepares (the device
        // delegate ignores the hint by default).
        selected().prime(sentences)
    }

    override fun cacheChapter(sentences: List<String>) {
        selected().cacheChapter(sentences)
    }

    override fun setRate(rate: Float) {
        device.setRate(rate)
        edge.setRate(rate)
    }

    override fun stop() {
        currentId = null
        currentText = null
        device.stop()
        edge.stop()
    }

    override fun shutdown() {
        device.shutdown()
        edge.shutdown()
    }

    /** The delegate the current choice selects, honouring the
     * cool-down (during it, the device delegate serves). */
    private fun selected(): TtsEngine =
        if (engineChoice() == VoiceEngine.EDGE && clock() < edgeDownUntil) {
            device
        } else if (engineChoice() == VoiceEngine.EDGE) {
            edge
        } else {
            device
        }

    /** The current utterance failed on Edge: cool the endpoint
     * down, SAY the device voice is speaking (and why), and
     * re-speak the same (id, text) through the device delegate —
     * waiting for its readiness the way the fork did. */
    private fun failOver(utteranceId: String) {
        val text = currentText ?: return
        edgeDownUntil = clock() + EDGE_RETRY_COOLDOWN_MS
        currentOnEdge = false
        listener?.onVoiceStatus(VoiceStatus(VoiceSurface.DEVICE, fellBack = true))
        scope.launch {
            var waited = 0L
            while (!device.isReady && waited < FALLBACK_READY_WAIT_MS) {
                delay(READY_POLL_MS)
                waited += READY_POLL_MS
            }
            // Speak only if this utterance is still the current
            // one (a stop or a newer speak supersedes it).
            if (currentId == utteranceId && !currentOnEdge) {
                device.speak(utteranceId, text)
            }
        }
    }

    companion object {
        /** The fork's cool-down: after an Edge failure the
         * device voice serves for a minute before Edge is
         * retried. */
        const val EDGE_RETRY_COOLDOWN_MS = 60_000L

        /** The fork's readiness wait when failing over to the
         * (asynchronously initialising) device engine. */
        const val FALLBACK_READY_WAIT_MS = 6_000L

        const val READY_POLL_MS = 200L
    }
}
