package org.sonorus.data

import androidx.media3.common.PlaybackException
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.sonorus.data.download.DownloadCutShort
import org.sonorus.data.download.DownloadRetry
import java.io.IOException
import java.net.ConnectException
import java.net.UnknownHostException

/** A failure has to say what happened, never just a status code or the network stack's English. */
class ErrorsTest {

    @Test
    fun `a proxy status is put into words`() {
        assertTrue(statusMessage(502).startsWith("Sonorus ist nicht erreichbar"))
        assertTrue(statusMessage(504).startsWith("Der Server hat zu lange nicht geantwortet"))
        assertTrue(statusMessage(500).startsWith("Der Server hat mit einem Fehler geantwortet"))
        // The status stays in brackets for whoever has to look into it.
        assertTrue(statusMessage(502).endsWith("(HTTP 502)."))
    }

    @Test
    fun `no network at all is not blamed on the address`() {
        assertEquals(NO_NETWORK, errorMessage(UnknownHostException("musik.example.com"), noNetwork = true))
        assertTrue(errorMessage(UnknownHostException("musik.example.com"), noNetwork = false).startsWith("Diese Adresse"))
        assertTrue(errorMessage(ConnectException("Failed to connect"), noNetwork = false).startsWith("Der Server nimmt"))
    }

    @Test
    fun `a full phone is not a missing server`() {
        val full = IOException("write failed: ENOSPC (No space left on device)")
        assertEquals("Auf dem Telefon ist kein Speicherplatz mehr frei.", errorMessage(full, noNetwork = false))
    }

    @Test
    fun `the app's own sentences survive`() {
        val cut = DownloadCutShort("Die Datei kam unvollständig an (1 von 2 Bytes).")
        assertEquals(cut.message, errorMessage(cut, noNetwork = false))
        assertEquals(statusMessage(503), errorMessage(DownloadRetry.httpFailure(503), noNetwork = false))
        assertEquals("Die Datei fehlt auf dem Server.", errorMessage(DownloadRetry.httpFailure(404), noNetwork = false))
    }

    @Test
    fun `an answer this build cannot read says so`() {
        val message = errorMessage(SerializationException("Expected start of the array"), noNetwork = false)
        assertEquals("Die Antwort des Servers passt nicht zu dieser App-Version.", message)
    }

    @Test
    fun `a bug is named as one`() {
        assertEquals("Fehler in der App: boom", errorMessage(IllegalStateException("boom"), noNetwork = false))
    }

    @Test
    fun `a song that will not play says why`() {
        assertEquals("Die Datei fehlt auf dem Server.", playbackMessage(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, 404))
        assertEquals(
            "Dieses Format kann das Telefon nicht abspielen.",
            playbackMessage(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED, null),
        )
        assertEquals(
            "Die heruntergeladene Datei fehlt auf dem Telefon.",
            playbackMessage(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND, null),
        )
    }
}
