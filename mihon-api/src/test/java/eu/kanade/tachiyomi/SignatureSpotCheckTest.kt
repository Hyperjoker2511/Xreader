package eu.kanade.tachiyomi

import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.NetworkPreferences
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier
import kotlin.coroutines.Continuation

/**
 * Spot-checks the JVM shapes Keiyoushi extensions were compiled against
 * (keiyoushi/extensions-lib @42255ee5): suspend functions carry a trailing
 * Continuation and return Object, ConfigurableSource's setup method takes the
 * real androidx PreferenceScreen, NetworkHelper keeps the (Context)
 * constructor, and so on. The full mechanical diff ran at transplant time;
 * these pin the highest-risk shapes in the test suite.
 */
class SignatureSpotCheckTest {

    private fun Class<*>.method(name: String, vararg params: Class<*>): java.lang.reflect.Method =
        getMethod(name, *params).also { assertNotNull(it) }

    @Test
    fun `Source suspend members have Continuation shapes`() {
        val source = Source::class.java
        val popular = source.method("getPopularManga", Int::class.javaPrimitiveType!!, Continuation::class.java)
        assertEquals(Object::class.java, popular.returnType)
        val update = source.method(
            "getMangaUpdate",
            eu.kanade.tachiyomi.source.model.SManga::class.java,
            List::class.java,
            Boolean::class.javaPrimitiveType!!,
            Boolean::class.javaPrimitiveType!!,
            Continuation::class.java,
        )
        assertEquals(Object::class.java, update.returnType)
        assertEquals(FilterList::class.java, source.method("getFilterList").returnType)
        assertEquals(Long::class.javaPrimitiveType!!, source.method("getId").returnType)
        assertEquals(Boolean::class.javaPrimitiveType!!, source.method("getSupportsLatest").returnType)
    }

    @Test
    fun `CatalogueSource adds lang and related-manga defaults`() {
        val catalogue = CatalogueSource::class.java
        assertEquals(String::class.java, catalogue.method("getLang").returnType)
        val related = catalogue.method(
            "fetchRelatedMangaList",
            eu.kanade.tachiyomi.source.model.SManga::class.java,
            Continuation::class.java,
        )
        assertEquals(Object::class.java, related.returnType)
        assertTrue(Source::class.java.isAssignableFrom(catalogue))
    }

    @Test
    fun `ConfigurableSource setup takes androidx PreferenceScreen`() {
        val setup = ConfigurableSource::class.java.method(
            "setupPreferenceScreen",
            androidx.preference.PreferenceScreen::class.java,
        )
        assertEquals(Void.TYPE, setup.returnType)
        // Komikku shape: a ConfigurableSource is also a Source.
        assertTrue(Source::class.java.isAssignableFrom(ConfigurableSource::class.java))
    }

    @Test
    fun `NetworkHelper keeps both constructor shapes and client`() {
        val helper = NetworkHelper::class.java
        assertNotNull(
            helper.getConstructor(android.content.Context::class.java),
        )
        assertNotNull(
            helper.getConstructor(android.content.Context::class.java, NetworkPreferences::class.java),
        )
        assertEquals(OkHttpClient::class.java, helper.method("getClient").returnType)
        assertEquals(String::class.java, helper.method("defaultUserAgentProvider").returnType)
    }

    @Test
    fun `HttpSource is abstract with the contract helpers`() {
        val http = HttpSource::class.java
        assertTrue(Modifier.isAbstract(http.modifiers))
        assertTrue(CatalogueSource::class.java.isAssignableFrom(http))
        val imageUrl = http.method(
            "getImageUrl",
            Page::class.java,
            Continuation::class.java,
        )
        assertEquals(Object::class.java, imageUrl.returnType)
        assertEquals(String::class.java, http.method("getMangaUrl", eu.kanade.tachiyomi.source.model.SManga::class.java).returnType)
        assertEquals(String::class.java, http.method("getChapterUrl", eu.kanade.tachiyomi.source.model.SChapter::class.java).returnType)
        assertEquals(String::class.java, http.method("getHomeUrl").returnType)
    }

    @Test
    fun `Page constructor shape matches the contract`() {
        val ctor = Page::class.java.getConstructor(
            Int::class.javaPrimitiveType!!,
            String::class.java,
            String::class.java,
            android.net.Uri::class.java,
        )
        assertNotNull(ctor)
        assertEquals(Int::class.javaPrimitiveType!!, Page::class.java.method("getIndex").returnType)
        assertNotNull(Page::class.java.method("setUrl", String::class.java))
        assertNotNull(Page::class.java.method("setImageUrl", String::class.java))
    }

    @Test
    fun `MangasPage keeps its deprecated component and copy members`() {
        val page = MangasPage::class.java
        assertEquals(List::class.java, page.method("component1").returnType)
        assertEquals(Boolean::class.javaPrimitiveType!!, page.method("component2").returnType)
        assertEquals(
            MangasPage::class.java,
            page.method("copy", List::class.java, Boolean::class.javaPrimitiveType!!).returnType,
        )
    }
}
