package app.xreader.data.extension

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v1.5.10 (B3) gate: the instance-picks store parses its file
 * ONCE and serves from memory; its own write path updates
 * memory + file together; a corrupt file still reads empty.
 * The read counter is the store's named test seam.
 */
class V1510InstancePicksCacheTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `N reads perform exactly one file read`() {
        val store = ExtensionStore(tmp.newFolder())
        store.writeInstancePick("pkg.a", "src-1")
        // The write itself performed the first (single) read.
        val afterWrite = store.instancePicksFileReadCount
        assertEquals(1, afterWrite)
        repeat(25) {
            assertEquals("src-1", store.instancePick("pkg.a"))
            assertEquals(
                mapOf("pkg.a" to "src-1"), store.readInstancePicks()
            )
        }
        assertEquals(
            "no read after the first may touch the file",
            afterWrite, store.instancePicksFileReadCount
        )
    }

    @Test
    fun `write is visible to the next read without a re-read`() {
        val store = ExtensionStore(tmp.newFolder())
        assertNull(store.instancePick("pkg.b"))
        val readsBefore = store.instancePicksFileReadCount
        store.writeInstancePick("pkg.b", "src-9")
        assertEquals("src-9", store.instancePick("pkg.b"))
        assertEquals(
            readsBefore, store.instancePicksFileReadCount
        )
        // And the file itself carries it: a FRESH store over
        // the same directory reads the pick back (one read).
        val fresh = ExtensionStore(storeRootOf(store))
        assertEquals("src-9", fresh.instancePick("pkg.b"))
        assertEquals(1, fresh.instancePicksFileReadCount)
    }

    @Test
    fun `corrupt file reads as empty and is parsed once`() {
        val dir = tmp.newFolder()
        val store = ExtensionStore(dir)
        val file = store.instancePicksFile()
        file.parentFile?.mkdirs()
        file.writeText("{not json at all")
        assertEquals(emptyMap<String, String>(), store.readInstancePicks())
        assertNull(store.instancePick("anything"))
        repeat(5) { store.readInstancePicks() }
        assertEquals(1, store.instancePicksFileReadCount)
    }

    @Test
    fun `missing file reads as empty`() {
        val store = ExtensionStore(tmp.newFolder())
        assertEquals(emptyMap<String, String>(), store.readInstancePicks())
        assertNull(store.instancePick("pkg.x"))
        assertEquals(1, store.instancePicksFileReadCount)
    }

    /** The filesDir a store was built over — recovered from
     * its picks file path (extensions/instance-picks.json). */
    private fun storeRootOf(store: ExtensionStore): java.io.File =
        store.instancePicksFile().parentFile.parentFile
}
