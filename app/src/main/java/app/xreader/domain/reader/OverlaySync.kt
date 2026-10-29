package app.xreader.domain.reader

import app.xreader.domain.content.MediaClip

/**
 * EPUB 3 Media Overlay synchronisation (PLAN §4 model, §6 playback):
 * maps a playback position to the clip (and therefore the text block)
 * that should be highlighted. Clips are sorted once; lookups are
 * binary searches. Pure — unit-tested against the timing model.
 *
 * Highlight rule: the active clip is the last clip that has begun; a
 * position inside a gap between clips keeps the previous clip lit
 * (read-aloud convention — the eye stays on the last spoken words),
 * and a position before the first clip lights nothing.
 */
class OverlaySync(clips: List<MediaClip>) {

    val clips: List<MediaClip> = clips.sortedBy { it.beginSec }

    private val byTextId: Map<String, MediaClip> =
        this.clips.associateBy { it.textId }

    val durationSec: Double get() = clips.lastOrNull()?.endSec ?: 0.0
    val isEmpty: Boolean get() = clips.isEmpty()

    /** Index of the active clip at [positionSec], or -1 before the first. */
    fun clipIndexAt(positionSec: Double): Int {
        if (clips.isEmpty() || positionSec < clips.first().beginSec) return -1
        var lo = 0
        var hi = clips.lastIndex
        var result = 0
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (clips[mid].beginSec <= positionSec) {
                result = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return result
    }

    fun clipAt(positionSec: Double): MediaClip? =
        clips.getOrNull(clipIndexAt(positionSec))

    /** The XHTML element id to highlight at [positionSec], if any. */
    fun textIdAt(positionSec: Double): String? = clipAt(positionSec)?.textId

    fun clipForTextId(textId: String): MediaClip? = byTextId[textId]

    /** The clip after the one active at [positionSec] (skip target). */
    fun nextClip(positionSec: Double): MediaClip? {
        val index = clipIndexAt(positionSec)
        return clips.getOrNull(if (index < 0) 0 else index + 1)
    }

    /** The clip before the one active at [positionSec] (skip target). */
    fun previousClip(positionSec: Double): MediaClip? {
        val index = clipIndexAt(positionSec)
        return if (index <= 0) null else clips[index - 1]
    }
}
