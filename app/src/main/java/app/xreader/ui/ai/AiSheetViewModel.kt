package app.xreader.ui.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.xreader.core.error.AppResult
import app.xreader.core.error.ErrorText
import app.xreader.data.ai.LanguageDetect
import app.xreader.data.ai.LoreChatContext
import app.xreader.data.ai.RawTranslator
import app.xreader.data.ai.ReadingPosition
import app.xreader.data.ai.Summarizer
import app.xreader.data.db.AiSummary
import app.xreader.data.prefs.AiFeature
import app.xreader.di.AppContainer
import app.xreader.domain.content.ChapterContent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ChatMessage(
    val fromUser: Boolean,
    val text: String
)

data class AiSheetUiState(
    val hasKey: Boolean = false,
    val featuresOn: Set<AiFeature> = emptySet(),
    // Summary
    val summary: AiSummary? = null,
    val summarizing: Boolean = false,
    val summaryError: String? = null,
    /** v1.0.6.2 (F9): a calm verdict (not a failure) for the
     * summary section — rendered as a note, never red. */
    val summaryNote: String? = null,
    // Catch-up
    val catchUp: String? = null,
    val catchingUp: Boolean = false,
    val catchUpError: String? = null,
    val catchUpNote: String? = null,
    // Chat
    val chat: List<ChatMessage> = emptyList(),
    val chatBusy: Boolean = false,
    val chatError: String? = null,
    // Translation
    val detectedLanguage: String = "Detecting…",
    val translationExists: Boolean = false,
    val translating: Boolean = false,
    val translationPreview: String = "",
    val translationError: String? = null,
    /** v1.0.6.2 (F9): "already in English" and friends — a calm
     * one-line note, not an error. */
    val translationNote: String? = null,
    // Art
    val illustrating: Boolean = false,
    val illustrateMessage: String? = null
)

/**
 * The reader's "Ask AI" sheet brain (PLAN §7, ai-sheet design).
 * Bound to the chapter on screen via [bind]; every answer comes
 * from the guarded services — chat only ever sees chapters up to
 * the reader's position (the spoiler guard lives in
 * [LoreChatContext]).
 */
class AiSheetViewModel(
    private val container: AppContainer,
    private val bookId: Long
) : ViewModel() {

    private val _state = MutableStateFlow(AiSheetUiState())
    val state: StateFlow<AiSheetUiState> = _state

    /** Emitted with the chapter id when a translation finishes, so
     * the reader can reload onto the translated text. */
    private val _translationDone = MutableSharedFlow<Long>(extraBufferCapacity = 2)
    val translationDone: SharedFlow<Long> = _translationDone

    private var boundChapterId: Long = -1L
    private var boundChapterIndex: Int = 0
    private var boundCharOffset: Int = 0
    private var boundContent: ChapterContent? = null
    private var boundLanguage: app.xreader.data.ai.SourceLanguage =
        app.xreader.data.ai.SourceLanguage.OTHER
    private var summaryJob: kotlinx.coroutines.Job? = null

    /** The engine chip's text (v1.0.3): which provider AI features
     * will use here — "AI · Automatic" or the specific pick. */
    val engineLabel: StateFlow<String> = container.aiHub.chipLabel
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "AI · Automatic")

    init {
        viewModelScope.launch {
            // v1.0.3: "has a key" means the CURRENT pick can run —
            // any configured provider under Automatic, or the picked
            // provider under a specific pick (hub routing decides).
            combine(
                container.aiHub.statesFlow(),
                container.aiPrefs.settings
            ) { states, settings ->
                app.xreader.data.ai.hub.EngineRouting
                    .order(settings.enginePick, states)
                    .any { it.configured }
            }.collectLatest { usable ->
                _state.value = _state.value.copy(hasKey = usable)
            }
        }
        viewModelScope.launch {
            container.aiPrefs.settings.collectLatest { settings ->
                _state.value = _state.value.copy(
                    featuresOn = settings.features.filterValues { it }.keys
                )
            }
        }
    }

    fun bind(chapterIndex: Int, chapterId: Long, charOffset: Int) {
        boundChapterIndex = chapterIndex
        boundCharOffset = charOffset
        if (chapterId == boundChapterId && boundContent != null) return
        boundChapterId = chapterId
        boundContent = null
        _state.value = _state.value.copy(
            summary = null,
            summaryError = null,
            translationPreview = "",
            translationError = null,
            detectedLanguage = "Detecting…"
        )
        summaryJob?.cancel()
        summaryJob = viewModelScope.launch {
            container.database.aiSummaryDao().observe(bookId, chapterId)
                .collectLatest { summary ->
                    if (summary != null) {
                        _state.value = _state.value.copy(summary = summary)
                    }
                }
        }
        viewModelScope.launch {
            // v1.0.6.2 (F9): the stored copy via the one loader
            // (bind never fetches — the reader already brought
            // this chapter to the phone to display it).
            val content = container.chapterTextLoader
                .storedContent(bookId, chapterId)
            if (content != null && boundChapterId == chapterId) {
                boundContent = content
                val detected = LanguageDetect.detect(content.plainText())
                boundLanguage = detected
                _state.value = _state.value.copy(
                    detectedLanguage = detected.label,
                    translationExists = container.bookStorage
                        .hasTranslatedContent(bookId, chapterId)
                )
            }
        }
    }

    private suspend fun contentOrLoad(): ChapterContent? {
        boundContent?.let { return it }
        // v1.0.6.2 (F9): THE content resolution — stored copy, or
        // the reader's own live fetch through the download queue
        // when the chapter isn't on the phone yet. A feature can
        // no longer fail to see a chapter the reader can show.
        val chapter = container.database.chapterDao()
            .getById(boundChapterId) ?: return null
        val loaded = container.chapterTextLoader
            .loadContent(bookId, chapter)
        boundContent = loaded
        return loaded
    }

    // -- Summary -----------------------------------------------------------------

    fun summarizeChapter() {
        if (_state.value.summarizing) return
        _state.value = _state.value.copy(
            summarizing = true, summaryError = null, summaryNote = null
        )
        viewModelScope.launch {
            val content = contentOrLoad()
            val chapter = container.database.chapterDao().getById(boundChapterId)
            val book = container.database.bookDao().getById(bookId)
            if (content == null || chapter == null || book == null) {
                _state.value = _state.value.copy(
                    summarizing = false,
                    summaryError = "This chapter's text isn't available."
                )
                return@launch
            }
            when (
                val result = container.summarizer.chapterSummary(
                    book.id, book.title, chapter, content.plainText()
                )
            ) {
                // The row lands in Room and the bind() collector
                // surfaces it; nothing more to do here.
                is AppResult.Ok -> _state.value = _state.value.copy(
                    summarizing = false
                )
                is AppResult.Err -> _state.value =
                    if (result.error is app.xreader.core.error.AppError.NeutralVerdict) {
                        _state.value.copy(
                            summarizing = false,
                            summaryNote = result.error.message
                        )
                    } else {
                        _state.value.copy(
                            summarizing = false,
                            summaryError = ErrorText.displayText(result.error)
                        )
                    }
            }
        }
    }

    fun catchUp() {
        if (_state.value.catchingUp) return
        _state.value = _state.value.copy(
            catchingUp = true, catchUpError = null, catchUpNote = null
        )
        viewModelScope.launch {
            val book = container.database.bookDao().getById(bookId)
            val chapters = container.database.chapterDao().forBook(bookId)
            val through = chapters.getOrNull(boundChapterIndex)
            if (book == null || through == null) {
                _state.value = _state.value.copy(
                    catchingUp = false,
                    catchUpError = "This book isn't available."
                )
                return@launch
            }
            when (
                val result = container.summarizer.bookSoFar(
                    book.id, book.title, chapters, through
                )
            ) {
                is AppResult.Ok -> _state.value = _state.value.copy(
                    catchingUp = false,
                    catchUp = result.value.bullets.joinToString("\n") { "• $it" }
                )
                is AppResult.Err -> _state.value =
                    if (result.error is app.xreader.core.error.AppError.NeutralVerdict) {
                        _state.value.copy(
                            catchingUp = false,
                            catchUpNote = result.error.message
                        )
                    } else {
                        _state.value.copy(
                            catchingUp = false,
                            catchUpError = ErrorText.displayText(result.error)
                        )
                    }
            }
        }
    }

    // -- Chat ----------------------------------------------------------------------

    fun sendChat(question: String) {
        val trimmed = question.trim()
        if (trimmed.isBlank() || _state.value.chatBusy) return
        val history = _state.value.chat
        _state.value = _state.value.copy(
            chat = history + ChatMessage(true, trimmed) + ChatMessage(false, ""),
            chatBusy = true,
            chatError = null
        )
        viewModelScope.launch {
            val book = container.database.bookDao().getById(bookId)
            if (book == null) {
                finishChatWithError("This book isn't available.")
                return@launch
            }
            val chapters = container.database.chapterDao().forBook(bookId)
            val summaries = chapters.mapNotNull { chapter ->
                container.database.aiSummaryDao().get(bookId, chapter.id)
                    ?.let { summary ->
                        chapter.index to Summarizer.decodeBullets(summary.bulletsJson)
                    }
            }
            val lore = container.database.loreEntityDao().forBook(bookId)
            val content = contentOrLoad()
            // v1.0.6.2 (F9): if there is genuinely nothing for the
            // chat to go on, SAY SO here in plain words — never
            // send an empty brief and let the model confess it.
            if (!LoreChatContext.hasMaterial(
                    lore = lore,
                    summariesBefore = summaries,
                    currentChapterText = content?.plainText() ?: "",
                    position = ReadingPosition(boundChapterIndex, boundCharOffset)
                )
            ) {
                val messages = _state.value.chat.toMutableList()
                val lastIndex = messages.indexOfLast { !it.fromUser }
                if (lastIndex >= 0) {
                    messages[lastIndex] = messages[lastIndex].copy(
                        text = "I don't have anything to go on yet for " +
                            "this book — read a little of this chapter " +
                            "first, or summarise it, and then I can " +
                            "answer from the story so far."
                    )
                }
                _state.value = _state.value.copy(
                    chat = messages,
                    chatBusy = false
                )
                return@launch
            }
            val context = LoreChatContext.build(
                bookTitle = book.title,
                lore = lore,
                summariesBefore = summaries,
                currentChapterText = content?.plainText() ?: "",
                position = ReadingPosition(boundChapterIndex, boundCharOffset)
            )
            // Pair the visible transcript into exchanges for the
            // prompt's bounded history.
            val exchanges = mutableListOf<app.xreader.data.ai.LoreChat.Exchange>()
            var pendingQuestion: String? = null
            for (message in history) {
                if (message.fromUser) {
                    pendingQuestion = message.text
                } else if (pendingQuestion != null && message.text.isNotBlank()) {
                    exchanges += app.xreader.data.ai.LoreChat.Exchange(
                        pendingQuestion, message.text
                    )
                    pendingQuestion = null
                }
            }
            container.loreChat.ask(context, exchanges, trimmed).collect { event ->
                when (event) {
                    is app.xreader.data.ai.GeminiClient.StreamEvent.Delta ->
                        appendToLastAssistant(event.text)
                    is app.xreader.data.ai.GeminiClient.StreamEvent.Done -> {
                        _state.value = _state.value.copy(chatBusy = false)
                        // Phase 6: the first completed Lore Chat is
                        // an achievement fact — record it (idempotent).
                        container.gamePrefs.setLoreChatUsed()
                        runCatching { container.expService.settle() }
                    }
                    is app.xreader.data.ai.GeminiClient.StreamEvent.Failed ->
                        finishChatWithError(ErrorText.displayText(event.error))
                }
            }
            if (_state.value.chatBusy) {
                _state.value = _state.value.copy(chatBusy = false)
            }
        }
    }

    private fun appendToLastAssistant(delta: String) {
        val messages = _state.value.chat.toMutableList()
        val lastIndex = messages.indexOfLast { !it.fromUser }
        if (lastIndex < 0) return
        messages[lastIndex] = messages[lastIndex].copy(
            text = messages[lastIndex].text + delta
        )
        _state.value = _state.value.copy(chat = messages)
    }

    private fun finishChatWithError(message: String) {
        val messages = _state.value.chat.toMutableList()
        val lastIndex = messages.indexOfLast { !it.fromUser }
        if (lastIndex >= 0 && messages[lastIndex].text.isBlank()) {
            messages.removeAt(lastIndex)
        }
        _state.value = _state.value.copy(
            chat = messages,
            chatBusy = false,
            chatError = message
        )
    }

    // -- RAW translation ------------------------------------------------------------

    fun translateChapter() {
        if (_state.value.translating) return
        _state.value = _state.value.copy(
            translating = true,
            translationError = null,
            translationNote = null,
            translationPreview = ""
        )
        viewModelScope.launch {
            val content = contentOrLoad()
            val chapter = container.database.chapterDao().getById(boundChapterId)
            val book = container.database.bookDao().getById(bookId)
            if (content == null || chapter == null || book == null) {
                _state.value = _state.value.copy(
                    translating = false,
                    translationError = "This chapter's text isn't available."
                )
                return@launch
            }
            val glossary = container.database.terminologyDao().forBook(bookId)
            val loreNames = container.database.loreEntityDao().forBook(bookId)
                .map { it.name }
            container.rawTranslator.translate(
                book, chapter, content, glossary, loreNames, boundLanguage
            ).collect { event ->
                when (event) {
                    is RawTranslator.Event.Progress -> _state.value =
                        _state.value.copy(
                            translationPreview = event.translatedText
                                .takeLast(PREVIEW_CHARS)
                        )
                    is RawTranslator.Event.Done -> {
                        _state.value = _state.value.copy(
                            translating = false,
                            translationExists = true,
                            translationPreview = ""
                        )
                        _translationDone.emit(boundChapterId)
                    }
                    is RawTranslator.Event.Failed -> _state.value =
                        if (event.error is
                            app.xreader.core.error.AppError.NeutralVerdict
                        ) {
                            _state.value.copy(
                                translating = false,
                                translationNote = event.error.message
                            )
                        } else {
                            _state.value.copy(
                                translating = false,
                                translationError = ErrorText.displayText(event.error)
                            )
                        }
                }
            }
        }
    }

    // -- Concept art ------------------------------------------------------------------

    fun illustrateMoment() {
        if (_state.value.illustrating) return
        _state.value = _state.value.copy(
            illustrating = true,
            illustrateMessage = null
        )
        viewModelScope.launch {
            val content = contentOrLoad()
            val book = container.database.bookDao().getById(bookId)
            val chapter = container.database.chapterDao().getById(boundChapterId)
            val passage = content?.blocks
                ?.filterIsInstance<app.xreader.domain.content.ChapterBlock.Paragraph>()
                ?.firstOrNull { it.text.length > 80 }?.text
            if (content == null || book == null || chapter == null || passage == null) {
                _state.value = _state.value.copy(
                    illustrating = false,
                    illustrateMessage = "This chapter has no passage to illustrate yet."
                )
                return@launch
            }
            when (
                val result = container.conceptArt.generateForPassage(book, chapter, passage)
            ) {
                is AppResult.Ok -> _state.value = _state.value.copy(
                    illustrating = false,
                    illustrateMessage = "Saved — see it in the book's Lore & Art."
                )
                is AppResult.Err -> _state.value = _state.value.copy(
                    illustrating = false,
                    // v1.0.6.2 (F9): a verdict (feature off) is
                    // its own calm sentence; a real failure names
                    // what failed under the plain lead-in.
                    illustrateMessage = if (
                        result.error is
                        app.xreader.core.error.AppError.NeutralVerdict
                    ) {
                        result.error.message
                    } else {
                        "Couldn't paint this moment.\n" +
                            ErrorText.displayText(result.error)
                    }
                )
            }
        }
    }

    companion object {
        private const val PREVIEW_CHARS = 900

        fun factory(container: AppContainer, bookId: Long): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    AiSheetViewModel(container, bookId) as T
            }
    }
}
