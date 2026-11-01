package app.xreader.data.extension

import app.xreader.core.error.AppError
import app.xreader.core.error.AppResult
import app.xreader.data.FakeTransport
import app.xreader.data.files.StorageMeter
import app.xreader.data.net.HttpTransport
import app.xreader.di.AppImageLoader
import app.xreader.domain.reader.ChapterDateText
import app.xreader.extensionapi.SourceBook
import app.xreader.ui.sources.SourceTwinArrangement
import java.io.File
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v1.3.1 delta pins (V120-CHECK items 44–59): the progressive
 * walk's page-1 start, the single-shot slice contract, the
 * chapter-shape gate, install-time dedupe planning, twin
 * arrangement, relative date bands, the cache measure/clean
 * pair, the auto-clean cap mapping, and the stall-timeout
 * error mapping.
 */
class V131DeltaTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ── Item 46/48: the progressive walk starts at page 1 ────

    @Test
    fun `progressive walk fetches the endpoint's first page first`() = runBlocking {
        val requests = mutableListOf<String>()
        val transport = FakeTransport { request ->
            requests += request.url
            when {
                request.url.contains("offset=0") -> FakeTransport.ok(
                    """{"items":[
                        {"number":1,"title":"Chapter 1","created_at":"2026-10-31T06:40:47.434361+00:00"},
                        {"number":2,"title":"Chapter 2","created_at":"2026-10-31T06:40:47.434361+00:00"},
                        {"number":3,"title":"Chapter 3","created_at":"2026-10-31T06:40:47.434361+00:00"}
                    ],"total":3}"""
                )
                request.url.contains("offset=") ->
                    FakeTransport.ok("""{"items":[],"total":3}""")
                else -> FakeTransport.ok("""{"slug":"test-book","title":"Test Book"}""")
            }
        }
        val context = testContext(
            transport, tmp.newFolder(),
            ExtensionPermissions(domains = listOf("chikari.moe"))
        )
        val source = DeclarativeSource(
            BundledSingles.chikari("chikari", "Chikari"), context
        )
        assertTrue(source.supportsProgressiveChapters())
        val chunk = source.chaptersFirst(
            SourceBook(
                id = "https://chikari.moe/api/novels/test-book",
                title = "Test Book"
            ),
            minCount = 2
        )
        // The regression: the walk used to start at page 2, so
        // the first 500 records never arrived (1,923 − 500 =
        // the 1,423 the user saw) and "Chapter 1" carried
        // chapter 501's text.
        assertEquals(3, chunk.chapters.size)
        assertEquals(listOf(1f, 2f, 3f), chunk.chapters.map { it.number })
        assertTrue(chunk.complete)
        assertTrue("first page must be fetched", requests.any { it.contains("offset=0") })
        assertFalse(
            "page 2 must not be fetched for a 3-row book",
            requests.any { it.contains("offset=500") }
        )
        // Item 53: the API's created_at rides the row.
        assertEquals(
            "2026-10-31T06:40:47.434361+00:00",
            chunk.chapters.first().dateText
        )
    }

    // ── Items 49/55: single-shot slice + the 3,229 pin ───────

    @Test
    fun `novelbuddy single-shot list slices first then completes at 3229`() = runBlocking {
        val total = 3229
        val chaptersJson = buildString {
            append("""{"data":{"chapters":[""")
            // The API serves newest first; the config reverses.
            for (n in total downTo 1) {
                if (n < total) append(',')
                append(
                    """{"name":"Chapter $n","url":"/shadow-slave/chapter-$n",""" +
                        """"number":$n,"updated_at":"2026-10-29T02:00:51.000Z"}"""
                )
            }
            append("]}}")
        }
        var chapterFetches = 0
        val transport = FakeTransport { request ->
            when {
                request.url.contains("/titles/") &&
                    request.url.endsWith("/chapters") -> {
                        chapterFetches++
                        FakeTransport.ok(chaptersJson)
                    }
                else -> FakeTransport.ok(
                    """<html><body><h1>Shadow Slave</h1>
                    <script>var x = {"initialManga":{"id":"nb123"}};</script>
                    </body></html>"""
                )
            }
        }
        val context = testContext(
            transport, tmp.newFolder(),
            ExtensionPermissions(
                domains = listOf("novelbuddy.me", "api.novelbuddy.me")
            )
        )
        val source = DeclarativeSource(
            BundledSingles.novelBuddy("novelbuddy", "NovelBuddy"), context
        )
        val book = SourceBook(
            id = "https://novelbuddy.me/shadow-slave", title = "Shadow Slave"
        )
        assertTrue(source.supportsProgressiveChapters())
        val first = source.chaptersFirst(book, minCount = 10)
        // Item 49: the open path persists only the first slice
        // (the pagination's page size — 50 here — not all 3,229).
        assertEquals(50, first.chapters.size)
        assertEquals(50f, first.chapters.last().number)
        assertFalse(first.complete)
        assertEquals(total, first.walk.pageSize)
        assertEquals(1f, first.chapters.first().number)
        assertEquals(
            "2026-10-29T02:00:51.000Z", first.chapters.first().dateText
        )
        val done = source.chaptersMore(book, first.chapters, total, first.walk)
        assertTrue(done.complete)
        assertEquals(total, done.chapters.size)
        assertEquals(1f, done.chapters.first().number)
        assertEquals(total.toFloat(), done.chapters.last().number)
        assertEquals(
            total, done.chapters.map { it.id }.toSet().size
        )
        // One fetch on open + one completing re-fetch, no more.
        assertEquals(2, chapterFetches)
    }

    // ── Item 50/55: the chapter-shape gate ───────────────────

    @Test
    fun `tag chips and CTA rows never become chapters`() = runBlocking {
        val bookHtml = """
            <html><body><h1>Some Book</h1>
            <div class="toc">
              <a href="/book/x/tag/magic">Magic</a>
              <a href="/genre/action">Action</a>
              <a href="/book/x/chapter-1">Start Reading</a>
              <a href="/book/x/chapter-1">Chapter 1</a>
              <a href="/book/x/chapter-2">Chapter 2</a>
            </div></body></html>
        """.trimIndent()
        val transport = FakeTransport { request ->
            if (request.url == "${FixtureSite.BASE}/book/x") {
                FakeTransport.ok(bookHtml)
            } else {
                FakeTransport.ok("<html><body></body></html>")
            }
        }
        val config = DeclarativeConfig(
            id = "junk-test",
            name = "Junk Test",
            baseUrl = FixtureSite.BASE,
            details = DetailsSpec(
                chapters = ChapterListSpec(
                    list = "div.toc a",
                    name = "@text",
                    link = "@href"
                )
            ),
            chapter = ChapterSpec(content = "div.content")
        )
        val source = DeclarativeSource(config, testContext(transport, tmp.newFolder()))
        val chapters = source.chapters(
            SourceBook(id = "${FixtureSite.BASE}/book/x", title = "Some Book")
        )
        assertEquals(2, chapters.size)
        assertEquals(listOf("Chapter 1", "Chapter 2"), chapters.map { it.name })
        assertTrue(chapters.none { it.id.contains("/tag/") || it.id.contains("/genre/") })
    }

    // ── Item 52: dedupe planning ─────────────────────────────

    private fun row(
        id: Long,
        pkg: String,
        domain: String?,
        installedAt: Long,
        origin: String = "Added by you"
    ) = SourceDedupePlanner.Row(
        extensionId = id,
        packageId = pkg,
        displayName = "NovelBuddy",
        origin = origin,
        canonicalDomain = domain,
        sourceIds = listOf("src-$id"),
        installedAt = installedAt
    )

    @Test
    fun `dedupe keeps the built-in and removes the older twin`() {
        val plan = SourceDedupePlanner.plan(
            listOf(
                row(1, "link.aaa", "novelbuddy.me", installedAt = 100),
                row(2, "bundled.novelbuddy", "novelbuddy.me", installedAt = 200)
            )
        )
        assertEquals(1, plan.size)
        assertEquals(1L, plan[0].removed.extensionId)
        assertEquals(2L, plan[0].survivor.extensionId)
    }

    @Test
    fun `dedupe without a built-in keeps the oldest row`() {
        val plan = SourceDedupePlanner.plan(
            listOf(
                row(5, "link.bbb", "example.com", installedAt = 500),
                row(3, "link.aaa", "example.com", installedAt = 300)
            )
        )
        assertEquals(1, plan.size)
        assertEquals(5L, plan[0].removed.extensionId)
        assertEquals(3L, plan[0].survivor.extensionId)
    }

    @Test
    fun `dedupe leaves single rows and ungroupable rows alone`() {
        assertTrue(
            SourceDedupePlanner.plan(
                listOf(row(1, "link.aaa", "one.com", installedAt = 1))
            ).isEmpty()
        )
        assertTrue(
            SourceDedupePlanner.plan(
                listOf(
                    row(1, "link.aaa", null, installedAt = 1),
                    row(2, "link.bbb", null, installedAt = 2)
                )
            ).isEmpty()
        )
        // Mirrors do NOT merge: different registrable domains
        // are different groups.
        assertTrue(
            SourceDedupePlanner.plan(
                listOf(
                    row(1, "link.aaa", "novelbuddy.me", installedAt = 1),
                    row(2, "link.bbb", "novelbuddy.com", installedAt = 2)
                )
            ).isEmpty()
        )
    }

    @Test
    fun `dedupe plan is idempotent`() {
        val rows = listOf(
            row(1, "link.aaa", "novelbuddy.me", installedAt = 100),
            row(2, "bundled.novelbuddy", "novelbuddy.me", installedAt = 200),
            row(3, "link.ccc", "novelbuddy.me", installedAt = 300)
        )
        val plan = SourceDedupePlanner.plan(rows)
        assertEquals(2, plan.size)
        val removedIds = plan.map { it.removed.extensionId }.toSet()
        assertTrue(
            SourceDedupePlanner.plan(rows.filter { it.extensionId !in removedIds })
                .isEmpty()
        )
    }

    @Test
    fun `canonical domain strips one www and case`() {
        assertEquals(
            "novelbuddy.me",
            SourceDedupePlanner.canonicalDomain("https://www.NovelBuddy.me/x")
        )
        assertEquals(
            "novelbuddy.me",
            SourceDedupePlanner.canonicalDomain("https://novelbuddy.me")
        )
        assertNull(SourceDedupePlanner.canonicalDomain(null))
        assertNull(SourceDedupePlanner.canonicalDomain("not a url"))
    }

    // ── Item 41: twin arrangement ────────────────────────────

    private data class TwinRow(val id: Long, val host: String?, val bundled: Boolean)

    @Test
    fun `bundled twin sorts ahead and user twin is tagged`() {
        val rows = listOf(
            TwinRow(1, "novelbuddy.me", bundled = false),
            TwinRow(2, "other.com", bundled = false),
            TwinRow(3, "novelbuddy.me", bundled = true)
        )
        val (ordered, twinIds) = SourceTwinArrangement.arrange(
            rows,
            idOf = { it.id },
            hostOf = { it.host },
            isBundled = { it.bundled }
        )
        assertEquals(listOf(3L, 1L, 2L), ordered.map { it.id })
        assertEquals(setOf(1L), twinIds)
    }

    @Test
    fun `arrangement without a bundled row changes nothing`() {
        val rows = listOf(
            TwinRow(1, "a.com", bundled = false),
            TwinRow(2, "a.com", bundled = false)
        )
        val (ordered, twinIds) = SourceTwinArrangement.arrange(
            rows,
            idOf = { it.id },
            hostOf = { it.host },
            isBundled = { it.bundled }
        )
        assertEquals(rows, ordered)
        assertTrue(twinIds.isEmpty())
    }

    // ── Item 53: relative date bands ─────────────────────────

    @Test
    fun `timestamp dates render in the user's relative words`() {
        val now = Instant.parse("2026-11-01T12:00:00Z").toEpochMilli()
        fun rel(raw: String) = ChapterDateText.relativeAtMillis(raw, now)
        assertEquals("just now", rel("2026-11-01T11:59:30Z"))
        assertEquals("5 min ago", rel("2026-11-01T11:55:00Z"))
        assertEquals("1 hour ago", rel("2026-11-01T11:00:00Z"))
        assertEquals("3 days ago", rel("2026-10-29T12:00:00Z"))
        assertEquals("2 weeks ago", rel("2026-10-18T12:00:00Z"))
        assertEquals("a month ago", rel("2026-09-17T12:00:00Z"))
        assertEquals("3 months ago", rel("2026-07-24T12:00:00Z"))
        assertEquals("1 Oct 2025", rel("2025-10-01T12:00:00Z"))
        // Chikari's own shape (offset + microseconds).
        assertEquals("1 day ago", rel("2026-10-31T06:40:47.434361+00:00"))
        // Epoch seconds are timestamps too.
        assertEquals("1 hour ago", rel("${now / 1000 - 3600}"))
        // Never fabricated.
        assertNull(rel("not a date"))
        assertNull(ChapterDateText.relativeAtMillis(null, now))
    }

    @Test
    fun `date-only values keep day words and never gain a time`() {
        val today = LocalDate.of(2026, 11, 1)
        assertEquals("Today", ChapterDateText.relative("2026-11-01", today))
        assertEquals("Yesterday", ChapterDateText.relative("31.10.2026", today))
        assertEquals("3 days ago", ChapterDateText.relative("29.10.2026", today))
    }

    // ── Items 57/58: cache measure, clean, cap ───────────────

    @Test
    fun `seeded cache measures then cleans to zero`() {
        val root = tmp.newFolder("cache")
        File(root, "a.bin").writeBytes(ByteArray(1000))
        val sub = File(root, "sub").apply { mkdirs() }
        File(sub, "b.bin").writeBytes(ByteArray(500))
        assertEquals(1500L, StorageMeter.dirSize(root))
        StorageMeter.clearContents(root)
        assertTrue(root.exists())
        assertEquals(0L, StorageMeter.dirSize(root))
        // A missing dir is a no-op, not a crash.
        StorageMeter.clearContents(File(root, "missing"))
    }

    @Test
    fun `auto-clean cap maps to the disk-cache ceiling`() {
        assertEquals(500L * 1024 * 1024, AppImageLoader.capBytesFor(500))
        assertEquals(200L * 1024 * 1024, AppImageLoader.capBytesFor(200))
        assertEquals(AppImageLoader.OFF_CEILING_BYTES, AppImageLoader.capBytesFor(0))
    }

    // ── Item 44: a stalled call maps to the plain timeout ────

    @Test
    fun `interrupted IO is the timeout family`() {
        val error = HttpTransport.ioError(java.io.InterruptedIOException("stalled"))
        assertTrue(error is AppError.ProviderDown)
        assertEquals(
            HttpTransport.TIMEOUT_DETAIL,
            (error as AppError.ProviderDown).detail
        )
    }
}
