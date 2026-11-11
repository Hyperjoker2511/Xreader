package app.xreader.ui.localreader

import app.xreader.fixtures.FakePreferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.5.10 (F3) gate: text selection — §6 Q3 → B, all four
 * actions (Copy · Highlight · Note · Dictionary) in EPUB and
 * the PDF Text view. The reducer is the shared state machine;
 * the EPUB poll is faked through the navigator seam (Readium
 * 3.1.2 has no selection callback — polling is the AAR-truth
 * shape); PDF highlights are page-anchored.
 */
class V1510EpubSelectionTest {

    private val snapshot = SelectionSnapshot(
        locatorJson = """{"href":"ch1.xhtml"}""",
        text = "a chosen phrase",
        rect = SelectionRect(10f, 20f, 110f, 40f),
        href = "ch1.xhtml"
    )

    // ── Reducer ─────────────────────────────────────────────

    @Test
    fun `poll shows popup only for a real selection`() {
        val shown = selectionPopupReducer(
            SelectionPopupState.Hidden, SelectionEvent.Polled(snapshot)
        )
        assertEquals(SelectionPopupState.Shown(snapshot), shown.state)
        val blank = selectionPopupReducer(
            SelectionPopupState.Hidden,
            SelectionEvent.Polled(snapshot.copy(text = "  "))
        )
        assertEquals(SelectionPopupState.Hidden, blank.state)
        val none = selectionPopupReducer(
            SelectionPopupState.Hidden, SelectionEvent.Polled(null)
        )
        assertEquals(SelectionPopupState.Hidden, none.state)
        // A selection that goes away hides a shown popup.
        val gone = selectionPopupReducer(
            SelectionPopupState.Shown(snapshot), SelectionEvent.Polled(null)
        )
        assertEquals(SelectionPopupState.Hidden, gone.state)
        assertEquals(
            listOf(SelectionEffect.ClearSelection), gone.effects
        )
    }

    @Test
    fun `highlight choice adds with empty note and clears`() {
        val transition = selectionPopupReducer(
            SelectionPopupState.Shown(snapshot),
            SelectionEvent.HighlightChosen
        )
        assertEquals(SelectionPopupState.Hidden, transition.state)
        assertEquals(
            listOf(
                SelectionEffect.AddHighlight(snapshot, note = ""),
                SelectionEffect.ClearSelection
            ),
            transition.effects
        )
    }

    @Test
    fun `copy and dictionary effects carry the text`() {
        val copy = selectionPopupReducer(
            SelectionPopupState.Shown(snapshot), SelectionEvent.CopyChosen
        )
        assertEquals(
            listOf(
                SelectionEffect.CopyText("a chosen phrase"),
                SelectionEffect.ClearSelection
            ),
            copy.effects
        )
        val dict = selectionPopupReducer(
            SelectionPopupState.Shown(snapshot), SelectionEvent.DictionaryChosen
        )
        assertEquals(
            listOf(
                SelectionEffect.LookupWord("a chosen phrase"),
                SelectionEffect.ClearSelection
            ),
            dict.effects
        )
    }

    @Test
    fun `note flow adds a noted highlight and cancel returns`() {
        val editing = selectionPopupReducer(
            SelectionPopupState.Shown(snapshot), SelectionEvent.NoteChosen
        )
        assertEquals(
            SelectionPopupState.NoteEditing(snapshot, null), editing.state
        )
        val saved = selectionPopupReducer(
            editing.state, SelectionEvent.NoteSaved("remember this")
        )
        assertEquals(SelectionPopupState.Hidden, saved.state)
        assertEquals(
            listOf(
                SelectionEffect.AddHighlight(snapshot, "remember this"),
                SelectionEffect.ClearSelection
            ),
            saved.effects
        )
        val cancelled = selectionPopupReducer(
            editing.state, SelectionEvent.NoteCancelled
        )
        assertEquals(SelectionPopupState.Shown(snapshot), cancelled.state)
    }

    @Test
    fun `decoration menu removes or edits existing highlight`() {
        val highlight = StoredSelectionHighlight(
            id = "h1", locatorJson = """{"href":"ch1.xhtml"}""",
            selectedText = "a chosen phrase", note = "old"
        )
        val menu = selectionPopupReducer(
            SelectionPopupState.Hidden,
            SelectionEvent.DecorationTapped(highlight)
        )
        assertEquals(SelectionPopupState.HighlightMenu(highlight), menu.state)
        val removed = selectionPopupReducer(
            menu.state, SelectionEvent.RemoveHighlightChosen
        )
        assertEquals(
            listOf(SelectionEffect.RemoveHighlight("h1")), removed.effects
        )
        val edit = selectionPopupReducer(
            menu.state, SelectionEvent.EditNoteChosen
        )
        assertEquals(
            SelectionPopupState.NoteEditing(
                SelectionSnapshot(
                    locatorJson = """{"href":"ch1.xhtml"}""",
                    text = "a chosen phrase",
                    rect = null,
                    href = null
                ),
                "h1"
            ),
            edit.state
        )
        // Saving from an existing highlight updates its note.
        val saved = selectionPopupReducer(
            edit.state, SelectionEvent.NoteSaved("new note")
        )
        assertEquals(
            listOf(
                SelectionEffect.UpdateNote("h1", "new note"),
                SelectionEffect.ClearSelection
            ),
            saved.effects
        )
    }

    @Test
    fun `selection menu actions are the four shared actions`() {
        // v1.5.12 (E3): the 280 ms poll policy this test used to
        // pin is DELETED with the poll itself — selection rides
        // the native ActionMode now. What must survive is the
        // ACTION SET: the same four actions the popup offered,
        // contributed as native menu items.
        assertEquals(
            listOf("Copy", "Highlight", "Note", "Dictionary"),
            EPUB_SELECTION_MENU.map { it.label }
        )
        EPUB_SELECTION_MENU.forEach { action ->
            assertEquals(action, EpubSelectionMenuAction.fromItemId(action.itemId))
        }
    }

    // ── Decorations ─────────────────────────────────────────

    @Test
    fun `decorations rebuild from epub highlights only`() {
        val epub = StoredSelectionHighlight(
            id = "e1", locatorJson = """{"href":"a"}""", selectedText = "x"
        )
        val pdf = StoredSelectionHighlight(
            id = "p1", page = 3, charStart = 0, charEnd = 5, selectedText = "y"
        )
        val specs = decorationsFor(listOf(epub, pdf))
        assertEquals(1, specs.size)
        assertEquals("e1", specs.single().highlightId)
        assertEquals("""{"href":"a"}""", specs.single().locatorJson)
        assertEquals("user_highlights", SELECTION_DECORATION_GROUP)
    }

    @Test
    fun `highlight codec round trips`() {
        val highlight = StoredSelectionHighlight(
            id = "h9", locatorJson = null, page = 2, charStart = 3,
            charEnd = 9, selectedText = "pdf words", note = "n",
            createdAtEpochMillis = 42L
        )
        assertEquals(
            listOf(highlight),
            decodeSelectionHighlights(encodeSelectionHighlights(listOf(highlight)))
        )
        assertEquals(
            emptyList<StoredSelectionHighlight>(),
            decodeSelectionHighlights("junk")
        )
        assertEquals(
            emptyList<StoredSelectionHighlight>(),
            decodeSelectionHighlights(null)
        )
    }

    // ── Store ───────────────────────────────────────────────

    @Test
    fun `store adds removes and updates notes per book`() = runTest {
        val store = SelectionHighlightStore(FakePreferencesDataStore())
        val epub = StoredSelectionHighlight(
            id = "e1", locatorJson = """{"href":"a"}""", selectedText = "x"
        )
        val pdf = StoredSelectionHighlight(
            id = "p1", page = 3, charStart = 0, charEnd = 5, selectedText = "y"
        )
        store.add(1L, epub)
        store.add(1L, pdf)
        assertEquals(listOf("e1", "p1"), store.highlights(1L).first().map { it.id })
        store.updateNote(1L, "e1", "a note")
        assertEquals("a note", store.highlights(1L).first().first().note)
        store.remove(1L, "p1")
        assertEquals(listOf("e1"), store.highlights(1L).first().map { it.id })
        assertTrue(store.highlights(2L).first().isEmpty())
    }

    // ── Fake navigator drive ────────────────────────────────

    private class FakeNavigator(
        var selection: SelectionSnapshot?
    ) : EpubSelectionNavigator {
        var applied: List<SelectionDecorationSpec> = emptyList()
        override suspend fun currentSelection(): SelectionSnapshot? = selection
        override fun clearSelection() {
            selection = null
        }
        override suspend fun applyDecorations(
            decorations: List<SelectionDecorationSpec>
        ) {
            applied = decorations
        }
    }

    @Test
    fun `navigator drive - poll, highlight, rebuild decorations`() = runTest {
        val store = SelectionHighlightStore(FakePreferencesDataStore())
        val navigator = FakeNavigator(snapshot)
        var state: SelectionPopupState = SelectionPopupState.Hidden

        // The poll lands a selection → popup shows.
        var transition = selectionPopupReducer(
            state, SelectionEvent.Polled(navigator.currentSelection())
        )
        state = transition.state
        assertTrue(state is SelectionPopupState.Shown)

        // Highlight chosen → the wiring's effects: persist the
        // snapshot as a highlight, clear, and rebuild
        // decorations from the store.
        transition = selectionPopupReducer(state, SelectionEvent.HighlightChosen)
        state = transition.state
        val add = transition.effects
            .filterIsInstance<SelectionEffect.AddHighlight>().single()
        store.add(
            1L,
            StoredSelectionHighlight(
                id = "new-1",
                locatorJson = add.snapshot.locatorJson,
                selectedText = add.snapshot.text,
                note = add.note
            )
        )
        navigator.clearSelection()
        assertNull(navigator.currentSelection())
        navigator.applyDecorations(decorationsFor(store.highlights(1L).first()))
        assertEquals(listOf("new-1"), navigator.applied.map { it.highlightId })
        // Poll again with no selection = stays hidden.
        transition = selectionPopupReducer(
            state, SelectionEvent.Polled(navigator.currentSelection())
        )
        assertEquals(SelectionPopupState.Hidden, transition.state)
    }

    // ── PDF text view helpers ───────────────────────────────

    @Test
    fun `expandWordRange grows to word edges`() {
        val text = "the quick brown fox"
        // Offset inside "quick" (index 6).
        assertEquals(4..8, expandWordRange(text, 6))
        // Offset on whitespace = an empty range at the offset.
        assertTrue(expandWordRange(text, 3).isEmpty())
        // End of text: "fox".
        assertEquals(16..18, expandWordRange(text, 18))
        // Out-of-range offsets clamp.
        assertEquals(0..2, expandWordRange(text, -1))
        assertEquals(IntRange.EMPTY, expandWordRange("", 0))
    }

    @Test
    fun `pdf spans are page scoped`() {
        val highlights = listOf(
            StoredSelectionHighlight(
                id = "p1", page = 2, charStart = 4, charEnd = 9, selectedText = "quick"
            ),
            StoredSelectionHighlight(
                id = "p2", page = 3, charStart = 0, charEnd = 3, selectedText = "the"
            ),
            StoredSelectionHighlight(
                id = "e1", locatorJson = "{}", selectedText = "epub"
            )
        )
        assertEquals(
            listOf(4..8),
            highlightSpansFor("the quick brown fox", 2, highlights)
        )
        assertTrue(highlightSpansFor("the quick brown fox", 9, highlights).isEmpty())
    }
}
