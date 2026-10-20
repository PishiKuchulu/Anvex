package com.anvex.core.android.signing

import android.util.Base64
import org.bouncycastle.crypto.digests.Blake2bDigest
import org.bouncycastle.crypto.engines.XSalsa20Engine
import org.bouncycastle.crypto.macs.Poly1305
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV
import org.bouncycastle.math.ec.rfc7748.X25519
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * v4.4.6: on-device APK signing-key factory.
 *
 * Generates an RSA keypair + self-signed X.509 certificate (BouncyCastle) and
 * stores them as PKCS12 — the exact format apksigner / Gradle signing configs
 * accept. The private key never leaves the device: it is generated in memory
 * and only the password-protected PKCS12 bytes are written out (download or
 * GitHub Actions secrets).
 *
 * Passwords are [CharArray] (never [String]) and are zeroed by the caller
 * after use. Nothing secret is logged.
 */
object SigningKeyManager {

    data class GeneratedKeystore(
        val pkcs12Bytes: ByteArray,
        val alias: String,
        /** SHA-256 fingerprint of the certificate, hex, colon-separated. */
        val certFingerprint: String,
    )

    private val http = OkHttpClient.Builder().callTimeout(60, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()
    private val jsonType = "application/json".toMediaType()

    /**
     * Generates a 2048-bit RSA keypair with a self-signed certificate valid for
     * [validityYears] and returns the PKCS12 bytes. Passwords are NOT stored;
     * the caller must zero them after use.
     */
    fun generate(
        alias: String,
        storePassword: CharArray,
        keyPassword: CharArray,
        validityYears: Int = 30,
    ): GeneratedKeystore {
        require(alias.matches(Regex("[A-Za-z0-9_.-]{1,64}"))) { "Invalid key alias." }
        require(storePassword.size in 6..128) { "Store password must be 6–128 characters." }
        require(keyPassword.size in 6..128) { "Key password must be 6–128 characters." }
        require(validityYears in 1..100) { "Validity must be 1–100 years." }

        val random = SecureRandom()
        val keyGen = KeyPairGenerator.getInstance("RSA")
        keyGen.initialize(2048, random)
        val keyPair = keyGen.generateKeyPair()

        val dn = X500Name("CN=Anvex Signing Key, OU=Mobile, O=Anvex")
        val notBefore = Date()
        val notAfter = Date(notBefore.time + validityYears * 365L * 24L * 60L * 60L * 1000L)
        val serial = BigInteger(64, random).abs().let { if (it == BigInteger.ZERO) BigInteger.ONE else it }

        val certBuilder = JcaX509v3CertificateBuilder(dn, serial, notBefore, notAfter, dn, keyPair.public)
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)
        val holder = certBuilder.build(signer)
        val cert = JcaX509CertificateConverter().getCertificate(holder)

        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, storePassword)
        ks.setKeyEntry(alias, keyPair.private, keyPassword, arrayOf(cert))
        val out = ByteArrayOutputStream()
        ks.store(out, storePassword)
        val pkcs12 = out.toByteArray()

        val fingerprint = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
            .joinToString(":") { "%02X".format(it) }

        return GeneratedKeystore(pkcs12, alias, fingerprint)
    }

    fun pkcs12Base64(pkcs12: ByteArray): String =
        Base64.encodeToString(pkcs12, Base64.NO_WRAP)

    // -- GitHub Actions secrets -------------------------------------------------
    // Secrets are encrypted with the repository's public key using libsodium's
    // sealed box (X25519 + XSalsa20-Poly1305), exactly as GitHub requires.
    // Implemented with pure BouncyCastle primitives (no JNA/native loader —
    // the JNA-based first version crashed on device).

    private const val SEAL_BYTES = 48 // crypto_box_SEALBYTES: 32 (epk) + 16 (MAC)
    private const val SEAL_NONCE_BYTES = 24 // crypto_box_seal nonce length (Blake2b output)

    /**
     * libsodium crypto_box_seal nonce derivation: unkeyed Blake2b with a
     * 24-byte digest over (ephemeral public key || recipient public key).
     * Internal rather than private so the JVM unit test can pin it against
     * the reference (libsodium / hashlib.blake2b) test vector.
     */
    internal fun sealedBoxNonce(ephemeralPublicKey: ByteArray, recipientPublicKey: ByteArray): ByteArray {
        val digest = Blake2bDigest(SEAL_NONCE_BYTES * 8)
        digest.update(ephemeralPublicKey, 0, ephemeralPublicKey.size)
        digest.update(recipientPublicKey, 0, recipientPublicKey.size)
        val nonce = ByteArray(SEAL_NONCE_BYTES)
        digest.doFinal(nonce, 0)
        return nonce
    }

    private fun sealedBox(message: ByteArray, recipientPublicKey: ByteArray): ByteArray {
        require(recipientPublicKey.size == 32) { "Invalid GitHub repository public key." }
        val random = SecureRandom()

        // Ephemeral X25519 keypair.
        val esk = ByteArray(32)
        X25519.generatePrivateKey(random, esk)
        val epk = ByteArray(32)
        X25519.generatePublicKey(esk, 0, epk, 0)

        // crypto_box_beforenm: k = HSalsa20(X25519(esk, pk), zeros(16)).
        val shared = ByteArray(32)
        if (!X25519.calculateAgreement(esk, 0, recipientPublicKey, 0, shared, 0)) {
            throw IllegalStateException("X25519 agreement failed.")
        }
        val beforenm = hsalsa20(shared, ByteArray(16))

        // crypto_box_seal nonce: Blake2b-24(epk || recipient pk), exactly as
        // libsodium derives it. (A zero nonce is NOT the sealed-box
        // construction: GitHub derives the Blake2b nonce when opening the
        // box, so a zero nonce fails its Poly1305 check — that is why every
        // secret push was rejected with HTTP 422.)
        val nonce = sealedBoxNonce(epk, recipientPublicKey)
        val xsalsa = XSalsa20Engine()
        xsalsa.init(true, ParametersWithIV(KeyParameter(beforenm), nonce))

        // First 32 keystream bytes are the Poly1305 one-time key; the message
        // keystream continues from byte 32.
        val polyKey = ByteArray(32)
        xsalsa.processBytes(ByteArray(32), 0, 32, polyKey, 0)
        val ciphertext = ByteArray(message.size)
        xsalsa.processBytes(message, 0, message.size, ciphertext, 0)

        val poly = Poly1305()
        poly.init(KeyParameter(polyKey))
        poly.update(ciphertext, 0, ciphertext.size)
        val tag = ByteArray(poly.macSize)
        poly.doFinal(tag, 0)

        esk.wipe()
        shared.wipe()
        beforenm.wipe()
        polyKey.wipe()
        return epk + tag + ciphertext
    }

    /**
     * HSalsa20: the Salsa20 core (20 rounds) without feed-forward, outputting
     * words (0, 5, 10, 15, 6, 7, 8, 9) as 32 bytes.
     */
    // Internal rather than private so the JVM unit test can pin it against
    // libsodium's crypto_box_beforenm test vector.
    internal fun hsalsa20(key: ByteArray, input16: ByteArray): ByteArray {
        require(key.size == 32 && input16.size == 16)
        val x = IntArray(16)
        // Canonical Salsa20 state layout: the sigma constants sit on the
        // diagonal (words 0, 5, 10, 15), the key occupies words 1,2,3,4 and
        // 11,12,13,14, and the 16-byte input occupies words 6,7,8,9.
        // (Filling constants/key/input into words 0-3 / 4-11 / 12-15 — a
        // ChaCha-style fill — produces a different permutation and was the
        // second reason GitHub rejected every sealed secret with HTTP 422.)
        x[0] = 0x61707865
        x[5] = 0x3320646e
        x[10] = 0x79622d32
        x[15] = 0x6b206574
        x[1] = leDecode(key, 0)
        x[2] = leDecode(key, 4)
        x[3] = leDecode(key, 8)
        x[4] = leDecode(key, 12)
        x[11] = leDecode(key, 16)
        x[12] = leDecode(key, 20)
        x[13] = leDecode(key, 24)
        x[14] = leDecode(key, 28)
        x[6] = leDecode(input16, 0)
        x[7] = leDecode(input16, 4)
        x[8] = leDecode(input16, 8)
        x[9] = leDecode(input16, 12)

        repeat(10) {
            qr(x, 0, 4, 8, 12)
            qr(x, 5, 9, 13, 1)
            qr(x, 10, 14, 2, 6)
            qr(x, 15, 3, 7, 11)
            qr(x, 0, 1, 2, 3)
            qr(x, 5, 6, 7, 4)
            qr(x, 10, 11, 8, 9)
            qr(x, 15, 12, 13, 14)
        }

        val out = ByteArray(32)
        val sel = intArrayOf(x[0], x[5], x[10], x[15], x[6], x[7], x[8], x[9])
        for (i in sel.indices) leEncode(sel[i], out, i * 4)
        return out
    }

    private fun qr(y: IntArray, a: Int, b: Int, c: Int, d: Int) {
        y[b] = y[b] xor rotl(y[a] + y[d], 7)
        y[c] = y[c] xor rotl(y[b] + y[a], 9)
        y[d] = y[d] xor rotl(y[c] + y[b], 13)
        y[a] = y[a] xor rotl(y[d] + y[c], 18)
    }

    private fun rotl(v: Int, n: Int): Int = (v shl n) or (v ushr (32 - n))

    private fun leDecode(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xff) or
            ((b[off + 1].toInt() and 0xff) shl 8) or
            ((b[off + 2].toInt() and 0xff) shl 16) or
            ((b[off + 3].toInt() and 0xff) shl 24)

    private fun leEncode(v: Int, b: ByteArray, off: Int) {
        b[off] = v.toByte()
        b[off + 1] = (v ushr 8).toByte()
        b[off + 2] = (v ushr 16).toByte()
        b[off + 3] = (v ushr 24).toByte()
    }

    private data class RepoPublicKey(val keyId: String, val keyBase64: String)

    private fun repoPublicKey(owner: String, repo: String, githubToken: String): RepoPublicKey {
        val req = Request.Builder()
            .url("https://api.github.com/repos/$owner/$repo/actions/secrets/public-key")
            .header("Authorization", "Bearer $githubToken")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .get()
            .build()
        http.newCall(req).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("Could not read repository public key (HTTP ${response.code}). Check owner/repo and token scopes.")
            val json = JSONObject(text)
            return RepoPublicKey(json.getString("key_id"), json.getString("key"))
        }
    }

    /**
     * Pushes one Actions secret. The plaintext is sealed in memory and never
     * logged; callers should zero any [CharArray] passwords afterwards.
     */
    suspend fun pushSecret(
        owner: String,
        repo: String,
        name: String,
        plaintext: ByteArray,
        githubToken: String,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(name.matches(Regex("[A-Z0-9_]{1,64}"))) { "Invalid secret name." }
            val pub = repoPublicKey(owner, repo, githubToken)
            val sealed = sealedBox(plaintext, Base64.decode(pub.keyBase64, Base64.DEFAULT))
            val body = JSONObject()
                .put("encrypted_value", Base64.encodeToString(sealed, Base64.NO_WRAP))
                .put("key_id", pub.keyId)
            val req = Request.Builder()
                .url("https://api.github.com/repos/$owner/$repo/actions/secrets/$name")
                .header("Authorization", "Bearer $githubToken")
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .put(body.toString().toRequestBody(jsonType))
                .build()
            http.newCall(req).execute().use { response ->
                if (response.code != 201 && response.code != 204) {
                    error("GitHub rejected secret $name (HTTP ${response.code}).")
                }
            }
        }
    }

    /**
     * Pushes the four secrets a Gradle signing workflow needs. Passwords are
     * taken as [CharArray] and never converted to [String].
     */
    suspend fun pushSigningSecrets(
        owner: String,
        repo: String,
        pkcs12Bytes: ByteArray,
        alias: String,
        storePassword: CharArray,
        keyPassword: CharArray,
        githubToken: String,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val secrets = listOf(
                "KEYSTORE_BASE64" to pkcs12Base64(pkcs12Bytes).toByteArray(Charsets.UTF_8),
                "KEYSTORE_PASSWORD" to storePassword.toUtf8Bytes(),
                "KEY_ALIAS" to alias.toByteArray(Charsets.UTF_8),
                "KEY_PASSWORD" to keyPassword.toUtf8Bytes(),
            )
            for ((name, value) in secrets) {
                pushSecret(owner, repo, name, value, githubToken).getOrThrow()
            }
        }
    }
}

/** Zeroes a password array after use. */
fun CharArray.wipe() { this.fill('\u0000') }

/** Zero out a byte array holding secret material. */
fun ByteArray.wipe() { this.fill(0) }

/** UTF-8 bytes of a password without creating an intermediate [String]. */
fun CharArray.toUtf8Bytes(): ByteArray {
    val buf = Charsets.UTF_8.encode(java.nio.CharBuffer.wrap(this))
    val out = ByteArray(buf.remaining())
    buf.get(out)
    return out
}
