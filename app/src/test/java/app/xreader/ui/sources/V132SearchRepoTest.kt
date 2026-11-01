package app.xreader.ui.sources

import app.xreader.data.extension.ForeignSite
import app.xreader.data.extension.SourceRestore
import app.xreader.domain.reader.ParagraphSpacing
import app.xreader.domain.reader.ReaderConfig
import app.xreader.domain.reader.ReaderFontChoice
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.3.2 pins for scope D (streaming search), scope E (per-repo
 * list logic), item 84 (the restore selector) and items 86/87
 * (reader typography defaults).
 */
class V132SearchRepoTest {

    // ── Streaming search (scope D) ────────────────────────────

    @Test
    fun `results stream in as sources answer, slow ones dont block`() =
        runBlocking {
            val gate = CompletableDeferred<Unit>()
            val arrived = mutableListOf<String>()
            val progress = mutableListOf<Pair<Int, Int>>()
            val run = async {
                StreamingSearch.run(
                    items = listOf("slow", "fast", "mid"),
                    parallelism = 3,
                    search = { name ->
                        when (name) {
                            "slow" -> {
                                gate.await()
                                "slow-result"
                            }
                            "mid" -> {
                                delay(50)
                                "mid-result"
                            }
                            else -> "fast-result"
                        }
                    },
                    onResult = { name, _ -> arrived += name },
                    onProgress = { done, total ->
                        progress += done to total
                    }
                )
            }
            // Fast + mid land while slow is still gated.
            withTimeout(5_000) {
                while (arrived.size < 2) delay(10)
            }
            assertEquals(listOf("fast", "mid"), arrived)
            gate.complete(Unit)
            run.await()
            assertEquals(3, arrived.size)
            assertEquals(3 to 3, progress.last())
        }

    @Test
    fun `one failing source never fails the others`() {
        runBlocking {
        val outcomes = mutableMapOf<String, Boolean>()
        StreamingSearch.run(
            items = listOf("bad", "good"),
            parallelism = 2,
            search = { name ->
                if (name == "bad") throw IllegalStateException("boom")
                "ok"
            },
            onResult = { name, result ->
                outcomes[name] = result.isSuccess
            },
            onProgress = { _, _ -> }
        )
        assertEquals(mapOf("bad" to false, "good" to true), outcomes)
        }
    }

    @Test
    fun `cancelling the run stops the fan-out`() {
        runBlocking {
        val started = mutableListOf<String>()
        val finished = mutableListOf<String>()
        val gate = CompletableDeferred<Unit>()
        val job = launch {
            StreamingSearch.run(
                items = (1..6).map { "s$it" },
                parallelism = 2,
                search = { name ->
                    started += name
                    gate.await()
                    name
                },
                onResult = { name, _ -> finished += name },
                onProgress = { _, _ -> }
            )
        }
        delay(100)
        job.cancel()
        job.join()
        assertTrue("only the permitted two began", started.size <= 2)
        assertTrue("nothing reported after cancel", finished.isEmpty())
        gate.complete(Unit)
        }
    }

    // ── Per-repo filter (scope E) ─────────────────────────────

    private fun site(
        name: String,
        lang: String = "en",
        family: String? = null,
        baseUrl: String = "https://x.example"
    ) = ForeignSite(
        name = name, lang = lang, baseUrl = baseUrl, family = family
    )

    @Test
    fun `novels lead and the toggle hides the rest`() {
        val rows = listOf(
            RepoSourceFilter.Row("Zeta Manga", "en", "z.example", novel = false),
            RepoSourceFilter.Row("Alpha Novels", "en", "a.example", novel = true),
            RepoSourceFilter.Row("Beta Manga", "en", "b.example", novel = false),
            RepoSourceFilter.Row("Gamma Novel Hub", "ko", "g.example", novel = true)
        )
        val novelsOnly = RepoSourceFilter.filter(
            rows, query = "", language = null, novelsOnly = true
        )
        assertEquals(
            listOf("Alpha Novels", "Gamma Novel Hub"),
            novelsOnly.map { it.name }
        )
        val all = RepoSourceFilter.filter(
            rows, query = "", language = null, novelsOnly = false
        )
        assertEquals(
            listOf(
                "Alpha Novels", "Gamma Novel Hub",
                "Beta Manga", "Zeta Manga"
            ),
            all.map { it.name }
        )
        val korean = RepoSourceFilter.filter(
            rows, query = "", language = "ko", novelsOnly = false
        )
        assertEquals(listOf("Gamma Novel Hub"), korean.map { it.name })
        val searched = RepoSourceFilter.filter(
            rows, query = "z.example", language = null, novelsOnly = false
        )
        assertEquals(listOf("Zeta Manga"), searched.map { it.name })
    }

    @Test
    fun `novel heuristic reads names families and ecosystems`() {
        assertTrue(
            RepoSourceFilter.isNovelSite(
                site("Moonlight Novels"), "Mihon"
            )
        )
        assertFalse(
            "a bare 'Reader' name is not evidence of novels",
            RepoSourceFilter.isNovelSite(
                site("Moonlight Reader"), "Mihon"
            )
        )
        assertTrue(
            RepoSourceFilter.isNovelSite(
                site("Moonlight Reader", family = "madara"), "LNReader"
            )
        )
        assertTrue(
            RepoSourceFilter.isNovelSite(
                site("Anything At All"), "LNReader"
            )
        )
        assertFalse(
            RepoSourceFilter.isNovelSite(
                site("Moonlight Manga"), "Mihon"
            )
        )
    }

    // ── Item 84: the restore selector ─────────────────────────

    @Test
    fun `restore card lists exactly the off rows and shows once`() {
        val rows = listOf(
            SourceRestore.Row(1, "On Source", enabled = true),
            SourceRestore.Row(2, "Deliberate Off", enabled = false),
            SourceRestore.Row(3, "Breaker Off", enabled = false)
        )
        assertEquals(
            listOf("Deliberate Off", "Breaker Off"),
            SourceRestore.offRows(rows).map { it.name }
        )
        assertTrue(SourceRestore.shouldShowCard(false, rows))
        assertFalse(
            "answered once, never again",
            SourceRestore.shouldShowCard(true, rows)
        )
        assertFalse(
            "nothing off, nothing to offer",
            SourceRestore.shouldShowCard(
                false,
                listOf(SourceRestore.Row(1, "On", enabled = true))
            )
        )
    }

    // ── Typography (items 86/87) ──────────────────────────────

    @Test
    fun `fresh reader config defaults to literata and normal spacing`() {
        val config = ReaderConfig()
        assertEquals(ReaderFontChoice.LITERATA, config.fontChoice)
        assertEquals(
            ParagraphSpacing.NORMAL,
            ParagraphSpacing.of(config.paragraphSpacingDp)
        )
        // The full shelf exists.
        assertEquals(
            setOf(
                ReaderFontChoice.LITERATA, ReaderFontChoice.LORA,
                ReaderFontChoice.MERRIWEATHER,
                ReaderFontChoice.NOTO_SERIF,
                ReaderFontChoice.JAKARTA, ReaderFontChoice.INTER,
                ReaderFontChoice.USER
            ),
            ReaderFontChoice.entries.toSet()
        )
    }

    @Test
    fun `paragraph spacing stops are distinct and ordered`() {
        val dps = ParagraphSpacing.entries.map { it.dp }
        assertEquals(dps.size, dps.toSet().size)
        assertEquals(dps.sorted(), dps)
        assertTrue(ParagraphSpacing.NORMAL.dp > ParagraphSpacing.COMPACT.dp)
        assertTrue(ParagraphSpacing.RELAXED.dp > ParagraphSpacing.NORMAL.dp)
        assertEquals(
            ParagraphSpacing.COMPACT, ParagraphSpacing.of(3f)
        )
        assertEquals(
            ParagraphSpacing.RELAXED, ParagraphSpacing.of(20f)
        )
    }
}
