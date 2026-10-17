package com.anvex.core.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.1 §18 — proves the crypto envelope used for API keys at rest:
 * AES-256-GCM with per-encryption random IVs, authenticated tamper detection,
 * and wrong-key rejection. (The AndroidKeyStore binding itself is verified on
 * a physical device — see the manual device checklist.)
 */
class CryptoEnvelopeTest {

    @Test
    fun `round trip preserves the secret`() {
        val key = CryptoEnvelope.softwareKey()
        val secret = "sk-test-" + "x".repeat(64)
        val enc = CryptoEnvelope.encrypt(secret, key)
        assertEquals(secret, CryptoEnvelope.decrypt(enc, key))
    }

    @Test
    fun `every encryption uses a fresh random IV`() {
        val key = CryptoEnvelope.softwareKey()
        val a = CryptoEnvelope.encrypt("same plaintext", key)
        val b = CryptoEnvelope.encrypt("same plaintext", key)
        assertNotEquals("identical ciphertexts leak plaintext equality", a, b)
        assertEquals("same plaintext", CryptoEnvelope.decrypt(a, key))
        assertEquals("same plaintext", CryptoEnvelope.decrypt(b, key))
    }

    @Test
    fun `tampered ciphertext is rejected (GCM authentication)`() {
        val key = CryptoEnvelope.softwareKey()
        val enc = CryptoEnvelope.encrypt("top-secret-key", key)
        val bytes = java.util.Base64.getDecoder().decode(enc)
        bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 0x41).toByte()
        val tampered = java.util.Base64.getEncoder().withoutPadding().encodeToString(bytes)
        assertThrows(Exception::class.java) { CryptoEnvelope.decrypt(tampered, key) }
    }

    @Test
    fun `wrong key cannot decrypt`() {
        val keyA = CryptoEnvelope.softwareKey()
        val keyB = CryptoEnvelope.softwareKey()
        val enc = CryptoEnvelope.encrypt("secret", keyA)
        assertThrows(Exception::class.java) { CryptoEnvelope.decrypt(enc, keyB) }
    }

    @Test
    fun `truncated ciphertext is rejected`() {
        val key = CryptoEnvelope.softwareKey()
        val enc = CryptoEnvelope.encrypt("secret", key)
        val bytes = java.util.Base64.getDecoder().decode(enc)
        val truncated = java.util.Base64.getEncoder().withoutPadding()
            .encodeToString(bytes.copyOfRange(0, 8))
        assertThrows(Exception::class.java) { CryptoEnvelope.decrypt(truncated, key) }
    }

    @Test
    fun `empty input stays empty (no ciphertext emitted)`() {
        val key = CryptoEnvelope.softwareKey()
        assertEquals("", CryptoEnvelope.encrypt("", key))
        assertEquals("", CryptoEnvelope.decrypt("", key))
    }

    @Test
    fun `ciphertext never contains the plaintext`() {
        val key = CryptoEnvelope.softwareKey()
        val secret = "SUPER-SECRET-KEY-MATERIAL"
        val enc = CryptoEnvelope.encrypt(secret, key)
        assertTrue(!enc.contains(secret))
        assertTrue(!enc.contains("SUPER"))
        // base64 alphabet only
        assertTrue(enc.all { it in "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/" })
    }

    @Test
    fun `unicode keys round trip through utf-8`() {
        val key = CryptoEnvelope.softwareKey()
        val secret = "ключ-🔐-鍵"
        assertEquals(secret, CryptoEnvelope.decrypt(CryptoEnvelope.encrypt(secret, key), key))
    }

    @Test
    fun `keys survive multi-tenant usage - many entries one key`() {
        val key = CryptoEnvelope.softwareKey()
        val secrets = (1..50).map { "sk-key-$it-${"x".repeat(30)}" }
        val encs = secrets.map { CryptoEnvelope.encrypt(it, key) }
        assertEquals(50, encs.toSet().size)
        encs.zip(secrets).forEach { (enc, secret) ->
            assertEquals(secret, CryptoEnvelope.decrypt(enc, key))
        }
    }
}
