package app.xreader.data.extension.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

/** In-memory [PreferenceStore] for player tests: NetworkPreferences can
 * be built on it with no Android SharedPreferences involved. */
class InMemoryPreferenceStore : PreferenceStore {

    private val values = mutableMapOf<String, Any?>()

    private fun <T> pref(key: String, default: T): Preference<T> =
        object : Preference<T> {
            override fun key(): String = key

            @Suppress("UNCHECKED_CAST")
            override fun get(): T = (values[key] as T?) ?: default

            override fun set(value: T) {
                values[key] = value
            }

            override fun isSet(): Boolean = values.containsKey(key)

            override fun delete() {
                values.remove(key)
            }

            override fun defaultValue(): T = default

            override fun changes(): Flow<T> = flowOf(get())

            override fun stateIn(scope: CoroutineScope): StateFlow<T> =
                MutableStateFlow(get())
        }

    override fun getString(key: String, defaultValue: String): Preference<String> =
        pref(key, defaultValue)

    override fun getLong(key: String, defaultValue: Long): Preference<Long> =
        pref(key, defaultValue)

    override fun getInt(key: String, defaultValue: Int): Preference<Int> =
        pref(key, defaultValue)

    override fun getFloat(key: String, defaultValue: Float): Preference<Float> =
        pref(key, defaultValue)

    override fun getBoolean(key: String, defaultValue: Boolean): Preference<Boolean> =
        pref(key, defaultValue)

    override fun getStringSet(
        key: String,
        defaultValue: Set<String>
    ): Preference<Set<String>> = pref(key, defaultValue)

    override fun <T> getObjectFromString(
        key: String,
        defaultValue: T,
        serializer: (T) -> String,
        deserializer: (String) -> T
    ): Preference<T> = pref(key, defaultValue)

    override fun <T> getObjectFromInt(
        key: String,
        defaultValue: T,
        serializer: (T) -> Int,
        deserializer: (Int) -> T
    ): Preference<T> = pref(key, defaultValue)

    override fun getAll(): Map<String, *> = values.toMap()
}
