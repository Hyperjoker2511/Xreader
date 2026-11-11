package app.xreader.data.extension.player

import app.xreader.data.extension.ContentRating
import app.xreader.extensionapi.SourceChapter
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.5.8 (B4, OD1a): a NovelSource extension's chapter is
 * served as PROSE — the extension's own fetchPageText per
 * page, joined in order — as the chapter content the novel
 * reader's prose road consumes, and NO manga page record is
 * stashed (a stashed record would route the book's chapters
 * to the manga reader).
 */
class V158NovelExtensionChapterTest {

    @Test
    fun novelChapterServesFetchPageTextProse() {
        val fake = V158FakeNovel()
        val adapter = MihonSourceAdapter(
            packageName = V158PlayerStacks.PKG,
            namespacedId = V158PlayerStacks.NAMESPACED,
            mihon = fake,
            contentRating = ContentRating.SAFE
        )
        val chapter = SourceChapter(id = "/ch/1", name = "Chapter 1")

        val content = adapter.chapterContent(chapter)
        assertTrue(content.html.contains("Prose of /ch/1"))
        assertTrue(content.html.contains("lantern keeper"))
        assertTrue(fake.fetchedPages.isNotEmpty())
        // No manga page record: the prose road, not the
        // page-list road, owns this chapter.
        assertNull(adapter.pagesOf("/ch/1"))
    }
}
