package org.sonorus.data.download

import org.sonorus.data.ApiException
import java.io.IOException

/** The server answered and said no - retrying the same request changes nothing. */
class DownloadRefused(message: String) : IOException(message)

/** The phone itself could not keep the file, so the network is not to blame either. */
class DownloadLocalFailure(message: String) : IOException(message)

/**
 * Whether a failed song counts as failed, and how long to wait before the next try.
 *
 * Pure so it can be tested on a plain JVM.
 */
object DownloadRetry {

    /**
     * A dropped connection is not the song's fault. Counting it as a failure is
     * what emptied the whole queue in seconds when the Wi-Fi blinked with the
     * screen off: every next song failed at connect and was dropped too.
     */
    fun isTransport(error: Throwable): Boolean = when (error) {
        is DownloadRefused, is DownloadLocalFailure, is ApiException -> false
        is IOException -> true
        else -> false
    }

    private val TRANSIENT_HTTP = setOf(408, 429, 502, 503, 504)

    /** A proxy that cannot reach the server for a moment is the network too, not a verdict on the song. */
    fun httpFailure(code: Int): IOException {
        val message = "Der Server antwortet mit HTTP $code."
        return if (code in TRANSIENT_HTTP) IOException(message) else DownloadRefused(message)
    }

    private val STEPS_MS = longArrayOf(2_000, 5_000, 10_000, 20_000, 30_000, 60_000)

    /** The pause before try [attempt] (1-based) after a transport failure. */
    fun delayFor(attempt: Int): Long = STEPS_MS[(attempt - 1).coerceIn(0, STEPS_MS.lastIndex)]

    /**
     * How long the phone keeps trying without a single new byte before it lets the
     * system decide when to go on. Long on purpose: a job handed back in deep doze
     * waited minutes past its backoff until a network change woke it (measured 2026-09-10).
     */
    const val GIVE_UP_AFTER_MS = 30 * 60_000L

    /** Tries that brought bytes and still did not finish one song: a server that always cuts it short. */
    const val MAX_CUT_SHORT = 10
}
