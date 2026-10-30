package app.xreader.data.extension

import app.xreader.core.error.AppResult
import app.xreader.data.FakeTransport

/**
 * Fixtures for the v1.0.5 engine round (E4), trimmed from LIVE
 * captures taken 2026-10-29 (D18/D21 evidence):
 *
 * - [NovelightSite] mirrors novelight.net's real shapes: the
 *   /catalog/ grid cards (`.manga-grid-list a.item`, poster img
 *   with "<Title> poster" alt), the search form
 *   (`action="/catalog/"`, input `name="search"`), book pages
 *   whose chapters live in TWO `.chapters` blocks (a "last
 *   chapters" block overlapping the full list), lock-marked rows
 *   (`<span class="cost"><i class="fa fa-lock"></i></span>`),
 *   chapter pages whose text container holds only "Loading…",
 *   and — since v1.0.5 Stage 1.5 — the ajax endpoint the real
 *   text comes from: `/book/ajax/read-chapter/<id>` answers 403
 *   without the `X-Requested-With: XMLHttpRequest` header, gives
 *   full prose JSON for unlocked chapters, and an EMPTY content
 *   div for locked ones (all verified live 2026-10-29). Since
 *   v1.0.6 it also models the all-locked outlier book (every
 *   listed chapter paid) behind the v1.0.5.1 device trap.
 * - [LyingSearchSite] is a readable site whose "search" returns
 *   the whole catalogue for every query — the A5 filtering proof
 *   must catch it.
 * - [JsShellSite] is an empty JavaScript app shell (A9).
 * - [NovelFullSite] mirrors novelfull.com's verified shapes
 *   (cover img whose alt IS the title, `#list-chapter` list,
 *   `#chapter-content` text).
 */
object NovelightSite {
    const val HOST = "novelight.net"
    const val BASE = "https://novelight.net"
    const val BOOK_URL = "$BASE/book/lord-of-talents"
    const val BOOK2_URL = "$BASE/book/quiet-river"
    const val CHAPTER_101_URL = "$BASE/book/chapter/101"

    private fun chapterRow(id: Int, name: String, locked: Boolean): String {
        val cost = if (locked) {
            """<span class="cost"><i class="fa fa-lock"></i></span>"""
        } else {
            ""
        }
        return """
            <a href="/book/chapter/$id" class="chapter ">
              <div class="title">$id chapter - <span>$name</span></div>
              <div class="chapter-info">
                $cost
                <span class="author"><i class="fa fa-user"></i> sole_79</span>
                <span class="date">28.10.2026</span>
              </div>
            </a>
        """.trimIndent()
    }

    private val searchForm = """
        <form action="/catalog/">
          <div class="text-input">
            <input id="ajax-base-search" name="search" type="text" placeholder="Search..."/>
          </div>
        </form>
    """.trimIndent()

    /** The book page shape: newest chapters locked, the full
     * list in a second block overlapping the "last chapters"
     * block (E3). [lockAll] marks EVERY row paid — the
     * all-locked outlier shape (v1.0.6). */
    private fun bookPage(lockAll: Boolean): String = """
        <html><head>
          <title>Lord of Talents - Novelight</title>
          <meta name="description" content="A talent lord rises."/>
        </head><body>
        $searchForm
        <h1 class="clamp clamp-2">Lord of Talents</h1>
        <div class="swiper-container">
          <div class="swiper-slide"><span class="author">Carousel Writer</span></div>
        </div>
        <h2>Last chapters</h2>
        <div class="chapters">
          ${chapterRow(105, "Finale (5)", locked = true)}
          ${chapterRow(104, "Finale (4)", locked = true)}
          ${chapterRow(103, "Finale (3)", locked = lockAll)}
        </div>
        <h2>All chapters</h2>
        <div class="chapters">
          ${chapterRow(105, "Finale (5)", locked = true)}
          ${chapterRow(104, "Finale (4)", locked = true)}
          ${chapterRow(103, "Finale (3)", locked = lockAll)}
          ${chapterRow(102, "Finale (2)", locked = lockAll)}
          ${chapterRow(101, "Finale (1)", locked = lockAll)}
        </div>
        </body></html>
    """.trimIndent()

    val BOOK_PAGE = bookPage(lockAll = false)

    /** v1.0.6: the same book with EVERY chapter row
     * lock-marked — the "Urban Vagabond" outlier from the
     * v1.0.5.1 device draw-check: a ~300-chapter book whose
     * listed (newest) chapters are all paid, so no drive can
     * sample an unlocked chapter from it. */
    val BOOK_PAGE_ALL_LOCKED = bookPage(lockAll = true)

    /** The same book page WITH the long prose synopsis real
     * novelight pages carry (v1.0.5.1). The trimmed capture
     * above kept only the short meta description — which is
     * exactly why the v1.0.5 gate stayed green in CI while the
     * device failed: this ≥300-char low-link prose block is
     * what trips the chapter-page analysis on the live site. */
    val BOOK_PAGE_LONG_SYNOPSIS: String = BOOK_PAGE.replace(
        "<h2>Last chapters</h2>",
        """
        <div class="synopsis">
          <p>The age of talents dawned without trumpets or portents. When the
          first lord of Aldermere woke to find a quiet voice naming the gifts
          of everyone he met, he did the sensible thing and told no one — in
          the river town below his hill, a talent seen was a talent taxed, and
          a talent taxed was a talent taken.</p>
          <p>So he counted other men's gifts the way a miser counts coins: in
          the dark, by touch, smiling at nothing. What he could not count was
          the price. Every gift he read left a thread tied to his own name,
          and threads, pulled patiently by patient enemies, have a way of
          becoming ropes.</p>
        </div>
        <h2>Last chapters</h2>
        """.trimIndent()
    )

    /** The chapter page as the static engine sees it: the text
     * container holds only the loading placeholder (the real
     * text arrives by ajax — see KnownSites' family note). */
    val CHAPTER_PAGE = """
        <html><head><title>Chapter 101 - Lord of Talents</title></head><body>
        <h1>Chapter 101</h1>
        <div class="chapter-text__place">
          <div class="nothing">Loading...</div>
        </div>
        </body></html>
    """.trimIndent()

    /** Prose paragraphs served by the ajax endpoint for the
     * unlocked chapters (101–103). Long enough to clear the
     * test-drive's readable-text bar. */
    val CHAPTER_PROSE: String = (1..4).joinToString("\n") {
        "The lord of talents counted his quiet coins in part $it, " +
            "and the river town below went on not noticing him at all."
    }

    private val CHAPTER_PROSE_HTML: String =
        CHAPTER_PROSE.split("\n").joinToString("") { "<p>$it</p>" }

    /** The endpoint's JSON shape, exactly as verified live: a
     * rotating random class echoed on the content div; locked
     * chapters get the same div with NOTHING inside it. */
    fun ajaxChapterJson(prose: Boolean): String {
        val inner = if (prose) CHAPTER_PROSE_HTML else ""
        return """{"class":"x9q2rand","content":"<div class=\"chapter-text x9q2rand\">$inner</div>"}"""
    }

    const val AJAX_PREFIX = "$BASE/book/ajax/read-chapter/"

    private fun card(url: String, title: String, poster: String): String = """
        <a href="$url" class="item">
          <div class="poster image image-cover lazy-load-bg">
            <img src="$poster" alt="$title poster" loading="lazy" class="lazy-image"/>
          </div>
          <div class="title clamp clamp-2">$title</div>
        </a>
    """.trimIndent()

    val CATALOGUE_PAGE = """
        <html><head><title>Catalogue - Novelight</title></head><body>
        $searchForm
        <div class="manga-grid-list">
          ${card("/book/lord-of-talents", "Lord of Talents", "/media/book/poster/lord.webp")}
          ${card("/book/quiet-river", "Quiet River", "/media/book/poster/river.webp")}
        </div>
        </body></html>
    """.trimIndent()

    /** A real filtered search: exactly one result (A5's
     * single-result judgement exists because of pages like
     * this). */
    val SEARCH_PAGE = """
        <html><head><title>Search - Novelight</title></head><body>
        <div class="manga-grid-list">
          ${card("/book/lord-of-talents", "Lord of Talents", "/media/book/poster/lord.webp")}
        </div>
        </body></html>
    """.trimIndent()

    val HOME_PAGE = """
        <html><head><title>Novelight</title></head><body>
        $searchForm
        <p>Welcome to the fixture.</p>
        </body></html>
    """.trimIndent()

    fun transport(): FakeTransport = transportServing(BOOK_PAGE)

    /** The same site, but its book pages carry the long prose
     * synopsis (v1.0.5.1): catalogue, search, chapter pages and
     * the ajax text endpoint are all identical. */
    fun transportLongSynopsis(): FakeTransport =
        transportServing(BOOK_PAGE_LONG_SYNOPSIS)

    /** v1.0.6: every book page is the all-locked one, and the
     * ajax endpoint answers every chapter id with the empty
     * (locked) content div. */
    fun transportAllLocked(): FakeTransport =
        transportServing(BOOK_PAGE_ALL_LOCKED, ajaxAllLocked = true)

    /** v1.0.6: the catalogue's FIRST book is all-locked, the
     * second is readable — the sample picker's proving
     * ground. */
    fun transportPicker(): FakeTransport =
        transportServing(BOOK_PAGE_ALL_LOCKED, book2Page = BOOK_PAGE)

    private fun transportServing(
        bookPage: String,
        book2Page: String = bookPage,
        ajaxAllLocked: Boolean = false
    ): FakeTransport {
        val pages = mapOf(
            BOOK_URL to bookPage,
            BOOK2_URL to book2Page,
            CHAPTER_101_URL to CHAPTER_PAGE,
            "$BASE/book/chapter/102" to CHAPTER_PAGE,
            "$BASE/book/chapter/103" to CHAPTER_PAGE,
            "$BASE/book/chapter/104" to CHAPTER_PAGE,
            "$BASE/book/chapter/105" to CHAPTER_PAGE,
            "$BASE/catalog/" to CATALOGUE_PAGE,
            // v1.0.6.2 (F2): the lane's catalogue/search URLs now
            // carry {page} — page 1 is what the engine asks for.
            "$BASE/catalog/?page=1" to CATALOGUE_PAGE,
            "$BASE/catalog/?search=Talents" to SEARCH_PAGE,
            "$BASE/catalog/?search=Talents&page=1" to SEARCH_PAGE,
            // A nonsense word finds NOTHING on the real site.
            "$BASE/catalog/?search=zxqwvkj" to """
                <html><head><title>Search - Novelight</title></head><body>
                <div class="manga-grid-list"></div>
                </body></html>
            """.trimIndent(),
            "$BASE/catalog/?search=zxqwvkj&page=1" to """
                <html><head><title>Search - Novelight</title></head><body>
                <div class="manga-grid-list"></div>
                </body></html>
            """.trimIndent(),
            BASE to HOME_PAGE,
            "$BASE/" to HOME_PAGE
        )
        return FakeTransport { request ->
            // The ajax text endpoint (Stage 1.5): refuses without
            // the XMLHttpRequest header (403, as verified live),
            // prose for the unlocked chapters, an empty content
            // div for the locked newest ones.
            if (request.url.startsWith(AJAX_PREFIX)) {
                val ajaxHeader = request.headers.entries.any {
                    it.key.equals("X-Requested-With", ignoreCase = true) &&
                        it.value.equals("XMLHttpRequest", ignoreCase = true)
                }
                return@FakeTransport when {
                    !ajaxHeader ->
                        AppResult.Ok(FakeTransport.response(403, "forbidden"))
                    ajaxAllLocked ->
                        FakeTransport.ok(ajaxChapterJson(prose = false))
                    request.url.removePrefix(AJAX_PREFIX) in
                        setOf("101", "102", "103") ->
                        FakeTransport.ok(ajaxChapterJson(prose = true))
                    request.url.removePrefix(AJAX_PREFIX) in
                        setOf("104", "105") ->
                        FakeTransport.ok(ajaxChapterJson(prose = false))
                    else ->
                        AppResult.Ok(FakeTransport.response(404, "not found"))
                }
            }
            val body = pages[request.url]
            if (body == null) {
                AppResult.Ok(FakeTransport.response(404, "not found"))
            } else {
                FakeTransport.ok(body)
            }
        }
    }
}

/** A readable site whose search endpoint ignores the query and
 * returns the whole catalogue — A5 must refuse to call that
 * "working search". */
object LyingSearchSite {
    const val HOST = "lying.example"
    const val BASE = "https://lying.example"
    const val BOOK_URL = "$BASE/book/quiet-star"

    val chapterText = (1..5).joinToString("\n") {
        "The quiet star watched over the harbour in part $it, and nobody below noticed at all."
    }

    private fun chapterPage(n: Int): String = """
        <html><head><title>Chapter $n</title></head><body>
        <h1>Chapter $n</h1>
        <div id="chapter-content">
          ${chapterText.split("\n").joinToString("\n") { "<p>$it</p>" }}
        </div>
        </body></html>
    """.trimIndent()

    val BOOK_PAGE = """
        <html><head>
          <title>Quiet Star - LyingBooks</title>
          <meta property="og:title" content="Quiet Star"/>
          <meta property="og:image" content="https://lying.example/covers/quiet.jpg"/>
        </head><body>
        <form action="/catalogue" method="get"><input type="text" name="q"/></form>
        <h1>Quiet Star</h1>
        <ul id="chapter-list">
          <li><a href="/book/quiet-star/chapter-3">Chapter 3</a></li>
          <li><a href="/book/quiet-star/chapter-2">Chapter 2</a></li>
          <li><a href="/book/quiet-star/chapter-1">Chapter 1</a></li>
        </ul>
        </body></html>
    """.trimIndent()

    /** The same three books, whatever was asked for. */
    val CATALOGUE_PAGE = """
        <html><body><div class="grid">
          <a href="/book/quiet-star"><img src="/covers/quiet.jpg"/>Quiet Star</a>
          <a href="/book/loud-moon"><img src="/covers/moon.jpg"/>Loud Moon</a>
          <a href="/book/dim-sun"><img src="/covers/sun.jpg"/>Dim Sun</a>
        </div></body></html>
    """.trimIndent()

    fun transport(): FakeTransport = FakeTransport { request ->
        val url = request.url
        val body = when {
            url == BOOK_URL -> BOOK_PAGE
            url.startsWith("$BASE/book/quiet-star/chapter-") ->
                chapterPage(url.substringAfterLast("chapter-").toInt())
            url.startsWith("$BASE/catalogue") -> CATALOGUE_PAGE
            url == BASE || url == "$BASE/" -> CATALOGUE_PAGE
            else -> null
        }
        if (body == null) {
            AppResult.Ok(FakeTransport.response(404, "not found"))
        } else {
            FakeTransport.ok(body)
        }
    }
}

/** An empty JavaScript app shell (A9): a mount div, module
 * scripts, a noscript note — and no readable content at all. */
object JsShellSite {
    const val HOST = "shell.example"
    const val BASE = "https://shell.example"

    val SHELL_PAGE = """
        <html><head>
          <title>ShellNovel</title>
          <script type="module" src="/assets/app-1a2b.js"></script>
          <script type="module" src="/assets/vendor-3c4d.js"></script>
        </head><body>
        <div id="root"></div>
        <noscript>You need to enable JavaScript to run this app.</noscript>
        </body></html>
    """.trimIndent()

    fun transport(): FakeTransport = FakeTransport { request ->
        if (request.url.startsWith(BASE)) {
            FakeTransport.ok(SHELL_PAGE)
        } else {
            AppResult.Ok(FakeTransport.response(404, "not found"))
        }
    }
}

/**
 * chikari.moe (v1.0.6, W6.3/D27): a JavaScript-app site that DOES
 * carry prose novels. [SHELL_PAGE] is the shape of the REAL static
 * capture taken 2026-10-29 (23 KB SvelteKit shell, trimmed to its
 * load-bearing bones): an `#app-root` mount, an inline bootstrap
 * that dynamic-imports the app, a boot splash whose ring div is
 * empty, ~50 characters of visible text and zero anchors — the
 * shipped A9 check flags it via the empty-div mount signal
 * (verified against the capture). The static transport serves the
 * shell for EVERY route, like the real SPA.
 *
 * The RENDERED_* pages are the DOM the site's own scripts build.
 * v1.0.8.1: these now model the REAL rendered shapes verified by
 * live DOM inspection 2026-10-30 (the replacement this comment
 * always promised): cards are bare <a href="/novels/<slug>">
 * anchors whose cover <img alt> is the title; the book page
 * states its total as plain text ("7 chapters" here — the real
 * book says 3229) and renders only a slice of chapter rows,
 * bare anchors /novels/<slug>/<n> newest-first whose text
 * carries a relative date and a nested "Mark read" button;
 * chapter prose lives in an <article>. Class names could not
 * be verified by the inspection, so the fixtures — like the
 * lane — are class-free.
 */
object ChikariSite {
    const val HOST = "chikari.moe"
    const val BASE = "https://chikari.moe"
    const val BOOK_URL = "$BASE/novels/shadow-slave"
    const val CHAPTER_1_URL = "$BASE/novels/shadow-slave/1"

    val SHELL_PAGE = """
        <!doctype html>
        <html lang="en" class="dark"><head>
          <meta charset="utf-8" />
          <title>chikari.moe</title>
          <meta name="description" content="Read manga, manhwa, and manhua online with a fast, clean, full-screen reader." />
        </head>
        <body data-sveltekit-preload-data="hover">
          <div id="app-root" style="display: contents">
            <script>
              { __sveltekit_z1krx7 = { base: "" };
                const element = document.currentScript.parentElement;
                Promise.all([
                  import("/_app/immutable/entry/start.CVcPCmEK.js"),
                  import("/_app/immutable/entry/app.NmZ9v-kO.js")
                ]).then(([kit, app]) => { kit.start(app, element); }); }
            </script>
          </div>
          <div id="boot-splash">
            <div id="boot-splash-ring"></div>
            <div id="boot-splash-failed"><div>chikari didn't finish loading.</div></div>
          </div>
          <noscript><link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Lora&amp;display=swap" /></noscript>
        </body></html>
    """.trimIndent()

    val chapterText = (1..5).joinToString("\n") {
        "Nephis stood at the edge of the shattered shore in part $it, watching the tide of darkness roll in."
    }

    private fun chapterPage(n: Int): String = """
        <html><head><title>Chapter $n — Shadow Slave</title></head><body>
        <main>
          <a href="/novels/shadow-slave">Shadow Slave</a>
          <span>Chapter $n - $n: Nightmare Begins</span>
          <article>
          ${chapterText.split("\n").joinToString("\n") { "<p>$it</p>" }}
          </article>
        </main>
        </body></html>
    """.trimIndent()

    /** The book page states 7 chapters but renders only three
     * rows (newest slice + the oldest) — the gap the lane's
     * pattern completion closes. */
    val RENDERED_BOOK_PAGE = """
        <html><head>
          <title>Shadow Slave · chikari.moe</title>
          <meta property="og:title" content="Shadow Slave"/>
          <meta name="description" content="Growing up in poverty, Sunny never expected anything good from life."/>
        </head><body>
        <main>
          <h1>Shadow Slave</h1>
          <div>9.5 rating (674) 9.7M views 7 chapters</div>
          <div>TYPE Novel STATUS Releasing AUTHOR <a href="/authors/guiltythree">GuiltyThree</a></div>
          <img src="https://cdn.chikari.moe/novels/53/cover.webp" alt="Shadow Slave"/>
          <h2>Chapters</h2>
          <a href="/novels/shadow-slave/7">Ch. 7 Seventh Night 1 day ago <button>Mark read</button></a>
          <a href="/novels/shadow-slave/6">Ch. 6 Sixth Night 2 days ago <button>Mark read</button></a>
          <button>Show all chapters</button>
          <a href="/novels/shadow-slave/1">Ch. 1 Nightmare Begins 3 years ago <button>Mark read</button></a>
          <div>
            <a href="/novels/lord-of-ash"><img src="/covers/ash.jpg" alt="Lord of Ash"/>Lord of Ash</a>
          </div>
        </main>
        </body></html>
    """.trimIndent()

    val RENDERED_HOME_PAGE = """
        <html><body><main>
          <a href="/novels/shadow-slave"><img src="https://cdn.chikari.moe/novels/53/cover.webp" alt="Shadow Slave"/><button>Bookmark</button>Novel 9.5 9.7M Shadow Slave</a>
          <a href="/novels/lord-of-ash"><img src="/covers/ash.jpg" alt="Lord of Ash"/><button>Bookmark</button>Novel 8.1 1.2M Lord of Ash</a>
          <a href="/novels/dim-harbour"><img src="/covers/harbour.jpg" alt="Dim Harbour"/><button>Bookmark</button>Novel 7.4 300K Dim Harbour</a>
        </main></body></html>
    """.trimIndent()

    /** The rendered pages the browser view hands back, by URL. */
    fun renderedPages(): Map<String, String> = mapOf(
        BOOK_URL to RENDERED_BOOK_PAGE,
        BASE to RENDERED_HOME_PAGE,
        "$BASE/" to RENDERED_HOME_PAGE
    )

    /** Fallback for the URL families (chapters, search results). */
    fun renderedFallback(url: String): String? = when {
        url.startsWith("$BASE/novels/shadow-slave/") ->
            url.substringAfterLast('/').toIntOrNull()
                ?.let { chapterPage(it) }
        url.startsWith("$BASE/search") -> RENDERED_HOME_PAGE
        else -> null
    }

    /** Static view of the site: the shell, for every route. */
    fun transport(): FakeTransport = FakeTransport { request ->
        if (request.url.startsWith(BASE)) {
            FakeTransport.ok(SHELL_PAGE)
        } else {
            AppResult.Ok(FakeTransport.response(404, "not found"))
        }
    }
}

/** novelfull.com's verified shapes (live capture, 2026-10-29):
 * the cover is an <img> whose alt IS the title; chapters live
 * under `#list-chapter`; chapter text in `#chapter-content`. */
object NovelFullSite {
    const val HOST = "novelfull.com"
    const val BASE = "https://novelfull.com"
    const val BOOK_URL = "$BASE/martial-peak"

    val chapterText = (1..5).joinToString("\n") {
        "The martial peak stood above the clouds in part $it, and every disciple looked up."
    }

    val BOOK_PAGE = """
        <html><head>
          <title>Martial Peak - NovelFull</title>
          <meta name="description" content="The journey to the peak."/>
        </head><body>
        <form action="/search" method="get"><input type="search" name="keyword"/></form>
        <div class="book">
          <img src="/uploads/webp/novel/martial-peak.webp" alt="Martial Peak"/>
        </div>
        <h3 class="title">Martial Peak</h3>
        <div id="list-chapter">
          <ul class="list-chapter">
            <li><a href="/martial-peak/chapter-1">Chapter 1</a></li>
            <li><a href="/martial-peak/chapter-2">Chapter 2</a></li>
            <li><a href="/martial-peak/chapter-3">Chapter 3</a></li>
          </ul>
        </div>
        </body></html>
    """.trimIndent()

    val CHAPTER_PAGE = """
        <html><head><title>Chapter 1 - Martial Peak</title></head><body>
        <div id="chapter-content">
          ${chapterText.split("\n").joinToString("\n") { "<p>$it</p>" }}
        </div>
        </body></html>
    """.trimIndent()

    fun transport(): FakeTransport {
        val pages = mapOf(
            BOOK_URL to BOOK_PAGE,
            "$BASE/martial-peak/chapter-1" to CHAPTER_PAGE,
            "$BASE/martial-peak/chapter-2" to CHAPTER_PAGE,
            "$BASE/martial-peak/chapter-3" to CHAPTER_PAGE
        )
        return FakeTransport { request ->
            val body = pages[request.url]
            if (body == null) {
                AppResult.Ok(FakeTransport.response(404, "not found"))
            } else {
                FakeTransport.ok(body)
            }
        }
    }
}
