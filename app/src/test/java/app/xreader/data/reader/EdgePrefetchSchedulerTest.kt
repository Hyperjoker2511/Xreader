package app.xreader.data.reader

import java.io.File
import java.util.Locale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The Edge engine's prefetch pipeline (spec §B.5/§F.2), driven
 * through its seams: a scripted synthesizer, a fake player and a
 * fake focus gate — no Android types, no network.
 */
class EdgePrefetchSchedulerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class FakeSynthesizer : EdgeSynthesizer {
        val calls = ArrayList<String>()
        val failures = HashSet<String>()
        var gate: CompletableDeferred<Unit>? = null
        var delayMs: Long = 0
        var active = 0
        var maxActive = 0

        override suspend fun synthesize(
            text: String,
            voice: EdgeVoiceTable.Voice,
            rate: Float
        ): ByteArray {
            calls += text
            active++
            maxActive = maxOf(maxActive, active)
            try {
                if (delayMs > 0) delay(delayMs)
                gate?.await()
                if (text in failures) throw EdgeSynthesisException("scripted failure")
                return "MP3:$text".toByteArray()
            } finally {
                active--
            }
        }
    }

    private class FakePlayer : SentencePlayer {
        override var onPrepared: (() -> Unit)? = null
        override var onCompletion: (() -> Unit)? = null
        override var onError: (() -> Unit)? = null
        var loadedPath: String? = null
        var startCount = 0
        var pauseCount = 0
        var released = false
        private var playing = false
        override val isPlaying: Boolean get() = playing

        override fun load(path: String) {
            loadedPath = path
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

    internal class FakeFocusGate : FocusGate {
        override var listener: ((FocusEvent) -> Unit)? = null
        var granted = true
        var requests = 0
        var abandons = 0
        private var held = false
        override val holdsFocus: Boolean get() = held

        override fun request(): Boolean {
            requests++
            if (granted) held = true
            return granted
        }

        override fun abandon() {
            abandons++
            held = false
        }

        fun fire(event: FocusEvent) = listener?.invoke(event)
    }

    private class RecordingListener : TtsEngine.Listener {
        val started = ArrayList<String>()
        val done = ArrayList<String>()
        val errors = ArrayList<String>()
        val statuses = ArrayList<VoiceStatus>()
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

        override fun onUnavailable(message: String) = Unit

        override fun onVoiceStatus(status: VoiceStatus) {
            statuses += status
        }

        override fun onInterrupted() {
            interrupted++
        }
    }

    private fun newCache() = TtsAudioCache(File(tmp.root, "voice-${System.nanoTime()}"))

    private fun newEngine(
        scope: CoroutineScope,
        synth: FakeSynthesizer,
        gate: FakeFocusGate,
        players: MutableList<FakePlayer>,
        cache: TtsAudioCache
    ): EdgeTtsEngine = EdgeTtsEngine(
        scope = scope,
        cache = cache,
        synthesizer = synth,
        focusGate = gate,
        playerFactory = { FakePlayer().also { players += it } },
        voiceIdOverride = { null },
        localeProvider = { Locale.US }
    )

    @Test
    fun `prime fetches in order, one socket at a time`() = runTest {
        val synth = FakeSynthesizer().apply { delayMs = 10 }
        val players = ArrayList<FakePlayer>()
        val engine = newEngine(this, synth, FakeFocusGate(), players, newCache())
        engine.prime(
            listOf(
                PrimedSentence("a", "Alpha one."),
                PrimedSentence("b", "Bravo two."),
                PrimedSentence("c", "Charlie three.")
            )
        )
        advanceUntilIdle()
        assertEquals(listOf("Alpha one.", "Bravo two.", "Charlie three."), synth.calls)
        assertEquals(1, synth.maxActive)
    }

    @Test
    fun `speak of a primed id performs no new synthesis and reports the voice`() = runTest {
        val synth = FakeSynthesizer()
        val players = ArrayList<FakePlayer>()
        val listener = RecordingListener()
        val engine = newEngine(this, synth, FakeFocusGate(), players, newCache())
        engine.setListener(listener)
        engine.prime(listOf(PrimedSentence("a", "Alpha one.")))
        advanceUntilIdle()
        engine.speak("a", "Alpha one.")
        advanceUntilIdle()
        assertEquals(listOf("Alpha one."), synth.calls)
        assertEquals(listOf("a"), listener.started)
        assertEquals(
            listOf(VoiceStatus(VoiceSurface.ONLINE, voiceName = "Aria")),
            listener.statuses
        )
        assertEquals(1, players.size)
        assertTrue(players[0].loadedPath!!.endsWith(".mp3"))
    }

    @Test
    fun `speak of an in-flight id awaits the same job`() = runTest {
        val synth = FakeSynthesizer()
        val gate = CompletableDeferred<Unit>()
        synth.gate = gate
        val players = ArrayList<FakePlayer>()
        val listener = RecordingListener()
        val engine = newEngine(this, synth, FakeFocusGate(), players, newCache())
        engine.setListener(listener)
        engine.prime(listOf(PrimedSentence("a", "Alpha one.")))
        runCurrent()
        assertEquals(listOf("Alpha one."), synth.calls) // fetch started, blocked
        engine.speak("a", "Alpha one.")
        runCurrent()
        assertEquals(listOf("Alpha one."), synth.calls) // no second synthesis
        assertTrue(listener.started.isEmpty())
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("a"), listener.started)
        assertEquals(listOf("Alpha one."), synth.calls)
    }

    @Test
    fun `speak of an unprimed id fetches immediately`() = runTest {
        val synth = FakeSynthesizer()
        val players = ArrayList<FakePlayer>()
        val listener = RecordingListener()
        val engine = newEngine(this, synth, FakeFocusGate(), players, newCache())
        engine.setListener(listener)
        engine.speak("x", "Xray nine.")
        advanceUntilIdle()
        assertEquals(listOf("Xray nine."), synth.calls)
        assertEquals(listOf("x"), listener.started)
    }

    @Test
    fun `a failed prime is retried by speak, with no error surfaced for the prime`() = runTest {
        val synth = FakeSynthesizer()
        synth.failures += "Alpha one."
        val players = ArrayList<FakePlayer>()
        val listener = RecordingListener()
        val engine = newEngine(this, synth, FakeFocusGate(), players, newCache())
        engine.setListener(listener)
        engine.prime(listOf(PrimedSentence("a", "Alpha one.")))
        advanceUntilIdle()
        assertEquals(listOf("Alpha one."), synth.calls)
        assertTrue(listener.errors.isEmpty()) // no negative caching, no noise
        synth.failures -= "Alpha one."
        engine.speak("a", "Alpha one.")
        advanceUntilIdle()
        assertEquals(listOf("Alpha one.", "Alpha one."), synth.calls)
        assertEquals(listOf("a"), listener.started)
    }

    @Test
    fun `a speak whose fetch fails reports one error and never plays`() = runTest {
        val synth = FakeSynthesizer()
        synth.failures += "Bravo two."
        val players = ArrayList<FakePlayer>()
        val listener = RecordingListener()
        val engine = newEngine(this, synth, FakeFocusGate(), players, newCache())
        engine.setListener(listener)
        engine.speak("b", "Bravo two.")
        advanceUntilIdle()
        assertEquals(listOf("b"), listener.errors)
        assertTrue(players.isEmpty())
        assertTrue(listener.started.isEmpty())
    }

    @Test
    fun `stop drops queued primes`() = runTest {
        val synth = FakeSynthesizer()
        val gate = CompletableDeferred<Unit>()
        synth.gate = gate
        val players = ArrayList<FakePlayer>()
        val engine = newEngine(this, synth, FakeFocusGate(), players, newCache())
        engine.prime(
            listOf(
                PrimedSentence("a", "Alpha one."),
                PrimedSentence("b", "Bravo two."),
                PrimedSentence("c", "Charlie three.")
            )
        )
        runCurrent()
        assertEquals(listOf("Alpha one."), synth.calls)
        engine.stop()
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("Alpha one."), synth.calls)
    }

    @Test
    fun `setRate drops ready audio so the next speak re-synthesizes`() = runTest {
        val synth = FakeSynthesizer()
        val players = ArrayList<FakePlayer>()
        val listener = RecordingListener()
        val engine = newEngine(this, synth, FakeFocusGate(), players, newCache())
        engine.setListener(listener)
        engine.prime(listOf(PrimedSentence("a", "Alpha one.")))
        advanceUntilIdle()
        assertEquals(1, synth.calls.size)
        engine.setRate(1.5f)
        engine.speak("a", "Alpha one.")
        advanceUntilIdle()
        assertEquals(2, synth.calls.size)
        assertEquals(listOf("a"), listener.started)
    }

    @Test
    fun `chapter fill waits for listening, runs after primes, and stops at stop`() = runTest {
        val synth = FakeSynthesizer()
        val players = ArrayList<FakePlayer>()
        val listener = RecordingListener()
        val engine = newEngine(this, synth, FakeFocusGate(), players, newCache())
        engine.setListener(listener)
        engine.cacheChapter(listOf("Fill one.", "Fill two."))
        advanceUntilIdle()
        assertTrue(synth.calls.isEmpty()) // not listening yet: nothing fills
        engine.speak("x", "Xray nine.")
        advanceUntilIdle()
        // The controller re-offers the chapter after play; the
        // fill now runs behind the spoken sentence.
        engine.cacheChapter(listOf("Fill one.", "Fill two."))
        advanceUntilIdle()
        assertEquals(listOf("Xray nine.", "Fill one.", "Fill two."), synth.calls)
        // A fresh chapter offer after stop fills nothing.
        engine.stop()
        engine.cacheChapter(listOf("Fill three."))
        advanceUntilIdle()
        assertEquals(listOf("Xray nine.", "Fill one.", "Fill two."), synth.calls)
    }

    @Test
    fun `speak plays from the disk cache with no synthesis`() = runTest {
        val synth = FakeSynthesizer()
        val players = ArrayList<FakePlayer>()
        val listener = RecordingListener()
        val cache = newCache()
        val stored = cache.put(
            "en-US-AriaNeural", "+0%", "Alpha one.", byteArrayOf(1, 2, 3)
        )
        val engine = newEngine(this, synth, FakeFocusGate(), players, cache)
        engine.setListener(listener)
        engine.speak("a", "Alpha one.")
        advanceUntilIdle()
        assertTrue(synth.calls.isEmpty())
        assertEquals(listOf("a"), listener.started)
        assertEquals(stored.absolutePath, players[0].loadedPath)
    }
}
