package app.xreader.data.download

import app.xreader.data.db.Chapter
import app.xreader.data.db.DownloadState

/**
 * v1.5.2 fix (FIX-8c/FIX-6a, §14.4/§12.10): display-layer truth for
 * chapter rows — shared by the book page (and any surface rendering
 * a chapter's count or mark) so suppression and marks can't drift.
 */
object ChapterTruth152 {

    /** A body whose plain text trips the login-wall/preview gate is
     * a STUB: it yields no rendered count anywhere (§14 kill
     * criterion), whatever its stored wordCount says. */
    fun isStubBody(plainText: String): Boolean =
        app.xreader.data.extension.ChapterServeGate
            .loginWallRefusal(plainText) != null ||
            app.xreader.data.extension.ChapterServeGate
                .refusalFor(plainText) != null

    /** The word count a surface may render for [chapter]: 0 when
     * the chapter's stored body is a known stub. */
    fun displayWordCount(chapter: Chapter, stubChapterIds: Set<Long>): Int =
        if (chapter.id in stubChapterIds) 0 else chapter.wordCount

    enum class RowMark { DOWNLOADED, CACHED, PARTIAL, NONE }

    /** §12.10 marks: green tick = USER+READY only; AUTOMATIC+READY
     * gets the distinct hollow CACHED mark; PARTIAL is temp. */
    fun rowMark(chapter: Chapter): RowMark = when {
        BodyOrigin152.isDownloaded(chapter) -> RowMark.DOWNLOADED
        chapter.downloadState == DownloadState.READY &&
            chapter.bodyOrigin == BodyOrigin152.AUTOMATIC -> RowMark.CACHED
        chapter.downloadState == DownloadState.PARTIAL -> RowMark.PARTIAL
        else -> RowMark.NONE
    }
}
