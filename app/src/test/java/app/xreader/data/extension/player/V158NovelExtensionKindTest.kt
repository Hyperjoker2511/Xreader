package app.xreader.data.extension.player

import app.xreader.core.error.AppResult
import app.xreader.data.extension.ContentRating
import app.xreader.data.extension.MediaKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v1.5.8 (B4): the adapter's kind comes from the wrapped
 * source's OWN type — a NovelSource extension files TEXT
 * (and the service reports TEXT for its handle), a manga
 * extension stays MANGA. Pre-fix the getter was hardcoded
 * MANGA, so every novel extension's books were stamped
 * manga and routed to the manga reader.
 */
class V158NovelExtensionKindTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun novelSourceAdapterReportsText() {
        val adapter = MihonSourceAdapter(
            packageName = V158PlayerStacks.PKG,
            namespacedId = V158PlayerStacks.NAMESPACED,
            mihon = V158FakeNovel(),
            contentRating = ContentRating.SAFE
        )
        assertEquals(MediaKind.TEXT, adapter.kind)
        assertEquals(MediaKind.TEXT, adapter.mediaKind)
    }

    @Test
    fun mangaSourceAdapterStillReportsManga() {
        val adapter = MihonSourceAdapter(
            packageName = V158PlayerStacks.PKG,
            namespacedId = V158PlayerStacks.NAMESPACED,
            mihon = V158FakeMihon(),
            contentRating = ContentRating.SAFE
        )
        assertEquals(MediaKind.MANGA, adapter.mediaKind)
    }

    @Test
    fun serviceReportsTextForNovelExtensionHandle() = runBlocking {
        val stack = V158PlayerStacks.build(V158FakeNovel(), tmp.newFolder())
        val handle = stack.service.source(stack.extRowId, V158PlayerStacks.NAMESPACED)
        assertTrue(handle is AppResult.Ok)
        assertEquals(
            MediaKind.TEXT,
            stack.service.mediaKindOf((handle as AppResult.Ok).value)
        )
    }
}
