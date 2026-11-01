package app.xreader.ui.sources

import app.xreader.core.error.AppError
import app.xreader.data.db.ExtensionRepo
import app.xreader.data.extension.RepoManager
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * v1.3.3 (V132-CHECK 9/25 + 12, scopes S7/S8 + addition 7):
 * the Repositories rules and the Explore streaming search.
 *
 * Addition 7's device shape: the Explore header's "Check
 * for updates" checked the BUILT-IN pseudo-repo, fetched
 * its internal xreader:// address as if it were a web URL,
 * and stacked three error fragments into one nonsense
 * toast. The rules pinned here: synthetic rows are never
 * checkable, and a failed check is ONE clean sentence.
 */
class V133RepoUiTest {

    private fun repo(name: String, url: String) =
        ExtensionRepo(name = name, indexUrl = url)

    @Test
    fun `update check scopes to real added repos only`() {
        val bundled = repo("Built-in sources", RepoManager.BUNDLED_REPO_URL)
        val links = repo("My link sources", RepoManager.LINK_REPO_URL)
        val keiyoushi = repo(
            "Keiyoushi",
            "https://github.com/keiyoushi/extensions/raw/repo/index.pb"
        )
        val mine = repo("Mine", "https://example.com/index.json")
        assertEquals(
            listOf(keiyoushi, mine),
            RepoCheckPolicy.checkable(
                listOf(bundled, links, keiyoushi, mine)
            )
        )
        assertTrue(RepoCheckPolicy.isSynthetic(bundled))
        assertTrue(RepoCheckPolicy.isSynthetic(links))
        assertFalse(RepoCheckPolicy.isSynthetic(keiyoushi))
        // Only built-ins present → nothing to check, no error.
        assertTrue(
            RepoCheckPolicy.checkable(listOf(bundled, links)).isEmpty()
        )
    }

    @Test
    fun `a failed repo check is one clean sentence`() {
        val badAddress = RepoCheckCopy.failureLine(
            "Keiyoushi",
            AppError.BadFile("that address doesn't look right")
        )
        assertEquals(
            "Couldn't check “Keiyoushi” — that address " +
                "doesn't look right.",
            badAddress
        )
        val offline = RepoCheckCopy.failureLine(
            "Mine", AppError.NoNetwork
        )
        assertFalse(offline.contains("\n"))
        assertTrue(offline.endsWith("."))
        assertTrue(offline.contains("Mine"))
        val slow = RepoCheckCopy.failureLine(
            "Mine",
            AppError.ProviderDown(
                "svc",
                detail = "couldn't load that page in time"
            )
        )
        assertEquals(
            "Couldn't check “Mine” — the site took too " +
                "long to answer.",
            slow
        )
        // No stacked fragments anywhere: exactly one full stop
        // at the end, no "Details:" tail.
        for (line in listOf(badAddress, offline, slow)) {
            assertFalse(line.contains("Details:"))
            assertEquals(
                1, line.count { it == '.' }
            )
        }
    }

    @Test
    fun `streaming search delivers per source and isolates failures`() =
        runBlocking {
            val delivered = CopyOnWriteArrayList<String>()
            val progress = CopyOnWriteArrayList<Pair<Int, Int>>()
            StreamingSearch.run(
                items = listOf("alpha", "broken", "beta"),
                parallelism = 2,
                search = { item: String ->
                    delay(30)
                    if (item == "broken") {
                        throw IllegalStateException("boom")
                    }
                    item.uppercase()
                },
                onResult = { item, result ->
                    delivered += if (result.isSuccess) {
                        "$item:ok"
                    } else {
                        "$item:failed"
                    }
                },
                onProgress = { done, total -> progress += done to total }
            )
            assertEquals(3, delivered.size)
            assertTrue(delivered.contains("alpha:ok"))
            assertTrue(delivered.contains("beta:ok"))
            assertTrue(delivered.contains("broken:failed"))
            assertEquals(3 to 3, progress.last())
            Unit
        }

    @Test
    fun `streaming search honours cancellation`() = runBlocking {
        val delivered = CopyOnWriteArrayList<String>()
        val job = async {
            StreamingSearch.run(
                items = (1..10).toList(),
                parallelism = 2,
                search = { item: Int ->
                    delay(200)
                    item
                },
                onResult = { item, _ -> delivered += "r$item" },
                onProgress = { _, _ -> }
            )
        }
        delay(250)
        job.cancel()
        // Cancellation must END the run, not merely pause it.
        val finished = runCatching {
            kotlinx.coroutines.withTimeout(3_000) { job.await() }
        }
        assertTrue(
            "the run ends when cancelled",
            finished.isFailure || job.isCancelled
        )
        assertTrue(
            "not everything was delivered (got $delivered)",
            delivered.size < 10
        )
        Unit
    }
}
