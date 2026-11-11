package app.xreader.ui.localreader

import app.xreader.domain.content.ChapterBlock
import app.xreader.domain.content.ChapterContent
import app.xreader.domain.content.TextSpan
import app.xreader.domain.reader.ChapterText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * v1.5.8 (H6): speech for PDF — the persisted strip fed through
 * the EXISTING ReadAloudController (the EPUB seam; no new TTS
 * machinery). Page-anchored: speaking starts at the reader's
 * current page and advances page by page as each page's speech
 * finishes.
 *
 * The controller is reached through the minimal [SpeechSink]
 * seam so the feeder is testable against a fake: the production
 * sink IS the app's ReadAloudController (loadChapter + play).
 */
interface SpeechSink {
    fun loadChapter(text: ChapterText)
    fun play()
}

class PdfStripSpeech(
    private val textAt: suspend (Int) -> String,
    private val pageCount: Int,
    private val sink: SpeechSink,
    private val scope: CoroutineScope,
    private val onNoText: (page: Int) -> Unit = {}
) {
    @Volatile var currentPage: Int = -1
        private set

    @Volatile private var active = false

    /** Begin speaking at [page] (the reader's current page). */
    fun startAt(page: Int) {
        active = true
        speak(page.coerceIn(0, (pageCount - 1).coerceAtLeast(0)))
    }

    /** The sink's chapter finished: advance one page, or settle
     * at the end of the book. */
    fun onChapterFinished() {
        if (!active) return
        val next = currentPage + 1
        if (next in 0 until pageCount) {
            speak(next)
        } else {
            active = false
            currentPage = -1
        }
    }

    fun stop() {
        active = false
        currentPage = -1
    }

    private fun speak(page: Int) {
        scope.launch {
            if (!active) return@launch
            val text = runCatching { textAt(page) }.getOrDefault("")
            if (!active) return@launch
            if (text.isBlank()) {
                onNoText(page)
                // A textless page (a plate) is skipped, not a
                // dead end: keep advancing while pages remain.
                currentPage = page
                onChapterFinished()
                return@launch
            }
            currentPage = page
            sink.loadChapter(chapterTextOf(text))
            sink.play()
        }
    }

    companion object {
        /** One page's strip text as the controller's chapter
         * shape: paragraphs from the page's lines, the same
         * shaping the EPUB seam applies to a section. */
        fun chapterTextOf(pageText: String): ChapterText {
            val blocks = pageText.split("\n\n", "\n")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { line ->
                    ChapterBlock.Paragraph(
                        spans = listOf(TextSpan(text = line))
                    ) as ChapterBlock
                }
            return ChapterText(ChapterContent(blocks = blocks))
        }

        /** The plain text [chapterTextOf] carries, in order —
         * the sequence a test asserts against the sink. */
        fun plainTextOf(text: ChapterText): List<String> =
            text.content.blocks.filterIsInstance<ChapterBlock.Paragraph>()
                .map { paragraph -> paragraph.spans.joinToString("") { it.text } }
    }
}
