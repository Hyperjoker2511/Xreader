package app.xreader.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.0.2 (R02): organisation data in the sync payload — shelves,
 * tags, favourites, custom-theme parameters — merges by the same
 * discipline as the rest of the payload, and a v1.0.1 payload with
 * none of it still decodes (plain defaults). */
class SyncOrganisationTest {

    private fun payload(
        shelves: List<ShelfRecord> = emptyList(),
        bookTags: List<BookTagRecord> = emptyList(),
        favourites: List<String> = emptyList(),
        follows: List<String> = emptyList(),
        theme: ThemeSyncRecord? = null,
        tombstones: List<Tombstone> = emptyList(),
        exportedAt: Long = 0
    ) = SyncPayload(
        deviceId = "d",
        exportedAt = exportedAt,
        shelves = shelves,
        bookTags = bookTags,
        favouriteBookKeys = favourites,
        followedTagSlugs = follows,
        themeSync = theme,
        tombstones = tombstones
    )

    @Test
    fun `shelves merge by name with union membership`() {
        val local = payload(shelves = listOf(
            ShelfRecord("Reading next", updatedAt = 10, memberBookKeys = listOf("k1"))
        ))
        val remote = payload(shelves = listOf(
            ShelfRecord("Reading next", updatedAt = 5, memberBookKeys = listOf("k2")),
            ShelfRecord("Only remote", updatedAt = 5)
        ))
        val merged = SyncMerger.merge(local, remote, now = 100).payload
        assertEquals(2, merged.shelves.size)
        val shelf = merged.shelves.first { it.name == "Reading next" }
        assertEquals(setOf("k1", "k2"), shelf.memberBookKeys.toSet())
        assertEquals(10, shelf.updatedAt)
    }

    @Test
    fun `a shelf tombstone deletes the shelf`() {
        val local = payload(shelves = listOf(ShelfRecord("Gone", updatedAt = 10)))
        val remote = payload(tombstones = listOf(Tombstone("shelf", "Gone", deletedAt = 20)))
        val merged = SyncMerger.merge(local, remote, now = 100).payload
        assertTrue(merged.shelves.isEmpty())
    }

    @Test
    fun `a suppressed tag correction is never resurrected`() {
        val local = payload(bookTags = listOf(
            BookTagRecord("k1", "revenge", origin = "SOURCE", suppressed = true)
        ))
        val remote = payload(bookTags = listOf(
            BookTagRecord("k1", "revenge", origin = "SOURCE", suppressed = false)
        ))
        val merged = SyncMerger.merge(local, remote, now = 100).payload
        assertTrue(merged.bookTags.single().suppressed)
    }

    @Test
    fun `favourites and follows union across devices`() {
        val merged = SyncMerger.merge(
            payload(favourites = listOf("k1"), follows = listOf("revenge")),
            payload(favourites = listOf("k2"), follows = listOf("revenge", "cultivation")),
            now = 100
        ).payload
        assertEquals(setOf("k1", "k2"), merged.favouriteBookKeys.toSet())
        assertEquals(setOf("revenge", "cultivation"), merged.followedTagSlugs.toSet())
        assertFalse(merged.followedTagSlugs.contains("share"))
    }

    @Test
    fun `the newer theme parameters win`() {
        val older = ThemeSyncRecord(colourScheme = "Ember", updatedAt = 5)
        val newer = ThemeSyncRecord(colourScheme = "custom:c1", updatedAt = 50)
        val merged = SyncMerger.merge(
            payload(theme = older), payload(theme = newer), now = 100
        ).payload
        assertEquals("custom:c1", merged.themeSync?.colourScheme)
    }

    @Test
    fun `an old payload without organisation fields round-trips`() {
        val old = SyncPayload(deviceId = "old", exportedAt = 1)
        val decoded = SyncPayload.decode(SyncPayload.encode(old))!!
        assertTrue(decoded.shelves.isEmpty())
        assertTrue(decoded.bookTags.isEmpty())
        assertEquals(null, decoded.themeSync)
    }
}
