package org.sonorus.data.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.sonorus.data.ApiException
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

class DownloadRetryTest {

    @Test
    fun `a connection that went away is transport and keeps the song queued`() {
        assertTrue(DownloadRetry.isTransport(ConnectException("Failed to connect")))
        assertTrue(DownloadRetry.isTransport(UnknownHostException("musik.example.com")))
        assertTrue(DownloadRetry.isTransport(SocketTimeoutException("timeout")))
        assertTrue(DownloadRetry.isTransport(SSLException("connection reset")))
        assertTrue(DownloadRetry.isTransport(EOFException("unexpected end of stream")))
        // A file cut short resumes with a Range request, so it is the network too.
        assertTrue(DownloadRetry.isTransport(IOException("Die Datei kam unvollständig an (1 von 2 Bytes).")))
    }

    @Test
    fun `a server that says no fails the song`() {
        assertFalse(DownloadRetry.isTransport(DownloadRetry.httpFailure(404)))
        assertFalse(DownloadRetry.isTransport(DownloadRetry.httpFailure(500)))
        assertFalse(DownloadRetry.isTransport(ApiException("bad_login", "Benutzername oder Passwort falsch.")))
    }

    @Test
    fun `a proxy that has lost the server for a moment is retried`() {
        assertTrue(DownloadRetry.isTransport(DownloadRetry.httpFailure(502)))
        assertTrue(DownloadRetry.isTransport(DownloadRetry.httpFailure(503)))
        assertTrue(DownloadRetry.isTransport(DownloadRetry.httpFailure(504)))
    }

    @Test
    fun `a phone that cannot keep the file fails the song`() {
        assertFalse(DownloadRetry.isTransport(DownloadLocalFailure("Die Datei konnte nicht abgelegt werden.")))
        assertFalse(DownloadRetry.isTransport(IllegalStateException("bug")))
    }

    @Test
    fun `the pause grows and then holds at a minute`() {
        assertEquals(2_000, DownloadRetry.delayFor(1))
        assertEquals(5_000, DownloadRetry.delayFor(2))
        assertEquals(60_000, DownloadRetry.delayFor(6))
        assertEquals(60_000, DownloadRetry.delayFor(50))
        assertEquals(2_000, DownloadRetry.delayFor(0))
    }
}
