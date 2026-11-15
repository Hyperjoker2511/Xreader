package app.xreader.data.reader

import java.io.File
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The audio-focus state machine (spec §E.4/§F.2), exercised
 * through the Edge engine with a fake gate: transient loss
 * pauses, gain resumes only a focus-pause, permanent loss stops
 * + interrupts, completion abandons, and a REFUSED request
 * reports an interruption and never plays.
 */
class FocusGateTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class InstantSynthesizer : EdgeSynthesizer {
        override suspend fun synthesize(
            text: String,
            voice: EdgeVoiceTable.Voice,
            rate: Float
        ): ByteArray = "MP3:$text".toByteArray()
    }

    private class FakePlayer : SentencePlayer {
        override var onPrepared: (() -> Unit)? = null
        override var onCompletion: (() -> Unit)? = null
        override var onError: (() -> Unit)? = null
        var startCount = 0
        var pauseCount = 0
        var released = false
        private var playing = false
        override val isPlaying: Boolean get() = playing

        override fun load(path: String) {
            onPrepared?.invoke()
        }

        override fun start() {
            startCount++
            playing = true
        }

        override fun pause() {
            pauseCount++
            playing = false
        }

        override fun stop() {
            playing = false
        }

        override fun release() {
            released = true
            playing = false
        }

        fun complete() {
            playing = false
            onCompletion?.invoke()
        }
    }

    private class RecordingListener : TtsEngine.Listener {
        val started = ArrayList<String>()
        val done = ArrayList<String>()
        var interrupted = 0

        override fun onStart(utteranceId: String) {
            started += utteranceId
        }

        override fun onDone(utteranceId: String) {
            done += utteranceId
        }

        override fun onError(utteranceId: String) = Unit
        override fun onUnavailable(message: String) = Unit

        override fun onInterrupted() {
            interrupted++
        }
    }

    private class Rig(
        val engine: EdgeTtsEngine,
        val gate: EdgePrefetchSchedulerTest.FakeFocusGate,
        val players: ArrayList<FakePlayer>,
        val listener: RecordingListener
    )

    private fun rig(scope: CoroutineScope): Rig {
        val gate = EdgePrefetchSchedulerTest.FakeFocusGate()
        val players = ArrayList<FakePlayer>()
        val listener = RecordingListener()
        val engine = EdgeTtsEngine(
            scope = scope,
            cache = TtsAudioCache(File(tmp.root, "voice-${System.nanoTime()}")),
            synthesizer = InstantSynthesizer(),
            focusGate = gate,
            playerFactory = { FakePlayer().also { players += it } },
            voiceIdOverride = { null },
            localeProvider = { Locale.US }
        )
        engine.setListener(listener)
        return Rig(engine, gate, players, listener)
    }

    @Test
    fun `transient loss pauses and gain resumes`() = runTest {
        val rig = rig(this)
        rig.engine.speak("a", "Alpha one.")
        advanceUntilIdle()
        assertEquals(listOf("a"), rig.listener.started)
        val player = rig.players.single()
        assertEquals(1, player.startCount)
        rig.gate.fire(FocusEvent.TRANSIENT_LOSS)
        assertEquals(1, player.pauseCount)
        rig.gate.fire(FocusEvent.GAIN)
        assertEquals(2, player.startCount)
    }

    @Test
    fun `gain without a focus pause does not restart playback`() = runTest {
        val rig = rig(this)
        rig.engine.speak("a", "Alpha one.")
        advanceUntilIdle()
        val player = rig.players.single()
        rig.gate.fire(FocusEvent.GAIN)
        assertEquals(1, player.startCount)
        assertEquals(0, player.pauseCount)
    }

    @Test
    fun `transient loss while not playing does not pause`() = runTest {
        val rig = rig(this)
        rig.engine.speak("a", "Alpha one.")
        advanceUntilIdle()
        val player = rig.players.single()
        player.complete()
        rig.gate.fire(FocusEvent.TRANSIENT_LOSS)
        assertEquals(0, player.pauseCount)
        rig.gate.fire(FocusEvent.GAIN)
        assertEquals(1, player.startCount) // never focus-paused: no resume
    }

    @Test
    fun `permanent loss stops playback and reports interruption`() = runTest {
        val rig = rig(this)
        rig.engine.speak("a", "Alpha one.")
        advanceUntilIdle()
        val player = rig.players.single()
        rig.gate.fire(FocusEvent.PERMANENT_LOSS)
        assertEquals(1, rig.listener.interrupted)
        assertTrue(player.released)
        assertFalse(rig.gate.holdsFocus)
    }

    @Test
    fun `completion abandons focus and reports done`() = runTest {
        val rig = rig(this)
        rig.engine.speak("a", "Alpha one.")
        advanceUntilIdle()
        assertTrue(rig.gate.holdsFocus)
        rig.players.single().complete()
        assertEquals(listOf("a"), rig.listener.done)
        assertFalse(rig.gate.holdsFocus)
        assertTrue(rig.gate.abandons >= 1)
    }

    @Test
    fun `a refused focus request reports interruption and never plays`() = runTest {
        val rig = rig(this)
        rig.gate.granted = false
        rig.engine.speak("a", "Alpha one.")
        advanceUntilIdle()
        assertEquals(1, rig.listener.interrupted)
        assertTrue(rig.players.isEmpty())
        assertTrue(rig.listener.started.isEmpty())
    }
}
