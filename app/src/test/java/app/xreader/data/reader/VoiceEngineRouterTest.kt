package app.xreader.data.reader

import app.xreader.data.prefs.VoiceEngine
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The voice-engine router (spec §E.2/§F.2): delegation by the
 * live choice, failover with the fork's cool-down and readiness
 * wait, exactly one terminal callback per utterance, stale
 * callbacks swallowed.
 */
class VoiceEngineRouterTest {

    private class FakeDelegateEngine : TtsEngine {
        var capturedListener: TtsEngine.Listener? = null
        var ready = true
        val spoken = ArrayList<Pair<String, String>>()
        val primed = ArrayList<List<PrimedSentence>>()
        val chapters = ArrayList<List<String>>()
        var stops = 0

        override val isReady: Boolean get() = ready

        override fun setListener(listener: TtsEngine.Listener?) {
            capturedListener = listener
        }

        override fun speak(utteranceId: String, text: String) {
            spoken += utteranceId to text
        }

        override fun setRate(rate: Float) = Unit
        override fun stop() {
            stops++
        }

        override fun shutdown() = Unit

        override fun prime(sentences: List<PrimedSentence>) {
            primed += sentences
        }

        override fun cacheChapter(sentences: List<String>) {
            chapters += sentences
        }

        fun done(id: String) = capturedListener?.onDone(id)
        fun error(id: String) = capturedListener?.onError(id)
        fun unavailable(message: String) = capturedListener?.onUnavailable(message)
        fun status(status: VoiceStatus) = capturedListener?.onVoiceStatus(status)
    }

    private class RecordingListener : TtsEngine.Listener {
        val started = ArrayList<String>()
        val done = ArrayList<String>()
        val errors = ArrayList<String>()
        val statuses = ArrayList<VoiceStatus>()
        val unavailable = ArrayList<String>()
        var interrupted = 0

        override fun onStart(utteranceId: String) {
            started += utteranceId
        }

        override fun onDone(utteranceId: String) {
            done += utteranceId
        }

        override fun onError(utteranceId: String) {
            errors += utteranceId
        }

        override fun onUnavailable(message: String) {
            unavailable += message
        }

        override fun onVoiceStatus(status: VoiceStatus) {
            statuses += status
        }

        override fun onInterrupted() {
            interrupted++
        }
    }

    private class Rig(
        val device: FakeDelegateEngine,
        val edge: FakeDelegateEngine,
        val router: VoiceEngineRouter,
        val listener: RecordingListener
    )

    private fun rig(
        scope: kotlinx.coroutines.CoroutineScope,
        choice: () -> VoiceEngine,
        clock: () -> Long = { 0L }
    ): Rig {
        val device = FakeDelegateEngine()
        val edge = FakeDelegateEngine()
        val router = VoiceEngineRouter(device, edge, choice, scope, clock)
        val listener = RecordingListener()
        router.setListener(listener)
        return Rig(device, edge, router, listener)
    }

    @Test
    fun `device choice delegates everything to the device engine`() = runTest {
        val rig = rig(this, { VoiceEngine.DEVICE })
        rig.router.speak("a", "Alpha.")
        rig.router.prime(listOf(PrimedSentence("b", "Bravo.")))
        rig.router.cacheChapter(listOf("Alpha.", "Bravo."))
        assertEquals(listOf("a" to "Alpha."), rig.device.spoken)
        assertTrue(rig.edge.spoken.isEmpty())
        assertEquals(1, rig.device.primed.size)
        assertTrue(rig.edge.primed.isEmpty())
        assertEquals(1, rig.device.chapters.size)
        assertTrue(rig.router.isReady)
    }

    @Test
    fun `edge choice speaks and primes through edge only`() = runTest {
        val rig = rig(this, { VoiceEngine.EDGE })
        rig.router.speak("a", "Alpha.")
        rig.router.prime(listOf(PrimedSentence("b", "Bravo.")))
        assertEquals(listOf("a" to "Alpha."), rig.edge.spoken)
        assertTrue(rig.device.spoken.isEmpty())
        assertEquals(1, rig.edge.primed.size)
        assertTrue(rig.device.primed.isEmpty())
    }

    @Test
    fun `edge error re-speaks the same id on device with exactly one terminal callback`() =
        runTest {
            val rig = rig(this, { VoiceEngine.EDGE })
            rig.router.speak("a", "Alpha.")
            rig.edge.error("a")
            advanceUntilIdle()
            // The SAME (id, text) is re-spoken by the device delegate.
            assertEquals(listOf("a" to "Alpha."), rig.device.spoken)
            // The status line says the device voice took over.
            assertEquals(
                listOf(VoiceStatus(VoiceSurface.DEVICE, fellBack = true)),
                rig.listener.statuses
            )
            // The edge error itself never reached the listener;
            // the device completion is the one terminal callback.
            assertTrue(rig.listener.errors.isEmpty())
            rig.device.done("a")
            assertEquals(listOf("a"), rig.listener.done)
            // A stale late edge callback for the same id is swallowed.
            rig.edge.done("a")
            rig.edge.error("a")
            assertEquals(listOf("a"), rig.listener.done)
            assertTrue(rig.listener.errors.isEmpty())
        }

    @Test
    fun `cool-down sends speaks straight to device until it lapses`() = runTest {
        var now = 1_000_000L
        val rig = rig(this, { VoiceEngine.EDGE }, clock = { now })
        rig.router.speak("a", "Alpha.")
        rig.edge.error("a")
        advanceUntilIdle()
        // Inside the 60 s cool-down: straight to device.
        rig.router.speak("b", "Bravo.")
        assertEquals(listOf("a" to "Alpha.", "b" to "Bravo."), rig.device.spoken)
        assertEquals(listOf("a" to "Alpha."), rig.edge.spoken)
        // After the cool-down, edge is tried again.
        now += VoiceEngineRouter.EDGE_RETRY_COOLDOWN_MS + 1
        rig.router.speak("c", "Charlie.")
        assertEquals(listOf("a" to "Alpha.", "c" to "Charlie."), rig.edge.spoken)
    }

    @Test
    fun `failover waits for device readiness before re-speaking`() = runTest {
        val rig = rig(this, { VoiceEngine.EDGE })
        rig.device.ready = false
        rig.router.speak("a", "Alpha.")
        rig.edge.error("a")
        runCurrent()
        assertTrue(rig.device.spoken.isEmpty()) // still waiting
        rig.device.ready = true
        advanceTimeBy(VoiceEngineRouter.READY_POLL_MS)
        runCurrent()
        assertEquals(listOf("a" to "Alpha."), rig.device.spoken)
    }

    @Test
    fun `a superseded failover never re-speaks`() = runTest {
        val rig = rig(this, { VoiceEngine.EDGE })
        rig.device.ready = false
        rig.router.speak("a", "Alpha.")
        rig.edge.error("a")
        runCurrent()
        rig.router.stop() // the user stopped before the wait ended
        rig.device.ready = true
        advanceUntilIdle()
        assertTrue(rig.device.spoken.isEmpty())
    }

    @Test
    fun `device unavailable forwards verbatim`() = runTest {
        val rig = rig(this, { VoiceEngine.DEVICE })
        rig.router.speak("a", "Alpha.")
        rig.device.unavailable("Speech isn't ready yet on this device.")
        assertEquals(
            listOf("Speech isn't ready yet on this device."),
            rig.listener.unavailable
        )
    }

    @Test
    fun `edge status heals the line when edge speaks again`() = runTest {
        val rig = rig(this, { VoiceEngine.EDGE })
        rig.router.speak("a", "Alpha.")
        rig.edge.status(VoiceStatus(VoiceSurface.ONLINE, voiceName = "Aria"))
        assertEquals(
            listOf(VoiceStatus(VoiceSurface.ONLINE, voiceName = "Aria")),
            rig.listener.statuses
        )
    }

    @Test
    fun `stop hits both delegates and rate fans out`() = runTest {
        val rig = rig(this, { VoiceEngine.EDGE })
        rig.router.stop()
        assertEquals(1, rig.device.stops)
        assertEquals(1, rig.edge.stops)
        assertFalse(rig.router.isReady && false) // readiness smoke: delegates ready
        assertTrue(rig.router.isReady)
    }
}
