package app.xreader.data.library

import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryListLogicTest {

    private fun row(
        id: Long, title: String, author: String = "", chapters: Int = 1,
        added: Long = id, opened: Long? = null, progress: Boolean = false,
        finished: Boolean = false, status: ReadingStatus = ReadingStatus.NONE
    ) = LibraryListLogic.Row(id, title, author, chapters, added, opened, progress, finished, status)

    private val rows = listOf(
        row(1, "Beta", author = "Kim", chapters = 10, opened = 100, progress = true),
        row(2, "alpha", author = "Lee", chapters = 50, opened = 300),
        row(3, "Gamma", chapters = 5, opened = 200, finished = true)
    )

    @Test
    fun `search matches title and author case-insensitively`() {
        val byTitle = LibraryListLogic.apply(
            rows, LibraryState(searchQuery = "ALP")
        )
        assertEquals(listOf(2L), byTitle.map { it.bookId })
        val byAuthor = LibraryListLogic.apply(
            rows, LibraryState(searchQuery = "kim")
        )
        assertEquals(listOf(1L), byAuthor.map { it.bookId })
    }

    @Test
    fun `sorts work`() {
        assertEquals(
            listOf(2L, 3L, 1L),
            LibraryListLogic.apply(rows, LibraryState(sort = LibrarySort.RECENTLY_READ))
                .map { it.bookId }
        )
        assertEquals(
            listOf(2L, 1L, 3L),
            LibraryListLogic.apply(rows, LibraryState(sort = LibrarySort.TITLE))
                .map { it.bookId }
        )
        assertEquals(
            listOf(2L, 1L, 3L),
            LibraryListLogic.apply(rows, LibraryState(sort = LibrarySort.MOST_CHAPTERS))
                .map { it.bookId }
        )
    }

    @Test
    fun `status filters work`() {
        assertEquals(
            listOf(1L),
            LibraryListLogic.apply(rows, LibraryState(statusFilter = "READING"))
                .map { it.bookId }
        )
        assertEquals(
            listOf(3L),
            LibraryListLogic.apply(rows, LibraryState(statusFilter = "COMPLETED"))
                .map { it.bookId }
        )
        assertEquals(
            listOf(2L),
            LibraryListLogic.apply(rows, LibraryState(statusFilter = "NOT_STARTED"))
                .map { it.bookId }
        )
    }

    @Test
    fun `glass style resolution falls back honestly`() {
        // API 31+ and >2 GB: frosted is real.
        assertEquals(
            app.xreader.data.prefs.GlassStyle.FROSTED,
            app.xreader.data.prefs.AppearanceValidation.effectiveGlassStyle(
                app.xreader.data.prefs.GlassStyle.FROSTED, 35, 6144
            )
        )
        // The 2 GB tablet: falls back to tinted.
        assertEquals(
            app.xreader.data.prefs.GlassStyle.TINTED,
            app.xreader.data.prefs.AppearanceValidation.effectiveGlassStyle(
                app.xreader.data.prefs.GlassStyle.FROSTED, 35, 2048
            )
        )
        // Below API 31: no RenderEffect, falls back.
        assertEquals(
            app.xreader.data.prefs.GlassStyle.TINTED,
            app.xreader.data.prefs.AppearanceValidation.effectiveGlassStyle(
                app.xreader.data.prefs.GlassStyle.FROSTED, 30, 6144
            )
        )
        // Solid and Tinted never change.
        assertEquals(
            app.xreader.data.prefs.GlassStyle.SOLID,
            app.xreader.data.prefs.AppearanceValidation.effectiveGlassStyle(
                app.xreader.data.prefs.GlassStyle.SOLID, 35, 6144
            )
        )
        assertEquals(
            "Frosted glass needs a stronger device — using tinted glass here.",
            app.xreader.data.prefs.AppearanceValidation.glassFallbackNote(
                app.xreader.data.prefs.GlassStyle.FROSTED, 35, 2048
            )
        )
    }
}
