package app.xreader.data.reader

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TtsAudioCacheTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun cache() = TtsAudioCache(File(tmp.root, "voice"))

    @Test
    fun `key is stable and varies with voice rate and text`() {
        val a = TtsAudioCache.keyFor("en-US-AriaNeural", "+0%", "Hello world.")
        assertEquals(a, TtsAudioCache.keyFor("en-US-AriaNeural", "+0%", "Hello world."))
        assertNotEquals(a, TtsAudioCache.keyFor("en-US-GuyNeural", "+0%", "Hello world."))
        assertNotEquals(a, TtsAudioCache.keyFor("en-US-AriaNeural", "+25%", "Hello world."))
        assertNotEquals(a, TtsAudioCache.keyFor("en-US-AriaNeural", "+0%", "Hello world!"))
        assertEquals(64, a.length)
        assertTrue(a.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun `put then get round-trips the bytes`() {
        val cache = cache()
        val bytes = byteArrayOf(1, 2, 3, 4, 5)
        val stored = cache.put("en-US-AriaNeural", "+0%", "Hello.", bytes)
        val hit = cache.get("en-US-AriaNeural", "+0%", "Hello.")
        assertEquals(stored, hit)
        assertTrue(bytes.contentEquals(hit!!.readBytes()))
        assertNull(cache.get("en-US-AriaNeural", "+0%", "Not stored."))
    }

    @Test
    fun `a partial tmp file is never returned`() {
        val cache = cache()
        val target = cache.fileFor("en-US-AriaNeural", "+0%", "Hello.")
        target.parentFile?.mkdirs()
        File(target.path + ".tmp").writeBytes(byteArrayOf(9, 9, 9))
        assertNull(cache.get("en-US-AriaNeural", "+0%", "Hello."))
        assertEquals(0L, cache.sizeBytes())
    }

    @Test
    fun `an empty file counts as a miss`() {
        val cache = cache()
        val target = cache.fileFor("en-US-AriaNeural", "+0%", "Hello.")
        target.parentFile?.mkdirs()
        target.createNewFile()
        assertNull(cache.get("en-US-AriaNeural", "+0%", "Hello."))
    }

    @Test
    fun `eviction deletes oldest first and keeps protected and just-written`() {
        val cache = cache()
        val chunk = ByteArray(1024) { 7 }
        // Old sentence, backdated: the eviction victim.
        val old = cache.put("en-US-AriaNeural", "+0%", "old", chunk)
        old.setLastModified(1_000L)
        // The playing file, also old: protected, must survive.
        val playing = cache.put("en-US-AriaNeural", "+0%", "playing", chunk)
        playing.setLastModified(2_000L)
        cache.protectedFile = playing
        // Write well past the cap with fresh sentences (each put
        // evicts back toward the cap, so the total written is
        // what forces eviction, not the resting size).
        repeat(30) { i ->
            cache.put("en-US-AriaNeural", "+0%", "filler-$i", ByteArray(1024 * 1024) { 3 })
        }
        assertFalse("oldest unprotected file was evicted", old.exists())
        assertTrue("the playing file survives eviction", playing.exists())
        assertTrue(cache.sizeBytes() <= TtsAudioCache.MAX_BYTES + 1024 * 1024)
    }

    @Test
    fun `a hit refreshes mtime so the hot chapter survives`() {
        val cache = cache()
        val file = cache.put("en-US-AriaNeural", "+0%", "hot", byteArrayOf(1))
        file.setLastModified(1_000L)
        val before = file.lastModified()
        Thread.sleep(5)
        cache.get("en-US-AriaNeural", "+0%", "hot")
        assertTrue(file.lastModified() > before)
    }

    @Test
    fun `sizeBytes sums real bytes and clear empties`() {
        val cache = cache()
        cache.put("en-US-AriaNeural", "+0%", "one", ByteArray(100))
        cache.put("en-US-AriaNeural", "+0%", "two", ByteArray(250))
        assertEquals(350L, cache.sizeBytes())
        cache.clear()
        assertEquals(0L, cache.sizeBytes())
        assertNull(cache.get("en-US-AriaNeural", "+0%", "one"))
    }
}
