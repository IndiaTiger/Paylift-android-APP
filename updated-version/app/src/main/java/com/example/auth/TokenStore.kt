package com.example.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class Tokens(val accessToken: String, val refreshToken: String)

/** Persists the session tokens. Implementations must be thread-safe. */
interface TokenStore {
    fun read(): Tokens?
    fun write(tokens: Tokens)
    fun clear()
}

class InMemoryTokenStore(initial: Tokens? = null) : TokenStore {
    @Volatile private var tokens: Tokens? = initial
    override fun read() = tokens
    override fun write(tokens: Tokens) { this.tokens = tokens }
    override fun clear() { tokens = null }
}

/**
 * Stores tokens in private SharedPreferences, encrypted with an AES-GCM key that lives in the
 * Android Keystore (non-exportable). The prefs file is also excluded from backups
 * (res/xml/data_extraction_rules.xml, backup_rules.xml).
 */
class KeystoreTokenStore(context: Context) : TokenStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val lock = Any()
    @Volatile private var cache: Tokens? = null

    override fun read(): Tokens? = synchronized(lock) {
        cache ?: run {
            val access = prefs.getString(KEY_ACCESS, null)?.let(::decrypt)
            val refresh = prefs.getString(KEY_REFRESH, null)?.let(::decrypt)
            if (access != null && refresh != null) Tokens(access, refresh).also { cache = it } else null
        }
    }

    override fun write(tokens: Tokens) = synchronized(lock) {
        prefs.edit().putString(KEY_ACCESS, encrypt(tokens.accessToken)).putString(KEY_REFRESH, encrypt(tokens.refreshToken)).commit()
        cache = tokens
    }

    override fun clear() = synchronized(lock) {
        prefs.edit().clear().commit()
        cache = null
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val out = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String? = try {
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12)) }
        String(cipher.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8)
    } catch (_: Exception) {
        null // key invalidated or data tampered: treat as signed out
    }

    private companion object {
        const val PREFS = "paylift_session"
        const val KEY_ACCESS = "a"
        const val KEY_REFRESH = "r"
        const val ALIAS = "paylift_session_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
