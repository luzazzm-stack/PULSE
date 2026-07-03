package app.pulse.core

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

private val Context.authDataStore by preferencesDataStore("pulse_auth")
private val K_ACCESS = stringPreferencesKey("access_token")
private val K_REFRESH = stringPreferencesKey("refresh_token")
private val K_EXPIRES = longPreferencesKey("expires_at")

/**
 * Google OAuth 2.0 device-code auth (the flow ytmusicapi / YouTube-on-TV use): the user approves a
 * short code in their NORMAL browser — no in-app login, no re-entering a password — and we sign
 * InnerTube requests with a Bearer token. Uses the public YouTube-TV OAuth client.
 */
object AuthStore {

    private const val CLIENT_ID = "861556708454-d6dlm3lh05idd8npek18k6be8ba3oc68.apps.googleusercontent.com"
    private const val CLIENT_SECRET = "SboVhoG9s0rNafixCSGGKXAT"
    private const val SCOPE = "https://www.googleapis.com/auth/youtube"
    private const val DEVICE_CODE_URL = "https://www.youtube.com/o/oauth2/device/code"
    private const val TOKEN_URL = "https://oauth2.googleapis.com/token"
    private const val DEVICE_GRANT = "http://oauth.net/grant_type/device/1.0"

    private val client = OkHttpClient()
    private val FORM = "application/x-www-form-urlencoded".toMediaType()

    @Volatile private var accessToken: String? = null
    @Volatile private var refreshToken: String? = null
    @Volatile private var expiresAt: Long = 0L

    private val _connected = MutableStateFlow(false)
    val connectedFlow = _connected.asStateFlow()
    val connected: Boolean get() = refreshToken != null

    suspend fun load(context: Context) {
        val prefs = context.authDataStore.data.first()
        accessToken = prefs[K_ACCESS]
        refreshToken = prefs[K_REFRESH]
        expiresAt = prefs[K_EXPIRES] ?: 0L
        _connected.value = refreshToken != null
    }

    suspend fun clear(context: Context) {
        accessToken = null; refreshToken = null; expiresAt = 0L
        context.authDataStore.edit { it.remove(K_ACCESS); it.remove(K_REFRESH); it.remove(K_EXPIRES) }
        _connected.value = false
    }

    private suspend fun persist(context: Context) {
        context.authDataStore.edit {
            val a = accessToken; if (a != null) it[K_ACCESS] = a else it.remove(K_ACCESS)
            val r = refreshToken; if (r != null) it[K_REFRESH] = r else it.remove(K_REFRESH)
            it[K_EXPIRES] = expiresAt
        }
    }

    data class DeviceCode(val deviceCode: String, val userCode: String, val verificationUrl: String, val interval: Int, val expiresIn: Int)

    /** Step 1: ask Google for a device + user code. Call off the main thread. */
    fun requestDeviceCode(): DeviceCode? {
        val resp = form(DEVICE_CODE_URL, "client_id=$CLIENT_ID&scope=$SCOPE") ?: return null
        val user = resp.optString("user_code", "")
        val device = resp.optString("device_code", "")
        if (user.isEmpty() || device.isEmpty()) return null
        val url = resp.optString("verification_url").ifBlank { "https://www.google.com/device" }
        return DeviceCode(device, user, url, resp.optInt("interval", 5), resp.optInt("expires_in", 1800))
    }

    /** Step 2: poll until the user approves in their browser. Suspends; returns true on success. */
    suspend fun pollForToken(context: Context, code: DeviceCode): Boolean {
        var interval = code.interval.coerceAtLeast(3)
        val deadline = nowSec() + code.expiresIn
        while (nowSec() < deadline) {
            delay(interval * 1000L)
            val resp = form(TOKEN_URL, "client_id=$CLIENT_ID&client_secret=$CLIENT_SECRET&code=${code.deviceCode}&grant_type=$DEVICE_GRANT") ?: continue
            val at = resp.optString("access_token", "")
            if (at.isNotEmpty()) {
                accessToken = at
                refreshToken = resp.optString("refresh_token").ifBlank { refreshToken }
                expiresAt = nowSec() + resp.optLong("expires_in", 3600)
                persist(context.applicationContext)
                _connected.value = true
                return true
            }
            when (resp.optString("error")) {
                "slow_down" -> interval += 3
                "access_denied", "expired_token" -> return false
                else -> {} // authorization_pending — keep polling
            }
        }
        return false
    }

    /** Authorization header for InnerTube, refreshing the access token if expired. Null if not connected. */
    fun authHeader(): String? {
        refreshToken ?: return null
        if (accessToken == null || nowSec() >= expiresAt - 60) refreshAccess()
        return accessToken?.let { "Bearer $it" }
    }

    private fun refreshAccess() {
        val rt = refreshToken ?: return
        val resp = form(TOKEN_URL, "client_id=$CLIENT_ID&client_secret=$CLIENT_SECRET&refresh_token=$rt&grant_type=refresh_token") ?: return
        val at = resp.optString("access_token", "")
        if (at.isNotEmpty()) {
            accessToken = at
            expiresAt = nowSec() + resp.optLong("expires_in", 3600)
        }
    }

    private fun nowSec() = System.currentTimeMillis() / 1000

    private fun form(url: String, body: String): JSONObject? = runCatching {
        val req = Request.Builder().url(url).post(body.toRequestBody(FORM)).header("User-Agent", NewPipeDownloader.USER_AGENT).build()
        client.newCall(req).execute().use { resp -> JSONObject(resp.body?.string() ?: "") }
    }.getOrNull()
}
