package app.xreader.ui.update

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Code 121 (R39 Mechanics 1): the update REVEAL's OWN seen-record.
 *
 * This store belongs to the reveal alone:
 *  - sole WRITER: the reveal's own terminal actions in
 *    [WhatsNewReveal] (Try the new design / Keep mine / Skip /
 *    finishing the last card), wired in MainActivity;
 *  - sole READER: the reveal's gate in MainActivity.
 *
 * Nothing else in the app may read or write it. In particular it
 * is NOT [app.xreader.data.prefs.GamePrefs.whatsNewSeenCode]:
 * that store belongs to the Me tab's "What's new in this
 * version" note and to the legacy (non-design-carrying)
 * showcase, and the two records never interact — dismissing the
 * Me note can never suppress the reveal, and dismissing the
 * reveal never marks the note read (the code-120 conviction,
 * designed out).
 *
 * The value is the newest version code whose design-carrying
 * reveal has been terminally dismissed; 0 = none.
 */
class RevealPrefs(private val store: DataStore<Preferences>) {

    val revealSeenCode: Flow<Int> =
        store.data.map { it[KEY_REVEAL_SEEN] ?: 0 }

    suspend fun setRevealSeen(code: Int) {
        store.edit { it[KEY_REVEAL_SEEN] = code }
    }

    companion object {
        /**
         * The version code whose update first carries the new
         * Material 3 design (code 121). An update is
         * design-carrying when it adds a design the installed
         * app did not previously offer (R39 Mechanics 3); for
         * this build that crossing is exactly this code.
         */
        const val DESIGN_CARRYING_CODE: Int = 121

        private val KEY_REVEAL_SEEN =
            intPreferencesKey("reveal.design_seen_code")
    }
}
