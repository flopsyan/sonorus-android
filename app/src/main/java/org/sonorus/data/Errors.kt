package org.sonorus.data

import androidx.media3.common.PlaybackException
import kotlinx.serialization.SerializationException
import org.sonorus.SonorusApp
import org.sonorus.data.download.DownloadCutShort
import org.sonorus.data.download.DownloadLocalFailure
import org.sonorus.data.download.DownloadRefused
import java.io.IOException

const val NO_SERVER = "Der Server ist nicht erreichbar."
const val NO_NETWORK = "Keine Internetverbindung."

/** A status the server answered with, when the body did not say more. */
class HttpStatusException(val status: Int) : IOException(statusMessage(status))

/**
 * What a status means when the body does not say it. An answer that is not
 * Sonorus's JSON came from the reverse proxy in front of it. The web client
 * says the same sentences (`statusMessage` in `public/js/api.js`).
 */
fun statusMessage(status: Int): String = when {
    status == 413 -> "Die Anfrage war für den Server zu groß."
    status == 502 || status == 503 ->
        "Sonorus ist nicht erreichbar, der Dienst startet vielleicht gerade neu (HTTP $status)."
    status == 504 -> "Der Server hat zu lange nicht geantwortet (HTTP 504)."
    status == 429 -> "Zu viele Anfragen, bitte kurz warten (HTTP 429)."
    status == 403 -> "Der Zugriff wurde vor Sonorus abgewiesen (HTTP 403)."
    status >= 500 -> "Der Server hat mit einem Fehler geantwortet (HTTP $status)."
    else -> "Unerwartete Antwort vom Server (HTTP $status)."
}

/**
 * The sentence for any failure. Sonorus's own errors already say it in German;
 * the network stack's ("Failed to connect to /10.0.2.2:3111") are told apart
 * by kind, and anything else is a bug in the app and says so.
 */
fun errorMessage(
    error: Throwable,
    noNetwork: Boolean = !SonorusApp.instance.connectivity.online.value,
): String = when (error) {
    is ApiException, is HttpStatusException, is DownloadRefused, is DownloadLocalFailure, is DownloadCutShort ->
        error.message ?: NO_SERVER
    // The server answered a shape this build does not know.
    is SerializationException -> "Die Antwort des Servers passt nicht zu dieser App-Version."
    is java.net.UnknownHostException ->
        if (noNetwork) NO_NETWORK else "Diese Adresse gibt es nicht. Steht der Server richtig in den Einstellungen?"
    is java.net.ConnectException ->
        if (noNetwork) NO_NETWORK else "Der Server nimmt keine Verbindung an. Läuft Sonorus?"
    is java.net.SocketTimeoutException -> "Der Server antwortet nicht rechtzeitig."
    is javax.net.ssl.SSLException -> "Die verschlüsselte Verbindung kam nicht zustande."
    is IOException -> when {
        // Android's errno text, e.g. "write failed: ENOSPC (No space left on device)".
        error.message.orEmpty().contains("ENOSPC") -> "Auf dem Telefon ist kein Speicherplatz mehr frei."
        noNetwork -> NO_NETWORK
        else -> NO_SERVER
    }
    else -> "Fehler in der App: ${error.message ?: error.javaClass.simpleName}"
}

/** Why a song or a video would not play, for the error codes that are not the network. */
fun playbackMessage(errorCode: Int, httpStatus: Int?): String = when {
    httpStatus == 404 -> "Die Datei fehlt auf dem Server."
    httpStatus != null -> statusMessage(httpStatus)
    else -> when (errorCode) {
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "Die heruntergeladene Datei fehlt auf dem Telefon."
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> "Kein Zugriff auf die Datei."
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        -> "Dieses Format kann das Telefon nicht abspielen."
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        -> "Die Datei ist beschädigt oder unvollständig."
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        -> "Die Verbindung zum Server ist abgebrochen."
        else -> "Wiedergabefehler ${PlaybackException.getErrorCodeName(errorCode)}."
    }
}
