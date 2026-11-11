package app.xreader.data.download

import app.xreader.data.db.Chapter
import app.xreader.data.db.DownloadState

/**
 * v1.5.2 (§12): the two-class storage law's pure core.
 * "Downloaded" has ONE definition everywhere (§12.9): origin USER +
 * state READY. Reading is cache (§12.2 caps per kind, LRU by
 * last-read, current+next of the open book pinned).
 */
object BodyOrigin152 {
    const val USER = "USER"
    const val AUTOMATIC = "AUTOMATIC"

    const val TEXT_CAP_BYTES: Long = 20L * 1024 * 1024
    const val IMAGE_CAP_BYTES: Long = 100L * 1024 * 1024

    fun isDownloaded(chapter: Chapter): Boolean =
        chapter.bodyOrigin == USER && chapter.downloadState == DownloadState.READY

    fun isCachedBody(chapter: Chapter): Boolean =
        chapter.contentPath != null && chapter.bodyOrigin == AUTOMATIC

    fun isPartial(chapter: Chapter): Boolean =
        chapter.downloadState == DownloadState.DOWNLOADING ||
            chapter.downloadState == DownloadState.QUEUED

    data class BodyEntry(
        val chapterId: Long,
        val bytes: Long,
        val lastReadAt: Long,
        val pinned: Boolean = false,
        val evictionExempt: Boolean = false
    )

    /**
     * LRU eviction plan for one kind's cache: oldest-read first until
     * the total fits [capBytes]. Pinned and eviction-exempt (§8.3:
     * absent / needs-repair providers) entries never appear.
     */
    fun evictionPlan(entries: List<BodyEntry>, capBytes: Long): List<Long> {
        // v1.5.2 fix (FIX-6e, §12.7): EXEMPT bodies stand OUTSIDE
        // the managed cache — they are never victims AND they do
        // not count toward the cap total. Counting them made the
        // cap unreachable whenever a kept needs-repair book held
        // more than the cap, and punished every ordinary book by
        // evicting its whole cache against someone else's bytes
        // (the §10 pass-composition fixture's exact shape).
        var total = entries.filter { !it.evictionExempt }.sumOf { it.bytes }
        if (total <= capBytes) return emptyList()
        val victims = entries
            .filter { !it.pinned && !it.evictionExempt }
            .sortedBy { it.lastReadAt }
        val plan = mutableListOf<Long>()
        for (victim in victims) {
            if (total <= capBytes) break
            plan += victim.chapterId
            total -= victim.bytes
        }
        return plan
    }

    /** §12.6 one-time heuristic: a pre-v1.5.2 READY body (origin null)
     * demotes to AUTOMATIC — status only, bodies kept. */
    fun demotedOrigin(chapter: Chapter): String? =
        if (chapter.bodyOrigin == null && chapter.contentPath != null) AUTOMATIC
        else chapter.bodyOrigin
}
