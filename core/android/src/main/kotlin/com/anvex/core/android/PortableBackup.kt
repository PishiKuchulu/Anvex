package com.anvex.core.android

import java.util.Base64
import com.anvex.core.android.repo.ProviderRepository
import com.anvex.core.android.repo.SettingsRepository
import com.anvex.core.model.ProviderConfig
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Password-portable encrypted backup. API keys are decrypted only in memory, then encrypted by the backup password. */
class PortableBackup(private val providers: ProviderRepository, private val settings: SettingsRepository) {
    @Serializable data class BackupProvider(val config: ProviderConfig, val apiKey: String = "")
    @Serializable data class Payload(val version: Int = 2, val settings: String, val providers: List<BackupProvider>)
    @Serializable data class Envelope(val version: Int = 1, val salt: String, val iv: String, val ciphertext: String, val iterations: Int = 600_000)
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    suspend fun export(password: CharArray): String {
        require(password.size >= 8) { "Backup password must be at least 8 characters." }
        val s = json.encodeToString(settings.settings.first())
        val ps = providers.observeAll().first().map { cfg ->
            val runtime = providers.runtimeConfig(cfg.id)
            if (cfg.apiKeyEnc != null && runtime == null) {
                error("Stored API key for provider '${cfg.name}' cannot be decrypted on this installation. Re-enter the API key before exporting.")
            }
            BackupProvider(cfg.copy(apiKeyEnc = null), runtime?.apiKey ?: "")
        }
        return encrypt(json.encodeToString(Payload(settings = s, providers = ps)), password)
    }
    fun encrypt(plain: String, password: CharArray): String {
        val random = SecureRandom(); val salt = ByteArray(16); val iv = ByteArray(12); random.nextBytes(salt); random.nextBytes(iv)
        val key = derive(password, salt, 600_000); val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, key, javax.crypto.spec.GCMParameterSpec(128, iv))
        return json.encodeToString(Envelope(salt = b64(salt), iv = b64(iv), ciphertext = b64(cipher.doFinal(plain.toByteArray(Charsets.UTF_8)))))
    }
    fun decrypt(encoded: String, password: CharArray): Payload {
        require(encoded.length <= 64 * 1024 * 1024) { "Backup envelope is too large." }
        require(password.size >= 8) { "Backup password must be at least 8 characters." }
        val env = json.decodeFromString<Envelope>(encoded)
        require(env.version == 1) { "Unsupported backup envelope version." }
        require(env.iterations in 100_000..1_000_000) { "Invalid backup KDF parameters." }
        val salt = Base64.getMimeDecoder().decode(env.salt)
        val iv = Base64.getMimeDecoder().decode(env.iv)
        val ciphertext = Base64.getMimeDecoder().decode(env.ciphertext)
        require(salt.size == 16 && iv.size == 12) { "Invalid backup envelope parameters." }
        require(ciphertext.isNotEmpty() && ciphertext.size <= 48 * 1024 * 1024) { "Backup ciphertext exceeds safety limits." }
        val key = derive(password, salt, env.iterations)
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(javax.crypto.Cipher.DECRYPT_MODE, key, javax.crypto.spec.GCMParameterSpec(128, iv))
        return json.decodeFromString(String(cipher.doFinal(ciphertext), Charsets.UTF_8))
    }

    /**
     * Transaction-like restore across Room + DataStore with in-memory rollback.
     * Returns the providers skipped during restore (M-08: per-item restore) so
     * the UI can tell the user exactly which providers need re-adding.
     */
    suspend fun restore(payload: Payload): List<String> {
        require(payload.version in 1..2) { "Unsupported backup payload version." }
        val oldSettings = json.encodeToString(settings.settings.first())
        val oldProviders = providers.snapshotEntities()
        try {
            val skipped = providers.replaceAllForRestore(payload.providers)
            settings.restoreFromBackup(payload.settings)
            return skipped
        } catch (t: Throwable) {
            runCatching { providers.restoreEntities(oldProviders) }
            runCatching { settings.restoreFromBackup(oldSettings) }
            throw t
        }
    }

    companion object {
        fun encryptBytes(bytes: ByteArray, password: CharArray): String {
            require(password.size >= 8) { "Backup password must be at least 8 characters." }
            val random = SecureRandom(); val salt = ByteArray(16); val iv = ByteArray(12)
            random.nextBytes(salt); random.nextBytes(iv)
            val key = deriveStatic(password, salt, 600_000)
            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, key, javax.crypto.spec.GCMParameterSpec(128, iv))
            val ct = cipher.doFinal(bytes)
            return Json { encodeDefaults = true }.encodeToString(Envelope(salt = Base64.getEncoder().encodeToString(salt), iv = Base64.getEncoder().encodeToString(iv), ciphertext = Base64.getEncoder().encodeToString(ct)))
        }
        fun decryptBytes(encoded: String, password: CharArray): ByteArray {
            require(encoded.length <= 64 * 1024 * 1024) { "Backup envelope is too large." }
            val env = Json { ignoreUnknownKeys = true }.decodeFromString<Envelope>(encoded)
            require(env.version == 1) { "Unsupported backup envelope version." }
            require(env.iterations in 100_000..1_000_000) { "Invalid backup KDF parameters." }
            require(password.size >= 8) { "Backup password must be at least 8 characters." }
            val salt = Base64.getMimeDecoder().decode(env.salt)
            val iv = Base64.getMimeDecoder().decode(env.iv)
            val ciphertext = Base64.getMimeDecoder().decode(env.ciphertext)
            require(salt.size == 16 && iv.size == 12) { "Invalid backup envelope parameters." }
            require(ciphertext.isNotEmpty() && ciphertext.size <= 48 * 1024 * 1024) { "Backup ciphertext exceeds safety limits." }
            val key = deriveStatic(password, salt, env.iterations)
            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, key, javax.crypto.spec.GCMParameterSpec(128, iv))
            return cipher.doFinal(ciphertext)
        }
        private fun deriveStatic(password: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec =
            SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(password, salt, iterations.coerceIn(100_000, 1_000_000), 256)).encoded, "AES")
    }

    private fun derive(password: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec = SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(password, salt, iterations, 256)).encoded, "AES")
    private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)
}
