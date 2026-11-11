package app.xreader.komikku

import app.xreader.data.db.Chapter
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v1.5.8 (I5): skip-filtered wired into the SHIPPED selection
 * (ChapterSelection, which KomikkuReaderViewModel's
 * selectChapterIndex delegates to) — the two skip-filtered
 * cases ONLY: with the pref ON, next/previous skip chapters
 * whose scanlator is in E3's exclusion set; with it OFF, they
 * land. (The shipped skip-read / skip-dupe cases are the
 * regression fence and stay untouched.)
 */
class V158ChapterSkipFilteredTest {

    private fun chapter(id: Long, scanlator: String?) = Chapter(
        id = id, bookId = 7, index = id.toInt(),
        title = "Chapter $id", updatedAt = 0, scanlator = scanlator
    )

    private val chapters = listOf(
        chapter(0, "Asura"), chapter(1, "Gengar"),
        chapter(2, "Asura"), chapter(3, "Gengar")
    )
    private val excluded = setOf("Gengar")
    private val noNumber: (String?) -> Float? = { null }

    @Test
    fun `skip-filtered ON skips excluded chapters both directions`() {
        assertEquals(
            2,
            ChapterSelection.select(
                chapters, fromIndex = 0, direction = +1,
                skipRead = false, skipDupe = false,
                skipFiltered = true, excludedScanlators = excluded,
                numberOf = noNumber
            )
        )
        assertEquals(
            2,
            ChapterSelection.select(
                chapters, fromIndex = 3, direction = -1,
                skipRead = false, skipDupe = false,
                skipFiltered = true, excludedScanlators = excluded,
                numberOf = noNumber
            )
        )
    }

    @Test
    fun `skip-filtered OFF lands on excluded chapters`() {
        assertEquals(
            1,
            ChapterSelection.select(
                chapters, fromIndex = 0, direction = +1,
                skipRead = false, skipDupe = false,
                skipFiltered = false, excludedScanlators = excluded,
                numberOf = noNumber
            )
        )
        assertEquals(
            3,
            ChapterSelection.select(
                chapters, fromIndex = 2, direction = +1,
                skipRead = false, skipDupe = false,
                skipFiltered = false, excludedScanlators = excluded,
                numberOf = noNumber
            )
        )
    }
}
