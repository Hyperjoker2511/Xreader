package app.xreader.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.xreader.ui.theme.LocalDockClearance
import app.xreader.ui.theme.LocalUiMetrics
import app.xreader.ui.theme.bottomContentPadding
import app.xreader.ui.theme.effectiveBottomPadding
import app.xreader.data.db.VocabWord
import app.xreader.di.AppContainer
import app.xreader.ui.common.GhostPill
import app.xreader.ui.common.GlassCard
import app.xreader.ui.common.KitEmptyState
import app.xreader.ui.common.NocturneChip
import app.xreader.ui.common.PrimaryPill
import app.xreader.ui.common.ScreenHeader
import app.xreader.ui.common.SearchCapsule
import app.xreader.ui.common.SectionHeader
import java.io.File
import androidx.datastore.preferences.core.edit
import app.xreader.data.prefs.settingsDataStore
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * "Words I'm learning" (v1.0.2, sweep addendum — KOReader-style,
 * kept deliberately small): words saved from the reader with the
 * sentence they were found in, a spaced review (VocabReview owns
 * the ladder), and ONE offline dictionary file the user can add
 * or remove. Nothing here needs a network or an account.
 */
data class VocabUiState(
    val words: List<VocabWord> = emptyList(),
    val due: List<VocabWord> = emptyList(),
    val dictionaryName: String? = null,
    val dictionaryBytes: Long = 0,
    val loading: Boolean = true,
    /** Code 122 (R10, audit item #3): book id → title for the
     * books saved words came from, so Saved groups are headed
     * by book TITLE. A word whose book is gone has no entry
     * here and falls back to the provenance heading. */
    val bookTitles: Map<Long, String> = emptyMap(),
    /** Code 122 (R10): the lookup history, newest first —
     * the "Looked up" tab's real list. */
    val lookupHistory: List<LookupHistoryEntry> = emptyList()
)

/** One recorded dictionary lookup (code 122, R10). A null
 * [definition] means the word was looked up and was NOT in
 * the dictionary — recorded honestly, not dropped. */
data class LookupHistoryEntry(
    val word: String,
    val definition: String?,
    val lookedUpAt: Long
)

/**
 * Code 122 (R10): the lookup-history store — the app kept no
 * record of lookups before this. Follows the tree's DataStore
 * discipline (the `settings` store, one string key, validated
 * reads with safe defaults, newest first, capped). Encoding:
 * one entry per line, tab-separated
 * `lookedUpAt ⇥ word ⇥ found(1/0) ⇥ definition`, with tabs
 * and newlines inside fields flattened to spaces at record
 * time (dictionary definitions are single lines by the file's
 * own format, so nothing real is lost).
 */
class LookupHistoryStore(
    private val store: androidx.datastore.core.DataStore<
        androidx.datastore.preferences.core.Preferences>
) {
    val entries: kotlinx.coroutines.flow.Flow<List<LookupHistoryEntry>> =
        store.data.map { prefs ->
            decode(prefs[KEY] ?: "")
        }

    suspend fun record(entry: LookupHistoryEntry) {
        store.edit { prefs ->
            val current = decode(prefs[KEY] ?: "")
            val next = (listOf(entry) + current.filter {
                !it.word.equals(entry.word, ignoreCase = true)
            }).take(MAX_ENTRIES)
            prefs[KEY] = encode(next)
        }
    }

    private fun clean(field: String): String =
        field.replace('\t', ' ').replace('\n', ' ').trim()

    private fun encode(entries: List<LookupHistoryEntry>): String =
        entries.joinToString("\n") { entry ->
            listOf(
                entry.lookedUpAt.toString(),
                clean(entry.word),
                if (entry.definition != null) "1" else "0",
                clean(entry.definition ?: "")
            ).joinToString("\t")
        }

    private fun decode(raw: String): List<LookupHistoryEntry> {
        if (raw.isBlank()) return emptyList()
        return raw.split("\n").mapNotNull { line ->
            val parts = line.split("\t")
            if (parts.size < 4) return@mapNotNull null
            val at = parts[0].toLongOrNull() ?: return@mapNotNull null
            LookupHistoryEntry(
                word = parts[1],
                definition = if (parts[2] == "1") parts[3] else null,
                lookedUpAt = at
            )
        }
    }

    private companion object {
        val KEY = androidx.datastore.preferences.core
            .stringPreferencesKey("vocab.lookup_history")
        const val MAX_ENTRIES = 100
    }
}

class VocabViewModel(private val container: AppContainer) : ViewModel() {

    private val dictionaryDir: File
        get() = DictionaryLookup.dictionaryDir(container.applicationContext.filesDir)

    private fun dictionaryFile(): File? =
        DictionaryLookup.dictionaryFile(container.applicationContext.filesDir)

    private val refreshTick = kotlinx.coroutines.flow.MutableStateFlow(0)

    /** Code 122 (R10): the lookup-history store over the
     * settings DataStore (the GamePrefs pattern). */
    private val lookupHistoryStore = LookupHistoryStore(
        container.applicationContext.settingsDataStore
    )

    val state: StateFlow<VocabUiState> = combine(
        container.database.vocabDao().observeAll(),
        refreshTick,
        container.database.bookDao().observeAllBooks(),
        lookupHistoryStore.entries
    ) { words, _, books, history ->
        val dict = dictionaryFile()
        VocabUiState(
            words = words,
            due = app.xreader.data.library.VocabReview.dueWords(
                words, System.currentTimeMillis()
            ),
            dictionaryName = dict?.name,
            dictionaryBytes = dict?.length() ?: 0,
            loading = false,
            bookTitles = books.associate { it.id to it.title },
            lookupHistory = history
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), VocabUiState())

    fun review(word: VocabWord, remembered: Boolean) {
        viewModelScope.launch {
            container.database.vocabDao().update(
                app.xreader.data.library.VocabReview.review(
                    word, remembered, System.currentTimeMillis()
                )
            )
        }
    }

    fun addWord(word: String, definition: String, sentence: String) {
        val trimmed = word.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            if (container.database.vocabDao().findByWord(trimmed) != null) return@launch
            val now = System.currentTimeMillis()
            container.database.vocabDao().insert(
                VocabWord(
                    word = trimmed,
                    definition = definition.trim(),
                    sentence = sentence.trim(),
                    state = app.xreader.data.db.VocabState.NEW,
                    dueAt = app.xreader.data.library.VocabReview.initialDue(now),
                    createdAt = now,
                    updatedAt = now
                )
            )
        }
    }

    fun removeWord(word: VocabWord) {
        viewModelScope.launch { container.database.vocabDao().delete(word) }
    }

    /** Code 121 (R10): the M3 list's snackbar Undo — re-insert
     * a just-removed word as a fresh row (same word, definition,
     * sentence and review state; a new id). */
    fun restoreWord(word: VocabWord) {
        viewModelScope.launch {
            if (container.database.vocabDao().findByWord(word.word) == null) {
                container.database.vocabDao().insert(word.copy(id = 0))
            }
        }
    }

    /** Look a word up in the user's offline dictionary file
     * (lines of `word<TAB>definition`). Null = no dictionary or
     * no entry — the UI says so plainly.
     * v1.5.10 (F3): the body lives in [DictionaryLookup] now —
     * the readers' Dictionary selection action calls the same
     * core; this stays as the screen's seam, behaviour
     * unchanged. */
    suspend fun lookup(word: String): String? =
        DictionaryLookup.lookupInFile(dictionaryFile(), word)

    /** Code 122 (R10): a lookup that also RECORDS itself in
     * the lookup history — the M3 "Looked up" tab's surface
     * calls this, so the tab is a real history list. A miss
     * is recorded too (definition null), honestly. */
    suspend fun lookupAndRecord(word: String): String? {
        val trimmed = word.trim()
        if (trimmed.isEmpty()) return null
        val result = lookup(trimmed)
        lookupHistoryStore.record(
            LookupHistoryEntry(
                word = trimmed,
                definition = result,
                lookedUpAt = System.currentTimeMillis()
            )
        )
        return result
    }

    /** Install the picked file as THE dictionary (replacing any
     * previous one — one dictionary, removable, per the plan). */
    fun installDictionary(name: String, openStream: () -> java.io.InputStream?) {
        viewModelScope.launch {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    dictionaryDir.mkdirs()
                    dictionaryDir.listFiles()?.forEach { it.delete() }
                    val target = File(dictionaryDir, name.ifBlank { "dictionary.txt" })
                    openStream()?.use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                }
            }
            refreshTick.value++
        }
    }

    fun removeDictionary() {
        viewModelScope.launch {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { dictionaryDir.listFiles()?.forEach { it.delete() } }
            }
            refreshTick.value++
        }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    VocabViewModel(container) as T
            }
    }
}

@Composable
fun VocabScreen(
    viewModel: VocabViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Code 120 completion (R10): the M3 vocabulary sort — A–Z
    // orders the saved-words list; Recent (the default) is the
    // shipped order. The Review-today queue keeps its
    // spaced-repetition order in both designs. Under Nocturne
    // the knob is never read.
    val designState = app.xreader.ui.theme.LocalDesignState.current
    val allWords = if (
        designState.design !=
            app.xreader.data.prefs.AppDesign.NOCTURNE &&
        designState.vocabSort ==
            app.xreader.data.prefs.M3VocabSort.A_Z
    ) {
        state.words.sortedBy { it.word.lowercase() }
    } else {
        state.words
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    var addOpen by remember { mutableStateOf(false) }
    var lookupDraft by remember { mutableStateOf("") }
    var lookupResult by remember { mutableStateOf<String?>(null) }
    var lookupTried by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    val dictionaryPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: "dictionary.txt"
            viewModel.installDictionary(name) {
                runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()
            }
        }
    }

    // Code 121 (R10): the Material 3 Vocabulary — small top
    // app bar, TabRow (Saved · Looked up), word list grouped
    // by book. The Nocturne composition below is unchanged.
    if (designState.design !=
        app.xreader.data.prefs.AppDesign.NOCTURNE
    ) {
        M3VocabContent(
            viewModel = viewModel,
            state = state,
            allWords = allWords,
            onBack = onBack,
            addOpen = addOpen,
            onOpenAdd = { addOpen = true },
            onDismissAdd = { addOpen = false },
            dictionaryPicker = dictionaryPicker
        )
        return
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(start = 20.dp, end = 20.dp, top = LocalUiMetrics.current.topGap, bottom = 16.dp)
    ) {
        ScreenHeader(
            title = "Words I'm learning",
            eyebrow = "Library",
            action = {
                PrimaryPill(
                    text = "+ Word",
                    enabled = true,
                    onClick = { addOpen = true }
                )
            }
        )
        TextButton(onClick = onBack) { Text("← Back") }
        Spacer(Modifier.height(8.dp))

        LazyColumn(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            contentPadding = PaddingValues(
                bottom = effectiveBottomPadding(
                    bottomContentPadding(LocalUiMetrics.current, dockVisible = false),
                    LocalDockClearance.current
                )
            )
        ) {
            item(key = "dictionary") {
                GlassCard(Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        SectionHeader(title = "Offline dictionary")
                        if (state.dictionaryName != null) {
                            Text(
                                "${state.dictionaryName} · " +
                                    app.xreader.data.files.StorageMeter
                                        .formatBytes(state.dictionaryBytes),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                GhostPill(
                                    text = "Replace",
                                    enabled = true,
                                    onClick = {
                                        dictionaryPicker.launch(arrayOf("text/*"))
                                    }
                                )
                                GhostPill(
                                    text = "Remove dictionary",
                                    enabled = true,
                                    onClick = { viewModel.removeDictionary() }
                                )
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                SearchCapsule(
                                    value = lookupDraft,
                                    onValueChange = { lookupDraft = it },
                                    placeholder = "Look up a word",
                                    modifier = Modifier.weight(1f)
                                )
                                GhostPill(
                                    text = "Look up",
                                    enabled = lookupDraft.isNotBlank(),
                                    onClick = {
                                        scope.launch {
                                            lookupResult = viewModel.lookup(lookupDraft)
                                            lookupTried = true
                                        }
                                    }
                                )
                            }
                            if (lookupTried) {
                                Text(
                                    lookupResult ?: "That word isn't in this dictionary.",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        } else {
                            Text(
                                "Add a plain-text dictionary file (one " +
                                    "“word⇥definition” per line) and word " +
                                    "look-ups work fully offline. Optional — " +
                                    "your saved words work without it.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            PrimaryPill(
                                text = "Add dictionary file",
                                enabled = true,
                                onClick = {
                                    dictionaryPicker.launch(arrayOf("text/*"))
                                }
                            )
                        }
                    }
                }
            }

            if (state.due.isNotEmpty()) {
                item(key = "due-header") {
                    SectionHeader(title = "Review today (${state.due.size})")
                }
                items(state.due, key = { "due-${it.id}" }) { word ->
                    VocabWordCard(
                        word = word,
                        reviewing = true,
                        onRemembered = { viewModel.review(word, remembered = true) },
                        onForgot = { viewModel.review(word, remembered = false) },
                        onRemove = { viewModel.removeWord(word) }
                    )
                }
            }

            item(key = "all-header") {
                SectionHeader(title = "All saved words (${state.words.size})")
            }
            if (state.words.isEmpty()) {
                item(key = "empty") {
                    KitEmptyState(
                        icon = Icons.Filled.MenuBook,
                        message = "No words saved yet. While reading, open the " +
                            "reader settings and use “Words I'm learning” to " +
                            "save a word here with its sentence — or add one " +
                            "with + Word.",
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            items(allWords, key = { "word-${it.id}" }) { word ->
                VocabWordCard(
                    word = word,
                    reviewing = false,
                    onRemembered = {},
                    onForgot = {},
                    onRemove = { viewModel.removeWord(word) }
                )
            }
        }
    }

    if (addOpen) {
        var word by remember { mutableStateOf("") }
        var definition by remember { mutableStateOf("") }
        var sentence by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { addOpen = false },
            title = { Text("Add a word") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = word,
                        onValueChange = { word = it },
                        label = { Text("Word") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = definition,
                        onValueChange = { definition = it },
                        label = { Text("What it means") }
                    )
                    OutlinedTextField(
                        value = sentence,
                        onValueChange = { sentence = it },
                        label = { Text("The sentence you found it in") }
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.addWord(word, definition, sentence)
                        addOpen = false
                    },
                    enabled = word.isNotBlank()
                ) { Text("Save word") }
            },
            dismissButton = {
                TextButton(onClick = { addOpen = false }) { Text("Cancel") }
            }
        )
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun VocabWordCard(
    word: VocabWord,
    reviewing: Boolean,
    onRemembered: () -> Unit,
    onForgot: () -> Unit,
    onRemove: () -> Unit
) {
    var showMeaning by remember(word.id) { mutableStateOf(!reviewing) }
    val stateLabel = when (word.state) {
        app.xreader.data.db.VocabState.NEW -> "New"
        app.xreader.data.db.VocabState.LEARNING -> "Learning"
        app.xreader.data.db.VocabState.KNOWN -> "Known"
    }
    val stateDot = when (word.state) {
        app.xreader.data.db.VocabState.NEW -> MaterialTheme.colorScheme.tertiary
        app.xreader.data.db.VocabState.LEARNING -> MaterialTheme.colorScheme.primary
        app.xreader.data.db.VocabState.KNOWN -> MaterialTheme.colorScheme.secondary
    }
    GlassCard(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    word.word,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                NocturneChip(
                    label = stateLabel,
                    selected = false,
                    dotColor = stateDot,
                    onClick = null
                )
            }
            if (word.sentence.isNotBlank()) {
                Text(
                    "“${word.sentence}”",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (showMeaning && word.definition.isNotBlank()) {
                Text(word.definition, style = MaterialTheme.typography.bodyMedium)
            }
            // v1.0.5 (B3): a FlowRow — "Show meaning / I know
            // this / Forgot / Remove word" crowded one fixed row.
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (reviewing) {
                    if (!showMeaning) {
                        TextButton(onClick = { showMeaning = true }) {
                            Text("Show meaning")
                        }
                    } else {
                        TextButton(onClick = onRemembered) { Text("I know this") }
                        TextButton(onClick = onForgot) { Text("Forgot") }
                    }
                }
                TextButton(onClick = onRemove) { Text("Remove word") }
            }
        }
    }
}

// ── Code 121 (R10): the Material 3 Vocabulary ──────────────
// Saved tab: the word list grouped by book. Code 122 (audit
// item #3): VocabUiState now carries book titles, so groups
// are headed by book TITLE (a word whose book is gone falls
// back to the provenance heading — never an invented title).
// Looked up tab: code 122 built the lookup-history store
// (LookupHistoryStore above); lookups made on this tab are
// recorded, and the tab lists the history under the
// dictionary card.
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun M3VocabContent(
    viewModel: VocabViewModel,
    state: VocabUiState,
    allWords: List<VocabWord>,
    onBack: () -> Unit,
    addOpen: Boolean,
    onOpenAdd: () -> Unit,
    onDismissAdd: () -> Unit,
    dictionaryPicker: androidx.activity.result.ActivityResultLauncher<Array<String>>
) {
    var tab by remember { mutableStateOf(0) }
    val snackbarHostState = remember {
        androidx.compose.material3.SnackbarHostState()
    }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var lookupDraft by remember { mutableStateOf("") }
    var lookupResult by remember { mutableStateOf<String?>(null) }
    var lookupTried by remember { mutableStateOf(false) }

    fun removeWithUndo(word: VocabWord) {
        viewModel.removeWord(word)
        scope.launch {
            val result = snackbarHostState.showSnackbar(
                message = "Removed “${word.word}”",
                actionLabel = "Undo",
                duration = androidx.compose.material3.SnackbarDuration.Short
            )
            if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                viewModel.restoreWord(word)
            }
        }
    }

    androidx.compose.material3.Scaffold(
        topBar = {
            androidx.compose.material3.TopAppBar(
                title = { Text("Vocabulary") },
                navigationIcon = {
                    androidx.compose.material3.IconButton(onClick = onBack) {
                        androidx.compose.material3.Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    androidx.compose.material3.TextButton(onClick = onOpenAdd) {
                        Text("Add a word")
                    }
                }
            )
        },
        snackbarHost = {
            androidx.compose.material3.SnackbarHost(snackbarHostState)
        }
    ) { innerPadding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            androidx.compose.material3.PrimaryTabRow(selectedTabIndex = tab) {
                androidx.compose.material3.Tab(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    text = { Text("Saved") }
                )
                androidx.compose.material3.Tab(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    text = { Text("Looked up") }
                )
            }
            if (tab == 0) {
                if (state.words.isEmpty()) {
                    // S40: words arrive from the reader's
                    // Dictionary action.
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            "No words saved yet",
                            style = MaterialTheme.typography.headlineSmall
                        )
                        Text(
                            "Words you save arrive from the reader's " +
                                "Dictionary action — or add one with " +
                                "“Add a word”.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        Modifier.weight(1f),
                        contentPadding = PaddingValues(
                            start = 16.dp, end = 16.dp,
                            top = 12.dp, bottom = 88.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        if (state.due.isNotEmpty()) {
                            item(key = "m3-due-header") {
                                Text(
                                    "Review today (${state.due.size})",
                                    style = MaterialTheme.typography.titleMedium
                                )
                            }
                            items(state.due, key = { "m3-due-${it.id}" }) { word ->
                                M3VocabRow(
                                    word = word,
                                    reviewing = true,
                                    onRemembered = {
                                        viewModel.review(word, remembered = true)
                                    },
                                    onForgot = {
                                        viewModel.review(word, remembered = false)
                                    },
                                    onRemove = { removeWithUndo(word) }
                                )
                            }
                        }
                        // Grouped by book: one group per bookId,
                        // hand-added words (no book) last.
                        val groups = allWords.groupBy { it.bookId }
                        val orderedKeys = groups.keys
                            .sortedBy { it ?: Long.MAX_VALUE }
                        orderedKeys.forEach { bookId ->
                            val groupWords = groups[bookId].orEmpty()
                            item(key = "m3-group-$bookId") {
                                Text(
                                    if (bookId == null) {
                                        "Added by hand"
                                    } else {
                                        state.bookTitles[bookId]
                                            ?: "Saved from a book"
                                    },
                                    style = MaterialTheme.typography.titleMedium
                                )
                            }
                            items(
                                groupWords,
                                key = { "m3-word-${it.id}" }
                            ) { word ->
                                M3VocabRow(
                                    word = word,
                                    reviewing = false,
                                    onRemembered = {},
                                    onForgot = {},
                                    onRemove = { removeWithUndo(word) }
                                )
                            }
                        }
                    }
                }
            } else {
                // Looked up: the offline dictionary surface —
                // install/replace/remove + the live lookup.
                LazyColumn(
                    Modifier.weight(1f),
                    contentPadding = PaddingValues(
                        start = 16.dp, end = 16.dp,
                        top = 12.dp, bottom = 88.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item(key = "m3-dictionary") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "Offline dictionary",
                                style = MaterialTheme.typography.titleMedium
                            )
                            if (state.dictionaryName != null) {
                                Text(
                                    "${state.dictionaryName} · " +
                                        app.xreader.data.files.StorageMeter
                                            .formatBytes(state.dictionaryBytes),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme
                                        .onSurfaceVariant
                                )
                                Row(
                                    horizontalArrangement =
                                        Arrangement.spacedBy(8.dp)
                                ) {
                                    androidx.compose.material3.OutlinedButton(
                                        onClick = {
                                            dictionaryPicker.launch(
                                                arrayOf("text/*")
                                            )
                                        }
                                    ) { Text("Replace") }
                                    androidx.compose.material3.OutlinedButton(
                                        onClick = { viewModel.removeDictionary() }
                                    ) { Text("Remove dictionary") }
                                }
                                OutlinedTextField(
                                    value = lookupDraft,
                                    onValueChange = { lookupDraft = it },
                                    label = { Text("Look up a word") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                androidx.compose.material3.Button(
                                    onClick = {
                                        scope.launch {
                                            lookupResult =
                                                viewModel.lookupAndRecord(
                                                    lookupDraft
                                                )
                                            lookupTried = true
                                        }
                                    },
                                    enabled = lookupDraft.isNotBlank()
                                ) { Text("Look up") }
                                if (lookupTried) {
                                    Text(
                                        lookupResult
                                            ?: "That word isn't in this dictionary.",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            } else {
                                Text(
                                    "Add a plain-text dictionary file (one " +
                                        "“word⇥definition” per line) and word " +
                                        "look-ups work fully offline. Optional — " +
                                        "your saved words work without it.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme
                                        .onSurfaceVariant
                                )
                                androidx.compose.material3.Button(
                                    onClick = {
                                        dictionaryPicker.launch(arrayOf("text/*"))
                                    }
                                ) { Text("Add dictionary file") }
                            }
                        }
                    }
                    // Code 122 (R10): the history itself —
                    // every lookup made on this tab, newest
                    // first, misses included.
                    if (state.lookupHistory.isEmpty()) {
                        item(key = "m3-history-empty") {
                            Text(
                                "Words you look up here are kept in " +
                                    "this list.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme
                                    .onSurfaceVariant
                            )
                        }
                    } else {
                        item(key = "m3-history-head") {
                            Text(
                                "Looked up (${state.lookupHistory.size})",
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                        items(
                            state.lookupHistory,
                            key = { "m3-history-${it.lookedUpAt}-${it.word}" }
                        ) { entry ->
                            androidx.compose.material3.ListItem(
                                headlineContent = { Text(entry.word) },
                                supportingContent = {
                                    Text(
                                        entry.definition
                                            ?: "That word isn't in this dictionary."
                                    )
                                }
                            )
                        }
                    }
                }
            }
        }
    }
    if (addOpen) {
        VocabAddDialog(
            onDismiss = onDismissAdd,
            onSave = { w, d, s ->
                viewModel.addWord(w, d, s)
                onDismissAdd()
            }
        )
    }
}

/** One R10 word row: word in titleMedium, chapter context in
 * bodySmall, definition in bodyMedium; trailing delete icon. */
@Composable
private fun M3VocabRow(
    word: VocabWord,
    reviewing: Boolean,
    onRemembered: () -> Unit,
    onForgot: () -> Unit,
    onRemove: () -> Unit
) {
    var showMeaning by remember(word.id) { mutableStateOf(!reviewing) }
    androidx.compose.material3.ListItem(
        headlineContent = {
            Text(
                word.word,
                style = MaterialTheme.typography.titleMedium
            )
        },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (word.chapterIndex >= 0) {
                    Text(
                        "Chapter ${word.chapterIndex + 1}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (word.sentence.isNotBlank()) {
                    Text(
                        "“${word.sentence}”",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (showMeaning && word.definition.isNotBlank()) {
                    Text(
                        word.definition,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                if (reviewing) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (!showMeaning) {
                            TextButton(onClick = { showMeaning = true }) {
                                Text("Show meaning")
                            }
                        } else {
                            TextButton(onClick = onRemembered) {
                                Text("I know this")
                            }
                            TextButton(onClick = onForgot) {
                                Text("Forgot")
                            }
                        }
                    }
                }
            }
        },
        trailingContent = {
            androidx.compose.material3.IconButton(onClick = onRemove) {
                androidx.compose.material3.Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Remove ${word.word}"
                )
            }
        }
    )
}

/** The add-a-word dialog, in this design's frame — the same
 * three fields the Nocturne dialog carries. */
@Composable
private fun VocabAddDialog(
    onDismiss: () -> Unit,
    onSave: (word: String, definition: String, sentence: String) -> Unit
) {
    var word by remember { mutableStateOf("") }
    var definition by remember { mutableStateOf("") }
    var sentence by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a word") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = word,
                    onValueChange = { word = it },
                    label = { Text("Word") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = definition,
                    onValueChange = { definition = it },
                    label = { Text("What it means") }
                )
                OutlinedTextField(
                    value = sentence,
                    onValueChange = { sentence = it },
                    label = { Text("The sentence you found it in") }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(word, definition, sentence) },
                enabled = word.isNotBlank()
            ) { Text("Save word") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
