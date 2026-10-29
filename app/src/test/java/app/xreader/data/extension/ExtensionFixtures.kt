package app.xreader.data.extension

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import app.xreader.core.security.KeyVault
import app.xreader.core.security.SecretCipher
import app.xreader.data.FakeTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

/** Test KeyVault: in-memory DataStore + a tagging fake cipher
 * (same approach as KeyVaultTest). */
class FakeCipher : SecretCipher {
    override fun encrypt(plain: ByteArray): ByteArray = byteArrayOf(TAG) + plain
    override fun decrypt(data: ByteArray): ByteArray {
        require(data.isNotEmpty() && data[0] == TAG) { "bad tag" }
        return data.copyOfRange(1, data.size)
    }

    private companion object {
        const val TAG: Byte = 0x5A
    }
}

class FakePreferencesStore : DataStore<Preferences> {
    private val state = MutableStateFlow(emptyPreferences())
    override val data: Flow<Preferences> = state
    override suspend fun updateData(
        transform: suspend (Preferences) -> Preferences
    ): Preferences {
        val updated = transform(state.value)
        state.value = updated
        return updated
    }
}

fun testVault(): KeyVault =
    KeyVault(FakePreferencesStore(), FakeCipher(), CoroutineScope(Dispatchers.Unconfined))

fun testSessionStore(): SessionStore = SessionStore(testVault())

/** A host context for tests: real facades over a FakeTransport-backed
 * client, whitelist = the given permissions object. */
fun testContext(
    transport: FakeTransport,
    filesDir: File,
    permissions: ExtensionPermissions = ExtensionPermissions(
        domains = listOf(FixtureSite.HOST)
    ),
    sessionStore: SessionStore = testSessionStore()
): app.xreader.extensionapi.SourceContext {
    val factory = HostContextFactory(
        store = ExtensionStore(filesDir),
        sessionStore = sessionStore,
        transport = transport
    )
    return factory.contextFor(
        extensionRowId = 1,
        packageId = "test.pkg",
        label = "Test Source",
        permissions = { permissions }
    )
}

/**
 * A tiny fake novel site (host novels.example) with known structure:
 * a catalogue, a book page whose chapters are listed NEWEST FIRST,
 * and a two-page first chapter with an ad block to strip.
 */
object FixtureSite {
    const val HOST = "novels.example"
    const val BASE = "https://novels.example"

    const val BOOK_URL = "$BASE/book/sword-saga"
    const val CHAPTER_1_URL = "$BASE/book/sword-saga/chapter-1"
    const val CHAPTER_2_URL = "$BASE/book/sword-saga/chapter-2"
    const val CHAPTER_3_URL = "$BASE/book/sword-saga/chapter-3"

    val chapterOneText = (1..6).joinToString("\n") {
        "Paragraph $it of the first chapter, where the sword is drawn and the saga truly begins."
    }
    val chapterOnePageTwoText =
        "Final paragraph of chapter one, after the page break, where the hero rests."

    fun pages(): Map<String, String> = mapOf(
        "$BASE/popular" to """
            <html><head><title>Popular</title></head><body>
            <div class="results">
              <div class="result">
                <a class="t" href="/book/sword-saga">Sword Saga</a>
                <span class="a">Jane Author</span>
                <img class="c" src="/covers/sword.jpg"/>
              </div>
              <div class="result">
                <a class="t" href="/book/other-tale">Other Tale</a>
                <span class="a">John Writer</span>
              </div>
            </div>
            <a class="next" href="/popular?page=2">Next</a>
            </body></html>
        """.trimIndent(),
        "$BASE/search?q=sword" to """
            <html><head><title>Search</title></head><body>
            <div class="results">
              <div class="result">
                <a class="t" href="/book/sword-saga">Sword Saga</a>
                <span class="a">Jane Author</span>
              </div>
            </div>
            </body></html>
        """.trimIndent(),
        BOOK_URL to """
            <html><head>
              <title>Sword Saga - Novels Example</title>
              <meta name="description" content="A hero, a sword, a very long saga."/>
              <meta property="og:image" content="$BASE/covers/sword.jpg"/>
            </head><body>
            <h1 class="book-title">Sword Saga</h1>
            <div class="author">Jane Author</div>
            <ul class="chapters">
              <li><a href="/book/sword-saga/chapter-3">Chapter 3: The Duel</a></li>
              <li><a href="/book/sword-saga/chapter-2">Chapter 2: The Road</a></li>
              <li><a href="/book/sword-saga/chapter-1">Chapter 1: The Beginning</a></li>
            </ul>
            </body></html>
        """.trimIndent(),
        CHAPTER_1_URL to """
            <html><head><title>Chapter 1</title></head><body>
            <h1>Chapter 1: The Beginning</h1>
            <div class="content">
              <div class="ad">BUY PREMIUM NOW</div>
              ${chapterOneText.split("\n").joinToString("\n") { "<p>$it</p>" }}
            </div>
            <a class="next-page" href="/book/sword-saga/chapter-1?page=2">Next page</a>
            </body></html>
        """.trimIndent(),
        "$CHAPTER_1_URL?page=2" to """
            <html><head><title>Chapter 1 (2)</title></head><body>
            <div class="content">
              <p>$chapterOnePageTwoText</p>
            </div>
            </body></html>
        """.trimIndent(),
        CHAPTER_2_URL to """
            <html><head><title>Chapter 2</title></head><body>
            <div class="content">
              <p>Chapter two text, on the road, travelling far from home.</p>
            </div>
            </body></html>
        """.trimIndent(),
        CHAPTER_3_URL to """
            <html><head><title>Chapter 3</title></head><body>
            <div class="content">
              <p>Chapter three text, the duel at dawn, blades flashing.</p>
            </div>
            </body></html>
        """.trimIndent()
    )

    /** A FakeTransport serving [pages] by exact URL (404 otherwise). */
    fun transport(extra: Map<String, String> = emptyMap()): FakeTransport {
        val all = pages() + extra
        return FakeTransport { request ->
            val body = all[request.url]
            if (body == null) {
                app.xreader.core.error.AppResult.Ok(
                    FakeTransport.response(404, "not found")
                )
            } else {
                FakeTransport.ok(body)
            }
        }
    }

    /** The declarative config a repo would ship for this site. */
    fun config(): DeclarativeConfig = DeclarativeConfig(
        id = "novels-example",
        name = "Novels Example",
        lang = "en",
        baseUrl = BASE,
        search = ListSpec(
            url = "$BASE/search?q={query}",
            results = "div.result",
            title = "a.t",
            link = "a.t@href",
            cover = "img.c@src",
            author = "span.a",
            nextPage = "a.next"
        ),
        popular = ListSpec(
            url = "$BASE/popular",
            results = "div.result",
            title = "a.t",
            link = "a.t@href",
            cover = "img.c@src",
            author = "span.a",
            nextPage = "a.next"
        ),
        latest = null,
        details = DetailsSpec(
            title = "h1.book-title",
            author = "div.author",
            description = "meta[name=description]@content",
            cover = "meta[property=og:image]@content",
            chapters = ChapterListSpec(
                list = "ul.chapters > li",
                name = "a",
                link = "a@href",
                reverse = true
            )
        ),
        chapter = ChapterSpec(
            content = "div.content",
            title = "h1",
            nextPage = "a.next-page@href",
            remove = listOf("div.ad")
        )
    )
}
