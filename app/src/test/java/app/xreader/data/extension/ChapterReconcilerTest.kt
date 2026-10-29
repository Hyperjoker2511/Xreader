package app.xreader.data.extension

import app.xreader.data.db.Chapter
import app.xreader.data.db.DownloadState
import app.xreader.extensionapi.SourceChapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R03: the chapter-identity reconciler. The headline scenario is the
 * item-15 defect: a source changes every chapter URL and the stored
 * rows (with the user's downloads and read state) must be ALIASED,
 * not replaced.
 */
class ChapterReconcilerTest {

    private fun row(
        id: Long, index: Int, remoteId: String, title: String,
        contentPath: String? = null,
        downloadState: DownloadState = DownloadState.NONE,
        read: Boolean = false
    ) = Chapter(
        id = id, bookId = 7L, index = index, title = title,
        remoteId = remoteId, contentPath = contentPath,
        downloadState = downloadState, read = read, updatedAt = 1L
    )

    private fun fetched(id: String, name: String, number: Float? = null) =
        SourceChapter(id = id, name = name, number = number)

    @Test
    fun `exact remote ids match in place`() {
        val existing = listOf(
            row(1, 0, "a/1", "Chapter 1"),
            row(2, 1, "a/2", "Chapter 2")
        )
        val plan = ChapterReconciler.reconcile(
            existing,
            listOf(fetched("a/1", "Chapter 1"), fetched("a/2", "Chapter 2"))
        )
        assertEquals(2, plan.updates.size)
        assertTrue(plan.inserts.isEmpty())
        assertTrue(plan.dropped.isEmpty())
        assertTrue(plan.keptStale.isEmpty())
    }

    @Test
    fun `url change aliases rows instead of replacing them`() {
        // The source moved hosts: every remoteId is new, titles same.
        val existing = listOf(
            row(1, 0, "old.example/c1", "Chapter 1: Dawn", contentPath = "f/1", read = true),
            row(2, 1, "old.example/c2", "Chapter 2: Dusk")
        )
        val plan = ChapterReconciler.reconcile(
            existing,
            listOf(
                fetched("new.example/x1", "Chapter 1: Dawn"),
                fetched("new.example/x2", "Chapter 2: Dusk")
            )
        )
        assertTrue(plan.inserts.isEmpty())
        assertEquals(listOf(1L, 2L), plan.updates.map { it.row.id })
        assertEquals("new.example/x1", plan.updates[0].newRemoteId)
        assertEquals("new.example/x2", plan.updates[1].newRemoteId)
    }

    @Test
    fun `canonicalised ids match despite case and trailing slash`() {
        val existing = listOf(row(1, 0, "Site/Ch/1/", "Chapter 1"))
        val plan = ChapterReconciler.reconcile(
            existing, listOf(fetched("site/ch/1", "Renamed Title"))
        )
        assertEquals(1, plan.updates.size)
        assertEquals(1L, plan.updates[0].row.id)
    }

    @Test
    fun `chapter numbers match when titles were rewritten`() {
        val existing = listOf(
            row(1, 0, "a/1", "Chapter 1"),
            row(2, 1, "a/2", "Chapter 2")
        )
        val plan = ChapterReconciler.reconcile(
            existing,
            listOf(
                fetched("b/9", "1. The Beginning", number = 1f),
                fetched("b/10", "2. The Middle", number = 2f)
            )
        )
        assertEquals(listOf(1L, 2L), plan.updates.map { it.row.id })
        assertTrue(plan.inserts.isEmpty())
    }

    @Test
    fun `new chapters insert at their fetched positions`() {
        val existing = listOf(row(1, 0, "a/1", "Chapter 1"))
        val plan = ChapterReconciler.reconcile(
            existing,
            listOf(fetched("a/1", "Chapter 1"), fetched("a/2", "Chapter 2"))
        )
        assertEquals(1, plan.inserts.size)
        assertEquals(1, plan.inserts[0].index)
        assertEquals("a/2", plan.inserts[0].fetched.id)
    }

    @Test
    fun `stale clean rows drop, stale rows with user content are kept`() {
        val existing = listOf(
            row(1, 0, "a/1", "Chapter 1"),
            row(2, 1, "a/gone-clean", "Chapter 2"),
            row(3, 2, "a/gone-dl", "Chapter 3", contentPath = "f/3",
                downloadState = DownloadState.READY),
            row(4, 3, "a/gone-read", "Chapter 4", read = true)
        )
        val plan = ChapterReconciler.reconcile(
            existing, listOf(fetched("a/1", "Chapter 1"))
        )
        assertEquals(listOf(2L), plan.dropped.map { it.id })
        assertEquals(listOf(3L, 4L), plan.keptStale.map { it.row.id })
        // Kept stale rows move AFTER the fetched list, in old order.
        assertEquals(listOf(1, 2), plan.keptStale.map { it.newIndex })
    }

    @Test
    fun `plan indices never collide`() {
        val existing = listOf(
            row(1, 0, "a/1", "Chapter 1"),
            row(2, 1, "a/2", "Chapter 2", contentPath = "f/2")
        )
        // Fetched list is reversed and one chapter vanished.
        val plan = ChapterReconciler.reconcile(
            existing,
            listOf(fetched("a/2x", "Chapter 2"), fetched("a/new", "Chapter 3"))
        )
        val targets = plan.updates.map { it.newIndex } +
            plan.inserts.map { it.index } +
            plan.keptStale.map { it.newIndex }
        assertEquals(targets.size, targets.toSet().size)
    }
}
