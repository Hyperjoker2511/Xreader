package app.xreader.di

import android.content.Context
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import java.io.File

/**
 * v1.2.0 (audit finding 3, loader half): the ONE tuned Coil
 * loader for the whole app. Coil 2.7.0 otherwise runs completely
 * stock — a memory cache of 25% of the app's memory class and a
 * 256 MB disk cache — on a 2 GB tablet that default competes
 * with the app itself. The budgets here are tier-aware (the
 * device's total RAM, via [AppContainer.totalRamMb] /
 * PerformancePolicy's tiers) and deliberately smaller:
 *
 *  - memory cache: ~12% on MINIMAL-class devices (≤ ~2.5 GB
 *    total RAM — the tablet tier), ~18% elsewhere (stock: 25%);
 *  - disk cache: capped at ~128 MB under cacheDir (stock: 256 MB);
 *  - crossfade: disabled (the app's own motion language handles
 *    transitions; Coil's crossfade costs a double draw per cover).
 *
 * Installed app-wide by [app.xreader.XreaderApp] implementing
 * coil.ImageLoaderFactory — Coil calls it the first time any
 * image loads, whenever that is, so installation cannot race
 * the first image request the way a setImageLoader call after
 * container construction could. Call-site request sizing is a
 * separate later change; this file owns only the loader.
 */
object AppImageLoader {

    /** Memory-cache fraction of the app memory class for a
     * device with [totalRamMb] MB of RAM (0 = unreadable, which
     * the app convention treats as a full-tier device). Pure —
     * unit-tested without Android. */
    fun memoryCacheFraction(totalRamMb: Long): Double =
        if (totalRamMb in 1L..MINIMAL_RAM_CEILING_MB) {
            MINIMAL_MEMORY_FRACTION
        } else {
            STANDARD_MEMORY_FRACTION
        }

    /** v1.3.1 (item 58): the disk-cache ceiling for an
     * auto-clean cap in MB — the cap itself when on, a large
     * ceiling when Off (0). Pure — unit-tested. */
    fun capBytesFor(capMb: Int): Long =
        if (capMb > 0) capMb * 1024L * 1024L else OFF_CEILING_BYTES

    fun build(
        context: Context,
        totalRamMb: Long,
        capBytes: Long = DISK_CACHE_MAX_BYTES,
        /** v1.3.2 (scope A3): the session-carrying call
         * factory (see [ImageSessionClient]) — covers ride
         * the browser view's cleared session. Null keeps
         * Coil's stock client (tests, previews). */
        callFactory: okhttp3.Call.Factory? = null
    ): ImageLoader =
        ImageLoader.Builder(context)
            .apply {
                if (callFactory != null) callFactory(callFactory)
            }
            .memoryCache {
                MemoryCache.Builder(context)
                    .maxSizePercent(memoryCacheFraction(totalRamMb))
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(File(context.cacheDir, DISK_CACHE_DIR))
                    // v1.3.1 (item 58): the max size IS the
                    // auto-clean mechanism — Coil's DiskLruCache
                    // trims oldest-first down to this bound on
                    // writes, continuously and quietly.
                    .maxSizeBytes(capBytes)
                    .build()
            }
            .crossfade(false)
            .build()

    /** ≤ ~2.5 GB total RAM is the MINIMAL-class device band
     * (PerformancePolicy's MINIMAL tier is ≤2 GB; the budget
     * stays conservative a little above it). */
    const val MINIMAL_RAM_CEILING_MB = 2_560L
    const val MINIMAL_MEMORY_FRACTION = 0.12
    const val STANDARD_MEMORY_FRACTION = 0.18
    const val DISK_CACHE_MAX_BYTES = 128L * 1024 * 1024

    /** Ceiling used when auto-clean is Off — effectively
     * unbounded for a cover cache, still finite. */
    const val OFF_CEILING_BYTES = 2L * 1024 * 1024 * 1024
    const val DISK_CACHE_DIR = "image_cache"
}
