package com.anvex.core.android.repo

import com.anvex.core.android.KeystoreCrypto
import com.anvex.core.android.db.ProviderDao
import com.anvex.core.android.db.ProviderEntity
import com.anvex.core.ai.ProviderRuntimeConfig
import com.anvex.core.model.ProviderConfig
import com.anvex.core.model.ProviderKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import java.net.URI
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Provider CRUD with Keystore-encrypted API keys (brief §12/§13).
 * Decryption happens ONLY here, on demand, into in-memory runtime configs.
 */
class ProviderRepository(private val dao: ProviderDao) {

    private val json = Json { ignoreUnknownKeys = true }

    fun observeAll(): Flow<List<ProviderConfig>> = dao.observeAll().map { list ->
        list.mapNotNull { it.toDomain() }
    }

    suspend fun get(id: String): ProviderConfig? = dao.get(id)?.toDomain()

    suspend fun getDefault(): ProviderConfig? {
        val def = dao.getDefault()?.toDomain()
        return def ?: firstProvider()
    }

    private suspend fun firstProvider(): ProviderConfig? =
        dao.observeAll().firstOrNull()?.firstOrNull()?.toDomain()

    /** Save; returns id. Encrypts the key if provided; blank key keeps existing. */
    suspend fun save(config: ProviderConfig, apiKeyPlain: String?): RepoResult<ProviderConfig> {
        if (config.name.isBlank()) return RepoResult.Err("Invalid name", "Provider name cannot be empty.")
        if (config.kind == ProviderKind.CUSTOM_OPENAI || config.kind == ProviderKind.CUSTOM_ANTHROPIC) {
            if (config.baseUrl.isBlank()) {
                return RepoResult.Err("Missing base URL", "Custom providers need an API base URL.")
            }
        }
        if (config.model.isBlank()) {
            return RepoResult.Err("Missing model", "Select or enter a model identifier.")
        }
        validateBaseUrl(config.baseUrl)?.let {
            return RepoResult.Err("Invalid base URL", it)
        }
        val enc = when {
            apiKeyPlain != null && apiKeyPlain.isNotBlank() -> try {
                KeystoreCrypto.encrypt(apiKeyPlain.trim())
            } catch (e: KeystoreCrypto.CryptoException) {
                return RepoResult.Err("Secure storage failed", e.message ?: "Android Keystore unavailable.")
            }
            else -> config.apiKeyEnc ?: dao.get(config.id)?.apiKeyEnc
        }
        val final = config.copy(apiKeyEnc = enc)
        val entity = try { final.toEntity() } catch (e: Exception) {
            return RepoResult.Err("Secure storage failed", e.message ?: "Provider headers could not be encrypted.")
        }
        if (final.isDefault) dao.clearDefault()
        dao.upsert(entity)
        // First provider ever saved becomes the default automatically.
        if (dao.getDefault() == null) {
            dao.clearDefault()
            dao.get(final.id)?.let { dao.update(it.copy(isDefault = true)) }
        }
        return RepoResult.Ok(final)
    }

    suspend fun setDefault(id: String) {
        dao.clearDefault()
        dao.get(id)?.let { dao.update(it.copy(isDefault = true)) }
    }

    suspend fun delete(id: String) = dao.delete(id)


    /** Cleartext HTTP is restricted to loopback/private/local provider endpoints. */
    private fun validateBaseUrl(value: String): String? {
        val uri = runCatching { URI(value.trim()) }.getOrNull() ?: return "Base URL is not a valid URI."
        val scheme = uri.scheme?.lowercase() ?: return "Base URL must include http:// or https://."
        val host = uri.host?.lowercase() ?: return "Base URL must include a hostname."
        if (uri.userInfo != null) return "Credentials must not be embedded in the provider URL."
        if (scheme == "https") return null
        if (scheme != "http") return "Only HTTP and HTTPS base URLs are supported."
        // v4.10.1 (ARCH-02): validation used to accept cleartext for ANY
        // private/LAN address, but the platform network-security config only
        // permits cleartext to loopback — a LAN Ollama URL passed validation
        // and then died at request time with "CLEARTEXT not permitted".
        // Validation now matches the platform exactly and says why.
        val loopback = host == "localhost" || isLoopbackLiteral(host)
        if (loopback) return null
        val lan = host.endsWith(".local") || isPrivateAddressLiteral(host)
        return if (lan) {
            "Android blocks cleartext HTTP to LAN addresses for this app, so a LAN endpoint cannot be used over http://. " +
                "Serve it over HTTPS, or reach it through a tunnel (e.g. Tailscale) with an https:// URL."
        } else {
            "Cleartext HTTP is allowed only for loopback provider endpoints. Use HTTPS for remote providers."
        }
    }

    /** Literal loopback check (127.0.0.0/8 or ::1) without DNS resolution. */
    private fun isLoopbackLiteral(host: String): Boolean = runCatching {
        val address = java.net.InetAddress.getByName(host)
        (host.matches(Regex("\\d{1,3}(?:\\.\\d{1,3}){3}")) || host.contains(':')) && address.isLoopbackAddress
    }.getOrDefault(false)

    /** Check only literal IP addresses; never DNS-resolve arbitrary hostnames during validation. */
    private fun isPrivateAddressLiteral(host: String): Boolean {
        val looksLikeIpv4 = host.matches(Regex("\\d{1,3}(?:\\.\\d{1,3}){3}"))
        val looksLikeIpv6 = host.contains(':')
        if (!looksLikeIpv4 && !looksLikeIpv6) return false
        return runCatching {
            val address = java.net.InetAddress.getByName(host)
            address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress ||
                (address is java.net.Inet6Address && address.address.firstOrNull()?.toInt()?.and(0xff)?.let { it and 0xfe == 0xfc } == true)
        }.getOrDefault(false)
    }

    /** One-time lazy migration for pre-v7 plaintext header JSON. Never logs header values. */
    suspend fun migrateLegacyHeaders(): Int {
        var migrated = 0
        dao.observeAll().firstOrNull().orEmpty().forEach { entity ->
            if (entity.extraHeadersEnc.isNullOrBlank() && entity.extraHeadersJson.isNotBlank() && entity.extraHeadersJson != "{}") {
                val encoded = try { KeystoreCrypto.encrypt(entity.extraHeadersJson) } catch (_: Exception) { return@forEach }
                dao.update(entity.copy(extraHeadersJson = "{}", extraHeadersEnc = encoded))
                migrated++
            }
        }
        return migrated
    }

    suspend fun importBackupProvider(item: com.anvex.core.android.PortableBackup.BackupProvider) {
        val cfg = item.config.copy(apiKeyEnc = null)
        save(cfg, item.apiKey)
    }

    /** Snapshot encrypted database rows for rollback; never exposes plaintext secrets. */
    internal suspend fun snapshotEntities(): List<ProviderEntity> = dao.all()

    /** Restore encrypted rows captured by [snapshotEntities]. */
    internal suspend fun restoreEntities(snapshot: List<ProviderEntity>) {
        dao.clearAll()
        snapshot.forEach { dao.upsert(it) }
    }

    /** Replace all providers as one logical restore operation; plaintext keys exist only in memory. */
    suspend fun replaceAllForRestore(items: List<com.anvex.core.android.PortableBackup.BackupProvider>) {
        require(items.size <= MAX_RESTORE_PROVIDERS) { "Backup contains too many providers." }
        val previous = snapshotEntities()
        try {
            dao.clearAll()
            items.forEach { item ->
                val result = save(item.config.copy(apiKeyEnc = null), item.apiKey)
                if (result is RepoResult.Err) error("Provider restore failed: ${result.detail}")
            }
        } catch (t: Throwable) {
            runCatching { restoreEntities(previous) }
            throw t
        }
    }

    companion object { private const val MAX_RESTORE_PROVIDERS = 100 }

    /**
     * Build a runtime config with the decrypted key (memory only).
     * A stored ciphertext that cannot be decrypted is reported as unavailable
     * instead of silently becoming an empty API key.
     */
    suspend fun runtimeConfig(id: String): ProviderRuntimeConfig? {
        val config = get(id) ?: return null
        val encoded = config.apiKeyEnc
        val key = if (encoded.isNullOrBlank()) {
            ""
        } else {
            try {
                KeystoreCrypto.decrypt(encoded)
            } catch (_: KeystoreCrypto.CryptoException) {
                return null
            }
        }
        return ProviderRuntimeConfig(config, key)
    }

    /** Returns true when a persisted API-key ciphertext exists but cannot be decrypted. */
    suspend fun hasCredentialDecryptionFailure(id: String): Boolean {
        val encoded = dao.get(id)?.apiKeyEnc
        if (encoded.isNullOrBlank()) return false
        return runCatching { KeystoreCrypto.decrypt(encoded); false }
            .getOrElse { true }
    }

    private fun ProviderEntity.toDomain(): ProviderConfig? = try {
        ProviderConfig(
            id = id,
            name = name,
            kind = ProviderKind.valueOf(kind),
            apiKeyEnc = apiKeyEnc,
            baseUrl = baseUrl,
            model = model,
            extraHeaders = runCatching {
                val encoded = extraHeadersEnc
                // Never fall back to legacy plaintext headers at runtime. The startup
                // migration must encrypt them first; otherwise the provider is treated
                // as unavailable instead of rehydrating credentials from plaintext DB data.
                require(!encoded.isNullOrBlank() || extraHeadersJson == "{}") { "Legacy plaintext provider headers require secure migration." }
                val raw = if (!encoded.isNullOrBlank()) KeystoreCrypto.decrypt(encoded) else "{}"
                json.decodeFromString<Map<String, String>>(raw.ifBlank { "{}" })
            }.getOrElse { return null },
            temperature = temperature,
            maxTokens = maxTokens,
            inputPricePerMillion = inputPricePerMillion,
            outputPricePerMillion = outputPricePerMillion,
            isDefault = isDefault,
            createdAt = createdAt,
        )
    } catch (_: Exception) {
        null
    }

    private fun ProviderConfig.toEntity() = ProviderEntity(
        id = id,
        name = name,
        kind = kind.name,
        apiKeyEnc = apiKeyEnc,
        baseUrl = baseUrl,
        model = model,
        extraHeadersJson = "{}",
        extraHeadersEnc = runCatching { KeystoreCrypto.encrypt(json.encodeToString(extraHeaders)) }.getOrElse { throw it },
        temperature = temperature,
        maxTokens = maxTokens,
        inputPricePerMillion = inputPricePerMillion,
        outputPricePerMillion = outputPricePerMillion,
        isDefault = isDefault,
        createdAt = createdAt,
    )
}
