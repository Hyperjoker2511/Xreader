package app.xreader.data.extension

import app.xreader.core.error.AppResult
import app.xreader.data.FakeTransport
import app.xreader.extensionapi.SourceBook
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v1.2.1 check round — the botitranslation defects, reproduced
 * from VERIFIED structure only:
 *
 * - The site is a Vite SPA (static home = an empty shell, 3.2 KB,
 *   captured 2026-10-31); everything below models the RENDERED
 *   DOM. The menu markup is the site's own menu template
 *   (menuControl bundle): `<li class="menu-item title-color">
 *   <a href="LINK"><i class="iconfont menu-icon …"></i><span>
 *   LABEL</span><i class="iconfont icon-gengduo1 more"></i></a>`
 *   under `<ul id="mainMenu">`, with the label/link pairs read
 *   from the site's own route table (base "/mobile": HOME
 *   /mobile/, EXPLORER /mobile/explore, RANK /mobile/rank,
 *   BOOK /mobile/book/, CHAPTER /mobile/chapter/, BOOK_SHELF
 *   /mobile/favorites, USER /mobile/account).
 * - The home page loads the swiper bundle (the static shell
 *   links swiper-bundle.min.css/js), so catalogue rails are
 *   swiper sections whose cards cluster per-section.
 * - The recommendation-card texts are the user's own
 *   screenshots (check log V120-CHECK): rating + count + title
 *   in one anchor ("8.5 779 The Genius Midfielder…").
 *
 * Defect 1: the nav cluster (5 menu items) beats each split
 * book rail (4) and becomes the "catalogue".
 * Defect 2: on a book page, a recommendation rail of OTHER
 * books (6 cards) beats the real chapter list (4 rows) and is
 * stored as the book's chapters.
 * Defect 4: a cold Live catalogue open must fetch the start
 * page ONCE (it fetched — on device, RENDERED — twice).
 */
class V121BotiFixTest {

    @get:Rule
    val tmp = TemporaryFolder()

    companion object {
        private const val HOST = "www.botitranslation.com"
        private const val BASE = "https://www.botitranslation.com"
        private const val HOME_URL = "$BASE/mobile/"
        private const val BOOK_URL = "$BASE/mobile/book/101"
        private const val CHAPTER_1_URL = "$BASE/mobile/chapter/3001"

        /** The site's own menu template + route table. */
        private val MENU = """
            <div class="menu-container"><div class="menu main-bg">
              <ul id="mainMenu" class="menu-list">
                <li class="menu-item title-color">
                  <a href="/mobile/">
                    <i class="iconfont menu-icon icon-bianzu"></i><span>Home</span>
                    <i class="iconfont icon-gengduo1 more"></i>
                  </a>
                </li>
                <li class="menu-item title-color">
                  <a href="/mobile/explore">
                    <i class="iconfont menu-icon icon-bianzu2"></i><span>Explore</span>
                    <i class="iconfont icon-gengduo1 more"></i>
                  </a>
                </li>
                <li class="menu-item title-color">
                  <a href="/mobile/rank">
                    <i class="iconfont menu-icon icon-bianzu4"></i><span>Rank</span>
                    <i class="iconfont icon-gengduo1 more"></i>
                  </a>
                </li>
                <li class="menu-item title-color">
                  <a href="/mobile/favorites">
                    <i class="iconfont menu-icon icon-favorites"></i><span>My Favorites</span>
                    <i class="iconfont icon-gengduo1 more"></i>
                  </a>
                </li>
                <li class="menu-item title-color">
                  <a href="/mobile/account">
                    <i class="iconfont menu-icon icon-bussiness-man"></i><span>Profile</span>
                    <i class="iconfont icon-gengduo1 more"></i>
                  </a>
                </li>
              </ul>
            </div></div>
        """.trimIndent()

        private fun bookCard(id: Int, title: String): String = """
            <div class="swiper-slide">
              <a href="/mobile/book/$id"><img src="/covers/$id.jpg" alt="$title"/></a>
            </div>
        """.trimIndent()

        /** The rendered home page: two swiper rails of four
         * book cards each, plus the site menu. */
        private val HOME = """
            <html><head><title>Home</title></head><body class="skin-default home">
              <header class="header main-bg">
                <i id="menuBtn" class="iconfont icon-liebiao menu-btn title-color"></i>
                <img class="main-logo" src="/mobile/images/mainlogo.png"/>
              </header>
              <div class="main-container">
                <section class="rail">
                  <h2>Popular this week</h2>
                  <div class="swiper"><div class="swiper-wrapper">
                    ${bookCard(101, "Sword Emperor of the Ninth Heaven")}
                    ${bookCard(102, "Reborn as the Villain's Butler")}
                    ${bookCard(103, "The Archmage's Second Life")}
                    ${bookCard(104, "My System Levels Up Alone")}
                  </div></div>
                </section>
                <section class="rail">
                  <h2>New releases</h2>
                  <div class="swiper"><div class="swiper-wrapper">
                    ${bookCard(105, "Chronicles of the Fallen Star")}
                    ${bookCard(106, "The Healer Who Retired")}
                    ${bookCard(107, "Dungeon Farmer Supreme")}
                    ${bookCard(108, "Last God of the Arena")}
                  </div></div>
                </section>
              </div>
              $MENU
            </body></html>
        """.trimIndent()

        private fun recCard(id: Int, text: String): String = """
            <a class="book-card" href="/mobile/book/$id"><span class="score">${
            text.substringBefore(' ')
            }</span> <span class="name">${
            text.substringAfter(' ')
            }</span></a>
        """.trimIndent()

        /** The rendered book page: a real chapter list of four
         * rows, and a six-card recommendation rail of OTHER
         * books (the screenshot shape). */
        private val BOOK_PAGE = """
            <html><head><title>Sword Emperor of the Ninth Heaven</title></head>
            <body class="skin-default">
              <div class="main-container">
                <h1>Sword Emperor of the Ninth Heaven</h1>
                <img class="cover" src="/covers/101.jpg" alt="Sword Emperor of the Ninth Heaven"/>
                <ul class="chapter-list">
                  <li><a href="/mobile/chapter/3001">Chapter 1</a></li>
                  <li><a href="/mobile/chapter/3002">Chapter 2</a></li>
                  <li><a href="/mobile/chapter/3003">Chapter 3</a></li>
                  <li><a href="/mobile/chapter/3004">Chapter 4</a></li>
                </ul>
                <section class="recommend">
                  <h2>Readers also enjoyed</h2>
                  <div class="rank-row">
                    ${recCard(201, "8.5 779 The Genius Midfielder")}
                    ${recCard(202, "8.2 5.1K Copy Skills With Affinity!")}
                    ${recCard(203, "7.0 360 Last on Earth")}
                    ${recCard(204, "5.5 352 Reloaded into the Other World")}
                    ${recCard(205, "Manhwa 116 The Earth's Chosen Savior")}
                    ${recCard(206, "9.0 1.2K The Roguish Guard in a Medieval World")}
                  </div>
                </section>
              </div>
              $MENU
            </body></html>
        """.trimIndent()

        private const val PROSE =
            "The rain had been falling on the harbour town for three " +
                "days when the letter arrived, sealed with black wax " +
                "and carrying no name but his own. Tomas read it twice " +
                "by the lamp, then fed it to the fire and watched the " +
                "words curl away, because some messages are safer " +
                "forgotten and some debts are only collected in the " +
                "dark, and he had been expecting this one for years."

        private val CHAPTER_PAGE = """
            <html><body><main><article>
              <h1>Chapter 1</h1>
              <p>$PROSE</p><p>$PROSE</p>
            </article></main></body></html>
        """.trimIndent()

        private val NAV_TITLES = setOf(
            "Home", "Explore", "Rank", "My Favorites", "Profile"
        )
    }

    /** The hrefs a listing analysis's items point at. */
    private fun analysisHrefs(
        doc: org.jsoup.nodes.Document,
        analysis: LinkInference.ListingAnalysis
    ): List<String> = doc.select(analysis.resultsSelector).mapNotNull { item ->
        val anchor = if (item.tagName() == "a") item else item.selectFirst("a[href]")
        anchor?.attr("abs:href")?.ifBlank { null }
    }

    // ── Defect 1: the catalogue is the site's navigation ────

    @Test
    fun `probe listing analysis never picks the nav cluster`() {
        val doc = Jsoup.parse(HOME, HOME_URL)
        // The probe's own call shape: the pasted/sample book is
        // the hint, and every /mobile/* anchor shares its first
        // segment — the nav qualifies as candidates unless the
        // learner vetoes chrome clusters.
        val analysis = LinkInference.analyseListingPage(doc, HOME_URL, BOOK_URL)
        assertNotNull("a real catalogue exists on this page", analysis)
        val hrefs = analysisHrefs(doc, analysis!!)
        assertTrue("expected book rows, got $hrefs", hrefs.isNotEmpty())
        assertTrue(
            "nav links in the catalogue: $hrefs",
            hrefs.all { it.startsWith("$BASE/mobile/book/") }
        )
    }

    @Test
    fun `books-only listing analysis serves the book rails`() {
        val doc = Jsoup.parse(HOME, HOME_URL)
        val analysis = LinkInference.analyseListingPage(
            doc, HOME_URL, null, booksOnly = true
        )
        assertNotNull(analysis)
        val hrefs = analysisHrefs(doc, analysis!!)
        assertTrue(
            "nav links in the catalogue: $hrefs",
            hrefs.all { it.startsWith("$BASE/mobile/book/") }
        )
        assertEquals("/mobile/book/[^/]+$", analysis.linkPattern)
    }

    // ── Defect 2: the chapter list is other books ───────────

    @Test
    fun `book page analysis reads chapters not recommendation cards`() {
        val doc = Jsoup.parse(BOOK_PAGE, BOOK_URL)
        val analysis = LinkInference.analyseBookPage(doc, BOOK_URL)
        assertNotNull("the real chapter list is on this page", analysis)
        val found = analysis!!
        assertEquals(4, found.chapterCount)
        assertEquals(CHAPTER_1_URL, found.firstChapterUrl)
        val rows = doc.select(found.chapterListSelector)
        val hrefs = rows.mapNotNull { item ->
            val anchor = if (item.tagName() == "a") item
            else item.selectFirst("a[href]")
            anchor?.attr("abs:href")
        }
        assertTrue(
            "book links stored as chapters: $hrefs",
            hrefs.all { it.startsWith("$BASE/mobile/chapter/") }
        )
    }

    @Test
    fun `live book open stores real chapters end to end`() {
        val transport = FakeTransport { request ->
            when (request.url) {
                BOOK_URL -> FakeTransport.ok(BOOK_PAGE)
                CHAPTER_1_URL, "$BASE/mobile/chapter/3002",
                "$BASE/mobile/chapter/3003", "$BASE/mobile/chapter/3004" ->
                    FakeTransport.ok(CHAPTER_PAGE)
                else -> AppResult.Ok(FakeTransport.response(404, "not found"))
            }
        }
        val context = testContext(
            transport = transport,
            filesDir = tmp.newFolder(),
            permissions = ExtensionPermissions(domains = listOf(HOST))
        )
        val source = LiveSource(
            LiveSourceSpec(
                id = "live.botitranslation",
                name = "botitranslation",
                baseUrl = BASE,
                startUrl = HOME_URL
            ),
            context
        )
        val (_, chapters) = source.detailsAndChapters(
            SourceBook(id = BOOK_URL, title = "Sword Emperor of the Ninth Heaven")
        )
        assertEquals(
            "chapters stored: ${chapters.map { it.id }}",
            4, chapters.size
        )
        assertTrue(
            "book links stored as chapters: ${chapters.map { it.id }}",
            chapters.all { it.id.startsWith("$BASE/mobile/chapter/") }
        )
        assertTrue(
            "nav or card titles stored as chapters: ${chapters.map { it.name }}",
            chapters.none { it.name in NAV_TITLES }
        )
    }

    // ── Defect 1: the probe judge's chrome backstop ─────────

    @Test
    fun `judge predicate flags a navigation title list`() {
        // The exact titles the user's Explore showed. A spec
        // reaching the drive by any other road than inference
        // must still be refused by the semantic judge.
        assertTrue(
            LinkProber.titlesAreChromeNav(
                listOf("Home", "Explore", "Rank", "My Favorites", "Profile")
            )
        )
    }

    @Test
    fun `judge predicate spares real catalogues`() {
        // A book honestly titled "Home" inside a real list is
        // not a menu: fewer than half chrome → spared. A
        // one-item list can never be judged a menu either.
        assertFalse(
            LinkProber.titlesAreChromeNav(
                listOf("Home", "Sword Saga", "Star Fall", "Ember City")
            )
        )
        assertFalse(LinkProber.titlesAreChromeNav(listOf("Home")))
        assertFalse(
            LinkProber.titlesAreChromeNav(
                listOf("Sword Saga", "Star Fall", "Ember City", "Low Tide")
            )
        )
    }

    // ── Defect 4: one fetch for a cold Live catalogue ───────

    @Test
    fun `cold live catalogue fetches the start page exactly once`() {
        val transport = FakeTransport { request ->
            when (request.url) {
                HOME_URL -> FakeTransport.ok(HOME)
                else -> AppResult.Ok(FakeTransport.response(404, "not found"))
            }
        }
        val context = testContext(
            transport = transport,
            filesDir = tmp.newFolder(),
            permissions = ExtensionPermissions(domains = listOf(HOST))
        )
        val source = LiveSource(
            LiveSourceSpec(
                id = "live.botitranslation",
                name = "botitranslation",
                baseUrl = BASE,
                startUrl = HOME_URL
            ),
            context
        )
        val page = source.popular(1)
        assertTrue("expected the book rails, got ${page.books.map { it.title }}",
            page.books.isNotEmpty())
        assertTrue(
            "nav titles in the catalogue: ${page.books.map { it.title }}",
            page.books.none { it.title in NAV_TITLES }
        )
        assertEquals(
            "start page fetches: ${transport.requests.map { it.url }}",
            1,
            transport.requests.count { it.url == HOME_URL }
        )
    }
}
