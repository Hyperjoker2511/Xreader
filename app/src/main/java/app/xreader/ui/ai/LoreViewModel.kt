package app.xreader.ui.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.xreader.core.error.AppResult
import app.xreader.core.error.ErrorText
import app.xreader.data.ai.LoreMerger
import app.xreader.data.db.Book
import app.xreader.data.db.LoreArt
import app.xreader.data.db.LoreEntity
import app.xreader.data.db.LoreRelation
import app.xreader.data.db.LoreType
import app.xreader.data.db.TerminologyEntry
import app.xreader.data.sync.LoreRecord
import app.xreader.data.sync.TermRecord
import app.xreader.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class LoreUiState(
    val book: Book? = null,
    val entities: List<LoreEntity> = emptyList(),
    val relations: List<LoreRelation> = emptyList(),
    val art: List<LoreArt> = emptyList(),
    val terms: List<TerminologyEntry> = emptyList(),
    val hasKey: Boolean = false,
    val building: Boolean = false,
    val buildProgress: String = "",
    val artBusyFor: Long? = null,
    val message: String? = null,
    val messageIsError: Boolean = false
)

/**
 * The per-book Lore screen (PLAN §7, screen #20): the LoreKeeper
 * knowledge base — viewable, editable (user edits lock and win over
 * re-extraction), with the relationship map, concept art, and the
 * book's translation glossary.
 */
class LoreViewModel(
    private val container: AppContainer,
    private val bookId: Long
) : ViewModel() {

    private val local = MutableStateFlow(LoreUiState())

    private data class Core(
        val book: Book?,
        val entities: List<LoreEntity>,
        val relations: List<LoreRelation>,
        val art: List<LoreArt>,
        val terms: List<TerminologyEntry>
    )

    val state: StateFlow<LoreUiState> = combine(
        combine(
            container.database.bookDao().observeById(bookId),
            container.database.loreEntityDao().observeForBook(bookId),
            container.database.loreRelationDao().observeForBook(bookId),
            container.database.loreArtDao().observeForBook(bookId),
            container.database.terminologyDao().observeForBook(bookId)
        ) { book, entities, relations, art, terms ->
            Core(book, entities, relations, art, terms)
        },
        combine(
            container.aiHub.statesFlow(),
            container.aiPrefs.settings
        ) { states, settings ->
            // v1.0.3: any configured engine under the current pick
            // can build lore (concept art still needs Gemini — the
            // hub says so plainly when it is asked without one).
            app.xreader.data.ai.hub.EngineRouting
                .order(settings.enginePick, states)
                .any { it.configured }
        },
        local
    ) { core, engineUsable, localState ->
        LoreUiState(
            book = core.book,
            entities = core.entities,
            relations = core.relations,
            art = core.art,
            terms = core.terms,
            hasKey = engineUsable,
            building = localState.building,
            buildProgress = localState.buildProgress,
            artBusyFor = localState.artBusyFor,
            message = localState.message,
            messageIsError = localState.messageIsError
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LoreUiState())

    /** The engine chip's text (v1.0.3) for the Lore screen. */
    val engineLabel: StateFlow<String> = container.aiHub.chipLabel
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "AI · Automatic")

    private fun setMessage(text: String?, isError: Boolean = false) {
        local.value = local.value.copy(message = text, messageIsError = isError)
    }

    /** Extracts lore from every chapter that has text on this device
     * and hasn't been extracted yet (the keeper skips those). */
    fun buildLore() {
        if (local.value.building) return
        local.value = local.value.copy(building = true, buildProgress = "", message = null)
        viewModelScope.launch {
            val book = container.database.bookDao().getById(bookId)
            if (book == null) {
                local.value = local.value.copy(building = false)
                return@launch
            }
            val chapters = container.database.chapterDao().forBook(bookId)
                .filter { it.contentPath != null }
            var done = 0
            var touched = 0
            var failure: String? = null
            for (chapter in chapters) {
                local.value = local.value.copy(
                    buildProgress = "Chapter ${chapter.index + 1} of ${chapters.size}…"
                )
                // v1.0.6.2 (F9): the stored copy via the one
                // loader (this pass is on-device chapters only,
                // so no fetch — the same accessor either way).
                val content = container.chapterTextLoader
                    .storedContent(bookId, chapter.id) ?: continue
                when (
                    val result = container.loreKeeper.extractForChapter(
                        book, chapter, content.plainText()
                    )
                ) {
                    is AppResult.Ok -> {
                        done++
                        touched += result.value
                    }
                    is AppResult.Err -> {
                        failure = ErrorText.displayText(result.error)
                        break
                    }
                }
            }
            local.value = local.value.copy(building = false, buildProgress = "")
            when {
                failure != null -> setMessage(failure, isError = true)
                done == 0 -> setMessage(
                    "No chapter text is on this device yet — download or read a " +
                        "chapter first, then build the lore."
                )
                touched == 0 -> setMessage(
                    "Lore is already up to date for the chapters on this device."
                )
                else -> setMessage("Lore updated from $done chapter(s).")
            }
        }
    }

    /** User edits win: saving sets the lock so re-extraction can
     * never overwrite them (§1.3). */
    fun saveEntity(entity: LoreEntity, name: String, description: String) {
        viewModelScope.launch {
            container.database.loreEntityDao().update(
                entity.copy(
                    name = name.trim().ifBlank { entity.name },
                    description = description.trim(),
                    userLocked = true,
                    updatedAt = System.currentTimeMillis()
                )
            )
            setMessage("Saved — your version is locked against automatic updates.")
        }
    }

    fun addEntity(name: String, type: LoreType, description: String) {
        viewModelScope.launch {
            container.database.loreEntityDao().insert(
                LoreEntity(
                    bookId = bookId,
                    type = type,
                    name = name.trim(),
                    description = description.trim(),
                    firstChapterIndex = 0,
                    userLocked = true,
                    updatedAt = System.currentTimeMillis()
                )
            )
            setMessage("Added to the lore.")
        }
    }

    fun deleteEntity(entity: LoreEntity) {
        viewModelScope.launch {
            val relations = container.database.loreRelationDao().forBook(bookId)
            relations.filter {
                it.fromEntityId == entity.id || it.toEntityId == entity.id
            }.forEach { container.database.loreRelationDao().delete(it) }
            container.database.loreEntityDao().delete(entity)
            val book = container.database.bookDao().getById(bookId)
            if (book != null) {
                val key = container.syncService.bookKeyFor(book)
                container.syncService.recordDeletion(
                    "lore",
                    LoreRecord(
                        bookKey = key,
                        type = entity.type.name,
                        name = entity.name
                    ).naturalKey
                )
            }
            setMessage("Removed from the lore.")
        }
    }

    fun generateArt(entity: LoreEntity) {
        if (local.value.artBusyFor != null) return
        local.value = local.value.copy(artBusyFor = entity.id, message = null)
        viewModelScope.launch {
            val book = container.database.bookDao().getById(bookId)
            if (book == null) {
                local.value = local.value.copy(artBusyFor = null)
                return@launch
            }
            when (val result = container.conceptArt.generateForEntity(book, entity)) {
                is AppResult.Ok -> {
                    local.value = local.value.copy(artBusyFor = null)
                    setMessage("Art saved — see the Art tab.")
                }
                is AppResult.Err -> {
                    local.value = local.value.copy(artBusyFor = null)
                    setMessage(
                        "Concept art couldn't be made this time.\n" +
                            ErrorText.displayText(result.error),
                        isError = true
                    )
                }
            }
        }
    }

    fun deleteArt(art: LoreArt) {
        viewModelScope.launch {
            container.database.loreArtDao().delete(art)
            runCatching { container.bookStorage.resolve(art.imagePath).delete() }
        }
    }

    // -- terminology -----------------------------------------------------------

    fun saveTerm(existing: TerminologyEntry?, source: String, translation: String, locked: Boolean) {
        val cleanSource = source.trim()
        val cleanTranslation = translation.trim()
        if (cleanSource.isBlank() || cleanTranslation.isBlank()) {
            setMessage("Both the original term and its translation are needed.", isError = true)
            return
        }
        viewModelScope.launch {
            container.database.terminologyDao().upsert(
                TerminologyEntry(
                    id = existing?.id ?: 0,
                    bookId = bookId,
                    sourceTerm = cleanSource,
                    chosenTranslation = cleanTranslation,
                    locked = locked
                )
            )
            setMessage("Glossary saved.")
        }
    }

    fun deleteTerm(entry: TerminologyEntry) {
        viewModelScope.launch {
            container.database.terminologyDao().delete(entry)
            val book = container.database.bookDao().getById(bookId)
            if (book != null) {
                val key = container.syncService.bookKeyFor(book)
                container.syncService.recordDeletion(
                    "term",
                    TermRecord(key, entry.sourceTerm, entry.chosenTranslation).naturalKey
                )
            }
        }
    }

    companion object {
        fun factory(container: AppContainer, bookId: Long): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    LoreViewModel(container, bookId) as T
            }
    }
}
