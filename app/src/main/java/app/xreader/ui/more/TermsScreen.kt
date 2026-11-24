package app.xreader.ui.more

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FindReplace
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.xreader.di.AppContainer
import app.xreader.domain.reader.TermPair
import app.xreader.ui.common.GhostPill
import app.xreader.ui.common.GlassCard
import app.xreader.ui.common.KitEmptyState
import app.xreader.ui.common.ScreenHeader
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import app.xreader.ui.theme.LocalUiMetrics

/**
 * Reader Terms (P0): the global list's page. Patch 11 moved
 * its home into the reader: the Me hub's "Reader terms" door
 * is gone, and the only way in is the reader flow (reader →
 * Terms for this book → Terms for all books). The screen and
 * its ViewModel are otherwise the pre-Patch-11 ones. The same
 * [TermsEditor] serves the per-book page ("Terms for this
 * book", ui/reader/BookTermsScreen.kt).
 *
 * Terms change only what the reader SHOWS. A book's own text is
 * never changed, and deleting every term brings the original
 * wording back exactly.
 */
class TermsViewModel(private val container: AppContainer) : ViewModel() {

    val pairs: StateFlow<List<TermPair>> = container.termsPrefs.globalPairs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setPairs(pairs: List<TermPair>) {
        viewModelScope.launch { container.termsPrefs.setGlobalPairs(pairs) }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    TermsViewModel(container) as T
            }
    }
}

@Composable
fun TermsScreen(
    viewModel: TermsViewModel,
    onBack: () -> Unit
) {
    val pairs by viewModel.pairs.collectAsStateWithLifecycle()
    // Code 121 W8 (R14): the Material 3 global terms page.
    if (app.xreader.ui.theme.LocalDesignState.current.design ==
        app.xreader.data.prefs.AppDesign.MATERIAL3
    ) {
        M3TermsContent(
            title = "Reader terms",
            scopeLine = "Applies to every book",
            pairs = pairs,
            onChange = { viewModel.setPairs(it) },
            onBack = onBack
        )
        return
    }
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = LocalUiMetrics.current.topGap, bottom = 16.dp)
            .padding(bottom = 72.dp)
    ) {
        ScreenHeader(title = "Reader terms", eyebrow = "Every book")
        TextButton(onClick = onBack) { Text("← Back") }
        Text(
            "Fix a name or word once, and it's shown your way in " +
                "every book. This only changes what you see — a " +
                "book's own text is never changed.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))
        if (pairs.isEmpty()) {
            KitEmptyState(
                icon = Icons.Filled.FindReplace,
                message = "No terms yet. Add one when a book keeps " +
                    "spelling a name wrong."
            )
            Spacer(Modifier.height(12.dp))
        }
        TermsEditor(
            pairs = pairs,
            onChange = { viewModel.setPairs(it) }
        )
    }
}

/**
 * The terms list editor, shared by the global Settings screen
 * and the reader's per-book sheet: one card per pair showing
 * "original → yours", a delete button on the card, tap a card
 * to edit it, and an add button at the end. Adding/editing
 * happens in a small dialog with the two fields, so the list
 * itself stays one calm column.
 */
@Composable
fun TermsEditor(
    pairs: List<TermPair>,
    onChange: (List<TermPair>) -> Unit,
    modifier: Modifier = Modifier
) {
    // The pair being added (index -1) or edited (its index);
    // null = the dialog is closed.
    var editIndex by remember { mutableStateOf<Int?>(null) }
    var draftOriginal by remember { mutableStateOf("") }
    var draftReplacement by remember { mutableStateOf("") }
    var draftMatchCase by remember { mutableStateOf(true) }
    var draftWholeWord by remember { mutableStateOf(true) }

    fun openEditor(index: Int) {
        val pair = pairs.getOrNull(index)
        draftOriginal = pair?.original ?: ""
        draftReplacement = pair?.replacement ?: ""
        draftMatchCase = pair?.matchCase ?: true
        draftWholeWord = pair?.wholeWord ?: true
        editIndex = index
    }

    Column(
        modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        pairs.forEachIndexed { index, pair ->
            GlassCard(
                onClick = { openEditor(index) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${pair.original} → ${pair.replacement}",
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = {
                            onChange(pairs.filterIndexed { i, _ -> i != index })
                        }
                    ) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = "Remove ${pair.original}"
                        )
                    }
                }
            }
        }
        GhostPill(
            text = "+ Add a term",
            enabled = true,
            onClick = { openEditor(-1) }
        )
    }

    val editing = editIndex
    if (editing != null) {
        AlertDialog(
            onDismissRequest = { editIndex = null },
            title = {
                Text(if (editing < 0) "Add a term" else "Edit term")
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = draftOriginal,
                        onValueChange = { draftOriginal = it },
                        label = { Text("Original") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = draftReplacement,
                        onValueChange = { draftReplacement = it },
                        label = { Text("Show as") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Match case",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = draftMatchCase,
                            onCheckedChange = { draftMatchCase = it }
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Whole word only",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = draftWholeWord,
                            onCheckedChange = { draftWholeWord = it }
                        )
                    }
                    Text(
                        "Names written without spaces (Chinese, " +
                            "Korean, Japanese) are always found " +
                            "exactly as written.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = draftOriginal.isNotBlank(),
                    onClick = {
                        val pair = TermPair(
                            original = draftOriginal.trim(),
                            replacement = draftReplacement,
                            matchCase = draftMatchCase,
                            wholeWord = draftWholeWord
                        )
                        onChange(
                            if (editing < 0) {
                                pairs + pair
                            } else {
                                pairs.mapIndexed { i, old ->
                                    if (i == editing) pair else old
                                }
                            }
                        )
                        editIndex = null
                    }
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { editIndex = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

// ────────────────────────────────────────────────────────────────
// Code 121 W8 (R13/R14): the Material 3 terms pages — ONE
// composition at two scopes. Small top app bar (the book's
// title on R13, "Reader terms" on R14), the scope line at the
// top in bodySmall, the pairs as settings-list rows
// (original → replacement, trailing edit/delete icons), and
// the "Add a term" extended FAB opening the S27 dialog form
// (Original / Show as / Match case / Whole word — the shipped
// pair model's own fields; the scope is the page itself, so
// the dialog carries no scope switch — D-121-W5-6 stands).
// Data is the screens' own pairs flow; Nocturne never reaches
// this composable.
// ────────────────────────────────────────────────────────────────

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun M3TermsContent(
    title: String,
    scopeLine: String,
    pairs: List<TermPair>,
    onChange: (List<TermPair>) -> Unit,
    onBack: () -> Unit,
    globalDoor: (() -> Unit)? = null
) {
    var editIndex by remember { mutableStateOf<Int?>(null) }
    var draftOriginal by remember { mutableStateOf("") }
    var draftReplacement by remember { mutableStateOf("") }
    var draftMatchCase by remember { mutableStateOf(true) }
    var draftWholeWord by remember { mutableStateOf(true) }

    fun openEditor(index: Int) {
        val pair = pairs.getOrNull(index)
        draftOriginal = pair?.original ?: ""
        draftReplacement = pair?.replacement ?: ""
        draftMatchCase = pair?.matchCase ?: true
        draftWholeWord = pair?.wholeWord ?: true
        editIndex = index
    }

    androidx.compose.material3.Scaffold(
        topBar = {
            androidx.compose.material3.TopAppBar(
                title = { Text(title, maxLines = 1) },
                navigationIcon = {
                    androidx.compose.material3.IconButton(onClick = onBack) {
                        Icon(
                            androidx.compose.material.icons.Icons
                                .AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            androidx.compose.material3.ExtendedFloatingActionButton(
                onClick = { openEditor(-1) },
                icon = {
                    Icon(
                        androidx.compose.material.icons.Icons.Filled.Add,
                        contentDescription = null
                    )
                },
                text = { Text("Add a term") }
            )
        }
    ) { innerPadding ->
        androidx.compose.foundation.lazy.LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = androidx.compose.foundation.layout
                .PaddingValues(
                    start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp
                ),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            item(key = "m3-terms-scope") {
                Text(
                    scopeLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (pairs.isEmpty()) {
                item(key = "m3-terms-empty") {
                    app.xreader.ui.common.M3EmptyState(
                        headline = "No terms yet",
                        body = "Add one when a book keeps spelling a " +
                            "name wrong — it is shown your way from " +
                            "then on. This only changes what you see; " +
                            "a book's own text is never changed."
                    )
                }
            }
            items(
                pairs.withIndex().toList(),
                key = { "m3-term-${it.index}-${it.value.original}" }
            ) { (index, pair) ->
                androidx.compose.material3.ListItem(
                    headlineContent = {
                        Text(
                            "${pair.original} → ${pair.replacement}",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    supportingContent = {
                        val flags = listOfNotNull(
                            if (pair.matchCase) "Match case" else null,
                            if (pair.wholeWord) "Whole word" else null
                        )
                        if (flags.isNotEmpty()) {
                            Text(flags.joinToString(" · "))
                        }
                    },
                    trailingContent = {
                        Row {
                            androidx.compose.material3.IconButton(
                                onClick = { openEditor(index) }
                            ) {
                                Icon(
                                    androidx.compose.material.icons.Icons
                                        .Filled.Edit,
                                    contentDescription =
                                        "Edit ${pair.original}"
                                )
                            }
                            androidx.compose.material3.IconButton(
                                onClick = {
                                    onChange(
                                        pairs.filterIndexed { i, _ ->
                                            i != index
                                        }
                                    )
                                }
                            ) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription =
                                        "Remove ${pair.original}"
                                )
                            }
                        }
                    }
                )
            }
            if (globalDoor != null) {
                item(key = "m3-terms-global-door") {
                    androidx.compose.material3.ListItem(
                        headlineContent = { Text("Terms for all books") },
                        supportingContent = {
                            Text(
                                "Pairs that apply in every book. This " +
                                    "book's own pairs win when both name " +
                                    "the same word."
                            )
                        },
                        leadingContent = {
                            Icon(
                                Icons.Filled.FindReplace,
                                contentDescription = null
                            )
                        },
                        modifier = Modifier.clickable { globalDoor() }
                    )
                }
            }
        }
    }

    val editing = editIndex
    if (editing != null) {
        AlertDialog(
            onDismissRequest = { editIndex = null },
            title = {
                Text(if (editing < 0) "Add a term" else "Edit term")
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = draftOriginal,
                        onValueChange = { draftOriginal = it },
                        label = { Text("Original") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = draftReplacement,
                        onValueChange = { draftReplacement = it },
                        label = { Text("Show as") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Match case",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = draftMatchCase,
                            onCheckedChange = { draftMatchCase = it }
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Whole word only",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = draftWholeWord,
                            onCheckedChange = { draftWholeWord = it }
                        )
                    }
                    Text(
                        "Names written without spaces (Chinese, " +
                            "Korean, Japanese) are always found " +
                            "exactly as written.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = draftOriginal.isNotBlank(),
                    onClick = {
                        val pair = TermPair(
                            original = draftOriginal.trim(),
                            replacement = draftReplacement,
                            matchCase = draftMatchCase,
                            wholeWord = draftWholeWord
                        )
                        onChange(
                            if (editing < 0) {
                                pairs + pair
                            } else {
                                pairs.mapIndexed { i, old ->
                                    if (i == editing) pair else old
                                }
                            }
                        )
                        editIndex = null
                    }
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { editIndex = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}
