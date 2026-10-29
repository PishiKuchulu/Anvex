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

    /**
     * M-08: rows [observeAll] silently drops (decrypt failure, bad enum,
     * corrupt JSON). They are still in the database — the UI shows them
     * as broken instead of pretending they do not exist.
     */
    data class BrokenProvider(val id: String, val name: String, val reason: String)

    fun observeBroken(): Flow<List<BrokenProvider>> = dao.observeAll().map { list ->
        list.mapNotNull { entity ->
            if (entity.toDomain() != null) null else BrokenProvider(
                id = entity.id,
                name = entity.name,
                reason = brokenReason(entity),
            )
        }
    }

    private fun ProviderEntity.brokenReason(): String {
        if (runCatching { ProviderKind.valueOf(kind) }.isFailure) return "unknown provider kind"
        if (!extraHeadersEnc.isNullOrBlank() && runCatching {
                decryptHeaders(extraHeadersEnc, id)
            }.isFailure) return "headers cannot be decrypted"
        if (!extraHeadersJson.isNullOrBlank() && extraHeadersJson != "{}" &&
            runCatching { json.decodeFromString<Map<String, String>>(extraHeadersJson) }.isFailure
        ) return "corrupt stored data"
        return "stored data cannot be read"
    }

    /**
     * M-15: GCM AAD binding a provider key to its row. If an attacker (or
     * a corrupt sync) swaps baseUrl/model/id in the database, decryption
     * fails instead of sending the real key to the attacker's endpoint.
     * Version-tagged so the format can evolve without breaking old rows.
     */
    private fun providerKeyAad(id: String, baseUrl: String, model: String): ByteArray =
        "anvex-provider-v1|$id|$baseUrl|$model".toByteArray(Charsets.UTF_8)

    /** Headers are bound to the provider id only (they carry no URL/model). */
    private fun providerHeadersAad(id: String): ByteArray =
        "anvex-provider-headers-v1|$id".toByteArray(Charsets.UTF_8)

    /** AAD form first, legacy no-AAD form second (transparent migration). */
    private fun decryptHeaders(encoded: String, providerId: String): String = try {
        KeystoreCrypto.decrypt(encoded, providerHeadersAad(providerId))
    } catch (_: KeystoreCrypto.CryptoException) {
        KeystoreCrypto.decrypt(encoded)
    }

    /** AAD form first, legacy form second. Throws when neither works. */
    private fun decryptApiKey(encoded: String, id: String, baseUrl: String, model: String): String = try {
        KeystoreCrypto.decrypt(encoded, providerKeyAad(id, baseUrl, model))
    } catch (_: KeystoreCrypto.CryptoException) {
        KeystoreCrypto.decrypt(encoded)
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
        // 15.7: same allow-list at save time, so a tampered restore or a
        // manual edit cannot even store an off-list Z.ai endpoint.
        if (config.kind == ProviderKind.ZAI_ACCOUNT && config.baseUrl.isNotBlank()) {
            val host = runCatching { URI(config.baseUrl.trim()).host?.lowercase() }.getOrNull()
            if (host != "api.z.ai") {
                return RepoResult.Err(
                    "Invalid base URL",
                    "Z.ai account providers may only use https://api.z.ai — the account token is not valid anywhere else.",
                )
            }
        }
        val stored = dao.get(config.id)
        val enc = when {
            apiKeyPlain != null && apiKeyPlain.isNotBlank() -> try {
                KeystoreCrypto.encrypt(apiKeyPlain.trim(), providerKeyAad(config.id, config.baseUrl, config.model))
            } catch (e: KeystoreCrypto.CryptoException) {
                return RepoResult.Err("Secure storage failed", e.message ?: "Android Keystore unavailable.")
            }
            else -> config.apiKeyEnc ?: stored?.apiKeyEnc
        }
        // AAD re-bind on edit-without-key-change: a ciphertext bound to the
        // OLD baseUrl/model would fail authentication after the user edits
        // those fields without re-entering the key. Re-encrypt the kept key
        // under the new AAD when it decrypts; on failure keep the stored
        // ciphertext untouched (the provider then surfaces its usual
        // decrypt warning instead of silently losing the key).
        val finalEnc = if (apiKeyPlain.isNullOrBlank() && !enc.isNullOrBlank() && stored != null &&
            (stored.baseUrl != config.baseUrl || stored.model != config.model)
        ) {
            runCatching {
                val plain = decryptApiKey(enc, stored.id, stored.baseUrl, stored.model)
                KeystoreCrypto.encrypt(plain, providerKeyAad(config.id, config.baseUrl, config.model))
            }.getOrDefault(enc)
        } else enc
        val final = config.copy(apiKeyEnc = finalEnc)
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
                val encoded = try { KeystoreCrypto.encrypt(entity.extraHeadersJson, providerHeadersAad(entity.id)) } catch (_: Exception) { return@forEach }
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

    /**
     * M-08: restore is per-item, not all-or-nothing. One legacy URL that
     * current validation rejects (e.g. cleartext http://192.168.x.x from
     * an older backup) used to fail the WHOLE restore. Now valid
     * providers are saved and the rest are reported by name; only a
     * total failure (nothing restorable) still rolls back and throws,
     * so a bad backup can never wipe the working set.
     * Returns "name: reason" lines for every skipped provider.
     */
    suspend fun replaceAllForRestore(items: List<com.anvex.core.android.PortableBackup.BackupProvider>): List<String> {
        require(items.size <= MAX_RESTORE_PROVIDERS) { "Backup contains too many providers." }
        val previous = snapshotEntities()
        dao.clearAll()
        val skipped = ArrayList<String>()
        var saved = 0
        items.forEach { item ->
            val result = runCatching { save(item.config.copy(apiKeyEnc = null), item.apiKey) }
                .getOrElse { RepoResult.Err("Restore failed", it.message ?: "Unknown error.") }
            if (result is RepoResult.Err) {
                skipped += "${item.config.name}: ${result.detail}"
            } else {
                saved++
            }
        }
        if (items.isNotEmpty() && saved == 0) {
            runCatching { restoreEntities(previous) }
            error("Provider restore failed for all ${items.size} provider(s): ${skipped.joinToString("; ")}")
        }
        return skipped
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
                decryptApiKey(encoded, config.id, config.baseUrl, config.model)
            } catch (_: KeystoreCrypto.CryptoException) {
                return null
            }
        }
        // Transparent AAD migration: a legacy (no-AAD) ciphertext that
        // still decrypts is re-encrypted bound to this row, so the next
        // read uses the AAD form. Best-effort — a failed rewrite never
        // blocks the chat; the legacy form still works next time.
        if (!encoded.isNullOrBlank()) {
            val aadWorks = runCatching {
                KeystoreCrypto.decrypt(encoded, providerKeyAad(config.id, config.baseUrl, config.model)); true
            }.getOrDefault(false)
            if (!aadWorks) {
                runCatching {
                    val reEnc = KeystoreCrypto.encrypt(key, providerKeyAad(config.id, config.baseUrl, config.model))
                    dao.get(id)?.let { dao.update(it.copy(apiKeyEnc = reEnc)) }
                }
            }
        }
        return ProviderRuntimeConfig(config, key)
    }

    /** Returns true when a persisted API-key ciphertext exists but cannot be decrypted. */
    suspend fun hasCredentialDecryptionFailure(id: String): Boolean {
        val entity = dao.get(id) ?: return false
        val encoded = entity.apiKeyEnc
        if (encoded.isNullOrBlank()) return false
        return runCatching { decryptApiKey(encoded, entity.id, entity.baseUrl, entity.model); false }
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
                val raw = if (!encoded.isNullOrBlank()) decryptHeaders(encoded, id) else "{}"
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
        extraHeadersEnc = runCatching { KeystoreCrypto.encrypt(json.encodeToString(extraHeaders), providerHeadersAad(id)) }.getOrElse { throw it },
        temperature = temperature,
        maxTokens = maxTokens,
        inputPricePerMillion = inputPricePerMillion,
        outputPricePerMillion = outputPricePerMillion,
        isDefault = isDefault,
        createdAt = createdAt,
    )
}
