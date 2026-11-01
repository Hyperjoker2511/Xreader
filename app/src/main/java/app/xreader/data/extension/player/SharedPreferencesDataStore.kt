/*
 * Transplanted verbatim for Xreader from Komikku
 * (app/src/main/java/eu/kanade/tachiyomi/data/preference/
 * SharedPreferencesDataStore.kt @ 1656575e, Apache-2.0) — see the
 * NOTICE file in the :mihon-api module for donor credits. Only the
 * package moved (host code lives with the player, not in the API
 * module extensions link against); the body is the donor's.
 */
package app.xreader.data.extension.player

import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.preference.PreferenceDataStore

/**
 * K5 (Keiyoushi player): the bridge that lets a headless
 * androidx.preference tree persist into ONE SharedPreferences
 * file — the donor's arrangement in Mihon/Komikku's source
 * preferences screen (`preferenceManager.preferenceDataStore =
 * SharedPreferencesDataStore(source.sourcePreferences())`).
 * [SourcePreferenceHost] installs this over the source's OWN
 * preferences (its `getSourcePreferences()`, resolved through
 * the player's per-extension namespace), so every value a
 * preference writes lands in exactly the file the source reads
 * back through its own SharedPreferences calls.
 */
class SharedPreferencesDataStore(
    private val prefs: SharedPreferences
) : PreferenceDataStore() {

    override fun getBoolean(key: String?, defValue: Boolean): Boolean {
        return prefs.getBoolean(key, defValue)
    }

    override fun putBoolean(key: String?, value: Boolean) {
        prefs.edit {
            putBoolean(key, value)
        }
    }

    override fun getInt(key: String?, defValue: Int): Int {
        return prefs.getInt(key, defValue)
    }

    override fun putInt(key: String?, value: Int) {
        prefs.edit {
            putInt(key, value)
        }
    }

    override fun getLong(key: String?, defValue: Long): Long {
        return prefs.getLong(key, defValue)
    }

    override fun putLong(key: String?, value: Long) {
        prefs.edit {
            putLong(key, value)
        }
    }

    override fun getFloat(key: String?, defValue: Float): Float {
        return prefs.getFloat(key, defValue)
    }

    override fun putFloat(key: String?, value: Float) {
        prefs.edit {
            putFloat(key, value)
        }
    }

    override fun getString(key: String?, defValue: String?): String? {
        return prefs.getString(key, defValue)
    }

    override fun putString(key: String?, value: String?) {
        prefs.edit {
            putString(key, value)
        }
    }

    override fun getStringSet(
        key: String?,
        defValues: MutableSet<String>?
    ): MutableSet<String>? {
        return prefs.getStringSet(key, defValues)
    }

    override fun putStringSet(key: String?, values: MutableSet<String>?) {
        prefs.edit {
            putStringSet(key, values)
        }
    }
}
