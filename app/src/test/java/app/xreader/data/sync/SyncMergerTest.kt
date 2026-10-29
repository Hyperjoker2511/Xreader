package app.xreader.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sync merge rules (PLAN §7 gate): last-write-wins per record,
 * reading progress resolved by FURTHEST position (with a logged
 * conflict when that isn't the newest write), tombstones deleting,
 * profile progress never going backwards, and a clean encode →
 * decode round-trip.
 */
class SyncMergerTest {

    private val bookKey = "local:star fall|sky writer|3"

    private fun book(updatedAt: Long, finished: Boolean = false) = BookRecord(
        syncKey = bookKey, title = "Star Fall", author = "Sky Writer",
        totalChapters = 3, finished = finished, updatedAt = updatedAt
    )

    @Test
    fun `last write wins per record`() {
        val local = SyncPayload(deviceId = "a", books = listOf(book(100)))
        val remote = SyncPayload(
            deviceId = "b", books = listOf(book(200, finished = true))
        )
        val merged = SyncMerger.merge(local, remote, now = 300).payload
        assertEquals(1, merged.books.size)
        assertEquals(200L, merged.books.single().updatedAt)
        assertTrue(merged.books.single().finished)
    }

    @Test
    fun `progress keeps the furthest position and logs the conflict`() {
        val local = SyncPayload(
            deviceId = "a",
            progress = listOf(
                ProgressRecord(bookKey, chapterIndex = 5, charOffset = 0, updatedAt = 100)
            )
        )
        val remote = SyncPayload(
            deviceId = "b",
            progress = listOf(
                ProgressRecord(bookKey, chapterIndex = 3, charOffset = 900, updatedAt = 200)
            )
        )
        val result = SyncMerger.merge(local, remote, now = 300)
        val progress = result.payload.progress.single()
        assertEquals(
            "furthest reading position wins over the newer timestamp",
            5, progress.chapterIndex
        )
        assertTrue(
            "the disagreement is surfaced, not silent: ${result.conflicts}",
            result.conflicts.any { it.contains("progress", ignoreCase = true) }
        )
    }

    @Test
    fun `tombstone removes the record it names`() {
        val bookmark = BookmarkRecord(bookKey, chapterIndex = 1, charOffset = 42, updatedAt = 100)
        val local = SyncPayload(deviceId = "a", bookmarks = listOf(bookmark))
        val remote = SyncPayload(
            deviceId = "b",
            tombstones = listOf(
                Tombstone(kind = "bookmark", key = bookmark.naturalKey, deletedAt = 500)
            )
        )
        val merged = SyncMerger.merge(local, remote, now = 600).payload
        assertTrue(merged.bookmarks.isEmpty())
        assertEquals(1, merged.tombstones.size)
    }

    @Test
    fun `profile progress never goes backwards`() {
        val local = SyncPayload(
            deviceId = "a",
            profile = ProfileRecord(totalExp = 900, level = 4)
        )
        val remote = SyncPayload(
            deviceId = "b",
            profile = ProfileRecord(totalExp = 300, level = 2)
        )
        val merged = SyncMerger.merge(local, remote, now = 1).payload
        assertEquals(900L, merged.profile!!.totalExp)
        assertEquals(4, merged.profile!!.level)
    }

    @Test
    fun `locked lore beats an unlocked newer copy`() {
        val local = SyncPayload(
            deviceId = "a",
            lore = listOf(
                LoreRecord(
                    bookKey, "CHARACTER", "Ara",
                    description = "The user's own edit", userLocked = true, updatedAt = 100
                )
            )
        )
        val remote = SyncPayload(
            deviceId = "b",
            lore = listOf(
                LoreRecord(
                    bookKey, "CHARACTER", "Ara",
                    description = "AI re-extraction", userLocked = false, updatedAt = 999
                )
            )
        )
        val merged = SyncMerger.merge(local, remote, now = 1000).payload
        assertEquals("The user's own edit", merged.lore.single().description)
        assertTrue(merged.lore.single().userLocked)
    }

    @Test
    fun `payload survives an encode decode round trip`() {
        val payload = SyncPayload(
            deviceId = "dev-1",
            exportedAt = 1234,
            books = listOf(book(100)),
            progress = listOf(ProgressRecord(bookKey, 2, 40, updatedAt = 90)),
            bookmarks = listOf(BookmarkRecord(bookKey, 1, 42, label = "x")),
            lore = listOf(LoreRecord(bookKey, "CHARACTER", "Ara")),
            terminology = listOf(TermRecord(bookKey, "천마", "Heavenly Demon", locked = true)),
            profile = ProfileRecord(totalExp = 10, level = 1),
            tombstones = listOf(Tombstone("bookmark", "k", 5))
        )
        val decoded = SyncPayload.decode(SyncPayload.encode(payload))
        assertEquals(payload, decoded)
    }
}
