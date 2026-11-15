package app.xreader.data.ai

import app.xreader.core.error.AppResult
import app.xreader.data.FakeTransport
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Patch 10, Addendum 1: the two bring-your-own-key engines.
 * Cloudflare's request/response shape and allowance mapping;
 * Pollinations' keyed-only law (anonymous is never called — it
 * would silently serve a different model, not FLUX).
 */
class KeyedImageClientsTest {

    private fun b64(text: String): String =
        Base64.getEncoder().encodeToString(text.toByteArray())

    // -- Cloudflare ------------------------------------------------------------

    @Test
    fun `cloudflare is configured only with both account and token`() = runBlocking {
        val transport = FakeTransport { FakeTransport.ok("{}") }
        assertFalse(
            CloudflareImageClient(transport, { null }, { null }).isConfigured()
        )
        assertFalse(
            CloudflareImageClient(transport, { "acct-9" }, { null }).isConfigured()
        )
        assertFalse(
            CloudflareImageClient(transport, { null }, { "tok" }).isConfigured()
        )
        assertTrue(
            CloudflareImageClient(transport, { "acct-9" }, { "tok" }).isConfigured()
        )
    }

    @Test
    fun `cloudflare unconfigured paint fails without any request`() = runBlocking {
        val transport = FakeTransport { FakeTransport.ok("{}") }
        val outcome = CloudflareImageClient(transport, { null }, { null })
            .paint("a lighthouse")
        assertTrue(outcome is PaintOutcome.Failed)
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun `cloudflare posts the prompt and decodes the base64 jpeg`() = runBlocking {
        val transport = FakeTransport {
            FakeTransport.ok(
                """{"success":true,"result":{"image":"${b64("JPEGDATA")}"},""" +
                    """"errors":[],"messages":[]}"""
            )
        }
        val client = CloudflareImageClient(transport, { "acct-9" }, { "tok-1" })
        val outcome = client.paint("a lighthouse")
        assertTrue(outcome is PaintOutcome.Painted)
        val image = (outcome as PaintOutcome.Painted).image
        assertEquals("JPEGDATA", image.bytes.toString(Charsets.UTF_8))
        assertEquals("image/jpeg", image.mimeType)
        val request = transport.requests.single()
        assertEquals(
            "https://api.cloudflare.com/client/v4/accounts/acct-9" +
                "/ai/run/@cf/black-forest-labs/flux-1-schnell",
            request.url
        )
        assertEquals("Bearer tok-1", request.headers["Authorization"])
        val body = Json.parseToJsonElement(request.body!!).jsonObject
        assertEquals("a lighthouse", body.getValue("prompt").jsonPrimitive.content)
        assertEquals("4", body.getValue("steps").jsonPrimitive.content)
    }

    @Test
    fun `cloudflare 429 is an exhausted allowance`() = runBlocking {
        val transport = FakeTransport {
            AppResult.Ok(FakeTransport.response(429, """{"success":false}"""))
        }
        val outcome = CloudflareImageClient(transport, { "acct-9" }, { "tok-1" })
            .paint("a lighthouse")
        assertTrue(outcome is PaintOutcome.Failed)
        assertTrue((outcome as PaintOutcome.Failed).allowanceExhausted)
    }

    @Test
    fun `cloudflare success false is a failure not a picture`() = runBlocking {
        val transport = FakeTransport {
            FakeTransport.ok(
                """{"success":false,"result":{},"errors":[{"code":1000}]}"""
            )
        }
        val outcome = CloudflareImageClient(transport, { "acct-9" }, { "tok-1" })
            .paint("a lighthouse")
        assertTrue(outcome is PaintOutcome.Failed)
    }

    // -- Pollinations ------------------------------------------------------------

    @Test
    fun `pollinations without a key never calls the service`() = runBlocking {
        val transport = FakeTransport { FakeTransport.ok("JPEGDATA") }
        val client = PollinationsImageClient(transport, keyProvider = { null })
        assertFalse(client.isConfigured())
        val outcome = client.paint("a lighthouse")
        assertTrue(outcome is PaintOutcome.Failed)
        assertTrue(
            "anonymous Pollinations must NEVER be called",
            transport.requests.isEmpty()
        )
    }

    @Test
    fun `pollinations wire puts the encoded prompt and flux in the url`() {
        val url = PollinationsWire.imageUrl("a red lighthouse & sea", 42)
        assertTrue(url.startsWith("https://gen.pollinations.ai/image/"))
        assertTrue(url.contains("a%20red%20lighthouse%20%26%20sea"))
        assertTrue(url.contains("model=flux"))
        assertTrue(url.contains("seed=42"))
    }

    @Test
    fun `pollinations with a key paints the returned bytes`() = runBlocking {
        val transport = FakeTransport {
            AppResult.Ok(
                FakeTransport.response(
                    200, "JPEGDATA",
                    headers = mapOf("Content-Type" to "image/jpeg")
                )
            )
        }
        val client = PollinationsImageClient(
            transport, keyProvider = { "pk-1" }, seedProvider = { 7 }
        )
        assertTrue(client.isConfigured())
        val outcome = client.paint("a lighthouse")
        assertTrue(outcome is PaintOutcome.Painted)
        val image = (outcome as PaintOutcome.Painted).image
        assertEquals("JPEGDATA", image.bytes.toString(Charsets.UTF_8))
        assertEquals("image/jpeg", image.mimeType)
        val request = transport.requests.single()
        assertEquals("Bearer pk-1", request.headers["Authorization"])
        assertTrue(request.url.contains("model=flux"))
    }

    @Test
    fun `pollinations 402 is an exhausted allowance`() = runBlocking {
        val transport = FakeTransport {
            AppResult.Ok(FakeTransport.response(402, "payment required"))
        }
        val outcome = PollinationsImageClient(transport, keyProvider = { "pk-1" })
            .paint("a lighthouse")
        assertTrue(outcome is PaintOutcome.Failed)
        assertTrue((outcome as PaintOutcome.Failed).allowanceExhausted)
    }
}
