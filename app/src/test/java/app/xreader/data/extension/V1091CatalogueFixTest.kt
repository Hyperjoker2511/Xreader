package app.xreader.data.extension

import app.xreader.core.error.AppResult
import app.xreader.data.FakeTransport
import app.xreader.data.prefs.PerfTier
import app.xreader.extensionapi.HostResponse
import app.xreader.extensionapi.SourceBook
import app.xreader.extensionapi.SourceException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v1.0.9.1: the chikari catalogue fix. On device (v1.0.9) the
 * saved lane source's Popular catalogue came back EMPTY even
 * though v1.0.8.2 carried the wait-for-content hint into the
 * source's own fetches (pinned in V1082RuntimeHintTest — the
 * hint DID reach the renderer). The gap the hint cannot close:
 * the wait is capped at RenderSettle.HINT_WAIT_MAX_MS (25 s),
 * well inside the render's hard timeout, so on a slow
 * connection an app-shell page can settle on its CHROME alone
 * — header/footer clear both shell bands (anchors >= 5,
 * text >= 120) while the catalogue data is still in flight —
 * and the browser view's own shell verdict passes that chrome
 * as a built page. The listing parse then found no books and
 * the catalogue layer presented a legitimately-looking empty
 * page: no error, no retry.
 *
 * The fix is at the fetch layer, for every hinted source and
 * every fetch kind: a render that comes back without the
 * content it was told to wait for is rendered once more, and
 * if the content still isn't there the fetch fails with the
 * browser view's own "never finished building itself" error
 * instead of handing over the content-less page. A second
 * net sits in the listing engine itself: an empty listing
 * parsed from a page that is still a JS shell is an error,
 * never an empty catalogue.
 */
class V1091CatalogueFixTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** A built page that is all chrome: header nav and footer
     * (nine anchors, well over the shell band's text floor) and
     * a loading note where the catalogue should be. It carries
     * NO element the lane's wait selector — or its results
     * selector — can match, exactly like a render that settled
     * before the site's data arrived. Synthetic markup. */
    private val chromePage = """
        <html><head><title>Shellbook</title></head><body>
        <header>
          <a href="/">Shellbook</a>
          <nav>
            <a href="/">Home</a>
            <a href="/search">Search</a>
            <a href="/community">Community</a>
            <a href="/leaderboard">Leaderboard</a>
            <a href="/profile">Profile</a>
          </nav>
        </header>
        <main>
          <div class="spinner">Loading the catalogue — one moment
          while the shelves are stocked.</div>
        </main>
        <footer>
          <p>Shellbook is a small library on the web. Browse
          stories, keep a shelf of favourites, and pick up right
          where you left off, on any device you own.</p>
          <a href="/rules">Rules</a>
          <a href="/privacy">Privacy</a>
          <a href="/contact">Contact</a>
        </footer>
        </body></html>
    """.trimIndent()

    /** A fully built catalogue page that legitimately has no
     * books on it — NOT a shell (a long editorial note, no app
     * mount, no script tricks), so an empty listing read from
     * it is an honest empty page. */
    private val builtEmptyPage = """
        <html><head><title>Catalogue</title></head><body>
        <main>
          <h1>Catalogue</h1>
          <p>There is nothing on this shelf yet. New stories are
          added by the site's editors every week, so check back
          soon — or browse the community shelves in the meantime
          for something to read tonight.</p>
        </main>
        </body></html>
    """.trimIndent()

    /** A [RenderedPageFetcher] that hands back [pages] in call
     * order (the last page repeats), recording every call and
     * the wait selector each call carried. */
    private class SequencedRenderedFetcher(
        private val pages: List<String>
    ) : RenderedPageFetcher {
        val calls = mutableListOf<String>()
        val selectors = mutableListOf<String?>()

        override suspend fun fetchRendered(
            url: String,
            sessionKey: String,
            permissions: ExtensionPermissions,
            waitForSelector: String?
        ): AppResult<HostResponse> {
            calls += url
            selectors += waitForSelector
            val body = pages.getOrElse(calls.size - 1) { pages.last() }
            return AppResult.Ok(
                HostResponse(status = 200, body = body, finalUrl = url)
            )
        }
    }

    private fun runtime(): RenderRuntime {
        val runtime = RenderRuntime(
            MutableStateFlow(true), MutableStateFlow(PerfTier.FULL)
        )
        runtime.appInForeground = true
        return runtime
    }

    private fun lane(): DeclarativeConfig {
        val entry = KnownSites.entryFor(ChikariSite.HOST)!!
        return KnownSites.fullConfig(
            entry, "chikari", "Chikari", ChikariSite.BASE
        )!!
    }

    /** The runtime context ExtensionService builds for the
     * saved lane source: marked needsRendering, the lane's
     * declared hint, and [renderer] behind the escalating
     * fetcher. */
    private fun hintedSource(renderer: SequencedRenderedFetcher): DeclarativeSource {
        val lane = lane()
        val factory = HostContextFactory(
            ExtensionStore(tmp.newFolder()),
            testSessionStore(),
            ChikariSite.transport(),
            renderedFetcher = renderer,
            renderRuntime = runtime()
        )
        val context = factory.contextFor(
            extensionRowId = 21L,
            packageId = "link.chikari.moe",
            label = "Chikari",
            permissions = {
                ExtensionPermissions(domains = listOf(ChikariSite.HOST))
            },
            needsRendering = { true },
            waitForSelector = { lane.renderWaitSelector }
        )
        return DeclarativeSource(lane, context)
    }

    @Test
    fun `a hinted catalogue fetch re-renders when the first render settles without the waited-for content`() =
        runBlocking {
            val renderer = SequencedRenderedFetcher(
                listOf(chromePage, ChikariSite.RENDERED_HOME_PAGE)
            )
            val page = hintedSource(renderer).popular(1)
            assertEquals(3, page.books.size)
            // Exactly one retry, both renders of the catalogue
            // URL, both carrying the lane's hint.
            assertEquals(
                listOf(ChikariSite.BASE, ChikariSite.BASE), renderer.calls
            )
            assertEquals(
                listOf(lane().renderWaitSelector, lane().renderWaitSelector),
                renderer.selectors
            )
        }

    @Test
    fun `a hinted catalogue fetch fails honestly when the waited-for content never arrives`() =
        runBlocking {
            val renderer = SequencedRenderedFetcher(listOf(chromePage))
            val thrown = runCatching {
                hintedSource(renderer).popular(1)
            }.exceptionOrNull()
            assertTrue(thrown is SourceException)
            assertTrue(
                thrown!!.message!!.contains("never finished building itself")
            )
            // One retry, never a loop.
            assertEquals(2, renderer.calls.size)
        }

    @Test
    fun `a hinted catalogue fetch with the content present renders exactly once`() =
        runBlocking {
            val renderer = SequencedRenderedFetcher(
                listOf(ChikariSite.RENDERED_HOME_PAGE)
            )
            val page = hintedSource(renderer).popular(1)
            assertEquals(3, page.books.size)
            assertEquals(1, renderer.calls.size)
        }

    @Test
    fun `a hinted book fetch re-renders when the first render settles without the waited-for content`() =
        runBlocking {
            val renderer = SequencedRenderedFetcher(
                listOf(chromePage, ChikariSite.RENDERED_BOOK_PAGE)
            )
            val (book, chapters) = hintedSource(renderer).detailsAndChapters(
                SourceBook(id = ChikariSite.BOOK_URL, title = "Shadow Slave")
            )
            assertEquals("Shadow Slave", book.title)
            // The retried render's page states 7 chapters; the
            // lane's pattern completion closes the list.
            assertEquals(7, chapters.size)
            assertEquals(
                listOf(ChikariSite.BOOK_URL, ChikariSite.BOOK_URL),
                renderer.calls
            )
        }

    @Test
    fun `an empty listing read from a shell page is an error not an empty catalogue`() =
        runBlocking {
            // No renderer wired: the static client serves the
            // site's shell for every route, exactly what a
            // marked source degenerates to when the browser
            // view is unavailable or switched off.
            val context = testContext(
                ChikariSite.transport(), tmp.newFolder(),
                permissions = ExtensionPermissions(
                    domains = listOf(ChikariSite.HOST)
                )
            )
            val thrown = runCatching {
                DeclarativeSource(lane(), context).popular(1)
            }.exceptionOrNull()
            assertTrue(thrown is SourceException)
            assertTrue(
                thrown!!.message!!.contains("never finished loading")
            )
        }

    @Test
    fun `an empty listing read from a built page stays an honest empty page`() =
        runBlocking {
            val transport = FakeTransport { request ->
                if (request.url == ChikariSite.BASE ||
                    request.url == "${ChikariSite.BASE}/"
                ) {
                    FakeTransport.ok(builtEmptyPage)
                } else {
                    AppResult.Ok(FakeTransport.response(404, "not found"))
                }
            }
            val context = testContext(
                transport, tmp.newFolder(),
                permissions = ExtensionPermissions(
                    domains = listOf(ChikariSite.HOST)
                )
            )
            val page = DeclarativeSource(lane(), context).popular(1)
            assertTrue(page.books.isEmpty())
            assertEquals(false, page.hasNextPage)
        }
}
