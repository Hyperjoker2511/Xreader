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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
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
    val loading: Boolean = true
)

class VocabViewModel(private val container: AppContainer) : ViewModel() {

    private val dictionaryDir: File
        get() = File(container.applicationContext.filesDir, "dictionary")

    private fun dictionaryFile(): File? =
        dictionaryDir.listFiles()?.firstOrNull { it.isFile }

    private val refreshTick = kotlinx.coroutines.flow.MutableStateFlow(0)

    val state: StateFlow<VocabUiState> = combine(
        container.database.vocabDao().observeAll(),
        refreshTick
    ) { words, _ ->
        val dict = dictionaryFile()
        VocabUiState(
            words = words,
            due = app.xreader.data.library.VocabReview.dueWords(
                words, System.currentTimeMillis()
            ),
            dictionaryName = dict?.name,
            dictionaryBytes = dict?.length() ?: 0,
            loading = false
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

    /** Look a word up in the user's offline dictionary file
     * (lines of `word<TAB>definition`). Null = no dictionary or
     * no entry — the UI says so plainly. */
    suspend fun lookup(word: String): String? {
        val file = dictionaryFile() ?: return null
        val needle = word.trim().lowercase()
        if (needle.isEmpty()) return null
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                file.bufferedReader().useLines { lines ->
                    lines.firstOrNull { line ->
                        line.substringBefore('\t').trim().lowercase() == needle
                    }?.substringAfter('\t', "")?.trim()?.takeIf { it.isNotEmpty() }
                }
            }.getOrNull()
        }
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

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 16.dp)
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
            contentPadding = PaddingValues(bottom = 88.dp)
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
            items(state.words, key = { "word-${it.id}" }) { word ->
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
