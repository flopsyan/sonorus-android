package org.sonorus.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * Where the app is pointed and who it logs in as.
 *
 * Sonorus authenticates with a signed, stateless session cookie handed out by a
 * plain form POST to `/login` - the same one the browser uses. Two things
 * follow from that, and both are handled here:
 *
 *  - The cookie is good for 30 days (`MAX_AGE_MS` in `src/lib/auth.js`), so it
 *    is worth persisting rather than logging in on every cold start.
 *  - It *will* eventually expire, and a password change invalidates it early,
 *    because the signature binds the password hash. So the credentials are kept
 *    too and [SonorusApi] logs in again by itself when a call comes back 401.
 *    Without that the app would simply stop working one day.
 *
 * The credentials and the cookie are the two secrets here, so both live in
 * EncryptedSharedPreferences rather than plain ones.
 */
class Session(context: Context) {

    private val prefs: SharedPreferences = run {
        val key = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "sonorus_session",
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /** Base URL without a trailing slash, e.g. `https://musik.example.com`. */
    var serverUrl: String
        get() = prefs.getString(KEY_SERVER, "") ?: ""
        private set(value) = prefs.edit().putString(KEY_SERVER, value.trimEnd('/')).apply()

    var username: String
        get() = prefs.getString(KEY_USER, "") ?: ""
        private set(value) = prefs.edit().putString(KEY_USER, value).apply()

    var password: String
        get() = prefs.getString(KEY_PASS, "") ?: ""
        private set(value) = prefs.edit().putString(KEY_PASS, value).apply()

    val isConfigured: Boolean get() = serverUrl.isNotEmpty() && username.isNotEmpty()

    fun store(server: String, user: String, pass: String) {
        serverUrl = server
        username = user
        password = pass
    }

    fun clear() {
        cookieJar.forget()
        prefs.edit().clear().apply()
    }

    val cookieJar = SessionCookieJar()

    /**
     * Keeps exactly the one cookie Sonorus sets. A general cookie store would
     * be more than this needs - the server issues `sonorus-session` and nothing
     * else, and it is the only thing worth surviving a restart.
     */
    inner class SessionCookieJar : CookieJar {
        private var cookie: Cookie? = null

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            for (c in cookies) {
                if (c.name != COOKIE_NAME) continue
                // Logging out clears the cookie by sending an empty value.
                if (c.value.isEmpty()) forget() else remember(c)
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> =
            listOfNotNull(cookie ?: restore(url))

        private fun remember(c: Cookie) {
            cookie = c
            prefs.edit().putString(KEY_COOKIE, c.value).apply()
        }

        private fun restore(url: HttpUrl): Cookie? {
            val value = prefs.getString(KEY_COOKIE, "").orEmpty()
            if (value.isEmpty()) return null
            return Cookie.Builder()
                .name(COOKIE_NAME)
                .value(value)
                .domain(url.host)
                .path("/")
                .build()
                .also { cookie = it }
        }

        /** Forces the next call to log in again. */
        fun forget() {
            cookie = null
            prefs.edit().remove(KEY_COOKIE).apply()
        }

        val hasCookie: Boolean
            get() = cookie != null || prefs.getString(KEY_COOKIE, "").orEmpty().isNotEmpty()
    }

    private companion object {
        const val COOKIE_NAME = "sonorus-session"
        const val KEY_SERVER = "server"
        const val KEY_USER = "user"
        const val KEY_PASS = "pass"
        const val KEY_COOKIE = "cookie"
    }
}
