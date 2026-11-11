package app.xreader.data.extension.player

import app.xreader.core.error.AppResult
import app.xreader.data.extension.ContentRating
import app.xreader.extensionapi.SourceChapter
import eu.kanade.tachiyomi.source.model.Page
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.5.8 (A5): page resolution is per-page fault-tolerant —
 * one page whose getImageUrl throws keeps its own page URL
 * and the chapter still serves all five pages; pre-fix the
 * single throw failed the whole chapter.
 *
 * v1.5.11 (transplant, Verdict 1 — re-derived): resolution is
 * LAZY now, so the premise inverts — a throwing getImageUrl
 * can no longer fail chapterContent at all, because no
 * getImageUrl call happens at chapter open. The unaddressed
 * page keeps its registration key in the record, the chapter
 * serves all five pages, and the throw surfaces only later,
 * as that one page's byte-fetch failure in pageBytes.
 */
class V158ResolvePagesToleranceTest {

    @Test
    fun oneThrowingPageKeepsItsUrlAndTheChapterSurvives() = runBlocking {
        val fake = V158FakeMihon()
        fake.pagesFor = {
            (0 until 5).map { i ->
                Page(
                    i,
                    url = "/read/p${i + 1}",
                    imageUrl = if (i == 2) null else "https://cdn.test/$i.jpg"
                )
            }
        }
        val imageUrlAttempts = AtomicInteger(0)
        fake.imageUrlFor = { page ->
            imageUrlAttempts.incrementAndGet()
            if (page.url == "/read/p3") {
                throw IllegalStateException("image endpoint gone")
            }
            page.imageUrl ?: throw IllegalStateException("no image")
        }
        val adapter = MihonSourceAdapter(
            packageName = V158PlayerStacks.PKG,
            namespacedId = V158PlayerStacks.NAMESPACED,
            mihon = fake,
            contentRating = ContentRating.SAFE
        )
        val chapter = SourceChapter(id = "/ch/9", name = "Chapter 9")

        // Chapter open succeeds outright — and proves the
        // laziness: the throwing endpoint was never consulted.
        adapter.chapterContent(chapter)
        assertEquals(0, imageUrlAttempts.get())

        val pages = adapter.pagesOf("/ch/9")
        assertNotNull(pages)
        assertEquals(5, pages!!.urls.size)
        assertEquals("https://cdn.test/0.jpg", pages.urls[0])
        // The unaddressed page carries its registration key —
        // the absolutised page url plus its page index.
        assertEquals(
            "https://panels.test/read/p3#xreaderPage=2", pages.urls[2]
        )
        assertEquals("https://cdn.test/4.jpg", pages.urls[4])

        // The failure lands where the lazy contract puts it:
        // on THAT page's byte fetch, as a per-page error —
        // the chapter record above is untouched by it.
        val result = adapter.pageBytes(pages.urls[2])
        assertTrue(
            "the throwing page's byte fetch must fail per-page: $result",
            result is AppResult.Err
        )
        assertEquals(1, imageUrlAttempts.get())
    }
}
