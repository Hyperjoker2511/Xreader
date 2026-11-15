package app.xreader.data.reader

import java.io.File
import java.security.MessageDigest

/**
 * The listening cache (Patch 12, spec §C): synthesized sentences
 * on disk, shared by the Edge engine's prefetch pipeline and its
 * chapter-ahead fill. Lives in `cacheDir/voice` — rebuildable
 * synthesis, the same class as any cache — but its lifetime is
 * managed HERE (a hard cap + LRU), not by playback, and it is
 * measured and cleared from the Storage section directly
 * (StorageMeter only walks filesDir, and AppContainer.cleanCache
 * deliberately does not touch it: its Clear button in Storage is
 * its only eraser besides its own LRU).
 *
 * The file name IS the key: lowercase hex SHA-256 of
 * `"v1|<voiceId>|<prosody>|<text>"` + ".mp3". Flat directory, no
 * index file. Pure file logic — JVM-tested with a temp dir.
 */
class TtsAudioCache(private val dir: File) {

    /** The file the engine is playing right now: eviction never
     * takes it. Set at play start, cleared at completion/stop. */
    @Volatile
    var protectedFile: File? = null

    fun fileFor(voiceId: String, prosody: String, text: String): File =
        File(dir, keyFor(voiceId, prosody, text) + ".mp3")

    /** The cached file for this sentence, or null on a miss. A
     * hit refreshes the file's mtime so the chapter being
     * listened to stays hot under LRU. A leftover `.tmp` is never
     * returned (it has a different name by construction). */
    fun get(voiceId: String, prosody: String, text: String): File? {
        val file = fileFor(voiceId, prosody, text)
        if (!file.isFile || file.length() == 0L) return null
        runCatching { file.setLastModified(System.currentTimeMillis()) }
        return file
    }

    /** Stores a sentence's MP3 (atomic tmp + rename, so a killed
     * fill never leaves a playable partial file), then enforces
     * the cap. Returns the stored file. */
    fun put(voiceId: String, prosody: String, text: String, mp3: ByteArray): File {
        dir.mkdirs()
        val target = fileFor(voiceId, prosody, text)
        val tmp = File(dir, target.name + ".tmp")
        tmp.writeBytes(mp3)
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
        runCatching { target.setLastModified(System.currentTimeMillis()) }
        evict(protect = target)
        return target
    }

    fun sizeBytes(): Long =
        dir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".mp3") }
            ?.sumOf { it.length() }
            ?: 0L

    /** Removes every cached sentence (the Storage Clear button). */
    fun clear() {
        protectedFile = null
        dir.listFiles()?.forEach { runCatching { it.delete() } }
    }

    /** While over the cap, delete oldest-first by mtime, skipping
     * [protect] (the file just written) and the playing file. */
    private fun evict(protect: File) {
        val files = dir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".mp3") }
            ?.sortedBy { it.lastModified() }
            ?: return
        var total = files.sumOf { it.length() }
        if (total <= MAX_BYTES) return
        val protected = protectedFile
        for (file in files) {
            if (total <= MAX_BYTES) break
            if (file == protect || file == protected) continue
            val length = file.length()
            if (runCatching { file.delete() }.getOrDefault(false)) {
                total -= length
            }
        }
    }

    companion object {
        /** 25 MB. At 48 kbit/s mono (6 KB/s) a typical 15–25 s
         * sentence is 90–150 KB, so the cap holds roughly
         * 170–270 sentences — several chapters of headroom. */
        const val MAX_BYTES: Long = 25L * 1024 * 1024

        fun keyFor(voiceId: String, prosody: String, text: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest("v1|$voiceId|$prosody|$text".toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}
