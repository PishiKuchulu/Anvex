package com.anvex.core.android

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * v1.1 §18 — the testable half of API-key security.
 *
 * [CryptoEnvelope] is PURE JVM crypto (AES-256-GCM, random 96-bit IV per
 * encryption, base64(iv || ciphertext+tag)) with no Android dependency, so its
 * correctness — round-trip, IV uniqueness, tamper detection, wrong-key
 * rejection — is proven by JVM unit tests ([CryptoEnvelopeTest]).
 *
 * [KeystoreCrypto] binds that envelope to a hardware-backed AndroidKeyStore
 * key (device verification is part of the manual device checklist).
 */
object CryptoEnvelope {

    const val IV_LEN = 12
    const val TAG_BITS = 128
    const val KEY_BITS = 256

    class EnvelopeException(message: String, cause: Throwable? = null) : Exception(message, cause)

    fun encrypt(plain: String, key: SecretKey, aad: ByteArray? = null): String {
        if (plain.isEmpty()) return ""
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            if (aad != null) cipher.updateAAD(aad)
            val iv = cipher.iv
            val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            val out = ByteArray(iv.size + ct.size)
            System.arraycopy(iv, 0, out, 0, iv.size)
            System.arraycopy(ct, 0, out, iv.size, ct.size)
            return java.util.Base64.getEncoder().withoutPadding().encodeToString(out)
        } catch (e: Exception) {
            throw EnvelopeException("Encryption failed", e)
        }
    }

    fun decrypt(encoded: String, key: SecretKey, aad: ByteArray? = null): String {
        if (encoded.isEmpty()) return ""
        try {
            val all = java.util.Base64.getDecoder().decode(encoded)
            require(all.size > IV_LEN) { "Corrupt ciphertext" }
            val iv = all.copyOfRange(0, IV_LEN)
            val ct = all.copyOfRange(IV_LEN, all.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            if (aad != null) cipher.updateAAD(aad)
            return String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (e: EnvelopeException) {
            throw e
        } catch (e: Exception) {
            throw EnvelopeException("Decryption failed (wrong key or tampered data)", e)
        }
    }

    /** Generate a software AES-256 key (JVM tests; production uses AndroidKeyStore). */
    fun softwareKey(): SecretKey {
        val generator = KeyGenerator.getInstance("AES")
        generator.init(KEY_BITS)
        return generator.generateKey()
    }
}

/**
 * API-key security (brief §12): AES-256-GCM with a hardware-backed key inside
 * AndroidKeyStore. Ciphertext at rest = base64(iv || ct). Keys never appear in
 * logs, DB plaintext, or exported files.
 */
object KeystoreCrypto {

    private const val KEY_ALIAS = "anvex_master_v1"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"

    class CryptoException(message: String, cause: Throwable? = null) : Exception(message, cause)

    // M-08: the key object is cached after first fetch — the old code ran
    // KeyStore.getInstance + load() on EVERY encrypt/decrypt. The cache is
    // cleared on any crypto failure, so a rotated/invalidated key is
    // re-fetched on the next call instead of failing forever.
    @Volatile
    private var cachedKey: SecretKey? = null

    // v4.10.1 (SEC-14): two threads racing first use could each generate a
    // key under the same alias (the loser overwrites; data encrypted with
    // the first becomes unreadable). Serialize key creation.
    @Synchronized
    private fun getOrCreateKey(): SecretKey {
        cachedKey?.let { return it }
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { cachedKey = it; return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(CryptoEnvelope.KEY_BITS)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey().also { cachedKey = it }
    }

    /**
     * M-15 / idea 1.1: optional GCM additional authenticated data. When
     * [aad] is supplied, the ciphertext is bound to it: decrypting with
     * different AAD fails authentication. Providers bind keys to
     * id + baseUrl + model, so a tampered database row (attacker URL
     * swapped in) can never yield a working key. `aad = null` is the
     * legacy format, kept for transparent migration.
     */
    fun encrypt(plain: String, aad: ByteArray? = null): String {
        if (plain.isEmpty()) return ""
        return try {
            CryptoEnvelope.encrypt(plain, getOrCreateKey(), aad)
        } catch (e: Exception) {
            cachedKey = null
            throw CryptoException("Keystore encryption failed", e)
        }
    }

    fun decrypt(encoded: String, aad: ByteArray? = null): String {
        if (encoded.isEmpty()) return ""
        return try {
            CryptoEnvelope.decrypt(encoded, getOrCreateKey(), aad)
        } catch (e: Exception) {
            cachedKey = null
            throw CryptoException("Keystore decryption failed (key may be unavailable)", e)
        }
    }
}
