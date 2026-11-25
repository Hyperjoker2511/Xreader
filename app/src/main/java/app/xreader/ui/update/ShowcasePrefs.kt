package app.xreader.ui.update

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Code 125 (F4): the legacy showcase's OWN seen-record.
 *
 * This store belongs to the showcase alone:
 *  - sole WRITER: the showcase's own terminal actions in
 *    MainActivity (Skip / Done on the last card / Back — the
 *    BackHandler inside [WhatsNewShowcase] routes Back
 *    through the same finish), wired in MainActivity;
 *  - sole READER: the showcase's gate in MainActivity.
 *
 * Nothing else in the app may read or write it. In
 * particular it is NOT [app.xreader.data.prefs.GamePrefs
 * .whatsNewSeenCode]: that store belongs to the Me tab's
 * "What's new in this version" note alone now. The two
 * records shared one store before this build, and the
 * sharing was the known failure mode — dismissing the Me
 * note wrote the showcase's seen record and suppressed the
 * showcase (the code-120 conviction), and finishing the
 * showcase hid the Me note. Each record now answers only
 * for its own surface.
 *
 * The value is the newest version code whose showcase has
 * been terminally dismissed; 0 = none.
 */
class ShowcasePrefs(private val store: DataStore<Preferences>) {

    val showcaseSeenCode: Flow<Int> =
        store.data.map { it[KEY_SHOWCASE_SEEN] ?: 0 }

    suspend fun setShowcaseSeen(code: Int) {
        store.edit { it[KEY_SHOWCASE_SEEN] = code }
    }

    companion object {
        private val KEY_SHOWCASE_SEEN =
            intPreferencesKey("showcase.seen_code")
    }
}
