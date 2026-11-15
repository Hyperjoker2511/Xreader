package app.xreader.data.ai

import app.xreader.core.error.AppError
import app.xreader.core.error.AppResult
import app.xreader.data.FakeTransport
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Patch 10: the Horde wire contract, the poll loop, the plain-word
 * error mapping, and the censored-frame rules — all against
 * fixtures and a scripted transport. No live network: the one
 * live anonymous generation is an integration check, not a unit
 * test (the spec's own line).
 *
 * Two facts in here were proven against the REAL service on
 * 2026-11-15 and are pinned so they can't regress:
 *  - the seed is a JSON STRING (a numeric seed is rejected by
 *    Horde's payload validation);
 *  - Horde's censored frame is a black CARD with white text
 *    (mean luma ~23, variance in the thousands, ~90% of pixels
 *    near-black) — the detector's near-black-fraction rule is
 *    what catches it, not the plain-black rule.
 */
class HordeImageClientTest {

    private val submitOk = """{"id":"job-7","kudos":12}"""
    private val checkDone =
        """{"finished":1,"processing":0,"waiting":0,"done":true,""" +
            """"faulted":false,"queue_position":0,"wait_time":0,""" +
            """"is_possible":true,"kudos":12}"""
    private val statusUrl =
        """{"finished":1,"processing":0,"waiting":0,"done":true,""" +
            """"faulted":false,"generations":[{"img":"https://cdn.example/art.webp",""" +
            """"seed":"111","model":"stable_diffusion","censored":false}],"kudos":12}"""

    private fun b64(text: String): String =
        Base64.getEncoder().encodeToString(text.toByteArray())

    private fun statusB64(img: String, censored: Boolean = false) =
        """{"finished":1,"done":true,"faulted":false,"generations":[""" +
            """{"img":"$img","seed":"111","model":"stable_diffusion",""" +
            """"censored":$censored}],"kudos":12}"""

    /** A transport that plays the happy Horde flow; the check and
     * status legs can be overridden per test. */
    private fun flowTransport(
        submitCode: Int = 202,
        submitBody: String = submitOk,
        checkBody: (Int) -> String = { checkDone },
        statusBody: String = statusUrl,
        imageBody: String = "WEBPBYTES"
    ): FakeTransport {
        var checks = 0
        return FakeTransport { request ->
            when {
                request.url.endsWith("/generate/async") ->
                    AppResult.Ok(FakeTransport.response(submitCode, submitBody))
                request.url.contains("/generate/check/") -> {
                    checks++
                    FakeTransport.ok(checkBody(checks))
                }
                request.url.contains("/generate/status/") ->
                    FakeTransport.ok(statusBody)
                else -> AppResult.Ok(
                    FakeTransport.response(
                        200, imageBody,
                        headers = mapOf("Content-Type" to "image/webp")
                    )
                )
            }
        }
    }

    private fun client(
        transport: FakeTransport,
        seeds: List<String> = listOf("111", "222"),
        inspector: (ByteArray) -> Boolean = { false },
        nowMs: () -> Long = System::currentTimeMillis,
        delayMs: suspend (Long) -> Unit = {}
    ): HordeImageClient {
        val seedIter = seeds.iterator()
        return HordeImageClient(
            transport = transport,
            delayMs = delayMs,
            nowMs = nowMs,
            seedProvider = { if (seedIter.hasNext()) seedIter.next() else "999" },
            frameInspector = inspector
        )
    }

    private fun failed(outcome: PaintOutcome): AppError.NeutralVerdict {
        assertTrue("expected Failed, was $outcome", outcome is PaintOutcome.Failed)
        val error = (outcome as PaintOutcome.Failed).error
        assertTrue("expected NeutralVerdict, was $error", error is AppError.NeutralVerdict)
        return error as AppError.NeutralVerdict
    }

    // -- The submit contract ---------------------------------------------------

    @Test
    fun `submit body matches the contract exactly - seed as a string`() {
        val body = HordeProtocol.submitBody("a lighthouse on a cliff", "424242")
        val root = Json.parseToJsonElement(body).jsonObject
        assertEquals("a lighthouse on a cliff", root.getValue("prompt").jsonPrimitive.content)
        assertEquals(false, root.getValue("nsfw").jsonPrimitive.boolean)
        assertEquals(true, root.getValue("censor_nsfw").jsonPrimitive.boolean)
        assertEquals(
            listOf("stable_diffusion"),
            root.getValue("models").jsonArray.map { it.jsonPrimitive.content }
        )
        val params = root.getValue("params").jsonObject
        assertEquals(1, params.getValue("n").jsonPrimitive.int)
        assertEquals(512, params.getValue("width").jsonPrimitive.int)
        assertEquals(512, params.getValue("height").jsonPrimitive.int)
        assertEquals(20, params.getValue("steps").jsonPrimitive.int)
        assertEquals("k_euler_a", params.getValue("sampler_name").jsonPrimitive.content)
        assertEquals("7.0", params.getValue("cfg_scale").jsonPrimitive.content)
        val seed = params.getValue("seed").jsonPrimitive
        assertTrue(
            "the seed MUST be a JSON string (a number is rejected live)",
            seed.isString
        )
        assertEquals("424242", seed.content)
    }

    @Test
    fun `submit goes to the async endpoint with the anonymous key`() = runBlocking {
        val transport = flowTransport()
        val outcome = client(transport).paint("a lighthouse")
        assertTrue(outcome is PaintOutcome.Painted)
        val submit = transport.requests.first()
        assertEquals(
            "https://stablehorde.net/api/v2/generate/async", submit.url
        )
        assertEquals("POST", submit.method)
        assertEquals("0000000000", submit.headers["apikey"])
    }

    // -- Parsing -----------------------------------------------------------------

    @Test
    fun `check parses the done flag wait_time and faulted`() {
        val pending = HordeProtocol.parseCheck(
            """{"finished":0,"processing":1,"waiting":0,"done":false,""" +
                """"faulted":false,"queue_position":3,"wait_time":27,""" +
                """"is_possible":true}"""
        )!!
        assertFalse(pending.done)
        assertFalse(pending.faulted)
        assertEquals(27L, pending.waitTimeSeconds)
        val done = HordeProtocol.parseCheck(checkDone)!!
        assertTrue(done.done)
        val faulted = HordeProtocol.parseCheck(
            """{"finished":0,"done":false,"faulted":true,"wait_time":0}"""
        )!!
        assertTrue(faulted.faulted)
    }

    @Test
    fun `status parses the generation in url and base64 forms`() {
        val urlForm = HordeProtocol.parseGeneration(statusUrl)!!
        assertEquals("https://cdn.example/art.webp", urlForm.img)
        assertEquals("stable_diffusion", urlForm.model)
        assertFalse(urlForm.censored)
        val b64Form = HordeProtocol.parseGeneration(statusB64(b64("PNGBYTES")))!!
        assertEquals(b64("PNGBYTES"), b64Form.img)
        val flagged = HordeProtocol.parseGeneration(
            statusB64(b64("X"), censored = true)
        )!!
        assertTrue(flagged.censored)
    }

    // -- The flow ------------------------------------------------------------------

    @Test
    fun `paint downloads the picture when img is a url`() = runBlocking {
        val outcome = client(flowTransport()).paint("a lighthouse")
        assertTrue(outcome is PaintOutcome.Painted)
        val image = (outcome as PaintOutcome.Painted).image
        assertEquals("WEBPBYTES", image.bytes.toString(Charsets.UTF_8))
        assertEquals("image/webp", image.mimeType)
        assertTrue(image.modelUsed.contains("stable_diffusion"))
    }

    @Test
    fun `paint decodes the picture when img is base64`() = runBlocking {
        val transport = flowTransport(statusBody = statusB64(b64("PNGBYTES")))
        val outcome = client(transport).paint("a lighthouse")
        assertTrue(outcome is PaintOutcome.Painted)
        val image = (outcome as PaintOutcome.Painted).image
        assertEquals("PNGBYTES", image.bytes.toString(Charsets.UTF_8))
        // submit + check + status, and NO download leg.
        assertEquals(3, transport.requests.size)
    }

    @Test
    fun `poll honours wait_time between checks`() = runBlocking {
        val delays = mutableListOf<Long>()
        val transport = flowTransport(
            checkBody = { n ->
                if (n == 1) {
                    """{"finished":0,"processing":1,"waiting":0,"done":false,""" +
                        """"faulted":false,"wait_time":30,"is_possible":true}"""
                } else {
                    checkDone
                }
            }
        )
        val outcome = client(transport, delayMs = { delays += it }).paint("x")
        assertTrue(outcome is PaintOutcome.Painted)
        assertEquals(listOf(30_000L), delays)
    }

    // -- Error mapping --------------------------------------------------------------

    @Test
    fun `a faulted job is the busy sentence`() = runBlocking {
        val transport = flowTransport(
            checkBody = { """{"finished":0,"done":false,"faulted":true,"wait_time":0}""" }
        )
        val error = failed(client(transport).paint("x"))
        assertEquals(HordeImageClient.BUSY_WORDS, error.message)
    }

    @Test
    fun `submit 503 is busy and submit 500 is not answering`() = runBlocking {
        val busy = failed(
            client(flowTransport(submitCode = 503, submitBody = "{}")).paint("x")
        )
        assertEquals(HordeImageClient.BUSY_WORDS, busy.message)
        val down = failed(
            client(flowTransport(submitCode = 500, submitBody = "{}")).paint("x")
        )
        assertEquals(HordeImageClient.NOT_ANSWERING_WORDS, down.message)
    }

    @Test
    fun `a job that never finishes times out with the not-answering sentence`() =
        runBlocking {
            var now = 0L
            val transport = flowTransport(
                checkBody = {
                    """{"finished":0,"processing":1,"waiting":0,"done":false,""" +
                        """"faulted":false,"wait_time":55,"is_possible":true}"""
                }
            )
            val outcome = client(
                transport,
                nowMs = { now },
                delayMs = { ms -> now += ms }
            ).paint("x")
            assertEquals(
                HordeImageClient.NOT_ANSWERING_WORDS,
                failed(outcome).message
            )
            assertTrue(now > HordeImageClient.OVERALL_TIMEOUT_MS)
        }

    // -- The censored frame ---------------------------------------------------------

    @Test
    fun `detector flags a plain black frame`() {
        assertTrue(CensoredFrames.isCensored(IntArray(4096) { 0 }))
    }

    @Test
    fun `detector flags the censor card - mostly black with a white text block`() {
        // The measured real shape (2026-11-15): ~90% near-black,
        // ~8% near-white, mean ~23, variance in the thousands.
        // The plain-black rule alone would MISS this frame.
        val card = IntArray(10_000) { i -> if (i < 8_970) 4 else 245 }
        assertTrue(CensoredFrames.isCensored(card))
    }

    @Test
    fun `detector passes a normal varied picture`() {
        val photo = IntArray(4096) { i -> (i * 37) % 256 }
        assertFalse(CensoredFrames.isCensored(photo))
    }

    @Test
    fun `detector passes a dark night scene`() {
        // 60% genuinely dark pixels but real mid-tones elsewhere:
        // a dark picture is not a censored one.
        val night = IntArray(10_000) { i -> if (i < 6_000) 10 else 130 }
        assertFalse(CensoredFrames.isCensored(night))
    }

    @Test
    fun `a censored frame retries once with a fresh seed sent as a string`() =
        runBlocking {
            var submits = 0
            val transport = FakeTransport { request ->
                when {
                    request.url.endsWith("/generate/async") -> {
                        submits++
                        AppResult.Ok(
                            FakeTransport.response(202, """{"id":"job-$submits"}""")
                        )
                    }
                    request.url.contains("/generate/check/") -> FakeTransport.ok(checkDone)
                    request.url.contains("/generate/status/") -> FakeTransport.ok(
                        if (request.url.contains("job-1")) {
                            statusB64(b64("CENSOREDCARD"))
                        } else {
                            statusB64(b64("REALART"))
                        }
                    )
                    else -> AppResult.Err(AppError.NoNetwork)
                }
            }
            val outcome = client(
                transport,
                inspector = { bytes ->
                    bytes.contentEquals("CENSOREDCARD".toByteArray())
                }
            ).paint("a lighthouse")
            assertTrue(outcome is PaintOutcome.Painted)
            assertEquals(
                "REALART",
                (outcome as PaintOutcome.Painted).image.bytes.toString(Charsets.UTF_8)
            )
            assertEquals(2, submits)
            val bodies = transport.requests
                .filter { it.url.endsWith("/generate/async") }
                .map { Json.parseToJsonElement(it.body!!).jsonObject }
            val seeds = bodies.map {
                it.getValue("params").jsonObject.getValue("seed").jsonPrimitive
            }
            assertTrue(seeds.all { it.isString })
            assertEquals(listOf("111", "222"), seeds.map { it.content })
        }

    @Test
    fun `the service censored flag alone triggers the retry`() = runBlocking {
        var submits = 0
        val transport = FakeTransport { request ->
            when {
                request.url.endsWith("/generate/async") -> {
                    submits++
                    AppResult.Ok(
                        FakeTransport.response(202, """{"id":"job-$submits"}""")
                    )
                }
                request.url.contains("/generate/check/") -> FakeTransport.ok(checkDone)
                request.url.contains("/generate/status/") -> FakeTransport.ok(
                    if (request.url.contains("job-1")) {
                        statusB64(b64("SOMEART"), censored = true)
                    } else {
                        statusB64(b64("SOMEART"), censored = false)
                    }
                )
                else -> AppResult.Err(AppError.NoNetwork)
            }
        }
        // The pixel inspector says the frame is fine; the service
        // flag must still force the retry.
        val outcome = client(transport, inspector = { false }).paint("x")
        assertTrue(outcome is PaintOutcome.Painted)
        assertEquals(2, submits)
    }

    @Test
    fun `a second censored frame fails plainly and is never painted`() = runBlocking {
        var submits = 0
        val transport = FakeTransport { request ->
            when {
                request.url.endsWith("/generate/async") -> {
                    submits++
                    AppResult.Ok(
                        FakeTransport.response(202, """{"id":"job-$submits"}""")
                    )
                }
                request.url.contains("/generate/check/") -> FakeTransport.ok(checkDone)
                request.url.contains("/generate/status/") ->
                    FakeTransport.ok(statusB64(b64("CENSOREDCARD")))
                else -> AppResult.Err(AppError.NoNetwork)
            }
        }
        val outcome = client(
            transport,
            inspector = { true }
        ).paint("x")
        assertEquals(HordeImageClient.CENSORED_WORDS, failed(outcome).message)
        assertEquals(2, submits)
    }
}
