package app.xreader.data.ai

import app.xreader.core.error.AppError
import app.xreader.core.error.AppResult
import app.xreader.data.db.Book
import app.xreader.data.db.ChapterDao
import app.xreader.data.db.ReadingProgress
import app.xreader.data.db.ReadingProgressDao
import app.xreader.data.prefs.AiFeature
import app.xreader.data.prefs.AiSettings

/**
 * "Catch me up" (v1.0.3) — the spoiler-bounded recap for a reader
 * returning to a long novel after a break.
 *
 * The bound is the READING POSITION, not a chapter the user picked:
 * [CatchUpBounds.throughIndex] converts the saved progress into the
 * last chapter whose summary may be used. A chapter the reader is
 * only part-way through is EXCLUDED (its summary would describe its
 * ending) unless they are effectively at its end — the same hard
 * rule Lore Chat's context builder applies.
 *
 * Chapters in range that have no summary yet are summarised first,
 * with their text loaded through the shared ChapterTextLoader —
 * the stored copy, or the reader's own live fetch when a chapter
 * isn't on this device yet. A chapter whose text still can't be
 * read is counted and reported, never invented.
 */
object CatchUpBounds {

    /** Fraction of the current chapter read at which its summary is
     * treated as safe: the reader has essentially finished it. */
    const val FINISHED_FRACTION = 0.9f

    /**
     * The last chapter index whose summary CatchUp may use, or -1
     * when nothing is safe yet (no progress, or the reader is
     * part-way through chapter 1).
     */
    fun throughIndex(progress: ReadingProgress?, chapterCount: Int): Int {
        if (progress == null || chapterCount <= 0) return -1
        val current = progress.chapterIndex
        val candidate = if (progress.fraction >= FINISHED_FRACTION) {
            current
        } else {
            current - 1
        }
        return candidate.coerceIn(-1, chapterCount - 1)
    }
}

class CatchUp(
    private val summarizer: Summarizer,
    private val progressDao: ReadingProgressDao,
    private val chapterDao: ChapterDao,
    private val loader: app.xreader.data.download.ChapterTextLoader,
    private val settingsProvider: suspend () -> AiSettings
) {

    data class Result(
        val summary: Summarizer.Summary,
        /** Chapters whose summaries the recap covers. */
        val chaptersIncluded: Int,
        /** In-range chapters skipped: their text isn't on this
         * device, so no summary could be made or checked. */
        val chaptersSkipped: Int,
        /** The chapter index the bound landed on (for the UI line
         * "up to chapter N"). */
        val throughIndex: Int
    )

    suspend fun recap(book: Book): AppResult<Result> {
        if (!settingsProvider().enabled(AiFeature.SUMMARIZER)) {
            return AppResult.Err(
                AppError.NeutralVerdict(
                    "Chapter summaries are turned off — switch them on in " +
                        "Providers & keys."
                )
            )
        }
        val chapters = chapterDao.forBook(book.id)
        if (chapters.isEmpty()) {
            return AppResult.Err(
                AppError.NeutralVerdict("This book has no chapters yet.")
            )
        }
        val progress = progressDao.getForBook(book.id)
        val through = CatchUpBounds.throughIndex(progress, chapters.size)
        if (through < 0) {
            return AppResult.Err(
                AppError.NeutralVerdict(
                    "You haven't finished a chapter of this book yet — " +
                        "Catch me up works once there's a chapter behind you."
                )
            )
        }
        val eligible = chapters.filter { it.index <= through }
        var skipped = 0
        for (chapter in eligible) {
            // v1.0.6.2 (F9): text comes through the ONE loader —
            // the stored copy, or the reader's own live fetch
            // when the chapter isn't on the phone yet. A chapter
            // whose text is BLANK (an imported front-matter page)
            // is skipped without spending a summary call on it.
            val content = loader.loadContent(book.id, chapter)
            if (content == null || content.plainText().isBlank()) {
                skipped++
                continue
            }
            when (
                summarizer.chapterSummary(
                    book.id, book.title, chapter, content.plainText()
                )
            ) {
                is AppResult.Ok -> Unit
                is AppResult.Err -> {
                    // A chapter that can't be summarised (feature
                    // toggled mid-run, provider hiccup) counts as
                    // skipped ONLY if it has no earlier summary; if
                    // none of them work, the recap below will fail
                    // with the honest "no summaries yet" message.
                    skipped++
                }
            }
        }
        val throughChapter = eligible.last()
        return when (
            val recap = summarizer.bookSoFar(book.id, book.title, chapters, throughChapter)
        ) {
            is AppResult.Err -> recap
            is AppResult.Ok -> AppResult.Ok(
                Result(
                    summary = recap.value,
                    chaptersIncluded = eligible.size - skipped,
                    chaptersSkipped = skipped,
                    throughIndex = through
                )
            )
        }
    }
}
