package org.sonorus.data

import kotlinx.serialization.SerializationException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * The one rule the whole offline mode turns on: **did the server answer?**
 *
 * It is pinned here because getting it wrong does not look like a wrong error
 * message, it looks like an app that quietly stops using the network. Both bugs
 * Florian reported on 2026-09-01 were this rule being too broad - everything
 * that was not an `ApiException` counted as a server that had gone away, and
 * that swept in a JSON body the app could not parse and a request the user had
 * simply navigated away from.
 */
class ReachabilityTest {

    @Test
    fun `an error the server produced proves the server is there`() {
        assertTrue(serverAnswered(ApiException("not_found", "Nicht gefunden.")))
        assertTrue(serverAnswered(ApiException("http_500", "Fehler (500).")))
        // The one the whole feature exists for: a 404 for a track that has since
        // been deleted must not drop the app offline over one bad id.
        assertTrue(serverAnswered(ApiException("forbidden", "Kein Zugriff.")))
    }

    @Test
    fun `an answer the app cannot read still came from a server`() {
        // What one tap on Hörspiele threw before `SpokenWireTest` was written.
        // The server was right there and had sent a perfectly good body; the app
        // had the wrong type for one field of it.
        assertTrue(serverAnswered(SerializationException("Expected start of the array '['")))
    }

    @Test
    fun `nothing answering is the only thing that is really offline`() {
        assertFalse(serverAnswered(UnknownHostException("sonorus.flopsyan.eu")))
        assertFalse(serverAnswered(SocketTimeoutException("timeout")))
        assertFalse(serverAnswered(IOException("Software caused connection abort")))
    }

    @Test
    fun `something that is not Sonorus answering is not the server either`() {
        // A captive portal or a proxy serving its own HTML page. It answered,
        // but not with Sonorus, so the phone is behind something and the
        // downloads are the honest thing to show.
        assertFalse(serverAnswered(ApiException("not_json", "Unerwartete Antwort vom Server (HTTP 502).")))
        assertFalse(serverAnswered(ApiException("bad_url", "Die Server-Adresse ist keine gültige URL.")))
    }
}
