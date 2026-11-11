/*
 * Copyright 2024 The Komikku Authors
 * Copyright 2015 Javier Tomás
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * FIX-18 (v1.5.2): Komikku reader transplant. This file is the seam
 * between the donor's preference API (tachiyomi.core.common.preference
 * PreferenceStore / Preference<T>, backed there by AndroidX Preference)
 * and Xreader's single DataStore. The donor-facing shape — typed
 * getters returning a Preference handle with get()/set()/changes() —
 * is preserved so the transplanted ReaderPreferences and the viewer
 * configs port without logic changes; only the storage binding is
 * Xreader's.
 */
package app.xreader.komikku.setting

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** The donor's Preference<T> contract, kept verbatim in shape. */
class KPreference<T>(
    private val read: () -> T,
    private val write: (T) -> Unit,
    private val flow: Flow<T>
) {
    fun get(): T = read()

    fun set(value: T) = write(value)

    fun changes(): Flow<T> = flow.distinctUntilChanged()

    fun getAndSet(transform: (T) -> T): T {
        val next = transform(get())
        set(next)
        return next
    }
}

/**
 * The storage seam. Implementations hold a flat key→value map; the
 * transplanted code never sees which one is live.
 */
interface KomikkuPreferenceStore {
    fun snapshot(): Map<String, Any?>
    fun put(key: String, value: Any?)
    fun changes(): Flow<Map<String, Any?>>

    private fun <T> pref(
        key: String,
        default: T,
        decode: (Any?) -> T?
    ): KPreference<T> = KPreference(
        read = { decode(snapshot()[key]) ?: default },
        write = { put(key, it as Any) },
        flow = changes().map { decode(it[key]) ?: default }
    )

    fun getBoolean(key: String, default: Boolean): KPreference<Boolean> =
        pref(key, default) { it as? Boolean }

    fun getInt(key: String, default: Int): KPreference<Int> =
        pref(key, default) { (it as? Number)?.toInt() }

    fun getLong(key: String, default: Long): KPreference<Long> =
        pref(key, default) { (it as? Number)?.toLong() }

    fun getFloat(key: String, default: Float): KPreference<Float> =
        pref(key, default) { (it as? Number)?.toFloat() }

    fun getString(key: String, default: String): KPreference<String> =
        pref(key, default) { it as? String }

    @Suppress("UNCHECKED_CAST")
    fun getStringSet(key: String, default: Set<String>): KPreference<Set<String>> =
        pref(key, default) { (it as? Set<*>)?.map { v -> v.toString() }?.toSet() }

    fun <T : Enum<T>> getEnum(key: String, default: T): KPreference<T> =
        pref(key, default) { raw ->
            (raw as? String)?.let { name ->
                default.javaClass.enumConstants?.firstOrNull { it.name == name }
            } ?: (raw as? T)
        }
}

/** In-memory store — the JVM-test binding and the reader's hot cache. */
class InMemoryKomikkuStore(
    initial: Map<String, Any?> = emptyMap()
) : KomikkuPreferenceStore {
    private val state = MutableStateFlow(initial)

    override fun snapshot(): Map<String, Any?> = state.value

    override fun put(key: String, value: Any?) {
        state.value = state.value + (key to value)
    }

    override fun changes(): Flow<Map<String, Any?>> = state
}

/**
 * DataStore binding: [prefix] namespaces the keys ("" for the global
 * defaults, "book/<id>/" for a series' own values). Writes land in
 * the in-memory cache synchronously (the reader reads them on the
 * gesture path) and persist through the DataStore asynchronously.
 */
class DataStoreKomikkuStore(
    private val dataStore: DataStore<Preferences>,
    private val scope: CoroutineScope,
    private val prefix: String
) : KomikkuPreferenceStore {
    private val cache = MutableStateFlow<Map<String, Any?>>(emptyMap())

    init {
        scope.launch {
            dataStore.data.collect { prefs ->
                val loaded = mutableMapOf<String, Any?>()
                for ((key, value) in prefs.asMap()) {
                    if (key.name.startsWith(prefix)) {
                        loaded[key.name.removePrefix(prefix)] = value
                    }
                }
                cache.value = loaded
            }
        }
    }

    override fun snapshot(): Map<String, Any?> = cache.value

    override fun put(key: String, value: Any?) {
        cache.value = cache.value + (key to value)
        val fullKey = prefix + key
        scope.launch {
            dataStore.edit { prefs ->
                when (value) {
                    is Boolean -> prefs[booleanPreferencesKey(fullKey)] = value
                    is Int -> prefs[intPreferencesKey(fullKey)] = value
                    is Long -> prefs[longPreferencesKey(fullKey)] = value
                    is Float -> prefs[floatPreferencesKey(fullKey)] = value
                    is String -> prefs[stringPreferencesKey(fullKey)] = value
                    is Enum<*> -> prefs[stringPreferencesKey(fullKey)] = value.name
                    is Set<*> -> prefs[stringSetPreferencesKey(fullKey)] =
                        value.map { it.toString() }.toSet()
                    null -> Unit
                    else -> prefs[stringPreferencesKey(fullKey)] = value.toString()
                }
            }
        }
    }

    override fun changes(): Flow<Map<String, Any?>> = cache
}

/**
 * Option-scope overlay (owner's rule): reads resolve per-book first,
 * then the global default; writes go to the per-book layer when a
 * book is open, to the global layer otherwise. The global store is
 * never written through an overlay that carries a book id.
 */
class ScopedKomikkuStore(
    private val global: KomikkuPreferenceStore,
    private val book: KomikkuPreferenceStore?
) : KomikkuPreferenceStore {
    override fun snapshot(): Map<String, Any?> =
        if (book == null) global.snapshot()
        else global.snapshot() + book.snapshot()

    override fun put(key: String, value: Any?) {
        (book ?: global).put(key, value)
    }

    override fun changes(): Flow<Map<String, Any?>> =
        if (book == null) global.changes()
        else kotlinx.coroutines.flow.combine(global.changes(), book.changes()) { g, b -> g + b }
}
