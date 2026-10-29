package app.xreader.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

/**
 * The app's DataStore files (PLAN §1.3 — preferences live in DataStore,
 * never Room). Two files in Phase 1, one owner class each:
 *  - `settings` → owned by [AppearancePrefs] + [OnboardingPrefs]
 *  - `key_vault` → owned by `KeyVault` (ciphertext only)
 * The delegates live here so each file has exactly one instance.
 */
val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

val Context.keyVaultDataStore: DataStore<Preferences> by preferencesDataStore(name = "key_vault")
