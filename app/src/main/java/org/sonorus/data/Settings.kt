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

    /**
     * Hold the original back until the phone is on Wi-Fi.
     *
     * A flow, unlike [wifiOnly], because it is read while a track is being
     * opened and drawn in two places at once - see [QualityPolicy], which is
     * where the rule itself lives.
     */
    private val _losslessWifiOnly = MutableStateFlow(prefs.getBoolean(KEY_LOSSLESS_WIFI, false))
    val losslessWifiOnly: StateFlow<Boolean> = _losslessWifiOnly.asStateFlow()

    fun setLosslessWifiOnly(on: Boolean) {
        prefs.edit().putBoolean(KEY_LOSSLESS_WIFI, on).apply()
        _losslessWifiOnly.value = on
    }

    /**
     * This phone is being used without a login, on its downloads alone.
     *
     * Its own flag rather than a mood read off the offline switch: it is the
     * only thing that may let the app past the login form, so it has to be
     * something the user chose and nothing that can be inferred.
     */
    var downloadsOnly: Boolean
        get() = prefs.getBoolean(KEY_DOWNLOADS_ONLY, false)
        set(value) = prefs.edit().putBoolean(KEY_DOWNLOADS_ONLY, value).apply()

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

    /**
     * How a book is set: face, size, leading and margin.
     *
     * On the phone rather than on the account, for the reason the switches
     * above are: what reads comfortably is a fact about the screen in the hand.
     */
    private val _readerStyle = MutableStateFlow(
        ReaderStyle(
            font = ReaderFont.of(prefs.getString(KEY_READER_FONT, null)),
            size = prefs.getInt(KEY_READER_SIZE, ReaderStyle.DEFAULT.size),
            leading = prefs.getFloat(KEY_READER_LEADING, ReaderStyle.DEFAULT.leading),
            margin = prefs.getInt(KEY_READER_MARGIN, ReaderStyle.DEFAULT.margin),
        )
    )
    val readerStyle: StateFlow<ReaderStyle> = _readerStyle.asStateFlow()

    fun setReaderStyle(style: ReaderStyle) {
        prefs.edit()
            .putString(KEY_READER_FONT, style.font.wire)
            .putInt(KEY_READER_SIZE, style.size)
            .putFloat(KEY_READER_LEADING, style.leading)
            .putInt(KEY_READER_MARGIN, style.margin)
            .apply()
        _readerStyle.value = style
    }

    private companion object {
        const val KEY_WIFI_ONLY = "wifiOnly"
        const val KEY_LOSSLESS_WIFI = "losslessWifiOnly"
        const val KEY_OFFLINE = "offlineMode"
        const val KEY_DOWNLOADS_ONLY = "downloadsOnly"
        const val KEY_QUEUE = "playerQueue"
        const val KEY_STREAM_QUALITY = "streamQuality"
        const val KEY_DOWNLOAD_QUALITY = "downloadQuality"
        const val KEY_READER_FONT = "readerFont"
        const val KEY_READER_SIZE = "readerSize"
        const val KEY_READER_LEADING = "readerLeading"
        const val KEY_READER_MARGIN = "readerMargin"
    }
}
