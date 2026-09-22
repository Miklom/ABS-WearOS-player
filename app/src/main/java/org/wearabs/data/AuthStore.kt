package org.wearabs.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Everything needed to talk to a server as a logged-in user. */
data class Session(
    /** Base URL with no trailing slash. */
    val serverUrl: String,
    val username: String,
    /** Bearer token for API calls. The server expires these after an hour. */
    val accessToken: String,
    /** Exchanged at /auth/refresh for a new pair. Valid 30 days, and rotated on use. */
    val refreshToken: String,
    val defaultLibraryId: String? = null
)

/**
 * Holds the login session. The two tokens are encrypted with a hardware-backed
 * AES key from the Android Keystore before they touch SharedPreferences — the
 * same approach the official Audiobookshelf app uses for its refresh tokens.
 *
 * The password itself is never stored: it is exchanged for tokens at login and
 * then dropped.
 */
class AuthStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _session = MutableStateFlow(load())
    val session: StateFlow<Session?> = _session.asStateFlow()

    val current: Session? get() = _session.value

    fun save(session: Session) {
        prefs.edit()
            .putString(KEY_SERVER_URL, session.serverUrl)
            .putString(KEY_USERNAME, session.username)
            .putString(KEY_ACCESS, encrypt(session.accessToken))
            .putString(KEY_REFRESH, encrypt(session.refreshToken))
            .putString(KEY_LIBRARY, session.defaultLibraryId)
            .apply()
        _session.value = session
    }

    /** Stores a refreshed token pair, keeping the rest of the session. */
    fun updateTokens(accessToken: String, refreshToken: String) {
        val existing = _session.value ?: return
        save(existing.copy(accessToken = accessToken, refreshToken = refreshToken))
    }

    fun updateLibraryId(libraryId: String) {
        val existing = _session.value ?: return
        save(existing.copy(defaultLibraryId = libraryId))
    }

    fun clear() {
        prefs.edit().clear().apply()
        _session.value = null
    }

    private fun load(): Session? {
        val serverUrl = prefs.getString(KEY_SERVER_URL, null) ?: return null
        val username = prefs.getString(KEY_USERNAME, null) ?: return null
        val access = prefs.getString(KEY_ACCESS, null)?.let(::decrypt) ?: return null
        val refresh = prefs.getString(KEY_REFRESH, null)?.let(::decrypt) ?: return null
        return Session(
            serverUrl = serverUrl,
            username = username,
            accessToken = access,
            refreshToken = refresh,
            defaultLibraryId = prefs.getString(KEY_LIBRARY, null)
        )
    }

    // ---- Keystore-backed AES/GCM -------------------------------------------

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }

    private fun encrypt(value: String): String? = try {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
    } catch (e: Exception) {
        Log.e(TAG, "Could not encrypt session value", e)
        null
    }

    private fun decrypt(stored: String): String? = try {
        val combined = Base64.decode(stored, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            GCMParameterSpec(TAG_LENGTH_BITS, combined, 0, IV_LENGTH)
        )
        String(cipher.doFinal(combined, IV_LENGTH, combined.size - IV_LENGTH), Charsets.UTF_8)
    } catch (e: Exception) {
        // A wiped keystore entry makes the stored blob unreadable; treat it as
        // "not logged in" rather than crashing on launch.
        Log.w(TAG, "Could not decrypt session value, forcing a new login", e)
        null
    }

    private companion object {
        const val TAG = "AuthStore"
        const val PREFS = "wearabs-session"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "WearAbsSession"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
        const val TAG_LENGTH_BITS = 128

        const val KEY_SERVER_URL = "serverUrl"
        const val KEY_USERNAME = "username"
        const val KEY_ACCESS = "accessToken"
        const val KEY_REFRESH = "refreshToken"
        const val KEY_LIBRARY = "defaultLibraryId"
    }
}
