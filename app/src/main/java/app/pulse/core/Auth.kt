package app.pulse.core

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.MessageDigest

private val Context.authDataStore by preferencesDataStore("pulse_auth")
private val COOKIES_KEY = stringPreferencesKey("cookies")

/**
 * Holds the user's YouTube Music session cookies (grabbed from a one-time WebView login) and signs
 * InnerTube requests with SAPISIDHASH — the same auth the music.youtube.com web app uses.
 *
 * Google REMOVED OAuth support for YouTube Music endpoints (Nov 2024), so the earlier device-code
 * Bearer flow can never personalize again; cookie auth is the only method that works.
 */
object AuthStore {

    @Volatile
    var cookies: String? = null
        private set

    private val _connected = MutableStateFlow(false)
    val connectedFlow = _connected.asStateFlow()
    val connected: Boolean get() = !cookies.isNullOrBlank()

    suspend fun load(context: Context) {
        cookies = context.authDataStore.data.map { it[COOKIES_KEY] }.first()
        _connected.value = !cookies.isNullOrBlank()
    }

    suspend fun save(context: Context, c: String) {
        cookies = c
        context.authDataStore.edit { it[COOKIES_KEY] = c }
        _connected.value = true
    }

    suspend fun clear(context: Context) {
        cookies = null
        context.authDataStore.edit { it.remove(COOKIES_KEY) }
        _connected.value = false
    }

    /** Authorization header value for InnerTube, or null if not connected. */
    fun sapisidHash(): String? {
        val c = cookies ?: return null
        val sapisid = cookieValue(c, "SAPISID") ?: cookieValue(c, "__Secure-3PAPISID") ?: return null
        val time = System.currentTimeMillis() / 1000
        val origin = "https://music.youtube.com"
        val digest = sha1("$time $sapisid $origin")
        return "SAPISIDHASH ${time}_$digest"
    }

    private fun cookieValue(cookieStr: String, name: String): String? =
        cookieStr.split(";").map { it.trim() }.firstOrNull { it.startsWith("$name=") }?.substringAfter("=")

    private fun sha1(s: String): String {
        val md = MessageDigest.getInstance("SHA-1")
        return md.digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
