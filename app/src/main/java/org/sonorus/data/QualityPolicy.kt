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

    fun qualityNow(): Quality {
        val wanted = settings.streamQuality.value
        return if (wanted == Quality.ORIGINAL && !allowedNow()) Quality.OPUS128 else wanted
    }

    private fun recompute() {
        _losslessAllowed.value = allowedNow()
        _streamQuality.value = qualityNow()
    }
}
