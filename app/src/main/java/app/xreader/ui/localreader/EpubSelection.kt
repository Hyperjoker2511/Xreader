package app.xreader.ui.localreader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * v1.5.10 (F3, §6 Q3 → B, build-map Group F): text selection
 * with the SHARED actions — Copy · Highlight · Note ·
 * Dictionary — in the EPUB reader AND the PDF Text view.
 *
 * Toolkit facts (AAR-verified, plan F3): Readium 3.1.2's
 * `EpubNavigatorFragment` implements `SelectableNavigator`
 * (`currentSelection()`, suspend, → `Selection(locator, rect)`)
 * and the Decoration API is complete — but there is NO
 * selection-changed callback.
 *
 * v1.5.12 (E3 / contract §2.5): selection now follows the
 * PLATFORM pattern instead of a poll. The WebView's native
 * ActionMode (declared through the fragment Configuration's
 * `selectionActionModeCallback` — [EpubSelectionActionModeCallback])
 * IS the discovery mechanism: the system starts it on
 * long-press, Xreader's four actions ride it as menu items,
 * and `currentSelection()` is called ON DEMAND, once, inside
 * the handler of the action the user actually tapped. Idle
 * cost is ZERO — no timers, no periodic JS evaluation. The
 * v1.5.10/v1.5.11 design (a custom Compose popup fed by a
 * 280 ms poll, gated in v1.5.11 to long-press-armed windows)
 * is deleted: the poll's bridge traffic was a convicted EPUB
 * ANR contributor, and its arming defects concentrated that
 * traffic into exactly the chapter-boundary seconds. The
 * popup STATE MACHINE below survives only as the reducer the
 * menu actions and the decoration-tap menu dispatch through
 * (its `Polled` event is now fed by the one on-demand fetch,
 * never by a loop); the note editor + highlight menu surfaces
 * survive as the follow-on dialogs those actions open. A
 * selection spanning a spine-resource boundary is still not
 * offered (toolkit limit); on-page PDF selection cannot exist
 * below API 35 (X3) — the PDF half lives in the Text view,
 * page-anchored, and shares this file's store + action row.
 *
 * This file is the component layer: the persisted highlight
 * model + store (ONE per-book DataStore key serving both
 * formats), the popup state machine as a PURE function, the
 * native ActionMode callback, and the follow-on surfaces. The
 * navigator seam ([EpubSelectionNavigator]) is the narrow
 * interface the reader implements over the fragment (the same
 * extraction pattern as the navigator seam) and fakes in
 * tests.
 */

// ── The persisted highlight model ────────────────────────────────

/**
 * One stored highlight/note. EPUB entries anchor by locator
 * JSON ([locatorJson] set, [page] null); PDF Text-view entries
 * anchor by (page, char range) ([page] set, [locatorJson]
 * null). [note] empty = a plain highlight; non-empty = a note.
 */
@Serializable
data class StoredSelectionHighlight(
    val id: String,
    val locatorJson: String? = null,
    val page: Int? = null,
    val charStart: Int? = null,
    val charEnd: Int? = null,
    val selectedText: String,
    val note: String = "",
    val tintArgb: Int = HIGHLIGHT_TINT_ARGB,
    val createdAtEpochMillis: Long = 0L
) {
    val isEpub: Boolean get() = locatorJson != null
    val isPdfText: Boolean get() = page != null
    val hasNote: Boolean get() = note.isNotBlank()
}

/** The highlight tint: Nocturne Violet at 40% — the design's
 * one accent, spent on the reader's own marks. */
const val HIGHLIGHT_TINT_ARGB: Int = 0x668B5CF6

private val highlightJson = Json { ignoreUnknownKeys = true }

fun encodeSelectionHighlights(highlights: List<StoredSelectionHighlight>): String =
    highlightJson.encodeToString(
        ListSerializer(StoredSelectionHighlight.serializer()), highlights
    )

fun decodeSelectionHighlights(raw: String?): List<StoredSelectionHighlight> {
    if (raw.isNullOrBlank()) return emptyList()
    return runCatching {
        highlightJson.decodeFromString(
            ListSerializer(StoredSelectionHighlight.serializer()), raw
        )
    }.getOrDefault(emptyList())
}

/**
 * The highlight store: per book, ONE key —
 * `localreader.<id>.selection.highlights_json` (String) — in
 * the shared "settings" DataStore (the F-data key specified
 * for LocalReaderPrefs; this class is its implementation,
 * used by both readers). Room schema stays v11.
 */
class SelectionHighlightStore(private val store: DataStore<Preferences>) {

    fun highlights(bookId: Long): Flow<List<StoredSelectionHighlight>> =
        store.data.map { prefs -> decodeSelectionHighlights(prefs[key(bookId)]) }

    suspend fun save(bookId: Long, highlights: List<StoredSelectionHighlight>) {
        store.edit { prefs ->
            prefs[key(bookId)] = encodeSelectionHighlights(highlights)
        }
    }

    suspend fun add(bookId: Long, highlight: StoredSelectionHighlight) {
        val current = decodeSelectionHighlights(
            store.dataSnapshot(bookId)
        )
        save(bookId, current + highlight)
    }

    suspend fun remove(bookId: Long, highlightId: String) {
        val current = decodeSelectionHighlights(store.dataSnapshot(bookId))
        save(bookId, current.filterNot { it.id == highlightId })
    }

    suspend fun updateNote(bookId: Long, highlightId: String, note: String) {
        val current = decodeSelectionHighlights(store.dataSnapshot(bookId))
        save(
            bookId,
            current.map {
                if (it.id == highlightId) it.copy(note = note) else it
            }
        )
    }

    private suspend fun DataStore<Preferences>.dataSnapshot(bookId: Long): String? =
        data.first()[key(bookId)]

    companion object {
        fun key(bookId: Long) =
            stringPreferencesKey("localreader.$bookId.selection.highlights_json")
    }
}

// ── The selection snapshot + navigator seam ──────────────────────

/** On-screen rect of a live selection (px, WebView space). */
data class SelectionRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
)

/** A live EPUB selection, flattened off the toolkit types so
 * the state machine and its tests never touch Readium. */
data class SelectionSnapshot(
    val locatorJson: String,
    val text: String,
    val rect: SelectionRect?,
    val href: String?
)

/** One decoration to (re)apply at open: an EPUB highlight's
 * render spec. L-DE converts these to Readium `Decoration`s
 * (locator from JSON, Highlight style in [tintArgb]). */
data class SelectionDecorationSpec(
    val highlightId: String,
    val locatorJson: String,
    val tintArgb: Int,
    val note: String
)

/** The decorations a book's EPUB highlights render as — PDF
 * Text-view entries are excluded (they render as annotated
 * spans in the Text view instead). */
fun decorationsFor(
    highlights: List<StoredSelectionHighlight>
): List<SelectionDecorationSpec> = highlights.mapNotNull { highlight ->
    val locator = highlight.locatorJson ?: return@mapNotNull null
    SelectionDecorationSpec(
        highlightId = highlight.id,
        locatorJson = locator,
        tintArgb = highlight.tintArgb,
        note = highlight.note
    )
}

/** The decoration group name both the apply-at-open and the
 * rebuild paths use (the re-run's `user_highlights` shape). */
const val SELECTION_DECORATION_GROUP = "user_highlights"

/**
 * The narrow navigator seam F3 rides (L-DE implements it over
 * the fragment; tests fake it). Mirrors the AAR-verified
 * surface: suspend [currentSelection], [clearSelection], and
 * decoration application over [SelectionDecorationSpec]s.
 */
interface EpubSelectionNavigator {
    suspend fun currentSelection(): SelectionSnapshot?
    fun clearSelection()
    suspend fun applyDecorations(decorations: List<SelectionDecorationSpec>)
}

// ── Native ActionMode selection menu (v1.5.12, E3) ───────────────

/**
 * Xreader's four selection actions, contributed as items to
 * the WebView's NATIVE selection ActionMode (the platform
 * pattern the Readium reference host uses). The actions are
 * the same four the deleted Compose popup offered — Copy ·
 * Highlight · Note · Dictionary — only the trigger/surface
 * changed: the system menu on long-press instead of a popup
 * a 280 ms poll had to discover selections for.
 */
enum class EpubSelectionMenuAction(val itemId: Int, val label: String) {
    COPY(itemId = 871_001, label = "Copy"),
    HIGHLIGHT(itemId = 871_002, label = "Highlight"),
    NOTE(itemId = 871_003, label = "Note"),
    DICTIONARY(itemId = 871_004, label = "Dictionary");

    companion object {
        fun fromItemId(itemId: Int): EpubSelectionMenuAction? =
            entries.firstOrNull { it.itemId == itemId }
    }
}

/** The menu the native callback builds, in display order. */
val EPUB_SELECTION_MENU: List<EpubSelectionMenuAction> =
    EpubSelectionMenuAction.entries

/**
 * The `selectionActionModeCallback` the EPUB fragment's
 * Configuration carries (v1.5.12, E3). The WebView starts the
 * ActionMode on long-press selection; this callback adds
 * Xreader's items beside (or instead of) the platform's own
 * and routes a tap to [onAction] — the reader's handler,
 * which fetches `currentSelection()` ONCE for that action and
 * dispatches it through the state machine below. No state is
 * held here, no timer runs, and nothing is evaluated while
 * the user is merely reading: selection costs nothing when
 * idle (contract §2.5).
 */
class EpubSelectionActionModeCallback(
    private val onAction: (EpubSelectionMenuAction) -> Unit
) : android.view.ActionMode.Callback {

    override fun onCreateActionMode(
        mode: android.view.ActionMode,
        menu: android.view.Menu
    ): Boolean {
        EPUB_SELECTION_MENU.forEachIndexed { index, action ->
            menu.add(android.view.Menu.NONE, action.itemId, index, action.label)
        }
        return true
    }

    override fun onPrepareActionMode(
        mode: android.view.ActionMode,
        menu: android.view.Menu
    ): Boolean = false

    override fun onActionItemClicked(
        mode: android.view.ActionMode,
        item: android.view.MenuItem
    ): Boolean {
        val action = EpubSelectionMenuAction.fromItemId(item.itemId)
            ?: return false
        onAction(action)
        mode.finish()
        return true
    }

    override fun onDestroyActionMode(mode: android.view.ActionMode) = Unit
}

/** Adds Xreader's four actions to a selection menu the
 * PLATFORM built, dropping the platform's own Copy item first
 * (Xreader's Copy routes through the same state machine as
 * the other three; two "Copy" rows would be a lie of a menu).
 * Shared by the river's two wrappers below. */
private fun android.view.Menu.addXreaderSelectionItems() {
    removeItem(android.R.id.copy)
    if (findItem(EpubSelectionMenuAction.COPY.itemId) != null) return
    EPUB_SELECTION_MENU.forEachIndexed { index, action ->
        add(
            android.view.Menu.NONE, action.itemId,
            100 + index, action.label
        )
    }
}

/**
 * v1.5.34 (R4, plan §5.1): the RIVER's selection ActionMode —
 * the stock WebView FLOATING toolbar (Callback2) with
 * Xreader's actions added beside the platform's own. The
 * river WebView wraps whatever callback the WebView hands
 * `startActionMode` with one of these; a tap on a Xreader item
 * routes to [onAction] (the host then makes ONE on-demand
 * `__xrSelection()` pull and dispatches through the same
 * state machine the legacy reader uses); every other item
 * delegates untouched. The wrap-the-default shape is the
 * platform pattern for WebView selection menus — Readium's
 * navigator WebView does the equivalent substitution for its
 * own callback.
 */
class RiverSelectionCallback2(
    private val delegate: android.view.ActionMode.Callback2,
    private val onAction: (EpubSelectionMenuAction) -> Unit
) : android.view.ActionMode.Callback2() {

    override fun onCreateActionMode(
        mode: android.view.ActionMode,
        menu: android.view.Menu
    ): Boolean {
        val created = delegate.onCreateActionMode(mode, menu)
        menu.addXreaderSelectionItems()
        return created
    }

    override fun onPrepareActionMode(
        mode: android.view.ActionMode,
        menu: android.view.Menu
    ): Boolean {
        val changed = delegate.onPrepareActionMode(mode, menu)
        menu.addXreaderSelectionItems()
        return changed
    }

    override fun onActionItemClicked(
        mode: android.view.ActionMode,
        item: android.view.MenuItem
    ): Boolean {
        val action = EpubSelectionMenuAction.fromItemId(item.itemId)
            ?: return delegate.onActionItemClicked(mode, item)
        onAction(action)
        mode.finish()
        return true
    }

    override fun onDestroyActionMode(mode: android.view.ActionMode) {
        delegate.onDestroyActionMode(mode)
    }

    override fun onGetContentRect(
        mode: android.view.ActionMode,
        view: android.view.View,
        outRect: android.graphics.Rect
    ) {
        delegate.onGetContentRect(mode, view, outRect)
    }
}

/** The plain-Callback twin of [RiverSelectionCallback2] for
 * the rare WebView that starts its selection ActionMode with
 * a non-Callback2 callback. */
class RiverSelectionCallback(
    private val delegate: android.view.ActionMode.Callback,
    private val onAction: (EpubSelectionMenuAction) -> Unit
) : android.view.ActionMode.Callback {

    override fun onCreateActionMode(
        mode: android.view.ActionMode,
        menu: android.view.Menu
    ): Boolean {
        val created = delegate.onCreateActionMode(mode, menu)
        menu.addXreaderSelectionItems()
        return created
    }

    override fun onPrepareActionMode(
        mode: android.view.ActionMode,
        menu: android.view.Menu
    ): Boolean {
        val changed = delegate.onPrepareActionMode(mode, menu)
        menu.addXreaderSelectionItems()
        return changed
    }

    override fun onActionItemClicked(
        mode: android.view.ActionMode,
        item: android.view.MenuItem
    ): Boolean {
        val action = EpubSelectionMenuAction.fromItemId(item.itemId)
            ?: return delegate.onActionItemClicked(mode, item)
        onAction(action)
        mode.finish()
        return true
    }

    override fun onDestroyActionMode(mode: android.view.ActionMode) {
        delegate.onDestroyActionMode(mode)
    }
}

// ── The popup state machine (pure) ───────────────────────────────

sealed interface SelectionPopupState {
    data object Hidden : SelectionPopupState
    data class Shown(val snapshot: SelectionSnapshot) : SelectionPopupState
    data class NoteEditing(
        val snapshot: SelectionSnapshot,
        val existingHighlightId: String?
    ) : SelectionPopupState
    data class HighlightMenu(val highlight: StoredSelectionHighlight) :
        SelectionPopupState
}

sealed interface SelectionEvent {
    /** A selection fetched ON DEMAND (v1.5.12: the single
     * `currentSelection()` call a native menu action makes;
     * before v1.5.12 this was fed by the 280 ms poll loop). */
    data class Polled(val snapshot: SelectionSnapshot?) : SelectionEvent
    data object CopyChosen : SelectionEvent
    data object HighlightChosen : SelectionEvent
    data object NoteChosen : SelectionEvent
    data object DictionaryChosen : SelectionEvent
    data class NoteSaved(val text: String) : SelectionEvent
    data object NoteCancelled : SelectionEvent
    data object Dismissed : SelectionEvent
    data class DecorationTapped(val highlight: StoredSelectionHighlight) :
        SelectionEvent
    data object RemoveHighlightChosen : SelectionEvent
    data object EditNoteChosen : SelectionEvent
}

/** What the machine asks its host to DO after a transition —
 * the reducer stays pure; effects are data. */
sealed interface SelectionEffect {
    data class CopyText(val text: String) : SelectionEffect
    data class AddHighlight(val snapshot: SelectionSnapshot, val note: String) :
        SelectionEffect
    data class LookupWord(val word: String) : SelectionEffect
    data class RemoveHighlight(val highlightId: String) : SelectionEffect
    data class UpdateNote(val highlightId: String, val note: String) :
        SelectionEffect
    data object ClearSelection : SelectionEffect
}

data class SelectionTransition(
    val state: SelectionPopupState,
    val effects: List<SelectionEffect> = emptyList()
)

/**
 * The popup state machine. Rules:
 *  - A polled selection with non-blank text SHOWS the popup;
 *    a polled null hides a Shown popup (the selection went
 *    away) but never interrupts NoteEditing.
 *  - Copy/Highlight/Dictionary complete → Hidden + the effect
 *    (+ clear the toolkit selection).
 *  - Note → NoteEditing; saving adds (or updates) the
 *    highlight and hides; cancelling returns to Shown.
 *  - Tapping a decoration opens its menu: Remove deletes,
 *    Edit note re-opens the editor against that highlight.
 */
fun selectionPopupReducer(
    state: SelectionPopupState,
    event: SelectionEvent
): SelectionTransition = when (state) {
    is SelectionPopupState.Hidden -> when (event) {
        is SelectionEvent.Polled -> {
            val snapshot = event.snapshot
            if (snapshot != null && snapshot.text.isNotBlank()) {
                SelectionTransition(SelectionPopupState.Shown(snapshot))
            } else {
                SelectionTransition(state)
            }
        }
        is SelectionEvent.DecorationTapped ->
            SelectionTransition(SelectionPopupState.HighlightMenu(event.highlight))
        else -> SelectionTransition(state)
    }
    is SelectionPopupState.Shown -> when (event) {
        is SelectionEvent.Polled -> {
            val snapshot = event.snapshot
            when {
                snapshot == null -> SelectionTransition(
                    SelectionPopupState.Hidden,
                    listOf(SelectionEffect.ClearSelection)
                )
                snapshot.text.isBlank() -> SelectionTransition(state)
                snapshot.locatorJson != state.snapshot.locatorJson ->
                    SelectionTransition(SelectionPopupState.Shown(snapshot))
                else -> SelectionTransition(state)
            }
        }
        SelectionEvent.CopyChosen -> SelectionTransition(
            SelectionPopupState.Hidden,
            listOf(
                SelectionEffect.CopyText(state.snapshot.text),
                SelectionEffect.ClearSelection
            )
        )
        SelectionEvent.HighlightChosen -> SelectionTransition(
            SelectionPopupState.Hidden,
            listOf(
                SelectionEffect.AddHighlight(state.snapshot, note = ""),
                SelectionEffect.ClearSelection
            )
        )
        SelectionEvent.NoteChosen -> SelectionTransition(
            SelectionPopupState.NoteEditing(state.snapshot, existingHighlightId = null)
        )
        SelectionEvent.DictionaryChosen -> SelectionTransition(
            SelectionPopupState.Hidden,
            listOf(
                SelectionEffect.LookupWord(state.snapshot.text),
                SelectionEffect.ClearSelection
            )
        )
        SelectionEvent.Dismissed -> SelectionTransition(
            SelectionPopupState.Hidden,
            listOf(SelectionEffect.ClearSelection)
        )
        is SelectionEvent.DecorationTapped ->
            SelectionTransition(SelectionPopupState.HighlightMenu(event.highlight))
        else -> SelectionTransition(state)
    }
    is SelectionPopupState.NoteEditing -> when (event) {
        is SelectionEvent.NoteSaved -> {
            val existing = state.existingHighlightId
            SelectionTransition(
                SelectionPopupState.Hidden,
                if (existing != null) {
                    listOf(
                        SelectionEffect.UpdateNote(existing, event.text),
                        SelectionEffect.ClearSelection
                    )
                } else {
                    listOf(
                        SelectionEffect.AddHighlight(state.snapshot, event.text),
                        SelectionEffect.ClearSelection
                    )
                }
            )
        }
        SelectionEvent.NoteCancelled ->
            if (state.existingHighlightId != null) {
                SelectionTransition(SelectionPopupState.Hidden)
            } else {
                SelectionTransition(SelectionPopupState.Shown(state.snapshot))
            }
        else -> SelectionTransition(state)
    }
    is SelectionPopupState.HighlightMenu -> when (event) {
        SelectionEvent.RemoveHighlightChosen -> SelectionTransition(
            SelectionPopupState.Hidden,
            listOf(SelectionEffect.RemoveHighlight(state.highlight.id))
        )
        SelectionEvent.EditNoteChosen -> {
            val highlight = state.highlight
            val snapshot = SelectionSnapshot(
                locatorJson = highlight.locatorJson.orEmpty(),
                text = highlight.selectedText,
                rect = null,
                href = null
            )
            SelectionTransition(
                SelectionPopupState.NoteEditing(snapshot, highlight.id)
            )
        }
        SelectionEvent.Dismissed -> SelectionTransition(SelectionPopupState.Hidden)
        else -> SelectionTransition(state)
    }
}

// ── PDF Text view: word ranges + spans (pure) ────────────────────

/**
 * The word range around [offset] in [text] — the Text view's
 * selection unit (a long-press selects its word; the four
 * actions then apply to it). Word characters are letters,
 * digits, apostrophes and hyphens; an offset on whitespace
 * yields an empty range at the offset.
 */
fun expandWordRange(text: String, offset: Int): IntRange {
    if (text.isEmpty()) return IntRange.EMPTY
    val at = offset.coerceIn(0, text.length - 1)
    fun isWordChar(ch: Char): Boolean =
        ch.isLetterOrDigit() || ch == '\'' || ch == '-'
    if (!isWordChar(text[at])) return IntRange(at, at - 1)
    var start = at
    while (start > 0 && isWordChar(text[start - 1])) start--
    var end = at
    while (end < text.length - 1 && isWordChar(text[end + 1])) end++
    return IntRange(start, end)
}

/** The char ranges a page's stored highlights cover, clamped
 * to the page text's bounds — the spans the Text view renders. */
fun highlightSpansFor(
    pageText: String,
    page: Int,
    highlights: List<StoredSelectionHighlight>
): List<IntRange> = highlights.mapNotNull { highlight ->
    if (highlight.page != page) return@mapNotNull null
    val start = highlight.charStart ?: return@mapNotNull null
    val end = highlight.charEnd ?: return@mapNotNull null
    val clampedStart = start.coerceIn(0, pageText.length)
    val clampedEnd = end.coerceIn(clampedStart, pageText.length)
    if (clampedEnd <= clampedStart) null else IntRange(clampedStart, clampedEnd - 1)
}

// ── Popup surfaces ───────────────────────────────────────────────

/**
 * The selection action popup: the four shared actions over a
 * live selection. Hosted by the reader at the selection's
 * rect (EPUB) or as the Text view's action bar (PDF) — this
 * content is identical for both (Q3 → B's shared menu).
 */
@Composable
internal fun SelectionActionRow(
    selectedText: String,
    onCopy: () -> Unit,
    onHighlight: () -> Unit,
    onNote: () -> Unit,
    onDictionary: () -> Unit,
    onDismiss: () -> Unit
) {
    Column(
        Modifier.fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            selectedText,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onCopy) { Text("Copy") }
            TextButton(onClick = onHighlight) { Text("Highlight") }
            TextButton(onClick = onNote) { Text("Note") }
            TextButton(onClick = onDictionary) { Text("Dictionary") }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    }
}

/** The note editor: a highlight with words attached. */
@Composable
internal fun SelectionNoteEditor(
    selectedText: String,
    initialNote: String,
    onSave: (String) -> Unit,
    onCancel: () -> Unit
) {
    var text by remember { mutableStateOf(initialNote) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Note") },
        text = {
            Column {
                Text(
                    selectedText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Your note") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    )
}

/** A tapped highlight's menu: its note (if any), Remove, and
 * Edit note. */
@Composable
internal fun SelectionHighlightMenu(
    highlight: StoredSelectionHighlight,
    onRemove: () -> Unit,
    onEditNote: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Highlight") },
        text = {
            Column {
                Text(highlight.selectedText, maxLines = 3)
                if (highlight.hasNote) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        highlight.note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onEditNote) {
                Text(if (highlight.hasNote) "Edit note" else "Add note")
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onRemove) { Text("Remove") }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        }
    )
}
