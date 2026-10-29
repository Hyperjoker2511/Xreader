package app.xreader.data.reader

import android.content.Context
import android.media.MediaPlayer
import app.xreader.domain.reader.Soundscape

/**
 * Focus soundscapes (PLAN §6): looping playback of the bundled,
 * self-generated ambient WAVs in `assets/soundscapes/` (rain,
 * fireplace, forest — see P4-REPORT for how they were made and their
 * size budget). Playback failure is silent by design: a focus session
 * never depends on its soundtrack.
 */
class SoundscapePlayer(private val context: Context) {

    private var player: MediaPlayer? = null
    var current: Soundscape = Soundscape.NONE
        private set

    fun play(soundscape: Soundscape) {
        if (soundscape == Soundscape.NONE) {
            stop()
            return
        }
        if (soundscape == current && player != null) return
        stop()
        try {
            val asset = when (soundscape) {
                Soundscape.RAIN -> "soundscapes/rain.wav"
                Soundscape.FIREPLACE -> "soundscapes/fireplace.wav"
                Soundscape.FOREST -> "soundscapes/forest.wav"
                Soundscape.NONE -> return
            }
            val media = MediaPlayer()
            context.assets.openFd(asset).use { fd ->
                media.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
            }
            media.isLooping = true
            media.setVolume(0.7f, 0.7f)
            media.prepare()
            media.start()
            player = media
            current = soundscape
        } catch (e: Exception) {
            // The timer carries on without sound.
            player = null
            current = Soundscape.NONE
        }
    }

    fun stop() {
        try {
            player?.stop()
            player?.release()
        } catch (_: Exception) {
        }
        player = null
        current = Soundscape.NONE
    }
}
