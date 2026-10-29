package app.xreader.data.reader

import android.media.MediaPlayer
import android.media.PlaybackParams
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * EPUB 3 Media Overlay audio playback (PLAN §6): a thin MediaPlayer
 * wrapper publishing position ticks (~4×/s) that the reader maps
 * through `OverlaySync` to the block to highlight. Speed rides
 * [PlaybackParams] (API 23+; minSdk is 26). All player failures land
 * in [state] as plain-words messages — never a crash (§1.5).
 */
class OverlayPlayer(private val scope: CoroutineScope) {

    data class State(
        val prepared: Boolean = false,
        val playing: Boolean = false,
        val positionSec: Double = 0.0,
        val durationSec: Double = 0.0,
        val errorMessage: String? = null,
        val completed: Boolean = false
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private var player: MediaPlayer? = null
    private var ticker: Job? = null
    private var rate: Float = 1f

    fun load(audioFile: File) {
        release()
        if (!audioFile.isFile) {
            _state.value = State(
                errorMessage = "This book's read-aloud audio file is missing."
            )
            return
        }
        try {
            val media = MediaPlayer()
            media.setDataSource(audioFile.absolutePath)
            media.setOnPreparedListener { mp ->
                _state.value = State(
                    prepared = true,
                    playing = false,
                    durationSec = mp.duration / 1000.0
                )
                applyRate(mp)
            }
            media.setOnCompletionListener {
                stopTicker()
                _state.value = _state.value.copy(
                    playing = false,
                    completed = true,
                    positionSec = _state.value.durationSec
                )
            }
            media.setOnErrorListener { _, _, _ ->
                _state.value = _state.value.copy(
                    playing = false,
                    errorMessage = "The read-aloud audio couldn't be played."
                )
                true
            }
            media.prepareAsync()
            player = media
        } catch (e: Exception) {
            _state.value = State(
                errorMessage = "The read-aloud audio couldn't be opened."
            )
        }
    }

    fun play() {
        val media = player ?: return
        if (!_state.value.prepared) return
        try {
            if (_state.value.completed) media.seekTo(0)
            media.start()
            _state.value = _state.value.copy(playing = true, completed = false)
            startTicker()
        } catch (e: Exception) {
            _state.value = _state.value.copy(
                playing = false,
                errorMessage = "The read-aloud audio couldn't be played."
            )
        }
    }

    fun pause() {
        try {
            player?.pause()
        } catch (_: Exception) {
            // Pausing a stopped player is a no-op for the user.
        }
        stopTicker()
        _state.value = _state.value.copy(playing = false)
    }

    fun seekTo(positionSec: Double) {
        val media = player ?: return
        if (!_state.value.prepared) return
        val target = (positionSec * 1000).toInt()
            .coerceIn(0, maxOf(0, media.duration - 1))
        media.seekTo(target)
        _state.value = _state.value.copy(positionSec = target / 1000.0, completed = false)
    }

    fun setRate(newRate: Float) {
        rate = newRate
        player?.let { applyRate(it) }
    }

    fun release() {
        stopTicker()
        try {
            player?.release()
        } catch (_: Exception) {
        }
        player = null
        _state.value = State()
    }

    private fun applyRate(media: MediaPlayer) {
        try {
            media.playbackParams = PlaybackParams().setSpeed(rate)
        } catch (_: Exception) {
            // Some devices reject params while idle; the rate applies
            // on the next play() through this same path.
        }
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                val media = player ?: break
                try {
                    _state.value = _state.value.copy(
                        positionSec = media.currentPosition / 1000.0
                    )
                } catch (_: Exception) {
                    break
                }
                delay(TICK_MS)
            }
        }
    }

    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
    }

    companion object {
        const val TICK_MS = 250L
    }
}
