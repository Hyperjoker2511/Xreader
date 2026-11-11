package app.xreader.data.extension.player

import app.xreader.core.error.AppResult
import app.xreader.data.extension.ContentRating
import app.xreader.extensionapi.SourceBook
import app.xreader.extensionapi.SourceChapter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v1.5.8 (E1): the scanlator round trip, end to end —
 * SChapter.scanlator → SourceChapter.scanlator (donor
 * normalisation: blank → null, trimmed) → Room row through
 * the production sync → back out through schapterOf into the
 * SChapter a later getPageList receives.
 */
class V158ScanlatorRoundTripTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val book = SourceBook(id = "/manga/alpha", title = "Alpha")

    private fun adapter(fake: V158FakeMihon) = MihonSourceAdapter(
        packageName = V158PlayerStacks.PKG,
        namespacedId = V158PlayerStacks.NAMESPACED,
        mihon = fake,
        contentRating = ContentRating.SAFE
    )

    @Test
    fun adapterChaptersCarryNormalisedScanlator() {
        val fake = V158FakeMihon()
        fake.updateChapters = fake.updateChapters +
            eu.kanade.tachiyomi.source.model.SChapter(
                name = "Chapter 3", url = "/ch/3",
                date_upload = 1_700_000_003_000, chapter_number = 3f,
                scanlator = "   "
            )
        val chapters = adapter(fake).chapters(book)
        val byId = chapters.associateBy { it.id }
        assertEquals("Gengar", byId.getValue("/ch/2").scanlator)
        // The padded donor value is trimmed.
        assertEquals("Gengar", byId.getValue("/ch/1").scanlator)
        // A blank donor value normalises to null.
        assertNull(byId.getValue("/ch/3").scanlator)
    }

    @Test
    fun syncPersistsScanlatorAndAdapterWritesItBack() = runBlocking {
        val fake = V158FakeMihon()
        val stack = V158PlayerStacks.build(fake, tmp.newFolder())
        val seeded = stack.seedBook()

        val result = stack.sync.refresh(seeded)
        assertTrue("refresh failed: $result", result is AppResult.Ok)

        val rows = stack.chapters.forBook(seeded.id)
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.scanlator == "Gengar" })

        // Back out: the chapter the fetcher asks pages for
        // carries the stored scanlator into getPageList.
        val chapter = SourceChapter(
            id = "/ch/1", name = "Chapter 1",
            scanlator = rows.first { it.remoteId == "/ch/1" }.scanlator
        )
        adapter(fake).chapterContent(chapter)
        assertEquals("Gengar", fake.pageListChapters.last().scanlator)
    }
}
