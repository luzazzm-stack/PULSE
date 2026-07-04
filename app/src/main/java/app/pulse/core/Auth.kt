package app.pulse.core

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val Context.authDataStore by preferencesDataStore("pulse_auth")
private val COOKIES_KEY = stringPreferencesKey("cookies")

/**
 * Holds the user's YouTube Music session cookies (grabbed from a one-time WebView login) and signs
 * InnerTube requests with SAPISIDHASH — the same auth the music.youtube.com web app uses.
 *
 * Google REMOVED OAuth support for YouTube Music endpoints (Nov 2024), so the earlier device-code
 * Bearer flow can never personalize again; cookie auth is the only method that works.
 *
 * The cookie is a full-account credential, so it's encrypted at rest with an Android-Keystore AES-GCM
 * key (ciphertext is prefixed "enc:"; legacy plaintext is migrated on next save).
 */
object AuthStore {

    @Volatile
    var cookies: String? = null
        private set

    private val _connected = MutableStateFlow(false)
    val connectedFlow = _connected.asStateFlow()
    val connected: Boolean get() = !cookies.isNullOrBlank()

    suspend fun load(context: Context) {
        val stored = context.authDataStore.data.map { it[COOKIES_KEY] }.first()
        cookies = when {
            stored.isNullOrBlank() -> null
            stored.startsWith("enc:") -> decrypt(stored)
            else -> stored   // legacy plaintext — re-encrypted on next save()
        }
        _connected.value = !cookies.isNullOrBlank()
    }

    suspend fun save(context: Context, c: String) {
        cookies = c
        context.authDataStore.edit { it[COOKIES_KEY] = encrypt(c) }
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

    // --- cookie encryption at rest (Android Keystore, AES-GCM) ---
    private const val KEY_ALIAS = "pulse_cookie_key"

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        kg.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return kg.generateKey()
    }

    /** Returns "enc:<base64(iv|ciphertext)>"; falls back to plaintext if the Keystore is unavailable. */
    private fun encrypt(plain: String): String = runCatching {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, secretKey())
        "enc:" + Base64.encodeToString(c.iv + c.doFinal(plain.toByteArray()), Base64.NO_WRAP)
    }.getOrDefault(plain)

    private fun decrypt(stored: String): String? = runCatching {
        val data = Base64.decode(stored.removePrefix("enc:"), Base64.NO_WRAP)
        val iv = data.copyOfRange(0, 12)
        val ct = data.copyOfRange(12, data.size)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        String(c.doFinal(ct))
    }.getOrNull()
}
