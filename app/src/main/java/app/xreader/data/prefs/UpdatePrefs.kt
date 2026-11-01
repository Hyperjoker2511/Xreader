package app.xreader.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * In-app updater state (v1.3.5): the auto-check switch, the
 * once-a-day bookkeeping (last check day, card-shown day)
 * and the cached latest-release payload — cached so an app
 * open that isn't due a network check can still reason
 * about an update found earlier, offline.
 */
class UpdatePrefs(private val store: DataStore<Preferences>) {

    /** The Settings switch; on unless the user turns it off. */
    val autoCheckEnabled: Flow<Boolean> =
        store.data.map { it[KEY_AUTO_CHECK] ?: true }

    suspend fun setAutoCheckEnabled(enabled: Boolean) {
        store.edit { it[KEY_AUTO_CHECK] = enabled }
    }

    suspend fun autoCheckEnabledNow(): Boolean = autoCheckEnabled.first()

    /** ISO local day of the last completed check, "" if never. */
    suspend fun lastCheckDay(): String =
        store.data.first()[KEY_LAST_CHECK_DAY] ?: ""

    suspend fun setLastCheckDay(day: String) {
        store.edit { it[KEY_LAST_CHECK_DAY] = day }
    }

    /** ISO local day the floating card was last shown, "" if
     * never. The once-a-day rule reads this. */
    suspend fun cardShownDay(): String =
        store.data.first()[KEY_CARD_SHOWN_DAY] ?: ""

    suspend fun setCardShownDay(day: String) {
        store.edit { it[KEY_CARD_SHOWN_DAY] = day }
    }

    /** The raw releases/latest payload from the last good
     * check ("" if none) — parsed on demand by the updater. */
    suspend fun cachedReleaseJson(): String =
        store.data.first()[KEY_CACHED_RELEASE] ?: ""

    suspend fun setCachedReleaseJson(json: String) {
        store.edit { it[KEY_CACHED_RELEASE] = json }
    }

    private companion object {
        val KEY_AUTO_CHECK = booleanPreferencesKey("update.auto_check")
        val KEY_LAST_CHECK_DAY = stringPreferencesKey("update.last_check_day")
        val KEY_CARD_SHOWN_DAY = stringPreferencesKey("update.card_shown_day")
        val KEY_CACHED_RELEASE = stringPreferencesKey("update.cached_release_json")
    }
}
