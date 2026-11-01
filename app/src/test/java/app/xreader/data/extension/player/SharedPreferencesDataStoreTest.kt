package app.xreader.data.extension.player

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * K5: the donor's SharedPreferences bridge — the store every
 * preference type persists through in the headless host
 * (TwoState → boolean, List → string, EditText → string,
 * MultiSelect → string set). Round-trips per type against an
 * in-memory SharedPreferences with the real store's own
 * semantics (a null string removes the key), so what the
 * host writes is what a source reads back.
 */
class SharedPreferencesDataStoreTest {

    private class FakeEditor(
        private val owner: FakeSharedPreferences
    ) : SharedPreferences.Editor {
        private val puts = mutableMapOf<String, Any?>()
        private val removals = mutableSetOf<String>()
        private var clear = false

        override fun putString(key: String, value: String?) =
            apply { if (value == null) removals += key else puts[key] = value }
        override fun putStringSet(key: String, values: MutableSet<String>?) =
            apply { if (values == null) removals += key else puts[key] = values }
        override fun putInt(key: String, value: Int) = apply { puts[key] = value }
        override fun putLong(key: String, value: Long) = apply { puts[key] = value }
        override fun putFloat(key: String, value: Float) = apply { puts[key] = value }
        override fun putBoolean(key: String, value: Boolean) =
            apply { puts[key] = value }
        override fun remove(key: String) = apply { removals += key }
        override fun clear() = apply { clear = true }
        override fun commit(): Boolean {
            apply()
            return true
        }
        override fun apply() {
            if (clear) owner.values.clear()
            removals.forEach { owner.values.remove(it) }
            puts.forEach { (key, value) -> owner.values[key] = value }
        }
    }

    private class FakeSharedPreferences : SharedPreferences {
        val values = mutableMapOf<String, Any?>()

        @Suppress("UNCHECKED_CAST")
        override fun getAll(): Map<String, *> = values.toMap()
        override fun getString(key: String, defValue: String?): String? =
            values[key] as? String ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(
            key: String,
            defValues: MutableSet<String>?
        ): MutableSet<String>? =
            (values[key] as? Set<String>)?.toMutableSet() ?: defValues
        override fun getInt(key: String, defValue: Int): Int =
            values[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long): Long =
            values[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float): Float =
            values[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean =
            values[key] as? Boolean ?: defValue
        override fun contains(key: String): Boolean = values.containsKey(key)
        override fun edit(): SharedPreferences.Editor = FakeEditor(this)
        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) = Unit
    }

    @Test
    fun booleanRoundTrips_theSwitchShape() {
        val prefs = FakeSharedPreferences()
        val store = SharedPreferencesDataStore(prefs)
        assertTrue(store.getBoolean("use_cdn", true))
        store.putBoolean("use_cdn", false)
        assertEquals(false, prefs.getBoolean("use_cdn", true))
        assertFalse(store.getBoolean("use_cdn", true))
    }

    @Test
    fun stringRoundTrips_theListAndEditTextShapes() {
        val prefs = FakeSharedPreferences()
        val store = SharedPreferencesDataStore(prefs)
        assertNull(store.getString("lang", null))
        assertEquals("en", store.getString("lang", "en"))
        store.putString("lang", "ja")
        assertEquals("ja", store.getString("lang", "en"))
        // Clearing a text preference removes the key (the
        // real store's semantics, the donor bridge forwards).
        store.putString("lang", null)
        assertFalse(prefs.contains("lang"))
    }

    @Test
    fun stringSetRoundTrips_theMultiSelectShape() {
        val prefs = FakeSharedPreferences()
        val store = SharedPreferencesDataStore(prefs)
        assertEquals(
            mutableSetOf("a"),
            store.getStringSet("genres", mutableSetOf("a"))
        )
        store.putStringSet("genres", mutableSetOf("action", "drama"))
        assertEquals(
            setOf("action", "drama"),
            store.getStringSet("genres", null)
        )
    }

    @Test
    fun numericTypesRoundTrip() {
        val prefs = FakeSharedPreferences()
        val store = SharedPreferencesDataStore(prefs)
        store.putInt("per_page", 30)
        store.putLong("last_sync", 1700000003000L)
        store.putFloat("zoom", 1.5f)
        assertEquals(30, store.getInt("per_page", 0))
        assertEquals(1700000003000L, store.getLong("last_sync", 0L))
        assertEquals(1.5f, store.getFloat("zoom", 0f))
        assertEquals(7, store.getInt("absent", 7))
    }
}
