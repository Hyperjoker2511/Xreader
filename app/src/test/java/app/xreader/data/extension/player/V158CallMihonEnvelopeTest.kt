package app.xreader.data.extension.player

import app.xreader.data.extension.ContentRating
import app.xreader.extensionapi.SourceChapter
import app.xreader.extensionapi.SourceException
import eu.kanade.tachiyomi.source.model.MangasPage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * v1.5.8 (A1): the callMihon envelope split. Reader-class
 * calls (chapterContent) are no longer wrapped in
 * BRIDGE_CALL_TIMEOUT_MS — a latch-held getPageList outlives
 * a short bridge timeout and still completes when released.
 * Read-class calls (catalogue) KEEP the bounded envelope:
 * the fence half of the same proof.
 */
class V158CallMihonEnvelopeTest {

    private fun adapter(
        fake: V158FakeMihon,
        bridgeTimeoutMs: Long
    ) = MihonSourceAdapter(
        packageName = V158PlayerStacks.PKG,
        namespacedId = V158PlayerStacks.NAMESPACED,
        mihon = fake,
        contentRating = ContentRating.SAFE,
        bridgeTimeoutMs = bridgeTimeoutMs
    )

    @Test
    fun chapterContentOutlivesTheBridgeEnvelope() = runBlocking {
        val fake = V158FakeMihon()
        val gate = CompletableDeferred<Unit>()
        fake.pageListGate = gate
        val chapter = SourceChapter(id = "/ch/1", name = "Chapter 1")

        val call = async(Dispatchers.IO) {
            adapter(fake, bridgeTimeoutMs = 300).chapterContent(chapter)
        }
        // Three envelope lengths pass with the fetch parked.
        delay(1_000)
        assertTrue(
            "chapterContent was amputated by the bridge envelope",
            !call.isCompleted
        )
        gate.complete(Unit)
        val content = call.await()
        assertTrue(content.html.contains("cdn.test"))
    }

    @Test
    fun catalogueCallsKeepTheBoundedEnvelope() {
        val slow = object : V158FakeMihon() {
            override suspend fun getPopularManga(page: Int): MangasPage {
                delay(2_000)
                return super.getPopularManga(page)
            }
        }
        try {
            adapter(slow, bridgeTimeoutMs = 300).popular(1)
            fail("the catalogue call should have hit the envelope")
        } catch (e: SourceException) {
            assertTrue(
                "expected the too-long wording, got: ${e.message}",
                (e.message ?: "").contains("too long")
            )
        }
    }
}
