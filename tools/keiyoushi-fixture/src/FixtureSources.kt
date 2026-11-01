package fixture.panels

import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.PreferenceScreen
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * K6 smoke fixture: a canned, fully offline HttpSource in
 * a real signed extension APK (built by build-fixtures.sh),
 * so the instrumented test can prove the player's class
 * loading + adapter path end-to-end without any network.
 * Every answer is fixed data — if a value here changes, the
 * instrumented test's expectations change with it.
 *
 * It also implements ConfigurableSource (Asura's shape), so
 * the K5 preference host has a real screen to build on a
 * device.
 */
class FixturePanelsSource : HttpSource(), ConfigurableSource {

    override val id: Long = 901001L
    override val name: String = "Fixture Panels"
    override val lang: String = "en"
    override val baseUrl: String = "https://fixture.invalid"
    override val supportsLatest: Boolean = true

    private val catalogue = listOf(
        SManga(
            url = "/manga/alpha", title = "Fixture Alpha",
            thumbnail_url = "/covers/alpha.jpg"
        ),
        SManga(
            url = "/manga/beta", title = "Fixture Beta",
            thumbnail_url = "/covers/beta.jpg"
        )
    )

    private val chaptersNewestFirst = listOf(
        SChapter(
            name = "Chapter 3", url = "/ch/3",
            date_upload = 1700000003000, chapter_number = 3f
        ),
        SChapter(
            name = "Chapter 2", url = "/ch/2",
            date_upload = 1700000002000, chapter_number = 2f
        ),
        SChapter(
            name = "Chapter 1", url = "/ch/1",
            date_upload = 1700000001000, chapter_number = 1f
        )
    )

    override suspend fun getPopularManga(page: Int): MangasPage =
        MangasPage(catalogue, hasNextPage = false)

    override suspend fun getLatestUpdates(page: Int): MangasPage =
        MangasPage(catalogue, hasNextPage = false)

    override suspend fun getSearchManga(
        page: Int, query: String, filters: FilterList
    ): MangasPage = MangasPage(
        catalogue.filter {
            it.title.contains(query, ignoreCase = true)
        },
        hasNextPage = false
    )

    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean
    ): SMangaUpdate {
        val details = if (fetchDetails) {
            SManga(
                url = manga.url,
                title = manga.title,
                author = "Fixture Author",
                description = "Canned details from the K6 fixture.",
                thumbnail_url = "/covers/alpha-hd.jpg",
                initialized = true
            )
        } else {
            manga
        }
        return SMangaUpdate(
            details,
            if (fetchChapters) chaptersNewestFirst else emptyList()
        )
    }

    override suspend fun getPageList(
        chapter: SChapter
    ): List<Page> = listOf(
        Page(0, url = "/read/p1", imageUrl = "https://cdn.fixture/1.png"),
        Page(1, url = "/read/p2", imageUrl = null),
        Page(2, url = "/read/p3", imageUrl = "https://cdn.fixture/3.png")
    )

    override suspend fun getImageUrl(page: Page): String =
        "https://cdn.fixture/resolved/" +
            page.url.substringAfterLast('/')

    override suspend fun getImage(page: Page): Response {
        val url = page.imageUrl ?: "https://fixture.invalid/"
        return Response.Builder()
            .request(Request.Builder().url(url).build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(PNG_1PX.toResponseBody("image/png".toMediaType()))
            .build()
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        androidx.preference.SwitchPreferenceCompat(screen.context)
            .apply {
                key = "fixture_toggle"
                title = "Fixture toggle"
                summary = "A canned switch from the K6 fixture"
                setDefaultValue(true)
            }
            .also { screen.addPreference(it) }
        androidx.preference.ListPreference(screen.context)
            .apply {
                key = "fixture_choice"
                title = "Fixture choice"
                entries = arrayOf("First", "Second")
                entryValues = arrayOf("first", "second")
                summary = "A canned list from the K6 fixture"
                setDefaultValue("first")
            }
            .also { screen.addPreference(it) }
    }

    companion object {
        /** A real 1×1 transparent PNG. */
        val PNG_1PX: ByteArray = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
            0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
            0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, 0xC4.toByte(),
            0x89.toByte(), 0x00, 0x00, 0x00, 0x0A, 0x49, 0x44, 0x41, 0x54,
            0x78, 0x9C.toByte(), 0x63, 0x00, 0x01, 0x00, 0x00, 0x05,
            0x00, 0x01, 0x0D, 0x0A, 0x2D, 0xB4.toByte(), 0x00, 0x00,
            0x00, 0x00, 0x49, 0x45, 0x4E, 0x44, 0xAE.toByte(), 0x42,
            0x60, 0x82.toByte()
        )
    }
}

/** Fixture B's plain canned source (factory-made). */
class FixtureFactorySource(
    override val id: Long,
    override val name: String
) : HttpSource() {

    override val lang: String = "en"
    override val baseUrl: String = "https://fixture.invalid"
    override val supportsLatest: Boolean = false

    override suspend fun getPopularManga(page: Int): MangasPage =
        MangasPage(
            listOf(
                SManga(
                    url = "/manga/factory", title = "Fixture Factory"
                )
            ),
            hasNextPage = false
        )

    override suspend fun getLatestUpdates(page: Int): MangasPage =
        getPopularManga(page)

    override suspend fun getSearchManga(
        page: Int, query: String, filters: FilterList
    ): MangasPage = getPopularManga(page)

    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean
    ): SMangaUpdate = SMangaUpdate(
        manga,
        if (fetchChapters) {
            listOf(
                SChapter(
                    name = "Chapter 1", url = "/ch/1",
                    date_upload = 1700000001000, chapter_number = 1f
                )
            )
        } else {
            emptyList()
        }
    )

    override suspend fun getPageList(
        chapter: SChapter
    ): List<Page> = listOf(
        Page(0, url = "/read/p1", imageUrl = "https://cdn.fixture/1.png")
    )
}

/** Fixture B's entry: a SourceFactory making two sources —
 * MangaDex's shape at candle-power. */
class FixtureFactory : eu.kanade.tachiyomi.source.SourceFactory {
    override fun createSources():
        List<eu.kanade.tachiyomi.source.Source> = listOf(
            FixtureFactorySource(901002L, "Fixture Factory A"),
            FixtureFactorySource(901003L, "Fixture Factory B")
        )
}
