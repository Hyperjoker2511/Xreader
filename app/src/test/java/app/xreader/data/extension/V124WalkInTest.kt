package app.xreader.data.extension

import app.xreader.core.error.AppResult
import app.xreader.data.FakeTransport
import java.io.File
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v1.2.4 investigation — the ROOT-paste walk-in failure and
 * its Live twin (device, 2026-10-31 22:48 + 22:50 IST,
 * v1.2.3). Fail-before evidence was recorded by this file's
 * survey run on the pre-fix tree (JUnit XML, 2026-10-31):
 *
 *  - probe("https://novelbuddy.me") -> Failed with
 *    HOME_PAGE_COPY, word for word the device refusal.
 *  - analyseListingPage(landing) = null in both modes: the
 *    walk-in gate (listing != null) could never open, and
 *    the catalogue hop behind it was unreachable.
 *  - Live popular on the STATIC app home -> "didn't show a
 *    book list or a book page Xreader can read" (booksOnly
 *    analysis null: every real book is a root slug, filtered
 *    out by the ≥2-segment rule).
 *  - Live popular on the RENDERED app home -> the six PEOPLE
 *    rows ('Reader One' -> /users/101-reader-one, …,
 *    linkPattern /users/[^/]+$) — the device screenshot,
 *    reproduced exactly.
 *
 * Site facts (captures + the site's own home chunk):
 * - novelbuddy.me/ is a marketing LANDING: 17 text anchors,
 *   no book anchors, no payload. Its first catalogue-smelling
 *   link is /browse ("Library") — the site's 404 template;
 *   the real catalogue signpost is /home ("Browse Novels").
 * - novelbuddy.me/home is the app's content home: books at
 *   ROOT slugs (/<slug>). Its Community Discussion / XP
 *   leaderboard rails hydrate client-side; discussion cards
 *   link the author's profile and leaderboard cards link
 *   members, both at /users/<id>-<slug> (verified in the
 *   pages/home chunk: "/users/".concat(user.id, "-", …),
 *   href: t.user.url). The rendered fixture below is the
 *   real app-home capture + a synthetic people rail in that
 *   verified shape (names are ours, per the fixture rule).
 *
 * Transport honesty (the matrix rule): any book URL of a
 * site is served that site's ONE captured book page with the
 * requested slug substituted into its links (same template,
 * same book — the probe's walk-in picks the listing's first
 * book, which is not the captured one); likewise chapters.
 */
class V124WalkInTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun captureRoot(): File? {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(6) {
            val base = dir ?: return null
            if (File(base, "fixtures-capture").isDirectory) return base
            dir = base.parentFile
        }
        return null
    }

    private fun read(root: File, site: String, name: String) =
        File(root, "fixtures-capture/$site/$name").readText(Charsets.UTF_8)

    // ── novelbuddy fixtures ────────────────────────────────

    /** The real app-home capture + a client-side people rail
     * in the shape the site's home chunk verifies (profile
     * hrefs /users/<id>-<slug>, avatar img, member name). */
    private fun renderedAppHome(root: File): String {
        val names = listOf(
            "Reader One", "Page Turner", "Night Owl",
            "Bookmark Bee", "Chapter Chaser", "Prose Prowler"
        )
        val people = names.mapIndexed { i, name ->
            val slug = name.lowercase().replace(' ', '-')
            """<a class="member" href="/users/${101 + i}-$slug">""" +
                """<img src="/avatars/${101 + i}.png" alt="$name"/></a>"""
        }.joinToString("")
        val rail = """<section class="xp-rail"><h2>Top Readers</h2>""" +
            """<div class="rail">$people</div></section>"""
        return read(root, "novelbuddy.com", "app-home.html")
            .replace("</main>", "$rail</main>")
    }

    private fun novelbuddyTransport(root: File, rendered: Boolean = false): FakeTransport {
        val landing = read(root, "novelbuddy.com", "home.html")
        val appHome = if (rendered) {
            renderedAppHome(root)
        } else {
            read(root, "novelbuddy.com", "app-home.html")
        }
        val latest = read(root, "novelbuddy.com", "latest.html")
        val bookBody = read(root, "novelbuddy.com", "book.html")
        val chapterBody = read(root, "novelbuddy.com", "chapter.html")
        val notfound = read(root, "novelbuddy.com", "browse.html")
        val capturedSlug = "eternal-holy-emperor"
        val bookShape = Regex("https://novelbuddy\\.me/([a-z0-9][a-z0-9-]*)")
        val chapterShape = Regex("https://novelbuddy\\.me/([a-z0-9-]+)/chapter-.*")
        val chrome = setOf(
            "https://novelbuddy.me/latest", "https://novelbuddy.me/ranking",
            "https://novelbuddy.me/search", "https://novelbuddy.me/browse",
            "https://novelbuddy.me/home"
        )
        return FakeTransport { request ->
            val url = request.url
            if (request.method != "GET") {
                return@FakeTransport AppResult.Ok(
                    FakeTransport.response(404, notfound)
                )
            }
            val clean = url.substringBefore('?')
            val chapterMatch = chapterShape.matchEntire(clean)
            val bookMatch = bookShape.matchEntire(clean)
            val body = when {
                url == "https://novelbuddy.me" ||
                    url == "https://novelbuddy.me/" -> landing
                clean == "https://novelbuddy.me/home" -> appHome
                clean == "https://novelbuddy.me/latest" -> latest
                chapterMatch != null -> chapterBody.replace(
                    capturedSlug, chapterMatch.groupValues[1]
                )
                bookMatch != null && clean !in chrome -> bookBody.replace(
                    capturedSlug, bookMatch.groupValues[1]
                )
                else -> null
            }
            if (body == null) {
                AppResult.Ok(FakeTransport.response(404, notfound))
            } else {
                FakeTransport.ok(body)
            }
        }
    }

    private fun prober(transport: FakeTransport): LinkProber {
        val factory = HostContextFactory(
            ExtensionStore(tmp.newFolder()), testSessionStore(), transport
        )
        return LinkProber(factory, testSessionStore())
    }

    // ── 1. the root paste walks in ─────────────────────────

    @Test(timeout = 900_000)
    fun `root paste walks in and drafts a catalogued source`() = runBlocking {
        val root = captureRoot()
        assumeTrue("captures not on disk", root != null)
        val outcome = prober(novelbuddyTransport(root!!))
            .probe("https://novelbuddy.me", aiAvailable = false)
        val ready = outcome as? LinkProber.Outcome.Ready
        assertNotNull(
            "the root paste must walk in, got: $outcome", ready
        )
        val config = ready!!.draft.config
        println(
            "[v124] root draft popular=${config.popular?.url} " +
                "latest=${config.latest?.url} search=${config.search?.url}"
        )
        assertTrue(
            "the walked-in source carries a catalogue (the v1.2.3 gate)",
            config.popular != null || config.latest != null
        )
        assertTrue(
            "the stage lines record the walk-in: " +
                ready.stageLines.joinToString { it.render() },
            ready.stageLines.any { it.step == "walked in" }
        )
        Unit
    }

    @Test(timeout = 900_000)
    fun `app home paste probes ready`() = runBlocking {
        val root = captureRoot()
        assumeTrue("captures not on disk", root != null)
        val outcome = prober(novelbuddyTransport(root!!))
            .probe("https://novelbuddy.me/home", aiAvailable = false)
        assertTrue(
            "the /home paste must probe ready, got: $outcome",
            outcome is LinkProber.Outcome.Ready
        )
        Unit
    }

    // ── 2. the Live catalogue: books, not people ───────────

    private fun livePopular(root: File, rendered: Boolean): List<Pair<String, String>> {
        val transport = novelbuddyTransport(root, rendered)
        val context = testContext(
            transport, tmp.newFolder(),
            ExtensionPermissions(domains = listOf("novelbuddy.me"))
        )
        val live = LiveSource(
            LiveSourceSpec(
                id = "live.test", name = "NovelBuddy",
                baseUrl = "https://novelbuddy.me",
                startUrl = "https://novelbuddy.me/home"
            ),
            context
        )
        return live.popular(1).books.map { it.title to it.id }
    }

    @Test
    fun `live popular on the static app home returns books`() {
        val root = captureRoot()
        assumeTrue("captures not on disk", root != null)
        val rows = livePopular(root!!, rendered = false)
        println("[v124] LIVE static rows=${rows.take(4)}")
        assertTrue("the catalogue reads books: $rows", rows.size >= 6)
        assertTrue(
            "every row is a root-level book slug: $rows",
            rows.all {
                it.second.startsWith("https://novelbuddy.me/") &&
                    !it.second.contains("/users/")
            }
        )
    }

    @Test
    fun `live popular on the rendered app home returns books not people`() {
        val root = captureRoot()
        assumeTrue("captures not on disk", root != null)
        val rows = livePopular(root!!, rendered = true)
        println("[v124] LIVE rendered rows=${rows.take(4)}")
        assertTrue("the catalogue reads books: $rows", rows.size >= 6)
        assertTrue(
            "no people row may reach the catalogue: $rows",
            rows.none { it.second.contains("/users/") }
        )
        assertFalse(
            "no member name may be a book title: $rows",
            rows.any { it.first == "Reader One" || it.first == "Page Turner" }
        )
    }

    // ── 3. shape units ─────────────────────────────────────

    @Test
    fun `book candidate shapes - root books in, people and sections out`() {
        val base = "https://site.example"
        // Root-level books (novelbuddy / xnovel shape).
        assertTrue(BookShape.isRootBookHref("$base/supreme-magus"))
        assertTrue(BookShape.isBookCandidateHref("$base/supreme-magus"))
        assertTrue(BookShape.isBookCandidateHref("$base/hidden-marriage.html"))
        // Sections are not books.
        for (section in listOf(
            "latest", "ranking", "browse", "home", "search",
            "chat", "genre", "login", "community"
        )) {
            assertFalse(
                "/$section must not be a root book",
                BookShape.isRootBookHref("$base/$section")
            )
        }
        // Chapter-shaped and numeric roots are not books.
        assertFalse(BookShape.isRootBookHref("$base/chapter-12"))
        assertFalse(BookShape.isRootBookHref("$base/12345"))
        // People collections: slug tail, still not books.
        assertTrue(
            BookShape.isPeopleCollectionHref("$base/users/101-reader-one")
        )
        assertTrue(
            "the bare shape rule alone admits a profile (the v1.2.3 trap)",
            BookShape.isBookShapedHref("$base/users/101-reader-one")
        )
        assertFalse(
            BookShape.isBookCandidateHref("$base/users/101-reader-one")
        )
        assertFalse(
            BookShape.isBookCandidateHref("$base/profile/reader-one")
        )
        assertFalse(
            BookShape.isBookCandidateHref("$base/members/reader-one")
        )
        // Ordinary collection books are untouched.
        assertTrue(BookShape.isBookCandidateHref("$base/novels/some-book"))
        assertTrue(BookShape.isBookCandidateHref("$base/mobile/book/101"))
        assertEquals(
            "/[^/]+$",
            BookShape.linkPatternForPrefix("")
        )
        // Chapter rows OF a book — the listing-rescue's other
        // half. A wordy chapter tail passes as book-shaped
        // (that is the trap), but it is a chapter of its book:
        // it lives under the book's path. A listing's book
        // rows are not chapters of the listing page.
        val book = "$base/eternal-holy-emperor"
        assertTrue(
            BookShape.isChapterRowOf(
                book, "$book/chapter-2217-2043-shaking-the-world"
            )
        )
        assertTrue(BookShape.isChapterRowOf(book, "$book/12"))
        assertTrue(
            BookShape.isChapterRowOf(
                "$base/series/some-book",
                "$base/series/some-book/chapter-1-the-start"
            )
        )
        assertFalse(
            BookShape.isChapterRowOf("$base/home", "$base/a-night-of-desire")
        )
        assertFalse(
            "rows under a collection index are its books, not chapters",
            BookShape.isChapterRowOf("$base/novels", "$base/novels/some-book")
        )
        assertFalse(BookShape.isChapterRowOf(book, book))
    }

    // ── 4. neighbour root pastes (walk-in regressions) ─────

    /** A neighbour transport in the matrix's serving spirit:
     * the captured home at the site root, the captured book
     * page (slug-substituted) for any book-shaped URL of the
     * site's book family, the captured chapter page for any
     * chapter URL of that family, 404 otherwise. */
    private fun neighbourTransport(
        homeBody: String,
        rootUrl: String,
        bookBody: String,
        capturedBookPath: String,
        bookPattern: Regex,
        chapterPattern: Regex,
        extraPages: Map<String, String> = emptyMap()
    ): FakeTransport = FakeTransport { request ->
        val url = request.url
        if (request.method != "GET") {
            return@FakeTransport AppResult.Ok(FakeTransport.response(404, ""))
        }
        val clean = url.substringBefore('?')
        val bookMatch = bookPattern.find(clean)
        val body = when {
            clean == rootUrl || clean == "$rootUrl/" -> homeBody
            extraPages.containsKey(clean) -> extraPages.getValue(clean)
            chapterPattern.containsMatchIn(clean) ->
                extraPages["chapter"] ?: ""
            bookMatch != null -> {
                val requested = bookMatch.value
                val captured = "$rootUrl$capturedBookPath".trimEnd('/')
                if (requested.trimEnd('/') == captured) {
                    bookBody
                } else {
                    // Same template, requested book: substitute
                    // the captured book's whole path (id + slug)
                    // with the requested one, so the page's own
                    // chapter links stay under the walked-into
                    // book — the aliasing the matrix harness
                    // documents for its chapter serves.
                    val capturedPath = capturedBookPath.trim('/')
                    val requestedPath = requested
                        .substringAfter(rootUrl).trim('/')
                    bookBody.replace(capturedPath, requestedPath)
                }
            }
            else -> null
        }
        if (body == null) {
            AppResult.Ok(FakeTransport.response(404, ""))
        } else {
            FakeTransport.ok(body)
        }
    }

    private fun assertRootProbesReady(
        label: String,
        rootUrl: String,
        transport: FakeTransport
    ) = runBlocking {
        val outcome = prober(transport).probe(rootUrl, aiAvailable = false)
        when (outcome) {
            is LinkProber.Outcome.Ready -> println(
                "[v124] NEIGHBOUR $label root -> Ready " +
                    "popular=${outcome.draft.config.popular != null} " +
                    "latest=${outcome.draft.config.latest != null}"
            )
            is LinkProber.Outcome.Failed -> println(
                "[v124] NEIGHBOUR $label root -> Failed: ${outcome.message} " +
                    outcome.stageLines.joinToString(" ;; ") { it.render() }
            )
            else -> println("[v124] NEIGHBOUR $label root -> $outcome")
        }
        assertTrue(
            "$label root paste must walk in, got: $outcome",
            outcome is LinkProber.Outcome.Ready
        )
        Unit
    }

    @Test(timeout = 900_000)
    fun `neighbour woopread root still walks in`() {
        val root = captureRoot()
        assumeTrue("captures not on disk", root != null)
        root!!
        // woopread: books /series/<slug>, chapters below them.
        assertRootProbesReady(
            "woopread", "https://woopread.com",
            neighbourTransport(
                homeBody = read(root, "woopread.com", "home.html"),
                rootUrl = "https://woopread.com",
                bookBody = read(root, "woopread.com", "book.html"),
                capturedBookPath = "/series/the-star-painting-genius",
                bookPattern = Regex("https://woopread\\.com/series/[^/]+$"),
                chapterPattern = Regex("https://woopread\\.com/series/[^/]+/chapter-"),
                extraPages = mapOf(
                    "chapter" to read(root, "woopread.com", "chapter.html")
                )
            )
        )
    }

    @Test(timeout = 900_000)
    fun `neighbour freewebnovel root still walks in`() {
        val root = captureRoot()
        assumeTrue("captures not on disk", root != null)
        root!!
        // freewebnovel: books /novel/<slug>; its home is
        // served at /home on the live site — the transport
        // serves the same body at the root, as the matrix did.
        assertRootProbesReady(
            "freewebnovel", "https://freewebnovel.com",
            neighbourTransport(
                homeBody = read(root, "freewebnovel.com", "home.html"),
                rootUrl = "https://freewebnovel.com",
                bookBody = read(root, "freewebnovel.com", "book.html"),
                capturedBookPath = "/novel/the-innkeeper",
                bookPattern = Regex("https://freewebnovel\\.com/novel/[^/]+$"),
                chapterPattern = Regex("https://freewebnovel\\.com/novel/[^/]+/chapter-"),
                extraPages = mapOf(
                    "chapter" to read(root, "freewebnovel.com", "chapter.html"),
                    "https://freewebnovel.com/home" to
                        read(root, "freewebnovel.com", "home.html")
                )
            )
        )
    }

    @Test(timeout = 900_000)
    fun `neighbour novelsparadise root still walks in`() {
        val root = captureRoot()
        assumeTrue("captures not on disk", root != null)
        root!!
        // novelsparadise: books /np-light/series/<slug>/; the
        // captured home is the /np-light/ page, served at the
        // root as the matrix served it.
        assertRootProbesReady(
            "novelsparadise", "https://novelsparadise.site",
            neighbourTransport(
                homeBody = read(root, "novelsparadise.site", "home.html"),
                rootUrl = "https://novelsparadise.site",
                bookBody = read(root, "novelsparadise.site", "book.html"),
                capturedBookPath = "/np-light/series/silent-crown/",
                bookPattern = Regex("https://novelsparadise\\.site/np-light/series/[^/]+/?$"),
                chapterPattern = Regex("https://novelsparadise\\.site/np-light/series/[^/]+/chapter-"),
                extraPages = mapOf(
                    "chapter" to read(root, "novelsparadise.site", "chapter.html"),
                    "https://novelsparadise.site/np-light/" to
                        read(root, "novelsparadise.site", "home.html")
                )
            )
        )
    }

    // wtr-lab's root is NOT re-run here: its book drive needs
    // the site's chapters-API endpoint, which the matrix
    // harness models and this simplified transport does not
    // (the direct book probe fails in this transport for the
    // same reason — a harness-fidelity limit, not a walk-in
    // defect; the descent itself was traced working: home
    // listing null, catalogue links [/en/library,
    // /en/novel-list], list listing = 10 books, first = the
    // captured book). The matrix's own wtr-lab root entry,
    // re-run with the full suite, is its regression check.
}
