package com.anvex.core.android.signing

import android.util.Base64
import com.goterl.lazysodium.SodiumAndroid
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

    private const val SEAL_BYTES = 48 // crypto_box_SEALBYTES: 32 (epk) + 16 (MAC)

    private fun sealedBox(message: ByteArray, recipientPublicKey: ByteArray): ByteArray {
        require(recipientPublicKey.size == 32) { "Invalid GitHub repository public key." }
        val sodium = SodiumAndroid() // loads libsodium native library
        val cipher = ByteArray(SEAL_BYTES + message.size)
        val rc = sodium.cryptoBoxSeal(cipher, message, message.size.toLong(), recipientPublicKey)
        require(rc == 0) { "Sealed-box encryption failed." }
        return cipher
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

/** UTF-8 bytes of a password without creating an intermediate [String]. */
fun CharArray.toUtf8Bytes(): ByteArray {
    val buf = Charsets.UTF_8.encode(java.nio.CharBuffer.wrap(this))
    val out = ByteArray(buf.remaining())
    buf.get(out)
    return out
}
