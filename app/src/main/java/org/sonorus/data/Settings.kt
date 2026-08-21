package org.sonorus.data

import android.content.Context

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

    private companion object {
        const val KEY_WIFI_ONLY = "wifiOnly"
        const val KEY_OFFLINE = "offlineMode"
        const val KEY_QUEUE = "playerQueue"
    }
}
