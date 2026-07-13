package be.mygod.vpnhotspot.proxy

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import be.mygod.vpnhotspot.App.Companion.app
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/** Persisted proxy-only configuration backed by the app's default preferences. */
object ProxyOnlyPreferences {
    const val KEY_ENABLED = "proxy.enabled"
    const val KEY_TCP_PORT = "proxy.tcpPort"
    const val KEY_UDP_ENABLED = "proxy.udpEnabled"
    const val KEY_UDP_RANGE_START = "proxy.udpRangeStart"
    const val KEY_UDP_RANGE_END = "proxy.udpRangeEnd"
    const val KEY_MAX_UDP = "proxy.maxUdpAssociations"
    const val KEY_CREDENTIALS_VERSION = "proxy.credentialsVersion"
    internal const val KEY_CREDENTIALS = "proxy.credentials"

    const val DEFAULT_TCP_PORT = 1080
    const val DEFAULT_UDP_START = 20_000
    const val DEFAULT_UDP_END = 20_100
    const val DEFAULT_MAX_UDP = 32

    var enabled: Boolean
        get() = app.pref.getBoolean(KEY_ENABLED, false)
        set(value) = app.pref.edit().putBoolean(KEY_ENABLED, value).apply()

    fun current(): ProxyOnlySettings {
        val tcpPort = app.pref.getInt(KEY_TCP_PORT, DEFAULT_TCP_PORT).coerceIn(1, 65_535)
        val udpStart = app.pref.getInt(KEY_UDP_RANGE_START, DEFAULT_UDP_START).coerceIn(1, 65_535)
        val udpEnd = app.pref.getInt(KEY_UDP_RANGE_END, DEFAULT_UDP_END).coerceIn(udpStart, 65_535)
        return ProxyOnlySettings(
            enabled = enabled,
            tcpPort = tcpPort,
            udpEnabled = app.pref.getBoolean(KEY_UDP_ENABLED, true),
            udpPortRange = udpStart..udpEnd,
            maxUdpAssociations = app.pref.getInt(KEY_MAX_UDP, DEFAULT_MAX_UDP).coerceIn(1, 256),
            credentialsVersion = app.pref.getLong(KEY_CREDENTIALS_VERSION, 1L).coerceAtLeast(1L),
        )
    }

    fun setTcpPort(value: Int) {
        require(value in 1..65_535)
        app.pref.edit().putInt(KEY_TCP_PORT, value).apply()
    }

    fun setUdpEnabled(value: Boolean) {
        app.pref.edit().putBoolean(KEY_UDP_ENABLED, value).apply()
    }

    fun setUdpRange(value: IntRange) {
        require(value.first in 1..65_535 && value.last in value.first..65_535)
        app.pref.edit()
            .putInt(KEY_UDP_RANGE_START, value.first)
            .putInt(KEY_UDP_RANGE_END, value.last)
            .apply()
    }

    fun setMaxUdpAssociations(value: Int) {
        require(value in 1..256)
        app.pref.edit().putInt(KEY_MAX_UDP, value).apply()
    }
}

fun proxySettingsFlow(): Flow<ProxyOnlySettings> = callbackFlow {
    trySend(ProxyOnlyPreferences.current())
    val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key?.startsWith("proxy.") == true) trySend(ProxyOnlyPreferences.current())
    }
    app.pref.registerOnSharedPreferenceChangeListener(listener)
    awaitClose { app.pref.unregisterOnSharedPreferenceChangeListener(listener) }
}.distinctUntilChanged()

/** Android-Keystore-backed credentials. Secrets are never included in settings or logs. */
object ProxyCredentialStore : ProxyCredentialProvider {
    private const val KEY_ALIAS = "vpnhotspot-proxy-credentials-v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_LENGTH = 12
    private val random = SecureRandom()

    @Synchronized
    override fun credentials(): ProxyCredentials {
        val encoded = app.pref.getString(ProxyOnlyPreferences.KEY_CREDENTIALS, null)
        if (encoded != null) runCatching { decrypt(encoded) }.getOrNull()?.let { return it }
        return generateAndStore(incrementVersion = encoded != null)
    }

    @Synchronized
    fun rotate(): ProxyCredentials = generateAndStore(incrementVersion = true)

    private fun generateAndStore(incrementVersion: Boolean): ProxyCredentials {
        val username = "vpn-" + randomBytes(4)
        val password = randomBytes(24)
        val credentials = ProxyCredentials(username, password)
        val editor = app.pref.edit().putString(
            ProxyOnlyPreferences.KEY_CREDENTIALS,
            encrypt("$username\n$password"),
        )
        if (incrementVersion) {
            editor.putLong(
                ProxyOnlyPreferences.KEY_CREDENTIALS_VERSION,
                app.pref.getLong(ProxyOnlyPreferences.KEY_CREDENTIALS_VERSION, 1L) + 1L,
            )
        }
        editor.apply()
        return credentials
    }

    private fun randomBytes(size: Int): String = ByteArray(size).also(random::nextBytes).let {
        Base64.encodeToString(it, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        val packed = ByteArray(cipher.iv.size + encrypted.size)
        cipher.iv.copyInto(packed)
        encrypted.copyInto(packed, cipher.iv.size)
        return Base64.encodeToString(packed, Base64.NO_WRAP)
    }

    private fun decrypt(value: String): ProxyCredentials {
        val packed = Base64.decode(value, Base64.NO_WRAP)
        require(packed.size > IV_LENGTH)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            GCMParameterSpec(128, packed.copyOfRange(0, IV_LENGTH)),
        )
        val clear = cipher.doFinal(packed.copyOfRange(IV_LENGTH, packed.size))
            .toString(StandardCharsets.UTF_8)
        val split = clear.indexOf('\n')
        require(split in 1 until clear.lastIndex)
        return ProxyCredentials(clear.substring(0, split), clear.substring(split + 1))
    }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }
}
