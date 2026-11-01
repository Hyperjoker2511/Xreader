package app.xreader.data.net

import app.xreader.core.error.AppError
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.UnknownServiceException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.3.5 (P0, slice E2): a PLATFORM cleartext refusal that still
 * reaches the transport mapper — an UnknownServiceException
 * naming cleartext — is shown with the exact same error as the
 * app's own pre-flight refusal ([LocalHttpPolicy.refusalFor]),
 * never as an Unknown carrying the raw platform sentence. The
 * pre-existing mappings are pinned unchanged.
 */
class HttpTransportErrorMappingTest {

    @Test
    fun `platform cleartext refusal maps to the plain-words refusal`() {
        val error = HttpTransport.ioError(
            UnknownServiceException(
                "CLEARTEXT communication to 192.168.1.10 not " +
                    "permitted by network security policy"
            )
        )
        assertEquals(
            LocalHttpPolicy.refusalFor("http://203.0.113.7:11434"),
            error
        )
        assertTrue(error is AppError.BadFile)
        assertEquals(
            LocalHttpPolicy.REFUSAL_MESSAGE,
            (error as AppError.BadFile).reason
        )
    }

    @Test
    fun `unknown service without cleartext stays unknown`() {
        val error = HttpTransport.ioError(
            UnknownServiceException("unexpected end of stream")
        )
        assertTrue(error is AppError.Unknown)
    }

    @Test
    fun `existing mappings are unchanged`() {
        assertEquals(
            AppError.NoNetwork,
            HttpTransport.ioError(UnknownHostException("no such host"))
        )
        val timeout = HttpTransport.ioError(SocketTimeoutException("slow"))
        assertTrue(timeout is AppError.ProviderDown)
        assertEquals(
            HttpTransport.TIMEOUT_DETAIL,
            (timeout as AppError.ProviderDown).detail
        )
    }
}
