package app.xreader.data.reader

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/**
 * One sentence in, MP3 bytes out; throws on any failure. The
 * seam that makes the prefetch pipeline JVM-testable
 * (PATCH-SPEC-EDGE-TTS §B.5): production is the WebSocket flow
 * below, tests substitute a scripted fake.
 */
internal interface EdgeSynthesizer {
    suspend fun synthesize(
        text: String,
        voice: EdgeVoiceTable.Voice,
        rate: Float
    ): ByteArray

    /** Cancels an in-flight socket, if any (stop() belt-and-braces
     * alongside job cancellation). */
    fun cancelActive() = Unit

    fun shutdown() = Unit
}

/** A synthesis failed (network, service refusal, empty audio). */
internal class EdgeSynthesisException(message: String) : Exception(message)

/**
 * The production synthesizer: the fork's verified WebSocket flow
 * (PATCH-SPEC-EDGE-TTS §B.1), one socket per sentence. The 403
 * clock-skew retry lives here: on a 403 with a server `Date`,
 * adopt the server's clock and retry the SAME sentence once; the
 * skew offset persists for the synthesizer's lifetime.
 */
internal class WebSocketEdgeSynthesizer(
    private val client: OkHttpClient
) : EdgeSynthesizer {

    @Volatile
    private var clockSkewMs = 0L

    @Volatile
    private var activeSocket: WebSocket? = null

    override suspend fun synthesize(
        text: String,
        voice: EdgeVoiceTable.Voice,
        rate: Float
    ): ByteArray {
        var retriedOnSkew = false
        while (true) {
            try {
                return attempt(text, voice, rate)
            } catch (signal: SkewSignal) {
                if (retriedOnSkew) {
                    throw EdgeSynthesisException("service refused after clock-skew retry")
                }
                retriedOnSkew = true
                clockSkewMs = signal.serverTimeMs - System.currentTimeMillis()
            }
        }
    }

    private class SkewSignal(val serverTimeMs: Long) : Exception()

    private suspend fun attempt(
        text: String,
        voice: EdgeVoiceTable.Voice,
        rate: Float
    ): ByteArray {
        val now = System.currentTimeMillis() + clockSkewMs
        val ssml = EdgeTtsProtocol.buildSsml(text, voice.language, voice.id, rate)
        val requestId = UUID.randomUUID().toString().replace("-", "")
        val url = EdgeTtsProtocol.WS_URL +
            "?TrustedClientToken=${EdgeTtsProtocol.TRUSTED_CLIENT_TOKEN}" +
            "&ConnectionId=$requestId" +
            "&Sec-MS-GEC=${EdgeTtsProtocol.secMsGec(now)}" +
            "&Sec-MS-GEC-Version=1-${EdgeTtsProtocol.CHROMIUM_FULL_VERSION}"
        val request = Request.Builder()
            .url(url)
            .header("Pragma", "no-cache")
            .header("Cache-Control", "no-cache")
            .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
            .header(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/${EdgeTtsProtocol.CHROMIUM_MAJOR_VERSION}.0.0.0 " +
                    "Safari/537.36 Edg/${EdgeTtsProtocol.CHROMIUM_MAJOR_VERSION}.0.0.0"
            )
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Cookie", "muid=" + UUID.randomUUID().toString().replace("-", "").uppercase())
            .build()

        return suspendCancellableCoroutine { cont ->
            val audio = ByteArrayOutputStream()
            var finished = false
            val socket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send(
                        EdgeTtsProtocol.speechConfigMessage(System.currentTimeMillis())
                    )
                    webSocket.send(
                        EdgeTtsProtocol.ssmlMessage(
                            requestId, ssml, System.currentTimeMillis()
                        )
                    )
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (text.contains("Path:turn.end") && !finished) {
                        finished = true
                        webSocket.close(1000, null)
                        val bytes = audio.toByteArray()
                        if (!cont.isActive) return
                        if (bytes.isEmpty()) {
                            cont.resumeWithException(
                                EdgeSynthesisException("empty audio at turn.end")
                            )
                        } else {
                            cont.resume(bytes)
                        }
                    }
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    EdgeTtsProtocol.audioPayload(bytes.toByteArray())?.let {
                        audio.write(it)
                    }
                }

                override fun onFailure(
                    webSocket: WebSocket,
                    t: Throwable,
                    response: Response?
                ) {
                    if (finished) return
                    finished = true
                    if (!cont.isActive) return
                    // A 403 with a server Date header usually means
                    // this phone's clock is off: signal the skew
                    // retry (the fork's behaviour).
                    val serverTime = if (response?.code == 403) {
                        EdgeTtsProtocol.parseHttpDate(response.header("Date"))
                    } else {
                        null
                    }
                    if (serverTime != null) {
                        cont.resumeWithException(SkewSignal(serverTime))
                    } else {
                        cont.resumeWithException(
                            EdgeSynthesisException(
                                "synthesis failed: ${t.message ?: "socket failure"}"
                            )
                        )
                    }
                }
            })
            activeSocket = socket
            cont.invokeOnCancellation { socket.cancel() }
        }
    }

    override fun cancelActive() {
        activeSocket?.cancel()
        activeSocket = null
    }

    override fun shutdown() {
        cancelActive()
        client.dispatcher.executorService.shutdown()
    }

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}

/** Audio-focus transitions the engine reacts to (spec §E.4). */
internal enum class FocusEvent { TRANSIENT_LOSS, GAIN, PERMANENT_LOSS }

/**
 * The audio-focus seam (spec §E.4): request focus for ONE
 * sentence, abandon it when the sentence ends. Speech is never
 * ducked — a transient loss pauses, a gain resumes, a permanent
 * loss interrupts. Behind an interface so the state machine is
 * JVM-testable with a fake.
 */
internal interface FocusGate {
    /** True while focus is held for the playing sentence. */
    val holdsFocus: Boolean

    var listener: ((FocusEvent) -> Unit)?

    /** Requests focus. False = REFUSED (e.g. a call is in
     * progress): the caller must not play, and reports an
     * interruption instead of talking over the call. */
    fun request(): Boolean

    fun abandon()
}

/** The Android implementation: API-26 [AudioFocusRequest] form
 * (minSdk is 26, so no legacy overload is needed). */
internal class AndroidFocusGate(context: Context) : FocusGate {

    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler = Handler(Looper.getMainLooper())
    private var focusRequest: AudioFocusRequest? = null

    override var listener: ((FocusEvent) -> Unit)? = null

    override val holdsFocus: Boolean get() = focusRequest != null

    private val changeListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK ->
                listener?.invoke(FocusEvent.TRANSIENT_LOSS)
            AudioManager.AUDIOFOCUS_GAIN -> listener?.invoke(FocusEvent.GAIN)
            AudioManager.AUDIOFOCUS_LOSS -> listener?.invoke(FocusEvent.PERMANENT_LOSS)
        }
    }

    override fun request(): Boolean {
        if (focusRequest != null) return true
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener(changeListener, handler)
            .build()
        val result = audioManager.requestAudioFocus(request)
        if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return false
        focusRequest = request
        return true
    }

    override fun abandon() {
        focusRequest?.let { held ->
            runCatching { audioManager.abandonAudioFocusRequest(held) }
        }
        focusRequest = null
    }
}

/**
 * One cache file's worth of playback — the player seam that
 * keeps the pipeline JVM-testable. Production wraps
 * [MediaPlayer]; tests substitute a scripted fake. Callbacks are
 * assigned before [load]; [load] prepares asynchronously and
 * fires [onPrepared] when ready.
 */
internal interface SentencePlayer {
    var onPrepared: (() -> Unit)?
    var onCompletion: (() -> Unit)?
    var onError: (() -> Unit)?
    val isPlaying: Boolean
    fun load(path: String)
    fun start()
    fun pause()
    fun stop()
    fun release()
}

/** The MediaPlayer implementation (the fork's play() shape:
 * speech audio attributes, async prepare). */
internal class MediaPlayerSentencePlayer : SentencePlayer {
    private val mp = MediaPlayer()

    override var onPrepared: (() -> Unit)? = null
    override var onCompletion: (() -> Unit)? = null
    override var onError: (() -> Unit)? = null
    override val isPlaying: Boolean
        get() = runCatching { mp.isPlaying }.getOrDefault(false)

    override fun load(path: String) {
        mp.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        mp.setDataSource(path)
        mp.setOnPreparedListener { onPrepared?.invoke() }
        mp.setOnCompletionListener { onCompletion?.invoke() }
        mp.setOnErrorListener { _, _, _ ->
            onError?.invoke()
            true
        }
        mp.prepareAsync()
    }

    override fun start() = mp.start()
    override fun pause() = mp.pause()
    override fun stop() {
        runCatching { mp.stop() }
    }
    override fun release() = mp.release()
}

/**
 * The Edge online voice engine (Patch 12): synthesis runs AHEAD
 * of playback through a sequential prefetch pipeline into the
 * on-disk listening cache (§C), so sentence boundaries have no
 * fetch gap; playback is MediaPlayer over the cache files, with
 * per-sentence audio focus (§E.4). Failures are per-utterance
 * [TtsEngine.Listener.onError] — failover to the device voice is
 * the ROUTER's job (§E.2), never this engine's.
 *
 * The pipeline core (queue / fetch / play decisions) sits behind
 * the [EdgeSynthesizer], [FocusGate] and [SentencePlayer] seams
 * so it is JVM-testable; the internal constructor is the test
 * entry.
 */
class EdgeTtsEngine internal constructor(
    private val scope: CoroutineScope,
    private val cache: TtsAudioCache,
    private val synthesizer: EdgeSynthesizer,
    private val focusGate: FocusGate,
    private val playerFactory: () -> SentencePlayer,
    private val voiceIdOverride: () -> String?,
    private val localeProvider: () -> Locale
) : TtsEngine {

    constructor(
        context: Context,
        scope: CoroutineScope,
        cache: TtsAudioCache,
        voiceIdOverride: () -> String? = { null }
    ) : this(
        scope = scope,
        cache = cache,
        synthesizer = WebSocketEdgeSynthesizer(WebSocketEdgeSynthesizer.defaultClient()),
        focusGate = AndroidFocusGate(context.applicationContext),
        playerFactory = { MediaPlayerSentencePlayer() },
        voiceIdOverride = voiceIdOverride,
        localeProvider = { Locale.getDefault() }
    )

    private var listener: TtsEngine.Listener? = null

    @Volatile
    private var rate: Float = 1f

    @Volatile
    private var generation = 0

    private val lock = Any()
    private val ready = HashMap<String, File>()
    private val inFlight = HashMap<String, Job>()
    private val queue = ArrayDeque<PrimedSentence>()
    private var workerJob: Job? = null

    /** Chapter-ahead fill state (§C.3): the list most recently
     * handed to [cacheChapter] and the fill cursor into it. */
    private var fillList: List<String> = emptyList()
    private var fillIndex = 0

    /** Fill runs only once the user is actually listening. */
    @Volatile
    private var hasSpoken = false

    private var player: SentencePlayer? = null

    @Volatile
    private var focusPaused = false

    // Edge needs no warm-up, so the engine is always ready.
    override val isReady: Boolean get() = true

    init {
        focusGate.listener = { event ->
            when (event) {
                FocusEvent.TRANSIENT_LOSS -> {
                    val mp = player
                    if (mp != null && mp.isPlaying) {
                        runCatching { mp.pause() }
                        focusPaused = true
                    }
                }
                FocusEvent.GAIN -> {
                    if (focusPaused) {
                        focusPaused = false
                        player?.let { runCatching { it.start() } }
                    }
                }
                FocusEvent.PERMANENT_LOSS -> {
                    stopPlayback()
                    focusGate.abandon()
                    listener?.onInterrupted()
                }
            }
        }
    }

    override fun setListener(listener: TtsEngine.Listener?) {
        this.listener = listener
    }

    override fun setRate(rate: Float) {
        this.rate = rate
        // Primed audio is at the old rate (rate is baked into the
        // SSML at fetch time): drop it. Disk entries at the old
        // rate simply miss under the new key and age out by LRU.
        synchronized(lock) {
            queue.clear()
            ready.clear()
            inFlight.values.forEach { it.cancel() }
            inFlight.clear()
        }
        workerJob?.cancel()
        workerJob = null
        if (hasSpoken) nudgeWorker()
    }

    override fun prime(sentences: List<PrimedSentence>) {
        if (sentences.isEmpty()) return
        var added = false
        synchronized(lock) {
            for (entry in sentences) {
                if (entry.utteranceId in ready || entry.utteranceId in inFlight) continue
                if (queue.any { it.utteranceId == entry.utteranceId }) continue
                val voice = resolveVoice(entry.text)
                val cached = cache.get(
                    voice.id, EdgeTtsProtocol.rateToProsody(rate), entry.text
                )
                if (cached != null) {
                    ready[entry.utteranceId] = cached
                    continue
                }
                queue.addLast(entry)
                added = true
            }
        }
        if (added) nudgeWorker()
    }

    override fun cacheChapter(sentences: List<String>) {
        fillList = sentences
        fillIndex = 0
        if (hasSpoken) nudgeWorker()
    }

    override fun speak(utteranceId: String, text: String) {
        hasSpoken = true
        stopPlayback()
        val gen = generation
        val voice = resolveVoice(text)
        synchronized(lock) { ready[utteranceId] }?.let { file ->
            playFile(gen, utteranceId, file, voice.displayName)
            return
        }
        val existing = synchronized(lock) { inFlight[utteranceId] }
        if (existing != null) {
            // Already fetching (a prime or the fill): await the
            // SAME job — no second synthesis.
            scope.launch {
                existing.join()
                if (gen != generation) return@launch
                val file = synchronized(lock) { ready[utteranceId] }
                if (file != null) {
                    playFile(gen, utteranceId, file, voice.displayName)
                } else {
                    fetchAndPlay(gen, utteranceId, text, voice.displayName)
                }
            }
            return
        }
        // Neither ready nor in flight: fetch NOW, ahead of the
        // queued primes.
        synchronized(lock) { queue.removeAll { it.utteranceId == utteranceId } }
        fetchAndPlay(gen, utteranceId, text, voice.displayName)
    }

    override fun stop() {
        generation++
        stopPlayback()
        focusGate.abandon()
        synchronized(lock) {
            queue.clear()
            ready.clear()
            inFlight.values.forEach { it.cancel() }
            inFlight.clear()
        }
        workerJob?.cancel()
        workerJob = null
        synthesizer.cancelActive()
        fillList = emptyList()
        fillIndex = 0
        hasSpoken = false
    }

    override fun shutdown() {
        stop()
        synthesizer.shutdown()
    }

    // -- Pipeline ---------------------------------------------------------------

    private fun resolveVoice(text: String): EdgeVoiceTable.Voice =
        EdgeVoiceTable.resolveVoice(text, localeProvider(), voiceIdOverride())

    private fun fetchAndPlay(gen: Int, utteranceId: String, text: String, voiceName: String) {
        val job = startFetch(PrimedSentence(utteranceId, text), registerReady = true)
        scope.launch {
            job?.join()
            if (gen != generation) return@launch
            val file = synchronized(lock) { ready[utteranceId] }
            if (file != null) {
                playFile(gen, utteranceId, file, voiceName)
            } else {
                listener?.onError(utteranceId)
            }
        }
    }

    /** Starts (or returns the running) fetch for [entry]. The job
     * unregisters itself from [inFlight] when it ends. */
    private fun startFetch(entry: PrimedSentence, registerReady: Boolean): Job? {
        synchronized(lock) {
            inFlight[entry.utteranceId]?.let { return it }
            if (registerReady && entry.utteranceId in ready) return null
        }
        val job = scope.launch { runFetch(entry, registerReady) }
        synchronized(lock) { inFlight[entry.utteranceId] = job }
        return job
    }

    private suspend fun runFetch(entry: PrimedSentence, registerReady: Boolean) {
        val self = currentCoroutineContext().job
        try {
            val voice = resolveVoice(entry.text)
            val prosody = EdgeTtsProtocol.rateToProsody(rate)
            val cached = cache.get(voice.id, prosody, entry.text)
            val gen = generation
            val file = if (cached != null) {
                cached
            } else {
                val bytes = synthesizer.synthesize(entry.text, voice, rate)
                // A stop() landed mid-synthesis: the audio belongs
                // to a chapter nobody is hearing any more.
                if (gen != generation) return
                cache.put(voice.id, prosody, entry.text, bytes)
            }
            if (registerReady) {
                synchronized(lock) { ready[entry.utteranceId] = file }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Nothing is recorded (no negative caching): speak()
            // for this id retries the fetch and surfaces a real
            // error if it fails again; a fill entry is skipped.
        } finally {
            synchronized(lock) {
                if (inFlight[entry.utteranceId] === self) {
                    inFlight.remove(entry.utteranceId)
                }
            }
        }
    }

    /** The sequential worker: drains the prime queue first (one
     * socket at a time), then fills the chapter-ahead cache while
     * the prime queue is empty. */
    private fun nudgeWorker() {
        if (workerJob?.isActive == true) return
        val gen = generation
        workerJob = scope.launch {
            while (gen == generation) {
                val entry = synchronized(lock) {
                    queue.firstOrNull {
                        it.utteranceId !in inFlight && it.utteranceId !in ready
                    }?.also { queue.remove(it) }
                }
                if (entry != null) {
                    startFetch(entry, registerReady = true)?.join()
                    continue
                }
                val fillText = claimNextFill() ?: break
                startFetch(
                    PrimedSentence(FILL_ID_PREFIX + fillText.hashCode(), fillText),
                    registerReady = false
                )?.join()
            }
        }
    }

    /** The next chapter sentence not yet in the disk cache under
     * the current (voice, rate) — or null when the fill is done
     * (or the user isn't listening yet). Existence is checked via
     * [TtsAudioCache.fileFor] so scanning never refreshes mtimes
     * the way a real hit does. */
    private fun claimNextFill(): String? {
        if (!hasSpoken) return null
        val prosody = EdgeTtsProtocol.rateToProsody(rate)
        while (fillIndex < fillList.size) {
            val text = fillList[fillIndex++]
            val voice = resolveVoice(text)
            val file = cache.fileFor(voice.id, prosody, text)
            if (!file.isFile || file.length() == 0L) return text
        }
        return null
    }

    // -- Playback -----------------------------------------------------------------

    private fun playFile(gen: Int, utteranceId: String, file: File, voiceName: String) {
        if (gen != generation) return
        // Focus is requested per sentence; a REFUSAL means
        // something more important owns the audio (a call) —
        // report an interruption, never talk over it.
        if (!focusGate.request()) {
            listener?.onInterrupted()
            return
        }
        val mp = playerFactory()
        player = mp
        cache.protectedFile = file
        focusPaused = false
        mp.onPrepared = {
            if (gen == generation) {
                listener?.onStart(utteranceId)
                listener?.onVoiceStatus(
                    VoiceStatus(VoiceSurface.ONLINE, voiceName = voiceName)
                )
                mp.start()
            }
        }
        mp.onCompletion = {
            if (cache.protectedFile == file) cache.protectedFile = null
            focusGate.abandon()
            if (gen == generation) listener?.onDone(utteranceId)
        }
        mp.onError = {
            if (cache.protectedFile == file) cache.protectedFile = null
            focusGate.abandon()
            if (gen == generation) listener?.onError(utteranceId)
        }
        try {
            mp.load(file.absolutePath)
        } catch (e: Exception) {
            if (cache.protectedFile == file) cache.protectedFile = null
            focusGate.abandon()
            if (gen == generation) listener?.onError(utteranceId)
        }
    }

    private fun stopPlayback() {
        player?.let { mp ->
            runCatching { mp.stop() }
            runCatching { mp.release() }
        }
        player = null
        cache.protectedFile = null
        focusPaused = false
    }

    private companion object {
        /** Chapter-fill fetches carry synthetic ids (they are
         * never spoken under them; the audio lands in the disk
         * cache keyed by content). */
        const val FILL_ID_PREFIX = "fill-"
    }
}
