package app.xreader.data.extension.player

import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.PreferenceScreen
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * K5: the settings door's classification — which sources
 * carry settings of their own. The fakes implement the real
 * :mihon-api interfaces (a plain Source, and a
 * ConfigurableSource in Asura/MangaDex's shape); their entry
 * points are never called here, and setupPreferenceScreen is
 * never invoked — building a real androidx preference tree
 * needs a device context, so the host's build/edit
 * round-trips are K6's on-device proof (the plan's
 * fixture-extension strategy), while everything around the
 * tree (this classification, the data-store bridge in
 * SharedPreferencesDataStoreTest, the summary strings in
 * PrefSummaryTextTest) is pinned here.
 */
class SourcePreferenceHostTest {

    private open class FakeSource(
        override val id: Long = 4242L
    ) : Source {
        override val name: String = "Fake Panels"
        override val supportsLatest: Boolean = true
        override fun getFilterList(): FilterList = FilterList()

        override suspend fun getPopularManga(page: Int): MangasPage =
            throw UnsupportedOperationException("not in this test")
        override suspend fun getLatestUpdates(page: Int): MangasPage =
            throw UnsupportedOperationException("not in this test")
        override suspend fun getSearchManga(
            page: Int,
            query: String,
            filters: FilterList
        ): MangasPage =
            throw UnsupportedOperationException("not in this test")
        override suspend fun getMangaUpdate(
            manga: SManga,
            chapters: List<SChapter>,
            fetchDetails: Boolean,
            fetchChapters: Boolean
        ): SMangaUpdate =
            throw UnsupportedOperationException("not in this test")
        override suspend fun getPageList(chapter: SChapter): List<Page> =
            throw UnsupportedOperationException("not in this test")
    }

    private class FakeConfigurableSource : FakeSource(), ConfigurableSource {
        override fun setupPreferenceScreen(screen: PreferenceScreen) {
            // Never invoked on the JVM — see the class doc.
        }
    }

    @Test
    fun configurableSourceHasSettings() {
        assertTrue(
            SourcePreferenceHost.hasSettings(FakeConfigurableSource())
        )
    }

    @Test
    fun plainSourceHasNoSettingsOfItsOwn() {
        assertFalse(SourcePreferenceHost.hasSettings(FakeSource()))
    }
}
