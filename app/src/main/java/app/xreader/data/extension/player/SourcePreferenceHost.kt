package app.xreader.data.extension.player

import android.content.Context
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import androidx.preference.TwoStatePreference
import eu.kanade.tachiyomi.source.ConfigurableSource

/** What one row of a source's settings screen is. */
enum class SourcePrefKind {
    /** A category / titled group heading (not editable). */
    SECTION,
    SWITCH,
    CHECKBOX,
    /** One choice from a fixed list (ListPreference). */
    CHOICE,
    /** Free text (EditTextPreference). */
    TEXT,
    /** Any subset of a fixed list (MultiSelectListPreference). */
    MULTI_CHOICE,
    /** A plain preference: tapping it is the whole action. */
    ACTION
}

/**
 * One row of a source's settings, as plain data the Compose
 * screen renders. [id] is the row's path in the preference
 * tree ("2", "2/1", …) — stable for one build of the screen,
 * and every edit is followed by a fresh snapshot, so the UI
 * always acts on rows from the latest build.
 */
data class SourcePrefRow(
    val id: String,
    val kind: SourcePrefKind,
    val title: String,
    val summary: String? = null,
    val enabled: Boolean = true,
    /** SWITCH / CHECKBOX: the current state. */
    val checked: Boolean = false,
    /** CHOICE / MULTI_CHOICE: the visible labels, in order. */
    val options: List<String> = emptyList(),
    /** CHOICE / MULTI_CHOICE: the stored value behind each
     * label in [options]. */
    val optionValues: List<String> = emptyList(),
    /** CHOICE: index into [options], or -1 when the stored
     * value matches none of them. */
    val selectedIndex: Int = -1,
    /** MULTI_CHOICE: the stored values currently chosen. */
    val selectedValues: Set<String> = emptySet(),
    /** TEXT: the current text. */
    val text: String = ""
)

/**
 * K5 (Keiyoushi player): a [ConfigurableSource]'s own
 * preference screen, hosted headlessly — the donor's
 * arrangement (Komikku's SourcePreferencesFragment) with the
 * fragment replaced by an object-tree walk:
 *
 * 1. a [PreferenceManager] over the player's context,
 * 2. its data store = the donor's [SharedPreferencesDataStore]
 *    wrapping the source's OWN preferences
 *    (`getSourcePreferences()` — the file the source itself
 *    reads, resolved through the K2 per-extension namespace),
 * 3. `createPreferenceScreen` + the source's
 *    `setupPreferenceScreen(screen)` builds the real
 *    androidx.preference tree,
 * 4. the tree is snapshotted to [SourcePrefRow]s for Compose,
 *    and edits write back through the live preference objects
 *    (their own setters — change listeners included, vetoes
 *    honoured), after which a fresh snapshot is returned so
 *    the screen shows what actually stuck.
 *
 * Every call runs bracketed with the package's namespace
 * ([PlayerContextState.withNamespace]): `setupPreferenceScreen`
 * and change listeners may resolve the injected Application
 * or the source's preferences again mid-call, and those reads
 * must land in this extension's namespace. Callers run the
 * host on a background thread (the catalogue/prefs ViewModels
 * use Dispatchers.IO); the host itself is confined to
 * whichever thread drives it.
 *
 * The four editable kinds are the ones the K0 inventory
 * proved real extensions use (Asura: SwitchPreferenceCompat;
 * MangaDex: EditText / List / MultiSelect / Switch). Anything
 * else a source adds still renders — as an [SourcePrefKind.ACTION]
 * row when it is a plain preference, a section when it groups
 * others — never a fake control.
 */
class SourcePreferenceHost(
    /** The player's application context (the wrapper extensions
     * see) in production; the manager and the screen are built
     * over it so any context read inside the source's setup
     * resolves the player's way. */
    private val context: Context,
    /** The Keiyoushi package name — the namespace every call
     * is bracketed with. */
    private val packageName: String,
    private val source: ConfigurableSource
) {

    private var screen: PreferenceScreen? = null
    private val liveById = LinkedHashMap<String, Preference>()

    /** Build (or rebuild) the screen and snapshot its rows.
     * Throws whatever the source's setup throws — the caller
     * words it. */
    fun build(): List<SourcePrefRow> = bracketed {
        val manager = PreferenceManager(context)
        // The donor order matters: the data store is installed
        // BEFORE setup runs, so preferences created during
        // setup attach to the source's own store and read
        // their persisted values from it.
        manager.preferenceDataStore =
            SharedPreferencesDataStore(source.getSourcePreferences())
        val built = manager.createPreferenceScreen(context)
        source.setupPreferenceScreen(built)
        screen = built
        snapshotLocked()
    }

    /** Flip a SWITCH / CHECKBOX row. The preference's own
     * setter persists (and runs its change listener — a veto
     * simply doesn't stick, and the returned snapshot says so). */
    fun setChecked(id: String, value: Boolean): List<SourcePrefRow> =
        bracketed {
            val pref = live(id) as? TwoStatePreference
                ?: return@bracketed snapshotLocked()
            pref.isChecked = value
            snapshotLocked()
        }

    /** Choose a CHOICE row's value (a stored value from the
     * row's [SourcePrefRow.optionValues]). */
    fun setChoice(id: String, value: String): List<SourcePrefRow> =
        bracketed {
            val pref = live(id) as? ListPreference
                ?: return@bracketed snapshotLocked()
            pref.value = value
            snapshotLocked()
        }

    /** Set a TEXT row's text. */
    fun setText(id: String, value: String): List<SourcePrefRow> =
        bracketed {
            val pref = live(id) as? EditTextPreference
                ?: return@bracketed snapshotLocked()
            pref.text = value
            snapshotLocked()
        }

    /** Set a MULTI_CHOICE row's chosen values. */
    fun setMultiChoice(
        id: String,
        values: Set<String>
    ): List<SourcePrefRow> = bracketed {
        val pref = live(id) as? MultiSelectListPreference
            ?: return@bracketed snapshotLocked()
        pref.values = values
        snapshotLocked()
    }

    /** Tap an ACTION row: the preference's own click path
     * (its click listener; a plain preference does nothing,
     * which is the honest outcome for a label-only row). */
    fun click(id: String): List<SourcePrefRow> = bracketed {
        live(id)?.performClick()
        snapshotLocked()
    }

    // ── internals ───────────────────────────────────────────

    private fun live(id: String): Preference? = liveById[id]

    private fun <T> bracketed(block: () -> T): T =
        PlayerContextState.withNamespace(packageName) { block() }

    /** Walk the built tree into rows. Must be called inside
     * the namespace bracket (a SummaryProvider may read the
     * source's preferences). */
    private fun snapshotLocked(): List<SourcePrefRow> {
        val root = screen ?: return emptyList()
        liveById.clear()
        val rows = mutableListOf<SourcePrefRow>()
        walk(root, parentPath = "", into = rows)
        return rows
    }

    private fun walk(
        group: PreferenceGroup,
        parentPath: String,
        into: MutableList<SourcePrefRow>
    ) {
        for (index in 0 until group.preferenceCount) {
            val pref = group.getPreference(index)
            if (!pref.isVisible) continue
            val id = if (parentPath.isEmpty()) {
                index.toString()
            } else {
                "$parentPath/$index"
            }
            when {
                pref is PreferenceCategory -> {
                    sectionRow(pref, id)?.let { into += it }
                    liveById[id] = pref
                    walk(pref, id, into)
                }
                pref is PreferenceGroup -> {
                    // A titled group (a plain PreferenceGroup a
                    // source nested its settings under) reads
                    // as a section; an untitled one is just
                    // structure and flattens away.
                    val title = pref.title?.toString().orEmpty()
                    if (title.isNotBlank()) {
                        into += SourcePrefRow(
                            id = id,
                            kind = SourcePrefKind.SECTION,
                            title = title,
                            summary = summaryOf(pref, null),
                            enabled = pref.isEnabled
                        )
                        liveById[id] = pref
                    }
                    walk(pref, id, into)
                }
                else -> {
                    leafRow(pref, id)?.let { row ->
                        into += row
                        liveById[id] = pref
                    }
                }
            }
        }
    }

    private fun sectionRow(
        category: PreferenceCategory,
        id: String
    ): SourcePrefRow? {
        val title = category.title?.toString().orEmpty()
        if (title.isBlank()) return null
        return SourcePrefRow(
            id = id,
            kind = SourcePrefKind.SECTION,
            title = title,
            summary = summaryOf(category, null),
            enabled = category.isEnabled
        )
    }

    private fun leafRow(pref: Preference, id: String): SourcePrefRow? {
        val title = pref.title?.toString().orEmpty()
        return when (pref) {
            is TwoStatePreference -> {
                // Belt and braces with the framework's own
                // attach-time sync: the persisted value, read
                // through the store, wins over the field.
                val checked = pref.persistedBoolean(pref.isChecked)
                SourcePrefRow(
                    id = id,
                    kind = if (pref is SwitchPreferenceCompat) {
                        SourcePrefKind.SWITCH
                    } else {
                        SourcePrefKind.CHECKBOX
                    },
                    title = title,
                    summary = summaryOf(pref, null),
                    enabled = pref.isEnabled,
                    checked = checked
                )
            }
            is ListPreference -> {
                val values = pref.entryValues
                    ?.map { it.toString() } ?: emptyList()
                val labels = pref.entries
                    ?.map { it.toString() } ?: values
                val current = pref.persistedString(pref.value)
                SourcePrefRow(
                    id = id,
                    kind = SourcePrefKind.CHOICE,
                    title = title,
                    summary = summaryOf(
                        pref,
                        current?.let { value ->
                            val at = values.indexOf(value)
                            if (at >= 0) labels.getOrNull(at) else value
                        }
                    ),
                    enabled = pref.isEnabled,
                    options = labels,
                    optionValues = values,
                    selectedIndex = current
                        ?.let { values.indexOf(it) } ?: -1
                )
            }
            is EditTextPreference -> {
                val current = pref.persistedString(pref.text)
                    ?: ""
                SourcePrefRow(
                    id = id,
                    kind = SourcePrefKind.TEXT,
                    title = title,
                    summary = summaryOf(pref, current),
                    enabled = pref.isEnabled,
                    text = current
                )
            }
            is MultiSelectListPreference -> {
                val values = pref.entryValues
                    ?.map { it.toString() } ?: emptyList()
                val labels = pref.entries
                    ?.map { it.toString() } ?: values
                val current = pref.getPersistedStringSet(pref.values)
                    ?: emptySet()
                SourcePrefRow(
                    id = id,
                    kind = SourcePrefKind.MULTI_CHOICE,
                    title = title,
                    summary = summaryOf(pref, null),
                    enabled = pref.isEnabled,
                    options = labels,
                    optionValues = values,
                    selectedValues = current
                )
            }
            else -> {
                // A spacer (no title, no summary) carries
                // nothing to render; anything else is an
                // action/label row.
                val summary = summaryOf(pref, null)
                if (title.isBlank() && summary.isNullOrBlank()) {
                    null
                } else {
                    SourcePrefRow(
                        id = id,
                        kind = SourcePrefKind.ACTION,
                        title = title,
                        summary = summary,
                        enabled = pref.isEnabled
                    )
                }
            }
        }
    }

    /** The summary a row shows: the source's SummaryProvider
     * when it set one (it computes from the live preference),
     * else the raw summary with the framework's "%s"
     * current-value substitution. */
    private fun summaryOf(
        pref: Preference,
        currentValue: String?
    ): String? {
        val provider = pref.summaryProvider
        if (provider != null) {
            @Suppress("UNCHECKED_CAST")
            val typed = provider as Preference.SummaryProvider<Preference>
            return runCatching { typed.provideSummary(pref)?.toString() }
                .getOrNull()
        }
        return PrefSummaryText.display(
            pref.summary?.toString(),
            currentValue
        )
    }

    // androidx.preference keeps getPersistedBoolean/getPersistedString
    // (and shouldPersist itself) protected — the framework calls them
    // from inside Preference subclasses; the headless host is not one,
    // so these helpers reproduce the framework's own lookup through its
    // public surface: the framework's shouldPersist() is exactly
    // "manager attached && isPersistent && key non-empty"; then a data
    // store wins, else the manager's SharedPreferences.
    // (getPersistedStringSet IS public in the framework, so the
    // MultiSelect branch reads it directly above.)
    private fun Preference.persistActive(): Boolean =
        preferenceManager != null && isPersistent &&
            !key.isNullOrEmpty()

    private fun Preference.persistedBoolean(defValue: Boolean): Boolean {
        if (!persistActive()) return defValue
        val store = preferenceDataStore
        if (store != null) return store.getBoolean(key, defValue)
        val prefs = preferenceManager?.sharedPreferences ?: return defValue
        return prefs.getBoolean(key, defValue)
    }

    private fun Preference.persistedString(defValue: String?): String? {
        if (!persistActive()) return defValue
        val store = preferenceDataStore
        if (store != null) return store.getString(key, defValue)
        val prefs = preferenceManager?.sharedPreferences ?: return defValue
        return prefs.getString(key, defValue)
    }

    companion object {
        /** Whether a Mihon source carries settings of its own
         * at all — the catalogue/prefs screens ask this before
         * offering the door; a source that isn't configurable
         * gets the honest "no settings of its own" line. */
        fun hasSettings(source: eu.kanade.tachiyomi.source.Source): Boolean =
            source is ConfigurableSource
    }
}
