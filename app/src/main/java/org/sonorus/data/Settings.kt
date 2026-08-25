package org.sonorus.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The handful of switches that belong to *this phone* rather than to the
 * account.
 *
 * Everything the web app also has lives in `users.prefs` on the server, so it
 * follows the user to another device. These two do not: what a download may
 * cost and whether this device is meant to stay offline are facts about the
 * phone in the hand, and a server that is unreachable is exactly when they have
 * to be readable. So they are plain SharedPreferences - no secret, and no
 * request.
 */
class Settings(context: Context) {

    private val prefs = context.getSharedPreferences("sonorus_local", Context.MODE_PRIVATE)

    /** Hold downloads back until the phone is on something not billed by the byte. */
    var wifiOnly: Boolean
        get() = prefs.getBoolean(KEY_WIFI_ONLY, false)
        set(value) = prefs.edit().putBoolean(KEY_WIFI_ONLY, value).apply()

    /** The user's own offline switch, the way Spotify has one. */
    var offlineMode: Boolean
        get() = prefs.getBoolean(KEY_OFFLINE, false)
        set(value) = prefs.edit().putBoolean(KEY_OFFLINE, value).apply()

    /**
     * The queue this phone was last playing, as JSON - see
     * `PlayerController.saveQueue`. It belongs here for the same reason as the
     * two above: what is in the queue of *this* device is a fact about the
     * device, the way the web app keeps it in that browser's `localStorage`
     * rather than on the account. Null when there is nothing to come back to.
     */
    var playerQueue: String?
        get() = prefs.getString(KEY_QUEUE, null)
        set(value) = prefs.edit().putString(KEY_QUEUE, value).apply()

    /**
     * How large a streamed song may arrive, and how large a downloaded one may.
     *
     * Two switches rather than one, because they are different questions: a
     * stream spends data every time the song plays, a download spends it once
     * and then costs storage for good. The small copy on the road and the
     * original on the shelf is a perfectly ordinary answer.
     *
     * Both belong here and not in `users.prefs` for the reason the two above do:
     * this is a fact about the phone in the hand, and the moment it has to be
     * readable is exactly the moment the server cannot be asked.
     *
     * Changing the download setting **never touches what is already on the
     * phone**. A song fetched as FLAC stays a FLAC; the setting decides what the
     * next download asks for and nothing else.
     */
    private val _streamQuality =
        MutableStateFlow(Quality.of(prefs.getString(KEY_STREAM_QUALITY, null)))
    private val _downloadQuality =
        MutableStateFlow(Quality.of(prefs.getString(KEY_DOWNLOAD_QUALITY, null)))

    /**
     * Flows rather than plain getters, unlike the switches above: these two are
     * drawn in three places at once - the settings screen, the player's menu and
     * the format indicator under the transport - and a value read once would
     * leave two of them showing yesterday's answer.
     */
    val streamQuality: StateFlow<Quality> = _streamQuality.asStateFlow()
    val downloadQuality: StateFlow<Quality> = _downloadQuality.asStateFlow()

    fun setStreamQuality(value: Quality) {
        prefs.edit().putString(KEY_STREAM_QUALITY, value.wire).apply()
        _streamQuality.value = value
    }

    fun setDownloadQuality(value: Quality) {
        prefs.edit().putString(KEY_DOWNLOAD_QUALITY, value.wire).apply()
        _downloadQuality.value = value
    }

    private companion object {
        const val KEY_WIFI_ONLY = "wifiOnly"
        const val KEY_OFFLINE = "offlineMode"
        const val KEY_QUEUE = "playerQueue"
        const val KEY_STREAM_QUALITY = "streamQuality"
        const val KEY_DOWNLOAD_QUALITY = "downloadQuality"
    }
}
