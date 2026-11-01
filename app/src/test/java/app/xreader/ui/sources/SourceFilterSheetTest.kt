package app.xreader.ui.sources

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * K5: the catalogue filter sheet's pure model — row mapping
 * for every filter kind in the lib-1.6 model, the donor's
 * state transitions, and the snapshot/restore pair that
 * keeps an unapplied sheet from leaking its edits. Fakes are
 * plain subclasses of the real :mihon-api filter types,
 * exactly the shapes source code compiles against.
 */
class SourceFilterSheetTest {

    private class TextF(name: String, state: String = "") :
        Filter.Text(name, state)

    private class CheckF(name: String, state: Boolean = false) :
        Filter.CheckBox(name, state)

    private class TriF(
        name: String,
        state: Int = Filter.TriState.STATE_IGNORE
    ) : Filter.TriState(name, state)

    private class SelectF(
        name: String,
        values: Array<String>,
        state: Int = 0
    ) : Filter.Select<String>(name, values, state)

    private class SortF(
        name: String,
        values: Array<String>,
        state: Filter.Sort.Selection? = null
    ) : Filter.Sort(name, values, state)

    private class GroupF(
        name: String,
        state: List<Filter<*>>
    ) : Filter.Group<Filter<*>>(name, state)

    private fun sampleList(): FilterList {
        val genreGroup = GroupF(
            "Genres",
            listOf(
                CheckF("Action", false),
                TriF("Romance", Filter.TriState.STATE_INCLUDE)
            )
        )
        return FilterList(
            listOf(
                Filter.Header("Refine"),
                TextF("Author", ""),
                CheckF("Completed only", false),
                TriF("Action", Filter.TriState.STATE_IGNORE),
                SelectF("Status", arrayOf("Any", "Ongoing", "Done"), 1),
                SortF("Sort by", arrayOf("Popular", "Newest")),
                Filter.Separator(),
                genreGroup
            )
        )
    }

    // ── row mapping ─────────────────────────────────────────

    @Test
    fun rowsMapEveryKindWithTreePathIds() {
        val rows = FilterSheetModel.rows(sampleList())
        assertEquals(8, rows.size)
        assertTrue(rows[0] is FilterRow.Header)
        assertEquals("0", rows[0].id)
        val text = rows[1] as FilterRow.Text
        assertEquals("Author", text.name)
        assertEquals("", text.value)
        val check = rows[2] as FilterRow.Check
        assertEquals(false, check.checked)
        val tri = rows[3] as FilterRow.Tri
        assertEquals(Filter.TriState.STATE_IGNORE, tri.state)
        val select = rows[4] as FilterRow.Select
        assertEquals(listOf("Any", "Ongoing", "Done"), select.options)
        assertEquals(1, select.selectedIndex)
        val sort = rows[5] as FilterRow.Sort
        assertEquals(listOf("Popular", "Newest"), sort.options)
        assertEquals(-1, sort.selectedIndex)
        assertTrue(rows[6] is FilterRow.Separator)
        val group = rows[7] as FilterRow.Group
        assertEquals("Genres", group.name)
        assertEquals(2, group.children.size)
        assertEquals("7/0", group.children[0].id)
        assertEquals("7/1", group.children[1].id)
        val groupedTri = group.children[1] as FilterRow.Tri
        assertEquals(Filter.TriState.STATE_INCLUDE, groupedTri.state)
    }

    @Test
    fun filterAtResolvesTopLevelGroupChildrenAndBadPaths() {
        val list = sampleList()
        val top = FilterSheetModel.filterAt(list, "2")
        assertTrue(top is Filter.CheckBox)
        assertEquals("Completed only", top?.name)
        val child = FilterSheetModel.filterAt(list, "7/1")
        assertTrue(child is Filter.TriState)
        assertEquals("Romance", child?.name)
        assertSame(list.list[7], FilterSheetModel.filterAt(list, "7"))
        assertNull(FilterSheetModel.filterAt(list, "99"))
        assertNull(FilterSheetModel.filterAt(list, "x"))
        assertNull(FilterSheetModel.filterAt(list, "2/0"))
    }

    // ── state transitions (the donor's) ─────────────────────

    @Test
    fun toggleFlipsCheckBoxBothWays() {
        val check = CheckF("Completed only")
        FilterStateOps.toggle(check)
        assertTrue(check.state)
        FilterStateOps.toggle(check)
        assertEquals(false, check.state)
    }

    @Test
    fun triStateCyclesIgnoreIncludeExcludeIgnore() {
        val tri = TriF("Action")
        assertEquals(Filter.TriState.STATE_IGNORE, tri.state)
        FilterStateOps.cycle(tri)
        assertEquals(Filter.TriState.STATE_INCLUDE, tri.state)
        FilterStateOps.cycle(tri)
        assertEquals(Filter.TriState.STATE_EXCLUDE, tri.state)
        FilterStateOps.cycle(tri)
        assertEquals(Filter.TriState.STATE_IGNORE, tri.state)
        assertEquals("Any", FilterStateOps.triStateWord(tri.state))
        FilterStateOps.cycle(tri)
        assertEquals("Include", FilterStateOps.triStateWord(tri.state))
        FilterStateOps.cycle(tri)
        assertEquals("Exclude", FilterStateOps.triStateWord(tri.state))
    }

    @Test
    fun setTextAndSelectWriteThrough_selectIgnoresOutOfRange() {
        val text = TextF("Author")
        FilterStateOps.setText(text, "Asura")
        assertEquals("Asura", text.state)
        val select = SelectF("Status", arrayOf("Any", "Ongoing"), 0)
        FilterStateOps.select(select, 1)
        assertEquals(1, select.state)
        FilterStateOps.select(select, 5)
        assertEquals(1, select.state)
        FilterStateOps.select(select, -1)
        assertEquals(1, select.state)
    }

    @Test
    fun sortTapSelectsFlipsAndKeepsDirection() {
        val sort = SortF("Sort by", arrayOf("Popular", "Newest", "A–Z"))
        // First tap: selects, ascending (the donor's default).
        FilterStateOps.sortTap(sort, 1)
        assertEquals(Filter.Sort.Selection(1, true), sort.state)
        // Same option again: direction flips.
        FilterStateOps.sortTap(sort, 1)
        assertEquals(Filter.Sort.Selection(1, false), sort.state)
        // A new option keeps the direction in force.
        FilterStateOps.sortTap(sort, 2)
        assertEquals(Filter.Sort.Selection(2, false), sort.state)
        // Out of range: untouched.
        FilterStateOps.sortTap(sort, 9)
        assertEquals(Filter.Sort.Selection(2, false), sort.state)
    }

    // ── snapshot / restore / count ──────────────────────────

    @Test
    fun restorePutsBackEveryStateIncludingGroupChildren() {
        val list = sampleList()
        val snapshot = FilterStateOps.snapshot(list)
        // Mutate one of each shape, incl. inside the group.
        FilterStateOps.setText(
            FilterSheetModel.filterAt(list, "1") as Filter.Text, "X"
        )
        FilterStateOps.toggle(
            FilterSheetModel.filterAt(list, "2") as Filter.CheckBox
        )
        FilterStateOps.cycle(
            FilterSheetModel.filterAt(list, "7/1") as Filter.TriState
        )
        FilterStateOps.sortTap(
            FilterSheetModel.filterAt(list, "5") as Filter.Sort, 0
        )
        FilterStateOps.restore(list, snapshot)
        assertEquals(
            "",
            (FilterSheetModel.filterAt(list, "1") as Filter.Text).state
        )
        assertEquals(
            false,
            (FilterSheetModel.filterAt(list, "2") as Filter.CheckBox).state
        )
        assertEquals(
            Filter.TriState.STATE_INCLUDE,
            (FilterSheetModel.filterAt(list, "7/1") as Filter.TriState).state
        )
        assertNull(
            (FilterSheetModel.filterAt(list, "5") as Filter.Sort).state
        )
    }

    @Test
    fun snapshotKeysByIdentitySoSameNamedFiltersStayIndependent() {
        // Two filters with the same name AND equal states:
        // Filter.equals/hashCode (name+state) would merge them
        // in a value-keyed map and restore both to one value.
        val first = CheckF("Action", false)
        val second = CheckF("Action", false)
        val list = FilterList(listOf(first, second))
        val snapshot = FilterStateOps.snapshot(list)
        FilterStateOps.toggle(first)
        FilterStateOps.restore(list, snapshot)
        assertEquals(false, first.state)
        assertEquals(false, second.state)
        // And a change to only the second still counts once.
        FilterStateOps.toggle(second)
        assertEquals(1, FilterStateOps.countChanged(list, snapshot))
    }

    @Test
    fun countChangedCountsLeavesOnlyAndSeesThroughGroups() {
        val list = sampleList()
        val defaults = FilterStateOps.snapshot(list)
        assertEquals(0, FilterStateOps.countChanged(list, defaults))
        FilterStateOps.toggle(
            FilterSheetModel.filterAt(list, "2") as Filter.CheckBox
        )
        FilterStateOps.cycle(
            FilterSheetModel.filterAt(list, "7/1") as Filter.TriState
        )
        FilterStateOps.select(
            FilterSheetModel.filterAt(list, "4") as Filter.Select<*>, 2
        )
        assertEquals(3, FilterStateOps.countChanged(list, defaults))
        // Back to the default = no longer counted.
        FilterStateOps.toggle(
            FilterSheetModel.filterAt(list, "2") as Filter.CheckBox
        )
        assertEquals(2, FilterStateOps.countChanged(list, defaults))
    }
}
