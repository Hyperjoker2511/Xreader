package app.xreader.data.download

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Whether the current network may carry downloads at all. */
interface NetworkGate {
    /** Unmetered (Wi-Fi/ethernet) or no restriction information. */
    fun onUnmeteredNetwork(): Boolean
    fun hasNetwork(): Boolean
}

class AndroidNetworkGate(context: Context) : NetworkGate {

    private val connectivity =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)

    override fun onUnmeteredNetwork(): Boolean {
        val network = connectivity?.activeNetwork ?: return false
        val caps = connectivity.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    override fun hasNetwork(): Boolean {
        val network = connectivity?.activeNetwork ?: return false
        val caps = connectivity.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}

private val Context.downloadDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "download_prefs"
)

data class DownloadSettings(
    /** PLAN §4: bulk/prefetch traffic waits for Wi-Fi; a download the
     * user taps explicitly is never blocked by this. */
    val wifiOnlyPrefetch: Boolean = true,
    /** Prefetch the next chapters of a book being read. */
    val autoPrefetch: Boolean = true,
    /** v1.3.1 (item 58): the auto-clean cap for the image/cover
     * cache, in MB; 0 = Off. Default ON at 500 MB. The cap IS
     * Coil's disk-cache max size, so the cache trims itself
     * oldest-first (LRU) as it grows — no wipe, no job, no
     * popup; recently used covers survive. Downloaded chapters
     * and book files are never part of this cache. */
    val autoCleanCapMb: Int = 500
)

class DownloadPrefs(private val store: DataStore<Preferences>) {

    constructor(context: Context) : this(context.applicationContext.downloadDataStore)

    val settings: Flow<DownloadSettings> = store.data.map { prefs ->
        DownloadSettings(
            wifiOnlyPrefetch = prefs[WIFI_ONLY] ?: true,
            autoPrefetch = prefs[AUTO_PREFETCH] ?: true,
            autoCleanCapMb = prefs[AUTO_CLEAN_CAP] ?: 500
        )
    }

    suspend fun setWifiOnlyPrefetch(value: Boolean) {
        store.edit { it[WIFI_ONLY] = value }
    }

    suspend fun setAutoCleanCapMb(value: Int) {
        store.edit { it[AUTO_CLEAN_CAP] = value }
    }

    suspend fun setAutoPrefetch(value: Boolean) {
        store.edit { it[AUTO_PREFETCH] = value }
    }

    companion object {
        private val WIFI_ONLY = booleanPreferencesKey("wifi_only_prefetch")
        private val AUTO_PREFETCH = booleanPreferencesKey("auto_prefetch")
        private val AUTO_CLEAN_CAP =
            androidx.datastore.preferences.core.intPreferencesKey(
                "auto_clean_cap_mb"
            )
    }
}
