package app.xreader.ui.sources

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import java.util.IdentityHashMap

/**
 * K5: the catalogue filter sheet's model — pure mappings and
 * state operations over a source's [FilterList], free of
 * Android and Compose types so the whole mapping is
 * unit-testable on the JVM.
 *
 * The donor model (Mihon/Komikku's SourceFilterDialog) is
 * kept: a FilterList's filter objects ARE the state — the
 * sheet edits them in place, and Apply hands the same list
 * to the search. What this file adds around that model:
 * rows as plain data for Compose, the donor's exact state
 * transitions (tri-state cycling, sort tap flips direction),
 * and a snapshot/restore pair so closing the sheet without
 * applying puts every state back — the one place we
 * deliberately differ from the donor, whose sheet leaks
 * unapplied edits into the next open.
 *
 * The model speaks the Mihon filter types because that is
 * what the player adapter exposes today. The seam is one
 * door wide — the catalogue asks its ViewModel for rows and
 * actions, never the loader — so a future source kind that
 * can describe its filters in these kinds adopts the same
 * sheet unchanged.
 */
sealed interface FilterRow {
    /** Tree path ("0", "3/2", …), stable for one FilterList. */
    val id: String
    val name: String

    data class Header(
        override val id: String,
        override val name: String
    ) : FilterRow

    data class Separator(override val id: String) : FilterRow {
        override val name: String = ""
    }

    data class Text(
        override val id: String,
        override val name: String,
        val value: String
    ) : FilterRow

    data class Check(
        override val id: String,
        override val name: String,
        val checked: Boolean
    ) : FilterRow

    data class Tri(
        override val id: String,
        override val name: String,
        /** One of Filter.TriState.STATE_IGNORE / INCLUDE / EXCLUDE. */
        val state: Int
    ) : FilterRow

    data class Select(
        override val id: String,
        override val name: String,
        val options: List<String>,
        val selectedIndex: Int
    ) : FilterRow

    data class Sort(
        override val id: String,
        override val name: String,
        val options: List<String>,
        /** Index of the chosen option, or -1 for none. */
        val selectedIndex: Int,
        val ascending: Boolean
    ) : FilterRow

    data class Group(
        override val id: String,
        override val name: String,
        val children: List<FilterRow>
    ) : FilterRow
}

/** A FilterList's states at one moment, keyed by filter
 * IDENTITY (Filter overrides equals/hashCode by name+state —
 * a value-keyed map would collide on same-named filters and
 * rot as states mutate). Opaque: take one with
 * [FilterStateOps.snapshot], hand it back to
 * [FilterStateOps.restore] / [FilterStateOps.countChanged]. */
class FilterStateSnapshot internal constructor(
    internal val states: IdentityHashMap<Filter<*>, Any?>
)

/** FilterList → sheet rows. Pure: the same list in, the same
 * rows out, one per filter, groups recursed. */
object FilterSheetModel {

    fun rows(filters: FilterList): List<FilterRow> =
        mapLevel(filters.list, parentPath = "")

    private fun mapLevel(
        filters: List<Filter<*>>,
        parentPath: String
    ): List<FilterRow> = filters.mapIndexed { index, filter ->
        val id = if (parentPath.isEmpty()) {
            index.toString()
        } else {
            "$parentPath/$index"
        }
        when (filter) {
            is Filter.Header -> FilterRow.Header(id, filter.name)
            is Filter.Separator -> FilterRow.Separator(id)
            is Filter.Text -> FilterRow.Text(id, filter.name, filter.state)
            is Filter.CheckBox -> FilterRow.Check(id, filter.name, filter.state)
            is Filter.TriState -> FilterRow.Tri(id, filter.name, filter.state)
            is Filter.Sort -> FilterRow.Sort(
                id = id,
                name = filter.name,
                options = filter.values.map { it.toString() },
                selectedIndex = filter.state?.index ?: -1,
                ascending = filter.state?.ascending ?: true
            )
            is Filter.Select<*> -> FilterRow.Select(
                id = id,
                name = filter.name,
                options = filter.values.map { it.toString() },
                selectedIndex = filter.state
            )
            is Filter.Group<*> -> FilterRow.Group(
                id = id,
                name = filter.name,
                children = mapLevel(childrenOf(filter), id)
            )
        }
    }

    /** A group's child filters — the donor reads them from
     * the group's state, filtered to filters (a group's state
     * list is typed by the source's value type; the filter
     * children are what a sheet can render). */
    fun childrenOf(group: Filter.Group<*>): List<Filter<*>> =
        group.state.filterIsInstance<Filter<*>>()

    /** The live filter behind a row [id] (a tree path, as
     * [rows] assigns them), or null when the path doesn't
     * resolve — the sheet resolves each row back to its
     * object to mutate it, so a stale id degrades to a
     * label-only row instead of a crash. */
    fun filterAt(filters: FilterList, id: String): Filter<*>? {
        var level: List<Filter<*>> = filters.list
        var found: Filter<*>? = null
        val segments = id.split('/')
        for ((position, segment) in segments.withIndex()) {
            val index = segment.toIntOrNull() ?: return null
            found = level.getOrNull(index) ?: return null
            if (position < segments.lastIndex) {
                // The path continues: only a group has a next
                // level — anything else means the path is stale.
                val group = found as? Filter.Group<*> ?: return null
                level = childrenOf(group)
            }
        }
        return found
    }
}

/** The sheet's state transitions — the donor's, exactly. */
object FilterStateOps {

    fun toggle(filter: Filter.CheckBox) {
        filter.state = !filter.state
    }

    /** Ignore → Include → Exclude → Ignore (the donor's cycle). */
    fun cycle(filter: Filter.TriState) {
        filter.state = when (filter.state) {
            Filter.TriState.STATE_IGNORE -> Filter.TriState.STATE_INCLUDE
            Filter.TriState.STATE_INCLUDE -> Filter.TriState.STATE_EXCLUDE
            else -> Filter.TriState.STATE_IGNORE
        }
    }

    fun setText(filter: Filter.Text, value: String) {
        filter.state = value
    }

    /** Select an option by index; out-of-range taps (a sheet
     * racing a source that changed its values) are ignored,
     * never a crash. */
    fun select(filter: Filter.Select<*>, index: Int) {
        if (index in filter.values.indices) {
            filter.state = index
        }
    }

    /**
     * The donor's sort tap: tapping the chosen option flips
     * its direction; tapping a new option keeps the direction
     * already in force (ascending when none was).
     */
    fun sortTap(filter: Filter.Sort, index: Int) {
        if (index !in filter.values.indices) return
        val current = filter.state
        val ascending = if (current != null && index == current.index) {
            !current.ascending
        } else {
            current?.ascending ?: true
        }
        filter.state = Filter.Sort.Selection(index, ascending)
    }

    /** The plain word for a tri-state, for the sheet's chips:
     * the three answers to "titles with this?" */
    fun triStateWord(state: Int): String = when (state) {
        Filter.TriState.STATE_INCLUDE -> "Include"
        Filter.TriState.STATE_EXCLUDE -> "Exclude"
        else -> "Any"
    }

    fun snapshot(filters: FilterList): FilterStateSnapshot {
        val states = IdentityHashMap<Filter<*>, Any?>()
        recordLevel(filters.list, states)
        return FilterStateSnapshot(states)
    }

    private fun recordLevel(
        filters: List<Filter<*>>,
        into: IdentityHashMap<Filter<*>, Any?>
    ) {
        for (filter in filters) {
            if (filter is Filter.Group<*>) {
                recordLevel(FilterSheetModel.childrenOf(filter), into)
            } else {
                into[filter] = filter.state
            }
        }
    }

    /** Put every recorded state back (the sheet closed
     * without applying). Filters added since the snapshot
     * keep their states; recorded ones are restored exactly. */
    fun restore(filters: FilterList, snapshot: FilterStateSnapshot) {
        restoreLevel(filters.list, snapshot)
    }

    private fun restoreLevel(
        filters: List<Filter<*>>,
        snapshot: FilterStateSnapshot
    ) {
        for (filter in filters) {
            if (filter is Filter.Group<*>) {
                restoreLevel(FilterSheetModel.childrenOf(filter), snapshot)
            } else if (snapshot.states.containsKey(filter)) {
                @Suppress("UNCHECKED_CAST")
                (filter as Filter<Any?>).state = snapshot.states[filter]
            }
        }
    }

    /** How many leaf filters now differ from [defaults]
     * (a snapshot taken when the list was fresh) — the
     * catalogue's "filters on" count. Groups never count;
     * their children do. */
    fun countChanged(
        filters: FilterList,
        defaults: FilterStateSnapshot
    ): Int {
        var count = 0
        countLevel(filters.list, defaults) { count++ }
        return count
    }

    private fun countLevel(
        filters: List<Filter<*>>,
        defaults: FilterStateSnapshot,
        onChanged: () -> Unit
    ) {
        for (filter in filters) {
            if (filter is Filter.Group<*>) {
                countLevel(
                    FilterSheetModel.childrenOf(filter), defaults, onChanged
                )
            } else if (defaults.states.containsKey(filter) &&
                filter.state != defaults.states[filter]
            ) {
                onChanged()
            }
        }
    }
}
