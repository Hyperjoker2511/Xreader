package app.xreader.data.net

import app.xreader.core.error.AppError
import java.io.IOException
import java.net.UnknownServiceException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.3.5 (P0): the plain-http rule — http is for the user's own
 * equipment only (loopback, RFC1918, link-local, .local names);
 * public http is refused with the one plain-words sentence, and
 * https is never this policy's business.
 */
class LocalHttpPolicyTest {

    private fun assertAllowed(url: String) {
        assertNull("expected allowed: $url", LocalHttpPolicy.refusalReason(url))
    }

    private fun assertRefused(url: String) {
        assertEquals(
            "expected refused: $url",
            LocalHttpPolicy.REFUSAL_MESSAGE,
            LocalHttpPolicy.refusalReason(url)
        )
    }

    @Test
    fun `loopback forms are allowed`() {
        assertAllowed("http://localhost:11434")
        assertAllowed("http://localhost:11434/api/chat")
        assertAllowed("http://LOCALHOST:11434")
        assertAllowed("http://127.0.0.1:11434")
        assertAllowed("http://127.1.2.3:8080/path")
        assertAllowed("http://[::1]:11434")
        assertAllowed("http://[0:0:0:0:0:0:0:1]:11434")
    }

    @Test
    fun `private and link-local addresses are allowed`() {
        assertAllowed("http://10.0.0.5:11434")
        assertAllowed("http://10.255.255.1")
        assertAllowed("http://172.16.0.1:11434")
        assertAllowed("http://172.31.255.254")
        assertAllowed("http://192.168.1.10:11434")
        assertAllowed("http://192.168.0.1/dav")
        assertAllowed("http://169.254.10.20")
        assertAllowed("http://[fe80::1]:11434")
        assertAllowed("http://[fd00::1]:11434")
        assertAllowed("http://[fd12:3456::1]:11434")
    }

    @Test
    fun `mdns names are allowed`() {
        assertAllowed("http://nas.local:8080/xreader")
        assertAllowed("http://my-pc.local")
        assertAllowed("http://raspberrypi.local:11434/api/tags")
    }

    @Test
    fun `https is always fine`() {
        assertAllowed("https://api.openai.com/v1")
        assertAllowed("https://dav.example.com/xreader")
        assertAllowed("https://8.8.8.8/dav")
    }

    @Test
    fun `public http is refused`() {
        assertRefused("http://api.example.com/v1")
        assertRefused("http://dav.example.com/xreader")
        assertRefused("http://8.8.8.8:11434")
        assertRefused("http://203.0.113.7:11434")
        assertRefused("http://user@203.0.113.7/dav")
        // Just outside the private ranges.
        assertRefused("http://172.15.0.1")
        assertRefused("http://172.32.0.1")
        assertRefused("http://192.167.1.1")
        assertRefused("http://11.0.0.1")
        assertRefused("http://169.253.1.1")
        assertRefused("http://128.0.0.1")
        // Names that merely contain the local words.
        assertRefused("http://localhost.evil.com")
        assertRefused("http://fake-localhost.com")
        assertRefused("http://example.local.evil.com")
        // A public IPv6 literal.
        assertRefused("http://[2001:4860:4860::8888]:11434")
        // An http URL whose host cannot even be read is not
        // provably local, so it is refused too.
        assertRefused("http://")
    }

    @Test
    fun `refusal is a BadFile carrying the plain-words message`() {
        val error = LocalHttpPolicy.refusalFor("http://api.example.com")
        assertTrue(error is AppError.BadFile)
        assertEquals(
            LocalHttpPolicy.REFUSAL_MESSAGE,
            (error as AppError.BadFile).reason
        )
        assertNull(LocalHttpPolicy.refusalFor("https://api.example.com"))
    }

    @Test
    fun `host rules read directly`() {
        assertTrue(LocalHttpPolicy.isLocalHost("localhost"))
        assertTrue(LocalHttpPolicy.isLocalHost("localhost."))
        assertTrue(LocalHttpPolicy.isLocalHost("[::1]"))
        assertTrue(LocalHttpPolicy.isLocalHost("192.168.1.1"))
        assertTrue(!LocalHttpPolicy.isLocalHost("192.168.1.256"))
        assertTrue(!LocalHttpPolicy.isLocalHost("192.168.1"))
        assertTrue(!LocalHttpPolicy.isLocalHost("example.com"))
        assertTrue(!LocalHttpPolicy.isLocalHost(""))
    }

    @Test
    fun `platform cleartext refusals are recognised in both stack phrasings`() {
        // OkHttp's shape.
        assertTrue(
            LocalHttpPolicy.isCleartextRefusal(
                UnknownServiceException(
                    "CLEARTEXT communication to 192.168.1.10 not " +
                        "permitted by network security policy"
                )
            )
        )
        // The HttpURLConnection stack's shape.
        assertTrue(
            LocalHttpPolicy.isCleartextRefusal(
                UnknownServiceException(
                    "Cleartext HTTP traffic to example.com not permitted"
                )
            )
        )
        // Wrapped as a cause by an intermediate stack.
        assertTrue(
            LocalHttpPolicy.isCleartextRefusal(
                IOException(
                    "call failed",
                    UnknownServiceException(
                        "CLEARTEXT communication to 10.0.0.5 not " +
                            "permitted by network security policy"
                    )
                )
            )
        )
    }

    @Test
    fun `ordinary network failures are not cleartext refusals`() {
        assertFalse(
            LocalHttpPolicy.isCleartextRefusal(
                UnknownServiceException("unexpected end of stream")
            )
        )
        assertFalse(
            LocalHttpPolicy.isCleartextRefusal(
                IOException("connection reset by peer")
            )
        )
        assertFalse(LocalHttpPolicy.isCleartextRefusal(IOException()))
    }
}
