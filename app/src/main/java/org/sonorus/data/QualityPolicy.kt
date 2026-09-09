package org.sonorus.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * What the phone may really ask for, as opposed to what is set.
 *
 * One rule, and it is the one every streaming app has: **the original only over
 * Wi-Fi**, if that is what the user asked for. On mobile data the stream falls
 * back to Opus 128 by itself and the picker refuses to be moved, with a line
 * saying why - rather than silently spending a gigabyte on an album that could
 * have been eighty megabytes.
 *
 * Written by hand rather than as `combine(...).stateIn(...)`, for the reason
 * [Library.offline] gives: a derived flow updates on its own coroutine, and the
 * player asks this question at the moment it opens a track. Reading a value one
 * beat behind there would open a FLAC on mobile data - which is the exact thing
 * the setting exists to prevent.
 *
 * Downloads are not part of this. They have their own switch and a different
 * answer: a download over mobile data is not made smaller, it *waits*. See
 * [Downloads] and [Settings.wifiOnly].
 */
class QualityPolicy(
    private val settings: Settings,
    private val connectivity: Connectivity,
    scope: CoroutineScope,
) {

    /**
     * The one song the user has bought an exception for.
     *
     * Florian's rule, and the shape follows from it: the exception has to be so
     * narrow that a large file can never be fetched by accident. It is one
     * track id, it is confirmed by hand, and it is gone the moment anything
     * else starts playing - going one song on and back means asking again.
     */
    private val _exceptionFor = MutableStateFlow<Int?>(null)

    /**
     * A flow and not a plain field, because the format under the transport is
     * drawn from it: granting the exception has to redraw that chip, and a
     * value nothing observes redraws nothing.
     */
    val exceptionFor: StateFlow<Int?> = _exceptionFor.asStateFlow()

    private val _losslessAllowed = MutableStateFlow(allowedNow())

    /** Whether the original may be asked for at all, right now. */
    val losslessAllowed: StateFlow<Boolean> = _losslessAllowed.asStateFlow()

    private val _streamQuality = MutableStateFlow(qualityNow())

    /**
     * What a stream is really asked for - the setting, unless it is the
     * original on a metered connection while the switch is on.
     */
    val streamQuality: StateFlow<Quality> = _streamQuality.asStateFlow()

    init {
        scope.launch { connectivity.unmetered.collect { recompute() } }
        scope.launch { settings.losslessWifiOnly.collect { recompute() } }
        scope.launch { settings.streamQuality.collect { recompute() } }
    }

    /** The synchronous answer, for callers that must not read a stale one. */
    fun allowedNow(): Boolean = !settings.losslessWifiOnly.value || connectivity.unmetered.value

    /** The same question for one song, which may carry an exception. */
    fun allowedFor(trackId: Int?): Boolean =
        allowedNow() || (trackId != null && trackId == _exceptionFor.value)

    fun qualityNow(): Quality {
        val wanted = settings.streamQuality.value
        return if (wanted == Quality.ORIGINAL && !allowedNow()) Quality.OPUS128 else wanted
    }

    /** What to ask for when opening [trackId], exception included. */
    fun qualityFor(trackId: Int?): Quality {
        val wanted = settings.streamQuality.value
        return if (wanted == Quality.ORIGINAL && !allowedFor(trackId)) Quality.OPUS128 else wanted
    }

    /** This one song, this once. Undone as soon as anything else plays. */
    fun allowOnce(trackId: Int) {
        _exceptionFor.value = trackId
    }

    /**
     * The song has changed to [trackId] - drop an exception that was not for
     * it. Compared rather than cleared outright: reopening the running track is
     * itself a media-item change, and clearing there would undo the exception
     * in the same breath it was granted.
     */
    fun forgetExceptionUnless(trackId: Int?) {
        if (_exceptionFor.value == null || _exceptionFor.value == trackId) return
        _exceptionFor.value = null
    }

    private fun recompute() {
        _losslessAllowed.value = allowedNow()
        _streamQuality.value = qualityNow()
    }
}
