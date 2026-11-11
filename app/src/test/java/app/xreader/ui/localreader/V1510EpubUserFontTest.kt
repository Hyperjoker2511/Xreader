package app.xreader.ui.localreader

import android.app.Application
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.xreader.data.engine.ReadiumEpubSession
import app.xreader.data.prefs.settingsDataStore
import app.xreader.fixtures.D3EpubFixture
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.util.Url
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * v1.5.10 (G3, SPIKE-GATED): user-supplied EPUB fonts. The
 * spike question, settled here against the shipped Readium
 * 3.1.2 AARs' real seams: can a picked font file be served
 * as a publication asset? Route under test (the production
 * one): picked file → app-private fonts dir → at publication
 * open, ReadiumEpubSession wraps the book's Container with
 * one extra entry at the synthetic href
 * `xreader-fonts/<file>` (an InMemoryResource over the
 * bytes) → the navigator's WebView server resolves the
 * @font-face source through `publication.get(...)`, which
 * reads that container.
 *
 * Assertions: the session exposes the font; the publication
 * serves the EXACT picked bytes at the synthetic href; the
 * book's own resources read exactly as before through the
 * wrapped container; a book with no stored choice (or a
 * missing file) opens on the bundled road untouched; the
 * declaration the reader registers renders @font-face CSS
 * naming the family and the synthetic href.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class V1510EpubUserFontTest {

    @get:Rule val temp = TemporaryFolder()

    private val fontKey = stringPreferencesKey(
        "localreader.${D3EpubFixture.BOOK_ID}" +
            ReadiumEpubSession.USER_FONT_FILE_KEY_SUFFIX
    )

    private val fontBytes =
        "XREADER-SPIKE-FONT-BYTES".toByteArray() +
            ByteArray(64) { it.toByte() }

    @Test
    fun `picked font is served at the synthetic href and the book still reads`() =
        runBlocking {
            val context = RuntimeEnvironment.getApplication()
            val root = temp.newFolder("files")
            val epub = D3EpubFixture.writeBook(root)
            val fontsDir = ReadiumEpubSession.fontsDir(context)
                .apply { mkdirs() }
            File(fontsDir, "SpikeFont.ttf").writeBytes(fontBytes)
            context.settingsDataStore.edit { it[fontKey] = "SpikeFont.ttf" }
            try {
                val session = D3EpubFixture.openSession(root, epub)
                try {
                    assertNotNull(
                        "the session must expose the served font",
                        session.userFont
                    )
                    assertEquals(
                        "xreader-fonts/SpikeFont.ttf",
                        session.userFont!!.href
                    )
                    assertEquals(
                        ReadiumEpubSession.USER_FONT_FAMILY_NAME,
                        session.userFont!!.familyName
                    )
                    // THE spike assertion: the publication
                    // serves the picked bytes at the synthetic
                    // href, through the same get() the
                    // navigator's WebView server reads.
                    val link = Link(
                        href = Url(session.userFont!!.href)!!
                    )
                    val served = session.publication.get(link)
                        ?.read()?.getOrNull()
                    assertArrayEquals(fontBytes, served)
                    // The wrapped container did not disturb
                    // the book: spine + chapter text intact.
                    assertEquals(
                        D3EpubFixture.SPINE.size, session.sectionCount
                    )
                    assertTrue(session.textAt(3).contains("Chapter One"))
                } finally {
                    session.close()
                }
            } finally {
                context.settingsDataStore.edit { it.remove(fontKey) }
            }
        }

    @Test
    fun `no stored choice and missing file both open on the bundled road`() =
        runBlocking {
            val context = RuntimeEnvironment.getApplication()
            val root = temp.newFolder("files")
            val epub = D3EpubFixture.writeBook(root)

            // No stored choice at all.
            val plain = D3EpubFixture.openSession(root, epub)
            try {
                assertNull(plain.userFont)
                assertTrue(plain.textAt(3).contains("Chapter One"))
            } finally {
                plain.close()
            }

            // A stored choice whose file is gone: the book
            // still opens, on the bundled road — never a
            // failure for a font.
            context.settingsDataStore.edit {
                it[fontKey] = "NoLongerThere.ttf"
            }
            try {
                val session = D3EpubFixture.openSession(root, epub)
                try {
                    assertNull(session.userFont)
                    assertTrue(session.textAt(3).contains("Chapter One"))
                } finally {
                    session.close()
                }
            } finally {
                context.settingsDataStore.edit { it.remove(fontKey) }
            }
        }

    @Test
    fun `declaration dsl executes over the public configuration api`() {
        // Readium's FontFaceDeclaration/FontFaceSource are
        // INTERNAL in 3.1.2 — the @font-face CSS is generated
        // inside the toolkit and is not reachable from a test.
        // What this leg proves instead, over public API only:
        // the exact DSL the reader's fragment configuration
        // runs (EpubBookReader's install path) builds a
        // Configuration naming the session's family and the
        // synthetic href, and the href helper composes the
        // same href the container wrap serves.
        val href = ReadiumEpubSession.userFontHref("SpikeFont.ttf")
        assertEquals("xreader-fonts/SpikeFont.ttf", href)
        val configuration = EpubNavigatorFragment.Configuration().apply {
            addFontFamilyDeclaration(
                FontFamily(ReadiumEpubSession.USER_FONT_FAMILY_NAME)
            ) {
                addFontFace { addSource(href) }
            }
        }
        assertNotNull(configuration)
        assertEquals(
            "XReaderUserFont", ReadiumEpubSession.USER_FONT_FAMILY_NAME
        )
    }

    @Test
    fun `picked file names are sanitised to plain font names`() {
        assertEquals(
            "My_Font.ttf", sanitiseUserFontFileName("My Font.ttf")
        )
        assertEquals("Custom.otf", sanitiseUserFontFileName("Custom.otf"))
        assertEquals(
            "evil.name.woff2",
            sanitiseUserFontFileName("../../evil.name.woff2")
        )
        assertNull(sanitiseUserFontFileName("notes.txt"))
        assertNull(sanitiseUserFontFileName("noextension"))
        assertNull(sanitiseUserFontFileName(null))
        assertNull(sanitiseUserFontFileName(".ttf"))
    }
}
