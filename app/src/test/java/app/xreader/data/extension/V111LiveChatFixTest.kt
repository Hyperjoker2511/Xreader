package app.xreader.data.extension

import app.xreader.core.error.AppResult
import app.xreader.data.FakeTransport
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v1.1.1 — the Live Source catalogue junk that SURVIVED
 * v1.1.0's Fix 4 on device (both Popular and Latest on the
 * user's live.chikari.moe row showing the site's "General
 * Chat" cards as books: usernames as titles, avatars as
 * covers, duplicates included).
 *
 * Root cause (see V111-BUILD.md): the real chat anchors are
 * `/chat?comment=<id>` — their normalised path is `/chat`,
 * whose last segment is neither numeric nor chapter-named,
 * so v1.1.0's chapter-shaped-only filter let them through;
 * and findAnchorCluster's Case 1 (anchors sharing one
 * direct parent) short-circuits, so the chat rows — direct
 * siblings in one container — beat the book cards, which
 * sit one-per-wrapper. The v1.1.0 fixture modelled comment
 * anchors as `/novels/<slug>/<n>`, the assumed shape, which
 * is why its tests passed while the device failed.
 *
 * This fixture mirrors the LIVE page structure (verified by
 * inspection, 2026-10-31): book grids under headings with
 * one-card-per-wrapper anchors `/novels/<slug>` and cover
 * img alt = title; a General Chat section BETWEEN the book
 * sections whose rows are direct-sibling anchors to
 * `/chat?comment=<n>` with avatar img alt = username.
 * All prose, titles and comments are synthetic.
 */
class V111LiveChatFixTest {

    @get:Rule
    val tmp = TemporaryFolder()

    companion object {
        private const val BASE = "https://shelf.example"

        private val BOOK_TITLES = listOf(
            "Alpha Flame", "The Clockwork Garden", "Salt and Cinder",
            "A Winter of Sparrows", "The Ninth Tide", "Harbour of Teeth",
            "Letters from the Hollow"
        )
        private val USERNAMES = listOf("caziol", "Monarch")

        /** Book cards: one anchor per wrapper card, in two
         * headed grids (4 + 3). Chat rows: SIX direct-sibling
         * anchors under one container — more than either book
         * grid, and the only direct-sibling cluster on the
         * page, exactly the shape that won on device. */
        private val HOME = """
            <html><body>
            <header><a href="/"><img src="/logo.png" alt="Shelf"/></a></header>
            <main>
              <section class="trending">
                <h2>Trending</h2>
                <div class="grid">
                  <div class="card"><a href="/novels/alpha-flame"><img src="/covers/alpha.jpg" alt="Alpha Flame"/></a></div>
                  <div class="card"><a href="/novels/the-clockwork-garden"><img src="/covers/garden.jpg" alt="The Clockwork Garden"/></a></div>
                  <div class="card"><a href="/novels/salt-and-cinder"><img src="/covers/cinder.jpg" alt="Salt and Cinder"/></a></div>
                  <div class="card"><a href="/novels/a-winter-of-sparrows"><img src="/covers/sparrows.jpg" alt="A Winter of Sparrows"/></a></div>
                </div>
              </section>
              <section class="chat">
                <h2>General Chat</h2>
                <div class="chat-list">
                  <a href="/chat?comment=4346840"><img src="/avatars/caziol.png" alt="caziol"/>caziol: anyone else stuck on the harbour arc?</a>
                  <a href="/chat?comment=4346838"><img src="/avatars/monarch.png" alt="Monarch"/>Monarch: the ninth tide finally broke me</a>
                  <a href="/chat?comment=4346837"><img src="/avatars/caziol.png" alt="caziol"/>caziol: rereading the garden chapters tonight</a>
                  <a href="/chat?comment=4346835"><img src="/avatars/monarch.png" alt="Monarch"/>Monarch: sparrows deserved a longer winter</a>
                  <a href="/chat?comment=4346831"><img src="/avatars/caziol.png" alt="caziol"/>caziol: cinder did nothing wrong</a>
                  <a href="/chat?comment=4346829"><img src="/avatars/monarch.png" alt="Monarch"/>Monarch: hollow letters when</a>
                </div>
              </section>
              <section class="popular">
                <h2>Popular</h2>
                <div class="grid">
                  <div class="card"><a href="/novels/the-ninth-tide"><img src="/covers/tide.jpg" alt="The Ninth Tide"/></a></div>
                  <div class="card"><a href="/novels/harbour-of-teeth"><img src="/covers/teeth.jpg" alt="Harbour of Teeth"/></a></div>
                  <div class="card"><a href="/novels/letters-from-the-hollow"><img src="/covers/hollow.jpg" alt="Letters from the Hollow"/></a></div>
                </div>
              </section>
            </main>
            </body></html>
        """.trimIndent()
    }

    /** A chat THREAD page (what a `/chat?comment=` link
     * opens): its heading is the chat's, and it quotes a
     * book's chapter links — chapter-LOOKING anchors that
     * belong to a book elsewhere, never below `/chat/`.
     * Before the fix, book derivation accepted this page:
     * the "book" was titled from the chat heading and its
     * chapters were the quoted links. */
    private val chatPage = """
        <html><body><main>
          <h1>General Chat</h1>
          <div class="thread">
            <p>caziol: start with these, the harbour arc pays off</p>
            <div class="quoted">
              <a href="/novels/alpha-flame/1">Chapter 1</a>
              <a href="/novels/alpha-flame/2">Chapter 2</a>
              <a href="/novels/alpha-flame/3">Chapter 3</a>
            </div>
            <p>Monarch: chapter two is where it turns</p>
          </div>
        </main></body></html>
    """.trimIndent()

    private val bookPage = """
        <html><body><main>
          <h1>Alpha Flame</h1>
          <img src="/covers/alpha.jpg" alt="Alpha Flame"/>
          <div class="chapters">
            <a href="/novels/alpha-flame/3">Chapter 3</a>
            <a href="/novels/alpha-flame/2">Chapter 2</a>
            <a href="/novels/alpha-flame/1">Chapter 1</a>
          </div>
        </main></body></html>
    """.trimIndent()

    private val chapterProse =
        "The harbour bell counted the hour before the fog had fully " +
            "lifted, and Wren stood at the end of the pier with her " +
            "brother's map folded small in her pocket. Three winters " +
            "the lighthouse had stood dark, and every soul in the " +
            "village had a theory about the night it went out. She " +
            "had come home to prove the kindest of them wrong. The " +
            "tide dragged at the pylons, gulls quarrelled over the " +
            "fish crates, and somewhere behind her a door opened " +
            "and closed without a voice to follow it."

    private val chapterPage = """
        <html><body><main><article>
          <h1>Chapter 1</h1>
          <p>$chapterProse</p><p>$chapterProse</p>
        </article></main></body></html>
    """.trimIndent()

    private fun liveSource(startUrl: String = "$BASE/"): LiveSource {
        val transport = FakeTransport { request ->
            when {
                request.url.contains("/chat?comment=") ->
                    FakeTransport.ok(chatPage)
                request.url == "$BASE/novels/alpha-flame/1" ->
                    FakeTransport.ok(chapterPage)
                request.url == "$BASE/novels/alpha-flame" ->
                    FakeTransport.ok(bookPage)
                request.url.startsWith(BASE) ->
                    FakeTransport.ok(HOME)
                else ->
                    AppResult.Ok(FakeTransport.response(404, "not found"))
            }
        }
        val context = testContext(
            transport = transport,
            filesDir = tmp.newFolder(),
            permissions = ExtensionPermissions(domains = listOf("shelf.example"))
        )
        return LiveSource(
            LiveSourceSpec(
                id = "live.shelf-example",
                name = "Shelf",
                baseUrl = BASE,
                startUrl = startUrl
            ),
            context
        )
    }

    // ── the device failure, reproduced end to end ──────────

    @Test
    fun `live popular shows books only - never the chat rail`() {
        val page = liveSource().popular(1)
        assertTrue("expected a non-empty catalogue", page.books.isNotEmpty())
        assertTrue(
            "usernames leaked into the catalogue: ${page.books.map { it.title }}",
            page.books.none { it.title in USERNAMES }
        )
        assertTrue(
            "non-book titles in the catalogue: ${page.books.map { it.title }}",
            page.books.all { it.title in BOOK_TITLES }
        )
        assertTrue(
            "non-book links in the catalogue: ${page.books.map { it.id }}",
            page.books.all { BookShape.isBookShapedHref(it.id) }
        )
        assertTrue(page.books.size >= 3)
    }

    @Test
    fun `live latest shows books only - never the chat rail`() {
        val page = liveSource().latest(1)
        assertTrue(page.books.isNotEmpty())
        assertTrue(page.books.none { it.title in USERNAMES })
        assertTrue(page.books.all { it.title in BOOK_TITLES })
    }

    // ── the analysis itself ────────────────────────────────

    @Test
    fun `books-only analysis picks a book cluster and carries its link pattern`() {
        val doc = Jsoup.parse(HOME, BASE)
        val analysis = LinkInference.analyseListingPage(
            doc, "$BASE/", null, booksOnly = true
        )
        assertNotNull(analysis)
        val found = analysis!!
        assertEquals("img@alt", found.titleSpec)
        // The derived spec must carry the Book-Shape Rule so
        // the fetch enforces it too (belt and braces).
        assertEquals("/novels/[^/]+$", found.linkPattern)
        assertTrue(found.count >= 3)
    }

    @Test
    fun `probe analysis without books-only is untouched by the chat fix`() {
        // The probe must still SEE the page as it is — its
        // list-shape verdicts depend on unfiltered anchors.
        val doc = Jsoup.parse(HOME, BASE)
        val analysis = LinkInference.analyseListingPage(doc, "$BASE/", null)
        assertNotNull(analysis)
    }

    // ── the details / add-to-library entry point ──────────

    @Test
    fun `live details refuses a chat-shaped url instead of deriving a book`() {
        val source = liveSource()
        val thrown = runCatching {
            source.details(
                app.xreader.extensionapi.SourceBook(
                    id = "$BASE/chat?comment=4346840", title = "caziol"
                )
            )
        }.exceptionOrNull()
        assertTrue(
            "a chat url must fail honestly, got: $thrown",
            thrown is app.xreader.extensionapi.SourceException
        )
    }

    @Test
    fun `live popular refuses a chat-shaped start url`() {
        val source = liveSource(startUrl = "$BASE/chat?comment=4346840")
        val thrown = runCatching { source.popular(1) }.exceptionOrNull()
        assertTrue(
            "a chat start url must fail honestly, got: $thrown",
            thrown is app.xreader.extensionapi.SourceException
        )
    }

    @Test
    fun `live details still derives a real book page`() {
        val source = liveSource()
        val ref = app.xreader.extensionapi.SourceBook(
            id = "$BASE/novels/alpha-flame", title = "Alpha Flame"
        )
        val details = source.details(ref)
        assertEquals("Alpha Flame", details.title)
        val chapters = source.chapters(ref)
        assertEquals(3, chapters.size)
    }

    // ── the shape predicate ────────────────────────────────

    @Test
    fun `book shape predicate reads path shape alone`() {
        // The device shape: identity lives in the query, the
        // path is a bare section name — never a book.
        assertFalse(BookShape.isBookShapedHref("https://chikari.moe/chat?comment=4346840"))
        assertFalse(BookShape.isBookShapedHref("/chat?comment=12"))
        // Book shapes across the common collection spellings.
        assertTrue(BookShape.isBookShapedHref("https://chikari.moe/novels/shadow-slave"))
        assertTrue(BookShape.isBookShapedHref("/series/second-life-ranker"))
        assertTrue(BookShape.isBookShapedHref("/novel/alpha"))
        assertTrue(BookShape.isBookShapedHref("https://x.example/en/novels/some-book"))
        // Chapters are not books, however they are spelled.
        assertFalse(BookShape.isBookShapedHref("/novels/shadow-slave/214"))
        assertFalse(BookShape.isBookShapedHref("/novels/shadow-slave/chapter-3"))
        // A bare section or the root is not a book either.
        assertFalse(BookShape.isBookShapedHref("/novels"))
        assertFalse(BookShape.isBookShapedHref("https://x.example/"))
    }
}
